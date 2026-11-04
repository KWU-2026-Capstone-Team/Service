"""One-origin local app + worker. Existing demo and research data remain untouched."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=5173)
    parser.add_argument("--mode", choices=("demo", "real"), default="demo")
    parser.add_argument("--model-dir", type=Path)
    parser.add_argument("--worker-python", type=Path)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("port must be 1024..65535")
    root = Path(__file__).resolve().parents[1]
    export = root.parent / "frontend" / "out"
    if not (export / "index.html").is_file():
        parser.error("Build frontend first: cd frontend; npm ci; npm run build")
    if args.mode == "real" and (not args.model_dir or not args.model_dir.is_dir()):
        parser.error("real mode requires --model-dir containing the trusted model code and weights")
    worker_python = str(args.worker_python.resolve()) if args.worker_python else sys.executable
    if not Path(worker_python).is_file():
        parser.error("worker Python does not exist")
    with socket.socket() as sock:
        try:
            sock.bind(("127.0.0.1", args.port))
        except OSError:
            parser.error(f"Port {args.port} is occupied; stop your previous server or choose --port")
    data = root / "data" / "integrated"
    env = os.environ.copy()
    env.update(
        DEEPTECTOR_INFERENCE_MODE=args.mode,
        DEEPTECTOR_DATA_DIR=str(data),
        DEEPTECTOR_DB_PATH=str(data / "deeptector.sqlite3"),
        DEEPTECTOR_STORAGE_DIR=str(data / "storage"),
        DEEPTECTOR_FRONTEND_DIR=str(export),
        DEEPTECTOR_MAX_UPLOAD_BYTES=str(50 * 1024 * 1024),
        DEEPTECTOR_CORS_ORIGINS=f"http://127.0.0.1:{args.port},http://localhost:{args.port}",
        PYTHONIOENCODING="utf-8",
        DEEPTECTOR_LOCAL_RUNTIME="1",
        DEEPTECTOR_RUN_ID=str(uuid.uuid4()),
    )
    if args.model_dir:
        env["DEEPTECTOR_MODEL_DIR"] = str(args.model_dir.resolve())
    processes = []
    try:
        api = subprocess.Popen([sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", str(args.port)], cwd=root, env=env)
        processes.append(api)
        ready = False
        for _ in range(40):
            if api.poll() is not None:
                break
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{args.port}/health/ready", timeout=1) as response:
                    ready = response.status == 200
                    if ready:
                        break
            except (urllib.error.URLError, TimeoutError):
                time.sleep(0.25)
        if not ready:
            print("API startup failed; worker was not started.", flush=True)
            return 1
        worker = subprocess.Popen([worker_python, "-m", "app.worker"], cwd=root, env=env)
        processes.append(worker)
        worker_ready = False
        for _ in range(80):
            if worker.poll() is not None:
                break
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{args.port}/health/worker", timeout=1) as response:
                    worker_ready = response.status == 200
                    if worker_ready:
                        break
            except (urllib.error.URLError, TimeoutError):
                time.sleep(0.25)
        if not worker_ready:
            print("Worker startup failed; check dependencies/model files or another integrated worker.", flush=True)
            return 1
        print(f"DeepTector ({args.mode}): http://127.0.0.1:{args.port}/", flush=True)
        print(f"Data: {data}\nCtrl+C stops API and worker. No public tunnel is opened.", flush=True)
        while all(process.poll() is None for process in processes):
            time.sleep(0.5)
        print("A service exited; stopping the companion process.", flush=True)
        return 1
    except KeyboardInterrupt:
        return 0
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    raise SystemExit(main())
