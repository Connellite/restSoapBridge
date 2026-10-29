package io.github.connellite.model;

import java.util.List;

/**
 * An XSD complex type in the shape JAXB binds it to: an object with properties. Named types are shared through a
 * registry (by {@link #name}) and published once, as an OpenAPI component; properties refer to them by name, which is
 * what lets a type contain itself.
 *
 * @param name          QName of a named {@code xs:complexType} ({@link javax.xml.namespace.QName#toString()} form),
 *                      {@code null} for an anonymous one
 * @param properties    elements and attributes, base type content first
 * @param documentation {@code xs:documentation} of the type, or {@code null}
 * @param open          the type takes content it does not declare ({@code xs:any}, {@code xs:anyAttribute}): such
 *                      elements and attributes are carried as they are, under their local names
 */
public record SchemaType(String name, List<SchemaProperty> properties, String documentation, boolean open) {

    public SchemaType {
        properties = properties == null ? List.of() : List.copyOf(properties);
    }

    public SchemaType(String name, List<SchemaProperty> properties, String documentation) {
        this(name, properties, documentation, false);
    }

    /** Local part of {@link #name}, the name the type is published under; {@code null} for an anonymous type. */
    public String localName() {
        if (name == null) {
            return null;
        }
        int end = name.indexOf('}');
        return end >= 0 ? name.substring(end + 1) : name;
    }
}
