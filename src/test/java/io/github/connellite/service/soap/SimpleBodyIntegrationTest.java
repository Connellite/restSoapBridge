package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.OpenApiExampleFactory;
import io.github.connellite.exception.RequestValidationException;
import io.swagger.v3.oas.models.media.ObjectSchema;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BARE operations whose body element is of a simple type ({@code <echo>hi</echo>}): the value travels as the JSON
 * field named after the element, both ways.
 */
class SimpleBodyIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SoapTestBackend backend;

    @BeforeAll
    static void start() throws Exception {
        backend = SoapTestBackend.start("Echo", EchoImpl.class, new EchoImpl());
    }

    @AfterAll
    static void stop() {
        backend.close();
    }

    @Test
    void sendsAndReadsTheElementText() throws Exception {
        ObjectNode response = backend.execute("echo", MAPPER.readTree("{\"echo\": \"hi\"}"));

        assertEquals("hi!", response.path("echoResponse").asText(), response.toString());
    }

    @Test
    void typesASimpleNumericBody() throws Exception {
        ObjectNode response = backend.execute("twice", MAPPER.readTree("{\"number\": \"21\"}"));

        assertEquals(42, response.path("doubled").asInt(), response.toString());
        assertTrue(response.path("doubled").isInt(), response.toString());
    }

    @Test
    void publishesTheValueAsTheOnlyRequestField() {
        ObjectSchema schema = OpenApiExampleFactory.schemaWithXsdExamples(
                backend.mapping("echo").getRequestSchemaFields(), List.of());

        assertEquals(List.of("echo"), List.copyOf(schema.getProperties().keySet()));
        assertEquals(List.of("echo"), schema.getRequired());
    }

    @Test
    void rejectsOtherFields() {
        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> backend.execute("echo", MAPPER.readTree("{\"echo\": \"hi\", \"text\": \"x\"}")));

        assertTrue(exception.getErrors().toString().contains("text"), exception.getErrors().toString());
    }

    @WebService(name = "Echo", serviceName = "EchoService", targetNamespace = "http://simple.test/")
    @SOAPBinding(parameterStyle = SOAPBinding.ParameterStyle.BARE)
    public static class EchoImpl {

        @WebMethod
        @WebResult(name = "echoResponse")
        public String echo(@WebParam(name = "echo") String text) {
            return text + "!";
        }

        @WebMethod
        @WebResult(name = "doubled")
        public int twice(@WebParam(name = "number") int number) {
            return number * 2;
        }
    }
}
