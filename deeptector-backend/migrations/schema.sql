CREATE TABLE IF NOT EXISTS schema_version (
    version INTEGER PRIMARY KEY CHECK(version >= 1)
);

CREATE TABLE IF NOT EXISTS sessions (
    id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS analyses (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    filename TEXT NOT NULL,
    source_path TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    stage TEXT NOT NULL,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    result_json TEXT,
    error_json TEXT
);

CREATE INDEX IF NOT EXISTS idx_analyses_queue ON analyses(status, created_at);
CREATE INDEX IF NOT EXISTS idx_analyses_owner ON analyses(session_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_analyses_expiry ON analyses(expires_at);

CREATE TABLE IF NOT EXISTS artifacts (
    id TEXT PRIMARY KEY,
    analysis_id TEXT NOT NULL REFERENCES analyses(id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    path TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    UNIQUE(analysis_id, kind)
);

CREATE INDEX IF NOT EXISTS idx_artifacts_analysis ON artifacts(analysis_id);
