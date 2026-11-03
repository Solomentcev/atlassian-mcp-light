package ru.solomentsev.core.config;

import org.junit.Test;

import java.util.Base64;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Тесты {@link AppConfig}: нормализация URL, fallback для Confluence,
 * Basic-аутентификация и безопасность вывода секретов.
 */
public class AppConfigTest {

    private static final String JIRA_URL = "https://company.atlassian.net";
    private static final String EMAIL = "user@example.com";
    private static final String TOKEN = "secret-token";

    @Test
    public void jiraUrlIsNormalizedByRemovingTrailingSlash() {
        AppConfig config = AppConfig.of("https://company.atlassian.net/", null, EMAIL, TOKEN);

        assertEquals("https://company.atlassian.net", config.getJiraUrl());
    }

    @Test
    public void confluenceUrlIsNormalizedByRemovingTrailingSlash() {
        AppConfig config = AppConfig.of(JIRA_URL, "https://company.atlassian.net/wiki/", EMAIL, TOKEN);

        assertEquals("https://company.atlassian.net/wiki", config.getConfluenceUrl());
    }

    @Test
    public void confluenceUrlFallsBackToJiraUrlWithWikiPath() {
        AppConfig config = AppConfig.of(JIRA_URL, null, EMAIL, TOKEN);

        assertNull(config.getConfluenceUrl());
        assertEquals("https://company.atlassian.net/wiki", config.resolveConfluenceUrl());
    }

    @Test
    public void explicitConfluenceUrlIsPreferredOverFallback() {
        AppConfig config = AppConfig.of(JIRA_URL, "https://confluence.example.com", EMAIL, TOKEN);

        assertEquals("https://confluence.example.com", config.resolveConfluenceUrl());
    }

    @Test
    public void authHeaderIsBuiltWithBase64EncodedCredentials() {
        AppConfig config = AppConfig.of(JIRA_URL, null, EMAIL, TOKEN);

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((EMAIL + ":" + TOKEN).getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, config.getAuthHeader());
    }

    @Test
    public void missingJiraUrlIsRejected() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> AppConfig.of(null, null, EMAIL, TOKEN));

        assertTrue(exception.getMessage().contains("jiraUrl"));
    }

    @Test
    public void blankEmailIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> AppConfig.of(JIRA_URL, null, "  ", TOKEN));
    }

    @Test
    public void blankTokenIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> AppConfig.of(JIRA_URL, null, EMAIL, ""));
    }

    @Test
    public void responseSizeLimitsUseDefaultsWhenNotConfigured() {
        AppConfig config = AppConfig.of(JIRA_URL, null, EMAIL, TOKEN);

        assertEquals(AppConfig.DEFAULT_MAX_TEXT_LENGTH, config.getMaxTextLength());
        assertEquals(AppConfig.DEFAULT_MAX_RESPONSE_BYTES, config.getMaxResponseBytes());
    }

    @Test
    public void customResponseSizeLimitsAreStored() {
        AppConfig config = AppConfig.of(JIRA_URL, null, EMAIL, TOKEN, 2500, 1024);

        assertEquals(2500, config.getMaxTextLength());
        assertEquals(1024, config.getMaxResponseBytes());
    }

    @Test
    public void nonPositiveResponseSizeLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.of(JIRA_URL, null, EMAIL, TOKEN, 0, 1024));
        assertThrows(IllegalArgumentException.class,
                () -> AppConfig.of(JIRA_URL, null, EMAIL, TOKEN, 1000, 0));
    }

    @Test
    public void toStringDoesNotLeakCredentials() {
        AppConfig config = AppConfig.of(JIRA_URL, null, EMAIL, TOKEN);

        String text = config.toString();
        assertFalse(text.contains(TOKEN));
        assertFalse(text.contains(EMAIL));
        assertTrue(text.contains(JIRA_URL));
    }

    @Test
    public void configsWithSameValuesAreEqual() {
        AppConfig first = AppConfig.of(JIRA_URL, null, EMAIL, TOKEN);
        AppConfig second = AppConfig.of(JIRA_URL, null, EMAIL, TOKEN);

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    public void configsWithDifferentTokensAreNotEqual() {
        AppConfig first = AppConfig.of(JIRA_URL, null, EMAIL, TOKEN);
        AppConfig second = AppConfig.of(JIRA_URL, null, EMAIL, "other-token");

        assertNotEquals(first, second);
    }
}
