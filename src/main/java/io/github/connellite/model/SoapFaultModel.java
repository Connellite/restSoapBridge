package io.github.connellite.model;

import java.util.List;

/**
 * A fault the WSDL declares for an operation ({@code wsdl:fault}).
 *
 * @param name          name the bridge reports as {@code faultName}
 * @param schemaFields  fields of the fault detail element, shown as {@code faultInfo}
 * @param documentation {@code wsdl:documentation} of the fault, or {@code null}
 */
public record SoapFaultModel(String name, List<SchemaField> schemaFields, String documentation) {

    public SoapFaultModel {
        schemaFields = schemaFields == null ? List.of() : List.copyOf(schemaFields);
    }
}
