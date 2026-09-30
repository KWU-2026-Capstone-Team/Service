CREATE TABLE IF NOT EXISTS game_clips (
    id TEXT PRIMARY KEY,
    video_path TEXT NOT NULL,
    label TEXT NOT NULL CHECK(label IN ('REAL','FAKE')),
    spatial REAL NOT NULL CHECK(spatial BETWEEN 0 AND 1),
    temporal REAL NOT NULL CHECK(temporal BETWEEN 0 AND 1),
    model_version TEXT NOT NULL,
    explanation TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL,
    license TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1))
);
CREATE TABLE IF NOT EXISTS games (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE','COMPLETED')),
    total_rounds INTEGER NOT NULL CHECK(total_rounds BETWEEN 1 AND 10),
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS games_owner ON games(session_id,created_at);
CREATE TABLE IF NOT EXISTS game_rounds (
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 10),
    clip_id TEXT NOT NULL REFERENCES game_clips(id),
    verdict TEXT CHECK(verdict IN ('REAL','FAKE')),
    confidence TEXT CHECK(confidence IN ('LOW','MEDIUM','HIGH')),
    answered_at TEXT,
    PRIMARY KEY(game_id,ordinal),
    UNIQUE(game_id,clip_id),
    CHECK((verdict IS NULL AND confidence IS NULL AND answered_at IS NULL) OR
          (verdict IS NOT NULL AND confidence IS NOT NULL AND answered_at IS NOT NULL))
);
