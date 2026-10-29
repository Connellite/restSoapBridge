package io.github.connellite.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.mapper.PathResolver;
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
        ObjectNode body = soapInvoker.invoke(mapping.getSoap(), requestBody, backendHeaders);
        addAbsentFields(mapping, body);
        return body;
    }

    /**
     * Every field of the response schema is in the body: one the backend left out as {@code null}, so that clients
     * see the same set of fields whatever the backend sends.
     */
    private static void addAbsentFields(MappingDefinition mapping, ObjectNode body) {
        for (String path : mapping.getResponse().values()) {
            if (!present(body, path)) {
                PathResolver.writeToJsonBody(body, path, null);
            }
        }
    }

    private static boolean present(JsonNode body, String jsonPath) {
        String relative = jsonPath.startsWith("$.") ? jsonPath.substring(2) : jsonPath;
        JsonNode current = body;
        for (String segment : relative.split("\\.")) {
            if (current == null || current.isNull()) {
                // A null parent becomes an object holding the null field, as the response schema shows it.
                return false;
            }
            if (!current.isObject()) {
                return true;
            }
            current = current.get(segment);
        }
        return current != null;
    }
}
