package ru.solomentsev.app;

import org.junit.Test;
import ru.solomentsev.core.protocol.McpProtocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Проверяет STDIO-транспорт MCP-сервера.
 */
public class AtlassianMcpServerTest {

    @Test
    public void stdioWritesJsonRpcResponsesInUtf8() throws IOException {
        String request = "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":1,"
                + "\\"method\\":\\"неизвестный-метод\\",\\"params\\":{}}\\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        AtlassianMcpServer.handleStdioTransport(
                new McpProtocol(),
                new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
                output);

        String response = output.toString(StandardCharsets.UTF_8);
        assertTrue(response.contains("Неизвестный метод: неизвестный-метод"));
        assertTrue(response.endsWith("\\n"));
    }

    @Test
    public void stdioDoesNotRespondToNotifications() throws IOException {
        String request = "{\\"jsonrpc\\":\\"2.0\\","
                + "\\"method\\":\\"notifications/initialized\\"}\\n";
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        AtlassianMcpServer.handleStdioTransport(
                new McpProtocol(),
                new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
                output);

        assertEquals("", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void stdioPropagatesOutputErrors() {
        String request = "{\\"jsonrpc\\":\\"2.0\\",\\"id\\":1,"
                + "\\"method\\":\\"ping\\",\\"params\\":{}}\\n";
        OutputStream failingOutput = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                throw new IOException("output unavailable");
            }
        };

        assertThrows(IOException.class, () -> AtlassianMcpServer.handleStdioTransport(
                new McpProtocol(),
                new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)),
                failingOutput));
    }
}
