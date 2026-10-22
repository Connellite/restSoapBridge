package io.github.connellite.service.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.SchemaField;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BridgeProperties properties = new BridgeProperties();
    private final RequestSchemaValidator validator = new RequestSchemaValidator(properties);

    @Test
    void acceptsBodyMatchingXsdSchema() {
        assertDoesNotThrow(() -> validator.validate(orderMapping(), json("""
                {"customer": {"name": "Alice", "age": 30}, "products": [{"sku": "A-1"}],
                 "createdAt": "2024-01-02T03:04:05", "note": null}
                """)));
    }

    @Test
    void rejectsMissingRequiredFieldAndWrongTypes() {
        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> validator.validate(orderMapping(), json("""
                        {"customer": {"age": "thirty"}, "products": {"sku": "A-1"}}
                        """)));

        List<String> errors = exception.getErrors();
        assertTrue(errors.stream().anyMatch(error -> error.contains("name")), errors.toString());
        assertTrue(errors.stream().anyMatch(error -> error.contains("age")), errors.toString());
        assertTrue(errors.stream().anyMatch(error -> error.contains("products")), errors.toString());
    }

    @Test
    void acceptsNumbersAndBooleansEncodedAsStrings() {
        assertDoesNotThrow(() -> validator.validate(orderMapping(), json("""
                {"customer": {"name": "Alice", "age": "42", "score": " 1.5e2 ", "vip": "true"}, "products": []}
                """)));
    }

    @Test
    void optionalStringEncodedScalarMayBeNull() {
        assertDoesNotThrow(() -> validator.validate(orderMapping(), json("""
                {"customer": {"name": "Alice", "age": null, "vip": null}, "products": []}
                """)));
    }

    @Test
    void rejectsStringThatIsNotANumber() {
        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> validator.validate(orderMapping(), json("""
                        {"customer": {"name": "Alice", "age": "4x2"}, "products": []}
                        """)));

        assertTrue(exception.getErrors().stream().allMatch(error -> error.contains("age")),
                exception.getErrors().toString());
    }

    @Test
    void requiredFieldMustNotBeNull() {
        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> validator.validate(orderMapping(), json("""
                        {"customer": {"name": null}, "products": []}
                        """)));

        assertTrue(exception.getErrors().get(0).contains("name"), exception.getErrors().toString());
    }

    @Test
    void skipsValidationWhenDisabled() {
        properties.getValidation().setEnabled(false);

        assertDoesNotThrow(() -> validator.validate(orderMapping(), json("{\"customer\": 1}")));
    }

    @Test
    void skipsOperationsWithoutXsdMetadata() {
        MappingDefinition mapping = orderMapping();
        mapping.setRequestSchemaFields(List.of());

        assertDoesNotThrow(() -> validator.validate(mapping, json("{\"customer\": 1}")));
    }

    private static MappingDefinition orderMapping() {
        MappingDefinition mapping = new MappingDefinition();
        mapping.setPath("/api/OrderService/createOrder");
        mapping.setMethod("POST");
        mapping.setRequestSchemaFields(List.of(
                new SchemaField("$.customer.name", "string", true, false),
                new SchemaField("$.customer.age", "int", false, false),
                new SchemaField("$.customer.score", "double", false, false),
                new SchemaField("$.customer.vip", "boolean", false, false),
                new SchemaField("$.products", "object", true, true),
                new SchemaField("$.products[].sku", "string", true, false),
                new SchemaField("$.createdAt", "dateTime", false, false),
                new SchemaField("$.note", "string", false, false)
        ));
        return mapping;
    }

    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }
}
