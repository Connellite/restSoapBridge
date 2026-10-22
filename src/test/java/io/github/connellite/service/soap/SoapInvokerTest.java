package io.github.connellite.service.soap;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SoapInvokerTest {

    @Test
    void convertArgument_convertsMapToExpectedBeanType() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("query", "contract");
        value.put("limit", 10);

        Object converted = SoapInvoker.convertArgument(value, SimpleQueryParams.class);

        SimpleQueryParams params = assertInstanceOf(SimpleQueryParams.class, converted);
        assertEquals("contract", params.getQuery());
        assertEquals(10, params.getLimit());
    }

    @Test
    void convertArgument_acceptsSingleValueAsNestedList() {
        Map<String, Object> restriction = new LinkedHashMap<>();
        restriction.put("valueList", "qui");

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("query", java.util.List.of(restriction));

        Object converted = SoapInvoker.convertArgument(value, DtoDocumentQueryParams.class);

        DtoDocumentQueryParams params = assertInstanceOf(DtoDocumentQueryParams.class, converted);
        assertEquals("qui", params.getQuery().get(0).getValueList().get(0));
    }

    @Test
    void convertArgument_convertsSimpleValuesToExpectedType() {
        Object converted = SoapInvoker.convertArgument("10", Integer.class);

        assertEquals(10, converted);
    }

    @Test
    void convertArgument_keepsBinaryArrayForSingleParameter() {
        byte[] content = new byte[]{1, 2, 3, 4};

        Object converted = SoapInvoker.convertArgument(content, byte[].class);

        assertArrayEquals(content, assertInstanceOf(byte[].class, converted));
    }

    @Test
    void convertArgument_returnsListForRepeatingArrayParameter() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("key", "author");
        first.put("value", "Pushkin");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("key", "title");
        second.put("value", "Dubrovsky");

        Object converted = SoapInvoker.convertArgument(List.of(first, second), MapEntryStringString[].class, true);

        List<?> entries = assertInstanceOf(List.class, converted);
        assertEquals(2, entries.size());
        MapEntryStringString entry = assertInstanceOf(MapEntryStringString.class, entries.get(0));
        assertEquals("author", entry.getKey());
        assertEquals("Pushkin", entry.getValue());
    }

    @Test
    void convertArgument_normalizesRepeatingArrayValueToList() {
        MapEntryStringString entry = new MapEntryStringString();
        entry.setKey("author");
        entry.setValue("Pushkin");

        Object converted = SoapInvoker.convertArgument(new MapEntryStringString[]{entry}, MapEntryStringString[].class, true);

        List<?> entries = assertInstanceOf(List.class, converted);
        assertEquals(entry, entries.get(0));
    }

    @Test
    void convertArgument_convertsEntryListToJaxbMapWrapper() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("key", "msg");
        first.put("value", "hello");

        Object converted = SoapInvoker.convertArgument(List.of(first), JaxbMapParams.class);

        JaxbMapParams params = assertInstanceOf(JaxbMapParams.class, converted);
        assertEquals(1, params.getEntry().size());
        assertEquals("msg", params.getEntry().get(0).getKey());
        assertEquals("hello", params.getEntry().get(0).getValue());
    }

    @Test
    void convertArgument_convertsPlainMapToJaxbMapWrapper() {
        Object converted = SoapInvoker.convertArgument(Map.of("msg", "hello"), JaxbMapParams.class);

        JaxbMapParams params = assertInstanceOf(JaxbMapParams.class, converted);
        assertEquals(1, params.getEntry().size());
        assertEquals("msg", params.getEntry().get(0).getKey());
        assertEquals("hello", params.getEntry().get(0).getValue());
    }

    @Test
    void convertArgument_convertsSupportedScalars() {
        assertEquals(10, SoapInvoker.convertArgument("10", int.class));
        assertEquals(10L, SoapInvoker.convertArgument("10", Long.class));
        assertEquals(1.5d, SoapInvoker.convertArgument("1.5", Double.class));
        assertEquals(true, SoapInvoker.convertArgument("true", Boolean.class));
        assertEquals('A', SoapInvoker.convertArgument("A", Character.class));
        assertEquals(SampleColor.RED, SoapInvoker.convertArgument("RED", SampleColor.class));
        assertEquals(10L, SoapInvoker.convertArgument(10, Long.class));
    }

    @Test
    void convertArgument_convertsUuidAndDuration() {
        UUID id = UUID.fromString("6b1e3e4a-8f2a-4c3e-9d1b-2a3b4c5d6e7f");

        assertEquals(id, SoapInvoker.convertArgument(id.toString(), UUID.class));
        assertEquals(Duration.ofSeconds(1), SoapInvoker.convertArgument("PT1S", Duration.class));
    }

    @Test
    void convertArgument_convertsStringToBinaryArrays() {
        byte[] expected = "hi".getBytes(StandardCharsets.UTF_8);

        assertArrayEquals(expected, assertInstanceOf(byte[].class, SoapInvoker.convertArgument("hi", byte[].class)));

        Byte[] boxed = assertInstanceOf(Byte[].class, SoapInvoker.convertArgument("hi", Byte[].class));
        assertEquals(expected.length, boxed.length);
        for (int index = 0; index < expected.length; index++) {
            assertEquals(expected[index], boxed[index]);
        }
    }

    @Test
    void convertArgument_convertsNumberListToByteArray() {
        Object converted = SoapInvoker.convertArgument(List.of(1, 2), byte[].class);

        assertArrayEquals(new byte[]{1, 2}, assertInstanceOf(byte[].class, converted));
    }

    @Test
    void convertArgument_returnsNullAndAlreadyTypedValue() {
        assertNull(SoapInvoker.convertArgument(null, Integer.class));

        String text = "contract";
        assertSame(text, SoapInvoker.convertArgument(text, String.class));

        SimpleQueryParams params = new SimpleQueryParams();
        assertSame(params, SoapInvoker.convertArgument(params, SimpleQueryParams.class));
    }

    @Test
    void convertArgument_returnsOriginalStringWhenBeanHasNoConverter() {
        String query = "contract";

        assertSame(query, SoapInvoker.convertArgument(query, SimpleQueryParams.class));
    }

    @Test
    void convertArgument_convertsRepeatingScalarElements() {
        Object converted = SoapInvoker.convertArgument(List.of("1", "2"), Integer.class, true);

        assertEquals(List.of(1, 2), converted);
        assertEquals(List.of(7), SoapInvoker.convertArgument("7", Integer.class, true));
    }

    public enum SampleColor {
        RED, BLUE
    }

    public static class JaxbMapParams {

        private List<MapEntryStringString> entry;

        public List<MapEntryStringString> getEntry() {
            return entry;
        }

        public void setEntry(List<MapEntryStringString> entry) {
            this.entry = entry;
        }
    }

    public static class SimpleQueryParams {

        private String query;
        private int limit;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }
    }

    public static class DtoDocumentQueryParams {

        private java.util.List<DtoAttributeRestriction> query;

        public java.util.List<DtoAttributeRestriction> getQuery() {
            return query;
        }

        public void setQuery(java.util.List<DtoAttributeRestriction> query) {
            this.query = query;
        }
    }

    public static class DtoAttributeRestriction {

        private java.util.List<String> valueList;

        public java.util.List<String> getValueList() {
            return valueList;
        }

        public void setValueList(java.util.List<String> valueList) {
            this.valueList = valueList;
        }
    }

    public static class MapEntryStringString {

        private String key;
        private String value;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
