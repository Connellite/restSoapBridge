package io.github.connellite.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import io.github.connellite.model.SchemaField;
import io.github.connellite.service.BridgeService;
import io.swagger.v3.oas.annotations.Hidden;
#if SPRING_BOOT_3
import jakarta.servlet.http.HttpServletRequest;
#else
import javax.servlet.http.HttpServletRequest;
#endif
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UrlPathHelper;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Hidden
@RestController
@RequiredArgsConstructor
public class BridgeController {

    private static final Set<String> QUERY_METHODS = Set.of("GET", "DELETE");

    private final MappingRegistry mappingRegistry;
    private final BridgeService bridgeService;
    private final BridgeProperties bridgeProperties;

    public ResponseEntity<JsonNode> handle(HttpServletRequest request, @RequestBody(required = false) JsonNode body) throws Exception {
        if (mappingRegistry.getMappings().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No mappings loaded. SOAP service discovery may have failed."
            );
        }

        // Mapping paths are relative to the application, so strip the servlet context path (WAR deployments).
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        MappingDefinition mapping = mappingRegistry.find(request.getMethod(), path)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No mapping for " + request.getMethod() + " " + request.getRequestURI()
                ));

        JsonNode requestBody = requestFields(mapping, body, mappingRegistry.pathVariables(mapping, path),
                request.getParameterMap());
        JsonNode responseBody = bridgeService.execute(mapping, requestBody, backendHeaders(request));
        return ResponseEntity.ok(responseBody);
    }

    /**
     * Request fields of the operation: the JSON body, path variables and, for {@code GET} and {@code DELETE}
     * (no body), query parameters. A dotted name ({@code customer.name}) addresses a nested field, a repeated
     * parameter or one of an array field becomes an array. Path variables win over the body.
     */
    static JsonNode requestFields(MappingDefinition mapping, JsonNode body, Map<String, String> pathVariables,
                                  Map<String, String[]> queryParameters) {
        boolean fromQuery = QUERY_METHODS.contains(mapping.getMethod().toUpperCase(Locale.ROOT));
        if (pathVariables.isEmpty() && (!fromQuery || queryParameters.isEmpty())) {
            return body != null ? body : JsonNodeFactory.instance.objectNode();
        }
        if (body != null && !body.isObject()) {
            return body;
        }
        ObjectNode fields = body != null ? ((ObjectNode) body).deepCopy() : JsonNodeFactory.instance.objectNode();
        if (fromQuery) {
            Set<String> arrayFields = arrayFields(mapping);
            queryParameters.forEach((name, values) -> {
                if (values.length > 1 || arrayFields.contains(name)) {
                    ArrayNode array = JsonNodeFactory.instance.arrayNode();
                    Arrays.stream(values).forEach(array::add);
                    put(fields, name, array);
                } else if (values.length == 1) {
                    put(fields, name, JsonNodeFactory.instance.textNode(values[0]));
                }
            });
        }
        pathVariables.forEach((name, value) -> put(fields, name, JsonNodeFactory.instance.textNode(value)));
        return fields;
    }

    private static Set<String> arrayFields(MappingDefinition mapping) {
        if (mapping.getRequestSchemaFields() == null) {
            return Set.of();
        }
        return mapping.getRequestSchemaFields().stream()
                .filter(SchemaField::collection)
                .map(field -> field.path().startsWith("$.") ? field.path().substring(2) : field.path())
                .collect(Collectors.toSet());
    }

    private static void put(ObjectNode root, String dottedName, JsonNode value) {
        String[] segments = dottedName.split("\\.");
        ObjectNode current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            JsonNode child = current.get(segments[index]);
            current = child instanceof ObjectNode object ? object : current.putObject(segments[index]);
        }
        current.set(segments[segments.length - 1], value);
    }

    private Map<String, List<String>> backendHeaders(HttpServletRequest request) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : bridgeProperties.getBackend().getForwardHeaders()) {
            List<String> values = Collections.list(request.getHeaders(name));
            if (!values.isEmpty()) {
                headers.put(name, values);
            }
        }
        return headers;
    }
}
