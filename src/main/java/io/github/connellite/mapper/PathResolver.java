package io.github.connellite.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.mapper.path.JsonPathReader;
import io.github.connellite.mapper.path.JsonPathWriter;
import io.github.connellite.mapper.path.SoapArgument;
import io.github.connellite.mapper.path.SoapArgumentBuilder;
import io.github.connellite.mapper.path.SoapPathResolver;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;

@UtilityClass
public final class PathResolver {

    public static Object readFromJsonBody(JsonNode body, String jsonPath) {
        return JsonPathReader.read(body, jsonPath);
    }

    public static void writeToJsonBody(ObjectNode body, String jsonPath, Object value) {
        JsonPathWriter.write(body, jsonPath, value);
    }

    public static Object readFromSoapResult(Object soapResult, String spelExpression) {
        return SoapPathResolver.read(soapResult, spelExpression);
    }

    public static List<SoapArgument> buildSoapArguments(Map<String, String> requestMappings, JsonNode body) {
        return SoapArgumentBuilder.build(requestMappings, body);
    }

    public static List<SoapArgument> buildSoapHeaderArguments(Map<String, String> headerMappings, JsonNode body) {
        return SoapArgumentBuilder.buildParts(headerMappings, body);
    }
}
