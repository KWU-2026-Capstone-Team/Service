"""Disposable live HTTP smoke for the unified local service. No accuracy claim."""
from __future__ import annotations

import argparse
from pathlib import Path
import tempfile
import time

import cv2
import httpx
import numpy as np


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:5173")
    args = parser.parse_args()
    with httpx.Client(base_url=args.base, timeout=20) as client:
        for path in ("/", "/analysis/", "/game/", "/result/", "/history/"):
            response = client.get(path)
            assert response.status_code == 200, (path, response.status_code)
            assert "text/html" in response.headers["content-type"]
        for path in ("/src/data/videos.json", "/data/integrated/deeptector.sqlite3", "/public/videos/"):
            assert client.get(path).status_code == 404, path
        assert client.get("/api/v1/model-info").json()["inference_mode"] == "demo"
        assert client.get("/health/worker").json()["ready"] is True
        for route in ("analysis", "game", "history", "result"):
            assert client.get(f"/{route}/__next.{route}.__PAGE__.txt").status_code == 200
        token = client.post("/api/v1/sessions").json()["token"]
        headers = {"Authorization": "Bearer " + token}
        print("PASS: static routes, private files excluded, anonymous session", flush=True)
        game = client.post("/api/v1/games", headers=headers, json={"rounds": 15, "difficulty": "all"})
        assert game.status_code == 201, game.text
        game_id = game.json()["id"]
        game_path = f"/api/v1/games/{game_id}"
        try:
            assert client.get(game_path).status_code == 401
            other = client.post("/api/v1/sessions").json()["token"]
            assert client.get(game_path, headers={"Authorization": "Bearer " + other}).status_code == 404
            for ordinal in range(1, 16):
                path = f"{game_path}/rounds/{ordinal}"
                question = client.get(path, headers=headers).json()
                assert "ground_truth" not in question and "result" not in question
                if ordinal == 1:
                    video = client.get(question["video_url"], headers={**headers, "Range": "bytes=0-31"})
                    assert video.status_code == 206 and len(video.content) == 32
                    skipped = client.post(path + "/skip", headers=headers, json={"reason": "VIDEO_UNAVAILABLE"})
                    assert skipped.status_code == 200, skipped.text
                else:
                    answer = {"verdict": "REAL", "confidence": "MEDIUM", "time_used_seconds": 1.2}
                    response = client.post(path + "/answer", headers=headers, json=answer)
                    assert response.status_code == 200, response.text
                    assert client.post(path + "/answer", headers=headers, json=answer).json() == response.json()
                    assert response.json()["ai"] is None
            result = client.get(game_path + "/result", headers=headers)
            assert result.status_code == 200, result.text
            data = result.json()
            assert data["ai_evaluated_rounds"] == 0 and data["ai_accuracy"] is None
            assert data["played_rounds"] == 14 and data["skipped_rounds"] == 1
            assert client.get(game_path, headers=headers).json()["status"] == "COMPLETED"
            assert any(g["id"] == game_id for g in client.get("/api/v1/games", headers=headers).json()["items"])
            print("PASS: 15 rounds, auth, video Range, skip, idempotent answer, server result/history, no invented AI", flush=True)
        finally:
            response = client.delete(game_path, headers=headers)
            assert response.status_code == 204, response.text
        assert client.get(game_path, headers=headers).status_code == 404
        with tempfile.TemporaryDirectory(prefix="deeptector-integrated-smoke-") as directory:
            path = Path(directory) / "synthetic.mp4"
            writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), 8, (128, 128))
            assert writer.isOpened()
            try:
                for _ in range(16):
                    writer.write(np.zeros((128, 128, 3), dtype=np.uint8))
            finally:
                writer.release()
            with path.open("rb") as video:
                response = client.post("/api/v1/analyses", headers=headers, files={"video": ("synthetic.mp4", video, "video/mp4")})
            assert response.status_code == 202, response.text
            analysis_path = "/api/v1/analyses/" + response.json()["id"]
            for _ in range(60):
                result = client.get(analysis_path, headers=headers).json()
                if result["status"] in ("COMPLETED", "FAILED"):
                    break
                time.sleep(0.5)
            assert result["status"] == "COMPLETED", result
            assert result["result"]["is_demo"] is True
            assert client.delete(analysis_path, headers=headers).status_code == 204
            assert client.get(analysis_path, headers=headers).status_code == 404
        print("PASS: demo upload -> worker -> traffic-light result -> delete; game deleted", flush=True)


if __name__ == "__main__":
    main()
