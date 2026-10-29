package io.github.connellite.config;

import io.github.connellite.model.SchemaProperty;
import io.github.connellite.model.SchemaTrees;
import io.github.connellite.model.SchemaType;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

import static io.github.connellite.config.OpenApiExampleFactory.scalarSchema;
import static io.github.connellite.config.OpenApiExampleFactory.withFacets;

/**
 * Renders type trees as OpenAPI 3.0 schemas. A named type becomes a component, rendered once and referred to by
 * {@code $ref} wherever it is used (itself included); anonymous types are rendered in place.
 */
public final class SchemaRenderer {

    static final String COMPONENTS = "#/components/schemas/";
    private static final Pattern INVALID_COMPONENT_KEY_CHARS = Pattern.compile("[^a-zA-Z0-9._-]");

    private final Map<String, SchemaType> types;
    private final Function<String, String> componentNames;
    @SuppressWarnings("rawtypes")
    private final Map<String, Schema> components = new LinkedHashMap<>();

    /**
     * @param types          named types by QName
     * @param componentNames component name of a named type, by QName
     */
    public SchemaRenderer(Map<String, SchemaType> types, Function<String, String> componentNames) {
        this.types = types;
        this.componentNames = componentNames;
    }

    /** Renderer whose components are named after the types' local names, numbered when two share one. */
    public static SchemaRenderer withLocalNames(Map<String, SchemaType> types) {
        Map<String, String> assigned = new HashMap<>();
        Set<String> taken = new LinkedHashSet<>();
        return new SchemaRenderer(types, key -> assigned.computeIfAbsent(key, ignored -> {
            String base = componentKey(localName(key));
            String name = base;
            for (int index = 2; !taken.add(name); index++) {
                name = base + "_" + index;
            }
            return name;
        }));
    }

    /** OpenAPI component keys must match {@code ^[a-zA-Z0-9.\-_]+$}, otherwise code generators reject the spec. */
    static String componentKey(String raw) {
        return INVALID_COMPONENT_KEY_CHARS.matcher(raw).replaceAll("_");
    }

    static String localName(String qname) {
        int end = qname.indexOf('}');
        return end >= 0 ? qname.substring(end + 1) : qname;
    }

    /** The components the rendered schemas refer to, by component name. */
    @SuppressWarnings("rawtypes")
    public Map<String, Schema> components() {
        return components;
    }

    /** A type as the value of a property: a {@code $ref} for a named type, the object itself for an anonymous one. */
    public Schema<?> type(SchemaType type) {
        return type.name() != null && types.containsKey(type.name()) ? reference(type.name()) : object(type);
    }

    /** A type rendered in place (a request or response body), whether it is named or not. */
    public ObjectSchema object(SchemaType type) {
        ObjectSchema schema = new ObjectSchema();
        List<String> required = new ArrayList<>();
        Map<String, Map<Integer, Set<String>>> choices = new LinkedHashMap<>();
        Map<String, Boolean> optionalChoices = new LinkedHashMap<>();
        for (SchemaProperty property : type.properties()) {
            String name = SchemaTrees.restName(property.name());
            schema.addProperty(name, property(property));
            if (property.required()) {
                required.add(name);
            }
            for (SchemaProperty.Choice choice : property.choices()) {
                choices.computeIfAbsent(choice.group(), key -> new LinkedHashMap<>())
                        .computeIfAbsent(choice.alternative(), key -> new LinkedHashSet<>())
                        .add(name);
                optionalChoices.merge(choice.group(), choice.optional(), Boolean::logicalOr);
            }
        }
        if (!required.isEmpty()) {
            schema.required(required);
        }
        if (type.open()) {
            // xs:any / xs:anyAttribute: content the schema does not name is carried as it is.
            schema.setAdditionalProperties(Boolean.TRUE);
        }
        addChoiceConstraints(schema, choices, optionalChoices);
        return schema;
    }

    private Schema<?> reference(String typeName) {
        String name = componentNames.apply(typeName);
        if (!components.containsKey(name)) {
            // Registered before rendering: a type that contains itself refers to its own component.
            components.put(name, new ObjectSchema());
            SchemaType type = types.get(typeName);
            ObjectSchema schema = object(type);
            if (type.documentation() != null) {
                schema.setDescription(type.documentation());
            }
            components.put(name, schema);
        }
        return new Schema<>().$ref(COMPONENTS + name);
    }

    private Schema<?> property(SchemaProperty property) {
        Schema<?> value;
        String documentation = property.documentation();
        if (property.typeName() != null && types.containsKey(property.typeName())) {
            value = reference(property.typeName());
        } else if (property.inlineType() != null) {
            value = object(property.inlineType());
            if (documentation == null) {
                documentation = property.inlineType().documentation();
            }
        } else {
            value = withFacets(scalarSchema(property.isComplex() ? "object" : property.xsdType()), property.facets());
        }
        if (property.nillable()) {
            value = nullable(value);
        }
        if (!property.array()) {
            return describe(value, documentation);
        }
        ArraySchema array = new ArraySchema();
        array.setItems(value);
        array.setMinItems(property.minItems());
        array.setMaxItems(property.maxItems());
        if (documentation != null) {
            array.setDescription(documentation);
        }
        return array;
    }

    /** OpenAPI 3.0 ignores keywords next to {@code $ref}: those go on an {@code allOf} wrapper. */
    private static Schema<?> nullable(Schema<?> value) {
        Schema<?> target = value.get$ref() != null ? new Schema<>().allOf(List.of(value)) : value;
        target.setNullable(true);
        return target;
    }

    private static Schema<?> describe(Schema<?> value, String documentation) {
        if (documentation == null) {
            return value;
        }
        Schema<?> target = value.get$ref() != null ? new Schema<>().allOf(List.of(value)) : value;
        // The XSD documentation ahead of a note the schema already carries (e.g. the JAXB map shape).
        target.setDescription(target.getDescription() == null
                ? documentation
                : documentation + "\n\n" + target.getDescription());
        return target;
    }

    /**
     * {@code xs:choice}: exactly one alternative ({@code oneOf}) when the choice is required, at most one
     * ({@code not} two of them) when it is optional. Several choices of one object are combined with
     * {@code allOf}. An alternative counts as sent when one of its members is.
     */
    @SuppressWarnings("rawtypes")
    private static void addChoiceConstraints(ObjectSchema schema, Map<String, Map<Integer, Set<String>>> choices,
                                             Map<String, Boolean> optionalChoices) {
        List<Schema> constraints = new ArrayList<>();
        choices.forEach((group, alternatives) -> {
            if (alternatives.size() < 2) {
                return;
            }
            List<Schema> present = alternatives.values().stream().map(SchemaRenderer::present).toList();
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
}
