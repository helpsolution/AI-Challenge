-- Сохранённые отчёты. Отчёт — конечная точка конвейера search → summarize → save:
-- текст сводки и статьи, на которые она ссылается.
CREATE TABLE IF NOT EXISTS reports (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    -- SQLite не знает типа «дата»: храним ISO-8601 в UTC с точностью до секунды, такой текст корректно сравнивается
    created_at TEXT    NOT NULL,
    -- тема поиска; NULL — сводка по свежей ленте
    query      TEXT,
    summary    TEXT    NOT NULL,
    -- источники JSON-массивом [{id, title, link}]: читаются и пишутся только вместе с отчётом,
    -- отдельная таблица не окупается
    sources    TEXT    NOT NULL
);
