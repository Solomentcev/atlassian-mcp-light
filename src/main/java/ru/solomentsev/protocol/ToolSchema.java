package ru.solomentsev.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Строит JSON-схему входных параметров инструмента MCP.
 *
 * <p>Инкапсулирует правила описания параметров: тип, описание
 * и признак обязательности.</p>
 */
public final class ToolSchema {

    /** Тип строкового параметра. */
    public static final String TYPE_STRING = "string";

    /** Тип числового параметра. */
    public static final String TYPE_INTEGER = "integer";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> descriptions = new LinkedHashMap<>();
    private final Map<String, String> types = new LinkedHashMap<>();
    private final Set<String> required = new LinkedHashSet<>();

    private ToolSchema() {
    }

    /**
     * Создает пустую схему.
     *
     * @return новый builder схемы
     */
    public static ToolSchema create() {
        return new ToolSchema();
    }

    /**
     * Добавляет обязательный строковый параметр.
     *
     * @param name        имя параметра
     * @param description описание параметра
     * @return этот builder
     */
    public ToolSchema requiredString(String name, String description) {
        return field(name, TYPE_STRING, true, description);
    }

    /**
     * Добавляет необязательный строковый параметр.
     *
     * @param name        имя параметра
     * @param description описание параметра
     * @return этот builder
     */
    public ToolSchema optionalString(String name, String description) {
        return field(name, TYPE_STRING, false, description);
    }

    /**
     * Добавляет необязательный числовой параметр.
     *
     * @param name        имя параметра
     * @param description описание параметра
     * @return этот builder
     */
    public ToolSchema optionalInteger(String name, String description) {
        return field(name, TYPE_INTEGER, false, description);
    }

    /**
     * Добавляет параметр.
     *
     * @param name        имя параметра
     * @param type        тип параметра
     * @param required    обязателен ли параметр
     * @param description описание параметра
     * @return этот builder
     */
    public ToolSchema field(String name, String type, boolean required, String description) {
        types.put(name, type);
        descriptions.put(name, description);
        if (required) {
            this.required.add(name);
        }
        return this;
    }

    /**
     * Формирует узел схемы.
     *
     * @return JSON-схема объекта
     */
    public ObjectNode build() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();
        for (Map.Entry<String, String> entry : types.entrySet()) {
            ObjectNode property = MAPPER.createObjectNode();
            property.put("type", entry.getValue());
            property.put("description", descriptions.getOrDefault(entry.getKey(), ""));
            properties.set(entry.getKey(), property);
        }
        schema.set("properties", properties);

        if (!required.isEmpty()) {
            ArrayNode requiredNodes = MAPPER.createArrayNode();
            for (String key : required) {
                requiredNodes.add(key);
            }
            schema.set("required", requiredNodes);
        }

        return schema;
    }
}
