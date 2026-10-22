package io.github.connellite.service.soap;

import io.github.connellite.config.BridgeProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.configuration.security.AuthorizationPolicy;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.jaxws.endpoint.dynamic.JaxWsDynamicClientFactory;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.apache.cxf.ws.security.wss4j.WSS4JOutInterceptor;
import org.apache.wss4j.common.ConfigurationConstants;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.springframework.stereotype.Component;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds dynamic CXF clients configured for the backend: timeouts, HTTP Basic auth and WS-Security UsernameToken.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SoapClientFactory {

    private final BridgeProperties bridgeProperties;

    public Client create(String wsdlUrl) {
        log.info("Creating dynamic SOAP client for WSDL: {}", wsdlUrl);
        Client client = JaxWsDynamicClientFactory.newInstance().createClient(wsdlUrl);
        configure(client);
        return client;
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
