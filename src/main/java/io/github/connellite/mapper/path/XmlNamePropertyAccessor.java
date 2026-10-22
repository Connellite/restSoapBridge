package io.github.connellite.mapper.path;

#if SPRING_BOOT_3
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementRef;
#else
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElementRef;
#endif
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads JAXB bean properties by their XML name, e.g. {@code class} for
 * {@code @XmlElement(name = "class") protected String clazz}. Generated SpEL paths use XSD names,
 * which differ from Java property names whenever the XML name is a Java keyword or not a valid identifier.
 */
@SuppressWarnings("NullableProblems")
final class XmlNamePropertyAccessor implements PropertyAccessor {

    private static final String DEFAULT_NAME = "##default";
    private static final Map<Class<?>, Map<String, Field>> FIELDS_BY_XML_NAME = new ConcurrentHashMap<>();

    @Override
    public Class<?>[] getSpecificTargetClasses() {
        return null;
    }

    @Override
    public boolean canRead(EvaluationContext context, Object target, String name) {
        return target != null && !(target instanceof Map<?, ?>) && field(target.getClass(), name) != null;
    }

    @Override
    public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
        Field field = target != null ? field(target.getClass(), name) : null;
        if (field == null) {
            throw new AccessException("No JAXB property with XML name '" + name + "'");
        }
        try {
            return new TypedValue(field.get(target));
        } catch (IllegalAccessException exception) {
            throw new AccessException("Cannot read JAXB property '" + name + "'", exception);
        }
    }

    @Override
    public boolean canWrite(EvaluationContext context, Object target, String name) {
        return false;
    }

    @Override
    public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
        throw new AccessException("JAXB properties are read-only in response mappings");
    }

    private static Field field(Class<?> type, String xmlName) {
        return FIELDS_BY_XML_NAME.computeIfAbsent(type, XmlNamePropertyAccessor::indexFields).get(xmlName);
    }

    /**
     * Indexes only fields whose XML name differs from the Java field name; everything else is
     * handled by the regular reflective accessor. Subclass fields win over inherited ones.
     */
    private static Map<String, Field> indexFields(Class<?> type) {
        Map<String, Field> fields = new HashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String xmlName = xmlName(field);
                if (xmlName != null && !xmlName.equals(field.getName()) && field.trySetAccessible()) {
                    fields.putIfAbsent(xmlName, field);
                }
            }
        }
        return Map.copyOf(fields);
    }

    private static String xmlName(Field field) {
        XmlElement element = field.getAnnotation(XmlElement.class);
        if (element != null && !DEFAULT_NAME.equals(element.name())) {
            return element.name();
        }
        XmlAttribute attribute = field.getAnnotation(XmlAttribute.class);
        if (attribute != null && !DEFAULT_NAME.equals(attribute.name())) {
            return attribute.name();
        }
        XmlElementRef elementRef = field.getAnnotation(XmlElementRef.class);
        if (elementRef != null && !DEFAULT_NAME.equals(elementRef.name())) {
            return elementRef.name();
        }
        return null;
    }
}
