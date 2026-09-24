-- Состояние сборщика — одна строка. Включён ли он и чем кончился последний сбор, хранится в базе,
-- а не в памяти: выключенный через бота сбор остаётся выключенным и после перезапуска сервиса.
CREATE TABLE IF NOT EXISTS collector_state (
    id          INTEGER PRIMARY KEY CHECK (id = 1),
    enabled     INTEGER NOT NULL,
    -- SQLite не знает типа «дата»: храним ISO-8601 в UTC с точностью до секунды, такой текст корректно сравнивается
    last_run_at TEXT,
    last_found  INTEGER,
    last_added  INTEGER,
    last_error  TEXT
);

-- При первом запуске сбор включён.
INSERT OR IGNORE INTO collector_state (id, enabled) VALUES (1, 1);

CREATE TABLE IF NOT EXISTS articles (
    -- AUTOINCREMENT: id только растёт и не переиспользуется после удаления — на этом держится курсор сводок
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    -- лента отдаёт одни и те же статьи при каждом сборе, дубли отсекает ограничение, а не проверка в коде
    link         TEXT    NOT NULL UNIQUE,
    title        TEXT    NOT NULL,
    author       TEXT,
    -- теги статьи через перевод строки: считать их нужно только в сводке, отдельная таблица не окупается
    categories   TEXT    NOT NULL,
    -- начало статьи без HTML: по одному заголовку модель не всегда поймёт, о чём статья
    excerpt      TEXT,
    published_at TEXT    NOT NULL,
    fetched_at   TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_articles_published_at ON articles (published_at);
