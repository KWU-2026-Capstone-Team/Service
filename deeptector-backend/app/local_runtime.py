"""Single-worker local launcher health and restart semantics (opt-in only)."""

import importlib.util
import json
import os
import threading
import time
from contextlib import contextmanager


def enabled():
    return os.getenv("DEEPTECTOR_LOCAL_RUNTIME") == "1"


def worker_health(settings):
    if not enabled():
        return {"managed": False, "ready": False, "reason": "Use the integrated launcher for worker health"}
    try:
        data = json.loads((settings.db_path.parent / "worker-status.json").read_text(encoding="utf-8"))
        ready = (0 <= time.time() - data["timestamp"] < 10 and data["mode"] == settings.inference_mode
                 and data.get("run_id") == os.getenv("DEEPTECTOR_RUN_ID"))
        return {"managed": True, "ready": ready, "mode": data["mode"], "model_loaded": False,
                "reason": "Worker prerequisites checked; model runs per job" if ready else "Worker heartbeat expired"}
    except (OSError, ValueError, KeyError, TypeError):
        return {"managed": True, "ready": False, "reason": "Worker has not started"}


def recover_interrupted(settings):
    # Only called while holding the exclusive integrated-worker lock.
    from .db import connect
    from .worker import _json, _remove_paths, iso_utc, utc_now
    with connect(settings) as db:
        db.execute("BEGIN IMMEDIATE")
        paths = [row[0] for row in db.execute("SELECT source_path FROM analyses WHERE status='PROCESSING'")]
        db.execute("UPDATE analyses SET status='FAILED',stage='FAILED',updated_at=?,error_json=? WHERE status='PROCESSING'",
                   (iso_utc(utc_now()), _json({"code": "WORKER_RESTARTED", "message": "Local worker was interrupted; upload again", "details": {}})))
    _remove_paths(settings, paths)


def _preflight(settings):
    names = ["cv2"] if settings.inference_mode == "demo" else ["cv2", "torch", "PIL", "facenet_pytorch", "timm"]
    missing = [name for name in names if importlib.util.find_spec(name) is None]
    if missing:
        raise RuntimeError(f"Worker dependencies missing: {missing}")
    if settings.inference_mode == "real":
        root = settings.model_dir
        if len(list((root / "models/spatial").glob("*.pt"))) != 5 or len(list((root / "models/temporal").glob("*.pt"))) != 6:
            raise RuntimeError("Real worker requires the 5+6 model checkpoints")
        if not all((root / path).is_file() for path in ("infer.py", "xai.py", "models/temporal/calibration.json")):
            raise RuntimeError("Real model code or calibration is missing")


@contextmanager
def local_worker(settings):
    if not enabled():
        yield
        return
    from .db import initialize
    initialize(settings)
    lock = (settings.db_path.parent / "worker.lock").open("a+b")
    if lock.tell() == 0:
        lock.write(b"0")
        lock.flush()
    lock.seek(0)
    locked = False
    stop = threading.Event()
    thread = None
    status = settings.db_path.parent / "worker-status.json"
    try:
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        locked = True
        status.unlink(missing_ok=True)
        _preflight(settings)
        recover_interrupted(settings)

        def beat():
            temporary = status.with_suffix(".tmp")
            while not stop.is_set():
                try:
                    temporary.write_text(json.dumps({"timestamp": time.time(), "mode": settings.inference_mode, "run_id": os.getenv("DEEPTECTOR_RUN_ID")}), encoding="utf-8")
                    temporary.replace(status)
                except OSError:
                    pass  # Health naturally expires if storage becomes unavailable.
                stop.wait(2)

        thread = threading.Thread(target=beat, daemon=True)
        thread.start()
        yield
    finally:
        stop.set()
        if thread:
            thread.join(timeout=3)
        if locked:
            status.unlink(missing_ok=True)
        lock.close()
