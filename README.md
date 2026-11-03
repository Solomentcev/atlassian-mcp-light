# atlassian-mcp-server

Лёгкий MCP-сервер на Java, дающий AI-агентам (Claude, Cline, Cursor и др.) доступ к **Jira** и **Confluence** через REST API.

Сервер работает по протоколу **MCP (JSON-RPC 2.0)** через `stdin`/`stdout` и предоставляет 13 готовых инструментов: чтение и поиск задач, комментарии, переводы между статусами, а также чтение, поиск, создание и обновление страниц Confluence.

---

## Возможности

| Инструмент               | Описание                                                       | Обязательные параметры                                      |
|--------------------------|----------------------------------------------------------------|-------------------------------------------------------------|
| `jira_get_issue`         | Краткая информация о задаче (заголовок, статус, тип, описание) | `issue_key`                                                 |
| `jira_search`            | Поиск задач по JQL                                             | `jql` (опц. `limit`, по умолчанию 5)                        |
| `jira_create_issue`      | Создание задачи/бага                                           | `project_key`, `summary`, `description` (опц. `issue_type`) |
| `jira_transition`        | Перевод задачи в другой статус                                 | `issue_key`, `transition_name`                              |
| `jira_add_comment`       | Добавление комментария к задаче                                | `issue_key`, `comment`                                      |
| `jira_get_project`       | Информация о проекте                                           | `project_key`                                               |
| `jira_get_user`          | Информация о пользователе                                      | `user_identifier`                                           |
| `confluence_get_page`    | Чтение страницы по ID                                          | `page_id`                                                   |
| `confluence_list_spaces` | Список пространств                                             | — (опц. `limit`, по умолчанию 25)                           |
| `confluence_get_space`   | Информация о пространстве                                      | `space_key`                                                 |
| `confluence_search`      | Поиск страниц по CQL                                           | `cql` (опц. `limit`, по умолчанию 5)                        |
| `confluence_create_page` | Создание страницы                                              | `space_key`, `title`, `content` (опц. `parent_id`)          |
| `confluence_update_page` | Обновление страницы (версия определяется автоматически)        | `page_id` (опц. `title`, `content`)                         |

Аргументы инструментов проверяются сервером по опубликованным JSON-схемам: строки должны быть непустыми, числовые значения — целыми, неизвестные параметры отклоняются. Для параметров `limit` допустим диапазон от 1 до 100; для `version` — положительное целое число. Некорректные аргументы возвращаются как ошибка вызова инструмента (`isError: true`), без обращения к Atlassian API.
Инструменты также публикуют MCP-аннотации `readOnlyHint`, `destructiveHint`, `idempotentHint` и `openWorldHint`. Это описательные подсказки для MCP-клиента, а не механизм авторизации или ограничения доступа.

JSON-RPC запросы проверяются на версию `2.0`, строковый метод, объект `params` и допустимый тип `id`. Только отсутствие поля `id` обозначает уведомление; `id: null` считается некорректным запросом. Ошибка синтаксиса JSON возвращается как `-32700 Parse error`, а корректный JSON с неверной структурой — как `-32600 Invalid Request`.

---

## Требования

- **Java 17+** (проверено на Java 25)
- **Maven 3.8+**
- Учётная запись Atlassian с доступом к Jira и/или Confluence

Внешние HTTP-библиотеки не требуются: запросы выполняются штатным `java.net.http.HttpClient` из JDK. Зависимости: Jackson Databind для работы с JSON, SLF4J + Logback для логирования.

---

## Конфигурация

### Параметры

| Переменная       | Обязательна | Описание                                                                |
|------------------|-------------|-------------------------------------------------------------------------|
| `JIRA_URL`       | да          | Базовый URL Jira, например `https://your-company.atlassian.net`         |
| `JIRA_EMAIL`     | да          | E-mail пользователя (только для Basic-аутентификации)                   |
| `JIRA_TOKEN`     | да          | API-токен Atlassian (Cloud) или Personal Access Token (Server/DC)       |
| `CONFLUENCE_URL` | нет         | Базовый URL Confluence. Если не задан — используется `JIRA_URL + /wiki` |
| `MCP_MAX_TEXT_LENGTH` | нет | Количество сохраняемых символов в одном текстовом поле до маркера `...` (по умолчанию 4000; диапазон 1–1000000) |
| `MCP_MAX_RESPONSE_BYTES` | нет | Максимальный размер тела одного HTTP-ответа (по умолчанию 2097152 байта; диапазон 1–104857600) |

> **Важно:** секреты не должны попадать в репозиторий. Используйте переменные окружения, файл `.env` или `application.properties` (оба в `.gitignore`).

Получение API-токена Atlassian Cloud: `https://id.atlassian.com/manage-profile/security/api-tokens`.

### Источники конфигурации

Конфигурация собирается из нескольких источников. Значение берётся из первого источника, где оно задано и не пустое (пустая переменная окружения считается незаданной и не перекрывает следующий источник):

| Приоритет | Источник                        | Ключи                                                    |
|-----------|---------------------------------|----------------------------------------------------------|
| 1         | Свойства JVM (`-Djira.url=...`) | `jira.url`, `jira.email`, `jira.token`, `confluence.url`, `mcp.max-text-length`, `mcp.max-response-bytes` |
| 2         | Переменные окружения            | `JIRA_URL`, `JIRA_EMAIL`, `JIRA_TOKEN`, `CONFLUENCE_URL`, `MCP_MAX_TEXT_LENGTH`, `MCP_MAX_RESPONSE_BYTES` |
| 3         | Файл `.env`                     | `JIRA_URL=...` (формат dotenv)                           |
| 4         | Файл `application.properties`   | `jira.url=...` (допустимы и `JIRA_URL=...`)              |

#### Файл `.env`

```dotenv
# комментарий
JIRA_URL=https://your-company.atlassian.net
JIRA_EMAIL=user@company.com
JIRA_TOKEN=your-api-token
# CONFLUENCE_URL=https://your-company.atlassian.net/wiki
```

#### Файл `application.properties`

```properties
jira.url=https://your-company.atlassian.net
jira.email=user@company.com
jira.token=your-api-token
# confluence.url=https://your-company.atlassian.net/wiki
# mcp.max-text-length=4000
# mcp.max-response-bytes=2097152
```

#### Где ищутся файлы

1. Явно заданный путь: переменная окружения `ATLASSIAN_ENV_FILE` / `ATLASSIAN_PROPERTIES_FILE` (либо свойство JVM `-Datlassian.env.file=...` / `-Datlassian.properties.file=...`).
2. Текущий рабочий каталог.
3. Каталог рядом с jar-файлом — самый надёжный вариант для MCP-клиентов, у которых рабочий каталог непредсказуем.
4. Для `application.properties` дополнительно проверяется classpath (внутри jar-файла).

Отсутствие файла не является ошибкой. Нечитаемый файл или несуществующий явно заданный путь приводят к предупреждению в `stderr` (в `stdout` идёт JSON-RPC) и к продолжению работы со следующими источниками.

---

## Сборка

```bash
mvn clean package
```

В `atlassian-mcp-app/target/` появится самодостаточный uber-jar со всеми зависимостями:

```
atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar
```

Полезные цели:

```bash
mvn test                       # только тесты
mvn clean package -DskipTests  # сборка без тестов
```

---

## Запуск

```bash
export JIRA_URL="https://your-company.atlassian.net"
export JIRA_EMAIL="user@company.com"
export JIRA_TOKEN="your-api-token"

java -jar atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar
```

Сервер ожидает JSON-RPC сообщения в `stdin` и отвечает в `stdout` (по одному JSON-объекту на строку). Проверить вручную:

```bash
echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  | java -jar atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar
```

```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"atlassian-lite","version":"1.0.0"}}}
```

---

## Подключение к MCP-клиенту

### Claude Desktop (`claude_desktop_config.json`)

```json
{
  "mcpServers": {
    "atlassian": {
      "command": "java",
      "args": ["-jar", "/полный/путь/atlassian-mcp-server/atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar"],
      "env": {
        "JIRA_URL": "https://your-company.atlassian.net",
        "JIRA_EMAIL": "user@company.com",
        "JIRA_TOKEN": "your-api-token"
      }
    }
  }
}
```

### Cline / Cursor / другие клиенты

Аналогично: указываем `command: java`, в `args` — путь к jar-файлу, в `env` — переменные окружения. Для VS Code (`.vscode/mcp.json`):

```json
{
  "servers": {
    "atlassian": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/полный/путь/atlassian-mcp-server/atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar"],
      "env": {
        "JIRA_URL": "https://your-company.atlassian.net",
        "JIRA_EMAIL": "user@company.com",
        "JIRA_TOKEN": "your-api-token"
      }
    }
  }
}
```

#### Cline CLI и плагин Cline для IDE

Cline CLI и плагин Cline для IntelliJ IDEA читают MCP-конфиг из `~/.cline/data/settings/cline_mcp_settings.json`.
Секреты удобно оставить в `.env` проекта, а в конфиге указать только путь к нему:

```json
{
  "mcpServers": {
    "atlassian-local": {
      "type": "stdio",
      "command": "/usr/bin/java",
      "args": [
        "-jar",
        "/полный/путь/atlassian-mcp-server/atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar"
      ],
      "env": {
        "ATLASSIAN_ENV_FILE": "/полный/путь/atlassian-mcp-server/.env",
        "ATLASSIAN_LOG_LEVEL": "INFO"
      },
      "disabled": false,
      "autoApprove": [],
      "timeout": 60
    }
  }
}
```

Cline перечитывает конфиг автоматически и перезапускает процесс сервера, поэтому после пересборки jar достаточно
сохранить файл конфигурации. Инструменты будут доступны как `mcp__atlassian-local__jira_search` и т. п.

### Конфигурация без переменных окружения

Если секреты не хочется держать в конфиге клиента, положите рядом с jar-файлом `application.properties` (или `.env`) — сервер найдёт их автоматически:

```
target/
├── atlassian-mcp-server-1.0-SNAPSHOT.jar
└── application.properties   # jira.url=..., jira.email=..., jira.token=...
```

Тогда блок `env` в конфиге клиента не нужен. Путь к файлу можно задать и явно: `"env": {"ATLASSIAN_PROPERTIES_FILE": "/путь/к/application.properties"}`.

---

## Логирование

Логи пишутся через SLF4J + Logback **только в `stderr`**. Поток `stdout` полностью занят протоколом JSON-RPC: любой посторонний вывод в него ломает разбор ответов MCP-клиентом. Это обеспечивается конфигурацией `atlassian-core/src/main/resources/logback.xml` (`<target>System.err</target>`).

Уровень логирования задаётся переменной окружения `ATLASSIAN_LOG_LEVEL`:

| Уровень    | Что попадает в лог                                                                 |
|------------|-------------------------------------------------------------------------------------|
| `DEBUG`    | Источники конфигурации, HTTP-запросы, имена инструментов и ключи их аргументов       |
| `INFO`     | Запуск/остановка сервера, входящие MCP-запросы, HTTP-статусы и длительность запросов  |
| `WARN`     | Проблемы с файлами конфигурации                                                     |
| `ERROR`    | Ошибки HTTP и вызова инструментов с traceback                                       |

Значение по умолчанию — `INFO`. Пример запуска с подробным логом:

```bash
ATLASSIAN_LOG_LEVEL=DEBUG java -jar atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar
```

Пример вывода в `stderr`:

```
12:47:21.264 DEBUG [main] ru.solomentsev.core.config.ConfigLoader - Параметр jira.url взят из файла .env
12:47:21.370 INFO  [main] ru.solomentsev.core.protocol.McpProtocol - Запрос: method=tools/call, id=3
12:47:21.377 DEBUG [main] ru.solomentsev.core.protocol.McpProtocol - Вызов инструмента: jira_search (аргументы: [jql, limit])
12:47:21.378 DEBUG [main] ru.solomentsev.core.client.JdkHttpClient - HTTP-запрос: POST /rest/api/3/search/jql
12:47:22.434 INFO  [main] ru.solomentsev.core.client.JdkHttpClient - HTTP-ответ: POST /rest/api/3/search/jql -> 200 (1055 мс)
```

**Что никогда не попадает в логи:** API-токен, заголовок `Authorization`, значения аргументов инструментов, тела запросов и query-строка URL (в лог пишется только путь). Имена аргументов логируются — значения нет.

---

## Запуск в Docker

Проект включает `Dockerfile` (multi-stage сборка) и `docker-compose.yml` для запуска MCP-сервера через STDIO.

MCP-сервер не является фоновым HTTP-сервисом: MCP-клиент должен запускать его как дочерний процесс и обмениваться сообщениями через `stdin/stdout`. Поэтому не используйте `docker compose up -d` и не запускайте контейнер с TTY.

### Сборка образа

Создайте файл `.env` на основе примера и укажите учётные данные:

```bash
cp .env.example .env
docker compose build
```

### Запуск через Docker Compose

Из каталога проекта выполните:

```bash
docker compose run --rm -i atlassian-mcp-server
```bash

Команда запускает контейнер в интерактивном режиме без псевдотерминала. `stdin` остаётся открытым, `stdout` используется только для JSON-RPC, диагностические сообщения выводятся в `stderr`.

### Запуск напрямую через Docker

```bash
docker build -t atlassian-mcp-server .
docker run -i --rm --env-file .env atlassian-mcp-server
```bash

**Пример конфигурации для Claude Desktop (`claude_desktop_config.json`):**

```json
{
  "mcpServers": {
    "atlassian": {
      "command": "docker",
      "args": [
        "run", "-i", "--rm",
        "--env-file", "/полный/путь/к/.env",
        "atlassian-mcp-server"
      ]
    }
  }
}
```

Для Cline, Cursor и других MCP-клиентов используется аналогичная конфигурация с `docker run -i --rm`.

> Не добавляйте флаг `-t`: псевдотерминал может добавить управляющие последовательности и нарушить STDIO-транспорт MCP.

### Переменные окружения в Docker

Основные переменные передаются через `--env-file .env` или `env_file` в `docker-compose.yml`:

| Переменная | Обязательна | Описание |
|------------|-------------|----------|
| `JIRA_URL` | да | Базовый URL Jira (`https://your-company.atlassian.net`) |
| `JIRA_EMAIL` | да | E-mail пользователя для Basic-аутентификации |
| `JIRA_TOKEN` | да | API-токен Atlassian |
| `CONFLUENCE_URL` | нет | Базовый URL Confluence (по умолчанию `JIRA_URL/wiki`) |
| `MCP_MAX_TEXT_LENGTH` | нет | Максимальная длина текстового поля (по умолчанию 4000) |
| `MCP_MAX_RESPONSE_BYTES` | нет | Максимальный размер HTTP-ответа в байтах (по умолчанию 2097152) |
| `ATLASSIAN_LOG_LEVEL` | нет | Уровень логирования (DEBUG, INFO, WARN, ERROR), по умолчанию INFO |

Логи приложения выводятся в `stderr`. Не перенаправляйте `stdout` в файл логов: он зарезервирован для JSON-RPC.

---

## Поддерживаемые методы протокола

| Метод        | Назначение                                               |
|--------------|----------------------------------------------------------|
| `initialize` | Рукопожатие, объявление доступных возможностей           |
| `tools/list` | Список инструментов с JSON-схемами аргументов            |
| `tools/call` | Вызов инструмента: `{"name": "...", "arguments": {...}}` |
| `ping`       | Проверка живости соединения, пустой результат            |

Уведомления (запросы без `id`, например `notifications/initialized`) обрабатываются, но **не порождают ответа** —
это требование JSON-RPC 2.0, которое требуется соблюдать, иначе MCP-клиент (Cline, Claude Desktop) не сможет
разобрать полученный кадр и разорвёт подключение.

Ошибки выполнения возвращаются в поле `result.isError` с текстом в `result.content[0].text`, поэтому агент может их показать пользователю.

---

## Структура проекта

Проект разделён на четыре Maven-модуля:

```
atlassian-mcp-server/
├── pom.xml                         # родительский POM и агрегатор модулей
├── atlassian-core/                 # общая инфраструктура
│   └── src/
│       ├── main/java/              # HTTP-клиент, конфигурация, MCP-протокол
│       ├── main/resources/         # logback.xml
│       └── test/java/              # тесты общей инфраструктуры
├── atlassian-jira/                 # интеграция с Jira
│   └── src/
│       ├── main/java/              # JiraService и JiraToolProvider
│       └── test/java/              # тесты Jira
├── atlassian-confluence/           # интеграция с Confluence
│   └── src/
│       ├── main/java/              # ConfluenceService и ConfluenceToolProvider
│       └── test/java/              # тесты Confluence
└── atlassian-mcp-app/              # приложение и точка сборки
    └── src/
        ├── main/java/              # AtlassianMcpServer и McpToolInitializer
        └── test/java/              # интеграционные тесты MCP-регистрации
```

Зависимости направлены от продуктовых модулей к общему ядру:

- `atlassian-jira` → `atlassian-core`;
- `atlassian-confluence` → `atlassian-core`;
- `atlassian-mcp-app` → `atlassian-core`, `atlassian-jira`, `atlassian-confluence`.

Модули Jira и Confluence не зависят друг от друга. Общий интерфейс `ToolProvider`
позволяет подключать новые продуктовые интеграции без изменений MCP-протокола.

Юнит-тесты расположены в `src/test` соответствующих модулей. Отдельный тестовый Maven-модуль не используется.

---

## Технические детали и ограничения

- **Версия протокола:** `2024-11-05`.
- **Аутентификация:** HTTP Basic (`email:token`). Поддерживается только базовая аутентификация — OAuth 2.0 не реализован.
- **REST API:** Jira — `rest/api/2`, Confluence — `rest/api` (v1). Для Confluence Cloud v1 с `?expand=body.storage` работает корректно; при необходимости смены версии API достаточно править URL в коде.
- **Jira Cloud — поиск задач:** эндпоинт `/rest/api/2/search` в облаке удалён (HTTP 410), поэтому поиск выполняется через `POST /rest/api/3/search/jql` с телом `{"jql": "...", "maxResults": N, "fields": [...]}`. Новый эндпоинт отклоняет неограниченные запросы (например, `ORDER BY created DESC` без условий) с HTTP 400 — в этом случае сервер сам ограничивает запрос последними 30 днями (`created >= -30d`), а запрос с уже заданными условиями фильтрации не изменяется.
- **Пагинация поиска:** Jira продолжает поиск по `nextPageToken`, а Confluence использует `start` и `_links.next`. Результаты объединяются до достижения запрошенного `limit`; повторяющийся токен Jira останавливает запрос с ошибкой, а Confluence не использует URL следующей страницы напрямую.
- **Jira Cloud — пользователи:** параметр `username` у `/rest/api/2/user` удалён (требуется `accountId`). Поиск пользователя выполняется через `/rest/api/2/user/search?query=...` (совпадение по e-mail, имени или username), а если передан `accountId` — прямой запрос `/rest/api/2/user?accountId=...`.
- **Структура payload Confluence** (по OpenAPI-спецификации Atlassian v1): `title` — обычная строка, `space` передаётся как `{"key": "..."}` и обязателен при создании, `body.storage.representation` — всегда `"storage"` (допустимые значения: `storage`, `atlas_doc_format`, `view`, `wiki`, `raw` и др.; `"html"` в enum отсутствует и приводит к `400 Bad Request`).
- **Идентификатор Confluence:** используется числовой `id` страницы (не `pageId` в формате «pageId/12345»).
- **Ограничение контекста:** длинные текстовые поля Jira и Confluence сохраняют не более `MCP_MAX_TEXT_LENGTH` символов (по умолчанию 4000), после чего добавляется маркер `...`; JSON формируется после сокращения полей и остаётся валидным.
- **Безопасность URL:** Jira и Confluence должны быть доступны по HTTPS. URL с `http`, user-info, query или fragment отклоняются, чтобы не отправлять Basic Auth по незашифрованному соединению и не допускать неоднозначную сборку REST URL.
- **Ограничение HTTP-ответов:** тело каждого ответа Atlassian читается потоково и ограничивается `MCP_MAX_RESPONSE_BYTES` (по умолчанию 2 МиБ). Превышение лимита приводит к ошибке инструмента без загрузки всего тела в память.
- **Потокобезопасность:** сервер обрабатывает запросы последовательно, по одному JSON-объекту на строку.
- **Логирование:** SLF4J + Logback, вывод только в `stderr`, уровень — `ATLASSIAN_LOG_LEVEL` (по умолчанию `INFO`); см. раздел «Логирование».
- **Сборка:** `maven-shade-plugin` собирает uber-jar; `dependency-reduced-pom.xml` не создаётся (`createDependencyReducedPom=false`).

---

## Лицензия

Свободное использование в личных и коммерческих целях.

