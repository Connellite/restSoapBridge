package io.github.connellite.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.PathType;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.OpenApiExampleFactory;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.SchemaField;
import io.swagger.v3.core.util.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates REST request bodies against the same XSD-derived schema that is published in OpenAPI,
 * so malformed input is rejected with 400 before a SOAP call is made.
 */
@Component
@RequiredArgsConstructor
public class RequestSchemaValidator {

    private static final Map<String, String> STRING_ENCODED_SCALARS = Map.of(
            "integer", "^\\s*[-+]?\\d+\\s*$",
            "number", "^\\s*[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?\\s*$",
            "boolean", "^\\s*(true|false|TRUE|FALSE|True|False)\\s*$"
    );

    private static final JsonSchemaFactory SCHEMA_FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    /**
     * Messages are part of the API response: English whatever the default locale of the JVM, with
     * {@code $.field} paths as without a config.
     */
    private static final SchemaValidatorsConfig SCHEMA_CONFIG = SchemaValidatorsConfig.builder()
            .locale(Locale.ENGLISH)
            .pathType(PathType.LEGACY)
            .build();

    private final BridgeProperties bridgeProperties;
    private final Map<String, Optional<CompiledSchema>> schemas = new ConcurrentHashMap<>();

    public void validate(MappingDefinition mapping, JsonNode body) {
        BridgeProperties.Validation validation = bridgeProperties.getValidation();
        if (!validation.isEnabled()) {
            return;
        }
        Optional<CompiledSchema> schema = schemas.computeIfAbsent(
                mapping.getMethod() + " " + mapping.getPath(),
                ignored -> compile(mapping.getRequestSchemaFields(), validation.isRejectUnknownFields()));
        if (schema.isEmpty()) {
            return;
        }
        Set<ValidationMessage> messages = schema.get().schema().validate(body);
        if (messages.isEmpty()) {
            return;
        }
        List<String> errors = new ArrayList<>(messages.stream().map(ValidationMessage::getMessage).sorted().toList());
        if (messages.stream().anyMatch(message -> "additionalProperties".equals(message.getType()))) {
            errors.add("$: allowed fields are " + schema.get().rootFields());
        }
        throw new RequestValidationException(errors);
    }

    static Optional<JsonSchema> compile(List<SchemaField> requestFields) {
        return compile(requestFields, false).map(CompiledSchema::schema);
    }

    /**
     * Without XSD field metadata there is no reliable type information, so such operations are not validated.
     * Unknown fields are rejected at the top level only: nested types may come from XSD constructs the field
     * index does not expand (e.g. {@code xs:any}, substitution groups), and a false 400 there would block valid
     * requests.
     */
    static Optional<CompiledSchema> compile(List<SchemaField> requestFields, boolean rejectUnknownFields) {
        if (requestFields == null || requestFields.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode schema = Json.mapper().valueToTree(OpenApiExampleFactory.schemaWithXsdExamples(requestFields, List.of()));
        toValidationSchema(schema);
        List<String> rootFields = new ArrayList<>();
        if (schema.get("properties") instanceof ObjectNode properties) {
            properties.fieldNames().forEachRemaining(rootFields::add);
        }
        if (rejectUnknownFields) {
            schema.put("additionalProperties", false);
        }
        return Optional.of(new CompiledSchema(SCHEMA_FACTORY.getSchema(schema, SCHEMA_CONFIG), List.copyOf(rootFields)));
    }

    record CompiledSchema(JsonSchema schema, List<String> rootFields) {
    }

    /**
     * Turns an OpenAPI 3.0 schema into a JSON Schema draft-07 one:
     * examples and vendor extensions are dropped, {@code format} is not asserted (XSD date/time lexical forms are
     * wider than RFC 3339), and optional and {@code nullable} (XSD {@code nillable}) properties also accept
     * {@code null}, as JAXB does.
     */
    static void toValidationSchema(ObjectNode schema) {
        schema.remove("example");
        schema.remove("format");
        schema.remove("nullable");
        List<String> extensions = new ArrayList<>();
        schema.fieldNames().forEachRemaining(name -> {
            if (name.startsWith("x-")) {
                extensions.add(name);
            }
        });
        schema.remove(extensions);
        toNumericExclusiveBound(schema, "exclusiveMinimum", "minimum");
        toNumericExclusiveBound(schema, "exclusiveMaximum", "maximum");
        allowStringEncodedScalar(schema);
        toChoiceConstraint(schema);

        JsonNode items = schema.get("items");
        if (items instanceof ObjectNode itemsSchema) {
            boolean nullable = isNullable(itemsSchema);
            toValidationSchema(itemsSchema);
            if (nullable) {
                allowNull(itemsSchema);
            }
        }
        if (schema.get("additionalProperties") instanceof ObjectNode mapValueSchema) {
            toValidationSchema(mapValueSchema);
            allowScalarMapValue(mapValueSchema);
        }

        if (schema.get("properties") instanceof ObjectNode properties) {
            Set<String> required = new HashSet<>();
            if (schema.get("required") instanceof ArrayNode requiredNames) {
                requiredNames.forEach(name -> required.add(name.asText()));
            }
            Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!(field.getValue() instanceof ObjectNode property)) {
                    continue;
                }
                boolean nullable = isNullable(property);
                toValidationSchema(property);
                if (!required.contains(field.getKey()) || nullable) {
                    allowNull(property);
                }
            }
        }
        allowKeyValueAlternative(schema);
    }

    private static boolean isNullable(ObjectNode schema) {
        return schema.path("nullable").asBoolean(false);
    }

    /**
     * {@code xs:choice} constraints of an object ({@code oneOf} / {@code not} / {@code allOf} of {@code required}
     * lists): a member sent as {@code null} is not sent, as JAXB leaves it out, so each {@code required} also
     * demands a non-null value. They apply to an object only: {@code required} holds for any other value, a
     * {@code null} optional object included.
     */
    private static void toChoiceConstraint(ObjectNode schema) {
        if (!isType(schema, "object")) {
            return;
        }
        ObjectNode then = JsonNodeFactory.instance.objectNode();
        for (String keyword : List.of("oneOf", "not", "allOf")) {
            JsonNode constraint = schema.remove(keyword);
            if (constraint != null) {
                requireNonNullMembers(constraint);
                then.set(keyword, constraint);
            }
        }
        if (!then.isEmpty()) {
            schema.putObject("if").put("type", "object");
            schema.set("then", then);
        }
    }

    private static void requireNonNullMembers(JsonNode constraint) {
        if (constraint instanceof ArrayNode alternatives) {
            alternatives.forEach(RequestSchemaValidator::requireNonNullMembers);
            return;
        }
        if (!(constraint instanceof ObjectNode node)) {
            return;
        }
        if (node.get("required") instanceof ArrayNode members && !node.has("type") && !node.has("properties")) {
            ObjectNode properties = node.putObject("properties");
            members.forEach(member -> properties.putObject(member.asText()).putObject("not").put("type", "null"));
        }
        for (String keyword : List.of("oneOf", "anyOf", "allOf", "not")) {
            requireNonNullMembers(node.get(keyword));
        }
    }

    /**
     * JAXB maps and {@code List<entry>} accept both key/value shapes (see JaxbMaps): a map may come as
     * {@code [{key, value}]}, an entry list as {@code {"name": "value"}}.
     */
    private static void allowKeyValueAlternative(ObjectNode schema) {
        ObjectNode alternative;
        if (schema.get("additionalProperties") instanceof ObjectNode && isType(schema, "object")) {
            alternative = JsonNodeFactory.instance.objectNode().put("type", "array");
            ObjectNode entry = alternative.putObject("items").put("type", "object");
            entry.putArray("required").add("key");
            entry.putObject("properties").putObject("key").put("type", "string");
        } else if (isType(schema, "array") && schema.get("items") instanceof ObjectNode items && isEntrySchema(items)) {
            alternative = JsonNodeFactory.instance.objectNode().put("type", "object");
            alternative.putObject("additionalProperties").putArray("type")
                    .add("string").add("number").add("boolean").add("null");
        } else {
            return;
        }
        ObjectNode original = schema.deepCopy();
        schema.removeAll();
        schema.putArray("anyOf").add(original).add(alternative);
    }

    private static boolean isType(ObjectNode schema, String type) {
        JsonNode actual = schema.get("type");
        return actual != null && actual.isTextual() && type.equals(actual.asText());
    }

    private static boolean isEntrySchema(ObjectNode items) {
        if (!(items.get("properties") instanceof ObjectNode properties) || properties.size() != 2) {
            return false;
        }
        return properties.has("key") && properties.has("value");
    }

    /**
     * Clients may send numbers and booleans as strings ({@code "42"}, {@code "true"}); the SOAP invoker coerces them.
     * Accept such strings when they look like the target type: {@code anyOf: [{type: integer}, {type: string, pattern}]}.
     */
    private static void allowStringEncodedScalar(ObjectNode schema) {
        JsonNode type = schema.get("type");
        String pattern = type != null && type.isTextual() ? STRING_ENCODED_SCALARS.get(type.asText()) : null;
        if (pattern == null) {
            return;
        }
        schema.remove("type");
        JsonNode enumeration = schema.remove("enum");
        ArrayNode anyOf = schema.putArray("anyOf");
        ObjectNode typed = anyOf.addObject().put("type", type.asText());
        ObjectNode encoded = anyOf.addObject().put("type", "string").put("pattern", pattern);
        if (enumeration instanceof ArrayNode values) {
            typed.set("enum", values);
            ArrayNode texts = encoded.putArray("enum");
            values.forEach(value -> texts.add(value.asText()));
        }
    }

    /** OpenAPI 3.0 flags an exclusive bound ({@code minimum: 0, exclusiveMinimum: true}); draft-07 holds the value. */
    private static void toNumericExclusiveBound(ObjectNode schema, String exclusiveName, String boundName) {
        JsonNode exclusive = schema.get(exclusiveName);
        if (exclusive == null || !exclusive.isBoolean()) {
            return;
        }
        schema.remove(exclusiveName);
        if (exclusive.booleanValue() && schema.has(boundName)) {
            schema.set(exclusiveName, schema.remove(boundName));
        }
    }

    /**
     * JAXB map values are strings in the schema, but numbers and booleans are converted with {@code String.valueOf}.
     */
    private static void allowScalarMapValue(ObjectNode valueSchema) {
        JsonNode type = valueSchema.get("type");
        if (type != null && type.isTextual() && "string".equals(type.asText())) {
            valueSchema.putArray("type").add("string").add("number").add("boolean").add("null");
        }
    }

    private static void allowNull(ObjectNode property) {
        if (property.get("enum") instanceof ArrayNode enumeration) {
            enumeration.addNull();
        }
        JsonNode type = property.get("type");
        if (type != null && type.isTextual()) {
            property.putArray("type").add(type.asText()).add("null");
        } else if (property.get("anyOf") instanceof ArrayNode anyOf) {
            anyOf.addObject().put("type", "null");
        }
    }
}
