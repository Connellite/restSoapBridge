package io.github.connellite.service.auto;

import com.ibm.wsdl.extensions.schema.SchemaImpl;
import io.github.connellite.model.SchemaFacets;
import io.github.connellite.model.SchemaField;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.wsdl.Definition;
import javax.wsdl.Types;
import javax.wsdl.factory.WSDLFactory;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
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

        Map<QName, List<String>> fields = XsdFieldIndex.readElementFields(definition);

        assertEquals(List.of(
                "customer.name",
                "customer.phone",
                "products[]",
                "products[].sku",
                "products[].qty",
                "email",
                "phoneContact",
                "externalId"
        ), fields.get(new QName("urn:test", "createOrder")));

        Map<QName, List<SchemaField>> schemaFields = XsdFieldIndex.readElementSchemaFields(definition);
        SchemaField.ChoiceRef email = new SchemaField.ChoiceRef("#choice2", 0, 0, false);
        SchemaField.ChoiceRef phone = new SchemaField.ChoiceRef("#choice2", 1, 0, false);
        assertEquals(List.of(
                new SchemaField("customer.name", "string", true, false),
                new SchemaField("customer.phone", "string", true, false),
                new SchemaField("products[]", "object", true, true, null, false, 1, null, null, List.of()),
                new SchemaField("products[].sku", "string", true, false),
                new SchemaField("products[].qty", "int", true, false),
                new SchemaField("email", "string", false, false).withChoice(email),
                new SchemaField("phoneContact", "string", false, false).withChoice(phone),
                new SchemaField("externalId", "string", true, false)
        ), schemaFields.get(new QName("urn:test", "createOrder")));
    }

    @Test
    void readElementSchemaFields_carriesNillableOccursDocumentationAndNestedChoices() throws Exception {
        Definition definition = definitionWithSchema("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           targetNamespace="urn:test" elementFormDefault="qualified">
                  <xs:element name="pay">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="note" type="xs:string" nillable="true">
                          <xs:annotation><xs:documentation>
                            Free text,
                            shown to the payee.
                          </xs:documentation></xs:annotation>
                        </xs:element>
                        <xs:element name="tags" type="xs:string" minOccurs="2" maxOccurs="5"/>
                        <xs:choice minOccurs="0">
                          <xs:element name="card" type="xs:string"/>
                          <xs:sequence>
                            <xs:element name="iban" type="xs:string"/>
                            <xs:element name="bic" type="xs:string" minOccurs="0"/>
                          </xs:sequence>
                        </xs:choice>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        List<SchemaField> fields = XsdFieldIndex.readElementSchemaFields(definition).get(new QName("urn:test", "pay"));

        SchemaField.ChoiceRef card = new SchemaField.ChoiceRef("#choice2", 0, 0, true);
        SchemaField.ChoiceRef account = new SchemaField.ChoiceRef("#choice2", 1, 0, true);
        assertEquals(List.of(
                new SchemaField("note", "string", true, false, null, true, null, null,
                        "Free text, shown to the payee.", List.of()),
                new SchemaField("tags[]", "string", true, true, null, false, 2, 5, null, List.of()),
                new SchemaField("card", "string", false, false).withChoice(card),
                new SchemaField("iban", "string", false, false).withChoice(account),
                new SchemaField("bic", "string", false, false).withChoice(account)
        ), fields);
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

        Map<QName, List<String>> fields = XsdFieldIndex.readElementFields(definition);

        assertEquals(List.of(
                "return.uuid",
                "return.name",
                "return.labels"
        ), fields.get(new QName("urn:test", "getItemResponse")));
    }

    @Test
    void readElementSchemaFields_expandsExtensionAttributesSimpleContentGroupsAndFacets() throws Exception {
        Definition definition = definitionWithSchema("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           targetNamespace="urn:test"
                           xmlns:tns="urn:test"
                           elementFormDefault="qualified">
                  <xs:element name="register" type="tns:Register"/>
                  <xs:complexType name="Register">
                    <xs:sequence>
                      <xs:element name="customer" type="tns:VipCustomer"/>
                      <xs:element name="price" type="tns:Money" minOccurs="0"/>
                      <xs:group ref="tns:Contact"/>
                      <xs:element name="tags" type="tns:Tags" minOccurs="0"/>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:group name="Contact">
                    <xs:sequence>
                      <xs:element name="email" type="tns:Email"/>
                    </xs:sequence>
                  </xs:group>
                  <xs:complexType name="Customer">
                    <xs:sequence>
                      <xs:element name="name" type="xs:string"/>
                    </xs:sequence>
                    <xs:attribute name="id" type="xs:long" use="required"/>
                  </xs:complexType>
                  <xs:complexType name="VipCustomer">
                    <xs:complexContent>
                      <xs:extension base="tns:Customer">
                        <xs:sequence>
                          <xs:element name="level" type="tns:Level"/>
                          <xs:element name="discount" minOccurs="0">
                            <xs:simpleType>
                              <xs:restriction base="xs:int">
                                <xs:minInclusive value="0"/>
                                <xs:maxExclusive value="50"/>
                              </xs:restriction>
                            </xs:simpleType>
                          </xs:element>
                        </xs:sequence>
                        <xs:attributeGroup ref="tns:Audit"/>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                  <xs:attributeGroup name="Audit">
                    <xs:attribute name="since" type="xs:date"/>
                  </xs:attributeGroup>
                  <xs:simpleType name="Level">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="GOLD"/>
                      <xs:enumeration value="PLATINUM"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:complexType name="Money">
                    <xs:simpleContent>
                      <xs:extension base="xs:decimal">
                        <xs:attribute name="currency" type="tns:Currency" use="required"/>
                      </xs:extension>
                    </xs:simpleContent>
                  </xs:complexType>
                  <xs:simpleType name="Currency">
                    <xs:restriction base="tns:Code">
                      <xs:length value="3"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:simpleType name="Code">
                    <xs:restriction base="xs:token">
                      <xs:pattern value="[A-Z]+"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:simpleType name="Email">
                    <xs:restriction base="xs:string">
                      <xs:maxLength value="64"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:simpleType name="Tags">
                    <xs:list itemType="xs:string"/>
                  </xs:simpleType>
                </xs:schema>
                """);

        List<SchemaField> fields = XsdFieldIndex.readElementSchemaFields(definition).get(new QName("urn:test", "register"));

        SchemaFacets level = new SchemaFacets(List.of("GOLD", "PLATINUM"), null, null, null, null, false, null, false);
        SchemaFacets discount = new SchemaFacets(List.of(), null, null, null,
                BigDecimal.ZERO, false, new BigDecimal("50"), true);
        SchemaFacets currency = new SchemaFacets(List.of(), "[A-Z]+", 3, 3, null, false, null, false);
        SchemaFacets email = new SchemaFacets(List.of(), null, null, 64, null, false, null, false);
        assertEquals(List.of(
                new SchemaField("customer.name", "string", true, false),
                new SchemaField("customer.id", "long", true, false),
                new SchemaField("customer.level", "string", true, false, level),
                new SchemaField("customer.discount", "int", false, false, discount),
                new SchemaField("customer.since", "date", false, false),
                new SchemaField("price.value", "decimal", false, false),
                new SchemaField("price.currency", "token", false, false, currency),
                new SchemaField("email", "string", true, false, email),
                new SchemaField("tags[]", "string", false, true)
        ), fields);
    }

    @Test
    void readElementSchemaFields_resolvesTypesOfAnotherInlineSchema() throws Exception {
        WSDLFactory factory = WSDLFactory.newInstance();
        Definition definition = factory.newDefinition();
        Types types = definition.createTypes();
        types.addExtensibilityElement(schema("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:api"
                           xmlns:dto="urn:dto" elementFormDefault="qualified">
                  <xs:import namespace="urn:dto"/>
                  <xs:element name="find">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="filter" type="dto:Filter"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """));
        types.addExtensibilityElement(schema("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:dto"
                           elementFormDefault="qualified">
                  <xs:complexType name="Filter">
                    <xs:sequence>
                      <xs:element name="text" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """));
        definition.setTypes(types);

        assertEquals(List.of(new SchemaField("filter.text", "string", true, false)),
                XsdFieldIndex.readElementSchemaFields(definition).get(new QName("urn:api", "find")));
    }

    @Test
    void readElementSchemaFields_expandsTypeNamedLikeAnEnclosingTypeOfAnotherNamespace() throws Exception {
        WSDLFactory factory = WSDLFactory.newInstance();
        Definition definition = factory.newDefinition();
        Types types = definition.createTypes();
        types.addExtensibilityElement(schema("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:a"
                           xmlns:a="urn:a" xmlns:b="urn:b" elementFormDefault="qualified">
                  <xs:import namespace="urn:b"/>
                  <xs:element name="item" type="a:Item"/>
                  <xs:complexType name="Item">
                    <xs:sequence>
                      <xs:element name="name" type="xs:string"/>
                      <xs:element name="part" type="b:Item"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """));
        types.addExtensibilityElement(schema("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:b"
                           elementFormDefault="qualified">
                  <xs:complexType name="Item">
                    <xs:sequence>
                      <xs:element name="code" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """));
        definition.setTypes(types);

        assertEquals(List.of(
                new SchemaField("name", "string", true, false),
                new SchemaField("part.code", "string", true, false)
        ), XsdFieldIndex.readElementSchemaFields(definition).get(new QName("urn:a", "item")));
    }

    private static SchemaImpl schema(String xsd) throws Exception {
        SchemaImpl schema = new SchemaImpl();
        schema.setElement(parseDocument(xsd).getDocumentElement());
        schema.setDocumentBaseURI("memory:test.wsdl");
        return schema;
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
