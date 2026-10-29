package io.github.connellite.service.soap;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalCause;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.model.WsdlEndpoint;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Client;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Dynamic CXF clients, one per WSDL URL, WSDL port and endpoint address. Building a client downloads the WSDL and compiles JAXB classes,
 * so clients are cached; idle ones are evicted and, optionally, rebuilt in the background to pick up WSDL changes.
 */
@Slf4j
@Component
public class SoapClientPool implements DisposableBean {

    private final LoadingCache<ClientKey, Client> clients;

    public SoapClientPool(BridgeProperties bridgeProperties, SoapClientFactory clientFactory, ObjectProvider<MeterRegistry> meterRegistry) {
        BridgeProperties.ClientPool pool = bridgeProperties.getClientPool();
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(pool.getMaximumSize())
                .recordStats();
        if (pool.getExpireAfterAccess() != null) {
            builder.expireAfterAccess(pool.getExpireAfterAccess());
        }
        if (pool.getRefreshAfterWrite() != null) {
            builder.refreshAfterWrite(pool.getRefreshAfterWrite());
        }
        this.clients = builder
                .removalListener(SoapClientPool::onRemoval)
                .build(key -> clientFactory.create(key.wsdlUrl(), key.endpoint(), key.endpointAddress()));
        meterRegistry.ifAvailable(registry -> CaffeineCacheMetrics.monitor(registry, clients, "soap.clients"));
    }

    public Client getClient(String wsdlUrl) {
        return getClient(wsdlUrl, null);
    }

    /**
     * @param endpointAddress configured address of the service, {@code null} for the (rebased) WSDL address
     */
    public Client getClient(String wsdlUrl, String endpointAddress) {
        return getClient(wsdlUrl, null, endpointAddress);
    }

    /**
     * @param endpoint WSDL service and port to call, {@code null} for the one CXF picks
     */
    public Client getClient(String wsdlUrl, WsdlEndpoint endpoint, String endpointAddress) {
        return clients.get(new ClientKey(wsdlUrl, endpoint, endpointAddress));
    }

    @Override
    public void destroy() {
        clients.invalidateAll();
        clients.cleanUp();
    }

    /**
     * A client replaced by a background refresh may still serve in-flight calls, so it is left to GC;
     * evicted or invalidated clients are destroyed to release their conduits.
     */
    private static void onRemoval(ClientKey key, Client client, RemovalCause cause) {
        if (client == null || key == null) {
            return;
        }
        log.info("SOAP client for WSDL {} removed from pool ({})", key.wsdlUrl(), cause);
        if (cause != RemovalCause.REPLACED) {
            client.destroy();
        }
    }

    private record ClientKey(String wsdlUrl, WsdlEndpoint endpoint, String endpointAddress) {
    }
}
