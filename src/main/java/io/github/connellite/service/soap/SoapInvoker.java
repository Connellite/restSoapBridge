package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.correlation.CorrelationId;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.exception.SoapFaultException;
import io.github.connellite.exception.TypeCoercionException;
import io.github.connellite.mapper.path.SoapArgument;
import io.github.connellite.mapper.path.SoapJsonMapper;
import io.github.connellite.util.TypeCoercionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.cxf.endpoint.Client;
import org.apache.cxf.headers.Header;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.message.Message;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.service.model.MessagePartInfo;
import org.apache.ws.commons.schema.XmlSchemaElement;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.w3c.dom.Element;

import javax.xml.namespace.QName;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SoapInvoker {

    private static final ObjectMapper OBJECT_MAPPER = SoapJsonMapper.instance();

    private static final ConversionService CONVERSION_SERVICE = ApplicationConversionService.getSharedInstance();

    private static final Object NOT_COERCED = new Object();

    private final SoapClientPool clientPool;
    private final BridgeProperties bridgeProperties;

    public Object invoke(String wsdlUrl, String operation, List<SoapArgument> arguments) {
        return invoke(wsdlUrl, operation, arguments, Map.of());
    }

    /**
     * @param backendHeaders HTTP headers for the SOAP request, in addition to the correlation id
     */
    public Object invoke(String wsdlUrl, String operation, List<SoapArgument> arguments,
                         Map<String, List<String>> backendHeaders) {
        Client client = clientPool.getClient(wsdlUrl);
        Object[] params = convertArguments(client, operation, arguments);
        log.debug("Invoking SOAP operation '{}' with {} parameter(s)", operation, params.length);
        try {
            // Per-call request context: the pooled client is shared between threads.
            Map<String, Object> invocationContext = new HashMap<>();
            invocationContext.put(Client.REQUEST_CONTEXT,
                    requestContext(client.getRequestContext(), backendHeaders, CorrelationId.current()));
            Object[] response = client.invoke(invocableOperation(client, operation), params, invocationContext);
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

    /**
     * Same resolution as {@code Client#invoke(String, Object...)}: unwrapped (document/literal wrapped) when possible.
     */
    private BindingOperationInfo invocableOperation(Client client, String operation) {
        BindingOperationInfo operationInfo = findOperation(client, operation);
        if (operationInfo == null) {
            throw new IllegalArgumentException("SOAP operation '" + operation + "' not found in "
                    + client.getEndpoint().getEndpointInfo().getName());
        }
        return operationInfo.isUnwrappedCapable() ? operationInfo.getUnwrappedOperation() : operationInfo;
    }

    Map<String, Object> requestContext(Map<String, Object> defaults, Map<String, List<String>> backendHeaders,
                                       String correlationId) {
        Map<String, Object> context = new HashMap<>(defaults);
        BridgeProperties.Correlation correlation = bridgeProperties.getCorrelation();

        Map<String, List<String>> httpHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        httpHeaders.putAll(backendHeaders);
        if (correlationId != null && correlation.isForwardToBackend()) {
            httpHeaders.put(correlation.getHeaderName(), List.of(correlationId));
        }
        if (!httpHeaders.isEmpty()) {
            context.put(Message.PROTOCOL_HEADERS, httpHeaders);
        }

        if (correlationId != null && correlation.getSoapHeader().isEnabled()) {
            List<Header> soapHeaders = new ArrayList<>();
            soapHeaders.add(correlationSoapHeader(correlation.getSoapHeader(), correlationId));
            context.put(Header.HEADER_LIST, soapHeaders);
        }
        return context;
    }

    private static Header correlationSoapHeader(BridgeProperties.SoapHeader soapHeader, String correlationId) {
        QName name = new QName(soapHeader.getNamespace(), soapHeader.getLocalName());
        Element element = DOMUtils.createDocument().createElementNS(name.getNamespaceURI(), name.getLocalPart());
        element.setTextContent(correlationId);
        // No data binding: SoapOutInterceptor copies DOM header elements as is.
        return new Header(name, element);
    }

    /**
     * A value that cannot become the parameter type is a client error: report it as 400 here instead of
     * letting CXF fail with a ClassCastException while building the wrapper (502).
     */
    private Object[] convertArguments(Client client, String operation, List<SoapArgument> arguments) {
        List<ParameterDescriptor> parameters = parameters(client, operation, arguments.size());
        Object[] params = new Object[arguments.size()];
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            SoapArgument argument = arguments.get(index);
            ParameterDescriptor parameter = index < parameters.size() ? parameters.get(index) : ParameterDescriptor.unknown();
            try {
                params[index] = convertArgument(argument.value(), parameter);
            } catch (IllegalArgumentException exception) {
                errors.add(conversionError(argument, parameter, NestedExceptionUtils.getMostSpecificCause(exception).getMessage()));
                continue;
            }
            if (!isAcceptable(params[index], parameter)) {
                errors.add(conversionError(argument, parameter, null));
            }
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        return params;
    }

    private static boolean isAcceptable(Object converted, ParameterDescriptor parameter) {
        // Repeating parameters are passed as List whatever the declared type is.
        return converted == null || parameter.targetType() == null || parameter.repeating()
                || ClassUtils.isAssignableValue(parameter.targetType(), converted);
    }

    private static String conversionError(SoapArgument argument, ParameterDescriptor parameter, String reason) {
        String message = "$." + argument.name() + ": " + jsonType(argument.value())
                + " cannot be converted to " + parameter.targetType().getSimpleName();
        return reason != null ? message + " (" + reason + ")" : message;
    }

    private static String jsonType(Object value) {
        if (value instanceof Map<?, ?>) {
            return "object";
        }
        if (value instanceof List<?>) {
            return "array";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        return "string";
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
        return convertValue(value, targetType);
    }

    private static Object convertValue(Object value, Class<?> targetType) {
        Object coerced = tryCoerce(value, targetType);
        if (coerced != NOT_COERCED) {
            return coerced;
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
        if (isSimpleType(targetType) || isByteArray(targetType)) {
            return OBJECT_MAPPER.convertValue(value, targetType);
        }
        return value;
    }

    private static Object tryCoerce(Object value, Class<?> targetType) {
        try {
            Object coerced = TypeCoercionUtil.coerce(value, targetType);
            return coerced == null ? NOT_COERCED : coerced;
        } catch (TypeCoercionException exception) {
            return NOT_COERCED;
        }
    }

    private static boolean isByteArray(Class<?> targetType) {
        return targetType == byte[].class || targetType == Byte[].class;
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
        if (value == null || elementType == null || elementType == Object.class) {
            return value;
        }
        return convertValue(value, elementType);
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
