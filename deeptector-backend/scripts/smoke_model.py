"""Real model integration on a local still-image video; NOT an accuracy evaluation.

Run from service root with .venv-model/Scripts/python scripts/smoke_model.py <image>.
Uses temporary files, leaves input untouched, reports only metadata and scores.
"""

import json
import sys
import tempfile
from pathlib import Path

import cv2

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Settings
from app.inference import ModelAdapter


def main():
    screenshot = cv2.imread(sys.argv[1])
    if screenshot is None:
        raise ValueError("Supply an existing image with a face")
    with tempfile.TemporaryDirectory(prefix="deeptector-smoke-") as directory:
        video = Path(directory) / "still-frame-fixture.mp4"
        writer = cv2.VideoWriter(
            str(video), cv2.VideoWriter_fourcc(*"mp4v"), 8, (screenshot.shape[1], screenshot.shape[0])
        )
        if not writer.isOpened():
            raise RuntimeError("Video writer unavailable")
        for _ in range(16):
            writer.write(screenshot)
        writer.release()
        result = ModelAdapter(Settings.from_env()).analyze(video, lambda stage: print(stage, flush=True))
        print(
            json.dumps(
                {k: v for k, v in result.items() if k not in ("artifacts", "metadata")}, ensure_ascii=True
            )
        )
        print("artifacts:", {k: len(v) for k, v in result["artifacts"].items()})
        assert result["is_demo"] is False
        assert result["n_faces"] >= 4
        assert set(result["artifacts"]) == {
            "cam_spatial",
            "cam_temporal",
            "spatial_original",
            "temporal_original",
        }
        assert all(v.startswith(b"\x89PNG") for v in result["artifacts"].values())


if __name__ == "__main__":
    main()
