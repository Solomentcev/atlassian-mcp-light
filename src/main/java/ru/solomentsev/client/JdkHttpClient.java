package ru.solomentsev.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

final class JdkHttpClient implements HttpClient {

    private static final Logger log = LoggerFactory.getLogger(JdkHttpClient.class);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final java.net.http.HttpClient client;
    private final String authHeader;
    private final Duration requestTimeout;

    JdkHttpClient(String authHeader) {
        this(authHeader, DEFAULT_REQUEST_TIMEOUT);
    }

    JdkHttpClient(String authHeader, Duration requestTimeout) {
        this.authHeader = Objects.requireNonNull(authHeader, "authHeader");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout должен быть положительным");
        }
        this.client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                // Не пересылаем Basic Auth автоматически на адрес из redirect.
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String get(String url) throws IOException {
        try {
            return send(HttpRequest.newBuilder(URI.create(url)).GET(), "GET", url, null);
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректный URL запроса", e);
        }
    }

    @Override
    public String post(String url, String jsonBody) throws IOException {
        try {
            return send(HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)), "POST", url, jsonBody);
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректный URL запроса", e);
        }
    }

    @Override
    public String put(String url, String jsonBody) throws IOException {
        try {
            return send(HttpRequest.newBuilder(URI.create(url))
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonBody)), "PUT", url, jsonBody);
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректный URL запроса", e);
        }
    }

    private String send(HttpRequest.Builder builder, String method, String url, String body)
            throws IOException {
        builder.header("Authorization", authHeader)
                .header("Accept", "application/json")
                .timeout(requestTimeout);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        String path = pathOf(url);
        log.debug("HTTP-запрос: {} {}", method, path);

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос " + method + " " + path + " прерван", e);
        }
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;

        int status = response.statusCode();
        log.info("HTTP-ответ: {} {} -> {} ({} мс)", method, path, status, durationMs);

        if (status < 200 || status >= 300) {
            // Тело ответа Atlassian может содержать персональные данные или фрагменты
            // пользовательского контента. Не включаем его ни в логи, ни в MCP-ошибку.
            log.error("HTTP-ошибка: {} {} -> {}", method, path, status);
            throw new IOException("HTTP " + status + " (" + method + " " + path + ")");
        }
        return response.body();
    }

    /**
     * Возвращает только path, исключая query, fragment, user-info и host.
     */
    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getRawPath();
            return path == null || path.isEmpty() ? "/" : path;
        } catch (IllegalArgumentException e) {
            return "<invalid-url>";
        }
    }
}
