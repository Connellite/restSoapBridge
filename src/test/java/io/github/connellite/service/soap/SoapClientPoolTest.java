package io.github.connellite.service.soap;

import io.github.connellite.config.BridgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.cxf.endpoint.Client;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoapClientPoolTest {

    private static final String WSDL = "http://backend/services/Auth?wsdl";

    private final BridgeProperties properties = new BridgeProperties();
    private final SoapClientFactory factory = mock(SoapClientFactory.class);

    @Test
    void createsOneClientPerWsdlUrl() {
        Client client = mock(Client.class);
        when(factory.create(WSDL, null)).thenReturn(client);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));

        assertSame(client, pool.getClient(WSDL));
        assertSame(client, pool.getClient(WSDL));
        verify(factory, times(1)).create(WSDL, null);
    }

    @Test
    void failedCreationIsNotCached() {
        Client client = mock(Client.class);
        when(factory.create(WSDL, null))
                .thenThrow(new IllegalStateException("backend down"))
                .thenReturn(client);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));

        try {
            pool.getClient(WSDL);
        } catch (IllegalStateException expected) {
            // first attempt fails
        }

        assertSame(client, pool.getClient(WSDL));
    }

    @Test
    void destroysClientsOnShutdown() {
        Client client = mock(Client.class);
        when(factory.create(WSDL, null)).thenReturn(client);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));
        pool.getClient(WSDL);

        pool.destroy();

        verify(client, timeout(2000)).destroy();
    }

    @Test
    void evictsClientsBeyondMaximumSize() {
        properties.getClientPool().setMaximumSize(1);
        Client first = mock(Client.class);
        Client second = mock(Client.class);
        when(factory.create("first", null)).thenReturn(first);
        when(factory.create("second", null)).thenReturn(second);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));

        pool.getClient("first");
        pool.getClient("second");
        pool.getClient("second");

        verify(first, timeout(2000)).destroy();
    }

    @Test
    void refreshRebuildsClientWithoutDestroyingTheOneInUse() throws Exception {
        properties.getClientPool().setRefreshAfterWrite(Duration.ofMillis(50));
        Client original = mock(Client.class);
        Client refreshed = mock(Client.class);
        when(factory.create(WSDL, null)).thenReturn(original, refreshed);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));

        assertSame(original, pool.getClient(WSDL));
        Thread.sleep(100);
        pool.getClient(WSDL); // triggers the asynchronous refresh

        verify(factory, timeout(2000).times(2)).create(WSDL, null);
        long deadline = System.currentTimeMillis() + 2000;
        while (pool.getClient(WSDL) == original && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNotSame(original, pool.getClient(WSDL));
        verify(original, times(0)).destroy();
    }

    @Test
    void publishesCacheMetrics() {
        when(factory.create(WSDL, null)).thenReturn(mock(Client.class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(registry));

        pool.getClient(WSDL);

        assertNotNull(registry.find("cache.gets").tag("cache", "soap.clients").meter());
    }

    private static ObjectProvider<MeterRegistry> meterRegistry(MeterRegistry registry) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (registry != null) {
            beanFactory.addBean("meterRegistry", registry);
        }
        return beanFactory.getBeanProvider(MeterRegistry.class);
    }
}
