package ru.solomentsev.core.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Строит JSON-схему входных параметров инструмента MCP.
 *
 * <p>Схема используется и для публикации контракта клиенту, и для
 * серверной проверки фактических аргументов.</p>
 */
public final class ToolSchema {

    /** Тип строкового параметра. */
    public static final String TYPE_STRING = "string";

    /** Тип целочисленного параметра. */
    public static final String TYPE_INTEGER = "integer";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, FieldDefinition> fields = new LinkedHashMap<>();
    private final Set<String> required = new LinkedHashSet<>();

    private ToolSchema() {
    }

    /**
     * Создаёт пустую схему.
     *
     * @return новый builder схемы
     */
    public static ToolSchema create() {
        return new ToolSchema();
    }

    /**
     * Добавляет обязательный строковый параметр.
     *
     * @param name имя параметра
     * @param description описание параметра
     * @return этот builder
     */
    public ToolSchema requiredString(String name, String description) {
        return field(name, TYPE_STRING, true, description);
    }

    /**
     * Добавляет необязательный строковый параметр.
     *
     * @param name имя параметра
     * @param description описание параметра
     * @return этот builder
     */
    public ToolSchema optionalString(String name, String description) {
        return field(name, TYPE_STRING, false, description);
    }

    /**
     * Добавляет необязательный целочисленный параметр без заданных границ.
     *
     * @param name имя параметра
     * @param description описание параметра
     * @return этот builder
     */
    public ToolSchema optionalInteger(String name, String description) {
        return optionalInteger(name, description, null, null);
    }

    /**
     * Добавляет необязательный целочисленный параметр с допустимым диапазоном.
     *
     * @param name имя параметра
     * @param description описание параметра
     * @param minimum минимальное значение или {@code null}
     * @param maximum максимальное значение или {@code null}
     * @return этот builder
     * @throws IllegalArgumentException если границы некорректны
     */
    public ToolSchema optionalInteger(String name, String description,
                                      Integer minimum, Integer maximum) {
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException("Минимум не может превышать максимум");
        }
        return field(name, TYPE_INTEGER, false, description, minimum, maximum);
    }

    /**
     * Добавляет параметр схемы.
     *
     * @param name имя параметра
     * @param type тип JSON-параметра
     * @param isRequired обязателен ли параметр
     * @param description описание параметра
     * @return этот builder
     */
    public ToolSchema field(String name, String type, boolean isRequired, String description) {
        return field(name, type, isRequired, description, null, null);
    }

    private ToolSchema field(String name, String type, boolean isRequired,
                             String description, Integer minimum, Integer maximum) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Имя параметра не должно быть пустым");
        }
        if (!TYPE_STRING.equals(type) && !TYPE_INTEGER.equals(type)) {
            throw new IllegalArgumentException("Неподдерживаемый тип параметра: " + type);
        }
        fields.put(name, new FieldDefinition(type, Objects.requireNonNullElse(description, ""),
                minimum, maximum));
        if (isRequired) {
            required.add(name);
        } else {
            required.remove(name);
        }
        return this;
    }

    /**
     * Формирует JSON Schema объекта аргументов.
     *
     * @return JSON-схема объекта
     */
    public ObjectNode build() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);

        ObjectNode properties = MAPPER.createObjectNode();
        for (Map.Entry<String, FieldDefinition> entry : fields.entrySet()) {
            FieldDefinition definition = entry.getValue();
            ObjectNode property = MAPPER.createObjectNode();
            property.put("type", definition.type);
            property.put("description", definition.description);
            if (TYPE_STRING.equals(definition.type)) {
                property.put("minLength", 1);
            }
            if (definition.minimum != null) {
                property.put("minimum", definition.minimum);
            }
            if (definition.maximum != null) {
                property.put("maximum", definition.maximum);
            }
            properties.set(entry.getKey(), property);
        }
        schema.set("properties", properties);

        if (!required.isEmpty()) {
            ArrayNode requiredNodes = MAPPER.createArrayNode();
            required.forEach(requiredNodes::add);
            schema.set("required", requiredNodes);
        }
        return schema;
    }

    private static final class FieldDefinition {
        private final String type;
        private final String description;
        private final Integer minimum;
        private final Integer maximum;

        private FieldDefinition(String type, String description,
                                Integer minimum, Integer maximum) {
            this.type = type;
            this.description = description;
            this.minimum = minimum;
            this.maximum = maximum;
        }
    }
}
