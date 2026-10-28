package io.github.connellite.service.auto;

import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.WsdlUrlCollector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HTTP Basic credentials for the services page, WSDL and XSD downloads ({@code bridge.backend.wsdl-basic-auth},
 * falling back to {@code basic-auth}).
 * <p>
 * Jsoup, wsdl4j and the CXF dynamic client all load documents through {@code HttpURLConnection}, and CXF offers
 * no hook for its own downloads, so the credentials are served by the JVM-wide {@link Authenticator}: it answers
 * a server's {@code 401} challenge only for the scheme, host and port of {@code services-url} and the
 * {@code wsdl-*-url}s, and delegates every other request to the authenticator that was installed before.
 * That one is restored on shutdown.
 */
@Slf4j
@Component
public class WsdlAuthenticator implements DisposableBean {

    private final Authenticator previous;
    private final ScopedAuthenticator installed;

    public WsdlAuthenticator(BridgeProperties bridgeProperties, WsdlUrlCollector wsdlUrlCollector) {
        BridgeProperties.BasicAuth credentials = bridgeProperties.getBackend().effectiveWsdlBasicAuth();
        List<String> sources = new ArrayList<>(wsdlUrlCollector.wsdlUrls());
        if (bridgeProperties.getAuto() != null && StringUtils.hasText(bridgeProperties.getAuto().getServicesUrl())) {
            sources.add(bridgeProperties.getAuto().getServicesUrl().trim());
        }
        Set<String> origins = origins(sources);
        if (!credentials.isConfigured() || origins.isEmpty()) {
            this.previous = null;
            this.installed = null;
            return;
        }
        this.previous = Authenticator.getDefault();
        this.installed = new ScopedAuthenticator(credentials, origins, previous);
        Authenticator.setDefault(installed);
        log.info("Basic auth for WSDL downloads from {}", origins);
    }

    @Override
    public void destroy() {
        if (installed != null && Authenticator.getDefault() == installed) {
            Authenticator.setDefault(previous);
        }
    }

    static Set<String> origins(Collection<String> urls) {
        Set<String> origins = new LinkedHashSet<>();
        for (String url : urls) {
            String origin = origin(url);
            if (origin != null) {
                origins.add(origin);
            }
        }
        return origins;
    }

    /** {@code scheme://host:port} with the default port made explicit; {@code null} for non-HTTP URLs. */
    static String origin(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : null;
            if (uri.getHost() == null || !("http".equals(scheme) || "https".equals(scheme))) {
                return null;
            }
            int port = uri.getPort() >= 0 ? uri.getPort() : ("https".equals(scheme) ? 443 : 80);
            return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    static final class ScopedAuthenticator extends Authenticator {

        private final BridgeProperties.BasicAuth credentials;
        private final Set<String> origins;
        private final Authenticator delegate;

        ScopedAuthenticator(BridgeProperties.BasicAuth credentials, Set<String> origins, Authenticator delegate) {
            this.credentials = credentials;
            this.origins = Set.copyOf(origins);
            this.delegate = delegate;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            URL url = getRequestingURL();
            if (getRequestorType() == RequestorType.SERVER && url != null && origins.contains(origin(url.toString()))) {
                String password = credentials.getPassword() != null ? credentials.getPassword() : "";
                return new PasswordAuthentication(credentials.getUsername(), password.toCharArray());
            }
            if (delegate == null) {
                return null;
            }
            return delegate.requestPasswordAuthenticationInstance(getRequestingHost(), getRequestingSite(),
                    getRequestingPort(), getRequestingProtocol(), getRequestingPrompt(), getRequestingScheme(),
                    url, getRequestorType());
        }
    }
}
