package io.github.connellite.mapper.path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;

@UtilityClass
public class JsonPathWriter {

    private static final ObjectMapper OBJECT_MAPPER = SoapJsonMapper.instance();

    public static void write(ObjectNode body, String jsonPath, Object value) {
        String relative = toRelativePath(jsonPath);
        if (relative.isEmpty()) {
            return;
        }

        String[] segments = relative.split("\\.");
        ObjectNode current = body;
        for (int index = 0; index < segments.length - 1; index++) {
            String segment = stripArraySuffix(segments[index]);
            JsonNode child = current.get(segment);
            if (!(child instanceof ObjectNode)) {
                child = OBJECT_MAPPER.createObjectNode();
                current.set(segment, child);
            }
            current = (ObjectNode) child;
        }

        current.set(stripArraySuffix(segments[segments.length - 1]), OBJECT_MAPPER.valueToTree(value));
    }

    static String toRelativePath(String jsonPath) {
        String normalized = JsonPathReader.normalize(jsonPath);
        if ("$".equals(normalized)) {
            return "";
        }
        return normalized.startsWith("$.") ? normalized.substring(2) : normalized;
    }

    static String stripArraySuffix(String segment) {
        int bracketIndex = segment.indexOf('[');
        return bracketIndex >= 0 ? segment.substring(0, bracketIndex) : segment;
    }
}
