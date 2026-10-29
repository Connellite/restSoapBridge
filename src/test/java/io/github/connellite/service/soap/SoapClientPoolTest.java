package io.github.connellite.service.soap;

import io.github.connellite.config.BridgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        SoapPort client = mock(SoapPort.class);
        when(factory.create(WSDL, null, null)).thenReturn(client);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));

        assertSame(client, pool.getPort(WSDL, null, null));
        assertSame(client, pool.getPort(WSDL, null, null));
        verify(factory, times(1)).create(WSDL, null, null);
    }

    @Test
    void failedCreationIsNotCached() {
        SoapPort client = mock(SoapPort.class);
        when(factory.create(WSDL, null, null))
                .thenThrow(new IllegalStateException("backend down"))
                .thenReturn(client);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));

        try {
            pool.getPort(WSDL, null, null);
        } catch (IllegalStateException expected) {
            // first attempt fails
        }

        assertSame(client, pool.getPort(WSDL, null, null));
    }

    @Test
    void destroysClientsOnShutdown() {
        SoapPort client = mock(SoapPort.class);
        when(factory.create(WSDL, null, null)).thenReturn(client);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));
        pool.getPort(WSDL, null, null);

        pool.destroy();

        verify(client, timeout(2000)).destroy();
    }

    @Test
    void evictsClientsBeyondMaximumSize() {
        properties.getClientPool().setMaximumSize(1);
        SoapPort first = mock(SoapPort.class);
        SoapPort second = mock(SoapPort.class);
        when(factory.create("first", null, null)).thenReturn(first);
        when(factory.create("second", null, null)).thenReturn(second);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));

        pool.getPort("first", null, null);
        pool.getPort("second", null, null);
        pool.getPort("second", null, null);

        verify(first, timeout(2000)).destroy();
    }

    @Test
    void refreshRebuildsClientWithoutDestroyingTheOneInUse() throws Exception {
        properties.getClientPool().setRefreshAfterWrite(Duration.ofMillis(50));
        SoapPort original = mock(SoapPort.class);
        SoapPort refreshed = mock(SoapPort.class);
        when(factory.create(WSDL, null, null)).thenReturn(original, refreshed);
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(null));

        assertSame(original, pool.getPort(WSDL, null, null));
        Thread.sleep(100);
        pool.getPort(WSDL, null, null); // triggers the asynchronous refresh

        verify(factory, timeout(2000).times(2)).create(WSDL, null, null);
        long deadline = System.currentTimeMillis() + 2000;
        while (pool.getPort(WSDL, null, null) == original && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNotSame(original, pool.getPort(WSDL, null, null));
        verify(original, times(0)).destroy();
    }

    @Test
    void publishesCacheMetrics() {
        when(factory.create(WSDL, null, null)).thenReturn(mock(SoapPort.class));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SoapClientPool pool = new SoapClientPool(properties, factory, meterRegistry(registry));

        pool.getPort(WSDL, null, null);

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
