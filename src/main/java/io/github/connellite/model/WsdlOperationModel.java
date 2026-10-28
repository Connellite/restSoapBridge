package io.github.connellite.model;

import java.util.List;

/**
 * @param inputHeaders SOAP headers the WSDL binding declares for the request ({@code soap:header})
 */
public record WsdlOperationModel(
        String name,
        String inputElement,
        List<String> inputFields,
        List<SchemaField> inputSchemaFields,
        String outputElement,
        List<String> outputFields,
        List<SchemaField> outputSchemaFields,
        List<SoapHeaderModel> inputHeaders
) {

    public WsdlOperationModel {
        inputHeaders = inputHeaders == null ? List.of() : List.copyOf(inputHeaders);
    }

    public WsdlOperationModel(String name, String inputElement, List<String> inputFields,
                              List<SchemaField> inputSchemaFields, String outputElement, List<String> outputFields,
                              List<SchemaField> outputSchemaFields) {
        this(name, inputElement, inputFields, inputSchemaFields, outputElement, outputFields, outputSchemaFields,
                List.of());
    }
}
