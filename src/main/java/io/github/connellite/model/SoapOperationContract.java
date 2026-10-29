package io.github.connellite.model;

import java.util.List;
import java.util.Map;

/**
 * What a SOAP call of one operation needs to build the request payload from JSON and read the response back.
 *
 * @param operation     QName of the {@code wsdl:operation} ({@link javax.xml.namespace.QName#toString()} form), in the
 *                      namespace of its port type
 * @param inputElement  QName of the request body element, {@code null} when the input has no element part
 * @param outputElement QName of the response body element, {@code null} when the output has no element part
 * @param oneWay        the operation has no output message
 * @param inputType     type of the request body element, {@code null} for an element without fields
 * @param outputType    type of the response body element, {@code null} for an element without fields
 * @param headers       SOAP headers of the request
 * @param faults        declared faults, recognised by their detail element
 * @param types         named types the types above refer to
 * @param inputSimple   the request element is of a simple type: {@code inputType} holds it as its only property,
 *                      named after the element, whose value is the element's text
 * @param outputSimple  the same for the response element and {@code outputType}
 */
public record SoapOperationContract(
        String operation,
        String inputElement,
        String outputElement,
        boolean oneWay,
        SchemaType inputType,
        SchemaType outputType,
        List<SoapHeaderModel> headers,
        List<SoapFaultModel> faults,
        Map<String, SchemaType> types,
        boolean inputSimple,
        boolean outputSimple
) {

    public SoapOperationContract {
        headers = headers == null ? List.of() : List.copyOf(headers);
        faults = faults == null ? List.of() : List.copyOf(faults);
        types = types == null ? Map.of() : types;
    }

    public SoapOperationContract(String operation, String inputElement, String outputElement, boolean oneWay,
                                 SchemaType inputType, SchemaType outputType, List<SoapHeaderModel> headers,
                                 List<SoapFaultModel> faults, Map<String, SchemaType> types) {
        this(operation, inputElement, outputElement, oneWay, inputType, outputType, headers, faults, types, false,
                false);
    }
}
