CREATE TABLE IF NOT EXISTS game_clips (
    id TEXT PRIMARY KEY,
    video_path TEXT NOT NULL,
    label TEXT NOT NULL CHECK(label IN ('REAL','FAKE')),
    explanation TEXT NOT NULL DEFAULT '',
    source TEXT NOT NULL,
    license TEXT NOT NULL,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)),
    difficulty TEXT NOT NULL DEFAULT 'all' CHECK(difficulty IN ('all','easy','medium','hard')),
    technique TEXT,
    cues_json TEXT NOT NULL DEFAULT '[]',
    tips_json TEXT NOT NULL DEFAULT '[]',
    source_verified INTEGER NOT NULL DEFAULT 0 CHECK(source_verified IN (0,1)),
    license_verified INTEGER NOT NULL DEFAULT 0 CHECK(license_verified IN (0,1))
);
CREATE TABLE IF NOT EXISTS game_clip_evaluations (
    clip_id TEXT NOT NULL REFERENCES game_clips(id) ON DELETE CASCADE,
    model_version TEXT NOT NULL,
    spatial REAL CHECK(spatial BETWEEN 0 AND 1),
    temporal REAL CHECK(temporal BETWEEN 0 AND 1),
    evaluated_at TEXT,
    PRIMARY KEY(clip_id,model_version),
    CHECK(spatial IS NOT NULL OR temporal IS NOT NULL)
);
CREATE TABLE IF NOT EXISTS games (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE','COMPLETED')),
    total_rounds INTEGER NOT NULL CHECK(total_rounds BETWEEN 1 AND 30),
    difficulty TEXT NOT NULL DEFAULT 'all' CHECK(difficulty IN ('all','easy','medium','hard')),
    score INTEGER NOT NULL DEFAULT 0 CHECK(score >= 0),
    max_streak INTEGER NOT NULL DEFAULT 0 CHECK(max_streak >= 0),
    created_at TEXT NOT NULL,
    completed_at TEXT
);
CREATE INDEX IF NOT EXISTS games_owner ON games(session_id,created_at DESC);
CREATE TABLE IF NOT EXISTS game_rounds (
    game_id TEXT NOT NULL REFERENCES games(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK(ordinal BETWEEN 1 AND 30),
    clip_id TEXT NOT NULL REFERENCES game_clips(id),
    verdict TEXT CHECK(verdict IN ('REAL','FAKE')),
    confidence TEXT CHECK(confidence IN ('LOW','MEDIUM','HIGH')),
    answered_at TEXT,
    time_used_seconds REAL CHECK(time_used_seconds IS NULL OR time_used_seconds BETWEEN 0 AND 86400),
    skip_reason TEXT CHECK(skip_reason IN ('VIDEO_UNAVAILABLE')),
    skipped_at TEXT,
    points INTEGER NOT NULL DEFAULT 0 CHECK(points >= 0),
    streak INTEGER NOT NULL DEFAULT 0 CHECK(streak >= 0),
    ai_model_version TEXT,
    ai_spatial REAL CHECK(ai_spatial BETWEEN 0 AND 1),
    ai_temporal REAL CHECK(ai_temporal BETWEEN 0 AND 1),
    snapshot_label TEXT NOT NULL CHECK(snapshot_label IN ('REAL','FAKE')),
    snapshot_explanation TEXT NOT NULL DEFAULT '',
    snapshot_technique TEXT,
    snapshot_cues_json TEXT NOT NULL DEFAULT '[]',
    snapshot_tips_json TEXT NOT NULL DEFAULT '[]',
    PRIMARY KEY(game_id,ordinal),
    UNIQUE(game_id,clip_id),
    CHECK((verdict IS NULL AND confidence IS NULL AND answered_at IS NULL) OR
          (verdict IS NOT NULL AND confidence IS NOT NULL AND answered_at IS NOT NULL)),
    CHECK((skip_reason IS NULL AND skipped_at IS NULL) OR
          (skip_reason IS NOT NULL AND skipped_at IS NOT NULL)),
    CHECK(NOT (verdict IS NOT NULL AND skip_reason IS NOT NULL)),
    CHECK((ai_spatial IS NULL AND ai_temporal IS NULL AND ai_model_version IS NULL) OR
          (ai_spatial IS NOT NULL AND ai_temporal IS NOT NULL AND ai_model_version IS NOT NULL))
);
