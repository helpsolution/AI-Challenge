-- Вся «миграция» дня: одна таблица. IF NOT EXISTS делает скрипт идемпотентным,
-- поэтому его можно гонять на каждом старте и не тащить Flyway ради одной таблицы.
--
-- Порядок диалога задаёт id: AUTOINCREMENT монотонен, значит сортировка по нему и есть
-- хронология. Отдельная колонка с позицией не нужна.
--
-- Системной инструкции здесь нет: в базе только сама переписка, персона живёт в конфиге.
CREATE TABLE IF NOT EXISTS message (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    role       TEXT    NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content    TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);
