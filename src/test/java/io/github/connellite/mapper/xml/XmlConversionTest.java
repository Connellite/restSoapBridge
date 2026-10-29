package io.github.connellite.mapper.xml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.connellite.model.SoapOperationContract;
import io.github.connellite.model.WsdlOperationModel;
import io.github.connellite.service.auto.WsdlParser;
import org.apache.cxf.staxutils.StaxUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON ↔ XML by the XSD of a WSDL, without a SOAP call.
 */
class XmlConversionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void writesElementsInSchemaOrderWithTheirFormAttributesTextAndNil() throws Exception {
        SoapOperationContract contract = contract();

        Element payload = write(contract, """
                {"note": null, "tags": ["a", "b"], "price": {"currency": "EUR", "value": 1.50},
                 "flag": "TRUE", "raw": "AQI=", "id": "7", "local": "x",
                 "attrs": {"k": "v"}, "pairs": {"p": "q"}, "customer": {"name": "Ann"}}
                """);
        String xml = StaxUtils.toString(payload);

        assertTrue(xml.startsWith("<ns1:order xmlns:ns1=\"urn:shop\"") || xml.contains("<ns1:order "), xml);
        assertTrue(xml.contains("<ns1:id>7</ns1:id>"), xml);
        assertTrue(xml.contains("<local>x</local>"), "unqualified local element: " + xml);
        assertTrue(xml.contains("<ns1:note xsi:nil=\"true\""), xml);
        assertTrue(xml.contains("<ns1:tags>a b</ns1:tags>"), xml);
        assertTrue(xml.contains("currency=\"EUR\">1.5</ns1:price>"), xml);
        assertTrue(xml.contains("<ns1:flag>true</ns1:flag>"), xml);
        assertTrue(xml.contains("<ns1:raw>0102</ns1:raw>"), "hexBinary from base64: " + xml);
        assertTrue(xml.contains("<ns1:attrs><ns1:entry><ns1:key>k</ns1:key><ns1:value>v</ns1:value></ns1:entry></ns1:attrs>"), xml);
        assertTrue(xml.contains("<ns1:pairs><ns1:key>p</ns1:key><ns1:value>q</ns1:value></ns1:pairs>"), xml);
        assertTrue(xml.indexOf("<ns1:id>") < xml.indexOf("<ns1:customer>"), "schema order: " + xml);
    }

    @Test
    void readsTypedJsonBack() throws Exception {
        SoapOperationContract contract = contract();
        Element payload = write(contract, """
                {"id": 7, "local": "x", "note": null, "tags": ["a", "b"], "price": {"currency": "EUR", "value": 1.5},
                 "flag": true, "raw": "AQI=", "attrs": {"k": "v"}, "pairs": [{"key": "p", "value": "q"}],
                 "customer": {"name": "Ann"}}
                """);

        JsonNode json = new XmlToJson(contract.types()).object(payload, contract.inputType());

        assertEquals(MAPPER.readTree("""
                {"id": 7, "local": "x", "note": null, "tags": ["a", "b"], "price": {"value": 1.5, "currency": "EUR"},
                 "flag": true, "raw": "AQI=", "attrs": {"k": "v"}, "pairs": [{"key": "p", "value": "q"}],
                 "customer": {"name": "Ann"}}
                """).toString(), json.toString(), "decimal as a JSON number, hexBinary as base64");
    }

    @Test
    void collectsTypeErrorsWithTheirPaths() throws Exception {
        SoapOperationContract contract = contract();
        JsonToXml writer = new JsonToXml(contract.types());

        writer.object(QName.valueOf(contract.inputElement()), contract.inputType(), MAPPER.readTree("""
                {"id": "seven", "flag": "yes", "customer": "Ann", "attrs": "k=v", "extra": 1}
                """), "$", Set.of(), "order");

        assertEquals(List.of(
                "Unknown parameter(s) [extra] for order; expected [id, local, note, tags, price, flag, raw, attrs, pairs, customer]",
                "$.id: string cannot be converted to long",
                "$.flag: string cannot be converted to boolean",
                "$.attrs: string cannot be converted to Map",
                "$.customer: string found, object expected"
        ), writer.errors());
    }

    private static Element write(SoapOperationContract contract, String json) throws Exception {
        JsonToXml writer = new JsonToXml(contract.types());
        Element payload = writer.object(QName.valueOf(contract.inputElement()), contract.inputType(),
                MAPPER.readTree(json), "$", Set.of(), "order");
        assertEquals(List.of(), writer.errors());
        return payload;
    }

    private SoapOperationContract contract() throws Exception {
        Path wsdl = dir.resolve("shop.wsdl");
        Files.writeString(wsdl, WSDL, StandardCharsets.UTF_8);
        WsdlOperationModel order = new WsdlParser().parseOperations(wsdl.toUri().toString()).get(0);
        return order.contract();
    }

    private static final String WSDL = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                              xmlns:xs="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="urn:shop" targetNamespace="urn:shop">
              <wsdl:types>
                <xs:schema targetNamespace="urn:shop" elementFormDefault="qualified">
                  <xs:complexType name="Customer">
                    <xs:sequence><xs:element name="name" type="xs:string"/></xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="Price">
                    <xs:simpleContent>
                      <xs:extension base="xs:decimal"><xs:attribute name="currency" type="xs:string"/></xs:extension>
                    </xs:simpleContent>
                  </xs:complexType>
                  <xs:simpleType name="Tags"><xs:list itemType="xs:string"/></xs:simpleType>
                  <xs:complexType name="Pair">
                    <xs:sequence>
                      <xs:element name="key" type="xs:string"/>
                      <xs:element name="value" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:element name="order">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="id" type="xs:long"/>
                        <xs:element name="local" type="xs:string" form="unqualified" minOccurs="0"/>
                        <xs:element name="note" type="xs:string" nillable="true" minOccurs="0"/>
                        <xs:element name="tags" type="tns:Tags" minOccurs="0"/>
                        <xs:element name="price" type="tns:Price" minOccurs="0"/>
                        <xs:element name="flag" type="xs:boolean" minOccurs="0"/>
                        <xs:element name="raw" type="xs:hexBinary" minOccurs="0"/>
                        <xs:element name="attrs" minOccurs="0">
                          <xs:complexType><xs:sequence>
                            <xs:element name="entry" minOccurs="0" maxOccurs="unbounded">
                              <xs:complexType><xs:sequence>
                                <xs:element name="key" type="xs:string"/>
                                <xs:element name="value" type="xs:string"/>
                              </xs:sequence></xs:complexType>
                            </xs:element>
                          </xs:sequence></xs:complexType>
                        </xs:element>
                        <xs:element name="pairs" type="tns:Pair" minOccurs="0" maxOccurs="unbounded"/>
                        <xs:element name="customer" type="tns:Customer" minOccurs="0"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                  <xs:element name="orderResponse"><xs:complexType><xs:sequence>
                    <xs:element name="return" type="xs:string"/>
                  </xs:sequence></xs:complexType></xs:element>
                </xs:schema>
              </wsdl:types>
              <wsdl:message name="order"><wsdl:part name="parameters" element="tns:order"/></wsdl:message>
              <wsdl:message name="orderResponse"><wsdl:part name="parameters" element="tns:orderResponse"/></wsdl:message>
              <wsdl:portType name="Shop">
                <wsdl:operation name="order">
                  <wsdl:input message="tns:order"/>
                  <wsdl:output message="tns:orderResponse"/>
                </wsdl:operation>
              </wsdl:portType>
            </wsdl:definitions>
            """;
}
