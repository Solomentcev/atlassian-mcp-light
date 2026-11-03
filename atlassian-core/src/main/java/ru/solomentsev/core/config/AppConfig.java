package ru.solomentsev.core.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Неизменяемая конфигурация приложения.
 *
 * <p>Единая неизменяемая модель настроек MCP-сервера. Загрузка значений из JVM
 * properties, переменных окружения, файла {@code .env} и {@code application.properties}
 * выполняется классом {@link ConfigLoader}; этот класс отвечает за валидацию
 * и безопасное предоставление уже загруженных значений.</p>
 */
public final class AppConfig {

    /** Лимит текста одного поля в ответе инструмента по умолчанию. */
    public static final int DEFAULT_MAX_TEXT_LENGTH = 4000;

    /** Максимальный размер HTTP-ответа по умолчанию (2 МиБ). */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    /** Назначение Jira REST API. */
    private static final String JIRA_API_PREFIX = "/rest/api";

    /** Назначение Confluence REST API. */
    private static final String CONFLUENCE_API_PREFIX = "/rest/api";

    /** Значение по умолчанию для Confluence при отсутствии конфигурации. */
    private static final String DEFAULT_CONFLUENCE_PATH = "/wiki";

    /** Нормализованный URL Jira. */
    private final String jiraUrl;

    /** Нормализованный URL Confluence (может быть null, если не задан). */
    private final String confluenceUrl;

    /** Email пользователя. */
    private final String jiraEmail;

    /** API токен. */
    private final String jiraToken;

    /** Предварительно вычисленный заголовок Authorization. */
    private final String authHeader;

    /** Максимальная длина одного текстового поля в ответе инструмента. */
    private final int maxTextLength;

    /** Максимальный размер тела HTTP-ответа в байтах. */
    private final int maxResponseBytes;

    /**
     * Приватный конструктор. Создаёт конфигурацию с заданными значениями.
     *
     * @param jiraUrl      базовый URL Jira (не null, не пустой после нормализации)
     * @param confluenceUrl базовый URL Confluence (может быть null)
     * @param jiraEmail    email для аутентификации (не null, не пустой)
     * @param jiraToken    токен для аутентификации (не null, не пустой)
     */
    private AppConfig(String jiraUrl, String confluenceUrl, String jiraEmail, String jiraToken,
                      int maxTextLength, int maxResponseBytes) {
        this.jiraUrl = normalizeUrl(jiraUrl);
        this.confluenceUrl = confluenceUrl != null ? normalizeUrl(confluenceUrl) : null;
        this.jiraEmail = jiraEmail;
        this.jiraToken = jiraToken;
        this.authHeader = buildAuthHeader(jiraEmail, jiraToken);
        if (maxTextLength <= 0) {
            throw new IllegalArgumentException("maxTextLength должен быть больше нуля");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes должен быть больше нуля");
        }
        this.maxTextLength = maxTextLength;
        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Создаёт конфигурацию из всех поддерживаемых источников.
     *
     * <p>Метод сохранён для совместимости. Приоритет источников и загрузку файлов
     * централизованно выполняет {@link ConfigLoader}.</p>
     *
     * @deprecated Используйте {@link ConfigLoader#fromSystem()}{@code .load()}.
     * @return загруженная и проверенная конфигурация
     * @throws IllegalStateException если обязательные параметры отсутствуют
     *                               или содержат недопустимые значения
     */
    @Deprecated
    public static AppConfig fromEnvironment() {
        return ConfigLoader.fromSystem().load();
    }

    /**
     * Создает конфигурацию явно (для тестов и unit-тестирования).
     *
     * @param jiraUrl      базовый URL Jira (уже нормализованный или нормализуется внутри)
     * @param confluenceUrl базовый URL Confluence (или null)
     * @param jiraEmail    email для аутентификации
     * @param jiraToken    токен для аутентификации
     * @return новый экземпляр AppConfig
     * @throws IllegalStateException если обязательные поля пустые
     */
    public static AppConfig of(String jiraUrl, String confluenceUrl,
                               String jiraEmail, String jiraToken) {
        return of(jiraUrl, confluenceUrl, jiraEmail, jiraToken,
                DEFAULT_MAX_TEXT_LENGTH, DEFAULT_MAX_RESPONSE_BYTES);
    }

    /**
     * Создает конфигурацию с явно заданными ограничениями размера данных.
     *
     * @param jiraUrl базовый URL Jira
     * @param confluenceUrl базовый URL Confluence или null
     * @param jiraEmail email для аутентификации
     * @param jiraToken API-токен
     * @param maxTextLength максимальное количество сохраняемых символов текста до маркера сокращения
     * @param maxResponseBytes максимальный размер HTTP-ответа в байтах
     * @return новая конфигурация
     * @throws IllegalStateException если обязательные параметры пустые
     * @throws IllegalArgumentException если один из лимитов не положительный
     */
    public static AppConfig of(String jiraUrl, String confluenceUrl, String jiraEmail, String jiraToken,
                               int maxTextLength, int maxResponseBytes) {
        validateRequired(jiraUrl, "jiraUrl");
        validateRequired(jiraEmail, "jiraEmail");
        validateRequired(jiraToken, "jiraToken");
        return new AppConfig(jiraUrl, confluenceUrl, jiraEmail, jiraToken,
                maxTextLength, maxResponseBytes);
    }


    /**
     * Проверяет наличие обязательного строкового параметра.
     *
     * @param value значение параметра
     * @param name название параметра для сообщения об ошибке
     * @throws IllegalStateException если значение равно null или пустое
     */
    private static void validateRequired(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Обязательный параметр конфигурации не задан: " + name);
        }
    }

    /**
     * Нормализует URL: убирает пробелы по краям и завершающие косые черты.
     *
     * @param url URL для нормализации (может быть null)
     * @return нормализованный URL или null, если вход был null
     */
    private static String normalizeUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Некорректный URL Atlassian", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "URL Atlassian должен быть абсолютным HTTPS URL без user-info, query и fragment");
        }

        return trimmed.replaceAll("/+$", "");
    }

    /**
     * Строит HTTP Basic Auth заголовок из email и токена.
     *
     * @param email e-mail пользователя
     * @param token API токен
     * @return заголовок вида "Basic <base64-encoded-credentials>"
     */
    private static String buildAuthHeader(String email, String token) {
        String credentials = email + ":" + token;
        String encoded = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    /**
     * Возвращает URL Jira с нормализацией.
     *
     * @return базовый URL Jira (без trailing slash)
     */
    public String getJiraUrl() {
        return jiraUrl;
    }

    /**
     * Возвращает URL Confluence.
     *
     * <p>Если {@code confluenceUrl} не задан, метод {@link #resolveConfluenceUrl()}
     * вернет fallback-значение.</p>
     *
     * @return URL Confluence или null, если не задан
     */
    public String getConfluenceUrl() {
        return confluenceUrl;
    }

    /**
     * Возвращает email пользователя.
     *
     * @return email
     */
    public String getJiraEmail() {
        return jiraEmail;
    }

    /**
     * Возвращает API токен.
     *
     * @return токен
     */
    public String getJiraToken() {
        return jiraToken;
    }

    /**
     * Возвращает готовый HTTP-заголовок Authorization: Basic.
     *
     * @return строка заголовка
     */
    public String getAuthHeader() {
        return authHeader;
    }

    /**
     * Возвращает максимальное количество сохраняемых символов до маркера сокращения в ответе MCP-инструмента.
     *
     * @return количество сохраняемых символов до маркера сокращения
     */
    public int getMaxTextLength() {
        return maxTextLength;
    }

    /**
     * Возвращает максимальный размер тела HTTP-ответа.
     *
     * @return лимит в байтах
     */
    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    /**
     * Возвращает URL Confluence с fallback-логикой.
     *
     * <p>Если Confluence URL задан явно — возвращает его.
     * Если не задан — возвращает JIRA_URL + "/wiki" (типично для Atlassian Cloud).</p>
     *
     * @return URL Confluence (гарантированно не null, если JIRA_URL валиден)
     */
    public String resolveConfluenceUrl() {
        if (confluenceUrl != null && !confluenceUrl.isBlank()) {
            return confluenceUrl;
        }
        return jiraUrl + DEFAULT_CONFLUENCE_PATH;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AppConfig that = (AppConfig) o;
        return jiraUrl.equals(that.jiraUrl) &&
                java.util.Objects.equals(confluenceUrl, that.confluenceUrl) &&
                jiraEmail.equals(that.jiraEmail) &&
                jiraToken.equals(that.jiraToken) &&
                maxTextLength == that.maxTextLength &&
                maxResponseBytes == that.maxResponseBytes;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(jiraUrl, confluenceUrl, jiraEmail, jiraToken,
                maxTextLength, maxResponseBytes);
    }

    @Override
    public String toString() {
        return "AppConfig{" +
                "jiraUrl='" + jiraUrl + '\'' +
                ", confluenceUrl=" + (confluenceUrl != null ? confluenceUrl : "<null>") +
                ", maxTextLength=" + maxTextLength +
                ", maxResponseBytes=" + maxResponseBytes +
                '}';
    }
}
