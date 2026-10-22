package io.github.connellite.mapper.path;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.type.ArrayType;
import com.fasterxml.jackson.databind.type.CollectionType;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Jackson support for JAXB key/value shapes (see {@link JaxbMaps}), applied at any nesting level:
 * <ul>
 *     <li>map wrapper ({@code java.util.Map} parameter or property) is read from a plain JSON object,
 *     {@code {"entry": [...]}} or an entry array, and written as a plain JSON object;</li>
 *     <li>{@code List<entry>} / {@code entry[]} is also read from a plain JSON object.</li>
 * </ul>
 * Without it Jackson silently binds {@code {"a": "1"}} to an empty wrapper or to one entry with null key/value.
 */
final class JaxbMapModule extends SimpleModule {

    // Plain mapper for tree <-> java.util conversion only; never sees JAXB types.
    private static final ObjectMapper TREE_MAPPER = new ObjectMapper();

    JaxbMapModule() {
        super("jaxb-maps");
        setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc,
                                                          JsonDeserializer<?> deserializer) {
                return JaxbMaps.isMapWrapper(beanDesc.getBeanClass()) ? new MapWrapperDeserializer(deserializer) : deserializer;
            }

            @Override
            public JsonDeserializer<?> modifyCollectionDeserializer(DeserializationConfig config, CollectionType type,
                                                                    BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
                return entryContainer(type.getContentType()) ? new EntryListDeserializer(deserializer) : deserializer;
            }

            @Override
            public JsonDeserializer<?> modifyArrayDeserializer(DeserializationConfig config, ArrayType type,
                                                               BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
                return entryContainer(type.getContentType()) ? new EntryListDeserializer(deserializer) : deserializer;
            }
        });
        setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc,
                                                      JsonSerializer<?> serializer) {
                return JaxbMaps.isMapWrapper(beanDesc.getBeanClass()) ? new MapWrapperSerializer() : serializer;
            }
        });
    }

    private static boolean entryContainer(JavaType contentType) {
        return contentType != null && JaxbMaps.isEntryType(contentType.getRawClass());
    }

    /**
     * Re-reads the current value as a tree, lets {@code reshape} normalize it and hands it to the delegate.
     */
    private static Object deserializeReshaped(JsonParser parser, DeserializationContext context,
                                              JsonDeserializer<?> delegate, java.util.function.UnaryOperator<Object> reshape)
            throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return delegate.getNullValue(context);
        }
        JsonNode tree = context.readTree(parser);
        Object raw = TREE_MAPPER.convertValue(tree, Object.class);
        Object reshaped;
        try {
            reshaped = reshape.apply(raw);
        } catch (IllegalArgumentException exception) {
            return context.handleWeirdStringValue(delegate.handledType(), tree.toString(), exception.getMessage());
        }
        JsonParser reshapedParser = TREE_MAPPER.valueToTree(reshaped).traverse(parser.getCodec());
        reshapedParser.nextToken();
        return delegate.deserialize(reshapedParser, context);
    }

    private static final class MapWrapperDeserializer extends DelegatingDeserializer {

        private MapWrapperDeserializer(JsonDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
            return new MapWrapperDeserializer(newDelegatee);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return deserializeReshaped(parser, context, _delegatee, raw -> Map.of("entry", JaxbMaps.toEntries(raw)));
        }
    }

    private static final class EntryListDeserializer extends DelegatingDeserializer {

        private EntryListDeserializer(JsonDeserializer<?> delegate) {
            super(delegate);
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> newDelegatee) {
            return new EntryListDeserializer(newDelegatee);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return deserializeReshaped(parser, context, _delegatee, raw ->
                    // A single {key, value} object stays as is: ACCEPT_SINGLE_VALUE_AS_ARRAY turns it into one entry.
                    raw instanceof Map<?, ?> object && JaxbMaps.isEntryShaped(object) ? List.of(raw) : JaxbMaps.toEntries(raw));
        }
    }

    private static final class MapWrapperSerializer extends JsonSerializer<Object> {

        @Override
        public void serialize(Object value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeStartObject();
            for (Map.Entry<String, Object> entry : JaxbMaps.wrapperToMap(value).entrySet()) {
                serializers.defaultSerializeField(entry.getKey(), entry.getValue(), generator);
            }
            generator.writeEndObject();
        }
    }
}
