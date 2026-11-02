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
}
