package io.github.connellite.service.soap;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.connellite.config.BridgeProperties;
import io.github.connellite.correlation.CorrelationId;
import io.github.connellite.exception.RequestValidationException;
import io.github.connellite.exception.BackendFailures;
import io.github.connellite.exception.UpstreamException;
import io.github.connellite.exception.TypeCoercionException;
import io.github.connellite.mapper.path.JaxbMaps;
import io.github.connellite.mapper.path.SoapArgument;
import io.github.connellite.mapper.path.SoapJsonMapper;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.util.TypeCoercionUtil;
#if SPRING_BOOT_3
import jakarta.xml.bind.annotation.XmlElement;
#else
import javax.xml.bind.annotation.XmlElement;
#endif
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
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        MappingDefinition.SoapTarget target = new MappingDefinition.SoapTarget();
        target.setWsdl(wsdlUrl);
        target.setOperation(operation);
        return invoke(target, arguments, List.of(), backendHeaders);
    }

    /**
     * @param headerArguments values of the SOAP header parts the WSDL binding declares, by part name
     * @param backendHeaders  HTTP headers for the SOAP request, in addition to the correlation id
     */
    public Object invoke(MappingDefinition.SoapTarget target, List<SoapArgument> arguments,
                         List<SoapArgument> headerArguments, Map<String, List<String>> backendHeaders) {
        String operation = target.getOperation();
        Client client = client(target.getWsdl(), target.getEndpointAddress());
        BindingOperationInfo operationInfo = findOperation(client, operation);
        if (operationInfo == null) {
            throw UpstreamException.contractMismatch("SOAP operation '" + operation + "' not found in "
                    + client.getEndpoint().getEndpointInfo().getName(), null);
        }
        // Same resolution as Client#invoke(String, Object...): unwrapped (document/literal wrapped) when possible.
        BindingOperationInfo invocable = operationInfo.isUnwrappedCapable()
                ? operationInfo.getUnwrappedOperation()
                : operationInfo;
        Object[] params = convertArguments(operationInfo, invocable, arguments, headerArguments);
        try {
            // Per-call request context: the pooled client is shared between threads.
            Map<String, Object> invocationContext = new HashMap<>();
            invocationContext.put(Client.REQUEST_CONTEXT,
                    requestContext(client.getRequestContext(), backendHeaders, CorrelationId.current()));
            Object[] response = client.invoke(invocable, params, invocationContext);
            if (response == null) {
                return null;
            }
            if (response.length == 1) {
                return operationInfo.isUnwrappedCapable() ? response[0] : unwrapResponse(operation, operationInfo, response[0]);
            }
            return response;
        } catch (Exception exception) {
            throw BackendFailures.classify(exception, bridgeProperties.getErrors().getFaultStatus());
        }
    }

    private Client client(String wsdlUrl, String endpointAddress) {
        try {
            return clientPool.getClient(wsdlUrl, endpointAddress);
        } catch (RuntimeException exception) {
            throw BackendFailures.clientCreation(wsdlUrl, exception);
        }
    }

    /**
     * An operation CXF cannot unwrap returns the response element bean ({@code fooResponse{return}}) instead of
     * its payload. Mapping generation strips such a single-child {@code <operation>Response} wrapper, so strip it
     * here as well: response paths then see the same shape for wrapped and unwrapped operations.
     */
    static Object unwrapResponse(String operation, BindingOperationInfo operationInfo, Object response) {
        if (response == null || operationInfo.getOutput() == null
                || operationInfo.getOutput().getMessageParts().size() != 1) {
            return response;
        }
        MessagePartInfo part = operationInfo.getOutput().getMessageParts().get(0);
        String elementName = part.getConcreteName() != null ? part.getConcreteName().getLocalPart() : partName(part);
        if (!elementName.equalsIgnoreCase(operation + "Response")) {
            return response;
        }
        return singleFieldValue(response);
    }

    private static Object singleFieldValue(Object bean) {
        if (bean.getClass().getName().startsWith("java.")) {
            return bean;
        }
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = bean.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        if (fields.size() != 1 || !fields.get(0).trySetAccessible()) {
            return bean;
        }
        try {
            return fields.get(0).get(bean);
        } catch (IllegalAccessException exception) {
            return bean;
        }
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
     * Builds {@code Object[]} in CXF part order, taking each value by the part's element name
     * (JSON order and XSD index order do not matter). A value that cannot become the part type, or a
     * non-null argument without a matching part, is a client error: 400 instead of a ClassCastException
     * inside CXF (502) or a value silently landing in the wrong slot.
     */
    static Object[] convertArguments(BindingOperationInfo operationInfo, BindingOperationInfo invocable,
                                     List<SoapArgument> arguments) {
        return convertArguments(operationInfo, invocable, arguments, List.of());
    }

    /**
     * Body parts come from the binding message; SOAP header parts are the other parts of the operation message
     * (explicit headers) and its out-of-band parts (implicit headers). CXF takes every part from the parameter
     * array at the part's index and writes header parts into the SOAP header.
     */
    static Object[] convertArguments(BindingOperationInfo operationInfo, BindingOperationInfo invocable,
                                     List<SoapArgument> arguments, List<SoapArgument> headerArguments) {
        List<MessagePartInfo> parts = invocable.getInput() != null ? invocable.getInput().getMessageParts() : List.of();
        List<MessagePartInfo> headerParts = headerParts(operationInfo, invocable);
        List<SoapArgument> effectiveArguments = arguments;
        if (!operationInfo.isUnwrappedCapable() && parts.size() == 1 && !hasArgumentFor(arguments, parts.get(0))) {
            // Request element CXF cannot unwrap: the single part is the request bean, built from all fields.
            effectiveArguments = List.of(new SoapArgument(partName(parts.get(0)), argumentsAsMap(arguments)));
        }

        Map<String, SoapArgument> argumentsByName = new LinkedHashMap<>();
        for (SoapArgument argument : effectiveArguments) {
            argumentsByName.put(normalizeName(argument.name()), argument);
        }
        Class<?> requestWrapper = operationInfo.isUnwrappedCapable() ? requestWrapperClass(operationInfo) : null;

        Object[] params = new Object[parameterCount(parts, headerParts)];
        List<String> errors = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            MessagePartInfo part = parts.get(index);
            SoapArgument argument = argumentsByName.remove(normalizeName(partName(part)));
            if (argument != null) {
                convertPart(argument, parameter(part, requestWrapper), parameterIndex(part, index, headerParts),
                        params, errors);
            }
        }

        Map<String, SoapArgument> headersByName = new LinkedHashMap<>();
        for (SoapArgument argument : headerArguments) {
            headersByName.put(normalizeName(argument.name()), argument);
        }
        for (MessagePartInfo part : headerParts) {
            SoapArgument argument = headersByName.remove(normalizeName(partName(part)));
            if (argument != null) {
                convertPart(argument, parameter(part, null), part.getIndex(), params, errors);
            }
        }
        List<String> unknownHeaders = headersByName.values().stream()
                .filter(argument -> argument.value() != null)
                .map(SoapArgument::name)
                .toList();
        if (!unknownHeaders.isEmpty()) {
            errors.add("Unknown SOAP header(s) " + unknownHeaders + " for SOAP operation '"
                    + operationInfo.getName().getLocalPart() + "'; expected "
                    + headerParts.stream().map(SoapInvoker::partName).toList());
        }

        // Mappings list every XSD field, so absent (null) extras are harmless; a sent value must find its part.
        List<String> unknown = argumentsByName.values().stream()
                .filter(argument -> argument.value() != null)
                .map(SoapArgument::name)
                .toList();
        if (!unknown.isEmpty()) {
            errors.add("Unknown parameter(s) " + unknown + " for SOAP operation '"
                    + operationInfo.getName().getLocalPart() + "'; expected "
                    + parts.stream().map(SoapInvoker::partName).toList());
        }
        if (!errors.isEmpty()) {
            throw new RequestValidationException(errors);
        }
        if (log.isDebugEnabled()) {
            log.debug("Invoking SOAP operation '{}' with {}", operationInfo.getName().getLocalPart(), describe(parts, params));
        }
        return params;
    }

    private static void convertPart(SoapArgument argument, ParameterDescriptor parameter, int index,
                                    Object[] params, List<String> errors) {
        try {
            params[index] = convertArgument(argument.value(), parameter);
        } catch (IllegalArgumentException exception) {
            errors.add(conversionError(argument, parameter, NestedExceptionUtils.getMostSpecificCause(exception).getMessage()));
            return;
        }
        if (!isAcceptable(params[index], parameter)) {
            errors.add(conversionError(argument, parameter, null));
        }
    }

    /**
     * Parts of the operation's input that the binding does not put into the body. An unwrapped operation may not
     * repeat the out-of-band (implicit header) parts of the wrapped one, so those are taken from both.
     */
    static List<MessagePartInfo> headerParts(BindingOperationInfo operationInfo, BindingOperationInfo invocable) {
        List<MessagePartInfo> bodyParts = invocable.getInput() != null ? invocable.getInput().getMessageParts() : List.of();
        List<MessagePartInfo> candidates = new ArrayList<>();
        if (invocable.getOperationInfo().getInput() != null) {
            candidates.addAll(invocable.getOperationInfo().getInput().getMessageParts());
            candidates.addAll(invocable.getOperationInfo().getInput().getOutOfBandParts());
        }
        if (invocable != operationInfo && operationInfo.getOperationInfo().getInput() != null) {
            candidates.addAll(operationInfo.getOperationInfo().getInput().getOutOfBandParts());
        }
        List<MessagePartInfo> headers = new ArrayList<>();
        for (MessagePartInfo part : candidates) {
            boolean known = bodyParts.stream().anyMatch(body -> body.getName().equals(part.getName()))
                    || headers.stream().anyMatch(header -> header.getName().equals(part.getName()));
            if (!known) {
                headers.add(part);
            }
        }
        return headers;
    }

    private static int parameterCount(List<MessagePartInfo> bodyParts, List<MessagePartInfo> headerParts) {
        int count = bodyParts.size();
        if (headerParts.isEmpty()) {
            return count;
        }
        for (MessagePartInfo part : bodyParts) {
            count = Math.max(count, part.getIndex() + 1);
        }
        for (MessagePartInfo part : headerParts) {
            count = Math.max(count, part.getIndex() + 1);
        }
        return count;
    }

    /** Without headers the body parts are the parameters in order; with headers every part sits at its index. */
    private static int parameterIndex(MessagePartInfo part, int position, List<MessagePartInfo> headerParts) {
        return headerParts.isEmpty() ? position : part.getIndex();
    }

    private static boolean hasArgumentFor(List<SoapArgument> arguments, MessagePartInfo part) {
        String name = normalizeName(partName(part));
        return arguments.stream().anyMatch(argument -> normalizeName(argument.name()).equals(name));
    }

    private static Map<String, Object> argumentsAsMap(List<SoapArgument> arguments) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (SoapArgument argument : arguments) {
            fields.put(argument.name(), argument.value());
        }
        return fields;
    }

    static String partName(MessagePartInfo part) {
        return part.getName().getLocalPart();
    }

    /**
     * JAXB escapes XML names that are Java keywords with a leading underscore ({@code _return}).
     */
    static String normalizeName(String name) {
        int start = 0;
        while (start < name.length() - 1 && name.charAt(start) == '_') {
            start++;
        }
        return name.substring(start);
    }

    /**
     * Request wrapper bean of a document/literal wrapped operation; its fields carry the generic element types
     * of repeating parts ({@code List<MapEntry> attrs}), which the unwrapped parts only expose as {@code List}.
     */
    private static Class<?> requestWrapperClass(BindingOperationInfo operationInfo) {
        if (operationInfo.getInput() == null || operationInfo.getInput().getMessageParts().size() != 1) {
            return null;
        }
        return operationInfo.getInput().getMessageParts().get(0).getTypeClass();
    }

    private static String describe(List<MessagePartInfo> parts, Object[] params) {
        StringBuilder description = new StringBuilder("{");
        for (int index = 0; index < parts.size(); index++) {
            if (index > 0) {
                description.append(", ");
            }
            Object value = params[index];
            description.append(partName(parts.get(index))).append('=')
                    .append(value == null ? "null" : value.getClass().getSimpleName() + ":" + abbreviate(value));
        }
        return description.append('}').toString();
    }

    private static String abbreviate(Object value) {
        String text;
        try {
            text = OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            text = String.valueOf(value);
        }
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
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

    static BindingOperationInfo findOperation(Client client, String operation) {
        return client.getEndpoint().getEndpointInfo().getBinding().getOperations().stream()
                .filter(bindingOperation -> operation.equals(bindingOperation.getName().getLocalPart()))
                .findFirst()
                .orElse(null);
    }

    private static ParameterDescriptor parameter(MessagePartInfo part, Class<?> requestWrapper) {
        Class<?> targetType = part.getTypeClass();
        if (targetType == null) {
            return ParameterDescriptor.unknown();
        }
        boolean repeating = isRepeating(part, targetType);
        Class<?> elementType = repeating && !targetType.isArray() && Collection.class.isAssignableFrom(targetType)
                ? wrapperFieldElementType(requestWrapper, partName(part))
                : null;
        return new ParameterDescriptor(targetType, repeating, elementType);
    }

    private static Class<?> wrapperFieldElementType(Class<?> requestWrapper, String partName) {
        for (Class<?> type = requestWrapper; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (normalizeName(field.getName()).equals(normalizeName(partName))
                        || partName.equals(xmlElementName(field))) {
                    return JaxbMaps.listElementType(field.getGenericType());
                }
            }
        }
        return null;
    }

    private static String xmlElementName(Field field) {
        XmlElement element = field.getAnnotation(XmlElement.class);
        return element != null ? element.name() : null;
    }

    private static boolean isRepeating(MessagePartInfo part, Class<?> targetType) {
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
            return convertRepeatingArgument(value, targetType, parameter.elementType());
        }
        return convertValue(value, targetType);
    }

    private static Object convertValue(Object value, Class<?> targetType) {
        Object coerced = tryCoerce(value, targetType);
        if (coerced != NOT_COERCED) {
            return coerced;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            // JAXB map wrappers and entry lists are reshaped by JaxbMapModule, at any depth.
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

    private static List<?> convertRepeatingArgument(Object value, Class<?> targetType, Class<?> knownElementType) {
        Class<?> elementType = knownElementType != null
                ? knownElementType
                : targetType.isArray()
                ? targetType.getComponentType()
                : (List.class.isAssignableFrom(targetType) ? Object.class : targetType);
        if (value instanceof Map<?, ?> map && JaxbMaps.isEntryType(elementType) && !JaxbMaps.isEntryShaped(map)) {
            // attrs: {"a": "1"} for List<MapEntry>
            value = JaxbMaps.toEntries(map);
        }
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

    private record ParameterDescriptor(Class<?> targetType, boolean repeating, Class<?> elementType) {

        private ParameterDescriptor(Class<?> targetType, boolean repeating) {
            this(targetType, repeating, null);
        }

        private static ParameterDescriptor unknown() {
            return new ParameterDescriptor(null, false);
        }
    }
}
