package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.WsdlUrlCollector;
import io.github.connellite.exception.SoapDiscoveryException;
import io.github.connellite.model.DiscoveredSoapService;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.WsdlOperationModel;
import io.github.connellite.service.auto.WsdlAuthenticator;
import io.github.connellite.service.auto.WsdlParser;
import io.github.connellite.service.auto.WsdlServiceDiscovery;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A backend behind HTTP Basic auth whose WSDL advertises an address the bridge cannot use: credentials reach
 * the services page, the WSDL (wsdl4j and the CXF dynamic client) and the SOAP call, and
 * {@code endpoint-base-url} / {@code endpoint-addresses} redirect the call.
 */
class BackendAccessIntegrationTest {

    private static final String USER = "bridge";
    private static final String PASSWORD = "s3cret";

    private static SoapTestBackend backend;
    private final List<HttpServer> proxies = new ArrayList<>();
    private WsdlAuthenticator authenticator;

    @BeforeAll
    static void startBackend() throws Exception {
        backend = SoapTestBackend.start("Hello", HelloImpl.class, new HelloImpl());
    }

    @AfterAll
    static void stopBackend() {
        backend.close();
    }

    @AfterEach
    void stopProxies() {
        if (authenticator != null) {
            authenticator.destroy();
        }
        proxies.forEach(proxy -> proxy.stop(0));
    }

    @Test
    void credentialsReachServicesPageWsdlAndSoapCall() throws Exception {
        String proxy = protectedProxy();
        BridgeProperties properties = properties(proxy + "/services/");
        properties.getBackend().getBasicAuth().setUsername(USER);
        properties.getBackend().getBasicAuth().setPassword(PASSWORD);
        // The WSDL advertises the backend itself; the call has to go through the protected proxy.
        properties.getBackend().setEndpointBaseUrl(proxy);
        authenticator = new WsdlAuthenticator(properties, noWsdlUrls());

        List<DiscoveredSoapService> services = discovery(properties).discover(proxy + "/services/", List.of());

        assertEquals(1, services.size());
        assertEquals("Hello", services.get(0).name());
        assertEquals(proxy + "/services/Hello?wsdl", services.get(0).wsdlUrl());
        WsdlOperationModel hello = services.get(0).operations().stream()
                .filter(operation -> operation.name().equals("hello"))
                .findFirst()
                .orElseThrow();
        MappingDefinition.SoapTarget target = new MappingDefinition.SoapTarget();
        target.setWsdl(services.get(0).wsdlUrl());
        target.setOperation("hello");
        target.setEndpoint(hello.endpoint());
        target.setContract(hello.contract());
        JsonNode json = backend.invoker(properties)
                .invoke(target, new ObjectMapper().createObjectNode().put("name", "Ann"), Map.of());
        assertEquals("Hello, Ann", json.path("return").asText(), json.toString());
        assertEquals(1, requestsTo(proxy), "the call went through endpoint-base-url");
    }

    @Test
    void protectedWsdlWithoutCredentialsFailsDiscovery() throws Exception {
        String proxy = protectedProxy();
        BridgeProperties properties = properties(proxy + "/services/");
        authenticator = new WsdlAuthenticator(properties, noWsdlUrls());

        SoapDiscoveryException exception = assertThrows(SoapDiscoveryException.class,
                () -> discovery(properties).discover(proxy + "/services/", List.of()));

        assertTrue(exception.getMessage().contains("HTTP 401"), exception.getMessage());
    }

    @Test
    void configuredServiceAddressWinsOverWsdlAddress() throws Exception {
        String proxy = protectedProxy();
        BridgeProperties properties = SoapTestBackend.properties();
        properties.getBackend().getBasicAuth().setUsername(USER);
        properties.getBackend().getBasicAuth().setPassword(PASSWORD);
        MappingDefinition.SoapTarget target = backend.mapping("hello").getSoap();
        target.setEndpointAddress(proxy + "/services/Hello");
        try {
            JsonNode result = backend.invoker(properties)
                    .invoke(target, new ObjectMapper().createObjectNode().put("name", "Bob"), Map.of());
            assertEquals("Hello, Bob", result.path("return").asText(), result.toString());
            assertEquals(1, requestsTo(proxy), "the call went through the configured address");
        } finally {
            target.setEndpointAddress(null);
        }
    }

    @Test
    void credentialsAreOfferedOnlyToConfiguredOrigins() throws Exception {
        Authenticator previous = Authenticator.getDefault();
        BridgeProperties properties = properties("http://soap-backend:8080/services/");
        properties.getBackend().getWsdlBasicAuth().setUsername(USER);
        properties.getBackend().getWsdlBasicAuth().setPassword(PASSWORD);
        authenticator = new WsdlAuthenticator(properties, noWsdlUrls());

        PasswordAuthentication own = request("http://soap-backend:8080/services/Hello?wsdl");
        assertEquals(USER, own.getUserName());
        assertEquals(PASSWORD, new String(own.getPassword()));
        assertNull(request("http://soap-backend:9090/services/Hello?wsdl"));
        assertNull(request("https://www.w3.org/2001/xml.xsd"));

        authenticator.destroy();
        authenticator = null;
        assertSame(previous, Authenticator.getDefault());
    }

    @Test
    void rebaseKeepsPathAndQuery() {
        assertEquals("https://gw.example:8443/services/Hello?x=1",
                SoapClientFactory.rebase("http://localhost:8080/services/Hello?x=1", "https://gw.example:8443"));
        assertEquals("http://gw/backend/services/Hello",
                SoapClientFactory.rebase("http://localhost:8080/services/Hello", "http://gw/backend/"));
        assertEquals("http://localhost:8080/services/Hello",
                SoapClientFactory.rebase("http://localhost:8080/services/Hello", null));
    }

    private static PasswordAuthentication request(String url) throws Exception {
        URL target = new URL(url);
        return Authenticator.requestPasswordAuthentication(target.getHost(), null, target.getPort(),
                target.getProtocol(), "wsdl", "basic", target, Authenticator.RequestorType.SERVER);
    }

    private static BridgeProperties properties(String servicesUrl) {
        BridgeProperties properties = SoapTestBackend.properties();
        properties.getAuto().setServicesUrl(servicesUrl);
        return properties;
    }

    private static WsdlUrlCollector noWsdlUrls() {
        WsdlUrlCollector collector = mock(WsdlUrlCollector.class);
        when(collector.wsdlUrls()).thenReturn(List.of());
        return collector;
    }

    private static WsdlServiceDiscovery discovery(BridgeProperties properties) {
        return new WsdlServiceDiscovery(new WsdlParser(properties), properties);
    }

    private final Map<String, int[]> soapRequests = new java.util.concurrent.ConcurrentHashMap<>();

    private int requestsTo(String proxy) {
        return soapRequests.getOrDefault(proxy, new int[1])[0];
    }

    /**
     * Requires Basic auth for everything; lists the backend service as a CXF services page would and forwards
     * the rest (WSDL downloads, SOAP calls) to the backend.
     */
    private String protectedProxy() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        String origin = "http://localhost:" + server.getAddress().getPort();
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((USER + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        String backendOrigin = backend.address().substring(0, backend.address().indexOf("/services/"));
        server.createContext("/", exchange -> {
            try (exchange) {
                if (!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"backend\"");
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                String path = exchange.getRequestURI().toString();
                if (path.equals("/services/")) {
                    respond(exchange, 200, "text/html", ("<html><body><table><tr><td>"
                            + "<span class=\"porttypename\">Hello</span></td><td>"
                            + "<a href=\"" + origin + "/services/Hello?wsdl\">WSDL</a></td></tr></table></body></html>")
                            .getBytes(StandardCharsets.UTF_8));
                    return;
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    soapRequests.computeIfAbsent(origin, ignored -> new int[1])[0]++;
                }
                forward(exchange, backendOrigin + path);
            }
        });
        server.start();
        proxies.add(server);
        return origin;
    }

    private static void forward(HttpExchange exchange, String target) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setRequestMethod(exchange.getRequestMethod());
        for (String header : List.of("Content-Type", "SOAPAction")) {
            String value = exchange.getRequestHeaders().getFirst(header);
            if (value != null) {
                connection.setRequestProperty(header, value);
            }
        }
        if ("POST".equals(exchange.getRequestMethod())) {
            connection.setDoOutput(true);
            try (OutputStream body = connection.getOutputStream(); InputStream request = exchange.getRequestBody()) {
                request.transferTo(body);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        if (stream != null) {
            try (stream) {
                stream.transferTo(content);
            }
        }
        respond(exchange, status, connection.getContentType(), content.toByteArray());
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().add("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    @WebService(name = "Hello", serviceName = "HelloService", targetNamespace = "http://access.example.test")
    public static class HelloImpl {
        @WebMethod
        public String hello(@WebParam(name = "name") String name) {
            return "Hello, " + name;
        }
    }
}
