package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.OpenApiExampleFactory;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.mapper.path.SoapArgument;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
#if SPRING_BOOT_3
import jakarta.annotation.Resource;
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebService;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.ws.Provider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.ServiceMode;
import jakarta.xml.ws.WebServiceContext;
import jakarta.xml.ws.WebServiceProvider;
#else
import javax.annotation.Resource;
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebService;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.ws.Provider;
import javax.xml.ws.Service;
import javax.xml.ws.ServiceMode;
import javax.xml.ws.WebServiceContext;
import javax.xml.ws.WebServiceProvider;
#endif
import org.apache.cxf.headers.Header;
import org.apache.cxf.helpers.DOMUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SOAP headers declared in the WSDL binding ({@code soap:header}) are sent from the {@code soapHeaders} JSON object:
 * explicit headers (a part of the operation message, JAX-WS {@code @WebParam(header = true)}) and implicit ones
 * (a part of another message, common in hand-written WSDLs).
 */
class SoapHeadersIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NS = "urn:implicit";

    private static SoapTestBackend explicit;
    private static SoapTestBackend implicit;

    @BeforeAll
    static void start() throws Exception {
        explicit = SoapTestBackend.start("Secured", SecuredImpl.class, new SecuredImpl());
        Path wsdl = Files.createTempFile("implicit-headers", ".wsdl");
        Files.writeString(wsdl, IMPLICIT_WSDL, StandardCharsets.UTF_8);
        implicit = SoapTestBackend.start("Greeter", factory -> {
            factory.setServiceBean(new GreeterProvider());
            factory.setWsdlLocation(wsdl.toUri().toString());
            factory.setServiceName(new QName(NS, "GreeterService"));
            factory.setEndpointName(new QName(NS, "GreeterPort"));
        }, SoapTestBackend.properties());
    }

    @AfterAll
    static void stop() {
        explicit.close();
        implicit.close();
    }

    // --- explicit header -------------------------------------------------------------------------------------

    @Test
    void explicitHeaderPartIsNotMistakenForTheBody() {
        assertEquals(Map.of("$.text", "$.echo.text"), explicit.mapping("echo").getRequest());
        assertEquals(Map.of("$.soapHeaders.auth.token", "$.auth.token"), explicit.mapping("echo").getHeaderRequest());
    }

    @Test
    void sendsExplicitHeader() throws Exception {
        ObjectNode response = explicit.execute("echo", MAPPER.readTree("""
                {"text": "hi", "soapHeaders": {"auth": {"token": "tok"}}}
                """));

        assertEquals("hi:tok", response.path("return").asText(), response.toString());
    }

    @Test
    void headerMayBeOmitted() throws Exception {
        ObjectNode response = explicit.execute("echo", MAPPER.readTree("""
                {"text": "hi"}
                """));

        assertEquals("hi:none", response.path("return").asText(), response.toString());
    }

    @Test
    void headersArePublishedInRequestSchema() {
        ObjectSchema schema = OpenApiExampleFactory.schemaWithXsdExamples(
                explicit.mapping("echo").getRequestSchemaFields(), List.of());

        Schema<?> headers = schema.getProperties().get("soapHeaders");
        Schema<?> auth = (Schema<?>) headers.getProperties().get("auth");
        assertEquals("string", ((Schema<?>) auth.getProperties().get("token")).getType());
        assertTrue(schema.getRequired() == null || !schema.getRequired().contains("soapHeaders"), schema.getRequired() + "");
    }

    @Test
    void unknownHeaderIsRejected() {
        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> explicit.invoker(SoapTestBackend.properties()).invoke(explicit.mapping("echo").getSoap(),
                        List.of(new SoapArgument("text", "hi")),
                        List.of(new SoapArgument("session", Map.of("id", "1"))),
                        Map.of()));

        assertTrue(exception.getErrors().get(0).startsWith("Unknown SOAP header(s) [session]"), exception.getErrors().toString());
    }

    // --- implicit headers ------------------------------------------------------------------------------------

    @Test
    void sendsImplicitComplexAndSimpleHeaders() throws Exception {
        assertEquals(Map.of("$.soapHeaders.session.token", "$.session.token", "$.soapHeaders.locale", "$.locale"),
                implicit.mapping("greet").getHeaderRequest());

        ObjectNode response = implicit.execute("greet", MAPPER.readTree("""
                {"name": "Ann", "soapHeaders": {"session": {"token": "s-1"}, "locale": "de"}}
                """));

        assertEquals("Ann:s-1:de", response.path("return").asText(), response.toString());
    }

    @Test
    void implicitHeadersMayBeOmitted() throws Exception {
        ObjectNode response = implicit.execute("greet", MAPPER.readTree("""
                {"name": "Ann"}
                """));

        assertEquals("Ann:-:-", response.path("return").asText(), response.toString());
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Auth {
        public String token;
    }

    @WebService(name = "Secured", serviceName = "SecuredService", targetNamespace = "http://hdr.example.test")
    public static class SecuredImpl {
        @WebMethod
        public String echo(@WebParam(name = "text") String text,
                           @WebParam(name = "auth", header = true) Auth auth) {
            return text + ":" + (auth != null ? auth.token : "none");
        }
    }

    /** Answers {@code name:token:locale} from the body and the SOAP headers it received ({@code -} when absent). */
    @WebServiceProvider(serviceName = "GreeterService", portName = "GreeterPort", targetNamespace = NS)
    @ServiceMode(Service.Mode.PAYLOAD)
    public static class GreeterProvider implements Provider<DOMSource> {

        @Resource
        private WebServiceContext context;

        @Override
        public DOMSource invoke(DOMSource request) {
            Node node = request.getNode();
            Element body = node instanceof Document document ? document.getDocumentElement() : (Element) node;
            String token = "-";
            String locale = "-";
            @SuppressWarnings("unchecked")
            List<Header> headers = (List<Header>) context.getMessageContext().get(Header.HEADER_LIST);
            for (Header header : headers != null ? headers : List.<Header>of()) {
                Element element = (Element) header.getObject();
                if ("session".equals(header.getName().getLocalPart())) {
                    token = text(element, "token");
                } else if ("locale".equals(header.getName().getLocalPart())) {
                    locale = element.getTextContent();
                }
            }
            Document response = DOMUtils.createDocument();
            Element greetResponse = response.createElementNS(NS, "greetResponse");
            Element result = response.createElementNS(NS, "return");
            result.setTextContent(text(body, "name") + ":" + token + ":" + locale);
            greetResponse.appendChild(result);
            response.appendChild(greetResponse);
            return new DOMSource(response);
        }

        private static String text(Element parent, String localName) {
            Element child = DOMUtils.getFirstChildWithName(parent, NS, localName);
            return child != null ? child.getTextContent() : "-";
        }
    }

    private static final String IMPLICIT_WSDL = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:xs="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="urn:implicit" targetNamespace="urn:implicit" name="GreeterService">
              <wsdl:types>
                <xs:schema targetNamespace="urn:implicit" elementFormDefault="qualified">
                  <xs:element name="greet">
                    <xs:complexType><xs:sequence><xs:element name="name" type="xs:string"/></xs:sequence></xs:complexType>
                  </xs:element>
                  <xs:element name="greetResponse">
                    <xs:complexType><xs:sequence><xs:element name="return" type="xs:string"/></xs:sequence></xs:complexType>
                  </xs:element>
                  <xs:element name="session">
                    <xs:complexType><xs:sequence><xs:element name="token" type="xs:string"/></xs:sequence></xs:complexType>
                  </xs:element>
                  <xs:element name="locale" type="xs:string"/>
                </xs:schema>
              </wsdl:types>
              <wsdl:message name="greet"><wsdl:part name="parameters" element="tns:greet"/></wsdl:message>
              <wsdl:message name="greetResponse"><wsdl:part name="parameters" element="tns:greetResponse"/></wsdl:message>
              <wsdl:message name="Headers">
                <wsdl:part name="session" element="tns:session"/>
                <wsdl:part name="locale" element="tns:locale"/>
              </wsdl:message>
              <wsdl:portType name="Greeter">
                <wsdl:operation name="greet">
                  <wsdl:input message="tns:greet"/>
                  <wsdl:output message="tns:greetResponse"/>
                </wsdl:operation>
              </wsdl:portType>
              <wsdl:binding name="GreeterBinding" type="tns:Greeter">
                <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="greet">
                  <soap:operation soapAction=""/>
                  <wsdl:input>
                    <soap:header message="tns:Headers" part="session" use="literal"/>
                    <soap:header message="tns:Headers" part="locale" use="literal"/>
                    <soap:body use="literal"/>
                  </wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>
              <wsdl:service name="GreeterService">
                <wsdl:port name="GreeterPort" binding="tns:GreeterBinding">
                  <soap:address location="http://localhost:1/unused"/>
                </wsdl:port>
              </wsdl:service>
            </wsdl:definitions>
            """;
}
