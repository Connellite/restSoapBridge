package io.github.connellite.mapper.path;

import lombok.experimental.UtilityClass;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.context.expression.MapAccessor;
import org.springframework.expression.AccessException;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.PropertyAccessor;
import org.springframework.expression.TypedValue;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.ReflectivePropertyAccessor;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.LinkedCaseInsensitiveMap;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@UtilityClass
public class SoapPathResolver {

    private static final ExpressionParser SPEL = new SpelExpressionParser();
    private static final Map<String, Expression> EXPRESSIONS = new ConcurrentHashMap<>();
    // XML-name accessor first: JAXB "class" must resolve to the clazz field, not Object#getClass().
    private static final List<PropertyAccessor> PROPERTY_ACCESSORS = List.of(
            new XmlNamePropertyAccessor(),
            new MapAccessor(),
            new SyntheticSoapWrapperAccessor(),
            new ReflectivePropertyAccessor()
    );

    public static Object read(Object soapResult, String spelExpression) {
        if (soapResult == null) {
            return null;
        }
        Object root = unwrapSoapContainer(soapResult, spelExpression);
        StandardEvaluationContext context = new StandardEvaluationContext(root);
        context.setPropertyAccessors(new ArrayList<>(PROPERTY_ACCESSORS));
        try {
            return EXPRESSIONS.computeIfAbsent(spelExpression, SPEL::parseExpression).getValue(context);
        } catch (EvaluationException exception) {
            // Optional nested fields (e.g. null Map) must not fail the whole REST response.
            if (isMissingOptionalProperty(exception)) {
                return null;
            }
            throw new IllegalStateException(
                    "Cannot evaluate SpEL '" + spelExpression + "' on " + root.getClass().getName(),
                    exception);
        }
    }

    private static boolean isMissingOptionalProperty(EvaluationException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("cannot be found on null");
    }

    /**
     * CXF occasionally wraps a scalar payload in a singleton array/list.
     * Do not unwrap when the mapping wants the root payload itself ({@code #root}):
     * a singleton {@code List} of map entries must stay an array in JSON.
     */
    private static Object unwrapSoapContainer(Object soapResult, String spelExpression) {
        if (isRootPayloadExpression(spelExpression) && isArrayOrCollection(soapResult)) {
            return soapResult;
        }
        if (soapResult.getClass().isArray()) {
            int length = Array.getLength(soapResult);
            if (length == 1) {
                return unwrapSoapContainer(Array.get(soapResult, 0), spelExpression);
            }
        }
        if (soapResult instanceof Collection<?> collection && collection.size() == 1) {
            return unwrapSoapContainer(collection.iterator().next(), spelExpression);
        }
        return soapResult;
    }

    private static boolean isRootPayloadExpression(String spelExpression) {
        return spelExpression != null && "#root".equals(spelExpression.trim());
    }

    private static boolean isArrayOrCollection(Object value) {
        return value != null && (value.getClass().isArray() || value instanceof Collection<?>);
    }

    @SuppressWarnings("NullableProblems")
    private static final class SyntheticSoapWrapperAccessor implements PropertyAccessor {

        private static final Set<String> WRAPPER_FIELDS = Collections.newSetFromMap(new LinkedCaseInsensitiveMap<>());

        static {
            WRAPPER_FIELDS.add("return");
            WRAPPER_FIELDS.add("result");
        }

        @Override
        public Class<?>[] getSpecificTargetClasses() {
            return null;
        }

        @Override
        public boolean canRead(EvaluationContext context, Object target, String name) {
            return target != null
                    && !(target instanceof Map<?, ?>)
                    && WRAPPER_FIELDS.contains(normalizeWrapperFieldName(name))
                    && !new BeanWrapperImpl(target).isReadableProperty(name);
        }

        @Override
        public TypedValue read(EvaluationContext context, Object target, String name) throws AccessException {
            if (!canRead(context, target, name)) {
                throw new AccessException("Synthetic SOAP wrapper field is not readable: " + name);
            }
            return new TypedValue(target);
        }

        @Override
        public boolean canWrite(EvaluationContext context, Object target, String name) {
            return false;
        }

        @Override
        public void write(EvaluationContext context, Object target, String name, Object newValue) throws AccessException {
            throw new AccessException("Synthetic SOAP wrapper fields are read-only");
        }

        private String normalizeWrapperFieldName(String name) {
            if (name == null || name.isBlank()) {
                return "";
            }

            int start = 0;
            int end = name.length();
            while (start < end && !Character.isLetterOrDigit(name.charAt(start))) {
                start++;
            }
            while (end > start && !Character.isLetterOrDigit(name.charAt(end - 1))) {
                end--;
            }
            return name.substring(start, end);
        }
    }
}
