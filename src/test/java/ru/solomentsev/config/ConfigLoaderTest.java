package ru.solomentsev.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Тесты {@link ConfigLoader}: приоритет источников конфигурации
 * (свойства JVM → переменные окружения → .env → application.properties).
 */
public class ConfigLoaderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final String JIRA_URL = "https://company.atlassian.net";
    private static final String EMAIL = "user@example.com";
    private static final String TOKEN = "secret-token";

    @Test
    public void environmentIsUsedWhenNothingElseIsSet() {
        AppConfig config = ConfigLoader.of(
                env("JIRA_URL", JIRA_URL, "JIRA_EMAIL", EMAIL, "JIRA_TOKEN", TOKEN),
                null, null, null).load();

        assertEquals(JIRA_URL, config.getJiraUrl());
        assertEquals(EMAIL, config.getJiraEmail());
        assertEquals(TOKEN, config.getJiraToken());
    }

    @Test
    public void systemPropertyOverridesEnvironment() {
        Properties properties = new Properties();
        properties.setProperty("jira.url", "https://from-property.atlassian.net");

        AppConfig config = ConfigLoader.of(
                env("JIRA_URL", JIRA_URL, "JIRA_EMAIL", EMAIL, "JIRA_TOKEN", TOKEN),
                properties, null, null).load();

        assertEquals("https://from-property.atlassian.net", config.getJiraUrl());
    }

    @Test
    public void environmentOverridesEnvFile() throws IOException {
        Path envFile = writeFile(".env", "JIRA_URL=https://from-env-file.atlassian.net\n");

        AppConfig config = ConfigLoader.of(
                env("JIRA_URL", JIRA_URL, "JIRA_EMAIL", EMAIL, "JIRA_TOKEN", TOKEN),
                null, envFile, null).load();

        assertEquals(JIRA_URL, config.getJiraUrl());
    }

    @Test
    public void envFileOverridesPropertiesFile() throws IOException {
        Path envFile = writeFile(".env", "JIRA_URL=https://from-env-file.atlassian.net\n"
                + "JIRA_EMAIL=" + EMAIL + "\n"
                + "JIRA_TOKEN=" + TOKEN + "\n");
        Path propertiesFile = writeFile("application.properties",
                "jira.url=https://from-properties.atlassian.net\n");

        AppConfig config = ConfigLoader.of(env(), null, envFile, propertiesFile).load();

        assertEquals("https://from-env-file.atlassian.net", config.getJiraUrl());
    }

    @Test
    public void propertiesFileProvidesValues() throws IOException {
        Path propertiesFile = writeFile("application.properties",
                "jira.url=" + JIRA_URL + "\n"
                        + "jira.email=" + EMAIL + "\n"
                        + "jira.token=" + TOKEN + "\n");

        AppConfig config = ConfigLoader.of(env(), null, null, propertiesFile).load();

        assertEquals(JIRA_URL, config.getJiraUrl());
        assertEquals(EMAIL, config.getJiraEmail());
        assertEquals(TOKEN, config.getJiraToken());
    }

    @Test
    public void propertiesFileAcceptsEnvironmentStyleKeys() throws IOException {
        Path propertiesFile = writeFile("application.properties",
                "JIRA_URL=" + JIRA_URL + "\n"
                        + "JIRA_EMAIL=" + EMAIL + "\n"
                        + "JIRA_TOKEN=" + TOKEN + "\n");

        AppConfig config = ConfigLoader.of(env(), null, null, propertiesFile).load();

        assertEquals(JIRA_URL, config.getJiraUrl());
        assertEquals(EMAIL, config.getJiraEmail());
        assertEquals(TOKEN, config.getJiraToken());
    }

    @Test
    public void blankEnvironmentValueDoesNotOverrideEnvFile() throws IOException {
        Path envFile = writeFile(".env", "JIRA_URL=https://from-env-file.atlassian.net\n");

        AppConfig config = ConfigLoader.of(
                env("JIRA_URL", "   ", "JIRA_EMAIL", EMAIL, "JIRA_TOKEN", TOKEN),
                null, envFile, null).load();

        assertEquals("https://from-env-file.atlassian.net", config.getJiraUrl());
    }


    @Test
    public void confluenceUrlFallsBackToJiraUrlWithWikiPath() {
        AppConfig config = ConfigLoader.of(
                env("JIRA_URL", JIRA_URL, "JIRA_EMAIL", EMAIL, "JIRA_TOKEN", TOKEN),
                null, null, null).load();

        assertNull(config.getConfluenceUrl());
        assertEquals("https://company.atlassian.net/wiki", config.resolveConfluenceUrl());
    }

    @Test
    public void confluenceUrlIsTakenFromPropertiesFile() throws IOException {
        Path propertiesFile = writeFile("application.properties",
                "jira.url=" + JIRA_URL + "\n"
                        + "jira.email=" + EMAIL + "\n"
                        + "jira.token=" + TOKEN + "\n"
                        + "confluence.url=https://company.atlassian.net/wiki\n");

        AppConfig config = ConfigLoader.of(env(), null, null, propertiesFile).load();

        assertEquals("https://company.atlassian.net/wiki", config.resolveConfluenceUrl());
    }

    @Test
    public void missingTokenIsRejectedWithVariableName() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.of(env("JIRA_URL", JIRA_URL, "JIRA_EMAIL", EMAIL), null, null, null).load());

        assertTrue(exception.getMessage().contains("JIRA_TOKEN"));
    }

    @Test
    public void missingJiraUrlIsRejectedWithVariableName() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.of(env("JIRA_EMAIL", EMAIL, "JIRA_TOKEN", TOKEN), null, null, null).load());

        assertTrue(exception.getMessage().contains("JIRA_URL"));
    }

    @Test
    public void locateFindsFileInWorkingDirectory() throws IOException {
        Path workingDirectory = folder.newFolder("work").toPath();
        Path file = workingDirectory.resolve("application.properties");
        Files.writeString(file, "jira.url=" + JIRA_URL + "\n", StandardCharsets.UTF_8);

        Path found = ConfigLoader.locate(null, "application.properties", workingDirectory, null);

        assertEquals(file, found);
    }

    @Test
    public void locatePrefersExplicitPath() throws IOException {
        Path workingDirectory = folder.newFolder("work").toPath();
        Files.writeString(workingDirectory.resolve("application.properties"), "jira.url=x\n",
                StandardCharsets.UTF_8);
        Path explicit = writeFile("custom.properties", "jira.url=" + JIRA_URL + "\n");

        Path found = ConfigLoader.locate(explicit.toString(), "application.properties", workingDirectory, null);

        assertEquals(explicit, found);
    }

    @Test
    public void locateReturnsNullWhenFileIsMissing() {
        Path found = ConfigLoader.locate(null, "missing.properties", folder.getRoot().toPath(), null);

        assertNull(found);
    }

    private Map<String, String> env(String... pairs) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    private Path writeFile(String name, String content) throws IOException {
        Path file = folder.newFile(name).toPath();
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
