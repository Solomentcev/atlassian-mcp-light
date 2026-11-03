package ru.solomentsev.core.client;

import java.io.IOException;

/**
 * Интерфейс HTTP-клиента для выполнения запросов к Jira/Confluence REST API.
 *
 * <p>Разделение ответственности: интерфейс позволяет легко подменять
 * реализацию (например, для тестирования или в будущем — для другого провайдера).</p>
 */
public interface HttpClient {

    /**
     * Выполняет GET-запрос.
     *
     * @param url адрес эндпоинта (полный URL)
     * @return тело ответа в виде строки
     * @throws IOException при сетевой ошибке или HTTP ошибке
     */
    String get(String url) throws IOException;

    /**
     * Выполняет POST-запрос с JSON-телом.
     *
     * @param url     адрес эндпоинта
     * @param jsonBody JSON-тело запроса
     * @return тело ответа в виде строки
     * @throws IOException при сетевой ошибке или HTTP ошибке
     */
    String post(String url, String jsonBody) throws IOException;

    /**
     * Выполняет PUT-запрос с JSON-телом.
     *
     * @param url       адрес эндпоинта
     * @param jsonBody JSON-тело запроса
     * @return тело ответа в виде строки
     * @throws IOException при сетевой ошибке или HTTP ошибке
     */
    String put(String url, String jsonBody) throws IOException;
}
