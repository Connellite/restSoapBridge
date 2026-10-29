package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.exception.UpstreamException;
#if SPRING_BOOT_3
import jakarta.xml.ws.Provider;
import jakarta.xml.ws.Service;
import jakarta.xml.ws.ServiceMode;
import jakarta.xml.ws.WebServiceProvider;
#else
import javax.xml.ws.Provider;
import javax.xml.ws.Service;
import javax.xml.ws.ServiceMode;
import javax.xml.ws.WebServiceProvider;
#endif
import org.apache.cxf.helpers.DOMUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code bridge.validation.response}: a response that breaks the WSDL schema (here a {@code maxLength}) passes
 * unchecked by default, is logged in {@code log} mode and is a 502 in {@code strict} mode.
 */
class ResponseValidationIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NS = "urn:codes";

    private static SoapTestBackend backend;

    @BeforeAll
    static void start() throws Exception {
        Path wsdl = Files.createTempFile("codes", ".wsdl");
        Files.writeString(wsdl, WSDL, StandardCharsets.UTF_8);
        backend = SoapTestBackend.start("Codes", factory -> {
            factory.setServiceBean(new CodesProvider());
            factory.setWsdlLocation(wsdl.toUri().toString());
            factory.setServiceName(new QName(NS, "CodesService"));
            factory.setEndpointName(new QName(NS, "CodesPort"));
        }, SoapTestBackend.properties());
    }

    @AfterAll
    static void stop() {
        backend.close();
    }

    @Test
    void invalidResponseIsUsedWhenValidationIsOff() throws Exception {
        ObjectNode response = backend.execute(properties(BridgeProperties.ResponseValidation.OFF),
                backend.mapping("code"), MAPPER.readTree("{\"id\": 1}"));

        assertEquals("TOO-LONG", response.path("return").asText(), response.toString());
    }

    @Test
    void invalidResponseIsUsedAndLoggedInLogMode() throws Exception {
        ObjectNode response = backend.execute(properties(BridgeProperties.ResponseValidation.LOG),
                backend.mapping("code"), MAPPER.readTree("{\"id\": 1}"));

        assertEquals("TOO-LONG", response.path("return").asText(), response.toString());
    }

    @Test
    void invalidResponseIsBadGatewayInStrictMode() {
        UpstreamException exception = assertThrows(UpstreamException.class,
                () -> backend.execute(properties(BridgeProperties.ResponseValidation.STRICT),
                        backend.mapping("code"), MAPPER.readTree("{\"id\": 1}")));

        assertEquals(502, exception.getHttpStatus());
        assertTrue(exception.getMessage().contains("does not match the WSDL schema"), exception.getMessage());
    }

    @Test
    void validResponsePassesInStrictMode() throws Exception {
        ObjectNode response = backend.execute(properties(BridgeProperties.ResponseValidation.STRICT),
                backend.mapping("code"), MAPPER.readTree("{\"id\": 2}"));

        assertEquals("OK", response.path("return").asText(), response.toString());
    }

    private static BridgeProperties properties(BridgeProperties.ResponseValidation mode) {
        BridgeProperties properties = SoapTestBackend.properties();
        properties.getValidation().setResponse(mode);
        return properties;
    }

    /** Answers id 1 with a code longer than the schema allows, any other id with {@code OK}. */
    @WebServiceProvider(serviceName = "CodesService", portName = "CodesPort", targetNamespace = NS)
    @ServiceMode(Service.Mode.PAYLOAD)
    public static class CodesProvider implements Provider<DOMSource> {

        @Override
        public DOMSource invoke(DOMSource request) {
            Element body = request.getNode() instanceof Document document
                    ? document.getDocumentElement()
                    : (Element) request.getNode();
            Element id = DOMUtils.getFirstChildWithName(body, NS, "id");
            Document response = DOMUtils.createDocument();
            Element codeResponse = response.createElementNS(NS, "codeResponse");
            Element result = response.createElementNS(NS, "return");
            result.setTextContent(id != null && "1".equals(id.getTextContent()) ? "TOO-LONG" : "OK");
            codeResponse.appendChild(result);
            response.appendChild(codeResponse);
            return new DOMSource(response);
        }
    }

    private static final String WSDL = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:xs="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="urn:codes" targetNamespace="urn:codes" name="CodesService">
              <wsdl:types>
                <xs:schema targetNamespace="urn:codes" elementFormDefault="qualified">
                  <xs:element name="code">
                    <xs:complexType><xs:sequence><xs:element name="id" type="xs:int"/></xs:sequence></xs:complexType>
                  </xs:element>
                  <xs:element name="codeResponse">
                    <xs:complexType><xs:sequence>
                      <xs:element name="return">
                        <xs:simpleType><xs:restriction base="xs:string"><xs:maxLength value="3"/></xs:restriction></xs:simpleType>
                      </xs:element>
                    </xs:sequence></xs:complexType>
                  </xs:element>
                </xs:schema>
              </wsdl:types>
              <wsdl:message name="code"><wsdl:part name="parameters" element="tns:code"/></wsdl:message>
              <wsdl:message name="codeResponse"><wsdl:part name="parameters" element="tns:codeResponse"/></wsdl:message>
              <wsdl:portType name="Codes">
                <wsdl:operation name="code">
                  <wsdl:input message="tns:code"/>
                  <wsdl:output message="tns:codeResponse"/>
                </wsdl:operation>
              </wsdl:portType>
              <wsdl:binding name="CodesBinding" type="tns:Codes">
                <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="code">
                  <soap:operation soapAction=""/>
                  <wsdl:input><soap:body use="literal"/></wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>
              <wsdl:service name="CodesService">
                <wsdl:port name="CodesPort" binding="tns:CodesBinding">
                  <soap:address location="http://localhost:1/unused"/>
                </wsdl:port>
              </wsdl:service>
            </wsdl:definitions>
            """;
}
