from __future__ import annotations

import os
import sqlite3
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app
from app.worker import cleanup_expired, run_once


PNG = b"\x89PNG\r\n\x1a\n" + b"test-image"


class StubAdapter:
    def analyze(self, path: Path, progress):
        assert path.read_bytes() == b"video-content"
        progress("EXTRACTING_FACES")
        return {
            "spatial": 0.91,
            "temporal": 0.49,
            "fused": 0.70,
            "n_faces": 12,
            "family": "spatial",
            "faithfulness": "stub",
            "metadata": {"duration_seconds": 1.0},
            "model_version": "stub-v1",
            "calibration_version": "stub-cal-v1",
            "artifacts": {"heatmap": PNG},
            "warnings": [],
            "is_demo": True,
        }


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        db_path=tmp_path / "data" / "test.sqlite3",
        storage_dir=tmp_path / "storage",
        model_dir=tmp_path / "models",
        inference_mode="demo",
        max_upload_bytes=32,
        retention_hours=1,
        job_timeout_seconds=60,
        max_queued_per_session=1,
        session_ttl_hours=1,
    )


@pytest.fixture
def client(settings: Settings):
    with TestClient(create_app(settings)) as test_client:
        yield test_client


def session(client: TestClient) -> tuple[str, dict[str, str]]:
    response = client.post("/api/v1/sessions")
    assert response.status_code == 201
    body = response.json()
    return body["session_id"], {"Authorization": f"Bearer {body['token']}"}


def queue(client: TestClient, headers: dict[str, str], content: bytes = b"video-content"):
    return client.post(
        "/api/v1/analyses",
        headers=headers,
        files={"video": ("unsafe name.mp4", content, "video/mp4")},
    )


def test_queue_worker_result_artifact_and_delete(client: TestClient, settings: Settings):
    _, headers = session(client)
    queued = queue(client, headers)
    assert queued.status_code == 202
    analysis_id = queued.json()["id"]
    assert queued.json()["status"] == "QUEUED"

    assert run_once(settings, adapter=StubAdapter()) is True
    detail = client.get(f"/api/v1/analyses/{analysis_id}", headers=headers)
    assert detail.status_code == 200
    body = detail.json()
    assert body["status"] == "COMPLETED"
    assert body["result"]["policy"]["verdict"] == "FAKE"
    assert "artifacts" not in body["result"]
    assert body["artifacts"][0]["kind"] == "heatmap"

    artifact = client.get(body["artifacts"][0]["url"], headers=headers)
    assert artifact.status_code == 200
    assert artifact.content == PNG
    assert artifact.headers["content-type"].startswith("image/png")

    deleted = client.delete(f"/api/v1/analyses/{analysis_id}", headers=headers)
    assert deleted.status_code == 204
    assert client.get(f"/api/v1/analyses/{analysis_id}", headers=headers).status_code == 404
    assert not list(settings.storage_dir.rglob("*.png"))


def test_owner_isolation_and_auth_errors(client: TestClient):
    _, first = session(client)
    _, second = session(client)
    queued = queue(client, first)
    analysis_id = queued.json()["id"]

    hidden = client.get(f"/api/v1/analyses/{analysis_id}", headers=second)
    assert hidden.status_code == 404
    assert hidden.json()["error"]["code"] == "ANALYSIS_NOT_FOUND"
    missing = client.get("/api/v1/analyses")
    assert missing.status_code == 401
    assert missing.json()["error"]["code"] == "AUTH_REQUIRED"


def test_upload_limits_suffix_queue_bound_and_terminal_delete(client: TestClient):
    _, headers = session(client)
    bad_type = client.post(
        "/api/v1/analyses",
        headers=headers,
        files={"video": ("payload.exe", b"small", "application/octet-stream")},
    )
    assert bad_type.status_code == 400
    assert bad_type.json()["error"]["code"] == "UNSUPPORTED_VIDEO_TYPE"

    too_large = queue(client, headers, b"x" * 33)
    assert too_large.status_code == 413
    assert too_large.json()["error"]["code"] == "UPLOAD_TOO_LARGE"
    assert not list((client.app.state.settings.storage_dir / "sources").glob("*"))

    queued = queue(client, headers)
    assert queued.status_code == 202
    bounded = queue(client, headers)
    assert bounded.status_code == 429
    assert bounded.json()["error"]["code"] == "QUEUE_LIMIT_REACHED"
    not_terminal = client.delete(f"/api/v1/analyses/{queued.json()['id']}", headers=headers)
    assert not_terminal.status_code == 409
    assert not_terminal.json()["error"]["code"] == "ANALYSIS_NOT_TERMINAL"


def test_worker_failure_deletes_raw_video(client: TestClient, settings: Settings):
    class FailedAdapter:
        def analyze(self, path, progress):
            raise RuntimeError("boom")

    _, headers = session(client)
    analysis_id = queue(client, headers).json()["id"]
    assert run_once(settings, adapter=FailedAdapter()) is True
    detail = client.get(f"/api/v1/analyses/{analysis_id}", headers=headers).json()
    assert detail["status"] == "FAILED"
    assert detail["error"]["code"] == "ANALYSIS_FAILED"
    assert detail["error"]["message"] == "Analysis failed unexpectedly"
    assert "boom" not in str(detail["error"])
    with sqlite3.connect(settings.db_path) as connection:
        source_path = Path(
            connection.execute("SELECT source_path FROM analyses WHERE id=?", (analysis_id,)).fetchone()[0]
        )
    assert not source_path.exists()


def test_expired_results_hidden_and_cleanup_removes_aged_orphans(client: TestClient, settings: Settings):
    _, headers = session(client)
    analysis_id = queue(client, headers).json()["id"]
    with sqlite3.connect(settings.db_path) as connection:
        connection.execute(
            "UPDATE analyses SET expires_at='2000-01-01T00:00:00.000Z' WHERE id=?",
            (analysis_id,),
        )
    assert client.get(f"/api/v1/analyses/{analysis_id}", headers=headers).status_code == 404
    assert client.get("/api/v1/analyses?limit=1&offset=0", headers=headers).json()["items"] == []

    orphan = settings.storage_dir / "artifacts" / "orphan.bin"
    orphan.parent.mkdir(parents=True, exist_ok=True)
    orphan.write_bytes(b"orphan")
    old = time.time() - settings.job_timeout_seconds - 61
    os.utime(orphan, (old, old))
    assert cleanup_expired(settings) == 1
    assert not orphan.exists()


def test_expired_job_never_reaches_model(client, settings):
    _, headers = session(client)
    analysis_id = queue(client, headers).json()["id"]
    with sqlite3.connect(settings.db_path) as connection:
        connection.execute(
            "UPDATE analyses SET expires_at='2000-01-01T00:00:00.000Z' WHERE id=?", (analysis_id,)
        )

    class MustNotRun:
        def analyze(self, *_):
            pytest.fail("Expired work reached inference")

    assert run_once(settings, adapter=MustNotRun()) is False


def test_atomic_queue_admission(client, settings):
    from concurrent.futures import ThreadPoolExecutor

    _, headers = session(client)
    with ThreadPoolExecutor(max_workers=8) as executor:
        responses = list(executor.map(lambda _: queue(client, headers), range(8)))
    assert [r.status_code for r in responses].count(202) == 1
    assert all(r.status_code in (202, 429, 503) for r in responses)
    assert len(list((settings.storage_dir / "sources").glob("*"))) == 1


@pytest.mark.parametrize(
    "change",
    [
        {"fused": 99.0},
        {"fused": 0.2},
        {"n_faces": -1},
        {"n_faces": 18},
        {"warnings": "bad"},
        {"metadata": []},
        {"spatial": float("nan")},
    ],
)
def test_invalid_model_output_fails_without_artifacts(client, settings, change):
    class BadAdapter(StubAdapter):
        def analyze(self, path, progress):
            return {**super().analyze(path, progress), **change}

    _, headers = session(client)
    analysis_id = queue(client, headers).json()["id"]
    run_once(settings, adapter=BadAdapter())
    detail = client.get(f"/api/v1/analyses/{analysis_id}", headers=headers).json()
    assert detail["status"] == "FAILED"
    assert detail["artifacts"] == []
    assert not list(settings.storage_dir.rglob("*.png"))


def test_global_queue_and_session_caps(settings):
    from dataclasses import replace

    bounded = replace(settings, max_active_global=1, max_sessions=2)
    with TestClient(create_app(bounded)) as client:
        _, first = session(client)
        _, second = session(client)
        assert client.post("/api/v1/sessions").status_code == 503
        assert queue(client, first).status_code == 202
        assert queue(client, second).status_code == 503


def test_only_one_worker_claims_job(client, settings):
    from concurrent.futures import ThreadPoolExecutor
    import threading

    calls = []
    gate = threading.Barrier(2)

    class CountingAdapter(StubAdapter):
        def analyze(self, path, progress):
            calls.append(str(path))
            return super().analyze(path, progress)

    _, headers = session(client)
    queue(client, headers)

    def work(_):
        gate.wait()
        return run_once(settings, adapter=CountingAdapter())

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(work, range(2)))
    assert sorted(results) == [False, True]
    assert len(calls) == 1


def test_partial_xai_still_returns_scores(client, settings):
    class Partial(StubAdapter):
        def analyze(self, path, progress):
            data = super().analyze(path, progress)
            data["artifacts"] = {}
            data["warnings"] = ["CAM_TEMPORAL_UNAVAILABLE", "CAM_SPATIAL_UNAVAILABLE"]
            return data

    _, headers = session(client)
    queued = queue(client, headers).json()
    run_once(settings, adapter=Partial())
    data = client.get("/api/v1/analyses/" + queued["id"], headers=headers).json()
    assert data["status"] == "COMPLETED"
    assert len(data["result"]["warnings"]) == 2
    assert data["artifacts"] == []


def test_stale_processing_becomes_failure(client, settings):
    _, headers = session(client)
    job = queue(client, headers).json()
    with sqlite3.connect(settings.db_path) as db:
        db.execute(
            "UPDATE analyses SET status='PROCESSING', updated_at='2000-01-01T00:00:00.000Z' WHERE id=?",
            (job["id"],),
        )
    assert not run_once(settings, adapter=StubAdapter())
    data = client.get("/api/v1/analyses/" + job["id"], headers=headers).json()
    assert data["status"] == "FAILED"
    assert data["error"]["code"] == "WORKER_TIMEOUT"
    assert not list((settings.storage_dir / "sources").glob("*"))


def test_openapi_documents_session_auth(client):
    schema = client.get("/openapi.json").json()
    assert schema["components"]["securitySchemes"]["SessionBearer"]["scheme"] == "bearer"
    assert schema["paths"]["/api/v1/games"]["post"]["security"] == [{"SessionBearer": []}]
    assert "ModelResult" in schema["components"]["schemas"]


def test_chunked_upload_without_content_length_is_bounded(client):
    _, headers = session(client)
    headers["Content-Type"] = "multipart/form-data; boundary=bounded"

    def chunks():
        yield b'--bounded\r\nContent-Disposition: form-data; name="video"; filename="x.mp4"\r\nContent-Type: video/mp4\r\n\r\n'
        yield b"x" * (2 * 1024 * 1024)
        yield b"\r\n--bounded--\r\n"

    response = client.post("/api/v1/analyses", headers=headers, content=chunks())
    assert response.status_code == 413
    assert response.json()["error"]["code"] == "UPLOAD_TOO_LARGE"


def test_expired_capacity_does_not_block_new_work(settings):
    from dataclasses import replace

    bounded = replace(settings, max_active_global=1, max_sessions=1)
    with TestClient(create_app(bounded)) as client:
        _, headers = session(client)
        assert queue(client, headers).status_code == 202
        with sqlite3.connect(settings.db_path) as db:
            db.execute("UPDATE analyses SET expires_at='2000-01-01T00:00:00.000Z'")
        assert queue(client, headers).status_code == 202
        with sqlite3.connect(settings.db_path) as db:
            db.execute("UPDATE sessions SET expires_at='2000-01-01T00:00:00.000Z'")
        _, new_headers = session(client)
        assert queue(client, new_headers).status_code == 202


def test_full_queue_rejected_before_reading_body(settings):
    import asyncio
    from dataclasses import replace
    from app.main import UploadAdmissionMiddleware

    bounded = replace(settings, max_active_global=1)
    with TestClient(create_app(bounded)) as client:
        _, headers = session(client)
        assert queue(client, headers).status_code == 202

        async def no_app(*args):
            pytest.fail("A full queue reached the multipart parser")

        async def no_receive():
            pytest.fail("A full queue read the upload body")

        messages = []

        async def send(message):
            messages.append(message)

        scope = {
            "type": "http",
            "method": "POST",
            "path": "/api/v1/analyses",
            "headers": [(b"authorization", headers["Authorization"].encode())],
        }
        asyncio.run(UploadAdmissionMiddleware(no_app, bounded)(scope, no_receive, send))
        assert messages[0]["status"] == 503
