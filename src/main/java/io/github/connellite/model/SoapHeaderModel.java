package io.github.connellite.model;

import java.util.List;

/**
 * A request SOAP header of the WSDL binding: the message part and the global element it carries.
 *
 * @param schemaFields fields of a complex header element; a simple one is a single field with an empty path
 */
public record SoapHeaderModel(String part, String element, List<SchemaField> schemaFields) {
}
