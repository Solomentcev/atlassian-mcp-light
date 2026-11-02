package ru.solomentsev.protocol;

import ru.solomentsev.confluence.tools.ConfluenceToolProvider;
import ru.solomentsev.jira.tools.JiraToolProvider;
import ru.solomentsev.service.ConfluenceService;
import ru.solomentsev.service.JiraService;

import java.util.List;

/**
 * Собирает поставщиков инструментов подключённых продуктов Atlassian.
 *
 * <p>Классы инструментов находятся в собственных модулях продуктов.
 * Для подключения нового продукта достаточно добавить его поставщика
 * в список, не меняя реализацию MCP-протокола.</p>
 */
public final class ToolRegistrar {

    private ToolRegistrar() {
        // utility class
    }

    /**
     * Регистрирует инструменты Jira и Confluence.
     *
     * @param protocol MCP-протокол
     * @param jiraService сервис Jira
     * @param confluenceService сервис Confluence
     */
    public static void register(McpProtocol protocol,
                                JiraService jiraService,
                                ConfluenceService confluenceService) {
        List<ToolProvider> providers = List.of(
                new JiraToolProvider(jiraService),
                new ConfluenceToolProvider(confluenceService));
        providers.forEach(provider -> provider.register(protocol));
    }
}
