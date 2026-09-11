-- Три таблицы. IF NOT EXISTS делает скрипт идемпотентным, поэтому его можно гонять
-- на каждом старте и не тащить Flyway.
--
-- Порядок везде задаёт id: AUTOINCREMENT монотонен, значит сортировка по нему и есть
-- хронология. На created_at не завязываемся: оба сообщения одного хода получают близкие
-- метки времени и могут совпасть.

-- Диалог со своей стратегией. Главная новая таблица этого дня.
--
-- strategy и window_size — снимок настроек на момент создания, а не ссылка на конфиг.
-- Правка application.yml не должна задним числом переписывать условия прогонов, которые
-- уже лежат в базе: тогда сравнение стратегий сравнивало бы неизвестно что.
CREATE TABLE IF NOT EXISTS session (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    title         TEXT    NOT NULL,
    strategy      TEXT    NOT NULL CHECK (strategy IN ('SLIDING_WINDOW', 'FACTS', 'BRANCHING')),
    window_size   INTEGER NOT NULL,
    created_at    TEXT    NOT NULL,

    -- Ветвление. Ветка — обычная сессия, у которой есть родитель и точка отделения.
    -- Сообщения родителя до этой точки скопированы в неё при форке, поэтому чтение
    -- истории ветки ничем не отличается от чтения истории корневого диалога.
    parent_id     INTEGER REFERENCES session(id),
    forked_after  INTEGER
);

-- Переписка. Системной инструкции здесь нет: в базе только сама переписка,
-- персона живёт в конфиге.
CREATE TABLE IF NOT EXISTS message (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id INTEGER NOT NULL REFERENCES session(id),
    role       TEXT    NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content    TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS message_session ON message (session_id, id);

-- Расход по ходам.
--
-- Отдельная таблица, а не колонки в message: расход относится к обмену с моделью,
-- а не к сообщению. У одного хода два сообщения и один счёт; у неудачного хода счёт есть,
-- а сообщений нет вовсе.
--
-- Токены nullable по той же причине: у хода, который упал, их нет. Ноль на этом месте
-- был бы неправдой — он означал бы «запрос ничего не стоил», а не «неизвестно».
CREATE TABLE IF NOT EXISTS turn (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        INTEGER NOT NULL REFERENCES session(id),
    number            INTEGER NOT NULL,
    created_at        TEXT    NOT NULL,

    -- Условия хода. Стратегия дублируется из сессии намеренно: отчёт строится по ходам,
    -- и join ради поля, которое всё равно не меняется, ничего бы не дал.
    strategy          TEXT    NOT NULL,
    model             TEXT    NOT NULL,

    -- Состав промпта — то, ради чего этот день. Разница между history_messages
    -- и included_messages и есть то, что стратегия выбросила.
    history_messages  INTEGER NOT NULL,
    included_messages INTEGER NOT NULL,
    prompt_blocks     INTEGER NOT NULL,
    chars_sent        INTEGER NOT NULL,
    note              TEXT,

    -- Что насчитал провайдер. Не наша оценка, а то, за что выставлен счёт.
    prompt_tokens     INTEGER,
    cached_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    cost_usd          REAL,
    -- PRICE_LIST — наше умножение на прайс из конфига; DeepSeek денег не сообщает.
    cost_source       TEXT,

    latency_ms        INTEGER NOT NULL,
    finish_reason     TEXT,

    -- Неудачный ход. Переписка при этом не меняется, но событие остаётся в базе.
    error             TEXT,
    error_status      INTEGER
);

CREATE INDEX IF NOT EXISTS turn_session ON turn (session_id, number);

-- Прогоны сценария. Отчёт лежит целиком в JSON, а не разложен по колонкам.
--
-- Причина: по нему не делается ни одного запроса — он только читается целиком и рисуется.
-- Разложить его по таблицам значило бы завести схему под структуру, которая меняется
-- вместе с каждой новой стратегией, ради выборок, которых нет.
--
-- Колонками вынесено ровно то, по чему отбирают: сценарий и стратегия.
CREATE TABLE IF NOT EXISTS scenario_run (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    scenario_id TEXT    NOT NULL,
    strategy    TEXT    NOT NULL,
    session_id  INTEGER NOT NULL REFERENCES session(id),
    created_at  TEXT    NOT NULL,
    report      TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS scenario_run_session ON scenario_run (session_id);

-- Досье стратегии фактов: короткие пары «ключ — значение», выжимка из реплик пользователя.
--
-- Ключ уникален в пределах сессии: досье — это словарь, а не журнал. Повторное упоминание
-- дедлайна должно ЗАМЕНИТЬ прежнее значение, а не лечь рядом с ним; иначе досье растёт
-- вместе с разговором и стратегия вырождается в «отправляем всё».
--
-- turn_number — ход последнего изменения. По нему видно, что досье живое: значения
-- меняются, а не только копятся.
CREATE TABLE IF NOT EXISTS fact (
    session_id  INTEGER NOT NULL REFERENCES session(id),
    key         TEXT    NOT NULL,
    value       TEXT    NOT NULL,
    turn_number INTEGER NOT NULL,
    updated_at  TEXT    NOT NULL,
    PRIMARY KEY (session_id, key)
);

-- Расход на обслуживание памяти: второе обращение к модели, которым стратегия обновляет
-- своё состояние.
--
-- Отдельная таблица, а не колонки в turn, по двум причинам. Первая содержательная:
-- «сколько стоил ответ» и «сколько стоило помнить» — разные вопросы, и весь смысл
-- сравнения стратегий в их разнице. Вторая практическая: turn уже лежит в базах, которые
-- пережили прошлый запуск, а CREATE TABLE IF NOT EXISTS новых колонок не добавляет —
-- пришлось бы тащить миграции ради двух чисел.
--
-- У скользящего окна таких строк нет вовсе: оно обходится одним обращением на ход.
CREATE TABLE IF NOT EXISTS upkeep (
    session_id        INTEGER NOT NULL REFERENCES session(id),
    turn_number       INTEGER NOT NULL,
    note              TEXT    NOT NULL,
    latency_ms        INTEGER NOT NULL,
    prompt_tokens     INTEGER,
    cached_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    cost_usd          REAL,
    cost_source       TEXT,
    error             TEXT,
    PRIMARY KEY (session_id, turn_number)
);
