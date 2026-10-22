package io.github.connellite.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BridgeControllerTest {

    private final BridgeController controller = new BridgeController(
            new MappingRegistry(List.of(mapping("/api/Auth/login"))),
            (mapping, body) -> JsonNodeFactory.instance.objectNode().put("operation", mapping.getSoap().getOperation())
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
