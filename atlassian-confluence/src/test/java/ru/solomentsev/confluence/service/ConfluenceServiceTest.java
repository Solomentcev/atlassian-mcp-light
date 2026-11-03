package ru.solomentsev.confluence.service;

import org.junit.Before;
import org.junit.Test;
import ru.solomentsev.core.client.FakeHttpClient;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Тесты {@link ConfluenceService} через подставной {@link FakeHttpClient}.
 */
public class ConfluenceServiceTest {

    private static final String BASE_URL = "https://company.atlassian.net/wiki";

    private FakeHttpClient client;
    private ConfluenceService service;

    @Before
    public void setUp() {
        client = new FakeHttpClient();
        service = new ConfluenceService(client, BASE_URL);
    }

    @Test
    public void listSpacesAndSearchRejectLimitsOutsideSupportedRange() {
        assertThrows(IllegalArgumentException.class, () -> service.listSpaces(0));
        assertThrows(IllegalArgumentException.class, () -> service.listSpaces(101));
        assertThrows(IllegalArgumentException.class, () -> service.search("space = 'TEST'", 0));
        assertThrows(IllegalArgumentException.class, () -> service.search("space = 'TEST'", 101));
    }

    @Test
    public void getPageEncodesPageIdAsSinglePathSegment() throws IOException {
        String url = BASE_URL + "/rest/api/content/12%2F3?expand=body.storage,version";
        client.onGet(url,
                "{\"id\":\"12/3\",\"title\":\"Page\","
                        + "\"body\":{\"storage\":{\"value\":\"Body\"}}}");

        service.getPage("12/3");

        assertEquals(url, client.getLastRequest().url());
    }

    @Test
    public void getPageReturnsTitleAndContent() throws IOException {
        client.onGet(BASE_URL + "/rest/api/content/123?expand=body.storage,version",
                "{\"id\":\"123\",\"title\":\"My page\","
                        + "\"body\":{\"storage\":{\"value\":\"<p>Body</p>\"}}}");

        String output = service.getPage("123");

        assertEquals("Title: My page\nContent: <p>Body</p>", output);
    }

    @Test
    public void getPageTruncatesLongContent() throws IOException {
        client.onGet(BASE_URL + "/rest/api/content/123?expand=body.storage,version",
                "{\"id\":\"123\",\"title\":\"My page\","
                        + "\"body\":{\"storage\":{\"value\":\"" + "z".repeat(5000) + "\"}}}");

        String output = service.getPage("123");

        assertTrue(output.endsWith("..."));
        assertEquals("Title: My page\nContent: ".length() + 4003, output.length());
    }

    @Test
    public void configuredTextLimitTruncatesPageFields() throws IOException {
        client.onGet(BASE_URL + "/rest/api/content/123?expand=body.storage,version",
                "{\"id\":\"123\",\"title\":\"Long title\","
                        + "\"body\":{\"storage\":{\"value\":\"Long content\"}}}");
        ConfluenceService limited = new ConfluenceService(client, BASE_URL, 4);

        String output = limited.getPage("123");

        assertEquals("Title: Long...\nContent: Long...", output);
    }

    @Test
    public void createPageSendsTitleAsStringAndReturnsPageId() throws IOException {
        client.onPost(BASE_URL + "/rest/api/content", "{\"id\":\"999\",\"title\":\"New page\"}");

        String output = service.createPage("TEST", "New page", "<p>Body</p>", null);

        assertEquals("Страница создана: ID 999 - New page (пространство TEST)", output);

        String body = client.getLastRequest().body();
        assertTrue(body.contains("\"type\":\"page\""));
        assertTrue(body.contains("\"title\":\"New page\""));
        assertTrue(body.contains("\"space\":{\"key\":\"TEST\"}"));
        assertTrue(body.contains("\"value\":\"<p>Body</p>\""));
        assertTrue(body.contains("\"representation\":\"storage\""));
        assertTrue(body.contains("\"ancestors\":[]"));
    }

    @Test
    public void createPageAddsParentAsAncestor() throws IOException {
        client.onPost(BASE_URL + "/rest/api/content", "{\"id\":\"999\"}");

        service.createPage("TEST", "New page", "<p>Body</p>", "111");

        String body = client.getLastRequest().body();
        assertTrue(body.contains("\"ancestors\":[{\"id\":\"111\"}]"));
    }

    @Test
    public void updatePageIncrementsCurrentVersion() throws IOException {
        client.onGet(BASE_URL + "/rest/api/content/123?expand=body.storage,version",
                "{\"id\":\"123\",\"version\":{\"number\":4}}");
        client.onPut(BASE_URL + "/rest/api/content/123", "{}");

        String output = service.updatePage("123", "New title", "<p>New</p>", null);

        assertEquals("Страница обновлена: ID 123, новая версия: 5", output);

        String body = client.getLastRequest().body();
        assertTrue(body.contains("\"version\":{\"number\":5}"));
        assertTrue(body.contains("\"title\":\"New title\""));
        assertTrue(body.contains("\"representation\":\"storage\""));
    }

    @Test
    public void updatePageIncrementsExplicitVersionWhenServerResponseHasNoVersion() throws IOException {
        client.onGet(BASE_URL + "/rest/api/content/123?expand=body.storage,version",
                "{\"id\":\"123\"}");
        client.onPut(BASE_URL + "/rest/api/content/123", "{}");

        service.updatePage("123", "New title", "<p>New</p>", 4);

        String body = client.getLastRequest().body();
        assertTrue(body.contains("\"version\":{\"number\":5}"));
    }

    @Test
    public void updatePageOmitsAbsentFields() throws IOException {
        client.onGet(BASE_URL + "/rest/api/content/123?expand=body.storage,version",
                "{\"id\":\"123\",\"version\":{\"number\":1}}");
        client.onPut(BASE_URL + "/rest/api/content/123", "{}");

        service.updatePage("123", "Only title", null, null);

        String body = client.getLastRequest().body();
        assertTrue(body.contains("\"title\":\"Only title\""));
        assertTrue(!body.contains("\"body\""));
    }

    @Test
    public void getSpaceReturnsKeyNameTypeAndDescription() throws IOException {
        client.onGet(BASE_URL + "/rest/api/space/TEST",
                "{\"key\":\"TEST\",\"name\":\"Test space\",\"type\":\"global\","
                        + "\"description\":{\"value\":\"Space description\"}}");

        String output = service.getSpace("TEST");

        assertEquals("Пространство: TEST - Test space\nТип: global\nОписание: Space description",
                output);
    }

    @Test
    public void getSpaceFallsBackToNotAvailableForMissingFields() throws IOException {
        client.onGet(BASE_URL + "/rest/api/space/TEST", "{\"key\":\"TEST\"}");

        String output = service.getSpace("TEST");

        assertEquals("Пространство: TEST - \nТип: N/A\nОписание: N/A", output);
    }

    @Test
    public void listSpacesReturnsHeaderAndItems() throws IOException {
        client.onGet(BASE_URL + "/rest/api/space?limit=25",
                "{\"results\":[{\"key\":\"TEST\",\"name\":\"Test space\"}]}");

        String output = service.listSpaces(25);

        assertEquals("Пространства Confluence (лимит: 25):\n- TEST: Test space\n", output);
    }

    @Test
    public void listSpacesFollowsNextPageLinkWithoutExceedingLimit() throws IOException {
        String firstUrl = BASE_URL + "/rest/api/space?limit=2";
        String secondUrl = firstUrl + "&start=1";
        client.onGet(firstUrl,
                "{\"results\":[{\"key\":\"ONE\",\"name\":\"First space\"}],"
                        + "\"_links\":{\"next\":\"/rest/api/space?start=1\"}}");
        client.onGet(secondUrl,
                "{\"results\":[{\"key\":\"TWO\",\"name\":\"Second space\"}]}");

        String output = service.listSpaces(2);

        assertTrue(output.contains("ONE: First space"));
        assertTrue(output.contains("TWO: Second space"));
        assertEquals(2, client.getRequests().size());
    }

    @Test
    public void searchEncodesCqlQueryInUrl() throws IOException {
        String url = BASE_URL + "/rest/api/content?cql=space+%3D+%27TEST%27&limit=5";
        client.onGet(url, "{\"results\":[]}");

        service.search("space = 'TEST'", 5);

        assertEquals(url, client.getLastRequest().url());
    }

    @Test
    public void searchReturnsFormattedResults() throws IOException {
        client.onGet(BASE_URL + "/rest/api/content?cql=space+%3D+%27TEST%27&limit=5",
                "{\"results\":[{\"id\":\"123\",\"title\":\"Found page\","
                        + "\"space\":{\"key\":\"TEST\"}}]}");

        String output = service.search("space = 'TEST'", 5);

        assertEquals("Результаты поиска CQL: space = 'TEST' (лимит: 5):\n- TEST:123 - Found page\n",
                output);
    }

    @Test
    public void searchFollowsNextPageLinkUntilLimitIsReached() throws IOException {
        String firstUrl = BASE_URL + "/rest/api/content?cql=space+%3D+%27TEST%27&limit=5";
        String secondUrl = firstUrl + "&start=1";
        client.onGet(firstUrl,
                "{\"results\":[{\"id\":\"123\",\"title\":\"First page\","
                        + "\"space\":{\"key\":\"TEST\"}}],"
                        + "\"_links\":{\"next\":\"/rest/api/content?start=1\"}}");
        client.onGet(secondUrl,
                "{\"results\":[{\"id\":\"456\",\"title\":\"Second page\","
                        + "\"space\":{\"key\":\"TEST\"}}]}");

        String output = service.search("space = 'TEST'", 5);

        assertTrue(output.contains("TEST:123 - First page"));
        assertTrue(output.contains("TEST:456 - Second page"));
        assertEquals(2, client.getRequests().size());
        assertEquals(secondUrl, client.getLastRequest().url());
    }

    @Test
    public void trailingSlashInBaseUrlIsRemoved() throws IOException {
        FakeHttpClient localClient = new FakeHttpClient();
        localClient.onGet("https://company.atlassian.net/wiki/rest/api/space/TEST",
                "{\"key\":\"TEST\"}");
        ConfluenceService local = new ConfluenceService(localClient, BASE_URL + "/");

        local.getSpace("TEST");

        assertEquals("https://company.atlassian.net/wiki/rest/api/space/TEST",
                localClient.getLastRequest().url());
    }
}
