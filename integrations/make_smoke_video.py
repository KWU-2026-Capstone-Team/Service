"""Encode the repository's existing verification image, NOT a benchmark video."""
from pathlib import Path
import cv2
import numpy as np
import shutil
import tempfile

root = Path(__file__).resolve().parent
image_path = root / "Service/exports/deeptector-screens/05-xai-detail.png"
image = cv2.imdecode(np.frombuffer(image_path.read_bytes(), dtype=np.uint8), cv2.IMREAD_COLOR)
if image is None:
    raise RuntimeError("Reference verification image missing")
output = root / "local-runtime/model_smoke_fixture.mp4"
output.parent.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="verivid-fixture-") as directory:
    video = Path(directory) / "model_smoke_fixture.mp4"
    writer = cv2.VideoWriter(str(video), cv2.VideoWriter_fourcc(*"mp4v"), 8, (image.shape[1], image.shape[0]))
    if not writer.isOpened():
        raise RuntimeError("Cannot create MP4")
    for _ in range(16):
        writer.write(image)
    writer.release()
    shutil.copyfile(video, output)
    blank = Path(directory) / "no_face_fixture.mp4"
    writer = cv2.VideoWriter(str(blank), cv2.VideoWriter_fourcc(*"mp4v"), 8, (640, 360))
    if not writer.isOpened():
        raise RuntimeError("Cannot create no-face fixture")
    for _ in range(16):
        writer.write(np.full((360, 640, 3), 128, dtype=np.uint8))
    writer.release()
    shutil.copyfile(blank, output.with_name("no_face_fixture.mp4"))
print(output)
