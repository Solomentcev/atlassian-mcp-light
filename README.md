# atlassian-mcp-server

MCP-сервер на Java для работы AI-агентов с Jira и Confluence через REST API. Обмен сообщениями выполняется по MCP (JSON-RPC 2.0) через STDIO.

## Возможности

### Jira

| Инструмент | Назначение |
|---|---|
| `jira_get_issue` | Получить задачу по ключу |
| `jira_search` | Найти задачи по JQL |
| `jira_create_issue` | Создать задачу |
| `jira_transition` | Перевести задачу в другой статус |
| `jira_add_comment` | Добавить комментарий |
| `jira_get_project` | Получить информацию о проекте |
| `jira_get_user` | Найти пользователя |

### Confluence

| Инструмент | Назначение |
|---|---|
| `confluence_get_page` | Получить страницу по ID |
| `confluence_list_spaces` | Получить список пространств |
| `confluence_get_space` | Получить информацию о пространстве |
| `confluence_search` | Найти страницы по CQL |
| `confluence_create_page` | Создать страницу |
| `confluence_update_page` | Обновить страницу |

Параметры инструментов и их обязательность MCP-клиент получает через JSON-схемы. Для `limit` допустимы значения от 1 до 100. При обновлении страницы Confluence можно передать `title`, `content` и `version`; если версия не указана, сервер определяет её автоматически. Содержимое страниц передаётся в формате Confluence Storage.

## Требования

- Java 17 или новее.
- Учётная запись Atlassian с доступом к нужным проектам Jira и пространствам Confluence.
- Maven Wrapper включён в репозиторий; отдельно устанавливать Maven для сборки не требуется.

Права учётной записи Atlassian должны разрешать операции, которые планируется выполнять: просмотр, поиск, создание и изменение задач или страниц.

## Конфигурация

Для подключения необходимы следующие параметры:

| Переменная | Обязательна | Назначение |
|---|---:|---|
| `JIRA_URL` | Да | Базовый HTTPS URL Jira, например `https://company.atlassian.net` |
| `JIRA_EMAIL` | Да | E-mail пользователя Atlassian |
| `JIRA_TOKEN` | Да | API-токен Atlassian |
| `CONFLUENCE_URL` | Нет | Базовый URL Confluence. По умолчанию используется `JIRA_URL/wiki` |
| `MCP_MAX_TEXT_LENGTH` | Нет | Максимальная длина текстового поля в ответе MCP. По умолчанию 4000 символов |
| `MCP_MAX_RESPONSE_BYTES` | Нет | Максимальный размер HTTP-ответа. По умолчанию 2097152 байта |
| `ATLASSIAN_LOG_LEVEL` | Нет | Уровень логирования: `DEBUG`, `INFO`, `WARN` или `ERROR`. По умолчанию `INFO` |

Сервер использует HTTP Basic-аутентификацию с парой `JIRA_EMAIL:JIRA_TOKEN`. Для Atlassian Cloud создайте API-токен в [настройках безопасности Atlassian](https://id.atlassian.com/manage-profile/security/api-tokens). Bearer-аутентификация с Personal Access Token не реализована.

Для локального запуска скопируйте шаблон конфигурации и заполните его:

```bash
cp .env.example .env
```

Файл `.env` содержит секреты и исключён из Git. Не публикуйте его и не добавляйте токены в репозиторий.

Также поддерживается файл `application.properties`:

```properties
jira.url=https://company.atlassian.net
jira.email=user@company.com
jira.token=your-api-token
# confluence.url=https://company.atlassian.net/wiki
```

Источники настроек применяются в таком порядке: свойства JVM, переменные окружения, файл `.env`, файл `application.properties`. Путь к файлу можно задать через `ATLASSIAN_ENV_FILE` или `ATLASSIAN_PROPERTIES_FILE` (либо соответствующие свойства JVM `atlassian.env.file` и `atlassian.properties.file`). Без явно заданного пути файлы ищутся в рабочем каталоге и рядом с JAR; для `application.properties` дополнительно проверяется classpath. Поэтому файл настроек можно разместить рядом с JAR-файлом, если рабочий каталог MCP-клиента неизвестен.

**Важно:** `JIRA_URL`, `JIRA_EMAIL` и `JIRA_TOKEN` обязательны даже при использовании только инструментов Confluence. Для Atlassian Server/Data Center укажите отдельный `CONFLUENCE_URL`, если адрес Confluence не соответствует `JIRA_URL/wiki`.

## Сборка и запуск

Из корня репозитория выполните:

```bash
./mvnw clean package
```

После сборки исполняемый файл будет находиться здесь:

```
atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar
```

Задайте параметры подключения и запустите сервер:

```bash
export JIRA_URL="https://company.atlassian.net"
export JIRA_EMAIL="user@company.com"
export JIRA_TOKEN="your-api-token"

java -jar atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar
```

Сервер читает запросы из `stdin` и записывает ответы JSON-RPC в `stdout`. Логи выводятся только в `stderr`. Не направляйте в `stdout` посторонний текст — это нарушит обмен с MCP-клиентом.

## Подключение MCP-клиента

Добавьте сервер в MCP-конфигурацию клиента. Ниже общий пример STDIO-конфигурации; синтаксис и расположение файла настроек зависят от конкретного клиента:

```json
{
  "mcpServers": {
    "atlassian": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/atlassian-mcp-app/target/atlassian-mcp-server-1.0-SNAPSHOT.jar"
      ],
      "env": {
        "JIRA_URL": "https://company.atlassian.net",
        "JIRA_EMAIL": "user@company.com",
        "JIRA_TOKEN": "your-api-token"
      }
    }
  }
}
```

Замените путь и значения параметров на свои. Вместо передачи секретов в конфигурации клиента можно указать путь к файлу настроек через `ATLASSIAN_ENV_FILE` или `ATLASSIAN_PROPERTIES_FILE`.

## Docker

Перед запуском создайте файл `.env` из шаблона и заполните обязательные параметры:

```bash
cp .env.example .env
```

Сборка образа:

```bash
docker compose build
```

Запуск MCP-сервера:

```bash
docker compose run --rm -i atlassian-mcp-server
```

Docker Compose использует `.env` через `env_file`. Сервер работает по STDIO, поэтому контейнер должен запускаться в интерактивном режиме без псевдотерминала. Не используйте `docker compose up -d` или флаг Docker `-t`: MCP-клиент должен обмениваться данными с процессом через `stdin/stdout`.

## Структура проекта

Проект состоит из четырёх Maven-модулей:

| Модуль | Назначение |
|---|---|
| `atlassian-core` | MCP-протокол, конфигурация, HTTP-клиент и общая инфраструктура |
| `atlassian-jira` | Интеграция с Jira |
| `atlassian-confluence` | Интеграция с Confluence |
| `atlassian-mcp-app` | Точка входа и сборка приложения |

Модули Jira и Confluence используют общее ядро и не зависят друг от друга.

## Разработка

Сборка проекта:

```bash
./mvnw clean package
```

Запуск тестов:

```bash
./mvnw test
```

Тесты находятся в `src/test` соответствующих Maven-модулей.
