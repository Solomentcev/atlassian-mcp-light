package ru.solomentsev.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Собирает конфигурацию приложения из нескольких источников.
 *
 * <p>Поддерживаемые источники (в порядке убывания приоритета):
 * <ol>
 *   <li>свойства JVM ({@code -Djira.url=...});</li>
 *   <li>переменные окружения ({@code JIRA_URL});</li>
 *   <li>dotenv-файл {@code .env} (ключи совпадают с именами переменных окружения);</li>
 *   <li>properties-файл {@code application.properties} (ключи {@code jira.url},
 *       также принимаются имена переменных окружения).</li>
 * </ol>
 *
 * <p>Берётся первое непустое значение в этой цепочке. Пустые значения
 * (например, объявленная, но не заполненная переменная окружения)
 * считаются отсутствующими и не перекрывают следующий источник.</p>
 *
 * <p>Файлы ищутся в следующем порядке: явный путь (переменная окружения
 * {@code ATLASSIAN_ENV_FILE} / {@code ATLASSIAN_PROPERTIES_FILE} либо свойство JVM
 * {@code atlassian.env.file} / {@code atlassian.properties.file}), текущий рабочий
 * каталог, каталог рядом с jar-файлом. Для properties-файла дополнительно
 * проверяется classpath.</p>
 */
public final class ConfigLoader {

    /** Логгер загрузчика конфигурации. */
    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

    /** Ключ свойства JVM с базовым URL Jira. */
    private static final String SYS_JIRA_URL = "jira.url";

    /** Ключ свойства JVM с email пользователя. */
    private static final String SYS_JIRA_EMAIL = "jira.email";

    /** Ключ свойства JVM с API-токеном. */
    private static final String SYS_JIRA_TOKEN = "jira.token";

    /** Ключ свойства JVM с базовым URL Confluence. */
    private static final String SYS_CONFLUENCE_URL = "confluence.url";

    /** Имя переменной окружения с базовым URL Jira. */
    private static final String ENV_JIRA_URL = "JIRA_URL";

    /** Имя переменной окружения с email пользователя. */
    private static final String ENV_JIRA_EMAIL = "JIRA_EMAIL";

    /** Имя переменной окружения с API-токеном. */
    private static final String ENV_JIRA_TOKEN = "JIRA_TOKEN";

    /** Имя переменной окружения с базовым URL Confluence. */
    private static final String ENV_CONFLUENCE_URL = "CONFLUENCE_URL";

    /** Переменная окружения с путём к dotenv-файлу. */
    private static final String ENV_FILE_PATH = "ATLASSIAN_ENV_FILE";

    /** Переменная окружения с путём к properties-файлу. */
    private static final String PROPERTIES_FILE_PATH = "ATLASSIAN_PROPERTIES_FILE";

    /** Свойство JVM с путём к dotenv-файлу. */
    private static final String SYS_ENV_FILE_PATH = "atlassian.env.file";

    /** Свойство JVM с путём к properties-файлу. */
    private static final String SYS_PROPERTIES_FILE_PATH = "atlassian.properties.file";

    /** Имя dotenv-файла по умолчанию. */
    private static final String DEFAULT_ENV_FILE_NAME = ".env";

    /** Имя properties-файла по умолчанию. */
    private static final String DEFAULT_PROPERTIES_FILE_NAME = "application.properties";

    /** Путь properties-файла в classpath. */
    private static final String CLASSPATH_PROPERTIES = "/application.properties";

    private final Map<String, String> environment;
    private final Properties systemProperties;
    private final Map<String, String> envFileValues;
    private final Map<String, String> propertiesValues;

    private ConfigLoader(Map<String, String> environment,
                         Properties systemProperties,
                         Map<String, String> envFileValues,
                         Map<String, String> propertiesValues) {
        this.environment = environment;
        this.systemProperties = systemProperties;
        this.envFileValues = envFileValues;
        this.propertiesValues = propertiesValues;
    }

    /**
     * Создает загрузчик на основе реальных переменных окружения и свойств JVM.
     *
     * <p>Файлы конфигурации ищутся автоматически: явный путь, рабочий каталог,
     * каталог рядом с jar-файлом, а для properties — ещё и classpath.</p>
     *
     * @return готовый загрузчик
     */
    public static ConfigLoader fromSystem() {
        Map<String, String> environment = System.getenv();
        Properties systemProperties = copyOf(System.getProperties());

        Path envFile = locate(
                explicitPath(environment, systemProperties, ENV_FILE_PATH, SYS_ENV_FILE_PATH),
                DEFAULT_ENV_FILE_NAME);
        Path propertiesFile = locate(
                explicitPath(environment, systemProperties, PROPERTIES_FILE_PATH, SYS_PROPERTIES_FILE_PATH),
                DEFAULT_PROPERTIES_FILE_NAME);

        Map<String, String> propertiesValues = readPropertiesFile(propertiesFile);
        if (propertiesValues.isEmpty()) {
            propertiesValues = readClasspathProperties();
        }

        log.debug("Файл конфигурации {}: {}", DEFAULT_ENV_FILE_NAME, envFile);
        log.debug("Файл конфигурации {}: {}", DEFAULT_PROPERTIES_FILE_NAME, propertiesFile);

        return new ConfigLoader(environment, systemProperties, readEnvFile(envFile), propertiesValues);
    }

    /**
     * Создает загрузчик с явно заданными источниками.
     *
     * <p>Используется в тестах: позволяет подставить переменные окружения,
     * свойства JVM и файлы конфигурации без влияния на реальное окружение.</p>
     *
     * @param environment      переменные окружения (может быть null)
     * @param systemProperties свойства JVM (может быть null)
     * @param envFile          путь к dotenv-файлу (null — файл не используется)
     * @param propertiesFile   путь к properties-файлу (null — файл не используется)
     * @return готовый загрузчик
     */
    public static ConfigLoader of(Map<String, String> environment,
                                   Properties systemProperties,
                                   Path envFile,
                                   Path propertiesFile) {
        return new ConfigLoader(
                environment == null ? Collections.emptyMap() : environment,
                systemProperties == null ? new Properties() : systemProperties,
                readEnvFile(envFile),
                readPropertiesFile(propertiesFile));
    }

    /**
     * Собирает конфигурацию приложения.
     *
     * @return конфигурация с заполненными обязательными параметрами
     * @throws IllegalStateException если не заданы обязательные параметры
     */
    public AppConfig load() {
        String jiraUrl = require(resolve(SYS_JIRA_URL, ENV_JIRA_URL),
                ENV_JIRA_URL, SYS_JIRA_URL);
        String jiraEmail = require(resolve(SYS_JIRA_EMAIL, ENV_JIRA_EMAIL),
                ENV_JIRA_EMAIL, SYS_JIRA_EMAIL);
        String jiraToken = require(resolve(SYS_JIRA_TOKEN, ENV_JIRA_TOKEN),
                ENV_JIRA_TOKEN, SYS_JIRA_TOKEN);
        String confluenceUrl = resolve(SYS_CONFLUENCE_URL, ENV_CONFLUENCE_URL);

        return AppConfig.of(jiraUrl, confluenceUrl, jiraEmail, jiraToken);
    }

    /**
     * Ищет значение по цепочке источников с убыванием приоритета.
     *
     * @param systemKey ключ свойства JVM
     * @param envKey    имя переменной окружения
     * @return значение или null, если ни один источник его не содержит
     */
    private String resolve(String systemKey, String envKey) {
        String value = trimToNull(systemProperties.getProperty(systemKey));
        if (value != null) {
            log.debug("Параметр {} взят из свойства JVM", systemKey);
            return value;
        }
        value = trimToNull(environment.get(envKey));
        if (value != null) {
            log.debug("Параметр {} взят из переменной окружения {}", systemKey, envKey);
            return value;
        }
        value = trimToNull(envFileValues.get(envKey));
        if (value != null) {
            log.debug("Параметр {} взят из файла {}", systemKey, DEFAULT_ENV_FILE_NAME);
            return value;
        }
        value = trimToNull(propertiesValues.get(systemKey));
        if (value != null) {
            log.debug("Параметр {} взят из файла {}", systemKey, DEFAULT_PROPERTIES_FILE_NAME);
            return value;
        }
        value = trimToNull(propertiesValues.get(envKey));
        if (value != null) {
            log.debug("Параметр {} взят из файла {}", systemKey, DEFAULT_PROPERTIES_FILE_NAME);
            return value;
        }
        log.debug("Параметр {} не задан ни в одном источнике", systemKey);
        return null;
    }

    private static String require(String value, String envKey, String propertiesKey) {
        if (value == null) {
            throw new IllegalStateException("Обязательная конфигурация не задана: " + envKey
                    + " (или " + propertiesKey + " в " + DEFAULT_PROPERTIES_FILE_NAME
                    + ", или " + envKey + " в " + DEFAULT_ENV_FILE_NAME + ")");
        }
        return value;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Определяет явно заданный путь к файлу конфигурации.
     *
     * @param environment      переменные окружения
     * @param systemProperties свойства JVM
     * @param envKey           имя переменной окружения с путём
     * @param systemKey        ключ свойства JVM с путём
     * @return путь или null, если путь явно не задан
     */
    private static String explicitPath(Map<String, String> environment,
                                       Properties systemProperties,
                                       String envKey,
                                       String systemKey) {
        String fromSystem = trimToNull(systemProperties.getProperty(systemKey));
        if (fromSystem != null) {
            return fromSystem;
        }
        return trimToNull(environment.get(envKey));
    }

    /**
     * Ищет файл конфигурации: явный путь, рабочий каталог, каталог рядом с jar-файлом.
     *
     * @param explicitPath явно заданный путь (может быть null)
     * @param fileName     имя файла
     * @return путь к существующему файлу или null
     */
    static Path locate(String explicitPath, String fileName) {
        return locate(explicitPath, fileName, Paths.get("").toAbsolutePath(), jarDirectory());
    }

    static Path locate(String explicitPath, String fileName, Path workingDirectory, Path jarDirectory) {
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path explicit = Paths.get(explicitPath.trim()).toAbsolutePath();
            if (Files.isRegularFile(explicit)) {
                return explicit;
            }
            log.warn("Файл конфигурации не найден: {}", explicit);
            return null;
        }

        if (workingDirectory != null) {
            Path candidate = workingDirectory.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        if (jarDirectory != null) {
            Path candidate = jarDirectory.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Возвращает каталог, в котором расположен jar-файл (или class-каталог).
     *
     * @return путь к каталогу или null, если определить не удалось
     */
    private static Path jarDirectory() {
        try {
            URL location = ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            Path path = Paths.get(location.toURI());
            return Files.isDirectory(path) ? path : path.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> readEnvFile(Path file) {
        if (file == null) {
            return Collections.emptyMap();
        }
        try {
            return DotEnvParser.parse(file);
        } catch (IOException e) {
            log.warn("Не удалось прочитать файл {}: {}", file, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static Map<String, String> readPropertiesFile(Path file) {
        if (file == null) {
            return Collections.emptyMap();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Не удалось прочитать файл {}: {}", file, e.getMessage());
            return Collections.emptyMap();
        }
        return toMap(properties);
    }

    private static Map<String, String> readClasspathProperties() {
        try (InputStream input = ConfigLoader.class.getResourceAsStream(CLASSPATH_PROPERTIES)) {
            if (input == null) {
                return Collections.emptyMap();
            }
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            return toMap(properties);
        } catch (IOException e) {
            log.warn("Не удалось прочитать {} из classpath: {}", CLASSPATH_PROPERTIES, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private static Map<String, String> toMap(Properties properties) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            values.put(name, properties.getProperty(name));
        }
        return values;
    }

    private static Properties copyOf(Properties source) {
        Properties copy = new Properties();
        if (source != null) {
            copy.putAll(source);
        }
        return copy;
    }

}

