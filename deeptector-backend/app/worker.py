from __future__ import annotations

import argparse
import json
import logging
import re
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from .config import Settings
from .db import connect, initialize
from .schemas import ModelResult


REQUIRED_MODEL_KEYS = {
    "spatial",
    "temporal",
    "fused",
    "n_faces",
    "family",
    "faithfulness",
    "metadata",
    "model_version",
    "calibration_version",
    "artifacts",
    "warnings",
}


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def iso_utc(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _json_default(value: Any) -> Any:
    if hasattr(value, "item"):
        return value.item()
    if isinstance(value, Path):
        return str(value)
    raise TypeError(f"value of type {type(value).__name__} is not JSON serializable")


def _json(value: Any) -> str:
    return json.dumps(
        value,
        default=_json_default,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    )


def _error_payload(exc: Exception) -> dict[str, Any]:
    code = getattr(exc, "code", None)
    message = getattr(exc, "message", None)
    details = getattr(exc, "details", {})
    if isinstance(code, str) and isinstance(message, str) and isinstance(details, dict):
        return {"code": code, "message": message, "details": details}
    return {
        "code": "ANALYSIS_FAILED",
        "message": "Analysis failed unexpectedly",
        "details": {},
    }


def _classify(spatial: Any, temporal: Any) -> dict[str, Any]:
    from .policy import classify

    result = classify(spatial, temporal)
    if not isinstance(result, dict):
        raise TypeError("policy classify() must return a dictionary")
    return result


def _load_adapter(settings: Settings) -> Any:
    from .inference import ModelAdapter

    return ModelAdapter(settings)


def _claim(settings: Settings) -> Any | None:
    now = utc_now()
    # The adapter's child-process timeout and its final error propagation need a
    # small grace window so a healthy worker is never raced by another worker.
    cutoff = iso_utc(now - timedelta(seconds=settings.job_timeout_seconds + 60))
    stale_paths: list[str] = []
    claimed = None
    with connect(settings) as connection:
        connection.execute("BEGIN IMMEDIATE")
        stale = connection.execute(
            "SELECT id FROM analyses WHERE status = 'PROCESSING' AND updated_at < ?",
            (cutoff,),
        ).fetchall()
        if stale:
            placeholders = ",".join("?" for _ in stale)
            stale_paths = [
                row["source_path"]
                for row in connection.execute(
                    f"SELECT source_path FROM analyses WHERE id IN ({placeholders})",
                    [row["id"] for row in stale],
                ).fetchall()
            ]
            failure = _json(
                {
                    "code": "WORKER_TIMEOUT",
                    "message": "Worker stopped updating this analysis before the timeout",
                    "details": {},
                }
            )
            connection.executemany(
                "UPDATE analyses SET status = 'FAILED', stage = 'FAILED', updated_at = ?, error_json = ? WHERE id = ?",
                [(iso_utc(now), failure, row["id"]) for row in stale],
            )
        row = connection.execute(
            """SELECT a.* FROM analyses a JOIN sessions s ON s.id=a.session_id
               WHERE a.status='QUEUED' AND a.expires_at > ? AND s.expires_at > ?
               ORDER BY a.created_at, a.id LIMIT 1""",
            (iso_utc(now), iso_utc(now)),
        ).fetchone()
        if row is not None:
            connection.execute(
                "UPDATE analyses SET status = 'PROCESSING', stage = 'STARTING', updated_at = ? WHERE id = ? AND status = 'QUEUED'",
                (iso_utc(now), row["id"]),
            )
            claimed = dict(row)
    _remove_paths(settings, stale_paths)
    return claimed


def _set_stage(settings: Settings, analysis_id: str, stage: str) -> None:
    clean = re.sub(r"[^A-Za-z0-9 _.-]", "", str(stage)).strip()[:80] or "PROCESSING"
    with connect(settings) as connection:
        connection.execute(
            "UPDATE analyses SET stage = ?, updated_at = ? WHERE id = ? AND status = 'PROCESSING'",
            (clean, iso_utc(utc_now()), analysis_id),
        )


def _artifact_format(data: bytes) -> tuple[str, str]:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png", "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return ".jpg", "image/jpeg"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return ".gif", "image/gif"
    if data.startswith(b"RIFF") and data[8:12] == b"WEBP":
        return ".webp", "image/webp"
    return ".bin", "application/octet-stream"


def _write_artifacts(settings: Settings, analysis_id: str, artifacts: Any) -> list[dict[str, str]]:
    if not isinstance(artifacts, dict):
        raise TypeError("model artifacts must be a dictionary")
    directory = settings.storage_dir / "artifacts" / analysis_id
    directory.mkdir(parents=True, exist_ok=True)
    records: list[dict[str, str]] = []
    for raw_kind, data in artifacts.items():
        if not isinstance(data, bytes):
            raise TypeError(f"artifact {raw_kind!r} must contain bytes")
        kind = re.sub(r"[^A-Za-z0-9_.-]", "_", str(raw_kind)).strip("._")[:80]
        if not kind:
            raise ValueError("artifact kind cannot be empty")
        extension, mime_type = _artifact_format(data)
        path = directory / f"{uuid.uuid4().hex}{extension}"
        with path.open("xb") as output:
            output.write(data)
        records.append({"id": str(uuid.uuid4()), "kind": kind, "path": str(path), "mime_type": mime_type})
    return records


def _remove_paths(settings: Settings, paths: list[str]) -> None:
    root = settings.storage_dir.resolve()
    for raw_path in paths:
        try:
            path = Path(raw_path).resolve()
            if path == root or root not in path.parents:
                continue
            path.unlink(missing_ok=True)
            parent = path.parent
            if parent != root:
                try:
                    parent.rmdir()
                except OSError:
                    pass
        except OSError:
            pass


def run_once(settings: Settings | None = None, adapter: Any | None = None) -> bool:
    configured = settings or Settings.from_env()
    initialize(configured)
    job = _claim(configured)
    if job is None:
        return False
    analysis_id = job["id"]
    source_path = Path(job["source_path"])
    artifact_records: list[dict[str, str]] = []
    try:
        active_adapter = adapter if adapter is not None else _load_adapter(configured)
        output = active_adapter.analyze(
            source_path,
            lambda stage: _set_stage(configured, analysis_id, stage),
        )
        if not isinstance(output, dict):
            raise TypeError("model adapter analyze() must return a dictionary")
        missing = REQUIRED_MODEL_KEYS - output.keys()
        if missing:
            raise ValueError(f"model output missing keys: {', '.join(sorted(missing))}")
        result = ModelResult.model_validate(
            {key: value for key, value in output.items() if key != "artifacts"}
        ).model_dump(exclude_none=True)
        policy = _classify(result["spatial"], result["temporal"])
        artifact_records = _write_artifacts(configured, analysis_id, output["artifacts"])
        result["policy"] = policy
        now = iso_utc(utc_now())
        with connect(configured) as connection:
            connection.execute("BEGIN IMMEDIATE")
            lease = connection.execute("SELECT status FROM analyses WHERE id = ?", (analysis_id,)).fetchone()
            if lease is None or lease["status"] != "PROCESSING":
                raise RuntimeError("analysis processing lease was lost")
            for record in artifact_records:
                connection.execute(
                    "INSERT INTO artifacts(id, analysis_id, kind, path, mime_type) VALUES (?, ?, ?, ?, ?)",
                    (record["id"], analysis_id, record["kind"], record["path"], record["mime_type"]),
                )
            connection.execute(
                """UPDATE analyses
                   SET status = 'COMPLETED', stage = 'COMPLETED', updated_at = ?, result_json = ?, error_json = NULL
                   WHERE id = ? AND status = 'PROCESSING'""",
                (now, _json(result), analysis_id),
            )
    except Exception as exc:
        logging.getLogger(__name__).exception("Analysis %s failed", analysis_id)
        _remove_paths(configured, [record["path"] for record in artifact_records])
        with connect(configured) as connection:
            connection.execute(
                """UPDATE analyses
                   SET status = 'FAILED', stage = 'FAILED', updated_at = ?, error_json = ?
                   WHERE id = ? AND status = 'PROCESSING'""",
                (iso_utc(utc_now()), _json(_error_payload(exc)), analysis_id),
            )
    finally:
        _remove_paths(configured, [str(source_path)])
    return True


def cleanup_expired(settings: Settings | None = None) -> int:
    configured = settings or Settings.from_env()
    initialize(configured)
    now = iso_utc(utc_now())
    with connect(configured) as connection:
        connection.execute("BEGIN IMMEDIATE")
        rows = connection.execute(
            """SELECT a.id, a.source_path, r.path AS artifact_path
               FROM analyses a
               JOIN sessions s ON s.id = a.session_id
               LEFT JOIN artifacts r ON r.analysis_id = a.id
               WHERE a.status != 'PROCESSING' AND (a.expires_at <= ? OR s.expires_at <= ?)""",
            (now, now),
        ).fetchall()
        analysis_ids = {row["id"] for row in rows}
        paths = [row["source_path"] for row in rows]
        paths.extend(row["artifact_path"] for row in rows if row["artifact_path"])
        connection.execute(
            """DELETE FROM analyses
               WHERE status != 'PROCESSING'
                 AND (expires_at <= ? OR session_id IN (SELECT id FROM sessions WHERE expires_at <= ?))""",
            (now, now),
        )
        connection.execute(
            """DELETE FROM sessions
               WHERE expires_at <= ?
                 AND NOT EXISTS (
                     SELECT 1 FROM analyses
                     WHERE analyses.session_id = sessions.id AND analyses.status = 'PROCESSING'
                 )""",
            (now,),
        )
    _remove_paths(configured, paths)
    _cleanup_orphans(configured)
    return len(analysis_ids)


def _cleanup_orphans(settings: Settings) -> int:
    """Delete aged storage files that have no database reference."""
    with connect(settings) as connection:
        referenced = {
            str(Path(row["path"]).resolve())
            for row in connection.execute(
                "SELECT source_path AS path FROM analyses UNION SELECT path FROM artifacts"
            ).fetchall()
        }
    cutoff = utc_now().timestamp() - (settings.job_timeout_seconds + 60)
    removed = 0
    if not settings.storage_dir.exists():
        return removed
    for path in settings.storage_dir.rglob("*"):
        try:
            if path.is_file() and str(path.resolve()) not in referenced and path.stat().st_mtime < cutoff:
                path.unlink()
                removed += 1
        except OSError:
            continue
    for directory in sorted(
        (path for path in settings.storage_dir.rglob("*") if path.is_dir()),
        key=lambda path: len(path.parts),
        reverse=True,
    ):
        try:
            directory.rmdir()
        except OSError:
            pass
    return removed


def main() -> None:
    parser = argparse.ArgumentParser(description="Deeptector persistent analysis worker")
    parser.add_argument("--once", action="store_true", help="Process at most one queued analysis")
    parser.add_argument("--cleanup", action="store_true", help="Remove expired analyses and files, then exit")
    parser.add_argument("--poll-seconds", type=float, default=2.0)
    args = parser.parse_args()
    settings = Settings.from_env()
    if args.cleanup:
        cleanup_expired(settings)
        return
    if args.once:
        run_once(settings)
        return
    last_cleanup = 0.0
    while True:
        if time.monotonic() - last_cleanup >= 60:
            cleanup_expired(settings)
            last_cleanup = time.monotonic()
        processed = run_once(settings)
        if not processed:
            time.sleep(max(0.1, args.poll_seconds))


if __name__ == "__main__":
    main()
