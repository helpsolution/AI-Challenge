# API и MCP дня 31

## HTTP API ассистента

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/api/project` | Название репозитория, доступные дни, состояние индекса, модель и наличие настройки ключа |
| POST | `/api/help` | Полный ответ в JSON |
| POST | `/api/help/stream` | SSE: события `start`, `step`, `answer` |
| POST | `/api/knowledge/rebuild` | Принудительно обновить индекс документации |
| GET | `/api/traces` | Список последних трассировок |
| GET | `/api/traces/{id}` | Шаги и ответ конкретного запроса |
| GET | `/api/source?path=day31/docs/api.md` | Исходный документ; разрешены только документы из индекса |

Запрос `/api/help`: `{"message":"/help Как устроен day31?","scope":"day31","history":[]}`. Поле message — от 1 до 6000 символов. Scope — `repo` или `dayN`. История — до 12 сообщений с ролями user и assistant, суммарно до 24000 символов.

Ответ содержит `id`, `scope`, `question`, `status`, `answer`, `reason`, `branch`, `sources`, `quotes`, `tools`, `durationMs`, `model`, `promptTokens`, `completionTokens` и `warnings`. Источник содержит тип (`document` или `mcp`), путь, текст, а для документа — раздел и строки. У MCP сохраняется время получения данных.

Статусы ответа: `answered`, `help`, `clarify`, `weak_context`, `no_answer`, `unverified`, `failed`. Некорректный запрос получает HTTP 400. Ошибка внутри пайплайна возвращается как `failed` с причиной и трассировкой.

## MCP-инструменты

| Инструмент | Аргументы | Результат |
|---|---|---|
| `git_branch` | `{}` | branch, detached, commit, capturedAt |
| `git_status` | `{}` | clean, count, changes, truncated, capturedAt |
| `list_files` | `{"scope":"day31","limit":160}` | scope, count, files, truncated, capturedAt |

`git_branch` выполняет фиксированные команды `git branch --show-current` и `git rev-parse --short HEAD`. В detached HEAD branch равен null, detached равен true, commit содержит текущий короткий хеш.

`git_status` использует `git status --porcelain=v1 -z` и возвращает до 100 изменений публичных файлов. `clean` относится к публичным файлам, прошедшим фильтр. Имена с пробелами и кириллицей сохраняются. При переименовании доступен originalPath.

`list_files` использует `git ls-files --cached --others --exclude-standard -z`: учитывает tracked-файлы и новые файлы, не исключённые gitignore. Limit — от 1 до 300. Scope — только `repo` или `dayN`; произвольные пути не принимаются. Скрытые каталоги, секреты, data и build исключаются дополнительно.

Сервер возвращает JSON в `structuredContent` и текстовой части MCP. Ошибка инструмента обозначается `isError: true`. Сервер слушает только `127.0.0.1:8312` и проверяет Host. Произвольного выполнения shell-команд нет.
