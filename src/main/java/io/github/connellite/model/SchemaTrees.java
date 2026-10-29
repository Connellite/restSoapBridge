package io.github.connellite.model;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts between the type tree ({@link SchemaType}) and the flat field list ({@link SchemaField}) the mappings are
 * generated from.
 */
@UtilityClass
public class SchemaTrees {

    /**
     * Every field of a type as a path ({@code customer.name}, {@code items[].sku}), named types expanded in place.
     * A field is required when it and every object above it is; a type that already encloses the field is not
     * expanded again but listed as one {@code object} field.
     *
     * @param types named types by {@link SchemaType#name()}
     */
    public static List<SchemaField> flatten(SchemaType type, Map<String, SchemaType> types) {
        List<SchemaField> fields = new ArrayList<>();
        // The root type itself is not enclosing: a type holding itself is expanded once below the root.
        flatten(type.properties(), "", true, types, new LinkedHashSet<>(), fields);
        return fields;
    }

    private static void flatten(List<SchemaProperty> properties, String prefix, boolean parentRequired,
                                Map<String, SchemaType> types, Set<String> enclosing, List<SchemaField> fields) {
        for (SchemaProperty property : properties) {
            int start = fields.size();
            String path = prefix + property.name();
            boolean required = parentRequired && property.required();
            SchemaType type = property.typeName() != null ? types.get(property.typeName()) : property.inlineType();
            String documentation = property.documentation() != null || type == null
                    ? property.documentation()
                    : type.documentation();
            if (type == null || (property.typeName() != null && enclosing.contains(property.typeName()))) {
                fields.add(scalar(path, property, required, documentation));
            } else {
                boolean named = property.typeName() != null;
                if (named) {
                    enclosing.add(property.typeName());
                }
                List<SchemaField> nested = new ArrayList<>();
                flatten(type.properties(), property.array() ? path + "[]." : path + ".", required, types, enclosing,
                        nested);
                if (named) {
                    enclosing.remove(property.typeName());
                }
                if (property.array()) {
                    fields.add(new SchemaField(path + "[]", "object", required, true, null, property.nillable(),
                            property.minItems(), property.maxItems(), documentation, List.of()));
                }
                if (!nested.isEmpty()) {
                    fields.addAll(nested);
                } else if (!property.array()) {
                    fields.add(new SchemaField(path, "object", required, false, null, property.nillable(), null, null,
                            documentation, List.of()));
                }
            }
            List<SchemaProperty.Choice> choices = property.choices();
            for (int index = start; index < fields.size(); index++) {
                SchemaField field = fields.get(index);
                for (int choice = choices.size() - 1; choice >= 0; choice--) {
                    SchemaProperty.Choice local = choices.get(choice);
                    field = field.withChoice(new SchemaField.ChoiceRef(prefix + local.group(), local.alternative(),
                            depth(prefix), local.optional() || !parentRequired));
                }
                fields.set(index, field);
            }
        }
    }

    /**
     * A scalar, map or untyped object field; also a named type met again inside itself, which keeps the
     * documentation the type gives it.
     */
    private static SchemaField scalar(String path, SchemaProperty property, boolean required, String documentation) {
        boolean recursion = property.isComplex();
        String xsdType = recursion ? "object" : property.xsdType();
        return new SchemaField(property.array() ? path + "[]" : path, xsdType, required, property.array(),
                recursion ? null : property.facets(), property.nillable(), property.minItems(), property.maxItems(),
                documentation, List.of());
    }

    /**
     * REST name of an XSD name: CXF names some elements with a leading underscore ({@code _return} for {@code Map}
     * results), REST exposes the plain name.
     */
    public static String restName(String segment) {
        int start = 0;
        while (start < segment.length() - 1 && segment.charAt(start) == '_' && segment.charAt(start + 1) != '[') {
            start++;
        }
        return segment.substring(start);
    }

    /** Number of path segments of a field prefix ({@code a.b[].} → 2). */
    private static int depth(String prefix) {
        return prefix.isEmpty() ? 0 : prefix.split("\\.").length;
    }

    /**
     * A tree of anonymous types from field paths ({@code $.} optional), for fields that do not come with a tree.
     * An object is required when one of its fields is; {@code xs:choice} memberships are moved to the object that
     * holds the choice.
     */
    public static SchemaType fromFields(Iterable<SchemaField> fields) {
        Node root = new Node("", "object", false, false);
        for (SchemaField field : fields) {
            String path = field.path().startsWith("$.") ? field.path().substring(2) : field.path();
            if (!path.isBlank()) {
                root.add(path.split("\\."), field);
            }
        }
        return new SchemaType(null, root.properties(), null);
    }

    private static final class Node {

        private final String name;
        private String xsdType;
        private SchemaFacets facets = SchemaFacets.NONE;
        private boolean required;
        private boolean array;
        private boolean nillable;
        private Integer minItems;
        private Integer maxItems;
        private String documentation;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private final Map<String, SchemaProperty.Choice> choices = new LinkedHashMap<>();

        private Node(String name, String xsdType, boolean required, boolean array) {
            this.name = name;
            this.xsdType = xsdType;
            this.required = required;
            this.array = array;
        }

        private void add(String[] segments, SchemaField field) {
            Node current = this;
            for (int index = 0; index < segments.length; index++) {
                String segment = segments[index];
                boolean segmentArray = segment.endsWith("[]");
                String segmentName = segmentArray ? segment.substring(0, segment.length() - 2) : segment;
                boolean leaf = index == segments.length - 1;

                Node child = current.children.computeIfAbsent(segmentName,
                        key -> new Node(key, leaf ? field.xsdType() : "object", field.required(), segmentArray));
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
            for (SchemaField.ChoiceRef choice : field.choices()) {
                if (choice.depth() >= segments.length) {
                    continue;
                }
                Node holder = this;
                for (int index = 0; index < choice.depth() && holder != null; index++) {
                    holder = holder.children.get(stripArray(segments[index]));
                }
                Node member = holder != null ? holder.children.get(stripArray(segments[choice.depth()])) : null;
                if (member != null) {
                    member.choices.merge(choice.group(),
                            new SchemaProperty.Choice(choice.group(), choice.alternative(), choice.optional()),
                            (known, added) -> new SchemaProperty.Choice(known.group(), known.alternative(),
                                    known.optional() || added.optional()));
                }
            }
        }

        private static String stripArray(String segment) {
            return segment.endsWith("[]") ? segment.substring(0, segment.length() - 2) : segment;
        }

        private List<SchemaProperty> properties() {
            List<SchemaProperty> properties = new ArrayList<>();
            for (Node child : children.values()) {
                properties.add(child.toProperty());
            }
            return properties;
        }

        private SchemaProperty toProperty() {
            SchemaType inline = children.isEmpty() ? null : new SchemaType(null, properties(), null);
            return new SchemaProperty(name, inline != null ? "object" : xsdType, facets, null, inline, required, array,
                    nillable, minItems, maxItems, documentation, List.copyOf(choices.values()));
        }
    }
}
