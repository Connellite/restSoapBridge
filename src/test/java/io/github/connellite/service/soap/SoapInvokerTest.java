package io.github.connellite.service.soap;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
