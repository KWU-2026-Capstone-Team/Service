"""Temporary DB + genuine worker + API lifecycle. No inference accuracy claim."""

import json
import sys
import tempfile
from dataclasses import replace
from pathlib import Path

import cv2
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.config import Settings
from app.main import create_app
from app.worker import run_once


def main():
    image = cv2.imread(sys.argv[1])
    assert image is not None
    with tempfile.TemporaryDirectory(prefix="deeptector-api-smoke-") as directory:
        root = Path(directory)
        settings = replace(Settings.from_env(), db_path=root / "db.sqlite3", storage_dir=root / "storage")
        video = root / "fixture.mp4"
        writer = cv2.VideoWriter(
            str(video), cv2.VideoWriter_fourcc(*"mp4v"), 8, (image.shape[1], image.shape[0])
        )
        assert writer.isOpened()
        for _ in range(16):
            writer.write(image)
        writer.release()
        with TestClient(create_app(settings)) as client:
            token = client.post("/api/v1/sessions").json()["token"]
            headers = {"Authorization": "Bearer " + token}
            with video.open("rb") as file:
                response = client.post(
                    "/api/v1/analyses", headers=headers, files={"video": ("fixture.mp4", file, "video/mp4")}
                )
            assert response.status_code == 202, response.text
            url = "/api/v1/analyses/" + response.json()["id"]
            assert client.get(url, headers=headers).json()["status"] == "QUEUED"
            print("API upload queued; running worker", flush=True)
            assert run_once(settings)
            result = client.get(url, headers=headers).json()
            assert result["status"] == "COMPLETED", result
            assert result["result"]["is_demo"] is False
            assert len(result["artifacts"]) == 4
            for artifact in result["artifacts"]:
                img = client.get(artifact["url"], headers=headers)
                assert img.status_code == 200 and img.content.startswith(b"\x89PNG")
                assert client.get(artifact["url"]).status_code == 401
            assert not list((settings.storage_dir / "sources").glob("*"))
            print(
                json.dumps(
                    {
                        "status": result["status"],
                        "n_faces": result["result"]["n_faces"],
                        "model": result["result"]["model_version"],
                        "policy": result["result"]["policy"],
                        "artifact_count": len(result["artifacts"]),
                    }
                )
            )
            assert client.delete(url, headers=headers).status_code == 204
            assert client.get(url, headers=headers).status_code == 404
            print("PASS: upload -> durable queue -> real worker -> result -> PNGs -> deletion")


if __name__ == "__main__":
    main()
