# День 17 — первый инструмент MCP

Задание дня: сделать свой MCP-сервер вокруг какого-нибудь API и вызвать инструмент из агента.

Чтобы было видно, где проходит граница MCP, день собирается по шагам, каждый шаг — отдельный модуль:

| Модуль | Что это | Знает про MCP | Статус |
|---|---|---|---|
| `user-service` | Обычный Spring Boot сервис пользователей: REST API поверх SQLite | нет | готов |
| `user-service-mcp` | MCP-сервер на Streamable HTTP: описывает те же операции как инструменты | да | готов |
| `agent` | Консольный агент: связывает языковую модель и MCP-сервер | да | готов |

Смысл разделения тот же, что в дне 16: бизнес-сервис не переписывается под модели.
MCP — тонкий слой поверх уже существующего API.

## Запуск

Нужен JDK 21. Первым двум шагам ключи не нужны; агенту нужен ключ DeepSeek
в `day17/.env` (образец — `.env.example`).

```bash
cd day17
./gradlew :user-service:bootRun    # порт 8097
./gradlew :user-service-mcp:run    # порт 8098, в другом терминале
./gradlew :agent:run               # агент, в третьем терминале
```

---

# Шаг 1: сервис пользователей

Две операции: создать пользователя и найти пользователя.

## REST API

| Метод | Назначение |
|---|---|
| `POST /api/users` | Создать пользователя: `{"name": "...", "email": "..."}` |
| `GET /api/users?query=иван&limit=20` | Найти пользователя по части имени или email |

Ответ — пользователь или список пользователей:

```json
{"id": 1, "name": "Иван Петров", "email": "ivan@example.com", "createdAt": "2026-09-22T15:21:40Z"}
```

Ошибки приходят в виде `{"message": "..."}`:

| Код | Когда |
|---|---|
| 400 | Пустое имя, кривой email, нечитаемое тело, пустой `query`, `limit` вне 1..100 |
| 409 | Пользователь с таким email уже есть |

## Проверка руками

```bash
curl -X POST http://localhost:8097/api/users \
     -H 'Content-Type: application/json' \
     -d '{"name":"Иван Петров","email":"Ivan@Example.COM"}'
# 201 {"id":1,"name":"Иван Петров","email":"ivan@example.com","createdAt":"..."}

curl --get --data-urlencode 'query=ИВАН' http://localhost:8097/api/users
# 200 [{"id":1,...}]  — регистр и кириллица работают

curl -X POST http://localhost:8097/api/users \
     -H 'Content-Type: application/json' \
     -d '{"name":"Другой Иван","email":"IVAN@example.com"}'
# 409 {"message":"Пользователь с email ivan@example.com уже существует"}
```

## Swagger

- интерактивная страница — http://localhost:8097/swagger
- сам документ OpenAPI — http://localhost:8097/api-docs

Это то же самое описание, которое на шаге 2 получает модель в виде схемы инструмента:
в Swagger его читает человек и жмёт кнопку, модель читает схему и вызывает инструмент сама.

## Хранилище

SQLite, файл `day17/data/users.db` (путь меняется переменной `DB_PATH`). Файл в `.gitignore`:
данные локальные, их незачем коммитить. Схема применяется при старте — `schema.sql`, все
операции `IF NOT EXISTS`, поэтому перезапуск ничего не ломает и данные переживают рестарт.

Три решения, которые иначе выглядят странно:

- **Колонка `name_ci`.** Собственный `lower()` у SQLite умеет только ASCII — «Иван» и «иван»
  он бы не сопоставил. Поэтому имя в нижнем регистре пишет приложение, а поиск идёт по этой колонке.
  Email отдельной колонки не требует: он и так нормализуется к нижнему регистру при создании.
- **`SqliteExceptionTranslator`.** Spring не знает кодов ошибок SQLite и превращает нарушение
  `UNIQUE` в `UncategorizedSQLException`, то есть в 500 вместо 409. Транслятор разбирает коды
  и отдаёт `DuplicateKeyException`. Уникальность держится на ограничении в схеме, а не на проверке
  «сначала поищу, потом вставлю»: такая проверка проигрывает гонку двум одновременным запросам.
- **Пул из одного соединения.** У SQLite один писатель на файл; пул побольше даёт не параллелизм,
  а `SQLITE_BUSY`.

---

# Шаг 2: MCP-сервер сервиса пользователей

Два инструмента поверх того же REST API. Сервер сам ничего не хранит и не считает —
он переводит вызовы инструментов в HTTP-запросы к сервису пользователей.

| Инструмент | Аргументы | Что делает |
|---|---|---|
| `create_user` | `name`, `email` (оба обязательны) | Создаёт пользователя |
| `find_user` | `query` (обязателен), `limit` (1..100, по умолчанию 20) | Ищет по части имени или email |

## Транспорт: Streamable HTTP, без сессий

В дне 16 транспортом был stdio: клиент запускал сервер дочерним процессом, а в stdout нельзя было
писать ничего, кроме протокола. Здесь транспорт — HTTP, и это меняет две вещи:

- сервер живёт отдельно от клиента, его можно вынести в контейнер, на сервер, в облако;
- stdout свободен, логи пишутся как в обычном приложении.

Режим **stateless**: сессии нет, заголовок `Mcp-Session-Id` сервер не выдаёт и не требует,
каждый POST самодостаточен. Поэтому сервер масштабируется горизонтально — запросы можно
раскидывать по репликам без sticky-сессий. Плата за это — сервер не может сам присылать
уведомления и не поддерживает возобновление потока; для двух операций «создать» и «найти»
это не нужно.

Один эндпоинт: `POST /mcp`. `GET` и `DELETE` отвечают `405` — это штатное поведение
stateless-режима, а не поломка. Отдельно есть `GET /health` для облачных проб живости.

## Контракты JSON-RPC

MCP — это JSON-RPC 2.0 поверх транспорта. Ниже реальные запросы и ответы: их можно скопировать
в curl как есть. Общие заголовки для всех вызовов:

```
Content-Type: application/json
Accept: application/json, text/event-stream
MCP-Protocol-Version: 2025-06-18
```

### 1. `initialize` — рукопожатие

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-06-18",
    "capabilities": {},
    "clientInfo": {"name": "curl", "version": "1.0"}
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-06-18",
    "capabilities": {"tools": {"listChanged": false}},
    "serverInfo": {"name": "user-service-mcp", "version": "1.0.0"}
  }
}
```

### 2. `tools/list` — регистрация инструментов

```json
{"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
```

Ответ — описание обоих инструментов. Это и есть «регистрация»: модель узнаёт из него,
какие действия ей доступны, какие аргументы обязательны и что вернётся.

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "create_user",
        "description": "Создать пользователя: нужны имя и email. Email уникален и служит ключом пользователя, регистр не важен. Если пользователь с таким email уже есть, инструмент вернёт ошибку и дубль не создаст — поэтому перед созданием имеет смысл поискать через find_user.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "name": {"type": "string", "description": "Имя пользователя, например \"Иван Петров\"", "minLength": 1, "maxLength": 100},
            "email": {"type": "string", "format": "email", "description": "Email пользователя, например \"ivan@example.com\"", "maxLength": 254}
          },
          "required": ["name", "email"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "user": {
              "type": "object",
              "properties": {
                "id": {"type": "integer", "description": "Идентификатор пользователя"},
                "name": {"type": "string", "description": "Имя"},
                "email": {"type": "string", "description": "Email в нижнем регистре"},
                "createdAt": {"type": "string", "description": "Момент создания в UTC, ISO-8601"}
              },
              "required": ["id", "name", "email", "createdAt"]
            }
          },
          "required": ["user"]
        },
        "annotations": {
          "title": "Создать пользователя",
          "readOnlyHint": false,
          "destructiveHint": false,
          "idempotentHint": false,
          "openWorldHint": true
        }
      },
      {
        "name": "find_user",
        "description": "Найти пользователей по части имени или email: регистр не важен, ищется вхождение подстроки. Подходит, когда точный email неизвестен. Совпадений может быть несколько или ни одного.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "query": {"type": "string", "description": "Часть имени или email, например \"иван\" или \"@example.com\"", "minLength": 1},
            "limit": {"type": "integer", "description": "Сколько результатов вернуть, от 1 до 100", "minimum": 1, "maximum": 100, "default": 20}
          },
          "required": ["query"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "count": {"type": "integer", "description": "Сколько пользователей найдено"},
            "users": {"type": "array", "items": {"$ref": "см. user выше"}}
          },
          "required": ["count", "users"]
        },
        "annotations": {
          "title": "Найти пользователя",
          "readOnlyHint": true,
          "idempotentHint": true,
          "openWorldHint": true
        }
      }
    ]
  }
}
```

`annotations` — подсказки клиенту о характере инструмента: `find_user` только читает и идемпотентен,
поэтому его можно звать свободно; `create_user` меняет данные, и клиент вправе спросить подтверждение.
`outputSchema` описывает структуру результата, поэтому вызывающий код берёт из ответа готовый `id`,
а не разбирает строку.

### 3. `tools/call` — вызов `create_user`

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "create_user",
    "arguments": {"name": "Иван Петров", "email": "Ivan@Example.COM"}
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{"type": "text", "text": "Создан пользователь #1: Иван Петров <ivan@example.com>"}],
    "structuredContent": {
      "user": {"id": 1, "name": "Иван Петров", "email": "ivan@example.com", "createdAt": "2026-09-22T15:21:40Z"}
    }
  }
}
```

Результат приходит сразу в двух видах: `content` — текст для модели, `structuredContent` — структура
по схеме из `outputSchema` для кода.

### 4. `tools/call` — вызов `find_user`

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "find_user",
    "arguments": {"query": "ИВАН", "limit": 20}
  }
}
```

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "result": {
    "content": [{"type": "text", "text": "#1 Иван Петров <ivan@example.com>, создан 2026-09-22T15:21:40Z"}],
    "structuredContent": {
      "count": 1,
      "users": [{"id": 1, "name": "Иван Петров", "email": "ivan@example.com", "createdAt": "2026-09-22T15:21:40Z"}]
    }
  }
}
```

### 5. Ошибки

Отказ бизнес-сервиса — это **не** ошибка JSON-RPC: HTTP остаётся `200`, а в результате стоит
`isError: true`. Так модель видит причину и может исправиться сама, а соединение не рвётся.

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "content": [{"type": "text", "text": "Пользователь с email ivan@example.com уже существует"}],
    "isError": true
  }
}
```

Ошибкой протокола отвечают только проблемы уровня транспорта:

| Ситуация | HTTP | Тело |
|---|---|---|
| `GET`/`DELETE` на `/mcp` | 405 | `{"error": {"code": -32000, "message": "Method not allowed."}}` |
| Чужой `Host` (защита от DNS rebinding) | 403 | `{"error": {"code": -32000, "message": "Invalid Host: evil.example.com"}}` |
| Нет или неверен `Authorization` | 401 | `{"error": {"code": -32001, "message": "Unauthorized: ..."}}` |

## Проверка руками

```bash
curl -s http://localhost:8098/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-06-18' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'

curl -s http://localhost:8098/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-06-18' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"find_user","arguments":{"query":"иван"}}}'
```

В stateless-режиме `initialize` для этого не обязателен: каждый POST самодостаточен.
Полноценный клиент всё равно начнёт с него — так он узнаёт версию протокола и возможности сервера.

## Подключение к Claude Code и Claude Desktop

```bash
claude mcp add --transport http users http://localhost:8098/mcp
# с токеном:
claude mcp add --transport http users https://users-mcp.example.com/mcp \
  --header "Authorization: Bearer $MCP_AUTH_TOKEN"
```

Для Claude Desktop то же самое в `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "users": {
      "type": "http",
      "url": "https://users-mcp.example.com/mcp",
      "headers": {"Authorization": "Bearer ВАШ_ТОКЕН"}
    }
  }
}
```

Сервис пользователей при этом должен быть запущен — MCP-сервер данные не хранит.

## Настройка

| Переменная | По умолчанию | Зачем |
|---|---|---|
| `PORT` | `8098` | Порт MCP-сервера |
| `USERS_API_URL` | `http://localhost:8097` | Адрес сервиса пользователей |
| `MCP_ALLOWED_HOSTS` | `localhost,127.0.0.1,[::1]` | Допустимые значения заголовка `Host` |
| `MCP_AUTH_TOKEN` | не задан | Токен для `Authorization: Bearer`. Если не задан, проверки нет |

## Запуск в облаке

Сервер задуман переносимым: настройка целиком в переменных окружения, состояния в памяти нет,
собирается в один исполняемый jar.

### 1. Собрать и поднять локально в контейнерах

```bash
cd day17
export MCP_AUTH_TOKEN=$(openssl rand -hex 32)
export MCP_ALLOWED_HOSTS=localhost
docker compose up --build
```

Переменная `MCP_AUTH_TOKEN` обязательна и для `docker compose down` — она объявлена как
`${MCP_AUTH_TOKEN:?}`, и compose не станет разбирать файл без неё.

Наружу торчит только MCP-сервер на `8098`. Сервис пользователей портов не публикует —
он доступен лишь изнутри сети compose. Это не мелочь: REST API умеет создавать пользователей
без всякой авторизации, и выставлять его в интернет нельзя.

### 2. Выкатить

Любая платформа, которая умеет запускать контейнер и слушать HTTP: VPS с docker compose,
Yandex Cloud Serverless Containers, Cloud Run, Fly.io, Render, Railway, Amvera.
Отдельного образа не нужно — те же два `Dockerfile`.

Что задать при выкатке:

| Переменная | Значение в облаке |
|---|---|
| `PORT` | То, что даёт платформа. Многие передают порт сами через `PORT` — сервер его и читает |
| `USERS_API_URL` | Внутренний адрес сервиса пользователей, например `http://user-service:8097` |
| `MCP_ALLOWED_HOSTS` | **Домен развёртывания**, например `users-mcp.example.com` |
| `MCP_AUTH_TOKEN` | Случайная строка из секретов платформы, не из репозитория |

Проба живости: `GET /health` → `200 ok`. Токен для неё не нужен, чтобы платформа могла
проверять контейнер, не зная секрета.

### 3. Про что легко забыть

- **`MCP_ALLOWED_HOSTS` обязателен.** По умолчанию сервер принимает только `localhost` —
  это защита от DNS rebinding, встроенная в MCP SDK. На облачном домене без этой переменной
  каждый запрос получит `403 Invalid Host`, и выглядеть это будет как поломка, хотя это настройка.
- **`MCP_AUTH_TOKEN` обязателен.** `create_user` пишет в базу. Открытый наружу `/mcp` без токена
  означает, что писать может кто угодно. Bearer-токен — это минимум; спецификация MCP описывает
  полноценный OAuth 2.1, и если сервер выходит за рамки демо, идти надо туда.
- **TLS.** Токен в заголовке по открытому HTTP передаётся текстом. Нужен HTTPS —
  либо встроенный в платформу, либо реверс-прокси перед контейнером.
- **SQLite и реплики.** MCP-сервер stateless и масштабируется горизонтально, а сервис пользователей —
  нет: файл SQLite подразумевает одну запущенную копию и постоянный том (`DB_PATH=/data/users.db`).
  Эфемерная файловая система контейнера означает, что база исчезнет при перезапуске.
  Нужно несколько реплик — сначала меняется хранилище на PostgreSQL, и только потом растёт число копий.

---

# Шаг 3: агент

Консольный агент, в котором сходятся обе линии дня. У него два клиента и ничего больше:

| Клиент | Файл | С кем говорит |
|---|---|---|
| LLM | `DeepSeekClient.kt` | DeepSeek, OpenAI-совместимый `/chat/completions` |
| MCP | `McpToolbox.kt` | MCP-сервер по Streamable HTTP, тем же SDK, что и сервер |

Про пользователей агент не знает ничего: что умеет система, он выясняет в рантайме через
`tools/list`. Добавится третий инструмент — агент подхватит его без единой правки кода.

## Запуск

Нужен ключ DeepSeek в `day17/.env` (образец — `.env.example`, файл в `.gitignore`):

```
DEEPSEEK_API_KEY=sk-...
```

Дальше три терминала:

```bash
./gradlew :user-service:bootRun     # 1. база и REST API
./gradlew :user-service-mcp:run     # 2. MCP-сервер
./gradlew :agent:run                # 3. агент
```

## Как выглядит работа

```
Подключаюсь к MCP-серверу: http://localhost:8098/mcp
Подключился: user-service-mcp 1.0.0
Инструменты, которые он объявил:
  • create_user(name*, email*)
  • find_user(query*, limit)

Модель: deepseek-chat.
Команды: «/raw» — показывать обмен с LLM целиком, «выход» — закончить.

Вы: Заведи пользователя Анну Смирнову с почтой anna@example.com

   → LLM вызывает find_user {"query": "anna@example.com"}
   ← MCP: По запросу "anna@example.com" никого не найдено
     структура: {"count":0,"users":[]}
   → LLM вызывает find_user {"query": "Анна Смирнова"}
   ← MCP: По запросу "Анна Смирнова" никого не найдено
     структура: {"count":0,"users":[]}
   → LLM вызывает create_user {"name": "Анна Смирнова", "email": "anna@example.com"}
   ← MCP: Создан пользователь #1: Анна Смирнова <anna@example.com>
     структура: {"user":{"id":1,"name":"Анна Смирнова","email":"anna@example.com","createdAt":"2026-09-22T15:51:21Z"}}

Агент: Готово: создан пользователь #1 — Анна Смирнова (anna@example.com).
```

Три вызова инструментов на одну реплику — это и есть agent loop. Модель сама решила
сначала убедиться, что такого пользователя нет, и только потом создавать.

## Agent loop

Один проход цикла — один поход к модели (`Agent.kt`):

1. отдаём модели историю разговора и список инструментов;
2. модель ответила текстом → это ответ пользователю, цикл закончен;
3. модель попросила инструменты → вызываем их через MCP, кладём результаты в историю
   ролью `tool` и идём на следующий проход.

Цикл, а не один запрос, нужен именно из-за пункта 3: узнав результат инструмента, модель
может решить вызвать следующий. Ограничение — 6 проходов на реплику: без него модель,
зациклившаяся на инструменте, будет ходить по кругу за деньги пользователя.

Результат инструмента возвращается сообщением с тем же `tool_call_id`, что был в запросе
модели. Без него модель не поймёт, на какой из своих вызовов смотрит.

## Мост между MCP и LLM

Схема инструмента MCP и описание функции в OpenAI-совместимом API — это одно и то же
JSON Schema, поэтому перевод занимает несколько строк (`McpToolbox.kt`):

```kotlin
private fun Tool.toDefinition(): ToolDefinition = ToolDefinition(
    function = FunctionSpec(
        name = name,
        description = description.orEmpty(),
        parameters = buildJsonObject {
            put("type", "object")
            inputSchema.properties?.let { put("properties", it) }
            inputSchema.required?.let { required -> putJsonArray("required") { required.forEach { add(it) } } }
        },
    ),
)
```

Ровно поэтому MCP и стоит городить вокруг существующего API: описание, которое сервер
однажды объявил, годится любой модели с function calling без переписывания.

## Ошибки инструментов

Отказ инструмента не роняет диалог. Он приходит как `isError: true`, агент кладёт текст
ошибки в историю обычным сообщением, и модель объясняет её пользователю:

```
Вы: Создай пользователя Тест Тестов с почтой не-почта

   → LLM вызывает create_user {"name": "Тест Тестов", "email": "не-почта"}
   ✗ MCP: Значение "не-почта" не похоже на email

Агент: Не получилось: «не-почта» не похоже на email — инструмент требует нормальный
адрес вида имя@домен. Подскажите корректный email, и я создам пользователя.
```

То же и с недоступным сервером: агент отдаёт модели причину вместо того, чтобы упасть.

## Что уходит в модель: команда `/raw`

Команда `/raw` в диалоге включает показ полного обмена с LLM — того самого JSON, который
ушёл в сеть и вернулся оттуда:

```
  ┌─ в LLM ──── POST https://api.deepseek.com/chat/completions
  │  Content-Type: application/json
  │  Authorization: Bearer sk-…ce75
  │
  │ {
  │     "model": "deepseek-chat",
  │     "messages": [
  │         {"role": "system",    "content": "Ты помощник по базе пользователей..."},
  │         {"role": "user",      "content": "Найди пользователя по имени Анна"},
  │         {"role": "assistant", "content": "", "tool_calls": [
  │             {"id": "call_00_41fe...", "type": "function",
  │              "function": {"name": "find_user", "arguments": "{\"query\": \"Анна\"}"}}
  │         ]},
  │         {"role": "tool", "tool_call_id": "call_00_41fe...",
  │          "content": "По запросу \"Анна\" никого не найдено"}
  │     ],
  │     "tools": [ ... схемы create_user и find_user ... ],
  │     "temperature": 0.2
  │ }
  ├─ из LLM ─── HTTP 200
  │ {
  │     "choices": [{"message": {"role": "assistant", "content": "",
  │                              "tool_calls": [{"id": "...", "function": {"name": "find_user", ...}}]},
  │                  "finish_reason": "tool_calls"}],
  │     "usage": {"prompt_tokens": 737, "completion_tokens": 39, "total_tokens": 776}
  │ }
  └─
```

Что из этого видно:

- **Инструменты уезжают в каждом запросе.** Модель не «помнит» их между вызовами: массив
  `tools` отправляется заново, и описания из MCP — это то, что реально попадает в промпт.
  Отсюда же цена: два инструмента со схемами — это примерно 500 токенов на каждый запрос.
- **История растёт от прохода к проходу.** На втором проходе в `messages` уже лежит ответ
  модели с `tool_calls` и результат инструмента ролью `tool`. Ничего «скрытого» между
  вызовами нет — контекст это буквально массив сообщений, который агент собирает сам.
- **`tool_call_id` связывает результат с вызовом.** Он приходит от модели и возвращается
  ей же без изменений.
- **`finish_reason`** говорит, почему модель остановилась: `tool_calls` — просит инструменты,
  цикл продолжается; `stop` — это ответ пользователю, цикл закончен.
- **`arguments` — строка**, а не объект. Внутри JSON, но приезжает он текстом; это особенность
  протокола, из-за которой модель иногда присылает синтаксически битые аргументы.

Байты показываются именно те, что ушли в сеть: тело запроса собирается строкой в
`DeepSeekClient.kt` и только потом отправляется, а ответ читается строкой и разбирается
после. Отступы добавлены для читаемости, содержимое не меняется. От API-ключа в заголовке
остаются префикс и четыре последних символа — вывод удобно копировать, и ключ не должен
уехать вместе с ним.

## Настройка агента

| Переменная | По умолчанию | Зачем |
|---|---|---|
| `DEEPSEEK_API_KEY` | — | Обязателен. Из `.env` или из окружения |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com` | Адрес провайдера |
| `DEEPSEEK_MODEL` | `deepseek-chat` | Модель |
| `MCP_URL` | `http://localhost:8098/mcp` | Куда ходить за инструментами |
| `MCP_AUTH_TOKEN` | не задан | Токен MCP-сервера, если тот его требует |

## Стек

- Kotlin 2.4.20, JDK 21, Gradle (multi-project, дальше добавится модуль агента)
- Spring Boot 4.1.1 + Spring JDBC (`JdbcClient`) — сервис пользователей
- SQLite через `org.xerial:sqlite-jdbc` 3.49.1.0
- springdoc-openapi 3.1.1 — Swagger UI
- `io.modelcontextprotocol:kotlin-sdk` 0.15.0 (server + client) и Ktor 3.5.1 — MCP-сервер и агент
- DeepSeek (`deepseek-chat`) — языковая модель агента, function calling
