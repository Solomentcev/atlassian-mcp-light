package ru.solomentsev.app;

import ru.solomentsev.core.protocol.McpProtocol;
import ru.solomentsev.core.protocol.ToolProvider;
import java.util.List;
import java.util.Objects;

/**
 * Инициализирует инструменты подключённых продуктов Atlassian в MCP-протоколе.
 *
 * <p>Классы инструментов находятся в собственных модулях продуктов.
 * Для подключения нового продукта достаточно добавить его поставщика
 * в список, не меняя реализацию MCP-протокола.</p>
 */
public final class McpToolInitializer {

    private McpToolInitializer() {
        // utility class
    }

    /**
     * Регистрирует инструменты всех переданных продуктов в MCP-протоколе.
     *
     * @param protocol MCP-протокол
     * @param providers поставщики инструментов подключённых продуктов
     * @throws NullPointerException если протокол, список или один из поставщиков не задан
     */
    public static void initialize(McpProtocol protocol, List<? extends ToolProvider> providers) {
        Objects.requireNonNull(protocol, "protocol");
        List.copyOf(Objects.requireNonNull(providers, "providers"))
                .forEach(provider -> provider.register(protocol));
    }
}
