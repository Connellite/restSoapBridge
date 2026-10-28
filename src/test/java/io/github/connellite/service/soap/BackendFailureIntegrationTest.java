package io.github.connellite.service.soap;

import io.github.connellite.config.BridgeProperties;
import io.github.connellite.exception.SoapFaultException;
import io.github.connellite.exception.UpstreamException;
import io.github.connellite.mapper.path.SoapArgument;
#if SPRING_BOOT_3
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;
#else
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
#endif
import org.apache.cxf.transport.http_jetty.JettyHTTPServerEngineFactory;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real CXF failures and the status the bridge assigns to them.
 */
class BackendFailureIntegrationTest {

    @Test
    void unreachableWsdlIsServiceUnavailable() throws Exception {
        String wsdlUrl = "http://localhost:" + freePort() + "/services/Missing?wsdl";

        UpstreamException exception = assertThrows(UpstreamException.class,
                () -> new SoapTestBackendInvoker().invoker().invoke(wsdlUrl, "ping", List.of()));

        assertEquals(503, exception.getHttpStatus());
        assertTrue(exception.getMessage().startsWith("Cannot load WSDL " + wsdlUrl), exception.getMessage());
    }

    @Test
    void endpointRemovedThenPortClosedAfterClientCreation() throws Exception {
        SoapTestBackend backend = SoapTestBackend.start("Failures", FailureServiceImpl.class, new FailureServiceImpl());
        SoapInvoker invoker = backend.invoker(SoapTestBackend.properties());
        assertEquals("pong", invoker.invoke(backend.wsdlUrl(), "ping", List.of()));

        // Endpoint gone, HTTP server still up: the backend answers 404 -> bad gateway.
        backend.close();
        UpstreamException notFound = assertThrows(UpstreamException.class,
                () -> invoker.invoke(backend.wsdlUrl(), "ping", List.of()));
        assertEquals(502, notFound.getHttpStatus(), notFound.getMessage());
        assertTrue(notFound.getMessage().startsWith("SOAP backend answered HTTP 404"), notFound.getMessage());

        // Nothing listens on the port any more: connection refused -> service unavailable.
        JettyHTTPServerEngineFactory.destroyForPort(new URL(backend.wsdlUrl()).getPort());
        UpstreamException refused = assertThrows(UpstreamException.class,
                () -> invoker.invoke(backend.wsdlUrl(), "ping", List.of()));
        assertEquals(503, refused.getHttpStatus(), refused.getMessage());
    }

    @Test
    void slowBackendIsGatewayTimeout() throws Exception {
        try (SoapTestBackend backend = SoapTestBackend.start("Failures", FailureServiceImpl.class, new FailureServiceImpl())) {
            BridgeProperties properties = SoapTestBackend.properties();
            properties.getAuto().setReceiveTimeout(Duration.ofMillis(300));

            UpstreamException exception = assertThrows(UpstreamException.class,
                    () -> backend.invoker(properties).invoke(backend.wsdlUrl(), "slow",
                            List.of(new SoapArgument("millis", 2000))));

            assertEquals(504, exception.getHttpStatus());
        }
    }

    @Test
    void declaredFaultHasNameFaultInfoAndConfiguredStatus() throws Exception {
        try (SoapTestBackend backend = SoapTestBackend.start("Failures", FailureServiceImpl.class, new FailureServiceImpl())) {
            BridgeProperties properties = SoapTestBackend.properties();
            properties.getErrors().setFaultStatus(Map.of("ConflictException", 409));

            SoapFaultException fault = assertThrows(SoapFaultException.class,
                    () -> backend.invoker(properties).invoke(backend.wsdlUrl(), "create",
                            List.of(new SoapArgument("name", "dup"))));

            assertEquals("ConflictException", fault.getFaultName());
            assertEquals(409, fault.getHttpStatus());
            assertEquals("Already exists: dup", fault.getMessage());
            assertEquals(42, fault.getFaultInfo().get("code").asInt(), fault.getFaultInfo().toString());
            // CXF builds the declared exception from the fault bean and drops the SoapFault: no fault code.
            assertNull(fault.getFaultCode());
        }
    }

    @Test
    void undeclaredServerExceptionIsSoapFaultBadGateway() throws Exception {
        try (SoapTestBackend backend = SoapTestBackend.start("Failures", FailureServiceImpl.class, new FailureServiceImpl())) {
            SoapFaultException fault = assertThrows(SoapFaultException.class,
                    () -> backend.invoker(SoapTestBackend.properties()).invoke(backend.wsdlUrl(), "crash", List.of()));

            assertEquals("SoapFault", fault.getFaultName());
            assertEquals(502, fault.getHttpStatus());
            assertEquals("nFolder == null", fault.getMessage());
        }
    }

    @Test
    void wrongArgumentTypeStaysClientErrorNotBackendError() throws Exception {
        try (SoapTestBackend backend = SoapTestBackend.start("Failures", FailureServiceImpl.class, new FailureServiceImpl())) {
            RuntimeException exception = assertThrows(RuntimeException.class,
                    () -> backend.invoker(SoapTestBackend.properties()).invoke(backend.wsdlUrl(), "slow",
                            List.of(new SoapArgument("millis", Map.of("not", "a number")))));

            assertInstanceOf(io.github.connellite.exception.RequestValidationException.class, exception);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Invoker without a running backend. */
    private static final class SoapTestBackendInvoker {
        SoapInvoker invoker() {
            BridgeProperties properties = SoapTestBackend.properties();
            return new SoapInvoker(new SoapClientPool(properties, new SoapClientFactory(properties),
                    new org.springframework.beans.factory.support.StaticListableBeanFactory()
                            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)), properties);
        }
    }

    public static class ConflictException extends Exception {
        private int code;

        public ConflictException() {
        }

        public ConflictException(String message, int code) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }
    }

    @WebService(name = "Failures", serviceName = "FailuresService", targetNamespace = "http://failures.test/")
    public static class FailureServiceImpl {

        @WebMethod
        public String ping() {
            return "pong";
        }

        @WebMethod
        public String slow(@WebParam(name = "millis") int millis) throws InterruptedException {
            Thread.sleep(millis);
            return "done";
        }

        @WebMethod
        public String create(@WebParam(name = "name") String name) throws ConflictException {
            throw new ConflictException("Already exists: " + name, 42);
        }

        @WebMethod
        public String crash() {
            throw new IllegalStateException("nFolder == null");
        }
    }
}
