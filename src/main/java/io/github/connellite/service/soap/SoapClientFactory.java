package io.github.connellite.service.soap;

import io.github.connellite.config.BridgeProperties;
import io.github.connellite.model.WsdlEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.jaxws.DispatchImpl;
import org.apache.cxf.service.model.EndpointInfo;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor;
import org.apache.wss4j.common.ConfigurationConstants;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

#if SPRING_BOOT_3
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Dispatch;
import jakarta.xml.ws.Service;
#else
import javax.xml.ws.BindingProvider;
import javax.xml.ws.Dispatch;
import javax.xml.ws.Service;
#endif
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.xml.namespace.QName;
import javax.xml.transform.Source;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds the CXF {@code Dispatch} of a WSDL port, configured for the backend: endpoint address, timeouts, HTTP Basic
 * auth and WS-Security UsernameToken. Nothing is generated or compiled: requests and responses stay XML.
 */
@Slf4j
@Component
@DependsOn("wsdlAuthenticator")
@RequiredArgsConstructor
public class SoapClientFactory {

    private final BridgeProperties bridgeProperties;

    /**
     * @param endpoint        WSDL service and port the operations were read from
     * @param endpointAddress SOAP endpoint address of this service ({@code bridge.backend.endpoint-addresses});
     *                        {@code null}: the WSDL {@code soap:address}, rebased on {@code endpoint-base-url}
     */
    public SoapPort create(String wsdlUrl, WsdlEndpoint endpoint, String endpointAddress) {
        if (endpoint == null) {
            throw new IllegalStateException("WSDL " + wsdlUrl + " declares no SOAP port to call");
        }
        log.info("Creating SOAP dispatch for WSDL {} port {}", wsdlUrl, endpoint.port());
        Service service = Service.create(url(wsdlUrl), QName.valueOf(endpoint.service()));
        Dispatch<Source> dispatch = service.createDispatch(portName(endpoint), Source.class, Service.Mode.PAYLOAD);
        Client client = ((DispatchImpl<?>) dispatch).getClient();
        // Before configure(): the conduit takes its target address from the endpoint when it is created.
        String address = overrideAddress(client, endpointAddress);
        // JAX-WS puts the WSDL address into the request context, where it wins over the endpoint's.
        dispatch.getRequestContext().put(BindingProvider.ENDPOINT_ADDRESS_PROPERTY, address);
        // Calls share the dispatch: each thread gets its own copy of the request context above.
        client.setThreadLocalRequestContext(true);
        configure(client);
        return new SoapPort(dispatch, client);
    }

    private static URL url(String wsdlUrl) {
        try {
            return new URL(wsdlUrl);
        } catch (MalformedURLException exception) {
            throw new IllegalArgumentException("Not a WSDL URL: " + wsdlUrl, exception);
        }
    }

    /** CXF names an endpoint by the port name in the namespace of its service. */
    private static QName portName(WsdlEndpoint endpoint) {
        return new QName(QName.valueOf(endpoint.service()).getNamespaceURI(), endpoint.port());
    }

    /** The address calls go to: configured, rebased, or the WSDL one. */
    private String overrideAddress(Client client, String endpointAddress) {
        EndpointInfo endpoint = client.getEndpoint().getEndpointInfo();
        String wsdlAddress = endpoint.getAddress();
        String address = endpointAddress != null
                ? endpointAddress
                : rebase(wsdlAddress, bridgeProperties.getBackend().getEndpointBaseUrl());
        if (address != null && !address.equals(wsdlAddress)) {
            endpoint.setAddress(address);
            log.info("SOAP endpoint {} instead of WSDL address {}", address, wsdlAddress);
            return address;
        }
        return wsdlAddress;
    }

    /** Scheme, host and port of {@code baseUrl} with the path and query of {@code address}. */
    static String rebase(String address, String baseUrl) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(address)) {
            return address;
        }
        URI original = URI.create(address.trim());
        URI base = URI.create(baseUrl.trim());
        StringBuilder rebased = new StringBuilder(base.getScheme()).append("://").append(base.getRawAuthority());
        String basePath = base.getRawPath() != null ? base.getRawPath() : "";
        while (basePath.endsWith("/")) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        rebased.append(basePath);
        if (original.getRawPath() != null) {
            rebased.append(original.getRawPath());
        }
        if (original.getRawQuery() != null) {
            rebased.append('?').append(original.getRawQuery());
        }
        return rebased.toString();
    }

    void configure(Client client) {
        if (client.getConduit() instanceof HTTPConduit conduit) {
            configureTimeouts(conduit);
            configureBasicAuth(conduit);
        }
        configureWsSecurity(client);
    }

    private void configureTimeouts(HTTPConduit conduit) {
        BridgeProperties.AutoMapping auto = bridgeProperties.getAuto();
        if (auto == null) {
            return;
        }
        HTTPClientPolicy policy = new HTTPClientPolicy();
        policy.setConnectionTimeout(auto.getConnectTimeout().toMillis());
        policy.setReceiveTimeout(auto.getReceiveTimeout().toMillis());
        conduit.setClient(policy);
    }

    private void configureBasicAuth(HTTPConduit conduit) {
        BridgeProperties.BasicAuth basicAuth = bridgeProperties.getBackend().getBasicAuth();
        if (!basicAuth.isConfigured()) {
            return;
        }
        AuthorizationPolicy authorization = new AuthorizationPolicy();
        authorization.setAuthorizationType("Basic");
        authorization.setUserName(basicAuth.getUsername());
        authorization.setPassword(basicAuth.getPassword());
        conduit.setAuthorization(authorization);
    }

    private void configureWsSecurity(Client client) {
        BridgeProperties.WsSecurity wsSecurity = bridgeProperties.getBackend().getWsSecurity();
        if (!wsSecurity.isConfigured()) {
            return;
        }
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConfigurationConstants.ACTION, ConfigurationConstants.USERNAME_TOKEN);
        properties.put(ConfigurationConstants.USER, wsSecurity.getUsername());
        properties.put(ConfigurationConstants.PASSWORD_TYPE, wsSecurity.getPasswordType().wss4jValue());
        properties.put(ConfigurationConstants.ADD_USERNAMETOKEN_NONCE, String.valueOf(wsSecurity.isAddNonce()));
        properties.put(ConfigurationConstants.ADD_USERNAMETOKEN_CREATED, String.valueOf(wsSecurity.isAddCreated()));
        properties.put(ConfigurationConstants.PW_CALLBACK_REF, passwordCallback(wsSecurity.getPassword()));
        client.getOutInterceptors().add(new WSS4JOutInterceptor(properties));
    }

    private static CallbackHandler passwordCallback(String password) {
        return callbacks -> {
            for (Callback callback : callbacks) {
                if (callback instanceof WSPasswordCallback passwordCallback) {
                    passwordCallback.setPassword(password);
                }
            }
        };
    }
}
