package io.github.connellite.mapper.path;

import lombok.experimental.UtilityClass;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JAXB shapes of key/value data:
 * <ul>
 *     <li><b>entry type</b> — a bean with exactly {@code key} and {@code value} (e.g. {@code MapEntryStringString});
 *     {@code List<entry>} is how attribute lists are modelled;</li>
 *     <li><b>map wrapper</b> — a bean whose only property is {@code List<entry> entry}: JAXB/CXF {@code java.util.Map}
 *     (e.g. {@code Call$Params}).</li>
 * </ul>
 * REST clients send both as a plain JSON object ({@code {"a": "1"}}) or as an array of {@code {key, value}}.
 */
@UtilityClass
public class JaxbMaps {

    private static final Set<String> ENTRY_FIELDS = Set.of("key", "value");
    private static final Map<Class<?>, Boolean> ENTRY_TYPES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Field>> MAP_WRAPPER_ENTRY_FIELDS = new ConcurrentHashMap<>();

    public static boolean isEntryType(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isArray() || type.isInterface()
                || type.getName().startsWith("java.")) {
            return false;
        }
        return ENTRY_TYPES.computeIfAbsent(type, candidate -> {
            List<Field> fields = instanceFields(candidate);
            return fields.size() == 2 && fields.stream().map(Field::getName).allMatch(ENTRY_FIELDS::contains);
        });
    }

    public static boolean isMapWrapper(Class<?> type) {
        return entryField(type) != null;
    }

    /**
     * Entries of a JAXB map wrapper as an ordered map; {@code null} wrapper or entry list gives an empty map.
     */
    public static Map<String, Object> wrapperToMap(Object wrapper) {
        Map<String, Object> result = new LinkedHashMap<>();
        Field entryField = wrapper != null ? entryField(wrapper.getClass()) : null;
        if (entryField == null) {
            return result;
        }
        Object entries = read(entryField, wrapper);
        if (!(entries instanceof Collection<?> collection)) {
            return result;
        }
        for (Object entry : collection) {
            if (entry == null) {
                continue;
            }
            Object key = read(field(entry.getClass(), "key"), entry);
            if (key != null) {
                result.put(String.valueOf(key), read(field(entry.getClass(), "value"), entry));
            }
        }
        return result;
    }

    /**
     * Normalizes REST input to JAXB entries {@code [{key, value}, ...]}.
     * Accepts a plain object, {@code {"entry": [...]}} and an array of entries. Keys are strings, blank keys are
     * dropped, scalar values become strings (the JAXB entry value type decides the final conversion).
     *
     * @throws IllegalArgumentException when non-empty input yields no entries
     */
    public static List<Map<String, Object>> toEntries(Object value) {
        List<Map<String, Object>> entries = new ArrayList<>();
        boolean nonEmptyInput = false;
        if (value instanceof Map<?, ?> map && map.containsKey("entry") && map.size() == 1) {
            return toEntries(map.get("entry"));
        }
        if (value instanceof Map<?, ?> map) {
            nonEmptyInput = !map.isEmpty();
            for (Map.Entry<?, ?> pair : map.entrySet()) {
                addEntry(entries, pair.getKey(), pair.getValue());
            }
        } else if (value instanceof Collection<?> items) {
            nonEmptyInput = !items.isEmpty();
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry) {
                    addEntry(entries, entry.get("key"), entry.get("value"));
                }
            }
        } else if (value != null) {
            throw new IllegalArgumentException("expected an object of key/value pairs or an array of {key, value} entries");
        }
        if (nonEmptyInput && entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "no key/value entries: send {\"name\": \"value\", ...} or [{\"key\": \"name\", \"value\": \"value\"}]");
        }
        return entries;
    }

    /**
     * True when a JSON object already looks like a single entry ({@code {"key": ..., "value": ...}}).
     */
    public static boolean isEntryShaped(Map<?, ?> object) {
        return object.containsKey("key") && ENTRY_FIELDS.containsAll(object.keySet());
    }

    private static void addEntry(List<Map<String, Object>> entries, Object key, Object value) {
        if (key == null || String.valueOf(key).isBlank()) {
            return;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", String.valueOf(key));
        entry.put("value", isScalar(value) ? String.valueOf(value) : value);
        entries.add(entry);
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence || value instanceof Number || value instanceof Boolean
                || value instanceof Character || value instanceof Enum<?>;
    }

    private static Field entryField(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isArray() || type.isInterface()
                || type.getName().startsWith("java.")) {
            return null;
        }
        return MAP_WRAPPER_ENTRY_FIELDS.computeIfAbsent(type, candidate -> {
            List<Field> fields = instanceFields(candidate);
            if (fields.size() != 1 || !fields.get(0).getName().equals("entry")) {
                return Optional.empty();
            }
            Field entry = fields.get(0);
            return List.class.isAssignableFrom(entry.getType()) && isEntryType(listElementType(entry.getGenericType()))
                    ? Optional.of(entry)
                    : Optional.empty();
        }).orElse(null);
    }

    /**
     * Element class of {@code List<E>}, or {@code null} when it is not a parameterized list.
     */
    public static Class<?> listElementType(Type type) {
        if (type instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 1
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        return null;
    }

    private static Field field(Class<?> type, String name) {
        for (Field field : instanceFields(type)) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }

    private static Object read(Field field, Object target) {
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot read " + field, exception);
        }
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic() && field.trySetAccessible()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }
}
