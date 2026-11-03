package ru.solomentsev.jira.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.solomentsev.core.client.HttpClient;

import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Бизнес-логика Jira REST API.
 *
 * <p>Отвечает за все операции с Jira: получение issue,
 * поиск по JQL, создание задачи, перевод статуса,
 * добавление комментария, получение проекта и пользователя.</p>
 */
public class JiraService {

    /** Назначение Jira REST API. */
    private static final String REST_API = "/rest/api/2";

    /**
     * Эндпоинт поиска задач.
     *
     * <p>В Jira Cloud эндпоинт {@code /rest/api/2/search} удалён (HTTP 410 Gone),
     * поиск выполняется через {@code /rest/api/3/search/jql}. Формат ответа
     * совместим с v2: {@code issues[].key} и {@code issues[].fields}.</p>
     */
    private static final String SEARCH_API = "/rest/api/3/search/jql";

    /**
     * Ограничивающее условие JQL, добавляемое автоматически, если запрос
     * не содержит условий фильтрации: новый эндпоинт поиска отклоняет
     * неограниченные запросы (HTTP 400).
     */
    private static final String DEFAULT_SEARCH_RESTRICTION = " >= -30d";

    /** Формат accountId в виде UUID (Jira Server/Data Center). */
    private static final Pattern ACCOUNT_ID_UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Максимальная длина текстового поля по умолчанию. */
    private static final int DEFAULT_MAX_TEXT_LENGTH = 4000;

    /** Максимальная длина текстового поля в ответах Jira. */
    private final int maxTextLength;

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String baseUrl;

    /**
     * Конструктор.
     *
     * @param client  HTTP-клиент для выполнения запросов к Jira
     * @param baseUrl базовый URL Jira (например, https://company.atlassian.net)
     */
    public JiraService(HttpClient client, String baseUrl) {
        this(client, baseUrl, DEFAULT_MAX_TEXT_LENGTH);
    }

    /**
     * Создает сервис Jira с заданным ограничением длины возвращаемых текстовых полей.
     *
     * @param client HTTP-клиент Jira
     * @param baseUrl базовый URL Jira
     * @param maxTextLength максимальная длина текстового поля до добавления маркера сокращения
     * @throws IllegalArgumentException если лимит не положительный
     */
    public JiraService(HttpClient client, String baseUrl, int maxTextLength) {
        if (maxTextLength <= 0) {
            throw new IllegalArgumentException("maxTextLength должен быть больше нуля");
        }
        this.mapper = new ObjectMapper();
        this.client = client;
        this.baseUrl = normalizeUrl(baseUrl);
        this.maxTextLength = maxTextLength;
    }

    /**
     * Получает issue по ключу.
     *
     * @param issueKey ключ задачи (например, "PROJ-123")
     * @return строка с информацией о задаче
     * @throws IOException если запрос не удался
     */
    public String getIssue(String issueKey) throws IOException {
        String url = baseUrl + REST_API + "/issue/" + encodePathSegment(issueKey)
                + "?fields=summary,description,status,issuetype";
        JsonNode json = parseResponse(client.get(url));

        ObjectNode result = mapper.createObjectNode();
        result.put("key", json.has("key") ? json.get("key").asText() : issueKey);
        if (json.has("fields")) {
            JsonNode fields = json.get("fields");
            if (fields.has("summary")) {
                result.put("summary", truncate(fields.get("summary").asText()));
            }
            if (fields.has("status") && fields.get("status").has("name")) {
                result.put("status", truncate(fields.get("status").get("name").asText()));
            }
            if (fields.has("issuetype") && fields.get("issuetype").has("name")) {
                result.put("type", truncate(fields.get("issuetype").get("name").asText()));
            }
            if (fields.has("description") && !fields.get("description").isNull()) {
                String description = fields.get("description").asText();
                result.put("description_snippet", truncate(description));
            }
        }

        return mapper.writeValueAsString(result);
    }

    /**
     * Поиск задач по JQL.
     *
     * @param jql   поисковый запрос JQL
     * @param limit максимум результатов (по умолчанию 5)
     * @return строка со списком найденных задач (JSON array)
     * @throws IOException если запрос не удался
     * @throws IllegalArgumentException если limit не входит в диапазон 1..100
     */
    public String search(String jql, int limit) throws IOException {
        validateSearchLimit(limit);
        String url = baseUrl + SEARCH_API;
        ArrayNode results = mapper.createArrayNode();
        Set<String> requestedPageTokens = new HashSet<>();
        String nextPageToken = null;

        while (results.size() < limit) {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("jql", withSearchRestriction(jql));
            payload.put("maxResults", limit - results.size());
            ArrayNode fields = mapper.createArrayNode();
            fields.add("summary");
            fields.add("status");
            payload.set("fields", fields);
            if (nextPageToken != null) {
                if (!requestedPageTokens.add(nextPageToken)) {
                    throw new IOException("Jira вернула повторяющийся nextPageToken");
                }
                payload.put("nextPageToken", nextPageToken);
            }

            JsonNode page = parseResponse(client.post(url, mapper.writeValueAsString(payload)));
            JsonNode issues = page.path("issues");
            if (!issues.isArray() || issues.isEmpty()) {
                break;
            }

            for (JsonNode issue : issues) {
                if (results.size() >= limit) {
                    break;
                }
                ObjectNode item = mapper.createObjectNode();
                item.put("key", issue.has("key") ? issue.get("key").asText() : "");
                if (issue.has("fields")) {
                    JsonNode f = issue.get("fields");
                    if (f.has("summary")) {
                        item.put("summary", truncate(f.get("summary").asText()));
                    }
                    if (f.has("status") && f.get("status").has("name")) {
                        item.put("status", truncate(f.get("status").get("name").asText()));
                    }
                }
                results.add(item);
            }

            JsonNode nextToken = page.get("nextPageToken");
            if (results.size() >= limit || nextToken == null
                    || !nextToken.isTextual() || nextToken.asText().isBlank()) {
                break;
            }
            nextPageToken = nextToken.asText();
        }

        return mapper.writeValueAsString(results);
    }

    /**
     * Создание задачи.
     *
     * @param projectKey  ключ проекта (например, "PROJ")
     * @param summary    заголовок
     * @param description описание
     * @param issueType  тип задачи (Bug, Task, Story). По умолчанию Bug
     * @return строка с информацией о созданной задаче
     * @throws IOException если запрос не удался
     */
    public String createIssue(String projectKey, String summary, String description,
                             String issueType) throws IOException {
        if (description != null && description.length() > maxTextLength) {
            description = description.substring(0, maxTextLength);
        }

        String url = baseUrl + REST_API + "/issue";
        ObjectNode payload = mapper.createObjectNode();
        ObjectNode fields = payload.putObject("fields");

        ObjectNode project = mapper.createObjectNode();
        project.put("key", projectKey);
        fields.set("project", project);

        fields.put("summary", summary);
        fields.put("description", description);

        ObjectNode issuetype = mapper.createObjectNode();
        issuetype.put("name", issueType);
        fields.set("issuetype", issuetype);

        JsonNode json = parseResponse(client.post(url, mapper.writeValueAsString(payload)));

        ObjectNode result = mapper.createObjectNode();
        result.put("created_key", json.has("key") ? json.get("key").asText() : "");
        result.put("status", "success");

        return mapper.writeValueAsString(result);
    }

    /**
     * Перевод задачи в другой статус.
     *
     * @param issueKey       ключ задачи
     * @param transitionName имя статуса (например, "In Progress", "Done")
     * @return строка с подтверждением операции
     * @throws IOException если запрос не удался
     */
    public String transition(String issueKey, String transitionName) throws IOException {
        String transUrl = baseUrl + REST_API + "/issue/" + issueKey + "/transitions";

        JsonNode transitions = parseResponse(client.get(transUrl));
        ArrayNode transitionsList = transitions.has("transitions")
                && transitions.get("transitions").isArray()
                ? (ArrayNode) transitions.get("transitions")
                : mapper.createArrayNode();

        int targetId = -1;
        for (JsonNode t : transitionsList) {
            if (t.has("to") && t.get("to").has("name")) {
                String toName = t.get("to").get("name").asText().toLowerCase();
                if (toName.contains(transitionName.toLowerCase())) {
                    targetId = t.get("id").asInt();
                    break;
                }
            }
        }
        if (targetId == -1) {
            throw new IllegalArgumentException(
                    "Переход '" + transitionName + "' не найден. Доступны: " + transitionsList);
        }

        ObjectNode payload = mapper.createObjectNode();
        ObjectNode transition = mapper.createObjectNode();
        transition.put("id", String.valueOf(targetId));
        payload.set("transition", transition);
        client.post(transUrl, mapper.writeValueAsString(payload));

        ObjectNode result = mapper.createObjectNode();
        result.put("key", issueKey);
        result.put("new_status", transitionName);
        result.put("status", "success");

        return mapper.writeValueAsString(result);
    }

    /**
     * Добавление комментария к задаче.
     *
     * @param issueKey ключ задачи
     * @param comment   текст комментария
     * @return строка с подтверждением операции
     * @throws IOException если запрос не удался
     */
    public String addComment(String issueKey, String comment) throws IOException {
        String url = baseUrl + REST_API + "/issue/" + issueKey + "/comment";

        // Jira REST API v2 принимает комментарий обычной строкой.
        // ADF-документ используется в API v3, но здесь endpoint остаётся v2.
        ObjectNode payload = mapper.createObjectNode();
        payload.put("body", comment);

        client.post(url, mapper.writeValueAsString(payload));
        return "Комментарий добавлен к задаче " + issueKey;
    }

    /**
     * Получение информации о проекте.
     *
     * @param projectKey ключ проекта
     * @return строка с информацией о проекте
     * @throws IOException если запрос не удался
     */
    public String getProject(String projectKey) throws IOException {
        String url = baseUrl + REST_API + "/project/" + encodePathSegment(projectKey);
        JsonNode json = parseResponse(client.get(url));
        String key = json.has("key") ? json.get("key").asText() : projectKey;
        String name = json.has("name") ? truncate(json.get("name").asText()) : "";
        String typeKey = json.has("projectTypeKey") ? truncate(json.get("projectTypeKey").asText()) : "";
        return "Проект: " + key + " - " + name + " (" + typeKey + ")";
    }

    /**
     * Получение информации о пользователе.
     *
     * <p>В Jira Cloud параметр {@code username} у эндпоинта {@code /rest/api/2/user}
     * удалён (требуется {@code accountId}). Поэтому идентификатор определяется как
     * accountId (по формату) либо используется поиск по совпадению
     * {@code /rest/api/2/user/search?query=...}, который находит пользователя
     * по e-mail, имени или username.</p>
     *
     * @param userIdentifier идентификатор пользователя (accountId, e-mail или имя)
     * @return строка с информацией о пользователе
     * @throws IOException если запрос не удался
     */
    public String getUser(String userIdentifier) throws IOException {
        JsonNode json = looksLikeAccountId(userIdentifier)
                ? parseResponse(client.get(baseUrl + REST_API + "/user?accountId="
                + encodeUrl(userIdentifier)))
                : parseResponse(client.get(baseUrl + REST_API + "/user/search?query="
                + encodeUrl(userIdentifier)));

        if (json.isArray()) {
            if (json.isEmpty()) {
                return "Пользователь не найден: " + userIdentifier;
            }
            json = json.get(0);
        }

        String displayName = json.has("displayName") ? truncate(json.get("displayName").asText()) : "N/A";
        String email = json.has("emailAddress") ? truncate(json.get("emailAddress").asText()) : "N/A";
        return "Пользователь: " + userIdentifier + " - " + displayName + " (" + email + ")";
    }

    // ==================== Private helpers ====================

    /**
     * Сокращает длинное текстовое поле, не затрагивая структуру JSON-ответа.
     *
     * @param value исходный текст
     * @return исходный или сокращённый текст
     */
    private String truncate(String value) {
        return value != null && value.length() > maxTextLength
                ? value.substring(0, maxTextLength) + "..." : value;
    }

    private JsonNode parseResponse(String response) throws IOException {
        try {
            return mapper.readTree(response);
        } catch (IOException e) {
            throw new IOException("Не удалось разобрать ответ Jira: " + e.getMessage(), e);
        }
    }

    private String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.replaceAll("/+$", "");
    }

    /**
     * Кодирует значение для безопасного использования в query-параметре URL.
     *
     * @param value исходное значение
     * @return URL-кодированное значение
     */
    private static String encodeUrl(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Кодирует значение как отдельный сегмент пути URL.
     *
     * @param value исходное значение
     * @return URL-кодированный сегмент пути
     */
    private static String encodePathSegment(String value) {
        return encodeUrl(value).replace("+", "%20");
    }

    /**
     * Определяет, похож ли идентификатор на accountId Jira Cloud.
     *
     * @param identifier идентификатор пользователя
     * @return true, если идентификатор является accountId
     */
    private static boolean looksLikeAccountId(String identifier) {
        if (identifier == null) {
            return false;
        }
        String trimmed = identifier.trim();
        return trimmed.contains(":") || ACCOUNT_ID_UUID.matcher(trimmed).matches();
    }

    /**
     * Проверяет допустимый размер страницы поиска Jira.
     *
     * @param limit количество результатов
     * @throws IllegalArgumentException если значение не входит в диапазон 1..100
     */
    private static void validateSearchLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit должен быть в диапазоне от 1 до 100");
        }
    }

    /**
     * Добавляет ограничивающее условие к JQL-запросу, если в нём нет условий фильтрации.
     *
     * <p>Эндпоинт {@code /rest/api/3/search/jql} отклоняет неограниченные запросы
     * (например, {@code ORDER BY created DESC} без условий) с HTTP 400. В этом случае
     * добавляется ограничение по дате за последние 30 дней — поле ограничения
     * выбирается по полю сортировки запроса.</p>
     *
     * @param jql исходный JQL-запрос
     * @return JQL-запрос с ограничением
     */
    private static String withSearchRestriction(String jql) {
        if (jql == null || jql.trim().isEmpty()) {
            return "created" + DEFAULT_SEARCH_RESTRICTION + " ORDER BY created DESC";
        }
        String trimmed = jql.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith("order by")) {
            return trimmed;
        }
        String orderByField = trimmed.substring("order by".length()).trim().split("\\s+")[0];
        if (orderByField.isEmpty()) {
            return "created" + DEFAULT_SEARCH_RESTRICTION + " ORDER BY created DESC";
        }
        String boundField = orderByField.toLowerCase(Locale.ROOT).contains("updated")
                ? "updated" : "created";
        return boundField + DEFAULT_SEARCH_RESTRICTION + " " + trimmed;
    }
}
