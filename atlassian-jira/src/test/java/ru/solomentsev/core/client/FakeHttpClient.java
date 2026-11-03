package ru.solomentsev.core.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Тестовый HTTP-клиент: записывает запросы и возвращает заранее заданные ответы.
 *
 * <p>Позволяет тестировать сервисы без сетевых вызовов.</p>
 */
public class FakeHttpClient implements HttpClient {

    /** Зафиксированный HTTP-запрос. */
    public record Request(String method, String url, String body) {
    }

    private final Map<String, String> responses = new LinkedHashMap<>();
    private final List<Request> requests = new ArrayList<>();
    private IOException failure;

    /**
     * Регистрирует ответ для URL.
     *
     * @param url    адрес запроса
     * @param body   тело ответа
     * @return этот клиент
     */
    public FakeHttpClient onGet(String url, String body) {
        responses.put("GET " + url, body);
        return this;
    }

    /**
     * Регистрирует ответ для POST-запроса.
     *
     * @param url  адрес запроса
     * @param body тело ответа
     * @return этот клиент
     */
    public FakeHttpClient onPost(String url, String body) {
        responses.put("POST " + url, body);
        return this;
    }

    /**
     * Регистрирует ответ для PUT-запроса.
     *
     * @param url  адрес запроса
     * @param body тело ответа
     * @return этот клиент
     */
    public FakeHttpClient onPut(String url, String body) {
        responses.put("PUT " + url, body);
        return this;
    }

    /**
     * Заставляет клиент бросать исключение при любом запросе.
     *
     * @param failure исключение
     * @return этот клиент
     */
    public FakeHttpClient failWith(IOException failure) {
        this.failure = failure;
        return this;
    }

    /**
     * Возвращает список зафиксированных запросов.
     *
     * @return запросы в порядке вызова
     */
    public List<Request> getRequests() {
        return requests;
    }

    /**
     * Возвращает последний зафиксированный запрос.
     *
     * @return последний запрос
     */
    public Request getLastRequest() {
        return requests.get(requests.size() - 1);
    }

    @Override
    public String get(String url) throws IOException {
        return record("GET", url, null);
    }

    @Override
    public String post(String url, String jsonBody) throws IOException {
        return record("POST", url, jsonBody);
    }

    @Override
    public String put(String url, String jsonBody) throws IOException {
        return record("PUT", url, jsonBody);
    }

    private String record(String method, String url, String body) throws IOException {
        requests.add(new Request(method, url, body));
        if (failure != null) {
            throw failure;
        }
        String response = responses.get(method + " " + url);
        if (response == null) {
            throw new IOException("Нет зарегистрированного ответа для " + method + " " + url);
        }
        return response;
    }}
