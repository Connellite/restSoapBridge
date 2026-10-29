package io.github.connellite.model;

import java.util.ArrayList;
import java.util.List;

/**
 * @param nillable      {@code nillable="true"}: the element may be sent as {@code null} even when required
 * @param minItems      {@code minOccurs} of a repeating element, {@code null} when it does not bound the array
 * @param maxItems      {@code maxOccurs} of a repeating element, {@code null} for {@code unbounded}
 * @param documentation {@code xs:documentation} of the element or attribute, or {@code null}
 * @param choices       the {@code xs:choice} alternatives this field belongs to, outermost first
 */
public record SchemaField(
        String path,
        String xsdType,
        boolean required,
        boolean collection,
        SchemaFacets facets,
        boolean nillable,
        Integer minItems,
        Integer maxItems,
        String documentation,
        List<ChoiceRef> choices
) {

    public SchemaField {
        facets = facets == null ? SchemaFacets.NONE : facets;
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public SchemaField(String path, String xsdType, boolean required, boolean collection, SchemaFacets facets) {
        this(path, xsdType, required, collection, facets, false, null, null, null, List.of());
    }

    public SchemaField(String path, String xsdType, boolean required, boolean collection) {
        this(path, xsdType, required, collection, SchemaFacets.NONE);
    }

    /** The same field under another path; a path of another depth drops {@link #choices}, which count segments. */
    public SchemaField withPath(String newPath) {
        boolean sameDepth = depth(newPath) == depth(path);
        return new SchemaField(newPath, xsdType, required, collection, facets, nillable, minItems, maxItems,
                documentation, sameDepth ? choices : List.of());
    }

    public SchemaField withRequired(boolean newRequired) {
        return new SchemaField(path, xsdType, newRequired, collection, facets, nillable, minItems, maxItems,
                documentation, choices);
    }

    public SchemaField withChoice(ChoiceRef choice) {
        List<ChoiceRef> all = new ArrayList<>();
        all.add(choice);
        all.addAll(choices);
        return new SchemaField(path, xsdType, required, collection, facets, nillable, minItems, maxItems,
                documentation, all);
    }

    private static int depth(String path) {
        String relative = path.startsWith("$.") ? path.substring(2) : path;
        return relative.isEmpty() ? 0 : relative.split("\\.", -1).length;
    }

    /**
     * Membership in one {@code xs:choice}: at most one alternative may be sent, exactly one when the choice is
     * required.
     *
     * @param group       id of the choice, unique within the operation
     * @param alternative index of the alternative this field belongs to
     * @param depth       number of path segments of the object holding the choice; the next segment is the member
     * @param optional    the choice may be left out ({@code minOccurs="0"}, an optional parent, an empty alternative)
     */
    public record ChoiceRef(String group, int alternative, int depth, boolean optional) {
    }
}
