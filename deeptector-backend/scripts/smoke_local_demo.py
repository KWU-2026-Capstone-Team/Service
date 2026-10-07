"""Generate a disposable synthetic video and test the real browser client over HTTP."""
from pathlib import Path
import subprocess
import tempfile

import cv2
import numpy as np


def main():
    with tempfile.TemporaryDirectory(prefix="deeptector-local-demo-") as directory:
        path = Path(directory) / "synthetic.mp4"
        writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), 8, (128, 128))
        if not writer.isOpened():
            raise RuntimeError("MP4 encoder unavailable")
        try:
            for _ in range(16):
                writer.write(np.zeros((128, 128, 3), dtype=np.uint8))
        finally:
            writer.release()
        repo = Path(__file__).resolve().parents[2]
        subprocess.run(["node", str(repo / "web" / "smoke.mjs"), str(path)], cwd=repo, check=True)


if __name__ == "__main__":
    main()
