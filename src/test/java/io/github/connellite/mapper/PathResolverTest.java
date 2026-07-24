package io.github.connellite.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.mapper.path.SoapArgument;
#if SPRING_BOOT_3
import jakarta.activation.DataHandler;
import jakarta.activation.DataSource;
#else
import javax.activation.DataHandler;
import javax.activation.DataSource;
#endif
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildSoapArguments_unwrapsNestedLoginGroup() throws Exception {
        ObjectNode body = MAPPER.createObjectNode()
                .put("user", "Admin")
                .put("pass", "admin");

        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("$.user", "$.login.user");
        mappings.put("$.pass", "$.login.pass");

        List<SoapArgument> arguments = PathResolver.buildSoapArguments(mappings, body);

        assertEquals(2, arguments.size());
        assertEquals("user", arguments.get(0).name());
        assertEquals("Admin", arguments.get(0).value());
        assertEquals("pass", arguments.get(1).name());
        assertEquals("admin", arguments.get(1).value());
    }

    @Test
    void buildSoapArguments_buildsNestedComplexFields() throws Exception {
        ObjectNode customer = MAPPER.createObjectNode()
                .put("name", "Alice")
                .put("phone", "123");
        ObjectNode body = MAPPER.createObjectNode().set("customer", customer);

        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("$.customer.name", "$.createOrder.customer.name");
        mappings.put("$.customer.phone", "$.createOrder.customer.phone");

        List<SoapArgument> arguments = PathResolver.buildSoapArguments(mappings, body);

        assertEquals(1, arguments.size());
        assertEquals("customer", arguments.get(0).name());
        @SuppressWarnings("unchecked")
        Map<String, Object> customerArg = (Map<String, Object>) arguments.get(0).value();
        assertEquals("Alice", customerArg.get("name"));
        assertEquals("123", customerArg.get("phone"));
    }

    @Test
    void buildSoapArguments_passesCollectionAsArray() throws Exception {
        ObjectNode product = MAPPER.createObjectNode().put("sku", "A-1");
        ObjectNode body = MAPPER.createObjectNode()
                .set("products", MAPPER.createArrayNode().add(product));

        Map<String, String> mappings = Map.of("$.products", "$.createOrder.products");

        List<SoapArgument> arguments = PathResolver.buildSoapArguments(mappings, body);

        assertEquals(1, arguments.size());
        assertEquals("products", arguments.get(0).name());
        assertEquals(List.of(Map.of("sku", "A-1")), arguments.get(0).value());
    }

    @Test
    void readFromJsonBody_returnsNullForMissingPath() throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("user", "Admin");

        assertNull(PathResolver.readFromJsonBody(body, "$.missing"));
    }

    @Test
    void readFromSoapResult_returnsScalarForRootPropertyExpression() {
        Object value = PathResolver.readFromSoapResult("token-123", "#root.return");

        assertEquals("token-123", value);
    }

    @Test
    void readFromSoapResult_unwrapsSingletonCollectionBeforeRootPropertyExpression() {
        Object value = PathResolver.readFromSoapResult(List.of("token-list"), "#root.return");

        assertEquals("token-list", value);
    }

    @Test
    void readFromSoapResult_unwrapsSingletonPrimitiveArrayBeforeRootPropertyExpression() {
        Object value = PathResolver.readFromSoapResult(new int[]{42}, "#root.return");

        assertEquals(42, value);
    }

    @Test
    void readFromSoapResult_returnsMultiCollectionForRootPropertyExpression() {
        List<String> tokens = List.of("token-1", "token-2");

        Object value = PathResolver.readFromSoapResult(tokens, "#root.return");

        assertEquals(tokens, value);
    }

    @Test
    void readFromSoapResult_skipsSyntheticReturnWrapperForObjectProperty() {
        FulltextConfig config = new FulltextConfig(true);

        Object value = PathResolver.readFromSoapResult(config, "#root.return.analyzeAbbreviations");

        assertEquals(true, value);
    }

    @Test
    void readFromSoapResult_skipsSyntheticUnderscoreReturnWrapperForObjectProperty() {
        CallReturn response = new CallReturn("ok");

        Object value = PathResolver.readFromSoapResult(response, "#root._return.entry");

        assertEquals("ok", value);
    }

    @Test
    void readFromSoapResult_trimsNonAlphanumericWrapperNameEdges() {
        CallReturn response = new CallReturn("ok");

        Object value = PathResolver.readFromSoapResult(response, "#root.__RETURN__.entry");

        assertEquals("ok", value);
    }

    @Test
    void readFromSoapResult_skipsSyntheticReturnWrapperForWholeObject() {
        FulltextConfig config = new FulltextConfig(true);

        Object value = PathResolver.readFromSoapResult(config, "#root.return");

        assertEquals(config, value);
    }

    @Test
    void readFromSoapResult_readsPropertyFromObjectResponse() {
        Map<String, Object> response = Map.of("return", "token-456");

        Object value = PathResolver.readFromSoapResult(response, "#root.return");

        assertEquals("token-456", value);
    }

    @Test
    void readFromSoapResult_readsBracketPropertyFromObjectResponse() {
        Map<String, Object> response = Map.of("return", "token-789");

        Object value = PathResolver.readFromSoapResult(response, "#root['return']");

        assertEquals("token-789", value);
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

    @Test
    void writeToJsonBody_serializesDataHandlerWithoutTouchingOutputStream() {
        ObjectNode body = MAPPER.createObjectNode();
        DataHandler dataHandler = new DataHandler(new TestDataSource("hello"));

        PathResolver.writeToJsonBody(body, "$.attachment", dataHandler);

        assertEquals("text/plain", body.get("attachment").get("contentType").asText());
        assertEquals("test.txt", body.get("attachment").get("name").asText());
        assertEquals("aGVsbG8=", body.get("attachment").get("content").asText());
    }

    @Test
    void readFromSoapResult_keepsSingletonCollectionWhenMappingRootPayload() {
        List<MapEntry> files = List.of(new MapEntry("path", "name.jpg"));

        Object value = PathResolver.readFromSoapResult(files, "#root");

        assertEquals(files, value);
    }

    @Test
    void readFromSoapResult_returnsNullWhenOptionalMapPropertyIsNull() {
        LabeledItem item = new LabeledItem(null);

        Object value = PathResolver.readFromSoapResult(item, "#root.labels.entry");

        assertNull(value);
    }

    @Test
    void readFromSoapResult_readsMapAsWholeObject() {
        LabeledItem item = new LabeledItem(Map.of("en", "Root"));

        Object value = PathResolver.readFromSoapResult(item, "#root.labels");

        assertEquals(Map.of("en", "Root"), value);
    }

    private record MapEntry(String key, String value) {
    }

    private static final class LabeledItem {
        private final Map<String, String> labels;

        private LabeledItem(Map<String, String> labels) {
            this.labels = labels;
        }

        public Map<String, String> getLabels() {
            return labels;
        }
    }

    private static class FulltextConfig {

        private final boolean analyzeAbbreviations;

        private FulltextConfig(boolean analyzeAbbreviations) {
            this.analyzeAbbreviations = analyzeAbbreviations;
        }

        public boolean isAnalyzeAbbreviations() {
            return analyzeAbbreviations;
        }
    }

    private record CallReturn(String entry) {
    }

    private record TestDataSource(String content) implements DataSource {

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getContentType() {
            return "text/plain";
        }

        @Override
        public String getName() {
            return "test.txt";
        }
    }
}
