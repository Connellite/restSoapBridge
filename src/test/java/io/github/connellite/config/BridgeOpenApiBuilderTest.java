package io.github.connellite.config;

import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeOpenApiBuilderTest {

    private static final String COMPONENT_KEY = "^[a-zA-Z0-9.\\-_]+$";

    @Test
    void build_usesSpecCompliantComponentKeys() {
        MappingDefinition mapping = new MappingDefinition();
        mapping.setPath("/api/Auth Service/login");
        mapping.setMethod("POST");
        MappingDefinition.SoapTarget soap = new MappingDefinition.SoapTarget();
        soap.setService("Auth Service");
        soap.setOperation("login");
        soap.setWsdl("http://localhost/services/Auth?wsdl");
        mapping.setSoap(soap);
        mapping.setRequest(Map.of("$.user", "$.login.user"));

        OpenAPI openApi = new BridgeOpenApiBuilder(new MappingRegistry(List.of(mapping))).build();

        assertEquals(List.of("Auth_Service_loginRequest", "Auth_Service_loginResponse"),
                List.copyOf(openApi.getComponents().getSchemas().keySet()));
        openApi.getComponents().getSchemas().keySet()
                .forEach(key -> assertTrue(key.matches(COMPONENT_KEY), key));
        assertEquals("#/components/schemas/Auth_Service_loginRequest",
                openApi.getPaths().get("/api/Auth Service/login").getPost()
                        .getRequestBody().getContent().get("application/json").getSchema().get$ref());
    }
}
