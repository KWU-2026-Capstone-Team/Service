import json
import sqlite3
import threading
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.db import connect
from app.game_migrations import initialize_game_schema
from app.import_deepgame import LICENSE, load_reference
from app.main import create_app
from app.evaluate_games import record_evaluation


LEGACY_SCHEMA = """
CREATE TABLE sessions(id TEXT PRIMARY KEY);
CREATE TABLE game_clips (
 id TEXT PRIMARY KEY, video_path TEXT NOT NULL, label TEXT NOT NULL,
 spatial REAL NOT NULL, temporal REAL NOT NULL, model_version TEXT NOT NULL,
 explanation TEXT NOT NULL DEFAULT '', source TEXT NOT NULL, license TEXT NOT NULL,
 enabled INTEGER NOT NULL DEFAULT 1
);
CREATE TABLE games (
 id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
 status TEXT NOT NULL, total_rounds INTEGER NOT NULL, created_at TEXT NOT NULL
);
CREATE INDEX games_owner ON games(session_id,created_at);
CREATE TABLE game_rounds (
 game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
 ordinal INTEGER NOT NULL, clip_id TEXT NOT NULL REFERENCES game_clips(id),
 verdict TEXT, confidence TEXT, answered_at TEXT, PRIMARY KEY(game_id,ordinal)
);
"""


def legacy_db(path):
    db = sqlite3.connect(path, timeout=30)
    db.row_factory = sqlite3.Row
    db.executescript(LEGACY_SCHEMA)
    db.execute("INSERT INTO sessions VALUES('s')")
    db.execute(
        "INSERT INTO game_clips VALUES(?,?,?,?,?,?,?,?,?,?)",
        ("c", "/local.mp4", "FAKE", 0.9, 0.8, "legacy-v1", "old explanation", "src", "lic", 1),
    )
    db.execute("INSERT INTO games VALUES('g','s','COMPLETED',1,'old-time')")
    db.execute("INSERT INTO game_rounds VALUES('g',1,'c','FAKE','HIGH','answer-time')")
    db.commit()
    return db


def test_migration_preserves_rows_scores_and_evaluation(tmp_path):
    db = legacy_db(tmp_path / "legacy.sqlite3")
    initialize_game_schema(db)
    assert db.execute("SELECT count(*) FROM games").fetchone()[0] == 1
    game = db.execute("SELECT * FROM games WHERE id='g'").fetchone()
    assert (game["score"], game["max_streak"], game["status"]) == (100, 1, "COMPLETED")
    round_ = db.execute("SELECT * FROM game_rounds WHERE game_id='g'").fetchone()
    assert round_["verdict"] == "FAKE"
    assert round_["snapshot_label"] == "FAKE"
    assert round_["snapshot_explanation"] == "old explanation"
    assert round_["ai_model_version"] == "legacy-v1"
    evaluation = db.execute("SELECT * FROM game_clip_evaluations").fetchone()
    assert (evaluation["spatial"], evaluation["temporal"]) == (0.9, 0.8)
    assert db.execute("PRAGMA foreign_key_check").fetchall() == []
    initialize_game_schema(db)
    assert db.execute("SELECT count(*) FROM games").fetchone()[0] == 1
    db.close()


def test_migration_failure_rolls_back_exact_legacy_schema(tmp_path):
    db = legacy_db(tmp_path / "rollback.sqlite3")

    def fail():
        raise RuntimeError("injected")

    with pytest.raises(RuntimeError, match="injected"):
        initialize_game_schema(db, failure_hook=fail)
    assert "spatial" in {row[1] for row in db.execute("PRAGMA table_info(game_clips)")}
    assert db.execute("SELECT verdict FROM game_rounds").fetchone()[0] == "FAKE"
    assert db.execute("SELECT name FROM sqlite_master WHERE name='games_owner'").fetchone()
    assert not db.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='game_clips_legacy'"
    ).fetchone()
    db.close()


@pytest.mark.parametrize("table", ["game_clips", "games", "game_rounds", "game_clip_evaluations"])
def test_asymmetric_partial_migration_preserves_data(tmp_path, table):
    db = legacy_db(tmp_path / "partial.sqlite3")
    if table == "game_clip_evaluations":
        db.execute("CREATE TABLE game_clip_evaluations(clip_id TEXT)")
    else:
        db.execute(f"ALTER TABLE {table} ADD COLUMN newer_field TEXT DEFAULT 'preserve-me'")
    db.commit()
    before = list(db.iterdump())
    with pytest.raises(RuntimeError, match="partial game schema"):
        initialize_game_schema(db)
    assert list(db.iterdump()) == before
    db.close()


@pytest.mark.parametrize("value", [float("inf"), 1e308, -1, "invalid"])
def test_historical_invalid_time_is_null_in_api(client, value):
    seed_balanced(client)
    headers = auth(client)
    game = client.post("/api/v1/games", headers=headers, json={"rounds": 1}).json()
    base = f"/api/v1/games/{game['id']}"
    client.post(base + "/rounds/1/answer", headers=headers, json={"verdict": "REAL"})
    with connect(client.app.state.settings) as db:
        db.execute("PRAGMA ignore_check_constraints=ON")
        db.execute("UPDATE game_rounds SET time_used_seconds=? WHERE game_id=?", (value, game["id"]))
    response = client.get(base + "/result", headers=headers)
    assert response.status_code == 200
    assert response.json()["average_time_seconds"] is None
    assert response.json()["rounds"][0]["time_used_seconds"] is None
    assert client.get(base + "/rounds/1", headers=headers).json()["result"]["time_used_seconds"] is None


def test_concurrent_initializers_are_idempotent(tmp_path):
    path = tmp_path / "concurrent.sqlite3"
    legacy_db(path).close()
    barrier = threading.Barrier(2)
    errors = []

    def run():
        db = sqlite3.connect(path, timeout=30)
        db.row_factory = sqlite3.Row
        try:
            barrier.wait()
            initialize_game_schema(db)
        except Exception as exc:  # pragma: no cover - asserted below
            errors.append(exc)
        finally:
            db.close()

    threads = [threading.Thread(target=run) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert errors == []
    with sqlite3.connect(path) as db:
        assert db.execute("SELECT count(*) FROM games").fetchone()[0] == 1
        assert db.execute("PRAGMA foreign_key_check").fetchall() == []


@pytest.fixture
def client(tmp_path):
    settings = Settings(
        db_path=tmp_path / "db.sqlite3",
        storage_dir=tmp_path / "storage",
        model_dir=tmp_path / "models",
        inference_mode="demo",
    )
    with TestClient(create_app(settings)) as value:
        yield value


def auth(client):
    return {"Authorization": "Bearer " + client.post("/api/v1/sessions").json()["token"]}


def test_evaluation_rejects_demo_and_invalid_scores(client):
    seed_balanced(client)
    settings = client.app.state.settings
    result = {"is_demo": True, "model_version": "fixture", "spatial": 0.5, "temporal": 0.2}
    with pytest.raises(ValueError):
        record_evaluation(settings, "c0", result)
    result.update(is_demo=False, spatial=float("nan"))
    with pytest.raises(ValueError):
        record_evaluation(settings, "c0", result)
    result.update(spatial=0.4)
    record_evaluation(settings, "c0", result)
    record_evaluation(settings, "c0", result)
    with connect(settings) as db:
        assert db.execute("SELECT count(*) FROM game_clip_evaluations").fetchone()[0] == 1


def test_all_skipped_has_no_accuracy_or_grade(client):
    seed_balanced(client)
    headers = auth(client)
    game = client.post("/api/v1/games", headers=headers, json={"rounds": 1}).json()
    path = f"/api/v1/games/{game['id']}"
    client.post(path + "/rounds/1/skip", headers=headers, json={"reason": "VIDEO_UNAVAILABLE"})
    result = client.get(path + "/result", headers=headers).json()
    assert result["grade"] == "N/A"
    assert result["user_accuracy"] is None
    assert result["played_rounds"] == 0


def test_partial_schema_is_rejected(client):
    with connect(client.app.state.settings) as db:
        db.execute("ALTER TABLE game_clips DROP COLUMN tips_json")
    with connect(client.app.state.settings) as db, pytest.raises(RuntimeError, match="partial game schema"):
        initialize_game_schema(db)


def test_local_worker_readiness_lock_and_interruption_recovery(client, monkeypatch):
    from app.local_runtime import local_worker, worker_health
    from app.worker import _claim
    settings = client.app.state.settings
    headers = auth(client)
    uploaded = client.post("/api/v1/analyses", headers=headers, files={"video": ("fixture.mp4", b"fixture", "video/mp4")})
    assert uploaded.status_code == 202
    analysis_id = uploaded.json()["id"]
    assert _claim(settings)["id"] == analysis_id
    monkeypatch.setenv("DEEPTECTOR_LOCAL_RUNTIME", "1")
    monkeypatch.setenv("DEEPTECTOR_RUN_ID", "test-run")
    assert client.get("/health/worker").status_code == 503
    assert client.post("/api/v1/analyses", headers=headers, files={"video": ("fixture.mp4", b"fixture", "video/mp4")}).status_code == 503
    with local_worker(settings):
        for _ in range(50):
            if worker_health(settings)["ready"]:
                break
            time.sleep(0.02)
        assert client.get("/health/worker").status_code == 200
        recovered = client.get(f"/api/v1/analyses/{analysis_id}", headers=headers).json()
        assert recovered["status"] == "FAILED"
        assert recovered["error"]["code"] == "WORKER_RESTARTED"
        with pytest.raises(OSError), local_worker(settings):
            pass
        assert worker_health(settings)["ready"]
        monkeypatch.setenv("DEEPTECTOR_RUN_ID", "different-run")
        assert not worker_health(settings)["ready"]
        monkeypatch.setenv("DEEPTECTOR_RUN_ID", "test-run")
    assert client.get("/health/worker").status_code == 503


def test_partial_evaluation_and_extreme_time(client):
    seed_balanced(client)
    with connect(client.app.state.settings) as db:
        db.execute("INSERT INTO game_clip_evaluations VALUES('c0','partial',0.2,NULL,'2026-01-01')")
    headers = auth(client)
    response = client.post("/api/v1/games", headers=headers, json={"rounds": 6})
    assert response.status_code == 201
    path = f"/api/v1/games/{response.json()['id']}"
    assert client.post(path + "/rounds/1/answer", headers=headers, json={"verdict": "REAL", "time_used_seconds": 1e308}).status_code == 422
    from app.game import _average_time
    assert _average_time([1e308, 1e308, 3.0]) == 3.0
    assert _average_time([1e308]) is None
    assert client.get("/api/v1/games?limit=1&offset=1", headers=headers).json()["items"] == []


def seed_balanced(client, count=6, evaluated=False):
    with connect(client.app.state.settings) as db:
        for i in range(count):
            label = "REAL" if i % 2 == 0 else "FAKE"
            db.execute(
                """INSERT INTO game_clips(
                       id,video_path,label,explanation,source,license,enabled,
                       difficulty,technique,cues_json,tips_json,source_verified,license_verified
                   ) VALUES(?,?,?,?,?,?,1,'easy',?,'[\"cue\"]','[\"tip\"]',1,0)""",
                (f"c{i}", "/missing.mp4", label, f"explanation {i}", "local", LICENSE, "method"),
            )
            if evaluated:
                score = 0.1 if label == "REAL" else 0.9
                db.execute(
                    "INSERT INTO game_clip_evaluations VALUES(?,?,?,?,?)",
                    (f"c{i}", "snapshot-v1", score, score, "2026-01-01"),
                )


def labels(client, game_id):
    with connect(client.app.state.settings) as db:
        return {
            row["ordinal"]: row["snapshot_label"]
            for row in db.execute(
                "SELECT ordinal,snapshot_label FROM game_rounds WHERE game_id=?", (game_id,)
            )
        }


def test_scoring_skip_nullable_ai_history_delete_and_owner(client):
    seed_balanced(client)
    owner = auth(client)
    other = auth(client)
    created = client.post(
        "/api/v1/games", json={"rounds": 5, "difficulty": "easy"}, headers=owner
    )
    assert created.status_code == 201
    game_id = created.json()["id"]
    selected = labels(client, game_id)
    assert abs(list(selected.values()).count("REAL") - list(selected.values()).count("FAKE")) == 1
    base = f"/api/v1/games/{game_id}"
    assert client.get(base, headers=other).status_code == 404

    for ordinal in (1, 2):
        response = client.post(
            f"{base}/rounds/{ordinal}/answer",
            json={
                "verdict": selected[ordinal],
                "confidence": "HIGH",
                "time_used_seconds": ordinal + 0.5,
            },
            headers=owner,
        )
        assert response.json()["points"] == (100 if ordinal == 1 else 110)
    skip = client.post(
        f"{base}/rounds/3/skip", json={"reason": "VIDEO_UNAVAILABLE"}, headers=owner
    )
    assert skip.json()["skipped"] is True
    assert client.post(
        f"{base}/rounds/3/skip", json={"reason": "VIDEO_UNAVAILABLE"}, headers=owner
    ).json() == skip.json()
    wrong = "FAKE" if selected[4] == "REAL" else "REAL"
    client.post(
        f"{base}/rounds/4/answer",
        json={"verdict": wrong},
        headers=owner,
    )
    client.post(
        f"{base}/rounds/5/answer",
        json={"verdict": selected[5]},
        headers=owner,
    )
    result = client.get(f"{base}/result", headers=owner).json()
    assert (result["score"], result["max_streak"]) == (310, 2)
    assert (result["played_rounds"], result["skipped_rounds"]) == (4, 1)
    assert result["ai_evaluated_rounds"] == 0
    assert result["ai_score"] is None and result["ai_accuracy"] is None
    assert result["average_time_seconds"] == 2.0
    assert client.get("/api/v1/games", headers=owner).json()["items"][0]["id"] == game_id
    assert client.delete(base, headers=other).status_code == 404
    assert client.delete(base, headers=owner).status_code == 204
    assert client.get(base, headers=owner).status_code == 404


def test_round_snapshots_ai_and_metadata_and_rejects_nonfinite_time(client):
    seed_balanced(client, count=2, evaluated=True)
    owner = auth(client)
    game = client.post("/api/v1/games", json={"rounds": 1}, headers=owner).json()
    game_id = game["id"]
    selected = labels(client, game_id)[1]
    with connect(client.app.state.settings) as db:
        clip_id = db.execute(
            "SELECT clip_id FROM game_rounds WHERE game_id=?", (game_id,)
        ).fetchone()[0]
        db.execute("UPDATE game_clips SET label='FAKE',explanation='changed' WHERE id=?", (clip_id,))
        db.execute(
            "UPDATE game_clip_evaluations SET model_version='changed',spatial=0.5,temporal=0.5 WHERE clip_id=?",
            (clip_id,),
        )
    invalid = client.post(
        f"/api/v1/games/{game_id}/rounds/1/answer",
        content=json.dumps({"verdict": selected, "time_used_seconds": "Infinity"}),
        headers={**owner, "Content-Type": "application/json"},
    )
    assert invalid.status_code == 422
    answer = client.post(
        f"/api/v1/games/{game_id}/rounds/1/answer",
        json={"verdict": selected},
        headers=owner,
    ).json()
    assert answer["ground_truth"] == selected
    assert answer["explanation"].startswith("explanation")
    assert answer["ai"]["model_version"] == "snapshot-v1"


def test_reference_loader_registers_exact_local_metadata(tmp_path):
    root = tmp_path / "deepGame-reference"
    (root / "src/data").mkdir(parents=True)
    (root / "public/videos").mkdir(parents=True)
    videos = []
    for i in range(24):
        name = f"clip_{i}.mp4"
        (root / "public/videos" / name).write_bytes(b"local")
        videos.append(
            {
                "id": f"clip-{i}", "src": f"/videos/{name}",
                "label": "real" if i % 2 == 0 else "fake",
                "difficulty": "easy", "explanation": "review", "cues": [],
                "technique": None,
            }
        )
    (root / "src/data/videos.json").write_text(json.dumps({"videos": videos}), encoding="utf-8")
    loaded = load_reference(root)
    assert len(loaded) == 24
    assert all(item["license"] == LICENSE and item["license_verified"] is False for item in loaded)
    assert all(Path(item["video_path"]).parent == (root / "public/videos").resolve() for item in loaded)
    assert not any("spatial" in item or "temporal" in item for item in loaded)
