package io.github.connellite.service.auto;

import io.github.connellite.model.DiscoveredSoapService;
import io.github.connellite.model.WsdlOperationModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.exception.SoapDiscoveryException;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WsdlServiceDiscovery {

    private final WsdlParser wsdlParser;
    private final BridgeProperties bridgeProperties;

    /**
     * Every configured source must be available: an unreachable services page or WSDL, a services page that
     * lists no services, or a WSDL that cannot be parsed fails discovery at once (and with it the application start).
     * With {@code bridge.auto.fail-on-unavailable=false} such a source is logged and skipped instead.
     * Nothing configured at all is allowed: the bridge then starts without endpoints.
     */
    public List<DiscoveredSoapService> discover(String servicesUrl, List<String> wsdlUrls) {
        Map<String, DiscoveredSoapService> discoveredByWsdl = new LinkedHashMap<>();

        if (StringUtils.hasText(servicesUrl)) {
            discoverFromServicesPage(servicesUrl.trim(), discoveredByWsdl);
        }

        for (String wsdlUrl : nullSafeList(wsdlUrls)) {
            if (!StringUtils.hasText(wsdlUrl) || discoveredByWsdl.containsKey(wsdlUrl)) {
                continue;
            }
            try {
                DiscoveredSoapService service = discoverFromWsdl(wsdlUrl);
                discoveredByWsdl.put(wsdlUrl, service);
                log.debug("Discovered service {} from WSDL URL {}", service.name(), wsdlUrl);
            } catch (RuntimeException exception) {
                unavailable("wsdl-url " + wsdlUrl + ": " + reason(exception), exception);
            }
        }

        if (discoveredByWsdl.isEmpty()) {
            log.info("No SOAP services discovered (services-url and wsdl-*-url are empty or missing)");
            return List.of();
        }

        log.info("Discovered {} SOAP service(s)", discoveredByWsdl.size());
        return List.copyOf(discoveredByWsdl.values());
    }

    private void discoverFromServicesPage(String servicesUrl, Map<String, DiscoveredSoapService> discoveredByWsdl) {
        String baseUrl = normalizeBaseUrl(servicesUrl);
        log.info("Discovering SOAP services from {}", baseUrl);

        Map<String, String> services;
        try {
            services = listServices(baseUrl);
        } catch (IOException | RuntimeException exception) {
            unavailable("services-url " + baseUrl + ": " + reason(exception), exception);
            return;
        }
        if (services.isEmpty()) {
            unavailable("services-url " + baseUrl + ": no SOAP services listed (expected a CXF services page)", null);
            return;
        }

        for (Map.Entry<String, String> entry : services.entrySet()) {
            String wsdlUrl = entry.getValue();
            try {
                List<WsdlOperationModel> operations = wsdlParser.parseOperations(wsdlUrl);
                discoveredByWsdl.putIfAbsent(wsdlUrl, new DiscoveredSoapService(entry.getKey(), wsdlUrl, operations));
                log.debug("Discovered service {} with {} operation(s)", entry.getKey(), operations.size());
            } catch (RuntimeException exception) {
                unavailable("WSDL of service " + entry.getKey() + " listed at services-url: " + reason(exception), exception);
            }
        }
    }

    private void unavailable(String message, Throwable cause) {
        if (bridgeProperties.getAuto() == null || bridgeProperties.getAuto().isFailOnUnavailable()) {
            throw new SoapDiscoveryException(message, cause);
        }
        log.warn("Skipping unavailable SOAP source {}", message);
    }

    private Map<String, String> listServices(String baseUrl) throws IOException {
        Document document = Jsoup.connect(baseUrl)
                .timeout((int) pageTimeout().toMillis())
                .get();

        Map<String, String> services = new LinkedHashMap<>();
        Elements serviceNames = document.select("span.porttypename");
        for (Element serviceNameElement : serviceNames) {
            String serviceName = serviceNameElement.text().trim();
            if (serviceName.isEmpty()) {
                continue;
            }
            Element row = serviceNameElement.closest("tr");
            if (row == null) {
                continue;
            }
            Element wsdlLink = row.selectFirst("a[href*=wsdl]");
            if (wsdlLink == null) {
                continue;
            }
            services.putIfAbsent(serviceName, wsdlLink.attr("abs:href"));
        }
        return services;
    }

    /**
     * Jsoup has one timeout for connecting and for each read; use the longer of the configured ones.
     */
    private Duration pageTimeout() {
        BridgeProperties.AutoMapping auto = bridgeProperties.getAuto() != null
                ? bridgeProperties.getAuto()
                : new BridgeProperties.AutoMapping();
        return auto.getConnectTimeout().compareTo(auto.getReceiveTimeout()) >= 0
                ? auto.getConnectTimeout()
                : auto.getReceiveTimeout();
    }

    private static String reason(Throwable failure) {
        if (failure instanceof HttpStatusException httpStatus) {
            return "HTTP " + httpStatus.getStatusCode();
        }
        if (failure instanceof IllegalStateException && failure.getMessage() != null
                && failure.getMessage().startsWith("Failed to load WSDL ")) {
            return failure.getMessage().substring("Failed to load ".length());
        }
        Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message != null && !message.isBlank() ? " " + message : "");
    }

    public DiscoveredSoapService discoverFromWsdl(String wsdlUrl) {
        String serviceName = serviceNameFromWsdlUrl(wsdlUrl);
        List<WsdlOperationModel> operations = wsdlParser.parseOperations(wsdlUrl);
        log.info("Loaded {} operation(s) from WSDL {}", operations.size(), wsdlUrl);
        return new DiscoveredSoapService(serviceName, wsdlUrl, operations);
    }

    static String serviceNameFromWsdlUrl(String wsdlUrl) {
        try {
            URI uri = URI.create(wsdlUrl);
            String path = uri.getPath();
            if (path != null && !path.isBlank()) {
                String segment = path.substring(path.lastIndexOf('/') + 1);
                if (!segment.isBlank()) {
                    if (segment.endsWith(".wsdl")) {
                        segment = segment.substring(0, segment.length() - 5);
                    }
                    return segment;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to default
        }
        return "SoapService";
    }

    private String normalizeBaseUrl(String servicesUrl) {
        if (servicesUrl.endsWith("/")) {
            return servicesUrl;
        }
        return servicesUrl + "/";
    }

    private List<String> nullSafeList(List<String> values) {
        return values == null ? Collections.emptyList() : values;
    }
}
