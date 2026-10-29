package io.github.connellite.model;

import java.util.List;

/**
 * @param inputHeaders  SOAP headers the WSDL binding declares for the request ({@code soap:header})
 * @param faults        faults the WSDL declares for the operation
 * @param documentation {@code wsdl:documentation} of the operation, or {@code null}
 * @param endpoint      the port these operations were read from; {@code null} for a WSDL without SOAP ports
 */
public record WsdlOperationModel(
        String name,
        String inputElement,
        List<String> inputFields,
        List<SchemaField> inputSchemaFields,
        String outputElement,
        List<String> outputFields,
        List<SchemaField> outputSchemaFields,
        List<SoapHeaderModel> inputHeaders,
        List<SoapFaultModel> faults,
        String documentation,
        WsdlEndpoint endpoint
) {

    public WsdlOperationModel {
        inputHeaders = inputHeaders == null ? List.of() : List.copyOf(inputHeaders);
        faults = faults == null ? List.of() : List.copyOf(faults);
    }

    public WsdlOperationModel(String name, String inputElement, List<String> inputFields,
                              List<SchemaField> inputSchemaFields, String outputElement, List<String> outputFields,
                              List<SchemaField> outputSchemaFields, List<SoapHeaderModel> inputHeaders) {
        this(name, inputElement, inputFields, inputSchemaFields, outputElement, outputFields, outputSchemaFields,
                inputHeaders, List.of(), null, null);
    }

    public WsdlOperationModel(String name, String inputElement, List<String> inputFields,
                              List<SchemaField> inputSchemaFields, String outputElement, List<String> outputFields,
                              List<SchemaField> outputSchemaFields) {
        this(name, inputElement, inputFields, inputSchemaFields, outputElement, outputFields, outputSchemaFields,
                List.of());
    }
}
