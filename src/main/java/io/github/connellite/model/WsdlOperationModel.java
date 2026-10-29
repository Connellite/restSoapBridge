package io.github.connellite.model;

import java.util.List;
import java.util.Map;

/**
 * @param inputHeaders  SOAP headers the WSDL binding declares for the request ({@code soap:header})
 * @param faults        faults the WSDL declares for the operation
 * @param documentation {@code wsdl:documentation} of the operation, or {@code null}
 * @param endpoint      the port these operations were read from; {@code null} for a WSDL without SOAP ports
 * @param inputType     type of the request body element, or {@code null} when it has no fields
 * @param outputType    type of the response body element, or {@code null} when it has no fields
 * @param types         named types of the WSDL by QName, which the types above refer to
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
        WsdlEndpoint endpoint,
        SchemaType inputType,
        SchemaType outputType,
        Map<String, SchemaType> types
) {

    public WsdlOperationModel {
        inputHeaders = inputHeaders == null ? List.of() : List.copyOf(inputHeaders);
        faults = faults == null ? List.of() : List.copyOf(faults);
        // Not copied: every operation of a WSDL shares the one registry.
        types = types == null ? Map.of() : types;
    }

    public WsdlOperationModel(String name, String inputElement, List<String> inputFields,
                              List<SchemaField> inputSchemaFields, String outputElement, List<String> outputFields,
                              List<SchemaField> outputSchemaFields, List<SoapHeaderModel> inputHeaders) {
        this(name, inputElement, inputFields, inputSchemaFields, outputElement, outputFields, outputSchemaFields,
                inputHeaders, List.of(), null, null, null, null, null);
    }

    public WsdlOperationModel(String name, String inputElement, List<String> inputFields,
                              List<SchemaField> inputSchemaFields, String outputElement, List<String> outputFields,
                              List<SchemaField> outputSchemaFields) {
        this(name, inputElement, inputFields, inputSchemaFields, outputElement, outputFields, outputSchemaFields,
                List.of());
    }
}
