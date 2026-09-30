"""Generate a shareable API contract directly from registered FastAPI routes."""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.main import app

target = Path(__file__).resolve().parents[1] / "docs/openapi.json"
target.write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(target)
