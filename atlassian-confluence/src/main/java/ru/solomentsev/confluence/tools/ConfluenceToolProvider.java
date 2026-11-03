package ru.solomentsev.confluence.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.solomentsev.core.protocol.McpProtocol;
import ru.solomentsev.core.protocol.ToolProvider;
import ru.solomentsev.core.protocol.ToolSchema;
import ru.solomentsev.confluence.service.ConfluenceService;

import java.util.Objects;

import static ru.solomentsev.core.protocol.McpProtocol.ToolDefinition;

/**
 * Регистрирует MCP-инструменты для работы с Confluence.
 *
 * <p>Поставщик зависит только от Confluence-сервиса и общего контракта
 * регистрации инструментов. Он не содержит кода Jira и развивается независимо.</p>
 */
public final class ConfluenceToolProvider implements ToolProvider {

    private final ConfluenceService confluenceService;

    /**
     * Создаёт поставщик инструментов Confluence.
     *
     * @param confluenceService сервис Confluence
     * @throws NullPointerException если сервис не задан
     */
    public ConfluenceToolProvider(ConfluenceService confluenceService) {
        this.confluenceService = Objects.requireNonNull(confluenceService, "confluenceService");
    }

    /**
     * Регистрирует инструменты Confluence в MCP-протоколе.
     *
     * @param protocol MCP-протокол
     */
    @Override
    public void register(McpProtocol protocol) {
        Objects.requireNonNull(protocol, "protocol");

        protocol.addTool(new ToolDefinition(
                "confluence_get_page",
                "Прочитать страницу Confluence по ID",
                ToolSchema.create().requiredString("page_id", "ID страницы Confluence").build(),
                annotations(true, false, true),
                args -> confluenceService.getPage(required(args, "page_id"))));

        protocol.addTool(new ToolDefinition(
                "confluence_list_spaces",
                "Получить список всех пространств Confluence",
                ToolSchema.create()
                        .optionalInteger("limit", "Максимальное количество пространств (по умолчанию 25)", 1, 100)
                        .build(),
                annotations(true, false, true),
                args -> confluenceService.listSpaces(args.has("limit") ? args.get("limit").asInt() : 25)));

        protocol.addTool(new ToolDefinition(
                "confluence_get_space",
                "Получить информацию о пространстве Confluence по ключу",
                ToolSchema.create()
                        .requiredString("space_key", "Ключ пространства Confluence")
                        .build(),
                annotations(true, false, true),
                args -> confluenceService.getSpace(required(args, "space_key"))));

        protocol.addTool(new ToolDefinition(
                "confluence_search",
                "Поиск страниц Confluence по CQL запросу",
                ToolSchema.create()
                        .requiredString("cql", "CQL запрос (например, space = 'TEST' AND title ~ 'документ')")
                        .optionalInteger("limit", "Максимальное количество результатов (по умолчанию 5)", 1, 100)
                        .build(),
                annotations(true, false, true),
                args -> confluenceService.search(required(args, "cql"),
                        args.has("limit") ? args.get("limit").asInt() : 5)));

        protocol.addTool(new ToolDefinition(
                "confluence_create_page",
                "Создать новую страницу Confluence",
                ToolSchema.create()
                        .requiredString("space_key", "Ключ пространства")
                        .requiredString("title", "Заголовок страницы")
                        .requiredString("content", "Содержимое страницы в формате Confluence Storage")
                        .optionalString("parent_id", "ID родительской страницы (опционально)")
                        .build(),
                annotations(false, false, false),
                args -> confluenceService.createPage(required(args, "space_key"),
                        required(args, "title"), required(args, "content"),
                        args.has("parent_id") ? args.get("parent_id").asText() : null)));

        protocol.addTool(new ToolDefinition(
                "confluence_update_page",
                "Обновить существующую страницу Confluence",
                ToolSchema.create()
                        .requiredString("page_id", "ID страницы Confluence")
                        .optionalInteger("version", "Текущая версия страницы. Если не указана, определяется автоматически", 1, null)
                        .optionalString("title", "Новый заголовок страницы (опционально)")
                        .optionalString("content", "Новое Содержимое страницы в формате Confluence Storage (опционально)")
                        .build(),
                annotations(false, true, false),
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
     * Создаёт подсказки MCP о свойствах инструмента.
     *
     * @param readOnly признак операции только для чтения
     * @param destructive признак потенциально разрушающего изменения
     * @param idempotent признак идемпотентности операции
     * @return объект аннотаций MCP
     */
    private static ObjectNode annotations(boolean readOnly, boolean destructive, boolean idempotent) {
        ObjectNode annotations = JsonNodeFactory.instance.objectNode();
        annotations.put("readOnlyHint", readOnly);
        annotations.put("destructiveHint", destructive);
        annotations.put("idempotentHint", idempotent);
        annotations.put("openWorldHint", true);
        return annotations;
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
