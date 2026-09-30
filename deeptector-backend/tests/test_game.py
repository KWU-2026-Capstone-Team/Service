import pytest
from fastapi.testclient import TestClient

from app.config import Settings
from app.db import connect
from app.main import create_app


@pytest.fixture
def client(tmp_path):
    settings = Settings(
        db_path=tmp_path / "db.sqlite3",
        storage_dir=tmp_path / "storage",
        model_dir=tmp_path / "models",
        inference_mode="demo",
    )
    with TestClient(create_app(settings)) as client:
        yield client


def headers(client):
    return {"Authorization": "Bearer " + client.post("/api/v1/sessions").json()["token"]}


def seed(client):
    with connect(client.app.state.settings) as db:
        for i in range(3):
            db.execute(
                "INSERT INTO game_clips VALUES(?,?,?,?,?,?,?,?,?,1)",
                (
                    str(i),
                    "/not-public.mp4",
                    "FAKE",
                    0.9,
                    0.8,
                    "test-model",
                    "attention, not proof",
                    "test",
                    "test-license",
                ),
            )


def test_no_invented_dataset(client):
    response = client.post("/api/v1/games", json={}, headers=headers(client))
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "GAME_DATASET_NOT_READY"


def test_game_no_leak_and_scoring(client):
    seed(client)
    auth = headers(client)
    game = client.post("/api/v1/games", json={"rounds": 3}, headers=auth).json()
    base = "/api/v1/games/" + game["id"]
    assert client.get(base, headers=headers(client)).status_code == 404
    unanswered = client.get(base + "/rounds/1", headers=auth).json()
    assert "ground_truth" not in str(unanswered)
    assert "spatial" not in str(unanswered)
    assert "FAKE" not in str(unanswered)
    assert client.get(base + "/result", headers=auth).status_code == 409
    payload = {"verdict": "FAKE", "confidence": "HIGH"}
    assert client.post(base + "/rounds/2/answer", json=payload, headers=auth).status_code == 409
    first = client.post(base + "/rounds/1/answer", json=payload, headers=auth)
    assert first.status_code == 200
    assert first.json()["user_correct"] is True
    assert client.post(base + "/rounds/1/answer", json=payload, headers=auth).json() == first.json()
    assert (
        client.post(base + "/rounds/1/answer", json={**payload, "verdict": "REAL"}, headers=auth).status_code
        == 409
    )
    client.post(base + "/rounds/2/answer", json=payload, headers=auth)
    client.post(base + "/rounds/3/answer", json={**payload, "verdict": "REAL"}, headers=auth)
    result = client.get(base + "/result", headers=auth).json()
    assert result["user_score"] == 2
    assert result["ai_score"] == 3
    assert result["categories"] == {
        "human_only": [],
        "ai_only": [3],
        "both_correct": [1, 2],
        "both_wrong": [],
    }
    assert client.get(base, headers=auth).json()["status"] == "COMPLETED"


def test_invalid_answers_and_auth(client):
    seed(client)
    assert client.post("/api/v1/games", json={}).status_code == 401
    auth = headers(client)
    assert client.post("/api/v1/games", json={"rounds": 0}, headers=auth).status_code == 422
    game = client.post("/api/v1/games", json={"rounds": 1}, headers=auth).json()
    response = client.post(
        f"/api/v1/games/{game['id']}/rounds/1/answer",
        headers=auth,
        json={"verdict": "UNKNOWN", "confidence": "HIGH"},
    )
    assert response.status_code == 422
