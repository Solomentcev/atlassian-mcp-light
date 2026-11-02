package ru.solomentsev.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.solomentsev.client.HttpClient;

import java.io.IOException;
import java.util.Locale;
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

    /** Максимальная длина текста, возвращаемого агентом. */
    private static final int MAX_TEXT_LENGTH = 4000;

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
        this.mapper = new ObjectMapper();
        this.client = client;
        this.baseUrl = normalizeUrl(baseUrl);
    }

    /**
     * Получает issue по ключу.
     *
     * @param issueKey ключ задачи (например, "PROJ-123")
     * @return строка с информацией о задаче
     * @throws IOException если запрос не удался
     */
    public String getIssue(String issueKey) throws IOException {
        String url = baseUrl + REST_API + "/issue/" + issueKey
                + "?fields=summary,description,status,issuetype";
        JsonNode json = parseResponse(client.get(url));

        ObjectNode result = mapper.createObjectNode();
        result.put("key", json.has("key") ? json.get("key").asText() : issueKey);
        if (json.has("fields")) {
            JsonNode fields = json.get("fields");
            if (fields.has("summary")) {
                result.put("summary", fields.get("summary").asText());
            }
            if (fields.has("status") && fields.get("status").has("name")) {
                result.put("status", fields.get("status").get("name").asText());
            }
            if (fields.has("issuetype") && fields.get("issuetype").has("name")) {
                result.put("type", fields.get("issuetype").get("name").asText());
            }
            if (fields.has("description") && !fields.get("description").isNull()) {
                String description = fields.get("description").asText();
                if (description.length() > MAX_TEXT_LENGTH) {
                    description = description.substring(0, MAX_TEXT_LENGTH) + "...";
                }
                result.put("description_snippet", description);
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
     */
    public String search(String jql, int limit) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("jql", withSearchRestriction(jql));
        payload.put("maxResults", limit);
        ArrayNode fields = mapper.createArrayNode();
        fields.add("summary");
        fields.add("status");
        payload.set("fields", fields);

        String url = baseUrl + SEARCH_API;
        JsonNode json = parseResponse(client.post(url, mapper.writeValueAsString(payload)));
        ArrayNode issues = json.has("issues") && json.get("issues").isArray()
                ? (ArrayNode) json.get("issues")
                : mapper.createArrayNode();

        ArrayNode results = mapper.createArrayNode();
        for (JsonNode issue : issues) {
            ObjectNode item = mapper.createObjectNode();
            item.put("key", issue.has("key") ? issue.get("key").asText() : "");
            if (issue.has("fields")) {
                JsonNode f = issue.get("fields");
                if (f.has("summary")) {
                    item.put("summary", f.get("summary").asText());
                }
                if (f.has("status") && f.get("status").has("name")) {
                    item.put("status", f.get("status").get("name").asText());
                }
            }
            results.add(item);
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
        if (description != null && description.length() > MAX_TEXT_LENGTH) {
            description = description.substring(0, MAX_TEXT_LENGTH);
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
        String url = baseUrl + REST_API + "/project/" + projectKey;
        JsonNode json = parseResponse(client.get(url));
        String key = json.has("key") ? json.get("key").asText() : projectKey;
        String name = json.has("name") ? json.get("name").asText() : "";
        String typeKey = json.has("projectTypeKey") ? json.get("projectTypeKey").asText() : "";
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

        String displayName = json.has("displayName") ? json.get("displayName").asText() : "N/A";
        String email = json.has("emailAddress") ? json.get("emailAddress").asText() : "N/A";
        return "Пользователь: " + userIdentifier + " - " + displayName + " (" + email + ")";
    }

    // ==================== Private helpers ====================

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

    private String encodeUrl(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
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
