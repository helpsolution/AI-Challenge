# День 16 — подключение MCP

Три отдельные части, чтобы было видно, где в этой схеме проходит граница MCP:

| Модуль | Что это | Знает про MCP |
|---|---|---|
| `weather-service` | Обычный Spring Boot сервис погоды с REST API поверх Open-Meteo | нет |
| `mcp-server` | MCP-сервер на stdio: объявляет три инструмента и ходит за данными в сервис погоды | да |
| `mcp-client` | Минимальный MCP-клиент: соединяется и печатает список инструментов | да |

Смысл разделения: бизнес-логика не переписывается под модели. MCP-сервер — это тонкий слой,
который описывает уже существующее API так, чтобы модель сама поняла, какие действия ей доступны.

## Запуск

Нужен JDK 21. Ключи API не нужны: Open-Meteo работает без регистрации.

```bash
cd day16

# 1. Бизнес-сервис (порт 8096, переопределяется переменной PORT)
./gradlew :weather-service:bootRun

# 2. В другом терминале — собрать MCP-сервер и запустить клиент
./gradlew :mcp-server:jar
./gradlew :mcp-client:run
```

Клиент сам запускает MCP-сервер дочерним процессом, поэтому отдельно его стартовать не нужно.

Jar сервера клиент ищет сам: сначала аргумент командной строки, затем переменную `MCP_SERVER_JAR`,
затем каталог `mcp-server/build/libs` вверх по дереву от текущей директории. Поэтому запуск не зависит
от рабочей директории — работает и из Gradle, и из IDE, и из терминала:

```bash
./gradlew :mcp-client:run                                    # из day16
./gradlew :mcp-client:run --args="путь/до/server.jar"        # с другим сервером

./gradlew :mcp-client:installDist                            # собрать запускаемый скрипт
mcp-client/build/install/mcp-client/bin/mcp-client           # и запускать его откуда угодно

MCP_SERVER_JAR=/абсолютный/путь/server.jar mcp-client        # путь через переменную окружения
```

В IDE достаточно нажать «Run» на `main()` в `mcp-client/.../client/Main.kt` — рабочая директория роли не играет.

## Что печатает клиент

```
1. Запускаю MCP-сервер: java -jar .../mcp-server-0.0.1-SNAPSHOT.jar
2. Устанавливаю соединение (initialize)...
   Соединение установлено.
   Сервер: day16-weather 1.0.0
   Возможности сервера: ServerCapabilities(tools=Tools(listChanged=false), ...)

3. Запрашиваю список инструментов (tools/list)...
   Сервер объявил инструментов: 3

• search_city
    Найти город по названию и получить его координаты, страну и часовой пояс...
    аргументы:
      - query (string, обязательный) — Название города, например "Москва" или "Berlin"
      - limit (integer) — Сколько вариантов вернуть, от 1 до 10

• get_current_weather
    ...

• get_forecast
    ...
```

## Swagger

Сервис отдает OpenAPI-документацию и Swagger UI:

- интерактивная страница — http://localhost:8096/swagger
- сам документ OpenAPI — http://localhost:8096/api-docs

В Swagger UI есть «Try it out»: параметры заполнены примерами (`Москва`, `Берлин`), поэтому запрос
можно отправить в один клик и сразу увидеть ответ сервиса. Коды ошибок 400/404/502 описаны вместе
со схемой тела `ErrorResponse`.

Это ровно то же API, к которому обращается MCP-сервер, — удобно сравнить: в Swagger человек читает
описание глазами и жмет кнопку, а модель получает то же самое описание в виде схемы инструмента.

## REST API сервиса погоды

| Метод | Назначение |
|---|---|
| `GET /api/cities?query=Москва&limit=5` | Поиск города, координаты и часовой пояс |
| `GET /api/weather/current?city=Москва` | Текущая погода |
| `GET /api/weather/forecast?city=Москва&days=3` | Прогноз на 1–7 дней |

Ошибки приходят в виде `{"message": "..."}`: 404 — город не найден, 400 — некорректный параметр,
502 — Open-Meteo недоступен.

## Инструменты MCP-сервера

| Инструмент | Аргументы | Что делает |
|---|---|---|
| `search_city` | `query` (обязательный), `limit` | Ищет город, снимает неоднозначность названий |
| `get_current_weather` | `city` | Текущая погода |
| `get_forecast` | `city`, `days` | Прогноз по дням |

Ошибка бизнес-сервиса («город не найден», «сервис недоступен») возвращается как результат
с `isError: true`, а не рвет соединение: модель видит причину и может исправиться сама.

## Про транспорт stdio

Клиент запускает сервер как дочерний процесс и общается с ним через stdin/stdout. Порта нет,
поэтому такой сервер работает только локально — удаленно к нему не подключиться, для этого
понадобился бы транспорт Streamable HTTP.

Отсюда же главная ловушка stdio: **в stdout может быть только протокол**. Пока сервер писал туда
через `System.out`, посторонняя строка от библиотеки логирования ломала разбор JSON-RPC:

```
ERROR ReadBuffer - Failed to deserialize message from line: kotlin-logging: initializing...
```

Поэтому `Main.kt` первым делом забирает настоящий дескриптор stdout себе под протокол, а `System.out`
переводит в stderr. После этого никакой `println` в коде и никакая болтливая библиотека соединение не сломают.

## Подключение к Claude Code / Claude Desktop

Сервер собран в исполняемый jar, поэтому подключается одной строкой:

```bash
claude mcp add weather -- java -jar "$(pwd)/mcp-server/build/libs/mcp-server-0.0.1-SNAPSHOT.jar"
```

Для Claude Desktop то же самое в `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "weather": {
      "command": "java",
      "args": ["-jar", "/абсолютный/путь/day16/mcp-server/build/libs/mcp-server-0.0.1-SNAPSHOT.jar"],
      "env": { "WEATHER_API_URL": "http://localhost:8096" }
    }
  }
}
```

Сервис погоды при этом должен быть запущен — MCP-сервер сам данные не считает.

## Проверка руками

Протокол текстовый, поэтому его можно подать серверу прямо в stdin:

```bash
{ printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}' \
                '{"jsonrpc":"2.0","method":"notifications/initialized"}'
  sleep 1
  printf '%s\n' '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
  sleep 2
} | java -jar mcp-server/build/libs/mcp-server-0.0.1-SNAPSHOT.jar 2>/dev/null
```

## Стек

- Kotlin 2.4.20, JDK 21, Gradle (multi-project из трех модулей)
- Spring Boot 4.1.1 — сервис погоды
- `io.modelcontextprotocol:kotlin-sdk` 0.15.0 — сервер и клиент MCP
- Open-Meteo — источник данных, без ключа
