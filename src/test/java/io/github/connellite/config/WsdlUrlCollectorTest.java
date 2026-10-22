package io.github.connellite.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WsdlUrlCollectorTest {

    @Test
    void collectsWsdlUrlPropertiesWithOptionalSuffix() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bridge.auto.wsdl-url", "http://localhost/Auth?wsdl")
                .withProperty("bridge.auto.wsdl-1-url", "http://localhost/One?wsdl")
                .withProperty("bridge.auto.wsdl-auth-url", "http://localhost/Auth2?wsdl")
                .withProperty("bridge.auto.services-url", "http://localhost/services/");

        WsdlUrlCollector collector = new WsdlUrlCollector(environment);

        assertEquals(3, collector.wsdlUrls().size());
        assertEquals(
                Set.of(
                        "http://localhost/Auth?wsdl",
                        "http://localhost/One?wsdl",
                        "http://localhost/Auth2?wsdl"
                ),
                Set.copyOf(collector.wsdlUrls())
        );
    }

    @Test
    void collectsWsdlUrlsFromEnvironmentVariables() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().addFirst(systemEnvironment(Map.of(
                "BRIDGE_AUTO_WSDL_URL", "http://env/Auth?wsdl",
                "BRIDGE_AUTO_WSDL_1_URL", "http://env/One?wsdl",
                "BRIDGE_AUTO_WSDL_AUTH_URL", "http://env/Auth2?wsdl",
                "BRIDGE_AUTO_SERVICES_URL", "http://env/services/"
        )));

        WsdlUrlCollector collector = new WsdlUrlCollector(environment);

        assertEquals(
                Set.of("http://env/Auth?wsdl", "http://env/One?wsdl", "http://env/Auth2?wsdl"),
                Set.copyOf(collector.wsdlUrls())
        );
    }

    @Test
    void environmentVariableOverridesSameKeyFromConfigFile() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("bridge.auto.wsdl-1-url", "http://yaml/One?wsdl")
                .withProperty("bridge.auto.wsdl-2-url", "http://yaml/Two?wsdl");
        environment.getPropertySources().addFirst(systemEnvironment(Map.of(
                "BRIDGE_AUTO_WSDL_1_URL", "http://env/One?wsdl"
        )));

        WsdlUrlCollector collector = new WsdlUrlCollector(environment);

        assertEquals(List.of("http://env/One?wsdl", "http://yaml/Two?wsdl"), collector.wsdlUrls());
    }

    private static SystemEnvironmentPropertySource systemEnvironment(Map<String, Object> variables) {
        return new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables);
    }
}
