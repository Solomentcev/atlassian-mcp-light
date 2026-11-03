package ru.solomentsev.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Обрабатывает JSON-RPC 2.0 запросы протокола MCP.
 *
 * <p>Поддерживает методы initialize, tools/list, tools/call и ping,
 * а также проверяет структуру запросов и аргументы инструментов.</p>
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
     * Обрабатывает JSON-RPC запрос или уведомление.
     *
     * @param request объект запроса
     * @return ответ JSON-RPC либо {@code null} для корректного уведомления
     * @throws IOException если обработчик инструмента завершился ошибкой ввода-вывода
     */
    public JsonNode processRequest(ObjectNode request) throws IOException {
        if (request == null) {
            return errorResponse(NullNode.getInstance(), -32600, "Invalid Request");
        }

        JsonNode id = request.get("id");
        JsonNode version = request.get("jsonrpc");
        JsonNode methodNode = request.get("method");
        JsonNode params = request.has("params") ? request.get("params") : mapper.createObjectNode();

        if (version == null || !version.isTextual() || !"2.0".equals(version.asText())
                || methodNode == null || !methodNode.isTextual() || methodNode.asText().isBlank()
                || params == null || !params.isObject()
                || (request.has("id") && !isValidId(id))) {
            return errorResponse(isValidId(id) ? id : NullNode.getInstance(),
                    -32600, "Invalid Request");
        }

        // Только отсутствие id означает уведомление. id:null не является уведомлением.
        if (!request.has("id")) {
            log.debug("Уведомление получено: method={}", methodNode.asText());
            return null;
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

    private static boolean isValidId(JsonNode id) {
        return id != null && (id.isTextual() || id.isNumber());
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
        try {
            rejectUnknownFields(params, List.of("name", "arguments"), "Параметры tools/call");
            JsonNode nameNode = params.get("name");
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
                throw new IllegalArgumentException("Параметр 'name' должен быть непустой строкой");
            }

            String toolName = nameNode.asText();
            JsonNode arguments = params.has("arguments")
                    ? params.get("arguments") : mapper.createObjectNode();
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
            log.error("Ошибка вызова инструмента", e);
            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = mapper.createArrayNode();
            ObjectNode contentItem = mapper.createObjectNode();
            contentItem.put("type", "text");
            contentItem.put("text", "Ошибка: " + safeMessage(e));
            content.add(contentItem);
            result.set("content", content);
            result.put("isError", true);
            return successResponse(id, result);
        }
    }

    private String dispatch(String toolName, JsonNode arguments) throws IOException {
        for (ToolDefinition tool : tools) {
            if (tool.getName().equals(toolName)) {
                validateArguments(tool, arguments);
                return tool.getHandler().handle(arguments);
            }
        }
        throw new IllegalArgumentException("Неизвестный инструмент: " + toolName);
    }

    private static void validateArguments(ToolDefinition tool, JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("Аргументы инструмента должны быть JSON-объектом");
        }

        ObjectNode schema = tool.getInputSchema();
        JsonNode properties = schema.path("properties");
        JsonNode required = schema.path("required");

        if (required.isArray()) {
            for (JsonNode requiredName : required) {
                String name = requiredName.asText();
                if (!arguments.has(name) || arguments.get(name).isNull()) {
                    throw new IllegalArgumentException("Обязательный параметр не задан: " + name);
                }
            }
        }

        Iterator<String> names = arguments.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode value = arguments.get(name);
            JsonNode definition = properties.get(name);
            if (definition == null) {
                throw new IllegalArgumentException("Неизвестный параметр: " + name);
            }
            if (value == null || value.isNull()) {
                throw new IllegalArgumentException("Параметр не может быть null: " + name);
            }

            String type = definition.path("type").asText();
            if ("string".equals(type)) {
                if (!value.isTextual() || value.asText().isBlank()) {
                    throw new IllegalArgumentException(
                            "Параметр '" + name + "' должен быть непустой строкой");
                }
            } else if ("integer".equals(type)) {
                if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                    throw new IllegalArgumentException(
                            "Параметр '" + name + "' должен быть целым числом");
                }
                int number = value.intValue();
                JsonNode minimum = definition.get("minimum");
                JsonNode maximum = definition.get("maximum");
                if (minimum != null && number < minimum.intValue()) {
                    throw new IllegalArgumentException("Параметр '" + name
                            + "' должен быть не меньше " + minimum.intValue());
                }
                if (maximum != null && number > maximum.intValue()) {
                    throw new IllegalArgumentException("Параметр '" + name
                            + "' должен быть не больше " + maximum.intValue());
                }
            } else {
                throw new IllegalStateException("Неизвестный тип в схеме инструмента: " + type);
            }
        }
    }

    private static void rejectUnknownFields(JsonNode object, List<String> allowed, String context) {
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(context + ": неизвестное поле '" + name + "'");
            }
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message;
    }

    /**
     * Возвращает имена аргументов вызова инструмента без логирования значений.
     *
     * @param arguments аргументы вызова
     * @return список имён аргументов
     */
    private static List<String> argumentNames(JsonNode arguments) {
        List<String> names = new ArrayList<>();
        if (arguments != null && arguments.isObject()) {
            Iterator<String> fields = arguments.fieldNames();
            while (fields.hasNext()) {
                names.add(fields.next());
            }
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
     * @param tool описание и обработчик инструмента
     * @throws NullPointerException если инструмент не задан
     * @throws IllegalArgumentException если инструмент с таким именем уже зарегистрирован
     */
    public void addTool(ToolDefinition tool) {
        Objects.requireNonNull(tool, "tool");
        if (tools.stream().anyMatch(existing -> existing.getName().equals(tool.getName()))) {
            throw new IllegalArgumentException("Инструмент уже зарегистрирован: " + tool.getName());
        }
        tools.add(tool);
    }

    /**
     * Неизменяемое описание зарегистрированного MCP-инструмента.
     */
    public static class ToolDefinition {
        private final String name;
        private final String description;
        private final ObjectNode inputSchema;
        private final ToolHandler handler;

        /**
         * Создаёт описание инструмента.
         *
         * @param name имя инструмента
         * @param description описание инструмента
         * @param inputSchema JSON Schema аргументов
         * @param handler обработчик вызова
         */
        public ToolDefinition(String name, String description,
                              ObjectNode inputSchema, ToolHandler handler) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
            this.handler = Objects.requireNonNull(handler, "handler");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Имя инструмента не должно быть пустым");
            }
        }

        /** @return имя инструмента */
        public String getName() { return name; }

        /** @return описание инструмента */
        public String getDescription() { return description; }

        /** @return копия JSON Schema аргументов */
        public ObjectNode getInputSchema() { return inputSchema.deepCopy(); }

        /** @return обработчик вызова */
        public ToolHandler getHandler() { return handler; }

        /** Обработчик вызова инструмента. */
        public interface ToolHandler {
            /**
             * Выполняет вызов инструмента.
             *
             * @param arguments проверенные аргументы
             * @return текстовый результат
             * @throws IOException если произошла ошибка ввода-вывода
             */
            String handle(JsonNode arguments) throws IOException;
        }
    }
}
