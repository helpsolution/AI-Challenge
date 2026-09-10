-- Вся «миграция» дня: три таблицы. IF NOT EXISTS делает скрипт идемпотентным, поэтому его
-- можно гонять на каждом старте и не тащить Flyway ради трёх таблиц.
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

-- Конспект истории — то, что этот день добавляет к дню 8.
--
-- Задание требует хранить summary отдельно, и на это есть причина сильнее формальной:
-- конспект не сообщение. У него нет автора, он не участвует в диалоге и может быть
-- переписан заново, тогда как сообщение неизменно. Колонка в message означала бы,
-- что пересказ принадлежит какой-то одной реплике, — а он принадлежит их множеству.
--
-- Строки не заменяют друг друга, а накапливаются: версия N+1 составлена из версии N
-- и следующей пачки сообщений. Актуальная — с наибольшим version. Старые остаются,
-- потому что по ним видно, как затираются подробности начала разговора.
--
-- Цены здесь нет намеренно: сжатие — это обращение к модели, и его расход лежит в turn
-- с kind = 'SUMMARY'. Две записи о деньгах в двух таблицах разошлись бы.
CREATE TABLE IF NOT EXISTS summary (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    version                INTEGER NOT NULL,
    created_at             TEXT    NOT NULL,

    -- Граница: до какого сообщения конспект досчитан. Всё, что после, модель видит дословно.
    covers_from_message_id INTEGER NOT NULL,
    covers_upto_message_id INTEGER NOT NULL,
    -- Сколько сообщений добавила эта версия и сколько всего осталось за границей.
    messages_folded        INTEGER NOT NULL,
    messages_covered       INTEGER NOT NULL,

    content                TEXT    NOT NULL,
    -- После какого хода составлен. Не уникален: за один ход история может свернуться дважды.
    turn_number            INTEGER NOT NULL
);

-- Расход по обращениям к модели.
--
-- Почему отдельная таблица, а не колонки в message: расход относится к обмену с моделью,
-- а не к сообщению. У одного хода два сообщения и один счёт; у неудачного хода счёт есть,
-- а сообщений нет вовсе; у сворачивания истории счёт есть, а сообщений нет и не будет.
--
-- Токены nullable по той же причине: у обращения, которое упало, их нет. Ноль на этом
-- месте был бы неправдой — он означал бы «запрос ничего не стоил», а не «неизвестно».
CREATE TABLE IF NOT EXISTS turn (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    -- Номер хода диалога. У сворачивания — номер хода, после которого оно случилось,
    -- поэтому уникальности здесь нет: хронологию задаёт id.
    number            INTEGER NOT NULL,
    -- ANSWER — ход диалога, SUMMARY — сворачивание истории. Разделение обязательно:
    -- без него экономия считалась бы без учёта своей цены.
    kind              TEXT    NOT NULL CHECK (kind IN ('ANSWER', 'SUMMARY')),
    created_at        TEXT    NOT NULL,

    -- С кем и в каком режиме разговаривали. Режим, провайдер и модель меняются в конфиге,
    -- а старые обращения должны остаться сравнимыми: без этих полей график смешал бы
    -- прогон со сжатием и прогон без него.
    mode              TEXT    NOT NULL CHECK (mode IN ('RAW', 'SUMMARY')),
    provider          TEXT    NOT NULL,
    model             TEXT    NOT NULL,
    context_limit     INTEGER NOT NULL,
    max_tokens        INTEGER NOT NULL,

    -- Что отправили и из чего это собрано. Известно до ответа, поэтому есть даже
    -- у неудачного обращения. Состав — половина смысла дня: он объясняет, откуда взялась
    -- экономия, а history_chars против chars_sent показывает её размер в символах.
    prompt_messages   INTEGER NOT NULL,
    history_total     INTEGER NOT NULL,
    history_chars     INTEGER NOT NULL,
    prompt_blocks     INTEGER NOT NULL,
    chars_sent        INTEGER NOT NULL,
    persona_chars     INTEGER NOT NULL,
    summary_chars     INTEGER NOT NULL,
    tail_chars        INTEGER NOT NULL,
    summary_version   INTEGER,

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
    -- 1, если промпт урезал сам агрегатор: история выросла, а токены запроса нет.
    -- Это не сжатие этого дня, а чужое вмешательство, и путать их нельзя.
    truncated         INTEGER NOT NULL DEFAULT 0,

    -- Неудачное обращение. Переписка при этом не меняется, но событие остаётся в базе.
    error             TEXT,
    error_status      INTEGER
);
