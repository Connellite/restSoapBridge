package io.github.connellite.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.connellite.mapper.xml.JsonToXml;
import io.github.connellite.mapper.xml.XmlToJson;
import io.github.connellite.model.SchemaTrees;
import io.github.connellite.model.SoapOperationContract;
import io.github.connellite.model.WsdlOperationModel;
import io.github.connellite.service.auto.WsdlParser;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.apache.cxf.staxutils.StaxUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Schema details taken over from Membrane: default and fixed values, formats of anyURI and duration, the note on a
 * repeating choice, keys for elements of one local name from different namespaces, namespace prefixes.
 */
class SchemaDetailsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void publishesDefaultsFixedValuesAndFormats() throws Exception {
        ObjectSchema order = render(operation());

        Schema<?> priority = (Schema<?>) order.getProperties().get("priority");
        assertEquals(5, priority.getDefault());
        assertNull(priority.getEnum());
        Schema<?> express = (Schema<?>) order.getProperties().get("express");
        assertEquals(Boolean.TRUE, express.getDefault(), "XSD 1 is true");
        Schema<?> version = (Schema<?>) order.getProperties().get("version");
        assertEquals(new BigDecimal("1.0"), version.getDefault());
        assertEquals(List.of(new BigDecimal("1.0")), version.getEnum());
        assertEquals("uri", ((Schema<?>) order.getProperties().get("link")).getFormat());
        assertEquals("duration", ((Schema<?>) order.getProperties().get("ttl")).getFormat());
    }

    @Test
    void describesARepeatingChoiceInsteadOfConstrainingIt() throws Exception {
        ObjectSchema order = render(operation());

        assertEquals("Repeatable choice: each occurrence is one of: note, flag. Not enforced by this schema.",
                order.getDescription());
        assertNull(order.getOneOf());
    }

    @Test
    void elementsOfOneLocalNameFromTwoNamespacesKeepTheirOwnKeys() throws Exception {
        WsdlOperationModel operation = operation();
        SoapOperationContract contract = operation.contract();
        ObjectSchema order = render(operation);
        assertTrue(order.getProperties().containsKey("billing:item"), operation.inputType().properties().toString());
        assertTrue(order.getProperties().containsKey("shipping:item"), order.getProperties().keySet().toString());

        JsonToXml writer = new JsonToXml(contract.types());
        Element payload = writer.object(QName.valueOf(contract.inputElement()), contract.inputType(), MAPPER.readTree("""
                {"billing:item": "b", "shipping:item": "s"}
                """), "$", Set.of(), "order");
        String xml = StaxUtils.toString(payload);
        assertEquals(List.of(), writer.errors());
        assertTrue(xml.contains("<ns2:item>b</ns2:item><ns3:item>s</ns3:item>"), xml);

        assertEquals("{\"billing:item\":\"b\",\"shipping:item\":\"s\"}",
                new XmlToJson(contract.types()).object(payload, contract.inputType()).toString());
    }

    @Test
    void nsPrefixTakesTheLastMeaningfulSegment() {
        assertEquals("billing", SchemaTrees.nsPrefix("http://example.com/billing"));
        assertEquals("billing", SchemaTrees.nsPrefix("urn:example:billing:v1"));
    }

    private static ObjectSchema render(WsdlOperationModel operation) {
        return SchemaRenderer.withLocalNames(operation.types()).object(operation.inputType());
    }

    private WsdlOperationModel operation() throws Exception {
        Path wsdl = dir.resolve("details.wsdl");
        Files.writeString(wsdl, WSDL, StandardCharsets.UTF_8);
        return new WsdlParser().parseOperations(wsdl.toUri().toString()).get(0);
    }

    private static final String WSDL = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:xs="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="urn:orders" xmlns:b="http://example.com/billing"
                              xmlns:s="urn:example:shipping:v2" targetNamespace="urn:orders">
              <wsdl:types>
                <xs:schema targetNamespace="http://example.com/billing" elementFormDefault="qualified">
                  <xs:element name="item" type="xs:string"/>
                </xs:schema>
                <xs:schema targetNamespace="urn:example:shipping:v2" elementFormDefault="qualified">
                  <xs:element name="item" type="xs:string"/>
                </xs:schema>
                <xs:schema targetNamespace="urn:orders" elementFormDefault="qualified">
                  <xs:import namespace="http://example.com/billing"/>
                  <xs:import namespace="urn:example:shipping:v2"/>
                  <xs:element name="order">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="priority" type="xs:int" default="5" minOccurs="0"/>
                        <xs:element name="express" type="xs:boolean" default="1" minOccurs="0"/>
                        <xs:element name="link" type="xs:anyURI" minOccurs="0"/>
                        <xs:element name="ttl" type="xs:duration" minOccurs="0"/>
                        <xs:element ref="b:item" minOccurs="0"/>
                        <xs:element ref="s:item" minOccurs="0"/>
                        <xs:choice minOccurs="0" maxOccurs="unbounded">
                          <xs:element name="note" type="xs:string"/>
                          <xs:element name="flag" type="xs:boolean"/>
                        </xs:choice>
                      </xs:sequence>
                      <xs:attribute name="version" type="xs:decimal" fixed="1.0"/>
                    </xs:complexType>
                  </xs:element>
                  <xs:element name="orderResponse"><xs:complexType><xs:sequence>
                    <xs:element name="return" type="xs:string"/>
                  </xs:sequence></xs:complexType></xs:element>
                </xs:schema>
              </wsdl:types>
              <wsdl:message name="order"><wsdl:part name="parameters" element="tns:order"/></wsdl:message>
              <wsdl:message name="orderResponse"><wsdl:part name="parameters" element="tns:orderResponse"/></wsdl:message>
              <wsdl:portType name="Orders">
                <wsdl:operation name="order">
                  <wsdl:input message="tns:order"/>
                  <wsdl:output message="tns:orderResponse"/>
                </wsdl:operation>
              </wsdl:portType>
            </wsdl:definitions>
            """;
}
