package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.BridgeProperties;
#if SPRING_BOOT_3
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.jws.soap.SOAPBinding;
#else
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebResult;
import javax.jws.WebService;
import javax.jws.soap.SOAPBinding;
#endif
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RPC/literal operations: the bridge builds the operation wrapper from the parts and reads the response wrapper
 * back, with parts of simple and complex type.
 */
class RpcLiteralIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SoapTestBackend backend;

    @BeforeAll
    static void start() throws Exception {
        backend = SoapTestBackend.start("Rpc", RpcImpl.class, new RpcImpl());
    }

    @AfterAll
    static void stop() {
        backend.close();
    }

    @Test
    void callsWithSimplePartsAndReadsTheReturnPart() throws Exception {
        ObjectNode response = backend.execute("greet", MAPPER.readTree("{\"name\": \"Ann\", \"times\": 2}"));

        assertEquals("Hello Ann Hello Ann", response.path("return").asText(), response.toString());
    }

    @Test
    void callsWithAComplexPart() throws Exception {
        ObjectNode response = backend.execute("describe", MAPPER.readTree("""
                {"person": {"name": "Bob", "age": 41}}
                """));

        assertEquals("Bob (41)", response.path("return").asText(), response.toString());
        assertEquals(List.of("$.person.age", "$.person.name"),
                backend.mapping("describe").getRequestSchemaFields().stream().map(field -> field.path()).sorted()
                        .toList());
    }

    @Test
    void kebabCasePathsWhenConfigured() throws Exception {
        BridgeProperties properties = SoapTestBackend.properties();
        properties.getAuto().setPathStyle(BridgeProperties.PathStyle.KEBAB_CASE);
        try (SoapTestBackend kebab = SoapTestBackend.start("RpcService", factory -> {
            factory.setServiceClass(RpcImpl.class);
            factory.setServiceBean(new RpcImpl());
        }, properties)) {
            assertEquals(Map.of("describe", "/api/rpc-service/describe", "greet", "/api/rpc-service/greet",
                            "sayHelloTwice", "/api/rpc-service/say-hello-twice"),
                    Map.copyOf(kebab.mappings().stream().collect(java.util.stream.Collectors.toMap(
                            mapping -> mapping.getSoap().getOperation(), mapping -> mapping.getPath()))));
        }
    }

    public static class Person {
        private String name;
        private int age;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getAge() {
            return age;
        }

        public void setAge(int age) {
            this.age = age;
        }
    }

    @WebService(name = "Rpc", serviceName = "RpcService", targetNamespace = "http://rpc.test/")
    @SOAPBinding(style = SOAPBinding.Style.RPC)
    public static class RpcImpl {

        @WebMethod
        @WebResult(name = "return", partName = "return")
        public String greet(@WebParam(name = "name", partName = "name") String name,
                            @WebParam(name = "times", partName = "times") int times) {
            return String.join(" ", java.util.Collections.nCopies(times, "Hello " + name));
        }

        @WebMethod
        @WebResult(name = "return", partName = "return")
        public String describe(@WebParam(name = "person", partName = "person") Person person) {
            return person.getName() + " (" + person.getAge() + ")";
        }

        @WebMethod
        @WebResult(name = "return", partName = "return")
        public String sayHelloTwice(@WebParam(name = "name", partName = "name") String name) {
            return name;
        }
    }
}
