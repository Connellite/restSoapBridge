package io.github.connellite.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "bridge")
public class BridgeProperties {

    private AutoMapping auto;

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
