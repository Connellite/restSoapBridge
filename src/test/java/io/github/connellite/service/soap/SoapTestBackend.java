package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.WsdlUrlCollector;
import io.github.connellite.model.DiscoveredSoapService;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.service.auto.AutoMappingGenerator;
import io.github.connellite.service.auto.WsdlParser;
import io.github.connellite.service.auto.WsdlServiceDiscovery;
import io.github.connellite.service.impl.BridgeServiceImpl;
import io.github.connellite.service.validation.RequestSchemaValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.cxf.endpoint.Server;
import org.apache.cxf.jaxws.JaxWsServerFactoryBean;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.net.ServerSocket;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * In-process CXF endpoint plus the bridge pipeline in front of it: WSDL parsing, mapping generation,
 * validation, argument binding, invocation and response mapping.
 */
final class SoapTestBackend implements AutoCloseable {

    private final Server server;
    private final String serviceName;
    private final String wsdlUrl;
    private final List<MappingDefinition> mappings;

    private SoapTestBackend(Server server, String serviceName, String wsdlUrl, List<MappingDefinition> mappings) {
        this.server = server;
        this.serviceName = serviceName;
        this.wsdlUrl = wsdlUrl;
        this.mappings = mappings;
    }

    static SoapTestBackend start(String serviceName, Class<?> serviceClass, Object serviceBean) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        JaxWsServerFactoryBean factory = new JaxWsServerFactoryBean();
        factory.setServiceClass(serviceClass);
        factory.setServiceBean(serviceBean);
        factory.setAddress("http://localhost:" + port + "/services/" + serviceName);
        Server server = factory.create();
        String wsdlUrl = "http://localhost:" + port + "/services/" + serviceName + "?wsdl";

        WsdlServiceDiscovery discovery = mock(WsdlServiceDiscovery.class);
        WsdlUrlCollector collector = mock(WsdlUrlCollector.class);
        when(collector.wsdlUrls()).thenReturn(List.of());
        when(discovery.discover(null, List.of())).thenReturn(List.of(new DiscoveredSoapService(
                serviceName, wsdlUrl, new WsdlParser().parseOperations(wsdlUrl))));
        List<MappingDefinition> mappings = new AutoMappingGenerator(properties(), discovery, collector).generate();
        return new SoapTestBackend(server, serviceName, wsdlUrl, mappings);
    }

    static BridgeProperties properties() {
        BridgeProperties properties = new BridgeProperties();
        BridgeProperties.AutoMapping auto = new BridgeProperties.AutoMapping();
        auto.setPathPrefix("/api");
        auto.setHttpMethod("POST");
        properties.setAuto(auto);
        return properties;
    }

    String wsdlUrl() {
        return wsdlUrl;
    }

    List<MappingDefinition> mappings() {
        return mappings;
    }

    MappingDefinition mapping(String operation) {
        return mappings.stream()
                .filter(candidate -> candidate.getSoap().getOperation().equals(operation))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No mapping for " + serviceName + "." + operation));
    }

    ObjectNode execute(String operation, JsonNode body) throws Exception {
        return execute(properties(), mapping(operation), body);
    }

    ObjectNode execute(BridgeProperties properties, MappingDefinition mapping, JsonNode body) throws Exception {
        return new BridgeServiceImpl(invoker(properties), new RequestSchemaValidator(properties)).execute(mapping, body);
    }

    SoapClientPool pool(BridgeProperties properties) {
        return new SoapClientPool(properties, new SoapClientFactory(properties),
                new StaticListableBeanFactory().getBeanProvider(MeterRegistry.class));
    }

    SoapInvoker invoker(BridgeProperties properties) {
        return new SoapInvoker(pool(properties), properties);
    }

    @Override
    public void close() {
        server.destroy();
    }
}
