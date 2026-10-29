package io.github.connellite.config;

import io.github.connellite.model.SchemaFacets;
import io.github.connellite.model.SchemaField;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@UtilityClass
public class OpenApiExampleFactory {

    static final String MAP_DESCRIPTION = "Key/value map (JAXB java.util.Map) as a flat JSON object of strings, "
            + "e.g. {\"STATUS\": \"OK\", \"msg\": \"hello\"}; responses never use {\"entry\": [...]}. "
            + "Requests also accept [{\"key\": \"msg\", \"value\": \"hello\"}].";
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
            setNestedExample(example, path, sampleValue(field));
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

    /** The first enumeration value, or a type sample moved into the declared range. */
    private static Object sampleValue(SchemaField field) {
        SchemaFacets facets = field.facets();
        String jsonType = scalarSchema(field.xsdType()).getType();
        if (!facets.enumeration().isEmpty()) {
            Object first = typedValue(jsonType, facets.enumeration().get(0));
            if (first != null) {
                return first;
            }
        }
        Object sample = sampleValue(field.xsdType());
        if (!(sample instanceof Number number)) {
            return sample;
        }
        BigDecimal original = new BigDecimal(number.toString());
        BigDecimal value = original;
        BigDecimal step = "integer".equals(jsonType) ? BigDecimal.ONE : new BigDecimal("0.5");
        if (facets.minimum() != null && value.compareTo(facets.minimum()) <= 0) {
            value = facets.exclusiveMinimum() ? facets.minimum().add(step) : facets.minimum();
        }
        if (facets.maximum() != null && value.compareTo(facets.maximum()) >= 0) {
            value = facets.exclusiveMaximum() ? facets.maximum().subtract(step) : facets.maximum();
        }
        return value.compareTo(original) == 0 ? sample : typedValue(jsonType, value.toPlainString());
    }

    /** A lexical XSD value as the JSON value of an OpenAPI type; {@code null} when it is not one. */
    private static Object typedValue(String jsonType, String lexical) {
        try {
            return switch (jsonType) {
                case "integer" -> {
                    BigInteger integer = new BigDecimal(lexical.trim()).toBigIntegerExact();
                    yield integer.bitLength() < Integer.SIZE ? (Object) integer.intValue() : (Object) integer.longValue();
                }
                case "number" -> new BigDecimal(lexical.trim());
                case "boolean" -> switch (lexical.trim()) {
                    case "true", "1" -> Boolean.TRUE;
                    case "false", "0" -> Boolean.FALSE;
                    default -> null;
                };
                default -> lexical;
            };
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private static Object sampleValue(String xsdType) {
        return switch (normalizeXsdType(xsdType)) {
            case "boolean" -> Boolean.TRUE;
            case "byte", "short", "int", "integer", "long", "unsignedbyte", "unsignedshort", "unsignedint",
                    "unsignedlong", "nonnegativeinteger", "nonpositiveinteger", "positiveinteger",
                    "negativeinteger" -> 0;
            case "float", "double", "decimal" -> 1.23;
            case "date" -> LocalDate.now().toString();
            case "datetime" -> LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
            case "time" -> LocalTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
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
        private SchemaFacets facets = SchemaFacets.NONE;
        private boolean required;
        private boolean array;
        private boolean nillable;
        private Integer minItems;
        private Integer maxItems;
        private String documentation;
        private final Map<String, SchemaNode> children = new LinkedHashMap<>();
        /** {@code xs:choice} groups of this object: alternative index → member properties, in order. */
        private final Map<String, Map<Integer, Set<String>>> choices = new LinkedHashMap<>();
        private final Map<String, Boolean> optionalChoices = new LinkedHashMap<>();

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
                    child.facets = field.facets();
                    child.nillable = field.nillable();
                    child.minItems = field.minItems();
                    child.maxItems = field.maxItems();
                    child.documentation = field.documentation();
                }
                current = child;
            }
            addChoices(segments, field);
        }

        /** Registers the member of each choice the field belongs to with the object that holds the choice. */
        private void addChoices(String[] segments, SchemaField field) {
            for (SchemaField.ChoiceRef choice : field.choices()) {
                if (choice.depth() >= segments.length) {
                    continue;
                }
                SchemaNode holder = this;
                for (int index = 0; index < choice.depth() && holder != null; index++) {
                    holder = holder.children.get(stripArray(segments[index]));
                }
                if (holder == null) {
                    continue;
                }
                holder.choices.computeIfAbsent(choice.group(), key -> new LinkedHashMap<>())
                        .computeIfAbsent(choice.alternative(), key -> new LinkedHashSet<>())
                        .add(stripArray(segments[choice.depth()]));
                holder.optionalChoices.merge(choice.group(), choice.optional(), Boolean::logicalOr);
            }
        }

        private static String stripArray(String segment) {
            return segment.endsWith("[]") ? segment.substring(0, segment.length() - 2) : segment;
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
            addChoiceConstraints(schema);
            return schema;
        }

        /**
         * {@code xs:choice}: exactly one alternative ({@code oneOf}) when the choice is required, at most one
         * ({@code not} two of them) when it is optional. Several choices of one object are combined with
         * {@code allOf}. An alternative counts as sent when one of its members is.
         */
        @SuppressWarnings("rawtypes")
        private void addChoiceConstraints(ObjectSchema schema) {
            List<Schema> constraints = new ArrayList<>();
            choices.forEach((group, alternatives) -> {
                if (alternatives.size() < 2) {
                    return;
                }
                List<Schema> present = alternatives.values().stream().map(SchemaNode::present).toList();
                if (!optionalChoices.getOrDefault(group, false)) {
                    constraints.add(new Schema<>().oneOf(present));
                    return;
                }
                List<Schema> pairs = new ArrayList<>();
                for (int first = 0; first < present.size(); first++) {
                    for (int second = first + 1; second < present.size(); second++) {
                        pairs.add(both(present.get(first), present.get(second)));
                    }
                }
                constraints.add(new Schema<>().not(pairs.size() == 1 ? pairs.get(0) : new Schema<>().anyOf(pairs)));
            });
            if (constraints.size() == 1 && constraints.get(0).getOneOf() != null) {
                schema.setOneOf(constraints.get(0).getOneOf());
            } else if (constraints.size() == 1) {
                schema.setNot(constraints.get(0).getNot());
            } else if (!constraints.isEmpty()) {
                schema.setAllOf(constraints);
            }
        }

        /** Both alternatives sent: one {@code required} list when each has a single member. */
        @SuppressWarnings({"rawtypes", "unchecked"})
        private static Schema both(Schema first, Schema second) {
            if (first.getRequired() != null && second.getRequired() != null) {
                List<String> members = new ArrayList<>(first.getRequired());
                members.addAll(second.getRequired());
                return new Schema<>().required(members);
            }
            return new Schema<>().allOf(List.of(first, second));
        }

        @SuppressWarnings("rawtypes")
        private static Schema present(Set<String> members) {
            if (members.size() == 1) {
                return new Schema<>().required(List.copyOf(members));
            }
            return new Schema<>().anyOf(members.stream().map(member -> (Schema) new Schema<>().required(List.of(member)))
                    .toList());
        }

        private Schema<?> toSchema() {
            Schema<?> schema = children.isEmpty() ? withFacets(scalarSchema(xsdType), facets) : toObjectSchema();
            if (nillable) {
                schema.setNullable(true);
            }
            if (!array) {
                if (documentation != null) {
                    schema.setDescription(describe(schema.getDescription()));
                }
                return schema;
            }
            ArraySchema arraySchema = new ArraySchema();
            arraySchema.setItems(schema);
            arraySchema.setMinItems(minItems);
            arraySchema.setMaxItems(maxItems);
            if (documentation != null) {
                arraySchema.setDescription(documentation);
            }
            return arraySchema;
        }

        /** The XSD documentation ahead of a note the schema already carries (e.g. the JAXB map shape). */
        private String describe(String existing) {
            return existing == null ? documentation : documentation + "\n\n" + existing;
        }
    }

    /**
     * The JSON type of an XSD built-in type, with the {@code format} that holds its value range. Where the pair does
     * not name the XSD type (unbounded integers, {@code decimal}, {@code time}, {@code token}, the {@code g*} dates,
     * ...), {@code x-xsd-type} carries it. {@code decimal} has no {@code double} format: its values are exact.
     */
    static Schema<?> scalarSchema(String xsdType) {
        String type = normalizeXsdType(xsdType);
        Schema<?> schema = switch (type) {
            case "boolean" -> new BooleanSchema();
            case "byte", "short", "int", "unsignedbyte", "unsignedshort" -> new IntegerSchema().format("int32");
            case "long", "unsignedint" -> new IntegerSchema().format("int64");
            case "integer", "unsignedlong", "nonnegativeinteger", "nonpositiveinteger", "positiveinteger",
                    "negativeinteger" -> new IntegerSchema().format(null);
            case "float" -> new NumberSchema().format("float");
            case "double" -> new NumberSchema().format("double");
            case "decimal" -> new NumberSchema();
            case "date" -> new StringSchema().format("date");
            case "datetime" -> new StringSchema().format("date-time");
            case "time" -> new StringSchema().format("time");
            // Jackson reads and writes byte[] as base64, whatever the XSD lexical form (hexBinary).
            case "base64binary", "hexbinary" -> new StringSchema().format("byte");
            case "object" -> new ObjectSchema();
            case "map" -> new ObjectSchema().additionalProperties(new StringSchema()).description(MAP_DESCRIPTION);
            default -> new StringSchema();
        };
        if (!SELF_DESCRIBING_TYPES.contains(type)) {
            schema.addExtension(XSD_TYPE_EXTENSION, xsdTypeName(xsdType));
        }
        return schema;
    }

    static final String XSD_TYPE_EXTENSION = "x-xsd-type";

    /** XSD types that the JSON type and format already name; {@code object} and {@code map} are bridge types. */
    private static final Set<String> SELF_DESCRIBING_TYPES = Set.of(
            "string", "boolean", "int", "long", "float", "double", "date", "datetime", "base64binary",
            "object", "map");

    private static String xsdTypeName(String xsdType) {
        if (xsdType == null || xsdType.isBlank()) {
            return "string";
        }
        int colon = xsdType.indexOf(':');
        return colon >= 0 ? xsdType.substring(colon + 1) : xsdType;
    }

    /**
     * XSD facets as OpenAPI 3.0 keywords: {@code enum} typed like the property, length and pattern for strings,
     * bounds for numbers. Facets that do not fit the JSON type (a date range, an unparsable value) are dropped.
     */
    @SuppressWarnings("unchecked")
    static Schema<?> withFacets(Schema<?> schema, SchemaFacets facets) {
        if (facets.isEmpty() || schema.getType() == null || "object".equals(schema.getType())) {
            return schema;
        }
        List<Object> enumeration = new ArrayList<>();
        for (String value : facets.enumeration()) {
            Object typed = typedValue(schema.getType(), value);
            if (typed == null) {
                enumeration.clear();
                break;
            }
            enumeration.add(typed);
        }
        if (!enumeration.isEmpty()) {
            ((Schema<Object>) schema).setEnum(enumeration);
        }
        if ("string".equals(schema.getType())) {
            schema.setMinLength(facets.minLength());
            schema.setMaxLength(facets.maxLength());
            schema.setPattern(ecmaPattern(facets.pattern()));
        }
        if ("integer".equals(schema.getType()) || "number".equals(schema.getType())) {
            schema.setMinimum(facets.minimum());
            schema.setExclusiveMinimum(facets.minimum() != null && facets.exclusiveMinimum() ? Boolean.TRUE : null);
            schema.setMaximum(facets.maximum());
            schema.setExclusiveMaximum(facets.maximum() != null && facets.exclusiveMaximum() ? Boolean.TRUE : null);
        }
        return schema;
    }

    /**
     * An XSD pattern always matches the whole value, so it is anchored. XSD-only syntax ({@code \i}, {@code \c},
     * {@code \p{IsBlock}}, class subtraction) and literal {@code ^}/{@code $} have no ECMA equivalent: such a
     * pattern is left out rather than published wrong.
     */
    static String ecmaPattern(String xsdPattern) {
        if (xsdPattern == null || xsdPattern.isEmpty()) {
            return null;
        }
        String withoutNegatedClasses = xsdPattern.replace("[^", "[");
        if (withoutNegatedClasses.contains("^") || withoutNegatedClasses.contains("$")
                || xsdPattern.matches("(?s).*\\\\[iIcC].*") || xsdPattern.contains("\\p{Is")
                || xsdPattern.contains("\\P{Is") || xsdPattern.contains("-[")) {
            return null;
        }
        String anchored = "^(?:" + xsdPattern + ")$";
        try {
            Pattern.compile(anchored);
            return anchored;
        } catch (PatternSyntaxException exception) {
            return null;
        }
    }
}
