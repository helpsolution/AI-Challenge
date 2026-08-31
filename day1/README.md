# День 1 — первый запрос к LLM через API

Веб-приложение: форма в браузере → бэкенд на Kotlin/Spring Boot → REST API DeepSeek → ответ на экране.
API-ключ хранится только на сервере, в браузер не передаётся.

## Стек

| Слой | Технологии |
|---|---|
| Backend | Kotlin 2.3, Spring Boot 4.1, Gradle Kotlin DSL, JDK 21 |
| Frontend | Vue 3 (CDN), дизайн-система Halo Light |
| LLM | DeepSeek (`deepseek-chat`, `deepseek-reasoner`) |

## Запуск

```bash
cp .env.example .env          # и вписать свой ключ с platform.deepseek.com
./gradlew bootRun
```

Открыть http://localhost:8080

Ключ читается из `.env` рядом с проектом (`spring.config.import`) или из переменной
окружения `DEEPSEEK_API_KEY` — на сервере второе.

## API

### `POST /api/chat`

```json
{
  "prompt": "Объясни, что такое токен",
  "model": "deepseek-chat",
  "params": {
    "temperature": 0.2,
    "topP": 0.9,
    "maxTokens": 512,
    "frequencyPenalty": 0,
    "presencePenalty": 0,
    "stop": ["###"]
  }
}
```

`model` и `params` необязательны. Любой не переданный параметр не уходит в API —
модель работает на своих значениях по умолчанию. Это и есть «продвинутый режим» в UI:
каждый параметр включается отдельным тумблером.

Ответ:

```json
{
  "answer": "…",
  "reasoning": null,
  "model": "deepseek-v4-flash",
  "finishReason": "stop",
  "usage": { "promptTokens": 23, "completionTokens": 70, "totalTokens": 93 },
  "latencyMs": 2363,
  "appliedParams": { "temperature": 0.2, "max_tokens": 40 },
  "exchange": {
    "method": "POST",
    "url": "https://api.deepseek.com/chat/completions",
    "requestHeaders": { "Content-Type": "application/json", "Authorization": "Bearer sk-abc…7890" },
    "requestBody": "{\"model\":\"deepseek-chat\",\"messages\":[…]}",
    "status": 200,
    "responseBody": "{\"id\":\"…\",\"choices\":[…],\"usage\":{…}}"
  }
}
```

`appliedParams` показывает, что фактически ушло в API — по нему в интерфейсе рисуются чипы.

`exchange` — сырой HTTP-обмен с провайдером: тело запроса читается ровно тем, что ушло
по сети, тело ответа — как пришло, без переработки. В интерфейсе он выведен вкладками
«Запрос / Ответ» под ответом модели. Ключ в заголовке маскируется (`sk-abc…7890`),
целиком он не покидает сервер. При ошибке провайдера тот же `exchange` приходит в теле
ошибки — видно, что именно не понравилось API.

### `GET /api/models`

Список разрешённых моделей и модель по умолчанию.

## Границы параметров

Проверяются на бэкенде до обращения к сети, чтобы не тратить запросы на заведомо
невалидные значения:

| Параметр | Диапазон | Дефолт провайдера |
|---|---|---|
| `temperature` | 0.0 – 2.0 | 1.0 |
| `topP` | 0.0 – 1.0 | 1.0 |
| `maxTokens` | 1 – 8192 | 4096 |
| `frequencyPenalty` | −2.0 – 2.0 | 0 |
| `presencePenalty` | −2.0 – 2.0 | 0 |
| `stop` | до 16 строк | — |

`deepseek-reasoner` параметры сэмплирования игнорирует — интерфейс об этом предупреждает.

## Тесты

```bash
./gradlew test
```

7 тестов: контракт запроса к API (snake_case, отсутствие пустых полей), разбор ответа,
прокидывание только включённых параметров, валидация границ, белый список моделей.

## Деплой на VPS

```bash
./gradlew bootJar
scp build/libs/day1-0.0.1-SNAPSHOT.jar user@host:/opt/day1/
ssh user@host 'DEEPSEEK_API_KEY=sk-… PORT=8080 java -jar /opt/day1/day1-0.0.1-SNAPSHOT.jar'
```

Для постоянной работы — unit systemd с `Environment=DEEPSEEK_API_KEY=…` и `Restart=always`.

**Важно:** приложение не имеет авторизации. На публичном адресе любой сможет
расходовать баланс ключа — перед публикацией закройте его basic-auth или rate-limit
(на уровне nginx или Spring Security).

## Структура

```
src/main/kotlin/advent/day1/
  config/    настройки провайдера, RestClient с таймаутами
  llm/       DTO API, интерфейс LlmClient, реализация DeepSeekClient
  chat/      ChatService — сборка запроса, разбор ответа
  web/       контроллер, DTO с валидацией, обработчик ошибок
src/main/resources/static/
  index.html Vue-приложение
  css/       system.css дизайн-системы Halo Light
DESIGN.md    спецификация дизайн-системы
```
