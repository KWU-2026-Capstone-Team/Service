"""Run loopback API and worker together; never expose the user's PC publicly."""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys
import time


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    env = os.environ.copy()
    env.update(
        DEEPTECTOR_INFERENCE_MODE="demo",
        DEEPTECTOR_DATA_DIR=str(root / "data" / "local-demo"),
        DEEPTECTOR_DB_PATH=str(root / "data" / "local-demo" / "deeptector.sqlite3"),
        DEEPTECTOR_STORAGE_DIR=str(root / "data" / "local-demo" / "storage"),
        DEEPTECTOR_CORS_ORIGINS="https://kwu-2026-capstone-team.github.io,http://127.0.0.1:5173,http://localhost:5173",
        DEEPTECTOR_MAX_UPLOAD_BYTES=str(50 * 1024 * 1024),
    )
    api = subprocess.Popen(
        [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8000"],
        cwd=root, env=env,
    )
    worker = None
    try:
        time.sleep(2)
        if api.poll() is not None:
            print("API could not start. Check port 8000 and dependencies.")
            return 1
        worker = subprocess.Popen([sys.executable, "-m", "app.worker"], cwd=root, env=env)
        print("DEMO ONLY. Open https://kwu-2026-capstone-team.github.io/Service/", flush=True)
        print("Both API and worker stop with Ctrl+C. Data: " + env["DEEPTECTOR_DATA_DIR"], flush=True)
        while api.poll() is None and worker.poll() is None:
            time.sleep(0.5)
        return 1
    except KeyboardInterrupt:
        return 0
    finally:
        for process in (api, worker):
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
