package io.github.connellite.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One element or attribute of a {@link SchemaType}. Its value is a named type ({@link #typeName}), an anonymous one
 * ({@link #inlineType}) or, with neither, the scalar, {@code map} or untyped {@code object} {@link #xsdType} names.
 *
 * @param name          local name of the element or attribute in XML
 * @param required      required within the object that holds it ({@code minOccurs > 0}, {@code use="required"})
 * @param array         a repeating element or an {@code xs:list}
 * @param minItems      {@code minOccurs} of a repeating element, {@code null} when it does not bound the array
 * @param maxItems      {@code maxOccurs} of a repeating element, {@code null} for {@code unbounded}
 * @param documentation {@code xs:documentation} of the element or attribute, or {@code null}
 * @param choices       the {@code xs:choice}s of the holding object this property is an alternative of, outermost first
 * @param kind          how the property appears in XML
 * @param namespace     namespace of the element or attribute name on the wire ({@code ""} when unqualified),
 *                      {@code null} when unknown (a tree built from field paths)
 * @param list          an {@code xs:list}: one element or attribute holding space-separated values
 * @param mapNamespace  namespace of the {@code entry}/{@code key}/{@code value} elements of a JAXB map
 * @param jsonName      JSON name when it is not the (REST form of the) XML name: elements of one local name from
 *                      different namespaces in one object; {@code null} otherwise
 * @param defaultValue  {@code default} or {@code fixed} value of the declaration as written in XSD, or {@code null}
 * @param fixed         {@code defaultValue} is a {@code fixed} value: the only one the field may hold
 */
public record SchemaProperty(
        String name,
        String xsdType,
        SchemaFacets facets,
        String typeName,
        SchemaType inlineType,
        boolean required,
        boolean array,
        boolean nillable,
        Integer minItems,
        Integer maxItems,
        String documentation,
        List<Choice> choices,
        Kind kind,
        String namespace,
        boolean list,
        String mapNamespace,
        String jsonName,
        String defaultValue,
        boolean fixed
) {

    public SchemaProperty {
        facets = facets == null ? SchemaFacets.NONE : facets;
        choices = choices == null ? List.of() : List.copyOf(choices);
        kind = kind == null ? Kind.ELEMENT : kind;
    }

    public SchemaProperty(String name, String xsdType, SchemaFacets facets, String typeName, SchemaType inlineType,
                          boolean required, boolean array, boolean nillable, Integer minItems, Integer maxItems,
                          String documentation, List<Choice> choices, Kind kind, String namespace, boolean list,
                          String mapNamespace) {
        this(name, xsdType, facets, typeName, inlineType, required, array, nillable, minItems, maxItems,
                documentation, choices, kind, namespace, list, mapNamespace, null, null, false);
    }

    public SchemaProperty(String name, String xsdType, SchemaFacets facets, String typeName, SchemaType inlineType,
                          boolean required, boolean array, boolean nillable, Integer minItems, Integer maxItems,
                          String documentation, List<Choice> choices) {
        this(name, xsdType, facets, typeName, inlineType, required, array, nillable, minItems, maxItems,
                documentation, choices, Kind.ELEMENT, null, false, null);
    }

    public enum Kind {
        ELEMENT,
        ATTRIBUTE,
        /** The text of a simple-content type (JAXB {@code @XmlValue}). */
        VALUE
    }

    public boolean isComplex() {
        return typeName != null || inlineType != null;
    }

    /** The name of the property in JSON. */
    public String jsonKey() {
        return jsonName != null ? jsonName : SchemaTrees.restName(name);
    }

    public SchemaProperty withName(String newName) {
        return new SchemaProperty(newName, xsdType, facets, typeName, inlineType, required, array, nillable, minItems,
                maxItems, documentation, choices, kind, namespace, list, mapNamespace, jsonName, defaultValue, fixed);
    }

    public SchemaProperty withJsonName(String newJsonName) {
        return new SchemaProperty(name, xsdType, facets, typeName, inlineType, required, array, nillable, minItems,
                maxItems, documentation, choices, kind, namespace, list, mapNamespace, newJsonName, defaultValue,
                fixed);
    }

    public SchemaProperty withDefault(String value, boolean isFixed) {
        return new SchemaProperty(name, xsdType, facets, typeName, inlineType, required, array, nillable, minItems,
                maxItems, documentation, choices, kind, namespace, list, mapNamespace, jsonName, value, isFixed);
    }

    public SchemaProperty withRequired(boolean newRequired) {
        return new SchemaProperty(name, xsdType, facets, typeName, inlineType, newRequired, array, nillable, minItems,
                maxItems, documentation, choices, kind, namespace, list, mapNamespace, jsonName, defaultValue, fixed);
    }

    /** This property as an alternative of one more, enclosing choice. */
    public SchemaProperty withOuterChoice(Choice choice) {
        List<Choice> all = new ArrayList<>();
        all.add(choice);
        all.addAll(choices);
        return new SchemaProperty(name, xsdType, facets, typeName, inlineType, required, array, nillable, minItems,
                maxItems, documentation, all, kind, namespace, list, mapNamespace, jsonName, defaultValue, fixed);
    }

    /**
     * Membership in an {@code xs:choice} of the holding object: at most one alternative may be sent, exactly one when
     * the choice is required.
     *
     * @param group       id of the choice, unique within the holding object
     * @param alternative index of the alternative this property belongs to
     * @param optional    the choice may be left out ({@code minOccurs="0"}, an optional particle, an empty alternative)
     * @param repeating   the choice repeats ({@code maxOccurs > 1}): each occurrence is one alternative, which no
     *                    keyword of a JSON schema can say; it is described instead
     */
    public record Choice(String group, int alternative, boolean optional, boolean repeating) {

        public Choice(String group, int alternative, boolean optional) {
            this(group, alternative, optional, false);
        }
    }
}
