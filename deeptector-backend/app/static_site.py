"""Serve only an explicitly configured frontend export, never the repository root."""
import os
from pathlib import Path

from fastapi.staticfiles import StaticFiles


def mount_frontend(application):
    raw = os.getenv("DEEPTECTOR_FRONTEND_DIR")
    if not raw:
        return
    directory = Path(raw).resolve()
    # Prevent accidentally exposing backend source, local databases or labels.
    if not (directory / "index.html").is_file() or not (directory / "_next").is_dir():
        raise RuntimeError("DEEPTECTOR_FRONTEND_DIR must point to a built Next.js export (frontend/out)")
    if any((directory / name).exists() for name in ("app", "src", ".git", "data", "deeptector-backend")):
        raise RuntimeError("Refusing to serve a source or data directory")
    application.mount("/", StaticFiles(directory=directory, html=True, follow_symlink=False), name="frontend")
