package ru.solomentsev.confluence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.solomentsev.core.client.HttpClient;

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

    /** Максимальная длина текстового поля по умолчанию. */
    private static final int DEFAULT_MAX_TEXT_LENGTH = 4000;

    /** Максимальная длина текстового поля в ответах Confluence. */
    private final int maxTextLength;

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
        this(client, baseUrl, DEFAULT_MAX_TEXT_LENGTH);
    }

    /**
     * Создает сервис Confluence с заданным ограничением длины возвращаемых текстовых полей.
     *
     * @param client HTTP-клиент Confluence
     * @param baseUrl базовый URL Confluence
     * @param maxTextLength максимальная длина текстового поля до добавления маркера сокращения
     * @throws IllegalArgumentException если лимит не положительный
     */
    public ConfluenceService(HttpClient client, String baseUrl, int maxTextLength) {
        if (maxTextLength <= 0) {
            throw new IllegalArgumentException("maxTextLength должен быть больше нуля");
        }
        this.mapper = new ObjectMapper();
        this.client = client;
        this.baseUrl = normalizeUrl(baseUrl);
        this.maxTextLength = maxTextLength;
    }

    /**
     * Получает страницу по id.
     *
     * @param pageId идентификатор страницы
     * @return строка с заголовком и содержимым страницы
     * @throws IOException если запрос не удался
     */
    public String getPage(String pageId) throws IOException {
        String url = baseUrl + "/rest/api/content/" + encodePathSegment(pageId) + "?expand=body.storage,version";
        JsonNode json = parseResponse(client.get(url));

        String title = json.has("title") ? truncate(json.get("title").asText()) : "";
        String content = "";
        if (json.has("body") && json.get("body").has("storage")) {
            JsonNode storage = json.get("body").get("storage");
            if (storage.has("value")) {
                content = storage.get("value").asText();
            }
        }
        content = truncate(content);

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
        String getUrl = baseUrl + "/rest/api/content/" + encodePathSegment(pageId) + "?expand=body.storage,version";
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

        String putUrl = baseUrl + "/rest/api/content/" + encodePathSegment(pageId);
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
        String url = baseUrl + "/rest/api/space/" + encodePathSegment(spaceKey);
        JsonNode json = parseResponse(client.get(url));

        String key = json.has("key") ? truncate(json.get("key").asText()) : truncate(spaceKey);
        String name = json.has("name") ? truncate(json.get("name").asText()) : "";
        String type = json.has("type") ? truncate(json.get("type").asText()) : "N/A";
        String description = json.has("description") && json.get("description").has("value")
                ? truncate(json.get("description").get("value").asText()) : "N/A";

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
     * @throws IllegalArgumentException если limit не входит в диапазон 1..100
     */
    public String listSpaces(int limit) throws IOException {
        validateSearchLimit(limit);
        String url = baseUrl + "/rest/api/space?limit=" + limit;
        ArrayNode spaces = fetchPaginatedResults(url, limit);

        StringBuilder result = new StringBuilder("Пространства Confluence (лимит: " + limit + "):\n");
        for (JsonNode space : spaces) {
            result.append("- ").append(truncate(space.get("key").asText()))
                    .append(": ").append(truncate(space.get("name").asText()))
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
     * @throws IllegalArgumentException если limit не входит в диапазон 1..100
     */
    public String search(String cql, int limit) throws IOException {
        validateSearchLimit(limit);
        String url = baseUrl + "/rest/api/content?cql="
                + URLEncoder.encode(cql, StandardCharsets.UTF_8)
                + "&limit=" + limit;
        ArrayNode results = fetchPaginatedResults(url, limit);

        StringBuilder output = new StringBuilder(
                "Результаты поиска CQL: " + truncate(cql) + " (лимит: " + limit + "):\n");
        for (JsonNode result : results) {
            String title = result.has("title") ? truncate(result.get("title").asText()) : "";
            String spaceKey = result.has("space") && result.get("space").has("key")
                    ? truncate(result.get("space").get("key").asText()) : "N/A";
            String contentId = result.has("id") ? result.get("id").asText() : "";
            output.append("- ").append(spaceKey).append(":").append(contentId)
                    .append(" - ").append(title).append("\n");
        }
        return output.toString();
    }

    /**
     * Загружает результаты Confluence по страницам, не превышая заданный лимит.
     *
     * @param initialUrl URL первой страницы с параметром limit
     * @param maximumResults максимальное количество результатов
     * @return объединённые результаты всех полученных страниц
     * @throws IOException если запрос или разбор ответа завершился ошибкой
     */
    private ArrayNode fetchPaginatedResults(String initialUrl, int maximumResults) throws IOException {
        ArrayNode combined = mapper.createArrayNode();
        int start = 0;
        String url = initialUrl;

        // Каждая следующая страница должна добавлять хотя бы один результат,
        // поэтому число запросов не может превысить maximumResults.
        while (combined.size() < maximumResults) {
            JsonNode page = parseResponse(client.get(url));
            JsonNode pageResults = page.path("results");
            if (!pageResults.isArray() || pageResults.isEmpty()) {
                break;
            }

            for (JsonNode result : pageResults) {
                if (combined.size() >= maximumResults) {
                    break;
                }
                combined.add(result);
            }

            start += pageResults.size();
            JsonNode next = page.path("_links").path("next");
            if (combined.size() >= maximumResults || next.isMissingNode()
                    || !next.isTextual() || next.asText().isBlank()) {
                break;
            }

            // Offset формируется локально: URL из ответа сервера не используется.
            url = initialUrl + "&start=" + start;
        }

        return combined;
    }

    /**
     * Кодирует значение как отдельный сегмент пути URL.
     *
     * @param value исходное значение
     * @return URL-кодированный сегмент пути
     */
    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Проверяет допустимый размер страницы поиска Confluence.
     *
     * @param limit количество результатов
     * @throws IllegalArgumentException если значение не входит в диапазон 1..100
     */
    private static void validateSearchLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit должен быть в диапазоне от 1 до 100");
        }
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
