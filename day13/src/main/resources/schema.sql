CREATE TABLE IF NOT EXISTS profile (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    name        TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL,
    created_at  TEXT NOT NULL,
    updated_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS session (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    title       TEXT    NOT NULL,
    window_size INTEGER NOT NULL,
    profile_id  INTEGER REFERENCES profile(id) ON DELETE SET NULL,
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
    current_step     TEXT NOT NULL,
    expected_action  TEXT NOT NULL,
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

CREATE TABLE IF NOT EXISTS agent_trace (
    id          TEXT PRIMARY KEY,
    session_id  INTEGER NOT NULL REFERENCES session(id) ON DELETE CASCADE,
    started_at  TEXT NOT NULL,
    status      TEXT NOT NULL,
    summary     TEXT NOT NULL,
    payload     TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS agent_trace_session ON agent_trace (session_id);

INSERT INTO profile (name, description, created_at, updated_at)
VALUES (
    'Химик',
    'Рассматривай тему глазами химика. Объясняй явления через состав, свойства веществ, реакции и механизмы. Используй профессиональную терминологию там, где она помогает раскрыть мысль.',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
)
ON CONFLICT(name) DO NOTHING;

INSERT INTO profile (name, description, created_at, updated_at)
VALUES (
    'Психолог',
    'Рассматривай тему глазами психолога. Обращай внимание на мотивацию, эмоции, установки, поведение и отношения между людьми. Используй психологические понятия и характерные для этой дисциплины способы объяснения.',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
)
ON CONFLICT(name) DO NOTHING;

INSERT INTO profile (name, description, created_at, updated_at)
VALUES (
    'Экономист',
    'Рассматривай тему глазами экономиста. Анализируй стимулы, ресурсы, выгоды, издержки, риски и последствия решений. Используй экономические и финансовые понятия.',
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now'),
    strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
)
ON CONFLICT(name) DO NOTHING;
