package ru.solomentsev.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import ru.solomentsev.protocol.McpProtocol.ToolDefinition;
import ru.solomentsev.service.ConfluenceService;
import ru.solomentsev.service.JiraService;

/**
 * Каталог инструментов MCP-сервера.
 *
 * <p>Отвечает за регистрацию всех доступных инструментов и их схем
 * в протоколе. Добавление нового инструмента не требует изменений
 * в {@link McpProtocol} (принцип открытости/закрытости).</p>
 */
public final class ToolRegistrar {

    private ToolRegistrar() {
        // utility class
    }

    /**
     * Регистрирует все 13 инструментов MCP в протоколе.
     *
     * @param protocol          протокол MCP
     * @param jiraService       сервис Jira
     * @param confluenceService сервис Confluence
     */
    public static void register(McpProtocol protocol,
                                JiraService jiraService,
                                ConfluenceService confluenceService) {

        // --- Jira ---

        protocol.addTool(new ToolDefinition(
                "jira_get_issue",
                "Получить краткую информацию о задаче Jira (заголовок, описание, статус)",
                ToolSchema.create()
                        .requiredString("issue_key", "Ключ задачи (например, PROJ-123)")
                        .build(),
                args -> jiraService.getIssue(required(args, "issue_key"))));

        protocol.addTool(new ToolDefinition(
                "jira_search",
                "Поиск задач Jira по JQL запросу",
                ToolSchema.create()
                        .requiredString("jql", "JQL запрос (например, project = PROJ AND status = 'Open')")
                        .optionalInteger("limit", "Максимальное количество результатов (по умолчанию 5)")
                        .build(),
                args -> jiraService.search(
                        required(args, "jql"),
                        args.has("limit") ? args.get("limit").asInt() : 5)));

        protocol.addTool(new ToolDefinition(
                "jira_create_issue",
                "Создать новую задачу или баг в Jira",
                ToolSchema.create()
                        .requiredString("project_key", "Ключ проекта (например, PROJ)")
                        .requiredString("summary", "Заголовок задачи")
                        .requiredString("description", "Описание задачи")
                        .optionalString("issue_type", "Тип задачи (Bug, Task, Story). По умолчанию Bug")
                        .build(),
                args -> jiraService.createIssue(
                        required(args, "project_key"),
                        required(args, "summary"),
                        required(args, "description"),
                        args.has("issue_type") ? args.get("issue_type").asText() : "Bug")));

        protocol.addTool(new ToolDefinition(
                "jira_transition",
                "Перевести задачу в другой статус",
                ToolSchema.create()
                        .requiredString("issue_key", "Ключ задачи")
                        .requiredString("transition_name", "Название статуса (например, 'In Progress', 'Done')")
                        .build(),
                args -> jiraService.transition(
                        required(args, "issue_key"),
                        required(args, "transition_name"))));

        protocol.addTool(new ToolDefinition(
                "confluence_get_page",
                "Прочитать страницу Confluence по ID",
                ToolSchema.create()
                        .requiredString("page_id", "ID страницы Confluence")
                        .build(),
                args -> confluenceService.getPage(required(args, "page_id"))));

        protocol.addTool(new ToolDefinition(
                "jira_add_comment",
                "Добавить комментарий к задаче Jira",
                ToolSchema.create()
                        .requiredString("issue_key", "Ключ задачи (например, PROJ-123)")
                        .requiredString("comment", "Текст комментария")
                        .build(),
                args -> jiraService.addComment(
                        required(args, "issue_key"),
                        required(args, "comment"))));

        protocol.addTool(new ToolDefinition(
                "jira_get_project",
                "Получить информацию о проекте Jira",
                ToolSchema.create()
                        .requiredString("project_key", "Ключ проекта (например, PROJ)")
                        .build(),
                args -> jiraService.getProject(required(args, "project_key"))));

        protocol.addTool(new ToolDefinition(
                "jira_get_user",
                "Получить информацию о пользователе Jira",
                ToolSchema.create()
                        .requiredString("user_identifier", "Ключ пользователя или имя пользователя")
                        .build(),
                args -> jiraService.getUser(required(args, "user_identifier"))));

        protocol.addTool(new ToolDefinition(
                "confluence_list_spaces",
                "Получить список всех пространств Confluence",
                ToolSchema.create()
                        .optionalInteger("limit", "Максимальное количество пространств (по умолчанию 25)")
                        .build(),
                args -> confluenceService.listSpaces(
                        args.has("limit") ? args.get("limit").asInt() : 25)));

        protocol.addTool(new ToolDefinition(
                "confluence_get_space",
                "Получить информацию о пространстве Confluence по ключу",
                ToolSchema.create()
                        .requiredString("space_key", "Ключ пространства Confluence")
                        .build(),
                args -> confluenceService.getSpace(required(args, "space_key"))));

        protocol.addTool(new ToolDefinition(
                "confluence_search",
                "Поиск страниц Confluence по CQL запросу",
                ToolSchema.create()
                        .requiredString("cql", "CQL запрос (например, space = 'TEST' AND title ~ 'документ')")
                        .optionalInteger("limit", "Максимальное количество результатов (по умолчанию 5)")
                        .build(),
                args -> confluenceService.search(
                        required(args, "cql"),
                        args.has("limit") ? args.get("limit").asInt() : 5)));

        protocol.addTool(new ToolDefinition(
                "confluence_create_page",
                "Создать новую страницу Confluence",
                ToolSchema.create()
                        .requiredString("space_key", "Ключ пространства")
                        .requiredString("title", "Заголовок страницы")
                        .requiredString("content", "HTML-содержимое страницы")
                        .optionalString("parent_id", "ID родительской страницы (опционально)")
                        .build(),
                args -> confluenceService.createPage(
                        required(args, "space_key"),
                        required(args, "title"),
                        required(args, "content"),
                        args.has("parent_id") ? args.get("parent_id").asText() : null)));

        protocol.addTool(new ToolDefinition(
                "confluence_update_page",
                "Обновить существующую страницу Confluence",
                ToolSchema.create()
                        .requiredString("page_id", "ID страницы Confluence")
                        .optionalInteger("version", "Текущая версия страницы. Если не указана, определяется автоматически")
                        .optionalString("title", "Новый заголовок страницы (опционально)")
                        .optionalString("content", "Новое HTML-содержимое страницы (опционально)")
                        .build(),
                args -> {
                    String pageId = required(args, "page_id");
                    String title = args.has("title") ? args.get("title").asText() : null;
                    String content = args.has("content") ? args.get("content").asText() : null;
                    if (title == null && content == null) {
                        throw new IllegalArgumentException(
                                "Нужно передать хотя бы одно из полей: title или content");
                    }
                    Integer version = args.has("version") ? args.get("version").asInt() : null;
                    return confluenceService.updatePage(pageId, title, content, version);
                }));
    
    }

    /**
     * Извлекает обязательный строковый параметр из аргументов вызова.
     *
     * @param args аргументы вызова инструмента
     * @param key  имя параметра
     * @return значение параметра
     * @throws IllegalArgumentException если параметр не задан
     */
    private static String required(JsonNode args, String key) {
        if (!args.has(key) || args.get(key).isNull()) {
            throw new IllegalArgumentException("Обязательный параметр не задан: " + key);
        }
        return args.get(key).asText();
    }
}
