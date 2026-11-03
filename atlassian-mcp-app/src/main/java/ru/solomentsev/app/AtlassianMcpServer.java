package ru.solomentsev.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ru.solomentsev.core.client.HttpClient;
import ru.solomentsev.core.client.HttpClientFactory;
import ru.solomentsev.core.config.AppConfig;
import ru.solomentsev.core.config.ConfigLoader;
import ru.solomentsev.core.protocol.McpProtocol;
import ru.solomentsev.confluence.service.ConfluenceService;
import ru.solomentsev.jira.service.JiraService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

/**
 * Точка входа MCP-сервера для Jira и Confluence.
 *
 * <p>Сервер работает по протоколу JSON-RPC 2.0 через stdin/stdout.
 * Является composition root: собирает конфигурацию, HTTP-клиент,
 * доменные сервисы и протокол, регистрирует инструменты
 * и запускает STDIO транспорт.</p>
 */
public class AtlassianMcpServer {

    /** Логгер сервера. Всё логирование идёт в stderr, stdout занят JSON-RPC. */
    private static final Logger log = LoggerFactory.getLogger(AtlassianMcpServer.class);

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * Запускает сервер.
     *
     * @param args аргументы (игнорируются)
     * @throws IOException если не удалось инициализировать или обработать запросы
     */
    public static void main(String[] args) throws IOException {
        // 1. Конфигурация (SRP: чтение env + properties + валидация)
        AppConfig config;
        try {
            config = ConfigLoader.fromSystem().load();
        } catch (IllegalStateException e) {
            log.error("Ошибка конфигурации: {}", e.getMessage());
            System.exit(1);
            return;
        }

        // 2. HTTP-клиент (DIP: зависимость от интерфейса)
        HttpClient httpClient = HttpClientFactory.create(
                config.getAuthHeader(), config.getMaxResponseBytes());

        // 3. Доменные сервисы (SRP: бизнес-логика в отдельных классах)
        JiraService jiraService = new JiraService(
                httpClient, config.getJiraUrl(), config.getMaxTextLength());
        ConfluenceService confluenceService = new ConfluenceService(
                httpClient, config.resolveConfluenceUrl(), config.getMaxTextLength());

        // 4. Протокол MCP (SRP: только JSON-RPC роутинг)
        McpProtocol protocol = new McpProtocol();

        // 5. Регистрация инструментов (OCP: расширение без изменения протокола)
        ToolRegistrar.register(protocol, jiraService, confluenceService);

        log.info("atlassian-mcp-server запущен: Jira={}, Confluence={}",
                config.getJiraUrl(), config.resolveConfluenceUrl());

        // 6. STDIO транспорт (SRP: обработка ввода-вывода)
        handleStdioTransport(protocol);

        log.info("atlassian-mcp-server остановлен");
    }

    /**
     * STDIO транспорт: последовательная обработка JSON-RPC сообщений из stdin.
     *
     * @param protocol протокол MCP
     * @throws IOException при ошибке чтения stdin
     */
    private static void handleStdioTransport(McpProtocol protocol) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter writer = new PrintWriter(System.out, true);

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }

            JsonNode parsed;
            try {
                parsed = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(line);
            } catch (JsonProcessingException e) {
                log.warn("Некорректный JSON в STDIO сообщении", e);
                writer.println(mapper.writeValueAsString(errorResponse(-32700, "Parse error")));
                continue;
            }

            if (parsed == null || !parsed.isObject()) {
                writer.println(mapper.writeValueAsString(errorResponse(-32600, "Invalid Request")));
                continue;
            }

            JsonNode response = protocol.processRequest((ObjectNode) parsed);
            if (response != null) {
                writer.println(mapper.writeValueAsString(response));
            }
        }
    }

    /**
     * Формирует JSON-RPC ошибку транспорта.
     *
     * @param code код ошибки
     * @param message описание ошибки
     * @return JSON-RPC ответ с null в качестве идентификатора
     */
    private static ObjectNode errorResponse(int code, String message) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", NullNode.getInstance());
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return response;
    }

}
