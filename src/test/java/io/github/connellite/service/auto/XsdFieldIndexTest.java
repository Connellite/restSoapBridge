package io.github.connellite.service.auto;

import com.ibm.wsdl.extensions.schema.SchemaImpl;
import io.github.connellite.model.SchemaField;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.wsdl.Definition;
import javax.wsdl.Types;
import javax.wsdl.factory.WSDLFactory;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XsdFieldIndexTest {

    @Test
    void readElementFields_indexesNestedCollectionsChoiceAllAndRefs() throws Exception {
        Definition definition = definitionWithSchema("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           targetNamespace="urn:test"
                           xmlns:tns="urn:test"
                           elementFormDefault="qualified">
                  <xs:element name="externalId" type="xs:string"/>
                  <xs:element name="createOrder" type="tns:CreateOrder"/>
                  <xs:complexType name="CreateOrder">
                    <xs:sequence>
                      <xs:element name="customer" type="tns:Customer"/>
                      <xs:element name="products" type="tns:Product" maxOccurs="unbounded"/>
                      <xs:choice>
                        <xs:element name="email" type="xs:string"/>
                        <xs:element name="phoneContact" type="xs:string"/>
                      </xs:choice>
                      <xs:element ref="tns:externalId"/>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="Customer">
                    <xs:all>
                      <xs:element name="name" type="xs:string"/>
                      <xs:element name="phone" type="xs:string"/>
                    </xs:all>
                  </xs:complexType>
                  <xs:complexType name="Product">
                    <xs:sequence>
                      <xs:element name="sku" type="xs:string"/>
                      <xs:element name="qty" type="xs:int"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """);

        Map<String, List<String>> fields = XsdFieldIndex.readElementFields(definition);

        assertEquals(List.of(
                "customer.name",
                "customer.phone",
                "products[]",
                "products[].sku",
                "products[].qty",
                "email",
                "phoneContact",
                "externalId"
        ), fields.get("createOrder"));

        Map<String, List<SchemaField>> schemaFields = XsdFieldIndex.readElementSchemaFields(definition);
        assertEquals(List.of(
                new SchemaField("customer.name", "string", true, false),
                new SchemaField("customer.phone", "string", true, false),
                new SchemaField("products[]", "object", true, true),
                new SchemaField("products[].sku", "string", true, false),
                new SchemaField("products[].qty", "int", true, false),
                new SchemaField("email", "string", false, false),
                new SchemaField("phoneContact", "string", false, false),
                new SchemaField("externalId", "string", true, false)
        ), schemaFields.get("createOrder"));
    }

    @Test
    void readElementFields_collapsesJaxbMapEntryPatternToSingleObjectField() throws Exception {
        Definition definition = definitionWithSchema("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           targetNamespace="urn:test"
                           xmlns:tns="urn:test"
                           elementFormDefault="qualified">
                  <xs:element name="getItemResponse" type="tns:GetItemResponse"/>
                  <xs:complexType name="GetItemResponse">
                    <xs:sequence>
                      <xs:element name="return" type="tns:ItemDto" minOccurs="0"/>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="ItemDto">
                    <xs:sequence>
                      <xs:element name="uuid" type="xs:string"/>
                      <xs:element name="name" type="xs:string" minOccurs="0"/>
                      <xs:element name="labels" minOccurs="0">
                        <xs:complexType>
                          <xs:sequence>
                            <xs:element name="entry" maxOccurs="unbounded">
                              <xs:complexType>
                                <xs:sequence>
                                  <xs:element name="key" type="xs:string" minOccurs="0"/>
                                  <xs:element name="value" type="xs:string" minOccurs="0"/>
                                </xs:sequence>
                              </xs:complexType>
                            </xs:element>
                          </xs:sequence>
                        </xs:complexType>
                      </xs:element>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """);

        Map<String, List<String>> fields = XsdFieldIndex.readElementFields(definition);

        assertEquals(List.of(
                "return.uuid",
                "return.name",
                "return.labels"
        ), fields.get("getItemResponse"));
    }

    private static Definition definitionWithSchema(String xsd) throws Exception {
        WSDLFactory factory = WSDLFactory.newInstance();
        Definition definition = factory.newDefinition();
        Types types = definition.createTypes();
        SchemaImpl schema = new SchemaImpl();
        schema.setElement(parseDocument(xsd).getDocumentElement());
        schema.setDocumentBaseURI("memory:test.xsd");
        types.addExtensibilityElement(schema);
        definition.setTypes(types);
        return definition;
    }

    private static Document parseDocument(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
