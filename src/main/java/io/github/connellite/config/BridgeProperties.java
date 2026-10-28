package io.github.connellite.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@ConfigurationProperties(prefix = "bridge")
public class BridgeProperties {

    private AutoMapping auto;
    private Validation validation = new Validation();
    private ClientPool clientPool = new ClientPool();
    private Backend backend = new Backend();
    private Correlation correlation = new Correlation();
    private Errors errors = new Errors();

    @Getter
    @Setter
    public static class AutoMapping {
        private String servicesUrl;
        private String pathPrefix;
        private String httpMethod;
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration receiveTimeout = Duration.ofSeconds(60);
    }

    /**
     * HTTP status of SOAP faults, by fault name: the declared fault class without {@code _Exception}
     * ({@code AccessDeniedException}) or {@code SoapFault} for undeclared ones. Unlisted faults give 502.
     */
    @Getter
    @Setter
    public static class Errors {
        private Map<String, Integer> faultStatus = new LinkedHashMap<>(Map.of("AccessDeniedException", 401));
    }

    /**
     * Validation of REST request bodies against the schema generated from XSD.
     */
    @Getter
    @Setter
    public static class Validation {
        private boolean enabled = true;
        /**
         * Reject top-level request fields the operation does not have (e.g. {@code uuid} instead of {@code folderUuid}):
         * otherwise they are silently ignored and the SOAP parameter stays null.
         */
        private boolean rejectUnknownFields = true;
    }

    /**
     * Cache of dynamic CXF clients, one per WSDL URL.
     */
    @Getter
    @Setter
    public static class ClientPool {
        private long maximumSize = 100;
        /** Evict a client that was not used for this long; {@code null} disables idle eviction. */
        private Duration expireAfterAccess = Duration.ofHours(1);
        /** Rebuild a client (re-read WSDL) in the background this long after creation; {@code null} disables. */
        private Duration refreshAfterWrite;
    }

    /**
     * How the bridge authenticates to the SOAP backend and which REST headers it passes on.
     */
    @Getter
    @Setter
    public static class Backend {
        private BasicAuth basicAuth = new BasicAuth();
        private WsSecurity wsSecurity = new WsSecurity();
        /** REST request headers copied to the SOAP HTTP request, e.g. {@code Authorization}, {@code Accept-Language}. */
        private List<String> forwardHeaders = new ArrayList<>();
    }

    @Getter
    @Setter
    public static class BasicAuth {
        private String username;
        private String password;

        public boolean isConfigured() {
            return username != null && !username.isBlank();
        }
    }

    /**
     * WS-Security UsernameToken added to every SOAP request.
     */
    @Getter
    @Setter
    public static class WsSecurity {
        private String username;
        private String password;
        private PasswordType passwordType = PasswordType.TEXT;
        private boolean addNonce = true;
        private boolean addCreated = true;

        public boolean isConfigured() {
            return username != null && !username.isBlank();
        }

        public enum PasswordType {
            TEXT("PasswordText"),
            DIGEST("PasswordDigest"),
            NONE("PasswordNone");

            private final String wss4jValue;

            PasswordType(String wss4jValue) {
                this.wss4jValue = wss4jValue;
            }

            public String wss4jValue() {
                return wss4jValue;
            }
        }
    }

    /**
     * Correlation id: read from or generated for each REST request, logged via MDC and sent to the backend.
     */
    @Getter
    @Setter
    public static class Correlation {
        private String headerName = "X-Correlation-Id";
        /** Send the id to the SOAP backend as an HTTP header with the same name. */
        private boolean forwardToBackend = true;
        private SoapHeader soapHeader = new SoapHeader();
    }

    /**
     * Optional SOAP envelope header {@code <ns:localName>id</ns:localName>} carrying the correlation id.
     */
    @Getter
    @Setter
    public static class SoapHeader {
        private boolean enabled;
        private String namespace = "urn:rest-soap-bridge";
        private String localName = "CorrelationId";
    }

    /**
     * Normalizes a REST path prefix to {@code /segment[/segment...]} without a trailing slash;
     * a root prefix ({@code /} or blank) becomes an empty string.
     */
    public static String normalizePathPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
