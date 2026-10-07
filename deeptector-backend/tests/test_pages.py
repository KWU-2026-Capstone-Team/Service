from fastapi.testclient import TestClient

from app.config import Settings
from app.main import create_app


def test_pages_cors_preflight_and_auth(tmp_path):
    origin = "https://kwu-2026-capstone-team.github.io"
    settings = Settings(
        db_path=tmp_path / "test.sqlite3", storage_dir=tmp_path / "storage",
        model_dir=tmp_path / "models", inference_mode="demo", cors_origins=(origin,),
    )
    with TestClient(create_app(settings)) as client:
        for method in ("POST", "GET", "DELETE"):
            response = client.options("/api/v1/analyses", headers={
                "Origin": origin, "Access-Control-Request-Method": method,
                "Access-Control-Request-Headers": "authorization,content-type",
            })
            assert response.status_code == 200
            assert response.headers["access-control-allow-origin"] == origin
        denied = client.options("/api/v1/analyses", headers={
            "Origin": "https://untrusted.example", "Access-Control-Request-Method": "POST",
        })
        assert denied.status_code == 400
        assert "access-control-allow-origin" not in denied.headers
        session = client.post("/api/v1/sessions", headers={"Origin": origin})
        assert session.status_code == 201
        token = session.json()["token"]
        history = client.get("/api/v1/analyses", headers={"Origin": origin, "Authorization": f"Bearer {token}"})
        assert history.status_code == 200
        assert history.headers["access-control-allow-origin"] == origin
