package io.github.connellite.mapper.path;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@UtilityClass
public class SoapArgumentBuilder {

    public static List<SoapArgument> build(Map<String, String> requestMappings, JsonNode body) {
        if (requestMappings.isEmpty()) {
            return List.of();
        }

        Map<String, Object> soapRoot = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requestMappings.entrySet()) {
            setNestedValue(soapRoot, entry.getValue(), JsonPathReader.read(body, entry.getKey()));
        }

        if (soapRoot.isEmpty()) {
            return List.of();
        }

        if (soapRoot.size() == 1) {
            Map.Entry<String, Object> only = soapRoot.entrySet().iterator().next();
            if (only.getValue() instanceof Map<?, ?> nested) {
                return toOrderedArguments(nested);
            }
        }

        return toOrderedArguments(soapRoot);
    }

    /**
     * One argument per top-level SOAP path segment, without unwrapping a single root: used for SOAP header parts,
     * where {@code $.auth.token} must stay the {@code auth} part even when it is the only one.
     */
    public static List<SoapArgument> buildParts(Map<String, String> requestMappings, JsonNode body) {
        Map<String, Object> soapRoot = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : requestMappings.entrySet()) {
            Object value = JsonPathReader.read(body, entry.getKey());
            if (value != null) {
                setNestedValue(soapRoot, entry.getValue(), value);
            }
        }
        return toOrderedArguments(soapRoot);
    }

    @SuppressWarnings("unchecked")
    private static void setNestedValue(Map<String, Object> root, String jsonPath, Object value) {
        String relative = JsonPathWriter.toRelativePath(jsonPath);
        if (relative.isEmpty()) {
            if (value instanceof Map<?, ?> map) {
                root.putAll((Map<String, Object>) map);
            }
            return;
        }

        String[] segments = relative.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            String segment = JsonPathWriter.stripArraySuffix(segments[index]);
            Object child = current.get(segment);
            if (!(child instanceof Map<?, ?>)) {
                child = new LinkedHashMap<String, Object>();
                current.put(segment, child);
            }
            current = (Map<String, Object>) child;
        }

        String lastSegment = JsonPathWriter.stripArraySuffix(segments[segments.length - 1]);
        current.put(lastSegment, value);
    }

    private static List<SoapArgument> toOrderedArguments(Map<?, ?> fields) {
        List<SoapArgument> arguments = new ArrayList<>();
        for (Map.Entry<?, ?> entry : fields.entrySet()) {
            arguments.add(new SoapArgument(String.valueOf(entry.getKey()), entry.getValue()));
        }
        return arguments;
    }
}
