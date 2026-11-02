package ru.solomentsev.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Тесты {@link JdkHttpClient} на локальном HTTP-сервере:
 * заголовки, передача JSON-тела, обработка ошибочного кода ответа.
 */
public class JdkHttpClientTest {

    private HttpServer server;
    private HttpClient client;

    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();
    private final AtomicReference<String> lastAccept = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            capture(exchange);
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.createContext("/fail", exchange -> {
            capture(exchange);
            respond(exchange, 500, "{\"error\":\"boom\"}");
        });
        server.createContext("/large", exchange -> {
            capture(exchange);
            respond(exchange, 200, "0123456789");
        });
        server.createContext("/rest/", exchange -> {
            capture(exchange);
            respond(exchange, 500, "{\"errorMessages\":[\"JQL is invalid\"]}");
        });
        server.start();

        client = HttpClientFactory.create("Basic dXNlcjp0b2tlbg==");
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void capture(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastPath.set(exchange.getRequestURI().getPath());
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
        lastAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
        try (InputStream in = exchange.getRequestBody()) {
            lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    @Test
    public void getReturnsResponseBodyAndSendsAuthHeader() throws IOException {
        String body = client.get(url("/ok"));

        assertEquals("{\"ok\":true}", body);
        assertEquals("GET", lastMethod.get());
        assertEquals("Basic dXNlcjp0b2tlbg==", lastAuthorization.get());
        assertEquals("application/json", lastAccept.get());
    }

    @Test
    public void postSendsJsonBodyWithContentType() throws IOException {
        String body = client.post(url("/ok"), "{\"title\":\"test\"}");

        assertEquals("{\"ok\":true}", body);
        assertEquals("POST", lastMethod.get());
        assertEquals("{\"title\":\"test\"}", lastBody.get());
        assertEquals("application/json", lastContentType.get());
    }

    @Test
    public void putSendsJsonBodyAndReturnsResponseBody() throws IOException {
        String body = client.put(url("/ok"), "{\"version\":{\"number\":2}}");

        assertEquals("{\"ok\":true}", body);
        assertEquals("PUT", lastMethod.get());
        assertEquals("{\"version\":{\"number\":2}}", lastBody.get());
        assertEquals("application/json", lastContentType.get());
        assertEquals("Basic dXNlcjp0b2tlbg==", lastAuthorization.get());
    }

    @Test
    public void oversizedResponseIsRejectedBeforeFullBodyIsRead() {
        HttpClient limitedClient = HttpClientFactory.create("Basic dXNlcjp0b2tlbg==", 5);

        try {
            limitedClient.get(url("/large"));
            fail("Ожидалось IOException для слишком большого HTTP-ответа");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("exceeds configured limit of 5 bytes"));
        }
    }

    @Test
    public void errorStatusIsReportedAsIOException() {
        try {
            client.put(url("/fail"), "{}");
            fail("Ожидалось IOException для HTTP 500");
        } catch (IOException e) {
            assertNotNull(e.getMessage());
            assertTrue(e.getMessage().contains("HTTP 500"));
            assertFalse("Тело HTTP-ответа не должно попадать в ошибку",
                    e.getMessage().contains("boom"));
        }
    }

    @Test
    public void errorMessageDoesNotLeakQueryParameters() {
        try {
            client.get(url("/rest/api/2/user/search?query=user%40example.com&jql=SECRET"));
            fail("Ожидалось IOException для HTTP 500");
        } catch (IOException e) {
            String message = e.getMessage();
            assertNotNull(message);
            assertTrue("В тексте ошибки должен быть код ответа: " + message,
                    message.contains("HTTP 500"));
            assertTrue("В тексте ошибки должен быть путь запроса: " + message,
                    message.contains("/rest/api/2/user/search"));
            assertFalse("Query-параметры не должны попадать в текст ошибки: " + message,
                    message.contains("query="));
            assertFalse("E-mail не должен попадать в текст ошибки: " + message,
                    message.contains("user@example.com"));
            assertFalse("Закодированный e-mail не должен попадать в текст ошибки: " + message,
                    message.contains("%40example.com"));
            assertFalse("Секрет из query не должен попадать в текст ошибки: " + message,
                    message.contains("SECRET"));
            assertFalse("Тело ответа не должно попадать в текст ошибки: " + message,
                    message.contains("JQL is invalid"));
        }
    }
}
