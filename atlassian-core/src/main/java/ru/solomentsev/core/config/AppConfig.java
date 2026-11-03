package ru.solomentsev.core.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Неизменяемая конфигурация приложения.
 *
 * <p>Единая точка доступа к настройкам MCP-сервера. Читает переменные окружения
 * при создании и обеспечивает валидацию обязательных параметров.</p>
 *
 * <p>Внедрение конфигурации через конструктор (Dependency Injection) позволяет
 * легко подменять значения в тестах или читать из .env файла в будущем.</p>
 */
public final class AppConfig {

    /** Имя переменной окружения URL Jira. */
    private static final String JIRA_URL_ENV = "JIRA_URL";

    /** Имя переменной окружения Email для Basic-аутентификации. */
    private static final String JIRA_EMAIL_ENV = "JIRA_EMAIL";

    /** Имя переменной окружения API токена. */
    private static final String JIRA_TOKEN_ENV = "JIRA_TOKEN";

    /** Имя переменной окружения URL Confluence. */
    private static final String CONFLUENCE_URL_ENV = "CONFLUENCE_URL";

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
     * Защищенный конструктор. Создает конфигурацию с заданными значениями.
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
     * Создает конфигурацию из переменных окружения системы.
     *
     * <p>Учитывает только переменные окружения. Для полного набора источников
     * (свойства JVM, .env, application.properties) используйте
     * {@link ConfigLoader#fromSystem()}.</p>
     *
     * @return новый экземпляр AppConfig
     * @throws IllegalStateException если отсутствуют обязательные переменные
     *                               (JIRA_URL, JIRA_EMAIL, JIRA_TOKEN)
     */
    public static AppConfig fromEnvironment() {
        String jiraUrl = System.getenv(JIRA_URL_ENV);
        String jiraEmail = System.getenv(JIRA_EMAIL_ENV);
        String jiraToken = System.getenv(JIRA_TOKEN_ENV);
        String confluenceUrl = System.getenv(CONFLUENCE_URL_ENV);

        validateRequired(jiraUrl, JIRA_URL_ENV);
        validateRequired(jiraEmail, JIRA_EMAIL_ENV);
        validateRequired(jiraToken, JIRA_TOKEN_ENV);

        return new AppConfig(jiraUrl, confluenceUrl, jiraEmail, jiraToken,
                DEFAULT_MAX_TEXT_LENGTH, DEFAULT_MAX_RESPONSE_BYTES);
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
     * Валидирует, что значение не null и не пустое (после trim).
     *
     * @param value        проверяемое значение
     * @param paramName    имя параметра для сообщения об ошибке
     * @throws IllegalStateException если значение null или пустое
     */
    private static void validateRequired(String value, String paramName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Обязательная переменная конфигурации не задана: " + paramName);
        }
    }

    /**
     * Нормализует URL: убирает пробелы по краям и trailing slash.
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
