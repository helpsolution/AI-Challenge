-- Вся «миграция» дня: две таблицы. IF NOT EXISTS делает скрипт идемпотентным, поэтому его
-- можно гонять на каждом старте и не тащить Flyway ради двух таблиц.
--
-- Порядок задаёт id: AUTOINCREMENT монотонен, значит сортировка по нему и есть хронология.
--
-- Системной инструкции здесь нет: в базе только сама переписка, персона живёт в конфиге.
CREATE TABLE IF NOT EXISTS message (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    role       TEXT    NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content    TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);

-- Расход по ходам — то, что этот день добавляет к дню 7.
--
-- Почему отдельная таблица, а не колонки в message: расход относится к обмену с моделью,
-- а не к сообщению. У одного хода два сообщения и один счёт; у неудачного хода счёт есть,
-- а сообщений нет вовсе. Колонки в message пришлось бы половину времени держать пустыми.
--
-- Токены здесь nullable по той же причине: у хода, который упал, их нет. Ноль на этом
-- месте был бы неправдой — он означал бы «запрос ничего не стоил», а не «неизвестно».
CREATE TABLE IF NOT EXISTS turn (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    number            INTEGER NOT NULL,
    created_at        TEXT    NOT NULL,

    -- С кем разговаривали. Провайдер и модель меняются в конфиге, а старые ходы должны
    -- остаться сравнимыми: без этих полей график смешал бы 4K-модель с 1M-моделью.
    provider          TEXT    NOT NULL,
    model             TEXT    NOT NULL,
    context_limit     INTEGER NOT NULL,
    max_tokens        INTEGER NOT NULL,

    -- Что отправили. Известно до ответа, поэтому есть даже у неудачного хода.
    history_messages  INTEGER NOT NULL,
    prompt_blocks     INTEGER NOT NULL,
    chars_sent        INTEGER NOT NULL,

    -- Что насчитал провайдер. Не наша оценка, а то, за что выставлен счёт.
    prompt_tokens     INTEGER,
    cached_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    cost_usd          REAL,
    -- PROVIDER — сумма списана и сообщена; PRICE_LIST — наше умножение на прайс из конфига.
    cost_source       TEXT,

    latency_ms        INTEGER NOT NULL,
    finish_reason     TEXT,
    -- 1, если промпт дошёл до модели урезанным: история выросла, а токены запроса нет.
    compressed        INTEGER NOT NULL DEFAULT 0,

    -- Неудачный ход. Переписка при этом не меняется, но событие остаётся в базе:
    -- «упёрлись в лимит» — главный экспонат дня.
    error             TEXT,
    error_status      INTEGER
);
