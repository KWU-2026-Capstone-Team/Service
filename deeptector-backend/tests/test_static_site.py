import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.static_site import mount_frontend


def test_export_mount_does_not_replace_api(tmp_path, monkeypatch):
    (tmp_path / "index.html").write_text("<h1>Integrated</h1>", encoding="utf8")
    (tmp_path / "_next").mkdir()
    (tmp_path / "game").mkdir()
    (tmp_path / "game" / "index.html").write_text("game", encoding="utf8")
    monkeypatch.setenv("DEEPTECTOR_FRONTEND_DIR", str(tmp_path))
    app = FastAPI()

    @app.get("/health/live")
    def live():
        return {"status": "ok"}

    mount_frontend(app)
    with TestClient(app) as client:
        assert client.get("/").text == "<h1>Integrated</h1>"
        assert client.get("/game/").text == "game"
        assert client.get("/health/live").json() == {"status": "ok"}
        assert client.get("/data/deeptector.sqlite3").status_code == 404
        assert client.get("/src/data/videos.json").status_code == 404


def test_refuse_source_directory(tmp_path, monkeypatch):
    (tmp_path / "index.html").touch()
    (tmp_path / "_next").mkdir()
    (tmp_path / "data").mkdir()
    monkeypatch.setenv("DEEPTECTOR_FRONTEND_DIR", str(tmp_path))
    with pytest.raises(RuntimeError, match="source or data"):
        mount_frontend(FastAPI())
