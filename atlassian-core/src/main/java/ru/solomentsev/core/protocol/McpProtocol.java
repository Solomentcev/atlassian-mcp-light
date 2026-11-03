package ru.solomentsev.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Обработка JSON-RPC 2.0 протокола MCP.
 *
 * <p>Отвечает за разбор запросов и формирование ответов:
 * initialize, tools/list, tools/call.</p>
 */
public class McpProtocol {

    /** Логгер протокола. */
    private static final Logger log = LoggerFactory.getLogger(McpProtocol.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Версия протокола. */
    static final String PROTOCOL_VERSION = "2024-11-05";

    /** Имя сервера. */
    static final String SERVER_NAME = "atlassian-lite";

    /** Версия сервера. */
    static final String SERVER_VERSION = "1.0.0";

    private final List<ToolDefinition> tools = new ArrayList<>();

    /**
     * Обрабатывает JSON-RPC запрос.
     *
     * <p>Возвращает {@code null}, если запрос является уведомлением: по JSON-RPC 2.0
     * запрос без идентификатора {@code id} не требует ответа. Это критично для
     * совместимости с MCP-клиентами, которые после {@code initialize} отправляют
     * уведомление {@code notifications/initialized}.</p>
     *
     * @param request объект запроса
     * @return объект ответа или {@code null}, если запрос является уведомлением
     * @throws IOException если обработка упала
     */
    public JsonNode processRequest(ObjectNode request) throws IOException {
        JsonNode methodNode = request.get("method");
        JsonNode id = request.has("id") ? request.get("id") : null;
        JsonNode params = request.has("params") ? request.get("params") : mapper.createObjectNode();

        // JSON-RPC 2.0: уведомление (запрос без идентификатора) не требует ответа
        if (id == null || id.isNull()) {
            log.debug("Уведомление получено: method={}", methodNode != null ? methodNode.asText() : "null");
            return null;
        }

        if (methodNode == null || methodNode.isNull()) {
            log.debug("Запрос без метода получен, возвращаю Invalid Request (-32600)");
            return errorResponse(id, -32600, "Invalid Request: missing 'method'");
        }
        String method = methodNode.asText();

        log.info("Запрос: method={}, id={}", method, id);

        switch (method) {
            case "initialize":
                return handleInitialize(id);
            case "tools/list":
                return handleToolsList(id);
            case "tools/call":
                return handleToolCall(id, params);
            case "ping":
                return successResponse(id, mapper.createObjectNode());
            default:
                return errorResponse(id, -32601, "Неизвестный метод: " + method);
        }
    }

    private JsonNode handleInitialize(JsonNode id) {
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.set("tools", mapper.createObjectNode());
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.set("serverInfo", serverInfo);
        return successResponse(id, result);
    }

    private JsonNode handleToolsList(JsonNode id) {
        ArrayNode toolsArray = mapper.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode toolNode = mapper.createObjectNode();
            toolNode.put("name", tool.getName());
            toolNode.put("description", tool.getDescription());
            toolNode.set("inputSchema", tool.getInputSchema());
            toolsArray.add(toolNode);
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("tools", toolsArray);
        return successResponse(id, result);
    }

    private JsonNode handleToolCall(JsonNode id, JsonNode params) {
        JsonNode nameNode = params.get("name");
        if (nameNode == null || nameNode.isNull()) {
            log.error("Вызов инструмента без имени");
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = mapper.createArrayNode();
            ObjectNode contentItem = mapper.createObjectNode();
            contentItem.put("type", "text");
            contentItem.put("text", "Ошибка: обязательный параметр 'name' не задан");
            content.add(contentItem);
            result.set("content", content);
            result.put("isError", true);
            return successResponse(id, result);
        }
        String toolName = nameNode.asText();
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : mapper.createObjectNode();

        log.debug("Вызов инструмента: {} (аргументы: {})", toolName, argumentNames(arguments));

        try {
            String output = dispatch(toolName, arguments);

            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = mapper.createArrayNode();
            ObjectNode contentItem = mapper.createObjectNode();
            contentItem.put("type", "text");
            contentItem.put("text", output);
            content.add(contentItem);
            result.set("content", content);
            return successResponse(id, result);
        } catch (Exception e) {
            log.error("Ошибка вызова инструмента: {}", toolName, e);
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = mapper.createArrayNode();
            ObjectNode contentItem = mapper.createObjectNode();
            contentItem.put("type", "text");
            contentItem.put("text", "Ошибка: " + e.getMessage());
            content.add(contentItem);
            result.set("content", content);
            result.put("isError", true);
            return successResponse(id, result);
        }
    }

    private String dispatch(String toolName, JsonNode arguments) throws IOException {
        for (ToolDefinition tool : tools) {
            if (tool.getName().equals(toolName)) {
                return tool.getHandler().handle(arguments);
            }
        }
        throw new IllegalArgumentException("Неизвестный инструмент: " + toolName);
    }

    /**
     * Возвращает имена аргументов вызова инструмента.
     *
     * <p>Значения аргументов не логируются: они могут содержать содержимое
     * задач, комментарии и текст страниц.</p>
     *
     * @param arguments аргументы вызова
     * @return список имен аргументов
     */
    private static List<String> argumentNames(JsonNode arguments) {
        List<String> names = new ArrayList<>();
        if (arguments == null) {
            return names;
        }
        Iterator<String> fields = arguments.fieldNames();
        while (fields.hasNext()) {
            names.add(fields.next());
        }
        return names;
    }

    private JsonNode successResponse(JsonNode id, JsonNode result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("result", result);
        return response;
    }

    private JsonNode errorResponse(JsonNode id, int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return response;
    }

    /**
     * Регистрирует инструмент.
     *
     * @param tool инструмент
     */
    public void addTool(ToolDefinition tool) {
        tools.add(tool);
    }

    /**
     * Определение инструмента.
     */
    public static class ToolDefinition {
        private final String name;
        private final String description;
        private final ObjectNode inputSchema;
        private final ToolHandler handler;

        public ToolDefinition(String name, String description,
                             ObjectNode inputSchema, ToolHandler handler) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.handler = handler;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public ObjectNode getInputSchema() { return inputSchema; }
        public ToolHandler getHandler() { return handler; }

        public interface ToolHandler {
            String handle(JsonNode arguments) throws IOException;
        }
    }
}
