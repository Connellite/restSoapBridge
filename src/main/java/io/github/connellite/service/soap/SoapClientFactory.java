package io.github.connellite.service.soap;

import io.github.connellite.config.BridgeProperties;
import io.github.connellite.model.WsdlEndpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.jaxb.JAXBDataBinding;
import org.apache.cxf.message.Message;
import org.apache.cxf.jaxws.endpoint.dynamic.JaxWsDynamicClientFactory;
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
import jakarta.xml.bind.ValidationEventHandler;
#else
import javax.xml.bind.ValidationEventHandler;
#endif
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.xml.namespace.QName;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds dynamic CXF clients configured for the backend: endpoint address, timeouts, HTTP Basic auth and
 * WS-Security UsernameToken.
 */
@Slf4j
@Component
@DependsOn("wsdlAuthenticator")
@RequiredArgsConstructor
public class SoapClientFactory {

    private final BridgeProperties bridgeProperties;

    public Client create(String wsdlUrl) {
        return create(wsdlUrl, null);
    }

    /**
     * @param endpointAddress SOAP endpoint address of this service ({@code bridge.backend.endpoint-addresses});
     *                        {@code null}: the WSDL {@code soap:address}, rebased on {@code endpoint-base-url}
     */
    public Client create(String wsdlUrl, String endpointAddress) {
        return create(wsdlUrl, null, endpointAddress);
    }

    /**
     * @param endpoint WSDL service and port the operations were read from; {@code null}: the one CXF picks
     */
    public Client create(String wsdlUrl, WsdlEndpoint endpoint, String endpointAddress) {
        log.info("Creating dynamic SOAP client for WSDL: {}{}", wsdlUrl, endpoint != null ? " port " + endpoint.port() : "");
        Client client = endpoint == null
                ? JaxWsDynamicClientFactory.newInstance().createClient(wsdlUrl)
                : JaxWsDynamicClientFactory.newInstance().createClient(wsdlUrl, QName.valueOf(endpoint.service()),
                portName(endpoint));
        // Before configure(): the conduit takes its target address from the endpoint when it is created.
        overrideAddress(client, endpointAddress);
        configure(client);
        return client;
    }

    /** CXF names an endpoint by the port name in the namespace of its service. */
    private static QName portName(WsdlEndpoint endpoint) {
        return new QName(QName.valueOf(endpoint.service()).getNamespaceURI(), endpoint.port());
    }

    private void overrideAddress(Client client, String endpointAddress) {
        EndpointInfo endpoint = client.getEndpoint().getEndpointInfo();
        String wsdlAddress = endpoint.getAddress();
        String address = endpointAddress != null
                ? endpointAddress
                : rebase(wsdlAddress, bridgeProperties.getBackend().getEndpointBaseUrl());
        if (address != null && !address.equals(wsdlAddress)) {
            endpoint.setAddress(address);
            log.info("SOAP endpoint {} instead of WSDL address {}", address, wsdlAddress);
        }
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
        configureResponseValidation(client);
    }

    /**
     * {@code bridge.validation.response}: CXF validates what the client reads (response and fault details) against
     * the WSDL schemas while unmarshalling. In {@code log} mode a handler reports each violation and lets it pass.
     */
    private void configureResponseValidation(Client client) {
        BridgeProperties.ResponseValidation mode = bridgeProperties.getValidation().getResponse();
        if (mode == null || mode == BridgeProperties.ResponseValidation.OFF) {
            return;
        }
        client.getEndpoint().put(Message.SCHEMA_VALIDATION_ENABLED, "IN");
        if (mode == BridgeProperties.ResponseValidation.LOG) {
            String endpoint = client.getEndpoint().getEndpointInfo().getName().getLocalPart();
            ValidationEventHandler logOnly = event -> {
                log.warn("SOAP response of {} does not match the WSDL schema: {}", endpoint, event.getMessage());
                return true;
            };
            client.getEndpoint().put(JAXBDataBinding.READER_VALIDATION_EVENT_HANDLER, logOnly);
        }
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
