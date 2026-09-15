CREATE TABLE IF NOT EXISTS session (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT    NOT NULL,
    window_size INTEGER NOT NULL,
    created_at  TEXT    NOT NULL
);

CREATE TABLE IF NOT EXISTS message (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES session(id),
    role       TEXT    NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content    TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS message_session ON message (session_id, id);

CREATE TABLE IF NOT EXISTS post_task (
    session_id       INTEGER PRIMARY KEY REFERENCES session(id),
    state            TEXT NOT NULL CHECK (state IN ('IDEA', 'THESIS', 'PLAN', 'DRAFT')),
    idea             TEXT,
    thesis           TEXT,
    plan             TEXT NOT NULL,
    draft            TEXT,
    notes            TEXT NOT NULL,
    style_suggestion TEXT,
    updated_at       TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS memory_item (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    kind              TEXT    NOT NULL CHECK (kind IN ('PROFILE', 'PREFERENCE', 'DECISION', 'KNOWLEDGE')),
    key               TEXT    NOT NULL,
    value             TEXT    NOT NULL,
    confidence        REAL    NOT NULL,
    source_session_id INTEGER REFERENCES session(id),
    created_at        TEXT    NOT NULL,
    updated_at        TEXT    NOT NULL,
    UNIQUE(kind, key)
);

CREATE INDEX IF NOT EXISTS memory_item_kind ON memory_item (kind, key);
