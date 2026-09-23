-- Подписка — это задание для планировщика: что забирать и как часто.
-- Расписание живёт в базе, а не в памяти, поэтому переживает перезапуск сервиса.
CREATE TABLE IF NOT EXISTS subscriptions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    -- feed — лента из каталога, topic — тема через поиск Google News
    kind          TEXT    NOT NULL CHECK (kind IN ('feed', 'topic')),
    -- для ленты — ключ каталога (tass), для темы — запрос в нижнем регистре
    target        TEXT    NOT NULL,
    -- то, что видит человек: «ТАСС» или тема в том виде, в каком её ввели
    title         TEXT    NOT NULL,
    every_minutes INTEGER NOT NULL,
    -- SQLite не знает типа «дата»: храним ISO-8601 в UTC с точностью до секунды, такой текст корректно сравнивается
    created_at    TEXT    NOT NULL,
    next_run_at   TEXT    NOT NULL,
    last_run_at   TEXT,
    last_added    INTEGER,
    last_error    TEXT,
    UNIQUE (kind, target)
);

CREATE TABLE IF NOT EXISTS articles (
    -- AUTOINCREMENT: id только растёт и не переиспользуется после удаления — на этом держится курсор сводок
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    subscription_id INTEGER NOT NULL REFERENCES subscriptions (id),
    -- издание: ТАСС, РБК или то, которое указал Google News
    source          TEXT    NOT NULL,
    title           TEXT    NOT NULL,
    -- заголовок в нижнем регистре: SQLite умеет lower() только для ASCII, кириллицу сворачивает приложение
    title_ci        TEXT    NOT NULL,
    link            TEXT    NOT NULL,
    -- время публикации из ленты; если лента его не дала — момент сбора
    published_at    TEXT    NOT NULL,
    fetched_at      TEXT    NOT NULL,
    -- лента отдаёт одни и те же новости при каждом сборе, дубли отсекает ограничение, а не проверка в коде
    UNIQUE (subscription_id, link)
);

CREATE INDEX IF NOT EXISTS idx_articles_published_at ON articles (published_at);
CREATE INDEX IF NOT EXISTS idx_subscriptions_next_run_at ON subscriptions (next_run_at);
