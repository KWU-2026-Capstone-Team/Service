"""Preserving upgrade from the original unversioned game schema."""

from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from pathlib import Path


SCHEMA = Path(__file__).resolve().parent.parent / "migrations/002_games.sql"
REQUIRED_COLUMNS = {
    "game_clips": set("id video_path label explanation source license enabled difficulty technique cues_json tips_json source_verified license_verified".split()),
    "games": set("id session_id status total_rounds difficulty score max_streak created_at completed_at".split()),
    "game_rounds": set("game_id ordinal clip_id verdict confidence answered_at time_used_seconds skip_reason skipped_at points streak ai_model_version ai_spatial ai_temporal snapshot_label snapshot_explanation snapshot_technique snapshot_cues_json snapshot_tips_json".split()),
    "game_clip_evaluations": set("clip_id model_version spatial temporal evaluated_at".split()),
}


def _is_current(db):
    return all(columns <= _columns(db, table) for table, columns in REQUIRED_COLUMNS.items())


def _require_legacy(db):
    legacy = {
        "game_clips": set("id video_path label spatial temporal model_version explanation source license enabled".split()),
        "games": set("id session_id status total_rounds created_at".split()),
        "game_rounds": set("game_id ordinal clip_id verdict confidence answered_at".split()),
    }
    if not all(columns == _columns(db, table) for table, columns in legacy.items()) or _columns(db, "game_clip_evaluations"):
        raise RuntimeError("Unsupported partial game schema; restore a backup or apply an explicit migration before startup")


def _columns(db: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in db.execute(f"PRAGMA table_info({table})")}


def _execute_schema(db: sqlite3.Connection) -> None:
    statement = ""
    for line in SCHEMA.read_text(encoding="utf-8").splitlines(keepends=True):
        statement += line
        if sqlite3.complete_statement(statement):
            db.execute(statement)
            statement = ""
    if statement.strip():
        raise RuntimeError("incomplete game schema statement")


def initialize_game_schema(db: sqlite3.Connection, failure_hook=None) -> None:
    exists = db.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name='game_clips'"
    ).fetchone()
    if not exists:
        _execute_schema(db)
    elif _is_current(db):
        _execute_schema(db)
    else:
        _upgrade_legacy(db, failure_hook=failure_hook)


def _upgrade_legacy(db: sqlite3.Connection, failure_hook=None) -> None:
    db.execute("PRAGMA foreign_keys = OFF")
    db.execute("BEGIN IMMEDIATE")
    try:
        # Another process may have completed the migration while this
        # connection waited for the write lock.
        if _is_current(db):
            db.commit()
            return
        _require_legacy(db)
        clips = list(db.execute("SELECT * FROM game_clips"))
        games = list(db.execute("SELECT * FROM games"))
        rounds = list(db.execute("SELECT * FROM game_rounds ORDER BY game_id,ordinal"))
        now = datetime.now(timezone.utc).isoformat()
        labels = {row["id"]: row["label"] for row in clips}
        evaluations = {
            row["id"]: (row["model_version"], row["spatial"], row["temporal"])
            for row in clips
            if row["spatial"] is not None and row["temporal"] is not None
        }
        db.execute("ALTER TABLE game_rounds RENAME TO game_rounds_legacy")
        db.execute("ALTER TABLE games RENAME TO games_legacy")
        db.execute("ALTER TABLE game_clips RENAME TO game_clips_legacy")
        # The index follows its renamed table and would otherwise prevent the
        # new index from being created under the same global SQLite name.
        db.execute("DROP INDEX IF EXISTS games_owner")
        _execute_schema(db)
        for clip in clips:
            db.execute(
                """INSERT INTO game_clips(
                       id,video_path,label,explanation,source,license,enabled,
                       difficulty,technique,cues_json,tips_json,
                       source_verified,license_verified
                   ) VALUES(?,?,?,?,?,?,?,'all',NULL,'[]','[]',0,0)""",
                (
                    clip["id"], clip["video_path"], clip["label"], clip["explanation"],
                    clip["source"], clip["license"], clip["enabled"],
                ),
            )
            if clip["id"] in evaluations:
                version, spatial, temporal = evaluations[clip["id"]]
                db.execute(
                    """INSERT INTO game_clip_evaluations(
                           clip_id,model_version,spatial,temporal,evaluated_at
                       ) VALUES(?,?,?,?,?)""",
                    (clip["id"], version or "legacy-unknown", spatial, temporal, now),
                )

        by_game: dict[str, list[sqlite3.Row]] = {}
        for item in rounds:
            by_game.setdefault(item["game_id"], []).append(item)
        calculated: dict[str, tuple[int, int, dict[int, tuple[int, int]]]] = {}
        for game_id, items in by_game.items():
            score = streak = max_streak = 0
            per_round: dict[int, tuple[int, int]] = {}
            for item in items:
                points = 0
                if item["verdict"] is not None:
                    if item["verdict"] == labels[item["clip_id"]]:
                        points = 100 + 10 * streak
                        streak += 1
                    else:
                        streak = 0
                per_round[item["ordinal"]] = (points, streak)
                score += points
                max_streak = max(max_streak, streak)
            calculated[game_id] = (score, max_streak, per_round)

        for game in games:
            score, max_streak, _ = calculated.get(game["id"], (0, 0, {}))
            db.execute(
                """INSERT INTO games(
                       id,session_id,status,total_rounds,difficulty,score,
                       max_streak,created_at,completed_at
                   ) VALUES(?,?,?,?,'all',?,?,?,?)""",
                (
                    game["id"], game["session_id"], game["status"], game["total_rounds"],
                    score, max_streak, game["created_at"],
                    now if game["status"] == "COMPLETED" else None,
                ),
            )
        for item in rounds:
            points, streak = calculated[item["game_id"]][2][item["ordinal"]]
            evaluation = evaluations.get(item["clip_id"], (None, None, None))
            db.execute(
                """INSERT INTO game_rounds(
                       game_id,ordinal,clip_id,verdict,confidence,answered_at,
                       points,streak,ai_model_version,ai_spatial,ai_temporal,
                       snapshot_label,snapshot_explanation,snapshot_technique,
                       snapshot_cues_json,snapshot_tips_json
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    item["game_id"], item["ordinal"], item["clip_id"], item["verdict"],
                    item["confidence"], item["answered_at"], points, streak,
                    evaluation[0], evaluation[1], evaluation[2],
                    labels[item["clip_id"]],
                    next(row["explanation"] for row in clips if row["id"] == item["clip_id"]),
                    None, "[]", "[]",
                ),
            )
        if failure_hook is not None:
            failure_hook()
        db.execute("DROP TABLE game_rounds_legacy")
        db.execute("DROP TABLE games_legacy")
        db.execute("DROP TABLE game_clips_legacy")
        db.execute("CREATE INDEX IF NOT EXISTS games_owner ON games(session_id,created_at DESC)")
        violations = list(db.execute("PRAGMA foreign_key_check"))
        if violations:
            raise RuntimeError(f"game migration foreign-key violations: {violations!r}")
        db.commit()
    except Exception:
        db.rollback()
        raise
    finally:
        db.execute("PRAGMA foreign_keys = ON")
