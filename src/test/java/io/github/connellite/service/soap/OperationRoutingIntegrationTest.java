package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.connellite.config.BridgeOpenApiBuilder;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.controller.BridgeController;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import io.github.connellite.service.impl.BridgeServiceImpl;
import io.github.connellite.service.validation.RequestSchemaValidator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.parameters.Parameter;
#if SPRING_BOOT_3
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;
#else
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
#endif
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bridge.operations}: own paths with variables, GET with query parameters, disabled operations.
 */
class OperationRoutingIntegrationTest {

    private static SoapTestBackend backend;
    private static BridgeController controller;
    private static MappingRegistry registry;

    @BeforeAll
    static void start() throws Exception {
        BridgeProperties properties = SoapTestBackend.properties();
        properties.getOperations().put("Documents", Map.of(
                "getDocument", override("/documents/{uuid}", "get"),
                "findDocuments", override("/documents", "GET"),
                "purge", disabled()
        ));
        backend = SoapTestBackend.start("Documents", DocumentsImpl.class, new DocumentsImpl(), properties);
        registry = new MappingRegistry(backend.mappings());
        BridgeServiceImpl service = new BridgeServiceImpl(backend.invoker(properties), new RequestSchemaValidator(properties));
        controller = new BridgeController(registry, service::execute, properties);
    }

    @AfterAll
    static void stop() {
        backend.close();
    }

    @Test
    void overridesPathsAndMethodsAndDropsDisabledOperations() {
        assertEquals(Map.of(
                        "getDocument", "GET /api/documents/{uuid}",
                        "findDocuments", "GET /api/documents",
                        "rename", "POST /api/Documents/rename"),
                Map.copyOf(backend.mappings().stream().collect(java.util.stream.Collectors.toMap(
                        mapping -> mapping.getSoap().getOperation(),
                        mapping -> mapping.getMethod() + " " + mapping.getPath()))));
    }

    @Test
    void getTakesPathVariablesAndQueryParameters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents/d-42");
        request.addParameter("token", "t-1");
        request.addParameter("version", "3");

        ResponseEntity<JsonNode> response = controller.handle(request, null);

        assertEquals("d-42@3 by t-1", response.getBody().path("return").asText(), response.getBody().toString());
    }

    @Test
    void repeatedQueryParameterBecomesArray() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.addParameter("tags", "a", "b");

        ResponseEntity<JsonNode> response = controller.handle(request, null);

        assertEquals("[a, b]", response.getBody().path("return").asText(), response.getBody().toString());
    }

    @Test
    void singleQueryParameterOfArrayFieldIsArrayToo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents");
        request.addParameter("tags", "only");

        ResponseEntity<JsonNode> response = controller.handle(request, null);

        assertEquals("[only]", response.getBody().path("return").asText(), response.getBody().toString());
    }

    @Test
    void unknownQueryParameterIsRejected() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/documents/d-1");
        request.addParameter("uid", "x");

        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> controller.handle(request, null));

        assertTrue(exception.getErrors().stream().anyMatch(error -> error.contains("allowed fields")),
                exception.getErrors().toString());
    }

    @Test
    void openApiDescribesGetAsParametersWithoutBody() {
        OpenAPI openApi = new BridgeOpenApiBuilder(registry,
                new StaticListableBeanFactory().getBeanProvider(BuildProperties.class)).build();

        Operation getDocument = openApi.getPaths().get("/api/documents/{uuid}").getGet();
        assertNull(getDocument.getRequestBody());
        assertEquals(Map.of("uuid", "path", "token", "query", "version", "query"), Map.copyOf(
                getDocument.getParameters().stream().collect(java.util.stream.Collectors.toMap(
                        Parameter::getName, Parameter::getIn))));
        Parameter uuid = getDocument.getParameters().get(0);
        assertEquals(Boolean.TRUE, uuid.getRequired());

        Parameter tags = openApi.getPaths().get("/api/documents").getGet().getParameters().get(0);
        assertEquals("tags", tags.getName());
        assertEquals("array", tags.getSchema().getType());
        assertTrue(openApi.getPaths().get("/api/Documents/rename").getPost().getRequestBody() != null);
    }

    @Test
    void pathVariableMustNameRequestField() {
        BridgeProperties properties = SoapTestBackend.properties();
        properties.getOperations().put("Documents", Map.of("getDocument", override("/documents/{id}", null)));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> SoapTestBackend.start("Documents", DocumentsImpl.class, new DocumentsImpl(), properties).close());

        assertTrue(exception.getMessage().contains("variable {id} is not a request field"), exception.getMessage());
    }

    @Test
    void duplicateRouteFailsTheStart() {
        MappingDefinition first = backend.mapping("findDocuments");
        MappingDefinition second = new MappingDefinition();
        second.setPath("/api/documents/");
        second.setMethod("GET");
        second.setSoap(backend.mapping("rename").getSoap());

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new MappingRegistry(List.of(first, second)));

        assertTrue(exception.getMessage().startsWith("Duplicate endpoint GET /api/documents"), exception.getMessage());
    }

    private static BridgeProperties.OperationOverride override(String path, String method) {
        BridgeProperties.OperationOverride override = new BridgeProperties.OperationOverride();
        override.setPath(path);
        override.setMethod(method);
        return override;
    }

    private static BridgeProperties.OperationOverride disabled() {
        BridgeProperties.OperationOverride override = new BridgeProperties.OperationOverride();
        override.setEnabled(false);
        return override;
    }

    @WebService(name = "Documents", serviceName = "DocumentsService", targetNamespace = "http://routing.example.test")
    public static class DocumentsImpl {

        @WebMethod
        public String getDocument(@WebParam(name = "token") String token, @WebParam(name = "uuid") String uuid,
                                  @WebParam(name = "version") int version) {
            return uuid + "@" + version + " by " + token;
        }

        @WebMethod
        public String findDocuments(@WebParam(name = "tags") List<String> tags) {
            return String.valueOf(tags);
        }

        @WebMethod
        public String rename(@WebParam(name = "uuid") String uuid, @WebParam(name = "name") String name) {
            return uuid + "=" + name;
        }

        @WebMethod
        public void purge() {
        }
    }
}
