package io.github.connellite.model;

import lombok.Getter;
import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class MappingRegistry {

    @Getter
    private final List<MappingDefinition> mappings;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final Map<RouteKey, MappingDefinition> exactRoutes = new HashMap<>();
    private final Map<HttpMethod, List<MappingDefinition>> mappingsByMethod = new LinkedHashMap<>();

    public MappingRegistry(List<MappingDefinition> mappings) {
        this.mappings = List.copyOf(mappings);
        indexMappings(this.mappings);
    }

    public Optional<MappingDefinition> find(String httpMethod, String requestPath) {
        HttpMethod method = resolveMethod(httpMethod);
        if (method == null) {
            return Optional.empty();
        }
        String normalizedPath = normalizePath(requestPath);

        MappingDefinition exact = exactRoutes.get(new RouteKey(method, normalizedPath));
        if (exact != null) {
            return Optional.of(exact);
        }

        return mappingsByMethod.getOrDefault(method, List.of()).stream()
                .filter(mapping -> pathMatcher.match(mapping.getPath(), normalizedPath))
                .findFirst();
    }

    /** Values of the {@code {name}} segments of the mapping path, e.g. {@code uuid} of {@code /api/docs/{uuid}}. */
    public Map<String, String> pathVariables(MappingDefinition mapping, String requestPath) {
        String pattern = normalizePath(mapping.getPath());
        return pathMatcher.isPattern(pattern)
                ? pathMatcher.extractUriTemplateVariables(pattern, normalizePath(requestPath))
                : Map.of();
    }

    private static String describe(MappingDefinition mapping) {
        return mapping.getSoap() != null
                ? mapping.getSoap().getService() + "." + mapping.getSoap().getOperation()
                : mapping.getPath();
    }

    private HttpMethod resolveMethod(String httpMethod) {
        if (httpMethod == null || httpMethod.isBlank()) {
            return null;
        }
        try {
            return HttpMethod.valueOf(httpMethod.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void indexMappings(List<MappingDefinition> source) {
        for (MappingDefinition mapping : source) {
            HttpMethod method = HttpMethod.valueOf(mapping.getMethod().toUpperCase(Locale.ROOT));
            mappingsByMethod.computeIfAbsent(method, ignored -> new java.util.ArrayList<>()).add(mapping);

            String path = normalizePath(mapping.getPath());
            MappingDefinition duplicate = mappingsByMethod.get(method).stream()
                    .filter(other -> other != mapping && normalizePath(other.getPath()).equals(path))
                    .findFirst()
                    .orElse(null);
            if (duplicate != null) {
                throw new IllegalStateException("Duplicate endpoint " + method + " " + path + ": "
                        + describe(duplicate) + " and " + describe(mapping) + " (see bridge.operations)");
            }
            if (!pathMatcher.isPattern(path)) {
                exactRoutes.put(new RouteKey(method, path), mapping);
            }
        }

        mappingsByMethod.replaceAll((method, methodMappings) -> List.copyOf(methodMappings));
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    private record RouteKey(HttpMethod method, String path) {
    }
}
