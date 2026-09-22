CREATE TABLE IF NOT EXISTS users (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL,
    -- имя в нижнем регистре: SQLite умеет lower() только для ASCII, кириллицу сворачивает приложение
    name_ci    TEXT NOT NULL,
    email      TEXT NOT NULL UNIQUE,
    -- SQLite не знает типа «дата»: храним ISO-8601 в UTC, такой текст корректно сортируется
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_users_name_ci ON users (name_ci);
