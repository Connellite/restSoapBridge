package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.connellite.config.OpenApiExampleFactory;
import io.github.connellite.exception.RequestValidationException;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
#if SPRING_BOOT_3
import jakarta.jws.WebMethod;
import jakarta.jws.WebParam;
import jakarta.jws.WebResult;
import jakarta.jws.WebService;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlValue;
#else
import javax.jws.WebMethod;
import javax.jws.WebParam;
import javax.jws.WebResult;
import javax.jws.WebService;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlValue;
#endif
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XSD constructs JAXB produces for ordinary Java DTOs: type inheritance ({@code complexContent/extension}),
 * attributes, simple content ({@code @XmlValue} + attributes) and enumerations. They must reach Swagger and
 * request validation, not only the SOAP call.
 */
class XsdFeaturesIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SoapTestBackend backend;

    @BeforeAll
    static void start() throws Exception {
        backend = SoapTestBackend.start("Customers", ServiceImpl.class, new ServiceImpl());
    }

    @AfterAll
    static void stop() {
        backend.close();
    }

    @Test
    void requestSchemaHasInheritedFieldsAttributesSimpleContentAndEnums() {
        ObjectSchema schema = OpenApiExampleFactory.schemaWithXsdExamples(
                backend.mapping("register").getRequestSchemaFields(), List.of());

        Schema<?> customer = schema.getProperties().get("customer");
        assertEquals(List.of("name", "id", "level", "since"), List.copyOf(customer.getProperties().keySet()));
        assertEquals("string", property(customer, "name").getType());
        assertEquals("integer", property(customer, "id").getType());
        assertEquals("int64", property(customer, "id").getFormat());
        assertEquals(List.of("name", "id"), customer.getRequired());
        assertEquals(List.of("GOLD", "PLATINUM"), property(customer, "level").getEnum());

        Schema<?> price = schema.getProperties().get("price");
        assertEquals(List.of("value", "currency"), List.copyOf(price.getProperties().keySet()));
        assertEquals("number", property(price, "value").getType());
        assertEquals("string", property(price, "currency").getType());

        @SuppressWarnings("unchecked")
        Map<String, Object> example = (Map<String, Object>) schema.getExample();
        assertEquals("GOLD", ((Map<?, ?>) example.get("customer")).get("level"));
    }

    @Test
    void responseSchemaHasInheritedFieldsAndSimpleContent() {
        ObjectSchema schema = OpenApiExampleFactory.schemaWithXsdExamples(
                backend.mapping("register").getResponseSchemaFields(), List.of());

        Schema<?> receipt = schema.getProperties().get("return");
        Schema<?> customer = property(receipt, "customer");
        assertEquals(List.of("name", "id", "level", "since"), List.copyOf(customer.getProperties().keySet()));
        assertEquals(List.of("value", "currency"), List.copyOf(property(receipt, "total").getProperties().keySet()));
    }

    @Test
    void roundTripsInheritedFieldsAttributesAndSimpleContent() throws Exception {
        ObjectNode response = backend.execute("register", MAPPER.readTree("""
                {"customer": {"id": 7, "name": "Ann", "level": "GOLD", "since": "2024-01-02"},
                 "price": {"value": 9.5, "currency": "EUR"}}
                """));

        JsonNode customer = response.path("return").path("customer");
        assertEquals(7, customer.path("id").asLong(), response.toString());
        assertEquals("Ann", customer.path("name").asText());
        assertEquals("GOLD", customer.path("level").asText());
        assertEquals("2024-01-02", customer.path("since").asText());
        JsonNode total = response.path("return").path("total");
        assertEquals(0, new BigDecimal("19.0").compareTo(total.path("value").decimalValue()), response.toString());
        assertEquals("EUR", total.path("currency").asText());
    }

    @Test
    void valueOutsideEnumerationIsRejected() {
        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> backend.execute("register", MAPPER.readTree("""
                        {"customer": {"id": 7, "name": "Ann", "level": "SILVER"}}
                        """)));

        assertTrue(exception.getErrors().stream().anyMatch(error -> error.startsWith("$.customer.level")),
                exception.getErrors().toString());
    }

    @Test
    void missingRequiredAttributeIsRejected() {
        RequestValidationException exception = assertThrows(RequestValidationException.class,
                () -> backend.execute("register", MAPPER.readTree("""
                        {"customer": {"name": "Ann"}}
                        """)));

        assertEquals(List.of("$.customer: required property 'id' not found"), exception.getErrors());
    }

    private static Schema<?> property(Schema<?> schema, String name) {
        Schema<?> property = schema.getProperties().get(name);
        assertTrue(property != null, "No property '" + name + "' in " + schema.getProperties().keySet());
        return property;
    }

    public enum Level {
        GOLD, PLATINUM
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Customer {
        @XmlElement(required = true)
        public String name;
        @XmlAttribute(required = true)
        public Long id;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class VipCustomer extends Customer {
        public Level level;
        @XmlAttribute
        public String since;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Money {
        @XmlValue
        public BigDecimal amount;
        @XmlAttribute
        public String currency;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    public static class Receipt {
        public VipCustomer customer;
        public Money total;
    }

    // Own namespace: dynamic clients of other test services generate classes in the http://ws.example.test package.
    @WebService(name = "Customers", serviceName = "CustomersService", targetNamespace = "http://xsd.example.test")
    public static class ServiceImpl {

        @WebMethod
        @WebResult(name = "return")
        public Receipt register(@WebParam(name = "customer") @XmlElement(required = true) VipCustomer customer,
                                @WebParam(name = "price") Money price) {
            Receipt receipt = new Receipt();
            receipt.customer = customer;
            receipt.total = new Money();
            receipt.total.amount = price != null ? price.amount.multiply(BigDecimal.valueOf(2)) : BigDecimal.ZERO;
            receipt.total.currency = price != null ? price.currency : "EUR";
            return receipt;
        }
    }
}
