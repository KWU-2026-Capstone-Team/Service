"""Register clips from a local deepGame reference checkout.

Usage: python -m app.import_deepgame PATH_TO_DEEPGAME_REFERENCE
"""

from __future__ import annotations

import argparse
import json
from dataclasses import replace
from pathlib import Path

from .config import Settings
from .db import initialize
from .game import import_clips, initialize_game


LICENSE = "local-review-only; redistribution permission unverified"
SOURCE_COMMIT = "85d18621a8d82e7299036aa8725b150932b1a617"


def load_reference(reference_folder: str | Path) -> list[dict]:
    root = Path(reference_folder).resolve()
    manifest = root / "src" / "data" / "videos.json"
    video_root = root / "public" / "videos"
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    source = f"deepGame-reference@{SOURCE_COMMIT}"
    clips = []
    for item in payload.get("videos", []):
        filename = Path(str(item["src"]).replace("\\", "/")).name
        path = (video_root / filename).resolve()
        if path.parent != video_root.resolve():
            raise ValueError(f"video path escapes public/videos: {item['src']}")
        clips.append(
            {
                "id": item["id"],
                "video_path": str(path),
                "label": str(item["label"]).upper(),
                "explanation": item.get("explanation", ""),
                "difficulty": item.get("difficulty", "all"),
                "technique": item.get("technique"),
                "cues": item.get("cues", []),
                "tips": item.get("tips", []),
                "source": source,
                "license": LICENSE,
                "source_verified": True,
                "license_verified": False,
            }
        )
    if len(clips) != 24:
        raise ValueError(f"expected 24 deepGame clips, found {len(clips)}")
    return clips


def main() -> None:
    parser = argparse.ArgumentParser(description="Register a local deepGame reference folder")
    parser.add_argument("reference_folder", help="folder containing src/data/videos.json and public/videos")
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "data" / "integrated",
        help="service data directory (default: backend data/integrated)",
    )
    args = parser.parse_args()
    data_root = args.data_dir.resolve()
    settings = replace(Settings.from_env(), db_path=data_root / "deeptector.sqlite3", storage_dir=data_root / "storage")
    initialize(settings)
    initialize_game(settings)
    count = import_clips(settings, load_reference(args.reference_folder))
    print(f"registered {count} local deepGame clips in {settings.db_path}")


if __name__ == "__main__":
    main()
