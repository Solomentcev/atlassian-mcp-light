package ru.solomentsev.core.config;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Тесты парсера dotenv: комментарии, префикс {@code export}, кавычки.
 */
public class DotEnvParserTest {

    @Test
    public void parsesSimpleAssignment() {
        Map<String, String> values = DotEnvParser.parse("JIRA_URL=https://company.atlassian.net");

        assertEquals("https://company.atlassian.net", values.get("JIRA_URL"));
    }

    @Test
    public void skipsCommentsAndBlankLines() {
        String content = "# комментарий\n"
                + "\n"
                + "JIRA_EMAIL=user@example.com\n"
                + "   \n";

        Map<String, String> values = DotEnvParser.parse(content);

        assertEquals(1, values.size());
        assertEquals("user@example.com", values.get("JIRA_EMAIL"));
    }

    @Test
    public void supportsExportPrefix() {
        Map<String, String> values = DotEnvParser.parse("export JIRA_TOKEN=secret-token");

        assertEquals("secret-token", values.get("JIRA_TOKEN"));
    }

    @Test
    public void keepsVariableWhoseNameStartsWithExport() {
        Map<String, String> values = DotEnvParser.parse("EXPORTED_KEY=value");

        assertEquals("value", values.get("EXPORTED_KEY"));
    }

    @Test
    public void removesDoubleQuotes() {
        Map<String, String> values = DotEnvParser.parse("JIRA_URL=\"https://company.atlassian.net\"");

        assertEquals("https://company.atlassian.net", values.get("JIRA_URL"));
    }

    @Test
    public void removesSingleQuotes() {
        Map<String, String> values = DotEnvParser.parse("JIRA_URL='https://company.atlassian.net'");

        assertEquals("https://company.atlassian.net", values.get("JIRA_URL"));
    }

    @Test
    public void keepsHashInsideQuotedValue() {
        Map<String, String> values = DotEnvParser.parse("JIRA_URL=\"https://company.atlassian.net/#anchor\"");

        assertEquals("https://company.atlassian.net/#anchor", values.get("JIRA_URL"));
    }

    @Test
    public void stripsInlineComment() {
        Map<String, String> values = DotEnvParser.parse("JIRA_EMAIL=user@example.com # рабочий почтовый ящик");

        assertEquals("user@example.com", values.get("JIRA_EMAIL"));
    }

    @Test
    public void keepsHashWithoutPrecedingWhitespace() {
        Map<String, String> values = DotEnvParser.parse("JIRA_EMAIL=user@exa#mple.com");

        assertEquals("user@exa#mple.com", values.get("JIRA_EMAIL"));
    }

    @Test
    public void ignoresLinesWithoutSeparator() {
        Map<String, String> values = DotEnvParser.parse("просто текст\nJIRA_TOKEN=secret-token");

        assertEquals(1, values.size());
        assertFalse(values.containsKey("просто текст"));
    }

    @Test
    public void splitsByFirstEqualsSign() {
        Map<String, String> values = DotEnvParser.parse("JIRA_TOKEN=abc=def");

        assertEquals("abc=def", values.get("JIRA_TOKEN"));
    }

    @Test
    public void readsFileFromDisk() throws IOException {
        Path file = Files.createTempFile("dotenv", ".env");
        Files.writeString(file, "JIRA_URL=https://company.atlassian.net\n", StandardCharsets.UTF_8);

        Map<String, String> values = DotEnvParser.parse(file);

        assertEquals("https://company.atlassian.net", values.get("JIRA_URL"));
        Files.deleteIfExists(file);
    }

    @Test
    public void nullContentProducesEmptyMap() {
        assertTrue(DotEnvParser.parse((String) null).isEmpty());
    }
}
