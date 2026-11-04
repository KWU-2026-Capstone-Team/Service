"""Session-owned deepfake identification game API."""

from __future__ import annotations

import json
import math
import secrets
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from .db import connect
from .game_migrations import initialize_game_schema
from .policy import classify


router = APIRouter(prefix="/api/v1/games", tags=["game"])
Difficulty = Literal["all", "easy", "medium", "hard"]


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def initialize_game(settings) -> None:
    with connect(settings) as db:
        initialize_game_schema(db)


def owner(request: Request):
    from .main import get_session

    return get_session(request, request.headers.get("authorization"))


def fail(status: int, code: str, message: str) -> None:
    raise HTTPException(status, detail={"code": code, "message": message})


def owned(db, game_id: str, session_id: str):
    game = db.execute(
        "SELECT * FROM games WHERE id=? AND session_id=?", (game_id, session_id)
    ).fetchone()
    if not game:
        fail(404, "GAME_NOT_FOUND", "게임을 찾을 수 없습니다.")
    return game


class StartGame(BaseModel):
    rounds: int = Field(default=10, ge=1, le=30)
    difficulty: Difficulty = "all"


class Answer(BaseModel):
    verdict: Literal["REAL", "FAKE"]
    confidence: Literal["LOW", "MEDIUM", "HIGH"] = "MEDIUM"
    time_used_seconds: float | None = Field(default=None, ge=0, le=86400, allow_inf_nan=False)


class Skip(BaseModel):
    reason: Literal["VIDEO_UNAVAILABLE"]


def _summary(db, game) -> dict:
    terminal = game["terminal"] if "terminal" in game.keys() else db.execute(
        """SELECT count(*) FROM game_rounds
           WHERE game_id=? AND (verdict IS NOT NULL OR skip_reason IS NOT NULL)""",
        (game["id"],),
    ).fetchone()[0]
    return {
        "id": game["id"],
        "status": game["status"],
        "total_rounds": game["total_rounds"],
        "answered_rounds": terminal,
        "next_round": terminal + 1 if terminal < game["total_rounds"] else None,
        "difficulty": game["difficulty"],
        "score": game["score"],
        "max_streak": game["max_streak"],
        "created_at": game["created_at"],
    }


@router.get("")
def list_games(request: Request, session_id=Depends(owner), limit: int = Query(50, ge=1, le=100), offset: int = Query(0, ge=0)):
    with connect(request.app.state.settings) as db:
        games = db.execute(
            """SELECT g.*,count(CASE WHEN r.verdict IS NOT NULL OR r.skip_reason IS NOT NULL THEN 1 END) AS terminal
               FROM games g LEFT JOIN game_rounds r ON r.game_id=g.id WHERE g.session_id=?
               GROUP BY g.id ORDER BY g.created_at DESC,g.id LIMIT ? OFFSET ?""", (session_id, limit, offset)
        ).fetchall()
        items = [_summary(db, game) for game in games]
    return {"items": items}


def _balanced_sample(clips: list, count: int) -> list:
    by_label = {
        label: [clip for clip in clips if clip["label"] == label]
        for label in ("REAL", "FAKE")
    }
    low, high = count // 2, count - count // 2
    allocations = [(low, high)] if low == high else [(low, high), (high, low)]
    feasible = [
        pair
        for pair in allocations
        if len(by_label["REAL"]) >= pair[0] and len(by_label["FAKE"]) >= pair[1]
    ]
    if not feasible:
        fail(409, "GAME_DATASET_NOT_READY", "난이도와 정답 균형을 만족하는 영상이 부족합니다.")
    real_count, fake_count = secrets.choice(feasible)
    selected = secrets.SystemRandom().sample(by_label["REAL"], real_count)
    selected += secrets.SystemRandom().sample(by_label["FAKE"], fake_count)
    secrets.SystemRandom().shuffle(selected)
    return selected


@router.post("", status_code=201)
def start_game(body: StartGame, request: Request, session_id=Depends(owner)):
    settings = request.app.state.settings
    with connect(settings) as db:
        db.execute("BEGIN IMMEDIATE")
        active = db.execute(
            "SELECT count(*) FROM games WHERE session_id=? AND status='ACTIVE'", (session_id,)
        ).fetchone()[0]
        if active >= 5:
            fail(429, "GAME_LIMIT", "진행 중인 게임이 너무 많습니다.")
        where = (
            "enabled=1"
            if body.difficulty == "all"
            else "enabled=1 AND (difficulty=? OR difficulty='all')"
        )
        params = () if body.difficulty == "all" else (body.difficulty,)
        clips = list(db.execute(f"SELECT * FROM game_clips WHERE {where}", params))
        selected = _balanced_sample(clips, body.rounds)
        game_id = str(uuid.uuid4())
        created_at = now()
        db.execute(
            """INSERT INTO games(
                   id,session_id,status,total_rounds,difficulty,created_at
               ) VALUES(?,?,'ACTIVE',?,?,?)""",
            (game_id, session_id, body.rounds, body.difficulty, created_at),
        )
        for ordinal, clip in enumerate(selected, 1):
            evaluation = db.execute(
                """SELECT * FROM game_clip_evaluations WHERE clip_id=? AND spatial IS NOT NULL AND temporal IS NOT NULL
                   ORDER BY evaluated_at DESC,model_version DESC LIMIT 1""",
                (clip["id"],),
            ).fetchone()
            db.execute(
                """INSERT INTO game_rounds(
                       game_id,ordinal,clip_id,ai_model_version,ai_spatial,ai_temporal,
                       snapshot_label,snapshot_explanation,snapshot_technique,
                       snapshot_cues_json,snapshot_tips_json
                   ) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    game_id,
                    ordinal,
                    clip["id"],
                    evaluation["model_version"] if evaluation else None,
                    evaluation["spatial"] if evaluation else None,
                    evaluation["temporal"] if evaluation else None,
                    clip["label"],
                    clip["explanation"],
                    clip["technique"],
                    clip["cues_json"],
                    clip["tips_json"],
                ),
            )
    return {"id": game_id, "status": "ACTIVE", "total_rounds": body.rounds, "next_round": 1}


@router.get("/{game_id}")
def get_game(game_id: str, request: Request, session_id=Depends(owner)):
    with connect(request.app.state.settings) as db:
        return _summary(db, owned(db, game_id, session_id))


def round_row(db, game_id: str, ordinal: int):
    row = db.execute(
        """SELECT r.*,c.video_path,
                  r.snapshot_label AS label,
                  r.snapshot_explanation AS explanation,
                  r.snapshot_technique AS technique,
                  r.snapshot_cues_json AS cues_json,
                  r.snapshot_tips_json AS tips_json
           FROM game_rounds r JOIN game_clips c ON c.id=r.clip_id
           WHERE r.game_id=? AND r.ordinal=?""",
        (game_id, ordinal),
    ).fetchone()
    if not row:
        fail(404, "ROUND_NOT_FOUND", "라운드를 찾을 수 없습니다.")
    return row


def _ai(row) -> tuple[dict | None, bool | None]:
    if row["ai_spatial"] is None or row["ai_temporal"] is None:
        return None, None
    policy = classify(row["ai_spatial"], row["ai_temporal"])
    return (
        {
            **policy,
            "spatial": row["ai_spatial"],
            "temporal": row["ai_temporal"],
            "model_version": row["ai_model_version"],
        },
        policy["verdict"] == row["label"],
    )


def revealed(row) -> dict:
    ai, ai_correct = _ai(row)
    skipped = row["skip_reason"] is not None
    answer = None if skipped else row["verdict"]
    return {
        "round": row["ordinal"],
        "answer": answer,
        "confidence": None if skipped else row["confidence"],
        "ground_truth": row["label"],
        "user_correct": None if skipped else row["verdict"] == row["label"],
        "ai_correct": ai_correct,
        "ai": ai,
        "explanation": row["explanation"],
        "cues": json.loads(row["cues_json"]),
        "tips": json.loads(row["tips_json"]),
        "technique": row["technique"],
        "points": row["points"],
        "streak": row["streak"],
        "time_used_seconds": _safe_time(row["time_used_seconds"]),
        "skipped": skipped,
        "skip_reason": row["skip_reason"],
    }


@router.get("/{game_id}/rounds/{ordinal}")
def get_round(game_id: str, ordinal: int, request: Request, session_id=Depends(owner)):
    with connect(request.app.state.settings) as db:
        owned(db, game_id, session_id)
        row = round_row(db, game_id, ordinal)
        terminal = row["verdict"] is not None or row["skip_reason"] is not None
        result = {
            "round": ordinal,
            "video_url": f"/api/v1/games/{game_id}/rounds/{ordinal}/video",
            "answered": row["verdict"] is not None,
        }
        if row["skip_reason"] is not None:
            result["skipped"] = True
        if terminal:
            result["result"] = revealed(row)
    return result


@router.get("/{game_id}/rounds/{ordinal}/video")
def get_video(game_id: str, ordinal: int, request: Request, session_id=Depends(owner)):
    with connect(request.app.state.settings) as db:
        owned(db, game_id, session_id)
        row = round_row(db, game_id, ordinal)
    path = Path(row["video_path"])
    if not path.is_file():
        fail(404, "CLIP_UNAVAILABLE", "게임 영상 파일이 없습니다.")
    return FileResponse(path, media_type="video/mp4", headers={"Cache-Control": "private, no-store"})


def _next_round(db, game_id: str) -> int | None:
    return db.execute(
        """SELECT min(ordinal) FROM game_rounds
           WHERE game_id=? AND verdict IS NULL AND skip_reason IS NULL""",
        (game_id,),
    ).fetchone()[0]


def _complete_if_done(db, game_id: str) -> None:
    if _next_round(db, game_id) is None:
        db.execute(
            "UPDATE games SET status='COMPLETED',completed_at=? WHERE id=?", (now(), game_id)
        )


@router.post("/{game_id}/rounds/{ordinal}/answer")
def answer_round(
    game_id: str, ordinal: int, body: Answer, request: Request, session_id=Depends(owner)
):
    with connect(request.app.state.settings) as db:
        db.execute("BEGIN IMMEDIATE")
        owned(db, game_id, session_id)
        row = round_row(db, game_id, ordinal)
        if row["verdict"] is not None:
            if row["verdict"] != body.verdict or row["confidence"] != body.confidence:
                fail(409, "ANSWER_ALREADY_SUBMITTED", "제출한 답변은 변경할 수 없습니다.")
            return revealed(row)
        if row["skip_reason"] is not None:
            fail(409, "ROUND_ALREADY_SKIPPED", "건너뛴 라운드에는 답변할 수 없습니다.")
        if ordinal != _next_round(db, game_id):
            fail(409, "ROUND_OUT_OF_ORDER", "라운드 순서대로 답변해 주세요.")
        previous = db.execute(
            "SELECT streak FROM game_rounds WHERE game_id=? AND ordinal=?",
            (game_id, ordinal - 1),
        ).fetchone()
        prior_streak = previous["streak"] if previous else 0
        correct = body.verdict == row["label"]
        streak = prior_streak + 1 if correct else 0
        points = 100 + 10 * prior_streak if correct else 0
        db.execute(
            """UPDATE game_rounds SET
                   verdict=?,confidence=?,answered_at=?,time_used_seconds=?,points=?,streak=?
               WHERE game_id=? AND ordinal=?""",
            (
                body.verdict, body.confidence, now(), body.time_used_seconds, points, streak,
                game_id, ordinal,
            ),
        )
        db.execute(
            """UPDATE games SET score=score+?,max_streak=max(max_streak,?) WHERE id=?""",
            (points, streak, game_id),
        )
        _complete_if_done(db, game_id)
        return revealed(round_row(db, game_id, ordinal))


@router.post("/{game_id}/rounds/{ordinal}/skip")
def skip_round(
    game_id: str, ordinal: int, body: Skip, request: Request, session_id=Depends(owner)
):
    with connect(request.app.state.settings) as db:
        db.execute("BEGIN IMMEDIATE")
        owned(db, game_id, session_id)
        row = round_row(db, game_id, ordinal)
        if row["skip_reason"] is not None:
            if row["skip_reason"] != body.reason:
                fail(409, "ROUND_ALREADY_SKIPPED", "건너뛰기 사유는 변경할 수 없습니다.")
            return revealed(row)
        if row["verdict"] is not None:
            fail(409, "ANSWER_ALREADY_SUBMITTED", "답변한 라운드는 건너뛸 수 없습니다.")
        if ordinal != _next_round(db, game_id):
            fail(409, "ROUND_OUT_OF_ORDER", "라운드 순서대로 진행해 주세요.")
        db.execute(
            """UPDATE game_rounds SET skip_reason=?,skipped_at=?,points=0,streak=0
               WHERE game_id=? AND ordinal=?""",
            (body.reason, now(), game_id, ordinal),
        )
        _complete_if_done(db, game_id)
        return revealed(round_row(db, game_id, ordinal))


def _grade(correct: int, played: int) -> str:
    if not played:
        return "N/A"
    percent = 100 * correct / played if played else 0
    if percent >= 90:
        return "S"
    if percent >= 80:
        return "A"
    if percent >= 70:
        return "B"
    if percent >= 60:
        return "C"
    return "D"


def _safe_time(value):
    return value if isinstance(value, (int, float)) and math.isfinite(value) and 0 <= value <= 86400 else None


def _average_time(times):
    valid = [value for raw in times if (value := _safe_time(raw)) is not None]
    return sum(valid) / len(valid) if valid else None


@router.get("/{game_id}/result")
def game_result(game_id: str, request: Request, session_id=Depends(owner)):
    with connect(request.app.state.settings) as db:
        game = owned(db, game_id, session_id)
        if game["status"] != "COMPLETED":
            fail(409, "GAME_INCOMPLETE", "모든 라운드를 먼저 완료해 주세요.")
        rounds = [revealed(round_row(db, game_id, i)) for i in range(1, game["total_rounds"] + 1)]
    played = [item for item in rounds if not item["skipped"]]
    evaluated = [item for item in played if item["ai_correct"] is not None]
    user_correct = sum(item["user_correct"] is True for item in played)
    ai_correct = sum(item["ai_correct"] is True for item in evaluated)
    times = [item["time_used_seconds"] for item in played if item["time_used_seconds"] is not None]
    categories = {"human_only": [], "ai_only": [], "both_correct": [], "both_wrong": []}
    for item in evaluated:
        key = (
            "both_correct"
            if item["user_correct"] and item["ai_correct"]
            else "human_only"
            if item["user_correct"]
            else "ai_only"
            if item["ai_correct"]
            else "both_wrong"
        )
        categories[key].append(item["round"])
    return {
        "id": game_id,
        "total_rounds": len(rounds),
        "user_score": user_correct,
        "ai_score": ai_correct if evaluated else None,
        "user_accuracy": user_correct / len(played) if played else None,
        "ai_accuracy": ai_correct / len(evaluated) if evaluated else None,
        "accuracy_count": len(played),
        "categories": categories,
        "score": game["score"],
        "max_streak": game["max_streak"],
        "grade": _grade(user_correct, len(played)),
        "average_time_seconds": _average_time(times),
        "skipped_rounds": len(rounds) - len(played),
        "played_rounds": len(played),
        "ai_evaluated_rounds": len(evaluated),
        "rounds": rounds,
    }


@router.delete("/{game_id}", status_code=204)
def delete_game(game_id: str, request: Request, session_id=Depends(owner)):
    with connect(request.app.state.settings) as db:
        db.execute("BEGIN IMMEDIATE")
        owned(db, game_id, session_id)
        db.execute("DELETE FROM games WHERE id=?", (game_id,))
    return Response(status_code=204)


def import_clips(settings, clips: list[dict]) -> int:
    """Register already-local curated clips without creating model scores."""
    with connect(settings) as db:
        db.execute("BEGIN IMMEDIATE")
        for clip in clips:
            path = Path(clip["video_path"]).resolve()
            if path.suffix.lower() != ".mp4" or not path.is_file():
                raise ValueError(f"clip is not an existing MP4: {path}")
            if clip["label"] not in ("REAL", "FAKE"):
                raise ValueError("clip label must be REAL or FAKE")
            db.execute(
                """INSERT INTO game_clips(
                       id,video_path,label,explanation,source,license,enabled,
                       difficulty,technique,cues_json,tips_json,
                       source_verified,license_verified
                   ) VALUES(?,?,?,?,?,?,1,?,?,?,?,?,?)
                   ON CONFLICT(id) DO UPDATE SET
                       video_path=excluded.video_path,label=excluded.label,
                       explanation=excluded.explanation,source=excluded.source,
                       license=excluded.license,difficulty=excluded.difficulty,
                       technique=excluded.technique,cues_json=excluded.cues_json,
                       tips_json=excluded.tips_json,
                       source_verified=excluded.source_verified,
                       license_verified=excluded.license_verified""",
                (
                    clip["id"], str(path), clip["label"], clip.get("explanation", ""),
                    clip["source"], clip["license"], clip.get("difficulty", "all"),
                    clip.get("technique"), json.dumps(clip.get("cues", []), ensure_ascii=False),
                    json.dumps(clip.get("tips", []), ensure_ascii=False),
                    int(clip.get("source_verified", False)), int(clip.get("license_verified", False)),
                ),
            )
    return len(clips)


if __name__ == "__main__":
    import argparse
    from .config import Settings
    from .db import initialize

    parser = argparse.ArgumentParser(description="Register local game metadata; AI scores are evaluated separately")
    parser.add_argument("manifest", type=Path)
    args = parser.parse_args()
    payload = json.loads(args.manifest.read_text(encoding="utf-8"))
    clips = payload if isinstance(payload, list) else payload["clips"]
    settings = Settings.from_env()
    initialize(settings)
    initialize_game(settings)
    print(f"Registered {import_clips(settings, clips)} clips. Manifest AI scores are ignored; use app.evaluate_games.")
