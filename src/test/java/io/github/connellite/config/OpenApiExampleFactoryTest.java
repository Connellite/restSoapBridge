package io.github.connellite.config;

import io.github.connellite.model.SchemaField;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiExampleFactoryTest {

    @Test
    void exampleMap_buildsNestedObjectFromDottedPaths() {
        Map<String, Object> example = OpenApiExampleFactory.exampleMap(List.of("$.customer.name", "$.customer.phone"));

        @SuppressWarnings("unchecked")
        Map<String, Object> customer = (Map<String, Object>) example.get("customer");
        assertEquals(2, customer.size());
        assertEquals(true, customer.containsKey("name"));
        assertEquals(true, customer.containsKey("phone"));
    }

    @Test
    void exampleMap_buildsCollectionItemExample() {
        Map<String, Object> example = OpenApiExampleFactory.exampleMap(List.of("$.products[].sku", "$.products[].qty"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> products = (List<Map<String, Object>>) example.get("products");
        assertEquals(1, products.size());
        assertEquals(true, products.get(0).containsKey("sku"));
        assertEquals(true, products.get(0).containsKey("qty"));
    }

    @Test
    void schemaWithXsdExamples_usesTypesFromXsdMetadata() {
        ObjectSchema schema = OpenApiExampleFactory.schemaWithXsdExamples(List.of(
                new SchemaField("$.customer.name", "string", true, false),
                new SchemaField("$.customer.age", "int", true, false),
                new SchemaField("$.customer.enabled", "boolean", false, false),
                new SchemaField("$.searchRemoved", "boolean", false, false),
                new SchemaField("$.products", "object", true, true),
                new SchemaField("$.products[].sku", "string", true, false),
                new SchemaField("$.products[].qty", "int", true, false)
        ), List.of());

        Schema<?> customerSchema = schema.getProperties().get("customer");
        assertEquals(ObjectSchema.class, customerSchema.getClass());
        Map<String, Schema> customerProperties = customerSchema.getProperties();
        assertEquals(IntegerSchema.class, customerProperties.get("age").getClass());
        assertEquals(BooleanSchema.class, customerProperties.get("enabled").getClass());
        assertEquals(BooleanSchema.class, schema.getProperties().get("searchRemoved").getClass());

        @SuppressWarnings("unchecked")
        Map<String, Object> example = (Map<String, Object>) schema.getExample();
        assertInstanceOf(Boolean.class, example.get("searchRemoved"));

        Schema<?> productsSchema = schema.getProperties().get("products");
        assertEquals(ArraySchema.class, productsSchema.getClass());
        Schema<?> productItemSchema = productsSchema.getItems();
        Map<String, Schema> productProperties = productItemSchema.getProperties();
        assertEquals(IntegerSchema.class, productProperties.get("qty").getClass());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void scalarSchema_keepsValueRangeAndNamesXsdTypesTheJsonTypeHides() {
        Schema<?> time = OpenApiExampleFactory.scalarSchema("time");
        assertEquals("time", time.getFormat());
        assertEquals("time", time.getExtensions().get("x-xsd-type"));
        Schema<?> decimal = OpenApiExampleFactory.scalarSchema("decimal");
        assertEquals("number", decimal.getType());
        assertNull(decimal.getFormat());
        assertEquals("decimal", decimal.getExtensions().get("x-xsd-type"));
        assertNull(OpenApiExampleFactory.scalarSchema("integer").getFormat());
        assertEquals("int64", OpenApiExampleFactory.scalarSchema("unsignedInt").getFormat());
        assertNull(OpenApiExampleFactory.scalarSchema("int").getExtensions());

        ObjectSchema schema = OpenApiExampleFactory.schemaWithXsdExamples(List.of(
                new SchemaField("$.at", "time", true, false)), List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> example = (Map<String, Object>) schema.getExample();
        assertTrue(example.get("at").toString().matches("[0-9]{2}:[0-9]{2}(:[0-9]{2})?"), example.toString());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void schemaWithXsdExamples_publishesChoicesNillableOccursAndDocumentation() {
        SchemaField.ChoiceRef email = new SchemaField.ChoiceRef("#c", 0, 0, false);
        SchemaField.ChoiceRef phone = new SchemaField.ChoiceRef("#c", 1, 0, false);
        SchemaField.ChoiceRef card = new SchemaField.ChoiceRef("pay.#c", 0, 1, true);
        SchemaField.ChoiceRef cash = new SchemaField.ChoiceRef("pay.#c", 1, 1, true);
        ObjectSchema schema = OpenApiExampleFactory.schemaWithXsdExamples(List.of(
                new SchemaField("$.email", "string", false, false).withChoice(email),
                new SchemaField("$.phone", "string", false, false).withChoice(phone),
                new SchemaField("$.pay.card", "string", false, false).withChoice(card),
                new SchemaField("$.pay.cash", "boolean", false, false).withChoice(cash),
                new SchemaField("$.note", "string", true, false, null, true, null, null, "Free text", List.of()),
                new SchemaField("$.tags", "string", false, true, null, false, 1, 3, "Labels", List.of())
        ), List.of());

        assertEquals(List.of(List.of("email"), List.of("phone")),
                schema.getOneOf().stream().map(Schema::getRequired).toList());
        Schema<?> pay = schema.getProperties().get("pay");
        assertEquals(List.of("card", "cash"), pay.getNot().getRequired());
        Schema<?> note = schema.getProperties().get("note");
        assertEquals(Boolean.TRUE, note.getNullable());
        assertEquals("Free text", note.getDescription());
        Schema<?> tags = schema.getProperties().get("tags");
        assertEquals(1, tags.getMinItems());
        assertEquals(3, tags.getMaxItems());
        assertEquals("Labels", tags.getDescription());
    }
}
