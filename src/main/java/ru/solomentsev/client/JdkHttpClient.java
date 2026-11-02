package ru.solomentsev.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class JdkHttpClient implements HttpClient {

    /** Логгер HTTP-клиента. */
    private static final Logger log = LoggerFactory.getLogger(JdkHttpClient.class);

    /** Максимальная длина тела ответа, попадающего в лог. */
    private static final int MAX_LOGGED_BODY_LENGTH = 500;

    private final java.net.http.HttpClient client;
    private final String authHeader;

    JdkHttpClient(String authHeader) {
        this.authHeader = authHeader;
        this.client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String get(String url) throws IOException {
        try {
            return send(HttpRequest.newBuilder(URI.create(url)).GET(), "GET", url, null);
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректный URL: " + url, e);
        }
    }

    @Override
    public String post(String url, String jsonBody) throws IOException {
        try {
            return send(HttpRequest.newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)), "POST", url, jsonBody);
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректный URL: " + url, e);
        }
    }

    @Override
    public String put(String url, String jsonBody) throws IOException {
        try {
            return send(HttpRequest.newBuilder(URI.create(url))
                    .PUT(HttpRequest.BodyPublishers.ofString(jsonBody)), "PUT", url, jsonBody);
        } catch (IllegalArgumentException e) {
            throw new IOException("Некорректный URL: " + url, e);
        }
    }

    private String send(HttpRequest.Builder builder, String method, String url, String body) throws IOException {
        builder.header("Authorization", authHeader)
                .header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        log.debug("HTTP-запрос: {} {}", method, pathOf(url));

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Запрос к " + pathOf(url) + " прерван", e);
        }
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000L;

        int status = response.statusCode();
        log.info("HTTP-ответ: {} {} -> {} ({} мс)", method, pathOf(url), status, durationMs);

        if (status < 200 || status >= 300) {
            log.error("HTTP-ошибка: {} {} -> {}: {}", method, pathOf(url), status,
                    truncate(response.body()));
            throw new IOException("HTTP " + status + " (" + pathOf(url) + "): "
                    + truncate(response.body()));
        }
        return response.body();
    }

    /**
     * Возвращает путь URL без query-строки: параметры запроса могут содержать
     * чувствительные данные (JQL, CQL, e-mail), в лог и в текст ошибки они не попадают.
     *
     * @param url полный URL
     * @return путь запроса
     */
    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null || path.isEmpty() ? url : path;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= MAX_LOGGED_BODY_LENGTH) {
            return body;
        }
        return body.substring(0, MAX_LOGGED_BODY_LENGTH) + "... (обрезано)";
    }
}
