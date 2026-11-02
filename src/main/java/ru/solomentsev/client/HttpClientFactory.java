package ru.solomentsev.client;

/**
 * Фабрика для создания HTTP-клиентов.
 *
 * <p>Скрывает реализацию ({@link JdkHttpClient}) за интерфейсом {@link HttpClient},
 * обеспечивая Dependency Inversion Principle (DIP).</p>
 */
public final class HttpClientFactory {

    private HttpClientFactory() {
        // utility class
    }

    /**
     * Создает HTTP-клиент с заданным заголовком авторизации.
     *
     * @param authHeader заголовок Authorization (например, "Basic <base64>")
     * @return готовый к использованию HTTP-клиент
     */
    public static HttpClient create(String authHeader) {
        return new JdkHttpClient(authHeader);
    }

    /**
     * Создает HTTP-клиент с ограничением размера тела ответа.
     *
     * @param authHeader заголовок Authorization
     * @param maxResponseBytes максимальный размер тела ответа в байтах
     * @return HTTP-клиент с заданным ограничением
     * @throws IllegalArgumentException если лимит не положительный
     */
    public static HttpClient create(String authHeader, int maxResponseBytes) {
        return new JdkHttpClient(authHeader, java.time.Duration.ofSeconds(60), maxResponseBytes);
    }
}
