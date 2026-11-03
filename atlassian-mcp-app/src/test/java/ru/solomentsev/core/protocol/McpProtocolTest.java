package ru.solomentsev.core.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Before;
import org.junit.Test;
import ru.solomentsev.core.client.FakeHttpClient;
import ru.solomentsev.app.McpToolInitializer;
import ru.solomentsev.confluence.service.ConfluenceService;
import ru.solomentsev.confluence.tools.ConfluenceToolProvider;
import ru.solomentsev.jira.service.JiraService;
import ru.solomentsev.jira.tools.JiraToolProvider;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Тесты {@link McpProtocol}: инициализация, список инструментов,
 * вызов инструментов и обработка ошибок.
 */
public class McpProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Порядок инструментов должен совпадать с исходной реализацией. */
    private static final String[] EXPECTED_TOOLS = {
            "jira_get_issue", "jira_search", "jira_create_issue", "jira_transition",
            "jira_add_comment", "jira_get_project", "jira_get_user",
            "confluence_get_page", "confluence_list_spaces", "confluence_get_space",
            "confluence_search", "confluence_create_page", "confluence_update_page"
    };

    private FakeHttpClient httpClient;
    private McpProtocol protocol;

    @Before
    public void setUp() {
        httpClient = new FakeHttpClient();
        JiraService jiraService = new JiraService(httpClient, "https://company.atlassian.net");
        ConfluenceService confluenceService =
                new ConfluenceService(httpClient, "https://company.atlassian.net/wiki");
        protocol = new McpProtocol();
        McpToolInitializer.initialize(protocol, java.util.List.of(
                new JiraToolProvider(jiraService),
                new ConfluenceToolProvider(confluenceService)));
        protocol.processRequest(initializeRequest("2024-11-05"));
        protocol.processRequest(initializedNotification());
    }

    private ObjectNode request(String method) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        return request;
    }

    private ObjectNode initializeRequest(String protocolVersion) {
        ObjectNode request = request("initialize");
        ObjectNode params = request.putObject("params");
        params.put("protocolVersion", protocolVersion);
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "test-client");
        clientInfo.put("version", "1.0.0");
        return request;
    }

    private ObjectNode initializedNotification() {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        return notification;
    }

    @Test
    public void initializeReturnsProtocolVersionAndServerInfo() throws IOException {
        JsonNode response = new McpProtocol().processRequest(initializeRequest("2024-11-05"));

        JsonNode result = response.get("result");
        assertNotNull(result);
        assertEquals("2.0", response.get("jsonrpc").asText());
        assertEquals("2024-11-05", result.get("protocolVersion").asText());
        assertEquals("atlassian-lite", result.get("serverInfo").get("name").asText());
        assertEquals("1.0.0", result.get("serverInfo").get("version").asText());
        assertNotNull(result.get("capabilities").get("tools"));
    }

    @Test
    public void initializeRejectsMissingRequiredParameters() {
        JsonNode response = new McpProtocol().processRequest(request("initialize"));

        assertNotNull(response.get("error"));
        assertEquals(-32602, response.get("error").get("code").asInt());
    }

    @Test
    public void initializeReturnsSupportedVersionWhenClientRequestsAnotherVersion() {
        JsonNode response = new McpProtocol().processRequest(initializeRequest("2025-03-26"));

        assertEquals("2025-11-25", response.get("result").get("protocolVersion").asText());
    }

    @Test
    public void toolsListIsRejectedBeforeInitialization() {
        JsonNode response = new McpProtocol().processRequest(request("tools/list"));

        assertEquals(-32001, response.get("error").get("code").asInt());
    }

    @Test
    public void toolsListIsRejectedUntilInitializedNotificationArrives() {
        McpProtocol unreadyProtocol = new McpProtocol();
        unreadyProtocol.processRequest(initializeRequest("2024-11-05"));

        JsonNode response = unreadyProtocol.processRequest(request("tools/list"));

        assertEquals(-32001, response.get("error").get("code").asInt());
    }

    @Test
    public void pingIsAllowedBeforeInitialization() {
        JsonNode response = new McpProtocol().processRequest(request("ping"));

        assertNotNull(response.get("result"));
        assertFalse(response.has("error"));
    }

    @Test
    public void initializedNotificationEnablesNormalRequests() {
        McpProtocol newProtocol = new McpProtocol();
        newProtocol.processRequest(initializeRequest("2024-11-05"));
        newProtocol.processRequest(initializedNotification());

        JsonNode response = newProtocol.processRequest(request("tools/list"));

        assertNotNull(response.get("result"));
        assertFalse(response.has("error"));
    }

    @Test
    public void initializedNotificationBeforeInitializeDoesNotEnableRequests() {
        McpProtocol newProtocol = new McpProtocol();
        newProtocol.processRequest(initializedNotification());

        JsonNode response = newProtocol.processRequest(request("tools/list"));

        assertEquals(-32001, response.get("error").get("code").asInt());
    }

    @Test
    public void secondInitializeRequestIsRejected() {
        McpProtocol newProtocol = new McpProtocol();
        newProtocol.processRequest(initializeRequest("2024-11-05"));

        JsonNode response = newProtocol.processRequest(initializeRequest("2024-11-05"));

        assertEquals(-32600, response.get("error").get("code").asInt());
    }

    @Test
    public void toolsListContainsAllThirteenTools() throws IOException {
        JsonNode tools = protocol.processRequest(request("tools/list")).get("result").get("tools");

        assertEquals(EXPECTED_TOOLS.length, tools.size());
        for (int i = 0; i < EXPECTED_TOOLS.length; i++) {
            JsonNode tool = tools.get(i);
            assertEquals(EXPECTED_TOOLS[i], tool.get("name").asText());
            assertFalse(tool.get("description").asText().isEmpty());

            JsonNode inputSchema = tool.get("inputSchema");
            assertNotNull(inputSchema);
            assertEquals("object", inputSchema.get("type").asText());
            assertFalse(inputSchema.get("additionalProperties").asBoolean());
            assertFalse(inputSchema.get("properties").isEmpty());
        }
    }

    @Test
    public void toolsListIncludesOperationAnnotations() throws IOException {
        JsonNode tools = protocol.processRequest(request("tools/list")).get("result").get("tools");

        JsonNode readAnnotations = tools.get(0).get("annotations");
        assertTrue(readAnnotations.get("readOnlyHint").asBoolean());
        assertFalse(readAnnotations.get("destructiveHint").asBoolean());
        assertTrue(readAnnotations.get("idempotentHint").asBoolean());
        assertTrue(readAnnotations.get("openWorldHint").asBoolean());

        JsonNode commentAnnotations = tools.get(4).get("annotations");
        assertFalse(commentAnnotations.get("readOnlyHint").asBoolean());
        assertFalse(commentAnnotations.get("idempotentHint").asBoolean());

        JsonNode updateAnnotations = tools.get(12).get("annotations");
        assertTrue(updateAnnotations.get("destructiveHint").asBoolean());
        assertTrue(updateAnnotations.get("openWorldHint").asBoolean());
    }

    @Test
    public void toolsListKeepsRequiredParameters() throws IOException {
        JsonNode tools = protocol.processRequest(request("tools/list")).get("result").get("tools");

        JsonNode required = tools.get(0).get("inputSchema").get("required");
        assertNotNull(required);
        assertEquals(1, required.size());
        assertEquals("issue_key", required.get(0).asText());
    }

    @Test
    public void optionalParametersAreNotMarkedAsRequired() throws IOException {
        JsonNode tools = protocol.processRequest(request("tools/list")).get("result").get("tools");

        JsonNode searchSchema = tools.get(1).get("inputSchema");
        assertEquals("string", searchSchema.get("properties").get("jql").get("type").asText());
        assertEquals("integer", searchSchema.get("properties").get("limit").get("type").asText());
        assertEquals(1, searchSchema.get("properties").get("limit").get("minimum").asInt());
        assertEquals(100, searchSchema.get("properties").get("limit").get("maximum").asInt());
        assertEquals(1, searchSchema.get("required").size());
        assertEquals("jql", searchSchema.get("required").get(0).asText());
    }

    @Test
    public void unknownMethodReturnsError() throws IOException {
        JsonNode response = protocol.processRequest(request("unknown/method"));

        assertNotNull(response.get("error"));
        assertEquals(-32601, response.get("error").get("code").asInt());
        assertTrue(response.get("error").get("message").asText().contains("Неизвестный метод"));
    }

    @Test
    public void unknownToolIsReportedAsToolError() throws IOException {
        ObjectNode request = request("tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "no_such_tool");
        params.putObject("arguments");

        JsonNode response = protocol.processRequest(request);

        JsonNode result = response.get("result");
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text")
                .asText().contains("Неизвестный инструмент"));
    }

    @Test
    public void missingRequiredParamIsReportedAsToolError() throws IOException {
        ObjectNode request = request("tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "jira_get_issue");
        params.putObject("arguments");

        JsonNode response = protocol.processRequest(request);

        JsonNode result = response.get("result");
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text")
                .asText().contains("issue_key"));
    }

    @Test
    public void toolCallReturnsResultWhenArgumentsAreValid() throws IOException {
        httpClient.onGet("https://company.atlassian.net/rest/api/2/issue/PROJ-1?fields=summary,description,status,issuetype",
                "{\"key\":\"PROJ-1\",\"fields\":{\"summary\":\"Task A\","
                        + "\"status\":{\"name\":\"In Progress\"},"
                        + "\"issuetype\":{\"name\":\"Story\"}}}}");

        ObjectNode request = request("tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "jira_get_issue");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("issue_key", "PROJ-1");

        JsonNode response = protocol.processRequest(request);

        assertEquals(1, response.get("id").asInt());
        assertNotNull(response.get("result"));

        JsonNode content = response.get("result").get("content").get(0);
        assertEquals("text", content.get("type").asText());
        JsonNode payload = MAPPER.readTree(content.get("text").asText());
        assertEquals("PROJ-1", payload.get("key").asText());
        assertEquals("Task A", payload.get("summary").asText());
    }

    @Test
    public void toolCallReturnsErrorWhenServiceFails() throws IOException {
        httpClient.failWith(new IOException("HTTP 500 Internal Server Error"));

        ObjectNode request = request("tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "confluence_search");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("cql", "space = 'TEST'");
        arguments.put("limit", 5);

        JsonNode response = protocol.processRequest(request);

        JsonNode result = response.get("result");
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text")
                .asText().contains("HTTP 500"));
    }

    @Test
    public void notificationDoesNotProduceResponse() throws IOException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", "notifications/initialized");

        assertNull("Уведомление не должно порождать ответ", protocol.processRequest(request));
    }

    @Test
    public void requestWithNullIdReturnsInvalidRequestError() throws IOException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.putNull("id");
        request.put("method", "notifications/cancelled");

        JsonNode response = protocol.processRequest(request);

        assertNotNull(response);
        assertEquals(-32600, response.get("error").get("code").asInt());
    }

    @Test
    public void requestWithoutMethodReturnsInvalidRequestError() throws IOException {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        // поле method отсутствует entirely

        JsonNode response = protocol.processRequest(request);

        assertNotNull(response);
        assertEquals(-32600, response.get("error").get("code").asInt());
        assertEquals("Invalid Request", response.get("error").get("message").asText());
    }

    @Test
    public void pingReturnsEmptyResult() throws IOException {
        JsonNode response = protocol.processRequest(request("ping"));

        assertNotNull(response.get("result"));
        assertEquals(0, response.get("result").size());
        assertEquals(1, response.get("id").asInt());
    }
    @Test
    public void nonStringToolArgumentIsRejected() throws IOException {
        ObjectNode request = request("tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "jira_get_issue");
        params.putObject("arguments").put("issue_key", 123);

        JsonNode result = protocol.processRequest(request).get("result");

        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("непустой строкой"));
    }

    @Test
    public void unknownToolArgumentIsRejected() throws IOException {
        ObjectNode request = request("tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "jira_get_issue");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("issue_key", "PROJ-1");
        arguments.put("unexpected", "value");

        JsonNode result = protocol.processRequest(request).get("result");

        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("Неизвестный параметр"));
    }

    @Test
    public void limitOutsideConfiguredRangeIsRejected() throws IOException {
        ObjectNode request = request("tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "jira_search");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("jql", "project = PROJ");
        arguments.put("limit", 0);

        JsonNode result = protocol.processRequest(request).get("result");

        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("не меньше 1"));
    }

    @Test
    public void fractionalLimitIsRejected() throws IOException {
        ObjectNode request = request("tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "jira_search");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("jql", "project = PROJ");
        arguments.put("limit", 1.5);

        JsonNode result = protocol.processRequest(request).get("result");

        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("целым числом"));
    }

    @Test
    public void invalidJsonRpcVersionReturnsInvalidRequest() throws IOException {
        ObjectNode request = request("ping");
        request.put("jsonrpc", "1.0");

        JsonNode response = protocol.processRequest(request);

        assertEquals(-32600, response.get("error").get("code").asInt());
    }

    @Test
    public void nonObjectParamsReturnsInvalidRequest() throws IOException {
        ObjectNode request = request("tools/call");
        request.set("params", MAPPER.createArrayNode());

        JsonNode response = protocol.processRequest(request);

        assertEquals(-32600, response.get("error").get("code").asInt());
    }

    @Test
    public void booleanIdReturnsInvalidRequest() throws IOException {
        ObjectNode request = request("ping");
        request.put("id", true);

        JsonNode response = protocol.processRequest(request);

        assertEquals(-32600, response.get("error").get("code").asInt());
    }


    @Test
    public void fractionalIdReturnsInvalidRequestError() {
        ObjectNode request = request("ping");
        request.put("id", 1.5);

        JsonNode response = new McpProtocol().processRequest(request);

        assertEquals(-32600, response.get("error").get("code").asInt());
    }

    @Test
    public void initializeNegotiatesTheLatestHandshakeVersion() {
        JsonNode response = new McpProtocol().processRequest(initializeRequest("2025-11-25"));

        assertEquals("2025-11-25", response.get("result").get("protocolVersion").asText());
    }

    @Test
    public void discoverAdvertisesModernAndLegacyProtocolVersions() {
        JsonNode response = new McpProtocol().processRequest(modernRequest("server/discover"));
        JsonNode result = response.get("result");

        assertEquals("complete", result.get("resultType").asText());
        assertTrue(result.get("supportedVersions").toString().contains("2026-07-28"));
        assertTrue(result.get("supportedVersions").toString().contains("2025-11-25"));
        assertNotNull(result.get("capabilities").get("tools"));
        assertEquals("atlassian-lite",
                result.get("_meta").get("io.modelcontextprotocol/serverInfo").get("name").asText());
        assertEquals("public", result.get("cacheScope").asText());
        assertTrue(result.get("ttlMs").asLong() > 0);
    }

    @Test
    public void modernToolsListDoesNotRequireInitializeHandshake() {
        McpProtocol statelessProtocol = new McpProtocol();

        JsonNode response = statelessProtocol.processRequest(modernRequest("tools/list"));
        JsonNode result = response.get("result");

        assertNotNull(result);
        assertEquals("complete", result.get("resultType").asText());
        assertTrue(result.get("tools").isArray());
        assertNotNull(result.get("_meta").get("io.modelcontextprotocol/serverInfo"));
    }

    @Test
    public void modernToolCallDoesNotRequireInitializeHandshake() {
        McpProtocol statelessProtocol = new McpProtocol();
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("properties", MAPPER.createObjectNode());
        statelessProtocol.addTool(new McpProtocol.ToolDefinition(
                "echo", "Returns a fixed value", schema, arguments -> "ok"));

        ObjectNode request = modernRequest("tools/call");
        ObjectNode params = (ObjectNode) request.get("params");
        params.put("name", "echo");
        params.putObject("arguments");

        JsonNode result = statelessProtocol.processRequest(request).get("result");

        assertEquals("complete", result.get("resultType").asText());
        assertFalse(result.path("isError").asBoolean());
        assertEquals("ok", result.get("content").get(0).get("text").asText());
    }

    @Test
    public void modernRequestRequiresClientCapabilitiesMetadata() {
        ObjectNode request = modernRequest("tools/list");
        ((ObjectNode) request.get("params").get("_meta"))
                .remove("io.modelcontextprotocol/clientCapabilities");

        JsonNode response = new McpProtocol().processRequest(request);

        assertEquals(-32602, response.get("error").get("code").asInt());
    }

    @Test
    public void modernRequestRejectsUnsupportedProtocolVersion() {
        ObjectNode request = modernRequest("tools/list");
        ((ObjectNode) request.get("params").get("_meta"))
                .put("io.modelcontextprotocol/protocolVersion", "2099-01-01");

        JsonNode response = new McpProtocol().processRequest(request);

        assertEquals(-32022, response.get("error").get("code").asInt());
        assertTrue(response.get("error").get("data").get("supportedVersions")
                .toString().contains("2026-07-28"));
    }

    private ObjectNode modernRequest(String method) {
        ObjectNode request = request(method);
        ObjectNode metadata = request.putObject("params").putObject("_meta");
        metadata.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        metadata.putObject("io.modelcontextprotocol/clientCapabilities");
        ObjectNode clientInfo = metadata.putObject("io.modelcontextprotocol/clientInfo");
        clientInfo.put("name", "test-client");
        clientInfo.put("version", "1.0.0");
        return request;
    }

}
