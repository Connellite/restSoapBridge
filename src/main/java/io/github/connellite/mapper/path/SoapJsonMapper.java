package io.github.connellite.mapper.path;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.TypeFactory;
#if SPRING_BOOT_3
import com.fasterxml.jackson.module.jakarta.xmlbind.JakartaXmlBindAnnotationIntrospector;
import jakarta.activation.DataHandler;
import jakarta.xml.bind.JAXBElement;
#else
import com.fasterxml.jackson.module.jaxb.JaxbAnnotationIntrospector;
import javax.activation.DataHandler;
import javax.xml.bind.JAXBElement;
#endif
import lombok.experimental.UtilityClass;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.io.IOException;

/**
 * Jackson mapper shared by the SOAP side of the bridge.
 * <p>
 * JSON property names follow JAXB annotations ({@code @XmlElement(name = "return") _return},
 * {@code @XmlElement(name = "class") clazz}), so REST fields match the XSD names the mappings are
 * generated from. XML dates are written in their lexical XSD form instead of epoch millis, and
 * {@link JAXBElement} wrappers (nillable optional elements) are written as their value.
 */
@UtilityClass
public class SoapJsonMapper {

    private static final ObjectMapper INSTANCE = create();

    public static ObjectMapper instance() {
        return INSTANCE;
    }

    private static ObjectMapper create() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.setAnnotationIntrospector(AnnotationIntrospector.pair(
                xmlBindIntrospector(mapper.getTypeFactory()),
                mapper.getSerializationConfig().getAnnotationIntrospector()
        ));

        SimpleModule module = new SimpleModule("rest-soap-bridge");
        module.addSerializer(DataHandler.class, new DataHandlerSerializer());
        module.addSerializer(XMLGregorianCalendar.class, new XmlGregorianCalendarSerializer());
        module.addDeserializer(XMLGregorianCalendar.class, new XmlGregorianCalendarDeserializer());
        module.addSerializer(JAXBElement.class, new JaxbElementSerializer());
        return mapper.registerModule(module);
    }

    /**
     * The JAXB introspector forces its own base64-only {@code DataHandler} serializer through annotations;
     * suppress it so {@link DataHandlerSerializer} (content type, name, content) stays in charge.
     */
    private static AnnotationIntrospector xmlBindIntrospector(TypeFactory typeFactory) {
#if SPRING_BOOT_3
        return new JakartaXmlBindAnnotationIntrospector(typeFactory) {
            @Override
            public JsonSerializer<?> findSerializer(Annotated annotated) {
                return isDataHandler(annotated) ? null : super.findSerializer(annotated);
            }
        };
#else
        return new JaxbAnnotationIntrospector(typeFactory) {
            @Override
            public JsonSerializer<?> findSerializer(Annotated annotated) {
                return isDataHandler(annotated) ? null : super.findSerializer(annotated);
            }
        };
#endif
    }

    private static boolean isDataHandler(Annotated annotated) {
        Class<?> rawType = annotated.getRawType();
        return rawType != null && DataHandler.class.isAssignableFrom(rawType);
    }

    private static final class XmlGregorianCalendarSerializer extends JsonSerializer<XMLGregorianCalendar> {

        @Override
        public void serialize(XMLGregorianCalendar value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            generator.writeString(value.toXMLFormat());
        }
    }

    private static final class XmlGregorianCalendarDeserializer extends JsonDeserializer<XMLGregorianCalendar> {

        private static final DatatypeFactory DATATYPE_FACTORY = datatypeFactory();

        @Override
        public XMLGregorianCalendar deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String text = parser.getValueAsString();
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return DATATYPE_FACTORY.newXMLGregorianCalendar(text.trim());
            } catch (IllegalArgumentException exception) {
                return (XMLGregorianCalendar) context.handleWeirdStringValue(
                        XMLGregorianCalendar.class, text, "not a valid XSD date/time: %s", exception.getMessage());
            }
        }

        private static DatatypeFactory datatypeFactory() {
            try {
                return DatatypeFactory.newInstance();
            } catch (DatatypeConfigurationException exception) {
                throw new IllegalStateException("No javax.xml.datatype.DatatypeFactory available", exception);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static final class JaxbElementSerializer extends JsonSerializer<JAXBElement> {

        @Override
        public void serialize(JAXBElement value, JsonGenerator generator, SerializerProvider serializers)
                throws IOException {
            if (value.isNil() || value.getValue() == null) {
                generator.writeNull();
                return;
            }
            serializers.defaultSerializeValue(value.getValue(), generator);
        }
    }
}
