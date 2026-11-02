package ru.solomentsev.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import ru.solomentsev.client.FakeHttpClient;
import ru.solomentsev.client.HttpClient;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Тесты {@link JiraService} через подставной {@link FakeHttpClient}.
 *
 * <p>Проверяют формирование запросов и разбор ответов Jira REST API.</p>
 */
public class JiraServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE_URL = "https://company.atlassian.net";

    private FakeHttpClient client;
    private JiraService service;

    @Before
    public void setUp() {
        client = new FakeHttpClient();
        service = new JiraService(client, BASE_URL);
    }

    @Test
    public void getIssueReturnsBriefInformation() throws IOException {
        client.onGet(BASE_URL + "/rest/api/2/issue/PROJ-1?fields=summary,description,status,issuetype",
                issueResponse());

        JsonNode root = MAPPER.readTree(service.getIssue("PROJ-1"));

        assertEquals("PROJ-1", root.get("key").asText());
        assertEquals("Task A", root.get("summary").asText());
        assertEquals("In Progress", root.get("status").asText());
        assertEquals("Story", root.get("type").asText());
        assertEquals("Description here", root.get("description_snippet").asText());
    }

    @Test
    public void getIssueTruncatesLongDescription() throws IOException {
        String longDescription = "x".repeat(5000);
        client.onGet(BASE_URL + "/rest/api/2/issue/PROJ-1?fields=summary,description,status,issuetype",
                "{\"key\":\"PROJ-1\",\"fields\":{\"summary\":\"Task A\","
                        + "\"description\":\"" + longDescription + "\"}}");

        JsonNode root = MAPPER.readTree(service.getIssue("PROJ-1"));

        assertEquals(4003, root.get("description_snippet").asText().length());
        assertTrue(root.get("description_snippet").asText().endsWith("..."));
    }

    @Test
    public void configuredTextLimitTruncatesJiraFieldsWithoutBreakingJson() throws IOException {
        client.onGet(BASE_URL + "/rest/api/2/issue/PROJ-1?fields=summary,description,status,issuetype",
                issueResponse());
        JiraService limited = new JiraService(client, BASE_URL, 4);

        JsonNode root = MAPPER.readTree(limited.getIssue("PROJ-1"));

        assertEquals("Task...", root.get("summary").asText());
        assertEquals("Desc...", root.get("description_snippet").asText());
        assertEquals("In P...", root.get("status").asText());
    }

    @Test
    public void searchSendsJqlAndMaxResults() throws IOException {
        client.onPost(BASE_URL + "/rest/api/3/search/jql",
                "{\"issues\":[{\"key\":\"PROJ-1\",\"fields\":{\"summary\":\"Found\","
                        + "\"status\":{\"name\":\"Open\"}}}]}");

        JsonNode root = MAPPER.readTree(service.search("project = PROJ", 3));

        assertEquals(1, root.size());
        assertEquals("PROJ-1", root.get(0).get("key").asText());
        assertEquals("Found", root.get(0).get("summary").asText());
        assertEquals("Open", root.get(0).get("status").asText());

        String body = client.getLastRequest().body();
        assertTrue(body.contains("\"jql\":\"project = PROJ\""));
        assertTrue(body.contains("\"maxResults\":3"));
        assertTrue(body.contains("\"fields\":[\"summary\",\"status\"]"));
    }

    @Test
    public void searchAddsDateRestrictionForUnboundedJql() throws IOException {
        client.onPost(BASE_URL + "/rest/api/3/search/jql", "{\"issues\":[]}");

        service.search("ORDER BY created DESC", 5);

        String body = client.getLastRequest().body();
        assertTrue("Неограниченный JQL должен получить ограничение по дате, получено: " + body,
                body.contains("\"jql\":\"created >= -30d ORDER BY created DESC\""));
    }

    @Test
    public void searchKeepsJqlWithFilterUntouched() throws IOException {
        client.onPost(BASE_URL + "/rest/api/3/search/jql", "{\"issues\":[]}");

        service.search("project = PROJ ORDER BY created DESC", 5);

        String body = client.getLastRequest().body();
        assertTrue("JQL с условием фильтрации не должен изменяться, получено: " + body,
                body.contains("\"jql\":\"project = PROJ ORDER BY created DESC\""));
    }

    @Test
    public void createIssueReturnsCreatedKeyAndStatus() throws IOException {
        client.onPost(BASE_URL + "/rest/api/2/issue", "{\"id\":\"10001\",\"key\":\"PROJ-9\"}");

        JsonNode root = MAPPER.readTree(service.createIssue("PROJ", "Summary", "Description", "Task"));

        assertEquals("PROJ-9", root.get("created_key").asText());
        assertEquals("success", root.get("status").asText());

        String body = client.getLastRequest().body();
        assertTrue(body.contains("\"key\":\"PROJ\""));
        assertTrue(body.contains("\"summary\":\"Summary\""));
        assertTrue(body.contains("\"name\":\"Task\""));
    }

    @Test
    public void createIssueTruncatesLongDescription() throws IOException {
        client.onPost(BASE_URL + "/rest/api/2/issue", "{\"key\":\"PROJ-9\"}");

        service.createIssue("PROJ", "Summary", "y".repeat(5000), "Task");

        String body = client.getLastRequest().body();
        assertTrue(body.contains("y".repeat(4000)));
        assertTrue(!body.contains("y".repeat(4001)));
    }

    @Test
    public void transitionFindsTransitionByCaseInsensitiveSubstring() throws IOException {
        client.onGet(BASE_URL + "/rest/api/2/issue/PROJ-1/transitions",
                "{\"transitions\":[{\"id\":\"11\",\"to\":{\"name\":\"Done\"}},\n"
                        + "{\"id\":\"21\",\"to\":{\"name\":\"In Progress\"}}]} ");
        client.onPost(BASE_URL + "/rest/api/2/issue/PROJ-1/transitions", "{}");

        JsonNode root = MAPPER.readTree(service.transition("PROJ-1", "progress"));

        assertEquals("PROJ-1", root.get("key").asText());
        assertEquals("success", root.get("status").asText());

        String body = client.getLastRequest().body();
        assertTrue(body.contains("\"id\":\"21\""));
    }

    @Test
    public void transitionFailsWhenNoTransitionMatches() {
        client.onGet(BASE_URL + "/rest/api/2/issue/PROJ-1/transitions",
                "{\"transitions\":[{\"id\":\"11\",\"to\":{\"name\":\"Done\"}}]} ");

        try {
            service.transition("PROJ-1", "Nonexistent");
            fail("Ожидалось IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("не найден"));
        } catch (IOException e) {
            fail("Ожидалось IllegalArgumentException, получено IOException: " + e.getMessage());
        }
    }

    @Test
    public void addCommentSendsParagraphBody() throws IOException {
        client.onPost(BASE_URL + "/rest/api/2/issue/PROJ-1/comment", "{}");

        String output = service.addComment("PROJ-1", "Hello");

        assertEquals("Комментарий добавлен к задаче PROJ-1", output);
        String body = client.getLastRequest().body();
        assertTrue(body.contains("\"body\":\"Hello\""));
        assertTrue(!body.contains("\"type\":\"paragraph\""));
    }

    @Test
    public void getProjectReturnsProjectDescription() throws IOException {
        client.onGet(BASE_URL + "/rest/api/2/project/PROJ",
                "{\"key\":\"PROJ\",\"name\":\"Main project\",\"projectTypeKey\":\"software\"}");

        String output = service.getProject("PROJ");

        assertEquals("Проект: PROJ - Main project (software)", output);
    }

    @Test
    public void getUserFallsBackToNotAvailableWhenFieldsAreMissing() throws IOException {
        client.onGet(BASE_URL + "/rest/api/2/user/search?query=user%40example.com", "{}");

        String output = service.getUser("user@example.com");

        assertEquals("Пользователь: user@example.com - N/A (N/A)", output);
    }

    @Test
    public void getUserReturnsDisplayNameAndEmail() throws IOException {
        client.onGet(BASE_URL + "/rest/api/2/user/search?query=user%40example.com",
                "{\"displayName\":\"John Doe\",\"emailAddress\":\"john@example.com\"}");

        String output = service.getUser("user@example.com");

        assertEquals("Пользователь: user@example.com - John Doe (john@example.com)", output);
    }

    @Test
    public void getUserUsesAccountIdEndpointForAccountIdentifier() throws IOException {
        client.onGet(BASE_URL + "/rest/api/2/user?accountId=712020%3A8791cf9c-31e8-4f0b-8761-c21931ac4a8a",
                "{\"displayName\":\"Denis Solomentsev\",\"emailAddress\":\"solomentsev.denis@gmail.com\"}");

        String output = service.getUser("712020:8791cf9c-31e8-4f0b-8761-c21931ac4a8a");

        assertEquals("Пользователь: 712020:8791cf9c-31e8-4f0b-8761-c21931ac4a8a - Denis Solomentsev "
                        + "(solomentsev.denis@gmail.com)", output);
    }

    @Test
    public void getUserReportsNotFoundWhenNoMatchIsFound() throws IOException {
        client.onGet(BASE_URL + "/rest/api/2/user/search?query=nobody%40example.com", "[ ]");

        String output = service.getUser("nobody@example.com");

        assertEquals("Пользователь не найден: nobody@example.com", output);
    }

    @Test
    public void trailingSlashInBaseUrlIsRemoved() throws IOException {
        FakeHttpClient localClient = new FakeHttpClient();
        localClient.onGet(BASE_URL + "/rest/api/2/issue/PROJ-1?fields=summary,description,status,issuetype",
                issueResponse());
        JiraService local = new JiraService(localClient, BASE_URL + "/");

        local.getIssue("PROJ-1");

        assertEquals("/rest/api/2/issue/PROJ-1?fields=summary,description,status,issuetype",
                localClient.getLastRequest().url().substring(BASE_URL.length()));
    }

    private String issueResponse() {
        return "{\"key\":\"PROJ-1\",\"fields\":{\"summary\":\"Task A\","
                + "\"status\":{\"name\":\"In Progress\"},"
                + "\"issuetype\":{\"name\":\"Story\"},"
                + "\"description\":\"Description here\"}}";
    }
}
