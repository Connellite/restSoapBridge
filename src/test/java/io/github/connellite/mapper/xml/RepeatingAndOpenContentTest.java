package io.github.connellite.mapper.xml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.SchemaRenderer;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.SchemaField;
import io.github.connellite.model.SoapOperationContract;
import io.github.connellite.model.WsdlOperationModel;
import io.github.connellite.service.auto.WsdlParser;
import io.github.connellite.service.validation.RequestSchemaValidator;
import org.apache.cxf.staxutils.StaxUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Elements of a repeating {@code xs:choice} or {@code xs:sequence} are arrays, and content an {@code xs:any} or
 * {@code xs:anyAttribute} admits is carried as it is: nothing the backend sends is dropped.
 */
class RepeatingAndOpenContentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void elementsOfRepeatingParticlesAreArraysWithoutTheParticleBounds() throws Exception {
        WsdlOperationModel feed = operation();

        assertEquals(List.of(
                new SchemaField("title", "string", true, false),
                new SchemaField("a[]", "string", false, true),
                new SchemaField("b[]", "int", false, true),
                new SchemaField("k[]", "string", true, true),
                new SchemaField("v[]", "string", true, true)
        ), feed.inputSchemaFields());
    }

    @Test
    void readsEveryOccurrenceAndUndeclaredContent() throws Exception {
        SoapOperationContract contract = operation().contract();
        Element element = StaxUtils.read(new StringReader("""
                <f:feed xmlns:f="urn:feed" xmlns:x="urn:ext" lang="en">
                  <f:title>t</f:title><f:title>again</f:title>
                  <f:a>1</f:a><f:b>2</f:b><f:a>3</f:a>
                  <f:k>k1</f:k><f:v>v1</f:v><f:k>k2</f:k><f:v>v2</f:v>
                  <x:extra><x:y>z</x:y></x:extra>
                </f:feed>
                """)).getDocumentElement();

        JsonNode json = new XmlToJson(contract.types()).object(element, contract.inputType());

        assertEquals(MAPPER.readTree("""
                {"title": ["t", "again"], "a": ["1", "3"], "b": [2], "k": ["k1", "k2"], "v": ["v1", "v2"],
                 "lang": "en", "extra": {"y": "z"}}
                """), json);
    }

    @Test
    void writesUndeclaredFieldsOfAnOpenTypeAfterTheDeclaredOnes() throws Exception {
        SoapOperationContract contract = operation().contract();
        JsonToXml writer = new JsonToXml(contract.types());

        Element payload = writer.object(QName.valueOf(contract.inputElement()), contract.inputType(), MAPPER.readTree("""
                {"extra": {"y": "z"}, "title": "t", "a": ["1", "3"], "k": ["k1"], "v": ["v1"]}
                """), "$", Set.of(), "feed");

        assertEquals(List.of(), writer.errors());
        String xml = StaxUtils.toString(payload);
        assertTrue(xml.contains("<ns1:title>t</ns1:title><ns1:a>1</ns1:a><ns1:a>3</ns1:a><ns1:k>k1</ns1:k>"
                + "<ns1:v>v1</ns1:v><ns1:extra><ns1:y>z</ns1:y></ns1:extra>"), xml);
    }

    @Test
    void validationLetsUndeclaredFieldsOfAnOpenTypePass() throws Exception {
        WsdlOperationModel feed = operation();
        MappingDefinition mapping = new MappingDefinition();
        mapping.setMethod("POST");
        mapping.setPath("/api/Feeds/feed");
        mapping.setRequestType(feed.inputType());
        mapping.setSchemaTypes(feed.types());
        RequestSchemaValidator validator = new RequestSchemaValidator(new BridgeProperties());

        assertDoesNotThrow(() -> validator.validate(mapping, MAPPER.readTree("""
                {"title": "t", "k": ["k1"], "v": ["v1"], "extra": {"y": "z"}}
                """)));
        assertEquals(Boolean.TRUE, SchemaRenderer.withLocalNames(feed.types()).object(feed.inputType())
                .getAdditionalProperties());
    }

    private WsdlOperationModel operation() throws Exception {
        Path wsdl = dir.resolve("feed.wsdl");
        Files.writeString(wsdl, WSDL, StandardCharsets.UTF_8);
        return new WsdlParser().parseOperations(wsdl.toUri().toString()).get(0);
    }

    private static final String WSDL = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:xs="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="urn:feed" targetNamespace="urn:feed">
              <wsdl:types>
                <xs:schema targetNamespace="urn:feed" elementFormDefault="qualified">
                  <xs:element name="feed">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="title" type="xs:string"/>
                        <xs:choice minOccurs="0" maxOccurs="unbounded">
                          <xs:element name="a" type="xs:string"/>
                          <xs:element name="b" type="xs:int"/>
                        </xs:choice>
                        <xs:sequence maxOccurs="unbounded">
                          <xs:element name="k" type="xs:string"/>
                          <xs:element name="v" type="xs:string"/>
                        </xs:sequence>
                        <xs:any namespace="##other" processContents="lax" minOccurs="0" maxOccurs="unbounded"/>
                      </xs:sequence>
                      <xs:anyAttribute processContents="lax"/>
                    </xs:complexType>
                  </xs:element>
                  <xs:element name="feedResponse"><xs:complexType><xs:sequence>
                    <xs:element name="return" type="xs:string"/>
                  </xs:sequence></xs:complexType></xs:element>
                </xs:schema>
              </wsdl:types>
              <wsdl:message name="feed"><wsdl:part name="parameters" element="tns:feed"/></wsdl:message>
              <wsdl:message name="feedResponse"><wsdl:part name="parameters" element="tns:feedResponse"/></wsdl:message>
              <wsdl:portType name="Feeds">
                <wsdl:operation name="feed">
                  <wsdl:input message="tns:feed"/>
                  <wsdl:output message="tns:feedResponse"/>
                </wsdl:operation>
              </wsdl:portType>
            </wsdl:definitions>
            """;
}
