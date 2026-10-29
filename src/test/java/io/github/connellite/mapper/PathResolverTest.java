package io.github.connellite.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readFromJsonBody_returnsNullForMissingPath() {
        ObjectNode body = MAPPER.createObjectNode().put("user", "Admin");

        assertNull(PathResolver.readFromJsonBody(body, "$.missing"));
    }

    @Test
    void writeToJsonBody_writesReservedReturnField() {
        ObjectNode body = MAPPER.createObjectNode();

        PathResolver.writeToJsonBody(body, "$.return", "token-123");

        assertEquals("token-123", body.get("return").asText());
    }

    @Test
    void writeToJsonBody_writesNestedResponseField() {
        ObjectNode body = MAPPER.createObjectNode();

        PathResolver.writeToJsonBody(body, "$.loginResponse.return", "token-456");

        assertEquals("token-456", body.get("loginResponse").get("return").asText());
    }

    @Test
    void writeToJsonBody_writesObjectsAndArraysUsingJacksonConversion() {
        ObjectNode body = MAPPER.createObjectNode();

        PathResolver.writeToJsonBody(body, "$.result", Map.of(
                "count", 2,
                "items", List.of(Map.of("id", 1), Map.of("id", 2))
        ));

        assertEquals(2, body.get("result").get("count").asInt());
        assertEquals(2, body.get("result").get("items").size());
        assertEquals(1, body.get("result").get("items").get(0).get("id").asInt());
    }
}
