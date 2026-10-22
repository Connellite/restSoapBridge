package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.OpenApiExampleFactory;
import io.github.connellite.config.WsdlUrlCollector;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.model.DiscoveredSoapService;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.service.auto.AutoMappingGenerator;
import io.github.connellite.service.auto.WsdlParser;
import io.github.connellite.service.auto.WsdlServiceDiscovery;
import io.github.connellite.service.impl.BridgeServiceImpl;
import io.github.connellite.service.validation.RequestSchemaValidator;
#if SPRING_BOOT_3
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;
#else
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
#endif
import io.swagger.v3.oas.models.media.ObjectSchema;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End to end through WSDL parsing, mapping generation and invocation for an operation with a
 * {@code java.util.Map} parameter (JAXB {@code params/entry[key,value]} with an anonymous wrapper type).
 */
class SoapMapParameterIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Server server;
    private static String wsdlUrl;

    @BeforeAll
    static void startBackend() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        JaxWsServerFactoryBean factory = new JaxWsServerFactoryBean();
        factory.setServiceClass(RemoteMethodCallImpl.class);
        factory.setServiceBean(new RemoteMethodCallImpl());
        factory.setAddress("http://localhost:" + port + "/services/RemoteMethodCall");
        server = factory.create();
        wsdlUrl = "http://localhost:" + port + "/services/RemoteMethodCall?wsdl";
    }

    @AfterAll
    static void stopBackend() {
        if (server != null) {
            server.destroy();
        }
    }

    @Test
    void callsOperationWithMapParameter() throws Exception {
        BridgeProperties properties = properties();
        MappingDefinition mapping = mapping(properties, "call");

        ObjectNode response = service(properties).execute(mapping, json("""
                {"method": "search", "params": {"query": "abc", "limit": "10"}}
                """));

        assertEquals("search{limit=10, query=abc}", response.get("return").asText());
    }

    @Test
    void mapParameterIsPublishedAsObjectSchema() throws Exception {
        MappingDefinition mapping = mapping(properties(), "call");

        ObjectSchema schema = OpenApiExampleFactory.schemaWithXsdExamples(mapping.getRequestSchemaFields(), List.of());

        assertEquals("object", schema.getProperties().get("params").getType());
        assertEquals(Map.of(), ((Map<?, ?>) schema.getExample()).get("params"));
    }

    @Test
    void stringInsteadOfMapIsRejectedBySchemaValidation() throws Exception {
        BridgeProperties properties = properties();
        MappingDefinition mapping = mapping(properties, "call");

        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> service(properties).execute(mapping, json("""
                        {"method": "search", "params": "query=abc"}
                        """)));

        assertEquals(List.of("$.params: string found, object expected"), exception.getErrors());
    }

    @Test
    void stringInsteadOfMapIsRejectedBeforeCxfWhenValidationIsOff() throws Exception {
        BridgeProperties properties = properties();
        properties.getValidation().setEnabled(false);
        MappingDefinition mapping = mapping(properties, "call");

        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> service(properties).execute(mapping, json("""
                        {"method": "search", "params": "query=abc"}
                        """)));

        assertEquals(1, exception.getErrors().size(), exception.getErrors().toString());
        assertTrue(exception.getErrors().get(0).startsWith("$.params: string cannot be converted to "),
                exception.getErrors().toString());
    }

    private static BridgeProperties properties() {
        BridgeProperties properties = new BridgeProperties();
        BridgeProperties.AutoMapping auto = new BridgeProperties.AutoMapping();
        auto.setPathPrefix("/api");
        auto.setHttpMethod("POST");
        properties.setAuto(auto);
        return properties;
    }

    private static ObjectNode json(String text) throws Exception {
        return (ObjectNode) MAPPER.readTree(text);
    }

    private static MappingDefinition mapping(BridgeProperties properties, String operation) throws Exception {
        WsdlServiceDiscovery discovery = mock(WsdlServiceDiscovery.class);
        WsdlUrlCollector collector = mock(WsdlUrlCollector.class);
        when(collector.wsdlUrls()).thenReturn(List.of());
        when(discovery.discover(null, List.of())).thenReturn(List.of(new DiscoveredSoapService(
                "RemoteMethodCall", wsdlUrl, new WsdlParser().parseOperations(wsdlUrl))));
        return new AutoMappingGenerator(properties, discovery, collector).generate().stream()
                .filter(candidate -> candidate.getSoap().getOperation().equals(operation))
                .findFirst()
                .orElseThrow();
    }

    private static BridgeServiceImpl service(BridgeProperties properties) {
        SoapClientPool pool = new SoapClientPool(properties, new SoapClientFactory(properties),
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        return new BridgeServiceImpl(new SoapInvoker(pool, properties), new RequestSchemaValidator(properties));
    }

    @WebService(name = "RemoteMethodCall", serviceName = "RemoteMethodCallService", targetNamespace = "http://ws.test/")
    public static class RemoteMethodCallImpl {

        @WebMethod
        public String call(@WebParam(name = "method") String method, @WebParam(name = "params") Map<String, String> params) {
            return method + new TreeMap<>(params);
        }
    }
}
