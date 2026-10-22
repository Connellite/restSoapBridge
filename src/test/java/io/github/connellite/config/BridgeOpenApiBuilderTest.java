package io.github.connellite.config;

import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeOpenApiBuilderTest {

    private static final String COMPONENT_KEY = "^[a-zA-Z0-9.\\-_]+$";

    @Test
    void build_usesSpecCompliantComponentKeys() {
        MappingDefinition mapping = mapping();

        OpenAPI openApi = new BridgeOpenApiBuilder(new MappingRegistry(List.of(mapping)), buildProperties(null)).build();

        assertEquals(List.of("Auth_Service_loginRequest", "Auth_Service_loginResponse"),
                List.copyOf(openApi.getComponents().getSchemas().keySet()));
        openApi.getComponents().getSchemas().keySet()
                .forEach(key -> assertTrue(key.matches(COMPONENT_KEY), key));
        assertEquals("#/components/schemas/Auth_Service_loginRequest",
                openApi.getPaths().get("/api/Auth Service/login").getPost()
                        .getRequestBody().getContent().get("application/json").getSchema().get$ref());
    }

    @Test
    void build_takesVersionFromBuildInfo() {
        Properties info = new Properties();
        info.setProperty("version", "2.3.4");

        OpenAPI openApi = new BridgeOpenApiBuilder(new MappingRegistry(List.of(mapping())), buildProperties(info)).build();

        assertEquals("2.3.4", openApi.getInfo().getVersion());
    }

    @Test
    void build_fallsBackWhenBuildInfoIsMissing() {
        OpenAPI openApi = new BridgeOpenApiBuilder(new MappingRegistry(List.of(mapping())), buildProperties(null)).build();

        assertEquals("unknown", openApi.getInfo().getVersion());
    }

    private static ObjectProvider<BuildProperties> buildProperties(Properties info) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (info != null) {
            beanFactory.addBean("buildProperties", new BuildProperties(info));
        }
        return beanFactory.getBeanProvider(BuildProperties.class);
    }

    private static MappingDefinition mapping() {
        MappingDefinition mapping = new MappingDefinition();
        mapping.setPath("/api/Auth Service/login");
        mapping.setMethod("POST");
        MappingDefinition.SoapTarget soap = new MappingDefinition.SoapTarget();
        soap.setService("Auth Service");
        soap.setOperation("login");
        soap.setWsdl("http://localhost/services/Auth?wsdl");
        mapping.setSoap(soap);
        mapping.setRequest(Map.of("$.user", "$.login.user"));
        return mapping;
    }
}
