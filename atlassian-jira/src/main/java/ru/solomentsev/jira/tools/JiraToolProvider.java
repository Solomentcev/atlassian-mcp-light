package ru.solomentsev.jira.tools;

import com.fasterxml.jackson.databind.JsonNode;
import ru.solomentsev.protocol.McpProtocol;
import ru.solomentsev.protocol.ToolProvider;
import ru.solomentsev.protocol.ToolSchema;
import ru.solomentsev.service.JiraService;

import java.util.Objects;

import static ru.solomentsev.protocol.McpProtocol.ToolDefinition;

/**
 * Регистрирует MCP-инструменты для работы с Jira.
 *
 * <p>Поставщик зависит только от Jira-сервиса и общего контракта регистрации
 * инструментов. Он не содержит кода Confluence и развивается независимо.</p>
 */
public final class JiraToolProvider implements ToolProvider {

    private final JiraService jiraService;

    /**
     * Создаёт поставщик инструментов Jira.
     *
     * @param jiraService сервис Jira
     * @throws NullPointerException если сервис не задан
     */
    public JiraToolProvider(JiraService jiraService) {
        this.jiraService = Objects.requireNonNull(jiraService, "jiraService");
    }

    /**
     * Регистрирует инструменты Jira в MCP-протоколе.
     *
     * @param protocol MCP-протокол
     */
    @Override
    public void register(McpProtocol protocol) {
        Objects.requireNonNull(protocol, "protocol");

        protocol.addTool(new ToolDefinition(
                "jira_get_issue",
                "Получить краткую информацию о задаче Jira (заголовок, описание, статус)",
                ToolSchema.create().requiredString("issue_key", "Ключ задачи (например, PROJ-123)").build(),
                args -> jiraService.getIssue(required(args, "issue_key"))));

        protocol.addTool(new ToolDefinition(
                "jira_search",
                "Поиск задач Jira по JQL запросу",
                ToolSchema.create()
                        .requiredString("jql", "JQL запрос (например, project = PROJ AND status = 'Open')")
                        .optionalInteger("limit", "Максимальное количество результатов (по умолчанию 5)")
                        .build(),
                args -> jiraService.search(required(args, "jql"),
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
                args -> jiraService.createIssue(required(args, "project_key"),
                        required(args, "summary"), required(args, "description"),
                        args.has("issue_type") ? args.get("issue_type").asText() : "Bug")));

        protocol.addTool(new ToolDefinition(
                "jira_transition",
                "Перевести задачу в другой статус",
                ToolSchema.create()
                        .requiredString("issue_key", "Ключ задачи")
                        .requiredString("transition_name", "Название статуса (например, 'In Progress', 'Done')")
                        .build(),
                args -> jiraService.transition(required(args, "issue_key"),
                        required(args, "transition_name"))));

        protocol.addTool(new ToolDefinition(
                "jira_add_comment",
                "Добавить комментарий к задаче Jira",
                ToolSchema.create()
                        .requiredString("issue_key", "Ключ задачи (например, PROJ-123)")
                        .requiredString("comment", "Текст комментария")
                        .build(),
                args -> jiraService.addComment(required(args, "issue_key"), required(args, "comment"))));

        protocol.addTool(new ToolDefinition(
                "jira_get_project",
                "Получить информацию о проекте Jira",
                ToolSchema.create().requiredString("project_key", "Ключ проекта (например, PROJ)").build(),
                args -> jiraService.getProject(required(args, "project_key"))));

        protocol.addTool(new ToolDefinition(
                "jira_get_user",
                "Получить информацию о пользователе Jira",
                ToolSchema.create()
                        .requiredString("user_identifier", "Ключ пользователя или имя пользователя")
                        .build(),
                args -> jiraService.getUser(required(args, "user_identifier"))));
    }

    /**
     * Извлекает обязательный строковый параметр из аргументов инструмента.
     *
     * @param args аргументы вызова
     * @param key имя параметра
     * @return значение параметра
     * @throws IllegalArgumentException если параметр отсутствует или равен null
     */
    private static String required(JsonNode args, String key) {
        if (args == null || !args.has(key) || args.get(key).isNull()) {
            throw new IllegalArgumentException("Обязательный параметр не задан: " + key);
        }
        return args.get(key).asText();
    }
}
