package io.github.connellite.config;

import io.github.connellite.model.SchemaField;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import lombok.experimental.UtilityClass;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@UtilityClass
public class OpenApiExampleFactory {
    public static ObjectSchema schemaWithExamples(Iterable<String> bodyPaths) {
        Map<String, Object> example = exampleMap(bodyPaths);
        ObjectSchema schema = new ObjectSchema();
        for (Map.Entry<String, Object> entry : example.entrySet()) {
            schema.addProperty(entry.getKey(), propertySchema(entry.getValue()));
        }
        schema.setExample(example);
        return schema;
    }

    public static ObjectSchema schemaWithXsdExamples(Iterable<SchemaField> schemaFields, Iterable<String> fallbackBodyPaths) {
        if (schemaFields == null || !schemaFields.iterator().hasNext()) {
            return schemaWithExamples(fallbackBodyPaths);
        }

        SchemaNode root = new SchemaNode("", "object", false, false);
        Map<String, Object> example = new LinkedHashMap<>();
        for (SchemaField field : schemaFields) {
            String path = extractBodyPath(field.path());
            if (path.isBlank()) {
                continue;
            }
            root.add(path, field);
            setNestedExample(example, path, sampleValue(field.xsdType()));
        }

        ObjectSchema schema = root.toObjectSchema();
        schema.setExample(example);
        return schema;
    }

    public static Map<String, Object> exampleMap(Iterable<String> bodyPaths) {
        Map<String, Object> example = new LinkedHashMap<>();
        for (String path : bodyPaths) {
            String fieldPath = extractBodyPath(path);
            if (!fieldPath.isBlank()) {
                setNestedExample(example, fieldPath, "");
            }
        }
        return example;
    }

    private static Schema<?> propertySchema(Object sample) {
        if (sample instanceof List<?> list) {
            ArraySchema schema = new ArraySchema();
            Object itemSample = list.isEmpty() ? "" : list.get(0);
            schema.setItems(propertySchema(itemSample));
            schema.setExample(list);
            return schema;
        }
        if (sample instanceof Map<?, ?> map) {
            ObjectSchema schema = new ObjectSchema();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                schema.addProperty(String.valueOf(entry.getKey()), propertySchema(entry.getValue()));
            }
            schema.setExample(map);
            return schema;
        }
        if (sample instanceof Integer) {
            IntegerSchema schema = new IntegerSchema();
            schema.setExample(sample);
            return schema;
        }
        if (sample instanceof Boolean) {
            BooleanSchema schema = new BooleanSchema();
            schema.setExample(sample);
            return schema;
        }
        StringSchema schema = new StringSchema();
        schema.setExample(String.valueOf(sample));
        return schema;
    }

    private static Object sampleValue(String xsdType) {
        return switch (normalizeXsdType(xsdType)) {
            case "boolean" -> Boolean.TRUE;
            case "byte", "short", "int", "integer", "long", "unsignedbyte", "unsignedshort", "unsignedint",
                    "unsignedlong", "nonnegativeinteger", "nonpositiveinteger", "positiveinteger",
                    "negativeinteger" -> 0;
            case "float", "double", "decimal" -> 1.23;
            case "date" -> LocalDate.now().toString();
            case "datetime", "time" -> LocalDateTime.now().toString();
            // Complex value without indexed children (empty or recursive type).
            case "object" -> new LinkedHashMap<String, Object>();
            // JAXB Map (entry/key/value), sent as a plain JSON object.
            case "map" -> new LinkedHashMap<String, Object>(Map.of("key", "value"));
            default -> "";
        };
    }

    private static String normalizeXsdType(String xsdType) {
        if (xsdType == null || xsdType.isBlank()) {
            return "string";
        }
        int colon = xsdType.indexOf(':');
        String localName = colon >= 0 ? xsdType.substring(colon + 1) : xsdType;
        return localName.toLowerCase(Locale.ROOT);
    }

    public static String extractBodyField(String jsonPath) {
        return leafName(extractBodyPath(jsonPath));
    }

    public static String extractBodyPath(String jsonPath) {
        if (jsonPath == null || jsonPath.isBlank()) {
            return "";
        }
        return jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
    }

    private static String leafName(String path) {
        int lastDot = path.lastIndexOf('.');
        String leaf = lastDot >= 0 ? path.substring(lastDot + 1) : path;
        int bracketIndex = leaf.indexOf('[');
        return bracketIndex >= 0 ? leaf.substring(0, bracketIndex) : leaf;
    }

    private static void setNestedExample(Map<String, Object> root, String fieldPath, Object value) {
        int collectionItemIndex = fieldPath.indexOf("[].");
        if (collectionItemIndex >= 0) {
            String arrayPath = fieldPath.substring(0, collectionItemIndex);
            String itemField = fieldPath.substring(collectionItemIndex + 3);
            Map<String, Object> item = new LinkedHashMap<>();
            setNestedExample(item, itemField, value);
            mergeArrayExample(root, arrayPath, item);
            return;
        }

        if (fieldPath.endsWith("[]")) {
            String arrayName = fieldPath.substring(0, fieldPath.length() - 2);
            int dotIndex = arrayName.lastIndexOf('.');
            if (dotIndex < 0) {
                root.put(arrayName, List.of(value));
                return;
            }
            Map<String, Object> parent = ensureMap(root, arrayName.substring(0, dotIndex));
            parent.put(arrayName.substring(dotIndex + 1), List.of(value));
            return;
        }

        int dotIndex = fieldPath.lastIndexOf('.');
        if (dotIndex < 0) {
            root.put(fieldPath, value);
            return;
        }

        Map<String, Object> parent = ensureMap(root, fieldPath.substring(0, dotIndex));
        parent.put(fieldPath.substring(dotIndex + 1), value);
    }

    @SuppressWarnings("unchecked")
    private static void mergeArrayExample(Map<String, Object> root, String arrayPath, Map<String, Object> item) {
        int dotIndex = arrayPath.lastIndexOf('.');
        if (dotIndex < 0) {
            Object existing = root.get(arrayPath);
            if (existing instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> firstItem) {
                ((Map<String, Object>) firstItem).putAll(item);
                return;
            }
            root.put(arrayPath, new ArrayList<>(List.of(item)));
            return;
        }

        Map<String, Object> parent = ensureMap(root, arrayPath.substring(0, dotIndex));
        String arrayName = arrayPath.substring(dotIndex + 1);
        Object existing = parent.get(arrayName);
        if (existing instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> firstItem) {
            ((Map<String, Object>) firstItem).putAll(item);
            return;
        }
        parent.put(arrayName, new ArrayList<>(List.of(item)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ensureMap(Map<String, Object> root, String path) {
        int dotIndex = path.indexOf('.');
        if (dotIndex < 0) {
            Object existing = root.get(path);
            if (existing instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            Map<String, Object> created = new LinkedHashMap<>();
            root.put(path, created);
            return created;
        }

        String segment = path.substring(0, dotIndex);
        Object existing = root.get(segment);
        Map<String, Object> child;
        if (existing instanceof Map<?, ?> map) {
            child = (Map<String, Object>) map;
        } else {
            child = new LinkedHashMap<>();
            root.put(segment, child);
        }
        return ensureMap(child, path.substring(dotIndex + 1));
    }

    private static final class SchemaNode {

        private final String name;
        private String xsdType;
        private boolean required;
        private boolean array;
        private final Map<String, SchemaNode> children = new LinkedHashMap<>();

        private SchemaNode(String name, String xsdType, boolean required, boolean array) {
            this.name = name;
            this.xsdType = xsdType;
            this.required = required;
            this.array = array;
        }

        private void add(String path, SchemaField field) {
            String[] segments = path.split("\\.");
            SchemaNode current = this;
            for (int index = 0; index < segments.length; index++) {
                String segment = segments[index];
                boolean segmentArray = segment.endsWith("[]");
                String segmentName = segmentArray ? segment.substring(0, segment.length() - 2) : segment;
                boolean leaf = index == segments.length - 1;

                SchemaNode child = current.children.computeIfAbsent(segmentName,
                        key -> new SchemaNode(key, leaf ? field.xsdType() : "object", field.required(), segmentArray));
                child.required = child.required || field.required();
                child.array = child.array || segmentArray || (leaf && field.collection());
                if (leaf) {
                    child.xsdType = field.xsdType();
                }
                current = child;
            }
        }

        private ObjectSchema toObjectSchema() {
            ObjectSchema schema = new ObjectSchema();
            List<String> requiredFields = new ArrayList<>();
            for (SchemaNode child : children.values()) {
                schema.addProperty(child.name, child.toSchema());
                if (child.required) {
                    requiredFields.add(child.name);
                }
            }
            if (!requiredFields.isEmpty()) {
                schema.required(requiredFields);
            }
            return schema;
        }

        private Schema<?> toSchema() {
            Schema<?> schema = children.isEmpty() ? scalarSchema(xsdType) : toObjectSchema();
            if (!array) {
                return schema;
            }
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItems(schema);
            return arraySchema;
        }

        private Schema<?> scalarSchema(String xsdType) {
            return switch (normalizeXsdType(xsdType)) {
                case "boolean" -> new BooleanSchema();
                case "byte", "short", "int", "integer", "unsignedbyte", "unsignedshort", "unsignedint",
                        "nonnegativeinteger", "nonpositiveinteger", "positiveinteger", "negativeinteger" ->
                        new IntegerSchema().format("int32");
                case "long", "unsignedlong" -> new IntegerSchema().format("int64");
                case "float" -> new NumberSchema().format("float");
                case "double", "decimal" -> new NumberSchema().format("double");
                case "date" -> new StringSchema().format("date");
                case "datetime", "time" -> new StringSchema().format("date-time");
                case "base64binary", "hexbinary" -> new StringSchema().format("byte");
                case "object" -> new ObjectSchema();
                case "map" -> new ObjectSchema().additionalProperties(new StringSchema());
                default -> new StringSchema();
            };
        }
    }
}
