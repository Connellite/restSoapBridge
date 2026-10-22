package io.github.connellite.config;

import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Collects {@code bridge.auto.wsdl[-suffix]-url} properties from every configuration source.
 * <p>
 * Names are compared in Spring Boot's uniform form, so {@code bridge.auto.wsdl-1-url} in YAML,
 * {@code BRIDGE_AUTO_WSDL_1_URL} in the environment and {@code -Dbridge.auto.wsdl-1-url} all denote
 * the same key; the source with the highest precedence wins.
 */
@Component
public class WsdlUrlCollector {

    private static final ConfigurationPropertyName AUTO_PREFIX = ConfigurationPropertyName.of("bridge.auto");
    // Uniform form drops dashes: wsdl-url -> wsdlurl, wsdl-auth-url -> wsdlauthurl.
    private static final Pattern WSDL_URL_KEY = Pattern.compile("wsdl[a-z0-9]*url");

    private final List<String> wsdlUrls;

    public WsdlUrlCollector(Environment environment) {
        // Property sources are iterated from highest to lowest precedence: keep the first value per key.
        Map<String, String> urlsByKey = new LinkedHashMap<>();
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            if (source instanceof IterableConfigurationPropertySource iterable) {
                iterable.stream().forEach(name -> collect(environment, source, name, urlsByKey));
            }
        }
        this.wsdlUrls = List.copyOf(new LinkedHashSet<>(urlsByKey.values()));
    }

    public List<String> wsdlUrls() {
        return wsdlUrls;
    }

    private static void collect(
            Environment environment,
            ConfigurationPropertySource source,
            ConfigurationPropertyName name,
            Map<String, String> urlsByKey
    ) {
        String key = wsdlUrlKey(name);
        if (key == null || urlsByKey.containsKey(key)) {
            return;
        }
        ConfigurationProperty property = source.getConfigurationProperty(name);
        if (property == null || property.getValue() == null) {
            return;
        }
        String value = environment.resolvePlaceholders(String.valueOf(property.getValue()));
        if (StringUtils.hasText(value)) {
            urlsByKey.put(key, value.trim());
        }
    }

    /**
     * Returns the uniform key below {@code bridge.auto} (e.g. {@code wsdl1url}) or {@code null}
     * when the property is not a WSDL URL. Environment variables split {@code WSDL_1_URL} into
     * several elements ({@code wsdl[1].url}), YAML keeps {@code wsdl-1-url} as one.
     */
    static String wsdlUrlKey(ConfigurationPropertyName name) {
        if (!AUTO_PREFIX.isAncestorOf(name)) {
            return null;
        }
        StringBuilder key = new StringBuilder();
        for (int index = AUTO_PREFIX.getNumberOfElements(); index < name.getNumberOfElements(); index++) {
            key.append(name.getElement(index, ConfigurationPropertyName.Form.UNIFORM));
        }
        String uniform = key.toString().replace("[", "").replace("]", "");
        return WSDL_URL_KEY.matcher(uniform).matches() ? uniform : null;
    }
}
