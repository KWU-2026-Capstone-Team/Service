"""Curated, labelled clips only. Answers remain hidden until a round is submitted."""

import argparse
import json
import secrets
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from .db import connect
from .policy import classify

router = APIRouter(prefix="/api/v1/games", tags=["game"])
SQL = Path(__file__).resolve().parent.parent / "migrations/002_games.sql"


def initialize_game(settings):
    with connect(settings) as db:
        db.executescript(SQL.read_text(encoding="utf-8"))


def owner(request: Request):
    from .main import get_session

    return get_session(request, request.headers.get("authorization"))


def fail(status, code, message):
    raise HTTPException(status, detail={"code": code, "message": message})


def owned(db, game_id, session_id):
    game = db.execute("SELECT * FROM games WHERE id=? AND session_id=?", (game_id, session_id)).fetchone()
    if not game:
        fail(404, "GAME_NOT_FOUND", "게임을 찾을 수 없습니다.")
    return game


class StartGame(BaseModel):
    rounds: int = Field(default=10, ge=1, le=10)


class Answer(BaseModel):
    verdict: Literal["REAL", "FAKE"]
    confidence: Literal["LOW", "MEDIUM", "HIGH"]


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
        clips = list(db.execute("SELECT * FROM game_clips WHERE enabled=1"))
        if len(clips) < body.rounds:
            fail(409, "GAME_DATASET_NOT_READY", "정답이 검증된 게임 영상이 부족합니다.")
        clips = secrets.SystemRandom().sample(clips, body.rounds)
        game_id = str(uuid.uuid4())
        db.execute(
            "INSERT INTO games(id,session_id,status,total_rounds,created_at) VALUES(?,?,'ACTIVE',?,?)",
            (game_id, session_id, body.rounds, datetime.now(timezone.utc).isoformat()),
        )
        for ordinal, clip in enumerate(clips, 1):
            db.execute(
                "INSERT INTO game_rounds(game_id,ordinal,clip_id) VALUES(?,?,?)",
                (game_id, ordinal, clip["id"]),
            )
    return {"id": game_id, "status": "ACTIVE", "total_rounds": body.rounds, "next_round": 1}


@router.get("/{game_id}")
def get_game(game_id: str, request: Request, session_id=Depends(owner)):
    with connect(request.app.state.settings) as db:
        game = owned(db, game_id, session_id)
        answered = db.execute(
            "SELECT count(*) FROM game_rounds WHERE game_id=? AND verdict IS NOT NULL", (game_id,)
        ).fetchone()[0]
    return {
        "id": game_id,
        "status": game["status"],
        "total_rounds": game["total_rounds"],
        "answered_rounds": answered,
        "next_round": answered + 1 if answered < game["total_rounds"] else None,
    }


def round_row(db, game_id, ordinal):
    row = db.execute(
        "SELECT r.*,c.video_path,c.label,c.spatial,c.temporal,c.model_version,c.explanation FROM game_rounds r JOIN game_clips c ON c.id=r.clip_id WHERE r.game_id=? AND r.ordinal=?",
        (game_id, ordinal),
    ).fetchone()
    if not row:
        fail(404, "ROUND_NOT_FOUND", "라운드를 찾을 수 없습니다.")
    return row


def revealed(row):
    policy = classify(row["spatial"], row["temporal"])
    return {
        "round": row["ordinal"],
        "answer": row["verdict"],
        "confidence": row["confidence"],
        "ground_truth": row["label"],
        "user_correct": row["verdict"] == row["label"],
        "ai_correct": policy["verdict"] == row["label"],
        "ai": {
            **policy,
            "spatial": row["spatial"],
            "temporal": row["temporal"],
            "model_version": row["model_version"],
        },
        "explanation": row["explanation"],
    }


@router.get("/{game_id}/rounds/{ordinal}")
def get_round(game_id: str, ordinal: int, request: Request, session_id=Depends(owner)):
    with connect(request.app.state.settings) as db:
        owned(db, game_id, session_id)
        row = round_row(db, game_id, ordinal)
        result = {
            "round": ordinal,
            "video_url": f"/api/v1/games/{game_id}/rounds/{ordinal}/video",
            "answered": row["verdict"] is not None,
        }
        if row["verdict"] is not None:
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


@router.post("/{game_id}/rounds/{ordinal}/answer")
def answer_round(game_id: str, ordinal: int, body: Answer, request: Request, session_id=Depends(owner)):
    with connect(request.app.state.settings) as db:
        db.execute("BEGIN IMMEDIATE")
        owned(db, game_id, session_id)
        row = round_row(db, game_id, ordinal)
        if row["verdict"] is not None:
            if row["verdict"] != body.verdict or row["confidence"] != body.confidence:
                fail(409, "ANSWER_ALREADY_SUBMITTED", "제출한 답변은 변경할 수 없습니다.")
            return revealed(row)
        next_round = db.execute(
            "SELECT min(ordinal) FROM game_rounds WHERE game_id=? AND verdict IS NULL", (game_id,)
        ).fetchone()[0]
        if ordinal != next_round:
            fail(409, "ROUND_OUT_OF_ORDER", "라운드 순서대로 답변해 주세요.")
        db.execute(
            "UPDATE game_rounds SET verdict=?,confidence=?,answered_at=? WHERE game_id=? AND ordinal=?",
            (body.verdict, body.confidence, datetime.now(timezone.utc).isoformat(), game_id, ordinal),
        )
        remaining = db.execute(
            "SELECT count(*) FROM game_rounds WHERE game_id=? AND verdict IS NULL", (game_id,)
        ).fetchone()[0]
        if not remaining:
            db.execute("UPDATE games SET status='COMPLETED' WHERE id=?", (game_id,))
        return revealed(round_row(db, game_id, ordinal))


@router.get("/{game_id}/result")
def game_result(game_id: str, request: Request, session_id=Depends(owner)):
    with connect(request.app.state.settings) as db:
        game = owned(db, game_id, session_id)
        if game["status"] != "COMPLETED":
            fail(409, "GAME_INCOMPLETE", "모든 라운드를 먼저 완료해 주세요.")
        rounds = [revealed(round_row(db, game_id, i)) for i in range(1, game["total_rounds"] + 1)]
    user = sum(r["user_correct"] for r in rounds)
    ai = sum(r["ai_correct"] for r in rounds)
    categories = {"human_only": [], "ai_only": [], "both_correct": [], "both_wrong": []}
    for r in rounds:
        key = (
            "both_correct"
            if r["user_correct"] and r["ai_correct"]
            else "human_only"
            if r["user_correct"]
            else "ai_only"
            if r["ai_correct"]
            else "both_wrong"
        )
        categories[key].append(r["round"])
    return {
        "id": game_id,
        "total_rounds": len(rounds),
        "user_score": user,
        "ai_score": ai,
        "user_accuracy": user / len(rounds),
        "ai_accuracy": ai / len(rounds),
        "categories": categories,
        "rounds": rounds,
    }


def import_clips(settings, manifest_path):
    """Local operator command; no public endpoint can create labels or AI scores."""
    manifest_path = Path(manifest_path).resolve()
    clips = json.loads(manifest_path.read_text(encoding="utf-8"))
    from .inference import probe

    with connect(settings) as db:
        for clip in clips:
            path = (manifest_path.parent / clip["video_path"]).resolve()
            if path.suffix.lower() != ".mp4" or not path.is_file():
                raise ValueError("Curated clip must be an existing MP4")
            probe(path)
            if (
                clip["label"] not in ("REAL", "FAKE")
                or not clip.get("source")
                or not clip.get("license")
                or not clip.get("model_version")
            ):
                raise ValueError("label, source, license, model_version required")
            classify(float(clip["spatial"]), float(clip["temporal"]))
            db.execute(
                "INSERT INTO game_clips(id,video_path,label,spatial,temporal,model_version,explanation,source,license,enabled) VALUES(?,?,?,?,?,?,?,?,?,1)",
                (
                    clip["id"],
                    str(path),
                    clip["label"],
                    clip["spatial"],
                    clip["temporal"],
                    clip["model_version"],
                    clip.get("explanation", ""),
                    clip["source"],
                    clip["license"],
                ),
            )


if __name__ == "__main__":
    from .config import Settings
    from .db import initialize

    parser = argparse.ArgumentParser(description="Import verified game clips (insert only)")
    parser.add_argument("manifest")
    settings = Settings.from_env()
    initialize(settings)
    initialize_game(settings)
    import_clips(settings, parser.parse_args().manifest)
