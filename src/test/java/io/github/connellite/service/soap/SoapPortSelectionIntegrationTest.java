package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.WsdlEndpoint;
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

/**
 * A WSDL service with ports of different port types: the bridge publishes the operations of one port and calls
 * that very port, by default the first SOAP 1.1 one, otherwise the one {@code bridge.backend.ports} names.
 */
class SoapPortSelectionIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NS = "urn:ports";

    @Test
    void exposesOnlyTheOperationsOfTheDefaultPort() throws Exception {
        try (SoapTestBackend backend = start(SoapTestBackend.properties())) {
            assertEquals(List.of("purge"), operations(backend));
            assertEquals(new WsdlEndpoint("{urn:ports}PortsService", "AdminPort"),
                    backend.mapping("purge").getSoap().getEndpoint());
        }
    }

    @Test
    void exposesAndCallsTheConfiguredPort() throws Exception {
        BridgeProperties properties = SoapTestBackend.properties();
        properties.getBackend().setPorts(Map.of("ports", "pingport"));

        try (SoapTestBackend backend = start(properties)) {
            assertEquals(List.of("ping"), operations(backend));

            ObjectNode response = backend.execute("ping", MAPPER.readTree("{\"text\": \"hi\"}"));

            assertEquals("pong:hi", response.path("return").asText(), response.toString());
        }
    }

    private static List<String> operations(SoapTestBackend backend) {
        return backend.mappings().stream().map(MappingDefinition::getSoap).map(MappingDefinition.SoapTarget::getOperation)
                .toList();
    }

    private static SoapTestBackend start(BridgeProperties properties) throws Exception {
        Path wsdl = Files.createTempFile("ports", ".wsdl");
        Files.writeString(wsdl, WSDL, StandardCharsets.UTF_8);
        return SoapTestBackend.start("Ports", factory -> {
            factory.setServiceBean(new PingProvider());
            factory.setWsdlLocation(wsdl.toUri().toString());
            factory.setServiceName(new QName(NS, "PortsService"));
            factory.setEndpointName(new QName(NS, "PingPort"));
        }, properties);
    }

    @WebServiceProvider(serviceName = "PortsService", portName = "PingPort", targetNamespace = NS)
    @ServiceMode(Service.Mode.PAYLOAD)
    public static class PingProvider implements Provider<DOMSource> {

        @Override
        public DOMSource invoke(DOMSource request) {
            Node node = request.getNode();
            Element body = node instanceof Document document ? document.getDocumentElement() : (Element) node;
            Element text = DOMUtils.getFirstChildWithName(body, NS, "text");
            Document response = DOMUtils.createDocument();
            Element pingResponse = response.createElementNS(NS, "pingResponse");
            Element result = response.createElementNS(NS, "return");
            result.setTextContent("pong:" + (text != null ? text.getTextContent() : "-"));
            pingResponse.appendChild(result);
            response.appendChild(pingResponse);
            return new DOMSource(response);
        }
    }

    private static final String WSDL = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:xs="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="urn:ports" targetNamespace="urn:ports" name="PortsService">
              <wsdl:types>
                <xs:schema targetNamespace="urn:ports" elementFormDefault="qualified">
                  <xs:element name="ping">
                    <xs:complexType><xs:sequence><xs:element name="text" type="xs:string"/></xs:sequence></xs:complexType>
                  </xs:element>
                  <xs:element name="pingResponse">
                    <xs:complexType><xs:sequence><xs:element name="return" type="xs:string"/></xs:sequence></xs:complexType>
                  </xs:element>
                  <xs:element name="purge">
                    <xs:complexType><xs:sequence><xs:element name="all" type="xs:boolean"/></xs:sequence></xs:complexType>
                  </xs:element>
                  <xs:element name="purgeResponse">
                    <xs:complexType><xs:sequence/></xs:complexType>
                  </xs:element>
                </xs:schema>
              </wsdl:types>
              <wsdl:message name="ping"><wsdl:part name="parameters" element="tns:ping"/></wsdl:message>
              <wsdl:message name="pingResponse"><wsdl:part name="parameters" element="tns:pingResponse"/></wsdl:message>
              <wsdl:message name="purge"><wsdl:part name="parameters" element="tns:purge"/></wsdl:message>
              <wsdl:message name="purgeResponse"><wsdl:part name="parameters" element="tns:purgeResponse"/></wsdl:message>
              <wsdl:portType name="Ping">
                <wsdl:operation name="ping">
                  <wsdl:input message="tns:ping"/>
                  <wsdl:output message="tns:pingResponse"/>
                </wsdl:operation>
              </wsdl:portType>
              <wsdl:portType name="Admin">
                <wsdl:operation name="purge">
                  <wsdl:input message="tns:purge"/>
                  <wsdl:output message="tns:purgeResponse"/>
                </wsdl:operation>
              </wsdl:portType>
              <wsdl:binding name="PingBinding" type="tns:Ping">
                <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="ping">
                  <soap:operation soapAction=""/>
                  <wsdl:input><soap:body use="literal"/></wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>
              <wsdl:binding name="AdminBinding" type="tns:Admin">
                <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                <wsdl:operation name="purge">
                  <soap:operation soapAction=""/>
                  <wsdl:input><soap:body use="literal"/></wsdl:input>
                  <wsdl:output><soap:body use="literal"/></wsdl:output>
                </wsdl:operation>
              </wsdl:binding>
              <wsdl:service name="PortsService">
                <wsdl:port name="PingPort" binding="tns:PingBinding">
                  <soap:address location="http://localhost:1/unused"/>
                </wsdl:port>
                <wsdl:port name="AdminPort" binding="tns:AdminBinding">
                  <soap:address location="http://localhost:1/admin"/>
                </wsdl:port>
              </wsdl:service>
            </wsdl:definitions>
            """;
}
