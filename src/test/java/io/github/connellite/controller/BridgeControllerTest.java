package io.github.connellite.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BridgeControllerTest {

    private final AtomicReference<Map<String, List<String>>> backendHeaders = new AtomicReference<>();
    private final BridgeProperties properties = new BridgeProperties();
    private final BridgeController controller = new BridgeController(
            new MappingRegistry(List.of(mapping("/api/Auth/login"))),
            (mapping, body, headers) -> {
                backendHeaders.set(headers);
                ObjectNode response = JsonNodeFactory.instance.objectNode();
                return response.put("operation", mapping.getSoap().getOperation());
            },
            properties
    );

    @Test
    void handle_resolvesMappingWithoutContextPath() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/Auth/login");

        ResponseEntity<JsonNode> response = controller.handle(request, null);

        assertEquals("login", response.getBody().get("operation").asText());
    }

    @Test
    void handle_stripsServletContextPathOfWarDeployment() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/restSoapBridge/api/Auth/login");
        request.setContextPath("/restSoapBridge");

        ResponseEntity<JsonNode> response = controller.handle(request, null);

        assertEquals("login", response.getBody().get("operation").asText());
    }

    @Test
    void handle_forwardsOnlyConfiguredHeadersToBackend() throws Exception {
        properties.getBackend().setForwardHeaders(List.of("Authorization", "Accept-Language", "X-Missing"));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/Auth/login");
        request.addHeader("Authorization", "Bearer abc");
        request.addHeader("Accept-Language", "ru");
        request.addHeader("Accept-Language", "en");
        request.addHeader("Cookie", "secret=1");

        controller.handle(request, null);

        assertEquals(Map.of(
                "Authorization", List.of("Bearer abc"),
                "Accept-Language", List.of("ru", "en")
        ), backendHeaders.get());
    }

    @Test
    void handle_returnsNotFoundForUnknownOperation() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/Auth/logout");

        assertThrows(ResponseStatusException.class, () -> controller.handle(request, null));
    }

    private static MappingDefinition mapping(String path) {
        MappingDefinition mapping = new MappingDefinition();
        mapping.setPath(path);
        mapping.setMethod("POST");
        MappingDefinition.SoapTarget soap = new MappingDefinition.SoapTarget();
        soap.setService("Auth");
        soap.setOperation("login");
        mapping.setSoap(soap);
        return mapping;
    }
}
