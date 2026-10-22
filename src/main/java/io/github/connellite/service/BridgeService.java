package io.github.connellite.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.model.MappingDefinition;

import java.util.List;
import java.util.Map;

public interface BridgeService {

    /**
     * @param backendHeaders REST request headers to pass on to the SOAP backend as HTTP headers
     */
    ObjectNode execute(MappingDefinition mapping, JsonNode requestBody, Map<String, List<String>> backendHeaders) throws Exception;

    default ObjectNode execute(MappingDefinition mapping, JsonNode requestBody) throws Exception {
        return execute(mapping, requestBody, Map.of());
    }
}
