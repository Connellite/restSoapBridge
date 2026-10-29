package io.github.connellite.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.mapper.path.JsonPathReader;
import io.github.connellite.mapper.path.JsonPathWriter;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class PathResolver {

    public static Object readFromJsonBody(JsonNode body, String jsonPath) {
        return JsonPathReader.read(body, jsonPath);
    }

    public static void writeToJsonBody(ObjectNode body, String jsonPath, Object value) {
        JsonPathWriter.write(body, jsonPath, value);
    }
}
