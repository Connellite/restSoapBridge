package io.github.connellite.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private SoapHeaders soapHeaders = new SoapHeaders();
    /**
     * Per-operation overrides of the generated endpoint, by service and operation name (matched ignoring case):
     * {@code bridge.operations.Auth.login.path=/session}.
     */
    private Map<String, Map<String, OperationOverride>> operations = new LinkedHashMap<>();

    /** The override of {@code service.operation}, or an empty one. */
    public OperationOverride operation(String service, String operation) {
        return findIgnoringCase(operations, service)
                .flatMap(byOperation -> findIgnoringCase(byOperation, operation))
                .orElseGet(OperationOverride::new);
    }

    private static <V> Optional<V> findIgnoringCase(Map<String, V> values, String key) {
        if (values == null || key == null) {
            return Optional.empty();
        }
        V exact = values.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        return values.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(key))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    @Getter
    @Setter
    public static class OperationOverride {
        /** Replaces {@code /{Service}/{operation}} below the path prefix; may hold variables: {@code /docs/{uuid}}. */
        private String path;
        /** HTTP method of this endpoint instead of {@code bridge.auto.http-method}. */
        private String method;
        /** {@code false}: no endpoint for this operation. */
        private boolean enabled = true;
    }

    /**
     * SOAP headers declared in the WSDL binding ({@code soap:header}) are sent from this request JSON object,
     * one property per header part: {@code {"soapHeaders": {"auth": {"token": "..."}}}}.
     */
    @Getter
    @Setter
    public static class SoapHeaders {
        private String jsonField = "soapHeaders";
    }

    @Getter
    @Setter
    public static class AutoMapping {
        private String servicesUrl;
        private String pathPrefix;
        private String httpMethod;
        private Duration connectTimeout = Duration.ofSeconds(30);
        private Duration receiveTimeout = Duration.ofSeconds(60);
        /**
         * Do not start when a configured services page or WSDL is unavailable; {@code false} logs a warning
         * and skips that source.
         */
        private boolean failOnUnavailable = true;
        /** Spelling of the service and operation segments of generated paths. */
        private PathStyle pathStyle = PathStyle.AS_IS;
    }

    public enum PathStyle {
        /** As in the WSDL: {@code /api/UserService/getUserInfo}. */
        AS_IS,
        /** Lower case, words joined by dashes: {@code /api/user-service/get-user-info}. */
        KEBAB_CASE
    }

    /**
     * HTTP status of SOAP faults, by fault name: the declared fault class without {@code _Exception}
     * ({@code AccessDeniedException}) or {@code SoapFault} for undeclared ones. Unlisted faults give 502.
     */
    @Getter
    @Setter
    public static class Errors {
        private Map<String, Integer> faultStatus = new LinkedHashMap<>(Map.of("AccessDeniedException", 401));
        /**
         * Show backend failure details to REST clients: SOAP fault string, code and subcodes, and the transport
         * error of 502/503/504 (backend addresses). {@code false}: a generic text; the details are logged.
         * Declared faults ({@code faultName}, {@code faultInfo}) are shown either way.
         */
        private boolean exposeBackendDetails = true;
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
        /** Validation of SOAP responses (and faults) against the XSD of the WSDL. */
        private ResponseValidation response = ResponseValidation.OFF;
    }

    public enum ResponseValidation {
        /** Responses are not validated. */
        OFF,
        /** Violations are logged as warnings, the response is used anyway. */
        LOG,
        /** A response that violates the schema is a contract mismatch: 502. */
        STRICT
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
        /**
         * Credentials for the services page, WSDL and XSD downloads; {@code basic-auth} when not set.
         * Sent only to the scheme/host/port of {@code services-url} and the {@code wsdl-*-url}s.
         */
        private BasicAuth wsdlBasicAuth = new BasicAuth();
        private WsSecurity wsSecurity = new WsSecurity();
        /** REST request headers copied to the SOAP HTTP request, e.g. {@code Authorization}, {@code Accept-Language}. */
        private List<String> forwardHeaders = new ArrayList<>();
        /**
         * Replaces scheme, host and port of the WSDL {@code soap:address} (the path is kept), e.g. when the WSDL
         * advertises {@code localhost} or an internal host name.
         */
        private String endpointBaseUrl;
        /** Full SOAP endpoint address by service name (as in the REST path); wins over {@code endpoint-base-url}. */
        private Map<String, String> endpointAddresses = new LinkedHashMap<>();
        /**
         * WSDL port to expose and call, by service name (as in the REST path), e.g. {@code OrderServiceSoap12};
         * by default the first SOAP 1.1 port, or SOAP 1.2 port when there is none.
         */
        private Map<String, String> ports = new LinkedHashMap<>();

        public BasicAuth effectiveWsdlBasicAuth() {
            return wsdlBasicAuth.isConfigured() ? wsdlBasicAuth : basicAuth;
        }

        public String endpointAddress(String service) {
            return findIgnoringCase(endpointAddresses, service).filter(address -> !address.isBlank()).orElse(null);
        }

        public String port(String service) {
            return findIgnoringCase(ports, service).filter(port -> !port.isBlank()).map(String::trim).orElse(null);
        }
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
