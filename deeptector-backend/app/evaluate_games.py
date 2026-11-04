"""Explicit, resumable real-model evaluation of registered local game clips."""

import argparse
from dataclasses import replace
from pathlib import Path

from .config import Settings
from .db import connect, initialize
from .game import initialize_game, now
from .inference import ModelAdapter
from .policy import classify


def record_evaluation(settings, clip_id, result):
    if result.get("is_demo") is not False or not result.get("model_version"):
        raise ValueError("Only real model results with a model version may be recorded")
    classify(result["spatial"], result["temporal"])
    with connect(settings) as db:
        db.execute(
            """INSERT INTO game_clip_evaluations(clip_id,model_version,spatial,temporal,evaluated_at)
               VALUES(?,?,?,?,?) ON CONFLICT(clip_id,model_version) DO UPDATE SET
               spatial=excluded.spatial,temporal=excluded.temporal,evaluated_at=excluded.evaluated_at""",
            (clip_id, result["model_version"], result["spatial"], result["temporal"], now()),
        )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-dir", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, default=Path(__file__).resolve().parents[1] / "data/integrated")
    parser.add_argument("--clip-id", action="append", help="Evaluate only these IDs; repeatable")
    parser.add_argument("--force", action="store_true", help="Re-evaluate clips that already have an evaluation")
    args = parser.parse_args()
    data = args.data_dir.resolve()
    settings = replace(Settings.from_env(), inference_mode="real", model_dir=args.model_dir.resolve(),
                       db_path=data / "deeptector.sqlite3", storage_dir=data / "storage")
    initialize(settings)
    initialize_game(settings)
    with connect(settings) as db:
        clips = list(db.execute("SELECT id,video_path FROM game_clips WHERE enabled=1"))
        evaluated = {row[0] for row in db.execute("SELECT DISTINCT clip_id FROM game_clip_evaluations")}
    if args.clip_id:
        missing = set(args.clip_id) - {row["id"] for row in clips}
        if missing:
            parser.error(f"Unknown or disabled clip IDs: {sorted(missing)}")
        clips = [row for row in clips if row["id"] in args.clip_id]
    adapter = ModelAdapter(settings)
    for clip in clips:
        if clip["id"] in evaluated and not args.force:
            print(f"skip {clip['id']}: existing evaluation (use --force for a new model)")
            continue
        print(f"evaluating {clip['id']}", flush=True)
        result = adapter.analyze(Path(clip["video_path"]), lambda stage: print(stage, flush=True))
        record_evaluation(settings, clip["id"], result)
        print(f"saved {clip['id']}: {result['model_version']}", flush=True)


if __name__ == "__main__":
    main()
