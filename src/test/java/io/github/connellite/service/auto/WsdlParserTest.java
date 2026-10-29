package io.github.connellite.service.auto;

import io.github.connellite.model.SchemaField;
import io.github.connellite.model.WsdlOperationModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WsdlParserTest {

    @TempDir
    Path dir;

    /**
     * CXF publishes a service whose interface lives in another namespace as {@code ?wsdl} importing
     * {@code ?wsdl=Interface.wsdl}: types, messages and port type are all in the imported document.
     */
    @Test
    void parseOperations_readsSchemasOfImportedWsdl() throws Exception {
        write("iface.wsdl", """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                                  xmlns:xs="http://www.w3.org/2001/XMLSchema"
                                  xmlns:api="urn:api" targetNamespace="urn:api">
                  <wsdl:types>
                    <xs:schema targetNamespace="urn:api" elementFormDefault="qualified">
                      <xs:element name="echo">
                        <xs:complexType><xs:sequence>
                          <xs:element name="text" type="xs:string"/>
                        </xs:sequence></xs:complexType>
                      </xs:element>
                      <xs:element name="echoResponse">
                        <xs:complexType><xs:sequence>
                          <xs:element name="return" type="xs:string" minOccurs="0"/>
                        </xs:sequence></xs:complexType>
                      </xs:element>
                    </xs:schema>
                  </wsdl:types>
                  <wsdl:message name="echo"><wsdl:part name="parameters" element="api:echo"/></wsdl:message>
                  <wsdl:message name="echoResponse"><wsdl:part name="parameters" element="api:echoResponse"/></wsdl:message>
                  <wsdl:portType name="Echo">
                    <wsdl:operation name="echo">
                      <wsdl:input message="api:echo"/>
                      <wsdl:output message="api:echoResponse"/>
                    </wsdl:operation>
                  </wsdl:portType>
                </wsdl:definitions>
                """);
        write("main.wsdl", """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                                  xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                                  xmlns:api="urn:api" xmlns:svc="urn:svc" targetNamespace="urn:svc">
                  <wsdl:import namespace="urn:api" location="iface.wsdl"/>
                  <wsdl:binding name="EchoBinding" type="api:Echo">
                    <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                    <wsdl:operation name="echo">
                      <soap:operation soapAction=""/>
                      <wsdl:input><soap:body use="literal"/></wsdl:input>
                      <wsdl:output><soap:body use="literal"/></wsdl:output>
                    </wsdl:operation>
                  </wsdl:binding>
                  <wsdl:service name="EchoService">
                    <wsdl:port name="EchoPort" binding="svc:EchoBinding">
                      <soap:address location="http://localhost/echo"/>
                    </wsdl:port>
                  </wsdl:service>
                </wsdl:definitions>
                """);

        WsdlOperationModel echo = operation(parse("main.wsdl"), "echo");

        assertEquals(List.of(new SchemaField("text", "string", true, false)), echo.inputSchemaFields());
        assertEquals(List.of(new SchemaField("return", "string", false, false)), echo.outputSchemaFields());
    }

    @Test
    void parseOperations_resolvesXsdImportRelativeToImportedWsdl() throws Exception {
        Files.createDirectories(dir.resolve("api"));
        write("api/dto.xsd", """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" targetNamespace="urn:dto"
                           elementFormDefault="qualified">
                  <xs:complexType name="Filter">
                    <xs:sequence><xs:element name="query" type="xs:string"/></xs:sequence>
                  </xs:complexType>
                </xs:schema>
                """);
        write("api/iface.wsdl", """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                                  xmlns:xs="http://www.w3.org/2001/XMLSchema"
                                  xmlns:api="urn:api" targetNamespace="urn:api">
                  <wsdl:types>
                    <xs:schema targetNamespace="urn:api" xmlns:dto="urn:dto" elementFormDefault="qualified">
                      <xs:import namespace="urn:dto" schemaLocation="dto.xsd"/>
                      <xs:element name="find">
                        <xs:complexType><xs:sequence>
                          <xs:element name="filter" type="dto:Filter"/>
                        </xs:sequence></xs:complexType>
                      </xs:element>
                    </xs:schema>
                  </wsdl:types>
                  <wsdl:message name="find"><wsdl:part name="parameters" element="api:find"/></wsdl:message>
                  <wsdl:portType name="Finder">
                    <wsdl:operation name="find"><wsdl:input message="api:find"/></wsdl:operation>
                  </wsdl:portType>
                </wsdl:definitions>
                """);
        write("main.wsdl", """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                                  targetNamespace="urn:svc">
                  <wsdl:import namespace="urn:api" location="api/iface.wsdl"/>
                </wsdl:definitions>
                """);

        WsdlOperationModel find = operation(parse("main.wsdl"), "find");

        assertEquals(List.of(new SchemaField("filter.query", "string", true, false)), find.inputSchemaFields());
    }

    @Test
    void parseOperations_keepsElementsOfSameLocalNameInDifferentNamespacesApart() throws Exception {
        write("service.wsdl", """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                                  xmlns:xs="http://www.w3.org/2001/XMLSchema"
                                  xmlns:a="urn:a" xmlns:b="urn:b" xmlns:tns="urn:svc" targetNamespace="urn:svc">
                  <wsdl:types>
                    <xs:schema targetNamespace="urn:a" elementFormDefault="qualified">
                      <xs:element name="Request">
                        <xs:complexType><xs:sequence>
                          <xs:element name="name" type="xs:string"/>
                        </xs:sequence></xs:complexType>
                      </xs:element>
                    </xs:schema>
                    <xs:schema targetNamespace="urn:b" elementFormDefault="qualified">
                      <xs:element name="Request">
                        <xs:complexType><xs:sequence>
                          <xs:element name="code" type="xs:int"/>
                        </xs:sequence></xs:complexType>
                      </xs:element>
                    </xs:schema>
                  </wsdl:types>
                  <wsdl:message name="searchA"><wsdl:part name="parameters" element="a:Request"/></wsdl:message>
                  <wsdl:message name="searchB"><wsdl:part name="parameters" element="b:Request"/></wsdl:message>
                  <wsdl:portType name="Search">
                    <wsdl:operation name="searchA"><wsdl:input message="tns:searchA"/></wsdl:operation>
                    <wsdl:operation name="searchB"><wsdl:input message="tns:searchB"/></wsdl:operation>
                  </wsdl:portType>
                </wsdl:definitions>
                """);

        List<WsdlOperationModel> operations = parse("service.wsdl");

        assertEquals(List.of(new SchemaField("name", "string", true, false)),
                operation(operations, "searchA").inputSchemaFields());
        assertEquals(List.of(new SchemaField("code", "int", true, false)),
                operation(operations, "searchB").inputSchemaFields());
    }

    @Test
    void parseOperations_doesNotTakeFieldsOfComplexTypeNamedLikeTheElement() throws Exception {
        write("service.wsdl", """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                                  xmlns:xs="http://www.w3.org/2001/XMLSchema"
                                  xmlns:tns="urn:svc" targetNamespace="urn:svc">
                  <wsdl:types>
                    <xs:schema targetNamespace="urn:svc" elementFormDefault="qualified">
                      <xs:element name="order" type="tns:OrderRequest"/>
                      <xs:complexType name="OrderRequest">
                        <xs:sequence><xs:element name="id" type="xs:long"/></xs:sequence>
                      </xs:complexType>
                      <xs:complexType name="order">
                        <xs:sequence><xs:element name="legacy" type="xs:string"/></xs:sequence>
                      </xs:complexType>
                    </xs:schema>
                  </wsdl:types>
                  <wsdl:message name="order"><wsdl:part name="parameters" element="tns:order"/></wsdl:message>
                  <wsdl:portType name="Orders">
                    <wsdl:operation name="order"><wsdl:input message="tns:order"/></wsdl:operation>
                  </wsdl:portType>
                </wsdl:definitions>
                """);

        assertEquals(List.of(new SchemaField("id", "long", true, false)),
                operation(parse("service.wsdl"), "order").inputSchemaFields());
    }

    @Test
    void parseOperations_readsSeveralInlineSchemasOfOneNamespace() throws Exception {
        write("service.wsdl", """
                <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                                  xmlns:xs="http://www.w3.org/2001/XMLSchema"
                                  xmlns:tns="urn:svc" targetNamespace="urn:svc">
                  <wsdl:types>
                    <xs:schema targetNamespace="urn:svc" elementFormDefault="qualified">
                      <xs:element name="find">
                        <xs:complexType><xs:sequence>
                          <xs:element name="filter" type="tns:Filter"/>
                        </xs:sequence></xs:complexType>
                      </xs:element>
                    </xs:schema>
                    <xs:schema targetNamespace="urn:svc" elementFormDefault="qualified">
                      <xs:complexType name="Filter">
                        <xs:sequence><xs:element name="query" type="xs:string"/></xs:sequence>
                      </xs:complexType>
                    </xs:schema>
                  </wsdl:types>
                  <wsdl:message name="find"><wsdl:part name="parameters" element="tns:find"/></wsdl:message>
                  <wsdl:portType name="Finder">
                    <wsdl:operation name="find"><wsdl:input message="tns:find"/></wsdl:operation>
                  </wsdl:portType>
                </wsdl:definitions>
                """);

        assertEquals(List.of(new SchemaField("filter.query", "string", true, false)),
                operation(parse("service.wsdl"), "find").inputSchemaFields());
    }

    private List<WsdlOperationModel> parse(String file) {
        return new WsdlParser().parseOperations(dir.resolve(file).toUri().toString());
    }

    private static WsdlOperationModel operation(List<WsdlOperationModel> operations, String name) {
        return operations.stream()
                .filter(operation -> operation.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no operation " + name + " in " + operations));
    }

    private void write(String file, String content) throws IOException {
        Files.writeString(dir.resolve(file), content, StandardCharsets.UTF_8);
    }
}
