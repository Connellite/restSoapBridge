package io.github.connellite.service.auto;

import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.WsdlUrlCollector;
import io.github.connellite.model.DiscoveredSoapService;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.SchemaField;
import io.github.connellite.model.WsdlOperationModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutoMappingGeneratorTest {

    @Test
    void generate_mapsNestedFieldsAndCollectionParents() throws Exception {
        BridgeProperties properties = new BridgeProperties();
        BridgeProperties.AutoMapping auto = new BridgeProperties.AutoMapping();
        auto.setServicesUrl("http://localhost:8080/services/");
        auto.setPathPrefix("api");
        auto.setHttpMethod("POST");
        properties.setAuto(auto);

        WsdlServiceDiscovery discovery = mock(WsdlServiceDiscovery.class);
        WsdlUrlCollector wsdlUrlCollector = mock(WsdlUrlCollector.class);
        when(wsdlUrlCollector.wsdlUrls()).thenReturn(List.of());
        when(discovery.discover(auto.getServicesUrl(), List.of())).thenReturn(List.of(
                new DiscoveredSoapService(
                        "OrderService",
                        "http://localhost:8080/services/OrderService?wsdl",
                        List.of(new WsdlOperationModel(
                                "CreateOrder",
                                "createOrder",
                                List.of("customer.name", "customer.phone", "products[]", "products[].sku"),
                                List.of(
                                        new SchemaField("customer.name", "string", true, false),
                                        new SchemaField("customer.phone", "string", true, false),
                                        new SchemaField("products[]", "object", true, true),
                                        new SchemaField("products[].sku", "string", true, false)
                                ),
                                "createOrderResponse",
                                List.of("return"),
                                List.of(new SchemaField("return", "string", true, false))
                        ))
                )
        ));

        AutoMappingGenerator generator = new AutoMappingGenerator(properties, discovery, wsdlUrlCollector);

        List<MappingDefinition> mappings = generator.generate();

        assertEquals(1, mappings.size());
        MappingDefinition mapping = mappings.get(0);
        assertEquals("/api/OrderService/CreateOrder", mapping.getPath());
        assertEquals(Map.of(
                "$.customer.name", "$.createOrder.customer.name",
                "$.customer.phone", "$.createOrder.customer.phone",
                "$.products", "$.createOrder.products"
        ), mapping.getRequest());
        assertEquals(Map.of("#root", "$.return"), mapping.getResponse());
        assertEquals(List.of("$.customer.name", "$.customer.phone", "$.products", "$.products[].sku"),
                mapping.getRequestSchemaPaths());
        assertEquals(List.of(
                new SchemaField("$.customer.name", "string", true, false),
                new SchemaField("$.customer.phone", "string", true, false),
                new SchemaField("$.products", "object", true, true),
                new SchemaField("$.products[].sku", "string", true, false)
        ), mapping.getRequestSchemaFields());
    }

    @Test
    void generate_returnsEmptyMappingsWhenNoDiscoverySourcesConfigured() throws Exception {
        BridgeProperties properties = new BridgeProperties();
        BridgeProperties.AutoMapping auto = new BridgeProperties.AutoMapping();
        auto.setPathPrefix("/api");
        auto.setHttpMethod("POST");
        properties.setAuto(auto);

        WsdlServiceDiscovery discovery = mock(WsdlServiceDiscovery.class);
        WsdlUrlCollector wsdlUrlCollector = mock(WsdlUrlCollector.class);
        when(wsdlUrlCollector.wsdlUrls()).thenReturn(List.of());
        when(discovery.discover(null, List.of())).thenReturn(List.of());

        AutoMappingGenerator generator = new AutoMappingGenerator(properties, discovery, wsdlUrlCollector);

        assertEquals(0, generator.generate().size());
    }

    @Test
    void generate_stripsWsdlResponseWrapperFromNestedSpelPaths() throws Exception {
        BridgeProperties properties = new BridgeProperties();
        BridgeProperties.AutoMapping auto = new BridgeProperties.AutoMapping();
        auto.setServicesUrl("http://localhost:8080/services/");
        auto.setPathPrefix("api");
        auto.setHttpMethod("POST");
        properties.setAuto(auto);

        WsdlServiceDiscovery discovery = mock(WsdlServiceDiscovery.class);
        WsdlUrlCollector wsdlUrlCollector = mock(WsdlUrlCollector.class);
        when(wsdlUrlCollector.wsdlUrls()).thenReturn(List.of());
        when(discovery.discover(auto.getServicesUrl(), List.of())).thenReturn(List.of(
                new DiscoveredSoapService(
                        "ConfigService",
                        "http://localhost:8080/services/ConfigService?wsdl",
                        List.of(new WsdlOperationModel(
                                "GetConfig",
                                "getConfig",
                                List.of(),
                                List.of(),
                                "GetConfigResponse",
                                List.of("return.analyzeAbbreviations", "return.language"),
                                List.of(
                                        new SchemaField("return.analyzeAbbreviations", "boolean", true, false),
                                        new SchemaField("return.language", "string", true, false)
                                )
                        ))
                )
        ));

        AutoMappingGenerator generator = new AutoMappingGenerator(properties, discovery, wsdlUrlCollector);

        MappingDefinition mapping = generator.generate().get(0);

        assertEquals(Map.of(
                "#root.analyzeAbbreviations", "$.return.analyzeAbbreviations",
                "#root.language", "$.return.language"
        ), mapping.getResponse());
    }

    @Test
    void generate_skipsJaxbMapEntrySpelPaths() throws Exception {
        BridgeProperties properties = new BridgeProperties();
        BridgeProperties.AutoMapping auto = new BridgeProperties.AutoMapping();
        auto.setServicesUrl("http://localhost:8080/services/");
        auto.setPathPrefix("api");
        auto.setHttpMethod("POST");
        properties.setAuto(auto);

        WsdlServiceDiscovery discovery = mock(WsdlServiceDiscovery.class);
        WsdlUrlCollector wsdlUrlCollector = mock(WsdlUrlCollector.class);
        when(wsdlUrlCollector.wsdlUrls()).thenReturn(List.of());
        when(discovery.discover(auto.getServicesUrl(), List.of())).thenReturn(List.of(
                new DiscoveredSoapService(
                        "CatalogService",
                        "http://localhost:8080/services/CatalogService?wsdl",
                        List.of(new WsdlOperationModel(
                                "getItem",
                                "getItem",
                                List.of("token"),
                                List.of(new SchemaField("token", "string", true, false)),
                                "getItemResponse",
                                List.of("return.uuid", "return.labels", "return.labels.entry[]", "return.labels.entry[].key"),
                                List.of(
                                        new SchemaField("return.uuid", "string", true, false),
                                        new SchemaField("return.labels", "object", false, false),
                                        new SchemaField("return.labels.entry[]", "object", false, true),
                                        new SchemaField("return.labels.entry[].key", "string", false, false)
                                )
                        ))
                )
        ));

        AutoMappingGenerator generator = new AutoMappingGenerator(properties, discovery, wsdlUrlCollector);

        MappingDefinition mapping = generator.generate().get(0);

        assertEquals(Map.of(
                "#root.uuid", "$.return.uuid",
                "#root.labels", "$.return.labels"
        ), mapping.getResponse());
    }

    @Test
    void generate_usesSpelIndexerForNonIdentifierXmlNames() throws Exception {
        BridgeProperties properties = new BridgeProperties();
        BridgeProperties.AutoMapping auto = new BridgeProperties.AutoMapping();
        auto.setServicesUrl("http://localhost:8080/services/");
        auto.setPathPrefix("api");
        auto.setHttpMethod("POST");
        properties.setAuto(auto);

        WsdlServiceDiscovery discovery = mock(WsdlServiceDiscovery.class);
        WsdlUrlCollector wsdlUrlCollector = mock(WsdlUrlCollector.class);
        when(wsdlUrlCollector.wsdlUrls()).thenReturn(List.of());
        when(discovery.discover(auto.getServicesUrl(), List.of())).thenReturn(List.of(
                new DiscoveredSoapService(
                        "ItemService",
                        "http://localhost:8080/services/ItemService?wsdl",
                        List.of(new WsdlOperationModel(
                                "getItem",
                                "getItem",
                                List.of(),
                                List.of(),
                                "getItemResponse",
                                List.of("return.item-id", "return.class", "return.owner.full-name"),
                                List.of()
                        ))
                )
        ));

        MappingDefinition mapping = new AutoMappingGenerator(properties, discovery, wsdlUrlCollector).generate().get(0);

        assertEquals(Map.of(
                "#root['item-id']", "$.return.item-id",
                "#root.class", "$.return.class",
                "#root.owner['full-name']", "$.return.owner.full-name"
        ), mapping.getResponse());
    }
}
