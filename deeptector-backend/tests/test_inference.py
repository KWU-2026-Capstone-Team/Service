from pathlib import Path
import subprocess

import cv2
import numpy as np
import pytest

from app.config import Settings
from app.inference import ModelAdapter, AnalysisError, probe


def make_video(path):
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), 10, (64, 64))
    assert writer.isOpened()
    for _ in range(10):
        writer.write(np.zeros((64, 64, 3), dtype=np.uint8))
    writer.release()


def test_demo_is_explicit_and_validates_video(tmp_path):
    path = tmp_path / "clip.mp4"
    make_video(path)
    settings = Settings(
        db_path=tmp_path / "db",
        storage_dir=tmp_path / "storage",
        model_dir=tmp_path / "model",
        inference_mode="demo",
    )
    stages = []
    out = ModelAdapter(settings).analyze(path, stages.append)
    assert out["is_demo"]
    assert out["artifacts"] == {}
    assert out["metadata"]["frame_count"] == 10
    assert out["warnings"] == ["DEMO_NOT_REAL_INFERENCE"]
    path.write_bytes(b"not a video")
    with pytest.raises(AnalysisError, match="디코딩"):
        probe(path)


def test_real_timeout_is_structured(tmp_path, monkeypatch):
    settings = Settings(
        db_path=tmp_path / "db", storage_dir=tmp_path / "storage", model_dir=tmp_path / "model"
    )

    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired("model", 1)

    monkeypatch.setattr(subprocess, "run", timeout)
    with pytest.raises(AnalysisError) as error:
        ModelAdapter(settings).analyze(Path("clip.mp4"), lambda _: None)
    assert error.value.code == "ANALYSIS_TIMEOUT"
