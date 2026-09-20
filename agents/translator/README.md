# Агент-переводчик

Отдельное приложение на Kotlin и Spring Boot. Принимает строку по HTTP и возвращает массив переводов на языки из конфигурации. Для одного запроса агент делает один вызов DeepSeek.

## Запуск

Нужен JDK 21. Из папки проекта:

```bash
cd "agents/translator"
```

Для нового окружения создайте `.env` из примера и впишите ключ DeepSeek:

```bash
cp .env.example .env
```

В текущей рабочей папке `.env` уже скопирован из соседнего проекта. Файл не попадает в Git. Ключ читается только сервером и не требуется в запросах к API.

```bash
./gradlew bootRun
```

По умолчанию сервер слушает `http://localhost:8096`. Если порт занят, задайте `PORT`, например `PORT=8097 ./gradlew bootRun`.

## Swagger UI

Откройте [http://localhost:8096/swagger-ui/index.html](http://localhost:8096/swagger-ui/index.html). Раскройте `POST /api/translations`, нажмите **Try it out**, замените текст в JSON и нажмите **Execute**. Ответ и HTTP-код появятся ниже формы. Метод `GET /api/languages` показывает текущие целевые языки. Схема OpenAPI доступна по адресу [http://localhost:8096/v3/api-docs](http://localhost:8096/v3/api-docs).

## Проверка через curl

Посмотреть языки, на которые переводит агент:

```bash
curl -sS http://localhost:8096/api/languages
```

Перевести текст:

```bash
curl -sS http://localhost:8096/api/translations \
  -H 'Content-Type: application/json' \
  -d '{"text":"Привет, мир!"}'
```

Пример ответа:

```json
{
  "translations": [
    {"code":"en","language":"English","text":"Hello, world!"},
    {"code":"de","language":"German","text":"Hallo, Welt!"},
    {"code":"fr","language":"French","text":"Bonjour, le monde !"},
    {"code":"es","language":"Spanish","text":"¡Hola, mundo!"},
    {"code":"ja","language":"Japanese","text":"こんにちは、世界！"}
  ]
}
```

Ещё один запрос для самостоятельной проверки:

```bash
curl -sS http://localhost:8096/api/translations \
  -H 'Content-Type: application/json' \
  -d '{"text":"Завтра встречаемся в 10:30 у вокзала."}'
```

Пустой текст или текст длиннее `translator.max-input-chars` возвращает HTTP 400 с полем `error`. Ошибка DeepSeek или неполный список переводов возвращает HTTP 502.

## Проверка через Postman

1. Запустите приложение.
2. Создайте запрос `POST http://localhost:8096/api/translations`.
3. Во вкладке **Body** выберите **raw → JSON** и вставьте `{"text":"Доброе утро!"}`.
4. Нажмите **Send**. Ответ придёт в поле `translations` в порядке языков из конфигурации.

Для просмотра языков создайте запрос `GET http://localhost:8096/api/languages`.

## Как это устроено

`TranslationController` принимает запрос и передаёт текст в `TranslatorAgent`. Агент проверяет вход, берёт целевые языки из `TranslatorProperties`, поручает `PromptBuilder` собрать сообщения и вызывает `LlmClient`. `DeepSeekClient` отправляет сообщения в `/chat/completions` с JSON-режимом ответа. Агент проверяет коды и полноту полученных переводов и формирует HTTP-ответ. Исходный язык явно задавать не нужно: модель определяет его по тексту. Состояние между запросами не хранится.

Языки и максимальная длина входа находятся в `src/main/resources/application.yml` в секции `translator`. Там же можно изменить модель, температуру, лимит выходных токенов и таймауты в секции `llm.deepseek`. Ключ задаётся через `DEEPSEEK_API_KEY` в `.env` или переменной окружения. Для модели, температуры и лимита токенов также есть переменные `DEEPSEEK_MODEL`, `DEEPSEEK_TEMPERATURE`, `DEEPSEEK_MAX_TOKENS`.
