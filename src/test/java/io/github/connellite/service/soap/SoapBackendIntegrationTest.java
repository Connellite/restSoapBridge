package io.github.connellite.service.soap;

import io.github.connellite.config.BridgeProperties;
import io.github.connellite.correlation.CorrelationId;
import io.github.connellite.exception.SoapFaultException;
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
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.headers.Header;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.security.SecurityContext;
import org.apache.cxf.ws.security.wss4j.WSS4JInInterceptor;
import org.apache.wss4j.common.ConfigurationConstants;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.w3c.dom.Element;

import javax.security.auth.callback.Callback;
import javax.xml.namespace.QName;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Calls a real in-process CXF endpoint that requires a WS-Security UsernameToken and records what it receives.
 */
class SoapBackendIntegrationTest {

    private static final String WSS_USER = "soap-user";
    private static final String WSS_PASSWORD = "s3cret";
    private static final QName CORRELATION_HEADER = new QName("urn:rest-soap-bridge", "CorrelationId");
    private static final Map<String, Object> RECEIVED = new ConcurrentHashMap<>();

    private static Server server;
    private static String wsdlUrl;

    @BeforeAll
    static void startBackend() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        Map<String, Object> wss = new HashMap<>();
        wss.put(ConfigurationConstants.ACTION, ConfigurationConstants.USERNAME_TOKEN);
        wss.put(ConfigurationConstants.PASSWORD_TYPE, "PasswordDigest");
        wss.put(ConfigurationConstants.PW_CALLBACK_REF, (javax.security.auth.callback.CallbackHandler) callbacks -> {
            for (Callback callback : callbacks) {
                if (callback instanceof WSPasswordCallback password && WSS_USER.equals(password.getIdentifier())) {
                    password.setPassword(WSS_PASSWORD);
                }
            }
        });

        JaxWsServerFactoryBean factory = new JaxWsServerFactoryBean();
        factory.setServiceClass(GreeterImpl.class);
        factory.setServiceBean(new GreeterImpl());
        factory.setAddress("http://localhost:" + port + "/services/Greeter");
        factory.getInInterceptors().add(new WSS4JInInterceptor(wss));
        factory.getInInterceptors().add(new RecordingInterceptor());
        server = factory.create();
        wsdlUrl = "http://localhost:" + port + "/services/Greeter?wsdl";
    }

    @AfterAll
    static void stopBackend() {
        if (server != null) {
            server.destroy();
        }
    }

    @AfterEach
    void clear() {
        RECEIVED.clear();
        MDC.remove(CorrelationId.MDC_KEY);
    }

    @Test
    void sendsCredentialsForwardedHeadersAndCorrelationId() {
        BridgeProperties properties = backendProperties(WSS_PASSWORD);
        SoapInvoker invoker = invoker(properties);
        MDC.put(CorrelationId.MDC_KEY, "it-123");

        Object result = invoker.invoke(wsdlUrl, "greet", List.of(new SoapArgument("name", "Bob")),
                Map.of("Accept-Language", List.of("ru")));

        assertEquals("Hello, Bob", result);
        assertEquals(WSS_USER, RECEIVED.get("wssUser"));
        assertEquals("basic-user", RECEIVED.get("basicUser"));
        assertEquals("basic-pass", RECEIVED.get("basicPassword"));
        assertEquals(List.of("ru"), header("Accept-Language"));
        assertEquals(List.of("it-123"), header("X-Correlation-Id"));
        assertEquals("it-123", RECEIVED.get("soapCorrelationId"));
    }

    @Test
    void wrongWsSecurityPasswordIsReportedAsSoapFault() {
        SoapInvoker invoker = invoker(backendProperties("wrong"));

        SoapFaultException fault = assertThrows(SoapFaultException.class,
                () -> invoker.invoke(wsdlUrl, "greet", List.of(new SoapArgument("name", "Bob"))));

        assertEquals("SoapFault", fault.getFaultName());
    }

    private static BridgeProperties backendProperties(String wssPassword) {
        BridgeProperties properties = new BridgeProperties();
        properties.setAuto(new BridgeProperties.AutoMapping());
        properties.getBackend().getBasicAuth().setUsername("basic-user");
        properties.getBackend().getBasicAuth().setPassword("basic-pass");
        properties.getBackend().getWsSecurity().setUsername(WSS_USER);
        properties.getBackend().getWsSecurity().setPassword(wssPassword);
        properties.getBackend().getWsSecurity().setPasswordType(BridgeProperties.WsSecurity.PasswordType.DIGEST);
        properties.getCorrelation().getSoapHeader().setEnabled(true);
        return properties;
    }

    private static SoapInvoker invoker(BridgeProperties properties) {
        SoapClientPool pool = new SoapClientPool(properties, new SoapClientFactory(properties),
                new StaticListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        return new SoapInvoker(pool, properties);
    }

    @SuppressWarnings("unchecked")
    private static List<String> header(String name) {
        return ((Map<String, List<String>>) RECEIVED.get("httpHeaders")).get(name);
    }

    @WebService(name = "Greeter", serviceName = "GreeterService", targetNamespace = "urn:test:greeter")
    public static class GreeterImpl {

        @WebMethod
        public String greet(@WebParam(name = "name") String name) {
            return "Hello, " + name;
        }
    }

    private static final class RecordingInterceptor extends AbstractPhaseInterceptor<SoapMessage> {

        private RecordingInterceptor() {
            super(Phase.PRE_INVOKE);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleMessage(SoapMessage message) throws Fault {
            Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            headers.putAll((Map<String, List<String>>) message.get(Message.PROTOCOL_HEADERS));
            RECEIVED.put("httpHeaders", headers);

            AuthorizationPolicy authorization = message.get(AuthorizationPolicy.class);
            if (authorization != null) {
                RECEIVED.put("basicUser", authorization.getUserName());
                RECEIVED.put("basicPassword", authorization.getPassword());
            }
            SecurityContext securityContext = message.get(SecurityContext.class);
            if (securityContext != null && securityContext.getUserPrincipal() != null) {
                RECEIVED.put("wssUser", securityContext.getUserPrincipal().getName());
            }
            Header correlation = message.getHeader(CORRELATION_HEADER);
            if (correlation != null && correlation.getObject() instanceof Element element) {
                RECEIVED.put("soapCorrelationId", element.getTextContent());
            }
        }
    }
}
