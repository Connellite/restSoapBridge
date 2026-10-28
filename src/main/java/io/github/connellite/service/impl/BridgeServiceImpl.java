package io.github.connellite.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.exception.UpstreamException;
import io.github.connellite.mapper.PathResolver;
import io.github.connellite.mapper.path.SoapArgument;
import io.github.connellite.mapper.path.SoapJsonMapper;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.service.BridgeService;
import io.github.connellite.service.soap.SoapInvoker;
import io.github.connellite.service.validation.RequestSchemaValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BridgeServiceImpl implements BridgeService {

    private final SoapInvoker soapInvoker;
    private final RequestSchemaValidator requestSchemaValidator;

    @Override
    public ObjectNode execute(MappingDefinition mapping, JsonNode requestBody, Map<String, List<String>> backendHeaders) {
        requestSchemaValidator.validate(mapping, requestBody);
        MappingDefinition.SoapTarget soap = mapping.getSoap();
        List<SoapArgument> arguments = PathResolver.buildSoapArguments(mapping.getRequest(), requestBody);
        Object soapResult = soapInvoker.invoke(soap.getWsdl(), soap.getOperation(), arguments, backendHeaders);
        return toJsonBody(mapping, soapResult);
    }

    private ObjectNode toJsonBody(MappingDefinition mapping, Object soapResult) {
        Map<String, String> responseMappings = mapping.getResponse();
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        boolean anyValue = false;
        for (Map.Entry<String, String> entry : responseMappings.entrySet()) {
            Object value = PathResolver.readFromSoapResult(soapResult, entry.getKey());
            anyValue |= value != null;
            PathResolver.writeToJsonBody(body, entry.getValue(), value);
        }
        if (!anyValue && !responseMappings.isEmpty()) {
            requireNoUnmappedData(mapping, soapResult);
        }
        return body;
    }

    /**
     * Every mapped field is null although the SOAP response carries data: the response paths do not match
     * the actual payload shape (e.g. a wrapper bean where a DTO was expected). Answering 200 with a skeleton
     * of nulls would hide that, so fail with 502 and show what came back.
     */
    private static void requireNoUnmappedData(MappingDefinition mapping, Object soapResult) {
        if (soapResult == null) {
            return;
        }
        JsonNode actual = SoapJsonMapper.instance().valueToTree(soapResult);
        if (!hasData(actual)) {
            return;
        }
        String payload = actual.toString();
        throw UpstreamException.contractMismatch("SOAP response " + soapResult.getClass().getName() + " of "
                + mapping.getSoap().getService() + "." + mapping.getSoap().getOperation()
                + " does not match response mapping " + responseMappings(mapping) + "; payload: "
                + (payload.length() > 500 ? payload.substring(0, 500) + "..." : payload), null);
    }

    private static List<String> responseMappings(MappingDefinition mapping) {
        return List.copyOf(mapping.getResponse().keySet());
    }

    private static boolean hasData(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                if (hasData(child)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }
}
