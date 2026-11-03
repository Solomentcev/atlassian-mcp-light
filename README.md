# Atlassian MCP Light

MCP-сервер на Java для работы AI-агентов с Jira и Confluence через REST API. Транспорт — STDIO, формат сообщений — JSON-RPC 2.0.

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

Схемы и обязательные параметры инструментов передаются MCP-клиенту. Для параметра `limit` допустимы значения от 1 до 100. При обновлении страницы Confluence можно передать `title`, `content` и `version`; если версия не указана, сервер получает текущую версию и увеличивает её. Содержимое страницы передаётся в формате Confluence Storage.

## Требования

- JDK 17 или новее.
- Учётная запись Atlassian с правами на нужные проекты Jira и пространства Confluence.
- Для сборки используется Maven Wrapper из репозитория; отдельная установка Maven не требуется.

Сервер использует HTTP Basic-аутентификацию с парой `JIRA_EMAIL:JIRA_TOKEN`. Для Atlassian Cloud создайте API-токен в [настройках безопасности Atlassian](https://id.atlassian.com/manage-profile/security/api-tokens). Bearer-аутентификация с Personal Access Token не реализована.

## Конфигурация

Обязательные параметры:

| Переменная | Назначение |
|---|---|
| `JIRA_URL` | Базовый HTTPS URL Jira, например `https://company.atlassian.net` |
| `JIRA_EMAIL` | E-mail пользователя Atlassian |
| `JIRA_TOKEN` | API-токен Atlassian |

Дополнительные параметры:

| Переменная | Назначение | По умолчанию |
|---|---|---|
| `CONFLUENCE_URL` | Базовый URL Confluence | `JIRA_URL/wiki` |
| `MCP_MAX_TEXT_LENGTH` | Максимальная длина текстового поля в ответе MCP | `4000` символов |
| `MCP_MAX_RESPONSE_BYTES` | Максимальный размер HTTP-ответа | `2097152` байта |
| `ATLASSIAN_LOG_LEVEL` | Уровень логирования: `DEBUG`, `INFO`, `WARN`, `ERROR` | `INFO` |

Параметры Jira обязательны и при использовании только инструментов Confluence. Для Jira Server/Data Center укажите отдельный `CONFLUENCE_URL`, если Confluence расположен не по адресу `JIRA_URL/wiki`.

### Источники конфигурации

Сервер загружает настройки в следующем порядке приоритета:

1. Свойства JVM (`-D`).
2. Переменные окружения процесса.
3. Файл `.env`.
4. Файл `application.properties`.

Используется первое непустое значение. Поэтому при необходимости любой параметр можно переопределить через `-D` или ENV, не изменяя файлы конфигурации.

В репозитории есть шаблон `.env.example`. Создайте на его основе локальный файл:

```bash
cp .env.example .env
```

Заполните `JIRA_URL`, `JIRA_EMAIL` и `JIRA_TOKEN`. Файл `.env` читается самим приложением, поэтому для запуска из IntelliJ IDEA не требуется плагин загрузки dotenv. Файл содержит секреты: он исключён из Git. Не добавляйте его в репозиторий и не включайте токены в Docker-образ.

Встроенный файл `atlassian-mcp-app/src/main/resources/application.properties` содержит ссылки на ENV-переменные и значения по умолчанию для лимитов. Его можно использовать как основу для внешнего файла:

```properties
jira.url=${JIRA_URL}
jira.email=${JIRA_EMAIL}
jira.token=${JIRA_TOKEN}
confluence.url=${CONFLUENCE_URL}
mcp.max-text-length=${MCP_MAX_TEXT_LENGTH:4000}
mcp.max-response-bytes=${MCP_MAX_RESPONSE_BYTES:2097152}
```

Поддерживаются ссылки `${NAME}` и `${NAME:default}`. Значения ссылок ищутся в ENV и `.env`; значение после двоеточия используется, если переменная не задана. Не храните реальные токены в отслеживаемом Git файле `application.properties`.

По умолчанию `.env` и внешний `application.properties` ищутся в текущем рабочем каталоге и рядом с JAR; встроенный `application.properties` также загружается из classpath. Путь к файлам можно задать явно:

| Назначение | ENV | Свойство JVM |
|---|---|---|
| Путь к dotenv-файлу | `ATLASSIAN_ENV_FILE` | `-Datlassian.env.file=/path/to/.env` |
| Путь к properties-файлу | `ATLASSIAN_PROPERTIES_FILE` | `-Datlassian.properties.file=/path/to/application.properties` |

## Сборка и тестирование

Все команды выполняются из корня репозитория.

Собрать исполняемый JAR:

```bash
./mvnw clean package
```

Запустить тесты:

```bash
./mvnw test
```

Выполнить полный Maven lifecycle до фазы verify:

```bash
./mvnw clean verify
```

После успешной сборки JAR находится здесь:

```text
atlassian-mcp-app/target/atlassian-mcp-light-1.0-SNAPSHOT.jar
```

## Запуск

### IntelliJ IDEA

Создайте конфигурацию запуска типа **Application**:

- **Main class:** `ru.solomentsev.app.AtlassianMcpServer`.
- **Use classpath of module:** `atlassian-mcp-app`.
- **Working directory:** корень репозитория.

Создайте `.env` в корне репозитория и заполните параметры подключения. Приложение прочитает его при запуске. Дополнительные параметры можно задать в **VM options**, например:

```text
-Dmcp.max-text-length=8000
```

### Из командной строки

Если `.env` расположен в текущем каталоге или рядом с JAR, достаточно выполнить:

```bash
java -jar atlassian-mcp-app/target/atlassian-mcp-light-1.0-SNAPSHOT.jar
```

Либо передать переменные окружения средствами ОС или MCP-клиента. Пример переопределения настройки через JVM:

```bash
java -Dmcp.max-text-length=8000 -jar atlassian-mcp-app/target/atlassian-mcp-light-1.0-SNAPSHOT.jar
```

### STDIO

Сервер читает входящие сообщения из `stdin` и записывает ответы JSON-RPC в `stdout`. Логи направляются в `stderr`. Не выводите в `stdout` отладочные сообщения или другой посторонний текст — это нарушит обмен с MCP-клиентом.

## Подключение MCP-клиента

Добавьте сервер в конфигурацию MCP-клиента. Ниже общий пример STDIO-подключения через собранный JAR:

```json
{
  "mcpServers": {
    "atlassian-mcp-light": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/atlassian-mcp-app/target/atlassian-mcp-light-1.0-SNAPSHOT.jar"
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

Укажите абсолютный путь к JAR и свои параметры. Если MCP-клиент запускает процесс из корня репозитория, сервер также сможет найти расположенный там файл `.env`. Для другого рабочего каталога задайте `ATLASSIAN_ENV_FILE` с абсолютным путём к нему. Поддержка поля `env` и расположение MCP-конфигурации зависят от клиента.

## Docker

Docker-образ собирается отдельно от секретов. Файл `.env` не копируется в образ: Docker Compose передаёт его контейнеру во время запуска через `env_file`.

Создайте и заполните `.env`, затем соберите образ:

```bash
cp .env.example .env
# Заполните .env
docker compose build
```

Для локального запуска STDIO-сервера:

```bash
docker compose run --rm -i atlassian-mcp-light
```

Для подключения MCP-клиента укажите Docker Compose как STDIO-процесс. Пример общей конфигурации:

```json
{
  "mcpServers": {
    "atlassian-mcp-light": {
      "command": "docker",
      "args": [
        "compose",
        "-f",
        "/absolute/path/to/atlassian-mcp-light/docker-compose.yml",
        "run",
        "--rm",
        "-i",
        "atlassian-mcp-light"
      ]
    }
  }
}
```

Замените путь на абсолютный путь к своему `docker-compose.yml`. Файл `.env` должен находиться рядом с ним: Compose передаст параметры контейнеру через `env_file`. Не запускайте этот STDIO-сервис через `docker compose up -d` и не добавляйте флаг `-t`: MCP-клиенту нужен прямой обмен через stdin/stdout без псевдотерминала.

## Структура проекта

Проект состоит из четырёх Maven-модулей:

| Модуль | Назначение |
|---|---|
| `atlassian-core` | MCP-протокол, конфигурация, HTTP-клиент и общая инфраструктура |
| `atlassian-jira` | Инструменты и сервисы Jira |
| `atlassian-confluence` | Инструменты и сервисы Confluence |
| `atlassian-mcp-app` | Точка входа, регистрация инструментов и сборка приложения |

Модули Jira и Confluence используют общее ядро и не зависят друг от друга. Тесты находятся в `src/test` соответствующих модулей.
