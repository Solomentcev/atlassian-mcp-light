package ru.solomentsev.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.solomentsev.client.HttpClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Бизнес-логика Confluence REST API.
 *
 * <p>Отвечает за операции с Confluence: чтение, создание и обновление
 * страниц, работа с пространствами и CQL-поиск.</p>
 */
public class ConfluenceService {

    /** Максимальная длина текста, возвращаемого агентом. */
    private static final int MAX_TEXT_LENGTH = 4000;

    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String baseUrl;

    /**
     * Конструктор.
     *
     * @param client  HTTP-клиент для выполнения запросов к Confluence
     * @param baseUrl базовый URL Confluence (например, https://company.atlassian.net/wiki)
     */
    public ConfluenceService(HttpClient client, String baseUrl) {
        this.mapper = new ObjectMapper();
        this.client = client;
        this.baseUrl = normalizeUrl(baseUrl);
    }

    /**
     * Получает страницу по id.
     *
     * @param pageId идентификатор страницы
     * @return строка с заголовком и содержимым страницы
     * @throws IOException если запрос не удался
     */
    public String getPage(String pageId) throws IOException {
        String url = baseUrl + "/rest/api/content/" + pageId + "?expand=body.storage,version";
        JsonNode json = parseResponse(client.get(url));

        String title = json.has("title") ? json.get("title").asText() : "";
        String content = "";
        if (json.has("body") && json.get("body").has("storage")) {
            JsonNode storage = json.get("body").get("storage");
            if (storage.has("value")) {
                content = storage.get("value").asText();
            }
        }
        if (content.length() > MAX_TEXT_LENGTH) {
            content = content.substring(0, MAX_TEXT_LENGTH) + "...";
        }

        return "Title: " + title + "\nContent: " + content;
    }

    /**
     * Создание новой страницы.
     *
     * @param spaceKey ключ пространства
     * @param title    заголовок
     * @param content  содержимое (HTML)
     * @param parentId идентификатор родительской страницы (опционально)
     * @return строка с подтверждением операции
     * @throws IOException если запрос не удался
     */
    public String createPage(String spaceKey, String title, String content,
                             String parentId) throws IOException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "page");
        payload.put("title", title);
        payload.putObject("space").put("key", spaceKey);

        ObjectNode storage = payload.putObject("body").putObject("storage");
        storage.put("value", content);
        storage.put("representation", "storage");

        ArrayNode ancestors = mapper.createArrayNode();
        if (parentId != null && !parentId.isBlank()) {
            ancestors.add(mapper.createObjectNode().put("id", parentId));
        }
        payload.set("ancestors", ancestors);

        String url = baseUrl + "/rest/api/content";
        JsonNode json = parseResponse(client.post(url, mapper.writeValueAsString(payload)));

        String pageId = json.has("id") ? json.get("id").asText() : "";
        return "Страница создана: ID " + pageId + " - " + title + " (пространство " + spaceKey + ")";
    }

    /**
     * Обновление страницы.
     *
     * @param pageId  идентификатор страницы
     * @param title   новый заголовок (опционально)
     * @param content новое содержимое (опционально)
     * @param version текущая версия страницы (опционально, определяется автоматически)
     * @return строка с подтверждением операции
     * @throws IOException если запрос не удался
     */
    public String updatePage(String pageId, String title, String content,
                             Integer version) throws IOException {
        String getUrl = baseUrl + "/rest/api/content/" + pageId + "?expand=body.storage,version";
        JsonNode currentPage = parseResponse(client.get(getUrl));

        int newVersion;
        if (currentPage.has("version") && currentPage.get("version").has("number")) {
            newVersion = currentPage.get("version").get("number").asInt() + 1;
        } else if (version != null) {
            newVersion = version + 1;
        } else {
            newVersion = 1;
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("type", "page");
        if (title != null) {
            payload.put("title", title);
        }
        if (content != null) {
            ObjectNode storage = payload.putObject("body").putObject("storage");
            storage.put("value", content);
            storage.put("representation", "storage");
        }
        payload.putObject("version").put("number", newVersion);

        String putUrl = baseUrl + "/rest/api/content/" + pageId;
        client.put(putUrl, mapper.writeValueAsString(payload));

        return "Страница обновлена: ID " + pageId + ", новая версия: " + newVersion;
    }

    /**
     * Получение информации о пространстве по ключу.
     *
     * @param spaceKey ключ пространства
     * @return строка с информацией о пространстве
     * @throws IOException если запрос не удался
     */
    public String getSpace(String spaceKey) throws IOException {
        String url = baseUrl + "/rest/api/space/" + spaceKey;
        JsonNode json = parseResponse(client.get(url));

        String key = json.has("key") ? json.get("key").asText() : spaceKey;
        String name = json.has("name") ? json.get("name").asText() : "";
        String type = json.has("type") ? json.get("type").asText() : "N/A";
        String description = json.has("description") && json.get("description").has("value")
                ? json.get("description").get("value").asText() : "N/A";

        return "Пространство: " + key + " - " + name
                + "\nТип: " + type
                + "\nОписание: " + description;
    }

    /**
     * Получение списка пространств.
     *
     * @param limit максимальное количество пространств
     * @return строка со списком пространств
     * @throws IOException если запрос не удался
     */
    public String listSpaces(int limit) throws IOException {
        String url = baseUrl + "/rest/api/space?limit=" + limit;
        JsonNode json = parseResponse(client.get(url));
        ArrayNode spaces = json.has("results") && json.get("results").isArray()
                ? (ArrayNode) json.get("results")
                : mapper.createArrayNode();

        StringBuilder result = new StringBuilder("Пространства Confluence (лимит: " + limit + "):\n");
        for (JsonNode space : spaces) {
            result.append("- ").append(space.get("key").asText())
                    .append(": ").append(space.get("name").asText())
                    .append("\n");
        }
        return result.toString();
    }

    /**
     * Поиск страниц по CQL.
     *
     * @param cql   поисковый запрос CQL
     * @param limit максимальное количество результатов
     * @return строка со списком найденных страниц
     * @throws IOException если запрос не удался
     */
    public String search(String cql, int limit) throws IOException {
        String url = baseUrl + "/rest/api/content?cql="
                + URLEncoder.encode(cql, StandardCharsets.UTF_8)
                + "&limit=" + limit;
        JsonNode json = parseResponse(client.get(url));
        ArrayNode results = json.has("results") && json.get("results").isArray()
                ? (ArrayNode) json.get("results")
                : mapper.createArrayNode();

        StringBuilder output = new StringBuilder(
                "Результаты поиска CQL: " + cql + " (лимит: " + limit + "):\n");
        for (JsonNode result : results) {
            String title = result.has("title") ? result.get("title").asText() : "";
            String spaceKey = result.has("space") && result.get("space").has("key")
                    ? result.get("space").get("key").asText() : "N/A";
            String contentId = result.has("id") ? result.get("id").asText() : "";
            output.append("- ").append(spaceKey).append(":").append(contentId)
                    .append(" - ").append(title).append("\n");
        }
        return output.toString();
    }

    // ==================== Private helpers ====================

    private JsonNode parseResponse(String response) throws IOException {
        try {
            return mapper.readTree(response);
        } catch (IOException e) {
            throw new IOException("Не удалось разобрать ответ Confluence: " + e.getMessage(), e);
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
}
