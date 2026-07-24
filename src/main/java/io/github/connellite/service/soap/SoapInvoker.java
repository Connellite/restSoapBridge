package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.connellite.exception.SoapFaultException;
import io.github.connellite.mapper.path.SoapArgument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.service.model.MessagePartInfo;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class SoapInvoker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

    private static final ConversionService CONVERSION_SERVICE = ApplicationConversionService.getSharedInstance();

    private final SoapClientPool clientPool;

    public Object invoke(String wsdlUrl, String operation, List<SoapArgument> arguments) {
        Client client = clientPool.getClient(wsdlUrl);
        Object[] params = convertArguments(client, operation, arguments);
        log.debug("Invoking SOAP operation '{}' with {} parameter(s)", operation, params.length);
        try {
            Object[] response = client.invoke(operation, params);
            if (response == null) {
                return null;
            }
            if (response.length == 1) {
                return response[0];
            }
            return response;
        } catch (Exception exception) {
            throw SoapFaultException.from(exception);
        }
    }

    private Object[] convertArguments(Client client, String operation, List<SoapArgument> arguments) {
        List<ParameterDescriptor> parameters = parameters(client, operation, arguments.size());
        Object[] params = new Object[arguments.size()];
        for (int index = 0; index < arguments.size(); index++) {
            Object value = arguments.get(index).value();
            ParameterDescriptor parameter = index < parameters.size() ? parameters.get(index) : ParameterDescriptor.unknown();
            params[index] = convertArgument(value, parameter);
        }
        return params;
    }

    private List<ParameterDescriptor> parameters(Client client, String operation, int argumentCount) {
        BindingOperationInfo operationInfo = findOperation(client, operation);
        if (operationInfo == null) {
            return List.of();
        }

        if (operationInfo.isUnwrappedCapable()) {
            List<ParameterDescriptor> unwrappedParameters = inputParameters(operationInfo.getUnwrappedOperation());
            if (unwrappedParameters.size() == argumentCount) {
                return unwrappedParameters;
            }
        }

        List<ParameterDescriptor> wrappedParameters = inputParameters(operationInfo);
        if (wrappedParameters.size() == argumentCount) {
            return wrappedParameters;
        }
        return List.of();
    }

    private BindingOperationInfo findOperation(Client client, String operation) {
        return client.getEndpoint().getEndpointInfo().getBinding().getOperations().stream()
                .filter(bindingOperation -> operation.equals(bindingOperation.getName().getLocalPart()))
                .findFirst()
                .orElse(null);
    }

    private List<ParameterDescriptor> inputParameters(BindingOperationInfo operationInfo) {
        if (operationInfo == null || operationInfo.getInput() == null) {
            return List.of();
        }
        List<ParameterDescriptor> parameters = new ArrayList<>();
        for (MessagePartInfo part : operationInfo.getInput().getMessageParts()) {
            parameters.add(parameter(part));
        }
        return parameters.stream().filter(Objects::nonNull).toList();
    }

    private ParameterDescriptor parameter(MessagePartInfo part) {
        Class<?> targetType = part.getTypeClass();
        if (targetType == null) {
            return null;
        }
        return new ParameterDescriptor(targetType, isRepeating(part, targetType));
    }

    private boolean isRepeating(MessagePartInfo part, Class<?> targetType) {
        if (part.getXmlSchema() instanceof XmlSchemaElement element && element.getMaxOccurs() > 1) {
            return true;
        }
        if (!targetType.isArray()) {
            return false;
        }
        Class<?> componentType = targetType.getComponentType();
        if (componentType == null || componentType.isPrimitive()) {
            return false;
        }
        return targetType != Byte[].class;
    }

    static Object convertArgument(Object value, Class<?> targetType) {
        return convertArgument(value, new ParameterDescriptor(targetType, false));
    }

    static Object convertArgument(Object value, Class<?> targetType, boolean repeating) {
        return convertArgument(value, new ParameterDescriptor(targetType, repeating));
    }

    private static Object convertArgument(Object value, ParameterDescriptor parameter) {
        Class<?> targetType = parameter.targetType();
        if (value == null || targetType == null) {
            return value;
        }
        if (parameter.repeating()) {
            return convertRepeatingArgument(value, targetType);
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        if (isBinaryArray(targetType)) {
            return convertBinaryArgument(value, targetType);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            if (isJaxbMapWrapper(targetType)) {
                return convertToJaxbMapWrapper(value, targetType);
            }
            return OBJECT_MAPPER.convertValue(value, targetType);
        }
        if (CONVERSION_SERVICE.canConvert(value.getClass(), targetType)) {
            return CONVERSION_SERVICE.convert(value, targetType);
        }
        if (isSimpleType(targetType)) {
            return OBJECT_MAPPER.convertValue(value, targetType);
        }
        return value;
    }

    /**
     * CXF/JAXB represents SOAP maps as a wrapper bean with repeating {@code entry} items.
     * REST clients may send a JSON object, an entry array, or {@code {entry:[...]}}.
     */
    private static boolean isJaxbMapWrapper(Class<?> targetType) {
        try {
            Class<?> entryType = targetType.getMethod("getEntry").getReturnType();
            return List.class.isAssignableFrom(entryType) || entryType.isArray();
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    private static Object convertToJaxbMapWrapper(Object value, Class<?> targetType) {
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("entry", normalizeMapEntries(value));
        return OBJECT_MAPPER.convertValue(wrapper, targetType);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> normalizeMapEntries(Object value) {
        if (value instanceof List<?> list) {
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    entries.add(toEntryMap((Map<String, Object>) map));
                }
            }
            return entries;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.containsKey("entry")) {
                return normalizeMapEntries(map.get("entry"));
            }
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", entry.getKey() == null ? null : String.valueOf(entry.getKey()));
                item.put("value", entry.getValue() == null ? null : String.valueOf(entry.getValue()));
                entries.add(item);
            }
            return entries;
        }
        return List.of();
    }

    private static Map<String, Object> toEntryMap(Map<String, Object> source) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", source.get("key"));
        entry.put("value", source.get("value"));
        return entry;
    }

    private static boolean isBinaryArray(Class<?> targetType) {
        return targetType == byte[].class || targetType == Byte[].class;
    }

    private static Object convertBinaryArgument(Object value, Class<?> targetType) {
        if (value instanceof String text) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (targetType == byte[].class) {
                return bytes;
            }
            Byte[] boxed = new Byte[bytes.length];
            for (int index = 0; index < bytes.length; index++) {
                boxed[index] = bytes[index];
            }
            return boxed;
        }
        return OBJECT_MAPPER.convertValue(value, targetType);
    }

    private static List<?> convertRepeatingArgument(Object value, Class<?> targetType) {
        Class<?> elementType = targetType.isArray()
                ? targetType.getComponentType()
                : (List.class.isAssignableFrom(targetType) ? Object.class : targetType);
        if (value instanceof List<?> values) {
            return values.stream()
                    .map(item -> convertListItem(item, elementType))
                    .toList();
        }
        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                values.add(convertListItem(Array.get(value, index), elementType));
            }
            return values;
        }
        return List.of(convertListItem(value, elementType));
    }

    private static Object convertListItem(Object value, Class<?> elementType) {
        if (value == null || elementType == null || elementType == Object.class || elementType.isInstance(value)) {
            return value;
        }
        if (CONVERSION_SERVICE.canConvert(value.getClass(), elementType)) {
            return CONVERSION_SERVICE.convert(value, elementType);
        }
        if (value instanceof Map<?, ?> || value instanceof List<?> || isSimpleType(elementType)) {
            return OBJECT_MAPPER.convertValue(value, elementType);
        }
        return value;
    }

    private static boolean isSimpleType(Class<?> targetType) {
        return targetType.isPrimitive()
                || Number.class.isAssignableFrom(targetType)
                || CharSequence.class.isAssignableFrom(targetType)
                || Boolean.class == targetType
                || Character.class == targetType
                || Enum.class.isAssignableFrom(targetType);
    }

    private record ParameterDescriptor(Class<?> targetType, boolean repeating) {

        private static ParameterDescriptor unknown() {
            return new ParameterDescriptor(null, false);
        }
    }
}
