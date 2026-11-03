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
 * <p>Поддерживает два поколения MCP: handshake-жизненный цикл до версии
 * {@code 2025-11-25} и stateless-жизненный цикл {@code 2026-07-28}.
 * В обоих режимах доступны tools/list и tools/call; старые клиенты используют
 * initialize и notifications/initialized, современные передают версию и
 * возможности клиента в каждом запросе.</p>
 */
public class McpProtocol {

    /** Логгер протокола. */
    private static final Logger log = LoggerFactory.getLogger(McpProtocol.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Последняя поддерживаемая версия MCP с handshake. */
    static final String PROTOCOL_VERSION = "2025-11-25";

    /** Современная stateless-версия MCP. */
    static final String MODERN_PROTOCOL_VERSION = "2026-07-28";

    /** Поддерживаемые версии MCP с handshake. */
    private static final List<String> LEGACY_PROTOCOL_VERSIONS =
            List.of("2024-11-05", PROTOCOL_VERSION);

    /** Ключ версии протокола в per-request metadata. */
    private static final String META_PROTOCOL_VERSION =
            "io.modelcontextprotocol/protocolVersion";

    /** Ключ возможностей клиента в per-request metadata. */
    private static final String META_CLIENT_CAPABILITIES =
            "io.modelcontextprotocol/clientCapabilities";

    /** Ключ сведений о клиенте в per-request metadata. */
    private static final String META_CLIENT_INFO =
            "io.modelcontextprotocol/clientInfo";

    /** Ключ сведений о сервере в metadata ответа. */
    private static final String META_SERVER_INFO =
            "io.modelcontextprotocol/serverInfo";

    /** Имя сервера. */
    static final String SERVER_NAME = "atlassian-lite";

    /** Версия сервера. */
    static final String SERVER_VERSION = "1.0.0";

    private final List<ToolDefinition> tools = new ArrayList<>();

    /** Текущее состояние жизненного цикла соединения MCP. */
    private LifecycleState lifecycleState = LifecycleState.NEW;

    private enum LifecycleState {
        NEW,
        INITIALIZATION_PENDING,
        READY
    }

    /**
     * Обрабатывает JSON-RPC запрос или уведомление.
     *
     * @param request объект запроса
     * @return ответ JSON-RPC либо {@code null} для корректного уведомления
     */
    public JsonNode processRequest(ObjectNode request) {
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

        String method = methodNode.asText();
        JsonNode metadata = params.get("_meta");
        boolean modernRequest = "server/discover".equals(method)
                || (metadata != null && metadata.isObject()
                && (metadata.has(META_PROTOCOL_VERSION)
                || metadata.has(META_CLIENT_CAPABILITIES)
                || metadata.has(META_CLIENT_INFO)));

        // Уведомления никогда не получают ответ. В stateless-режиме они также
        // не меняют состояние процесса или последующих запросов.
        if (!request.has("id")) {
            if (!modernRequest && "notifications/initialized".equals(method)
                    && lifecycleState == LifecycleState.INITIALIZATION_PENDING) {
                lifecycleState = LifecycleState.READY;
                log.info("MCP-соединение перешло в рабочее состояние");
            }
            log.debug("Уведомление получено: method={}", method);
            return null;
        }

        log.info("Запрос: method={}, id={}", method, id);
        if (modernRequest) {
            JsonNode metadataError = validateModernMetadata(id, params);
            if (metadataError != null) {
                return metadataError;
            }
            return handleModernRequest(id, method, params);
        }

        switch (method) {
            case "initialize":
                return handleInitialize(id, params);
            case "tools/list":
                return lifecycleState == LifecycleState.READY
                        ? handleToolsList(id) : serverNotInitialized(id);
            case "tools/call":
                return lifecycleState == LifecycleState.READY
                        ? handleToolCall(id, params) : serverNotInitialized(id);
            case "ping":
                return successResponse(id, mapper.createObjectNode());
            default:
                return errorResponse(id, -32601, "Неизвестный метод: " + method);
        }
    }

    private static boolean isValidId(JsonNode id) {
        return id != null && (id.isTextual() || id.isNumber());
    }

    private JsonNode handleInitialize(JsonNode id, JsonNode params) {
        if (lifecycleState != LifecycleState.NEW) {
            return errorResponse(id, -32600, "Invalid Request: initialize has already been processed");
        }

        JsonNode requestedVersion = params.get("protocolVersion");
        JsonNode clientCapabilities = params.get("capabilities");
        JsonNode clientInfo = params.get("clientInfo");

        if (requestedVersion == null || !requestedVersion.isTextual()
                || requestedVersion.asText().isBlank()
                || clientCapabilities == null || !clientCapabilities.isObject()
                || !isValidImplementation(clientInfo)) {
            return errorResponse(id, -32602,
                    "Invalid params: initialize requires protocolVersion, capabilities and clientInfo");
        }

        // Версии handshake выбираются явно; неизвестная версия получает
        // встречное предложение последней поддерживаемой handshake-версии.
        String selectedVersion = LEGACY_PROTOCOL_VERSIONS.contains(requestedVersion.asText())
                ? requestedVersion.asText() : PROTOCOL_VERSION;
        ObjectNode result = mapper.createObjectNode();
        result.put("protocolVersion", selectedVersion);
        ObjectNode serverCapabilities = mapper.createObjectNode();
        serverCapabilities.set("tools", mapper.createObjectNode());
        result.set("capabilities", serverCapabilities);
        ObjectNode serverInfo = mapper.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.set("serverInfo", serverInfo);
        lifecycleState = LifecycleState.INITIALIZATION_PENDING;
        return successResponse(id, result);
    }

    /**
     * Проверяет per-request metadata современного протокола.
     *
     * @param id идентификатор запроса
     * @param params параметры запроса
     * @return JSON-RPC ошибка или null, если metadata корректна
     */
    private JsonNode validateModernMetadata(JsonNode id, JsonNode params) {
        JsonNode metadata = params.get("_meta");
        if (metadata == null || !metadata.isObject()) {
            return errorResponse(id, -32602, "Invalid params: modern MCP request requires _meta");
        }

        JsonNode version = metadata.get(META_PROTOCOL_VERSION);
        JsonNode capabilities = metadata.get(META_CLIENT_CAPABILITIES);
        if (version == null || !version.isTextual() || version.asText().isBlank()
                || capabilities == null || !capabilities.isObject()) {
            return errorResponse(id, -32602,
                    "Invalid params: _meta requires protocolVersion and clientCapabilities");
        }
        if (!MODERN_PROTOCOL_VERSION.equals(version.asText())) {
            ObjectNode data = mapper.createObjectNode();
            ArrayNode supported = data.putArray("supportedVersions");
            supported.add(MODERN_PROTOCOL_VERSION);
            return errorResponse(id, -32022, "Unsupported protocol version", data);
        }

        JsonNode clientInfo = metadata.get(META_CLIENT_INFO);
        if (clientInfo != null && !isValidImplementation(clientInfo)) {
            return errorResponse(id, -32602, "Invalid params: clientInfo must include name and version");
        }
        return null;
    }

    /**
     * Обрабатывает запросы stateless-версии MCP без использования состояния соединения.
     *
     * @param id идентификатор запроса
     * @param method имя метода
     * @param params параметры запроса
     * @return JSON-RPC ответ
     */
    private JsonNode handleModernRequest(JsonNode id, String method, JsonNode params) {
        try {
            switch (method) {
                case "server/discover":
                    rejectUnknownFields(params, List.of("_meta"), "Параметры server/discover");
                    return successResponse(id, discoverResult(), true);
                case "tools/list":
                    rejectUnknownFields(params, List.of("_meta"), "Параметры tools/list");
                    return handleToolsList(id, true);
                case "tools/call":
                    return handleToolCall(id, params, true);
                default:
                    return errorResponse(id, -32601, "Неизвестный метод: " + method);
            }
        } catch (IllegalArgumentException e) {
            return errorResponse(id, -32602, "Invalid params: " + safeMessage(e));
        }
    }

    /**
     * Формирует результат обнаружения сервера для современной версии MCP.
     *
     * @return описание поддерживаемых версий и возможностей сервера
     */
    private ObjectNode discoverResult() {
        ObjectNode result = mapper.createObjectNode();
        result.put("resultType", "complete");
        ArrayNode versions = result.putArray("supportedVersions");
        versions.add(MODERN_PROTOCOL_VERSION);
        versions.add(PROTOCOL_VERSION);
        versions.add("2024-11-05");

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.set("tools", mapper.createObjectNode());
        result.set("_meta", serverInfoMetadata());
        result.put("ttlMs", 300_000);
        result.put("cacheScope", "public");
        return result;
    }

    /**
     * Создаёт metadata ответа с идентификатором сервера.
     *
     * @return объект metadata с serverInfo
     */
    private ObjectNode serverInfoMetadata() {
        ObjectNode metadata = mapper.createObjectNode();
        ObjectNode serverInfo = metadata.putObject(META_SERVER_INFO);
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        return metadata;
    }

    private JsonNode serverNotInitialized(JsonNode id) {
        return errorResponse(id, -32001, "Server not initialized");
    }

    private static boolean isValidImplementation(JsonNode implementation) {
        if (implementation == null || !implementation.isObject()) {
            return false;
        }
        JsonNode name = implementation.get("name");
        JsonNode version = implementation.get("version");
        return name != null && name.isTextual() && !name.asText().isBlank()
                && version != null && version.isTextual() && !version.asText().isBlank();
    }

    private JsonNode handleToolsList(JsonNode id) {
        return handleToolsList(id, false);
    }

    private JsonNode handleToolsList(JsonNode id, boolean modern) {
        ArrayNode toolsArray = mapper.createArrayNode();
        for (ToolDefinition tool : tools) {
            ObjectNode toolNode = mapper.createObjectNode();
            toolNode.put("name", tool.getName());
            toolNode.put("description", tool.getDescription());
            toolNode.set("inputSchema", tool.getInputSchema());
            if (!tool.getAnnotations().isEmpty()) {
                toolNode.set("annotations", tool.getAnnotations());
            }
            toolsArray.add(toolNode);
        }

        ObjectNode result = mapper.createObjectNode();
        result.set("tools", toolsArray);
        return successResponse(id, result, modern);
    }

    private JsonNode handleToolCall(JsonNode id, JsonNode params) {
        return handleToolCall(id, params, false);
    }

    private JsonNode handleToolCall(JsonNode id, JsonNode params, boolean modern) {
        try {
            rejectUnknownFields(params, List.of("name", "arguments", "_meta"), "Параметры tools/call");
            JsonNode nameNode = params.get("name");
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText().isBlank()) {
                throw new IllegalArgumentException("Параметр 'name' должен быть непустой строкой");
            }

            String toolName = nameNode.asText();
            JsonNode arguments = params.has("arguments")
                    ? params.get("arguments") : mapper.createObjectNode();
            log.debug("Вызов инструмента: {} (аргументы: {})",
                    toolName, argumentNames(arguments));
            String output = dispatch(toolName, arguments);

            ObjectNode result = mapper.createObjectNode();
            ArrayNode content = mapper.createArrayNode();
            ObjectNode contentItem = mapper.createObjectNode();
            contentItem.put("type", "text");
            contentItem.put("text", output);
            content.add(contentItem);
            result.set("content", content);
            return successResponse(id, result, modern);
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
            return successResponse(id, result, modern);
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
        return successResponse(id, result, false);
    }

    private JsonNode successResponse(JsonNode id, JsonNode result, boolean modern) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        if (modern) {
            ObjectNode modernResult = result instanceof ObjectNode
                    ? (ObjectNode) result : mapper.createObjectNode();
            modernResult.put("resultType", "complete");
            modernResult.set("_meta", serverInfoMetadata());
            response.set("result", modernResult);
        } else {
            response.set("result", result);
        }
        return response;
    }

    private JsonNode errorResponse(JsonNode id, int code, String message) {
        return errorResponse(id, code, message, null);
    }

    private JsonNode errorResponse(JsonNode id, int code, String message, JsonNode data) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id);
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.set("data", data);
        }
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
        private final ObjectNode annotations;
        private final ToolHandler handler;

        /**
         * Создаёт описание инструмента без дополнительных аннотаций.
         *
         * @param name имя инструмента
         * @param description описание инструмента
         * @param inputSchema JSON Schema аргументов
         * @param handler обработчик вызова
         */
        public ToolDefinition(String name, String description,
                              ObjectNode inputSchema, ToolHandler handler) {
            this(name, description, inputSchema, mapper.createObjectNode(), handler);
        }

        /**
         * Создаёт описание инструмента с аннотациями MCP.
         *
         * @param name имя инструмента
         * @param description описание инструмента
         * @param inputSchema JSON Schema аргументов
         * @param annotations подсказки о свойствах инструмента
         * @param handler обработчик вызова
         */
        public ToolDefinition(String name, String description, ObjectNode inputSchema,
                              ObjectNode annotations, ToolHandler handler) {
            this.name = Objects.requireNonNull(name, "name");
            this.description = Objects.requireNonNull(description, "description");
            this.inputSchema = Objects.requireNonNull(inputSchema, "inputSchema").deepCopy();
            this.annotations = Objects.requireNonNull(annotations, "annotations").deepCopy();
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

        /** @return копия аннотаций MCP */
        public ObjectNode getAnnotations() { return annotations.deepCopy(); }

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
