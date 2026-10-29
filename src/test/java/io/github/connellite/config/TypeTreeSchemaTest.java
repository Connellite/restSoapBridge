package io.github.connellite.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.model.DiscoveredSoapService;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.MappingRegistry;
import io.github.connellite.service.auto.AutoMappingGenerator;
import io.github.connellite.service.auto.WsdlParser;
import io.github.connellite.service.auto.WsdlServiceDiscovery;
import io.github.connellite.service.validation.RequestSchemaValidator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Named XSD types are published once as components and referred to by {@code $ref}: their names stay in the
 * document, a type may contain itself, and validation follows the same tree to any depth.
 */
class TypeTreeSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path dir;

    @Test
    void namedTypesBecomeSharedComponents() throws Exception {
        OpenAPI openApi = openApi(mappings("Shop", SHOP_WSDL));

        Schema<?> order = openApi.getComponents().getSchemas().get("Order");
        assertEquals("#/components/schemas/Customer", ((Schema<?>) order.getProperties().get("customer")).get$ref());
        Schema<?> request = openApi.getComponents().getSchemas().get("Shop_placeRequest");
        assertEquals("#/components/schemas/Order", ((Schema<?>) request.getProperties().get("order")).get$ref());
        Schema<?> lookup = openApi.getComponents().getSchemas().get("Shop_lookupResponse");
        assertEquals("#/components/schemas/Customer", ((Schema<?>) lookup.getProperties().get("customer")).get$ref());
        assertEquals("A buyer", openApi.getComponents().getSchemas().get("Customer").getDescription());
    }

    @Test
    void recursiveTypeRefersToItself() throws Exception {
        OpenAPI openApi = openApi(mappings("Shop", SHOP_WSDL));

        Schema<?> category = openApi.getComponents().getSchemas().get("Category");
        Schema<?> children = (Schema<?>) category.getProperties().get("children");
        assertEquals("#/components/schemas/Category", children.getItems().get$ref());
    }

    @Test
    void validationFollowsRecursiveTypesToAnyDepth() throws Exception {
        MappingDefinition classify = mapping(mappings("Shop", SHOP_WSDL), "classify");
        RequestSchemaValidator validator = new RequestSchemaValidator(SoapTestProperties.properties());

        assertDoesNotThrow(() -> validator.validate(classify, MAPPER.readTree("""
                {"category": {"name": "a", "children": [{"name": "b", "children": [{"name": "c"}]}]}}
                """)));
        RequestValidationException deep = assertThrows(RequestValidationException.class,
                () -> validator.validate(classify, MAPPER.readTree("""
                        {"category": {"name": "a", "children": [{"name": "b", "children": [{"name": 7, "x": 1}]}]}}
                        """)));
        assertTrue(deep.getErrors().stream().anyMatch(error -> error.contains("children[0].children[0].name")),
                deep.getErrors().toString());
    }

    @Test
    void requiredFieldOfAnOptionalObjectIsRequiredWhenTheObjectIsSent() throws Exception {
        MappingDefinition place = mapping(mappings("Shop", SHOP_WSDL), "place");
        RequestSchemaValidator validator = new RequestSchemaValidator(SoapTestProperties.properties());

        assertDoesNotThrow(() -> validator.validate(place, MAPPER.readTree("{\"order\": {\"id\": 1}}")));
        RequestValidationException missingName = assertThrows(RequestValidationException.class,
                () -> validator.validate(place, MAPPER.readTree("{\"order\": {\"id\": 1, \"customer\": {}}}")));
        assertTrue(missingName.getErrors().stream().anyMatch(error -> error.contains("name")),
                missingName.getErrors().toString());
    }

    @Test
    void queryParametersFollowReferences() throws Exception {
        List<MappingDefinition> mappings = mappings("Shop", SHOP_WSDL);
        mapping(mappings, "place").setMethod("GET");
        mapping(mappings, "classify").setMethod("GET");

        OpenAPI openApi = openApi(mappings);

        assertEquals(List.of("order.id", "order.customer.name"), openApi.getPaths().get("/api/Shop/place").getGet()
                .getParameters().stream().map(parameter -> parameter.getName()).toList());
        // Arrays of objects have no query form: the recursion through children stops there.
        assertEquals(List.of("category.name"), openApi.getPaths().get("/api/Shop/classify").getGet()
                .getParameters().stream().map(parameter -> parameter.getName()).toList());
    }

    @Test
    void typesOfOneLocalNameInDifferentNamespacesGetQualifiedNames() throws Exception {
        OpenAPI openApi = openApi(mappings("Dual", DUAL_WSDL));

        assertNull(openApi.getComponents().getSchemas().get("Item"));
        assertTrue(openApi.getComponents().getSchemas().containsKey("Dual_Item"));
        assertTrue(openApi.getComponents().getSchemas().containsKey("Dual_Item_2"));
        Schema<?> first = openApi.getComponents().getSchemas().get("Dual_getARequest");
        Schema<?> second = openApi.getComponents().getSchemas().get("Dual_getBRequest");
        assertEquals("#/components/schemas/Dual_Item", ((Schema<?>) first.getProperties().get("item")).get$ref());
        assertEquals("#/components/schemas/Dual_Item_2", ((Schema<?>) second.getProperties().get("item")).get$ref());
    }

    private List<MappingDefinition> mappings(String service, String wsdl) throws Exception {
        Path file = dir.resolve(service + ".wsdl");
        Files.writeString(file, wsdl, StandardCharsets.UTF_8);
        String url = file.toUri().toString();
        WsdlServiceDiscovery discovery = mock(WsdlServiceDiscovery.class);
        WsdlUrlCollector collector = mock(WsdlUrlCollector.class);
        when(collector.wsdlUrls()).thenReturn(List.of());
        when(discovery.discover(null, List.of())).thenReturn(List.of(
                new DiscoveredSoapService(service, url, new WsdlParser().parseOperations(url))));
        return new AutoMappingGenerator(SoapTestProperties.properties(), discovery, collector).generate();
    }

    private static OpenAPI openApi(List<MappingDefinition> mappings) {
        return new BridgeOpenApiBuilder(new MappingRegistry(mappings),
                new StaticListableBeanFactory().getBeanProvider(BuildProperties.class)).build();
    }

    private static MappingDefinition mapping(List<MappingDefinition> mappings, String operation) {
        return mappings.stream().filter(mapping -> mapping.getSoap().getOperation().equals(operation)).findFirst()
                .orElseThrow();
    }

    /** Bridge properties as the application has them, without a Spring context. */
    private static final class SoapTestProperties {
        static BridgeProperties properties() {
            BridgeProperties properties = new BridgeProperties();
            BridgeProperties.AutoMapping auto = new BridgeProperties.AutoMapping();
            auto.setPathPrefix("/api");
            auto.setHttpMethod("POST");
            properties.setAuto(auto);
            return properties;
        }
    }

    private static String operations(String ns, String... names) {
        StringBuilder messages = new StringBuilder();
        StringBuilder operations = new StringBuilder();
        for (String name : names) {
            messages.append("<wsdl:message name=\"").append(name).append("\"><wsdl:part name=\"parameters\" element=\"")
                    .append(ns).append(':').append(name).append("\"/></wsdl:message>")
                    .append("<wsdl:message name=\"").append(name).append("Response\"><wsdl:part name=\"parameters\" element=\"")
                    .append(ns).append(':').append(name).append("Response\"/></wsdl:message>");
            operations.append("<wsdl:operation name=\"").append(name).append("\"><wsdl:input message=\"tns:")
                    .append(name).append("\"/><wsdl:output message=\"tns:").append(name)
                    .append("Response\"/></wsdl:operation>");
        }
        return messages + "<wsdl:portType name=\"Port\">" + operations + "</wsdl:portType>";
    }

    private static final String SHOP_WSDL = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:xs="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="urn:shop" targetNamespace="urn:shop">
              <wsdl:types>
                <xs:schema targetNamespace="urn:shop" elementFormDefault="qualified">
                  <xs:complexType name="Customer">
                    <xs:annotation><xs:documentation>A buyer</xs:documentation></xs:annotation>
                    <xs:sequence><xs:element name="name" type="xs:string"/></xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="Order">
                    <xs:sequence>
                      <xs:element name="id" type="xs:long"/>
                      <xs:element name="customer" type="tns:Customer" minOccurs="0"/>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="Category">
                    <xs:sequence>
                      <xs:element name="name" type="xs:string"/>
                      <xs:element name="children" type="tns:Category" minOccurs="0" maxOccurs="unbounded"/>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:element name="place"><xs:complexType><xs:sequence>
                    <xs:element name="order" type="tns:Order"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="placeResponse"><xs:complexType><xs:sequence>
                    <xs:element name="return" type="xs:string"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="lookup"><xs:complexType><xs:sequence>
                    <xs:element name="id" type="xs:long"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="lookupResponse"><xs:complexType><xs:sequence>
                    <xs:element name="customer" type="tns:Customer"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="classify"><xs:complexType><xs:sequence>
                    <xs:element name="category" type="tns:Category"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="classifyResponse"><xs:complexType><xs:sequence>
                    <xs:element name="return" type="xs:string"/>
                  </xs:sequence></xs:complexType></xs:element>
                </xs:schema>
              </wsdl:types>
            """ + operations("tns", "place", "lookup", "classify") + "</wsdl:definitions>";

    private static final String DUAL_WSDL = """
            <wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                              xmlns:xs="http://www.w3.org/2001/XMLSchema"
                              xmlns:tns="urn:dual" xmlns:a="urn:a" xmlns:b="urn:b" targetNamespace="urn:dual">
              <wsdl:types>
                <xs:schema targetNamespace="urn:a" elementFormDefault="qualified">
                  <xs:complexType name="Item"><xs:sequence><xs:element name="code" type="xs:string"/></xs:sequence></xs:complexType>
                </xs:schema>
                <xs:schema targetNamespace="urn:b" elementFormDefault="qualified">
                  <xs:complexType name="Item"><xs:sequence><xs:element name="size" type="xs:int"/></xs:sequence></xs:complexType>
                </xs:schema>
                <xs:schema targetNamespace="urn:dual" elementFormDefault="qualified">
                  <xs:import namespace="urn:a"/>
                  <xs:import namespace="urn:b"/>
                  <xs:element name="getA"><xs:complexType><xs:sequence>
                    <xs:element name="item" type="a:Item"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="getAResponse"><xs:complexType><xs:sequence>
                    <xs:element name="return" type="xs:string"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="getB"><xs:complexType><xs:sequence>
                    <xs:element name="item" type="b:Item"/>
                  </xs:sequence></xs:complexType></xs:element>
                  <xs:element name="getBResponse"><xs:complexType><xs:sequence>
                    <xs:element name="return" type="xs:string"/>
                  </xs:sequence></xs:complexType></xs:element>
                </xs:schema>
              </wsdl:types>
            """ + operations("tns", "getA", "getB") + "</wsdl:definitions>";
}
