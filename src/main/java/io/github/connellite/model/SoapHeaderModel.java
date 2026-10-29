package io.github.connellite.model;

import java.util.List;

/**
 * A request SOAP header of the WSDL binding: the message part and the global element it carries.
 *
 * @param schemaFields fields of a complex header element; a simple one is a single field with an empty path
 * @param property     the header part as an (optional) property named after the part, or {@code null}
 * @param elementName  QName of the header element ({@link javax.xml.namespace.QName#toString()} form), or {@code null}
 */
public record SoapHeaderModel(String part, String element, List<SchemaField> schemaFields, SchemaProperty property,
                              String elementName) {

    public SoapHeaderModel(String part, String element, List<SchemaField> schemaFields) {
        this(part, element, schemaFields, null, null);
    }
}
