package io.github.connellite.service.auto;

import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.WsdlUrlCollector;
import io.github.connellite.model.DiscoveredSoapService;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.SchemaField;
import io.github.connellite.model.SchemaProperty;
import io.github.connellite.model.SchemaTrees;
import io.github.connellite.model.SchemaType;
import io.github.connellite.model.SoapHeaderModel;
import io.github.connellite.model.WsdlOperationModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoMappingGenerator {

    private static final Pattern SPEL_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^{}/]+)}");
    private static final Set<String> SUPPORTED_METHODS = new LinkedHashSet<>(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));

    private final BridgeProperties bridgeProperties;
    private final WsdlServiceDiscovery serviceDiscovery;
    private final WsdlUrlCollector wsdlUrlCollector;

    public List<MappingDefinition> generate() throws IOException {
        BridgeProperties.AutoMapping auto = bridgeProperties.getAuto();
        List<DiscoveredSoapService> services = serviceDiscovery.discover(auto.getServicesUrl(), wsdlUrlCollector.wsdlUrls());
        List<MappingDefinition> mappings = new ArrayList<>();

        for (DiscoveredSoapService service : services) {
            for (WsdlOperationModel operation : service.operations()) {
                BridgeProperties.OperationOverride override = bridgeProperties.operation(service.name(), operation.name());
                if (!override.isEnabled()) {
                    log.info("Operation {}.{} is disabled by bridge.operations", service.name(), operation.name());
                    continue;
                }
                MappingDefinition mapping = toMapping(service, operation, auto);
                applyOverride(mapping, override, auto);
                mappings.add(mapping);
            }
        }

        log.info("Generated {} automatic mapping(s)", mappings.size());
        return mappings;
    }

    /**
     * {@code bridge.operations.<Service>.<operation>}: own path below the prefix and HTTP method. Path variables
     * fill request fields, so each must name one: a typo fails the start instead of every request.
     */
    private static void applyOverride(MappingDefinition mapping, BridgeProperties.OperationOverride override,
                                      BridgeProperties.AutoMapping auto) {
        String name = "bridge.operations." + mapping.getSoap().getService() + "." + mapping.getSoap().getOperation();
        if (StringUtils.hasText(override.getMethod())) {
            String method = override.getMethod().trim().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_METHODS.contains(method)) {
                throw new IllegalStateException(name + ".method: unsupported HTTP method " + override.getMethod()
                        + ", expected one of " + SUPPORTED_METHODS);
            }
            mapping.setMethod(method);
        }
        if (StringUtils.hasText(override.getPath())) {
            String path = BridgeProperties.normalizePathPrefix(override.getPath());
            if (path.isEmpty()) {
                throw new IllegalStateException(name + ".path must not be the root path");
            }
            mapping.setPath(BridgeProperties.normalizePathPrefix(auto.getPathPrefix()) + path);
        }
        Set<String> requestFields = new LinkedHashSet<>();
        mapping.getRequest().keySet().forEach(field -> requestFields.add(topLevelField(field.substring(2))));
        for (String variable : pathVariables(mapping.getPath())) {
            if (!requestFields.contains(topLevelField(variable))) {
                throw new IllegalStateException(name + ".path: variable {" + variable
                        + "} is not a request field of the operation; fields: " + requestFields);
            }
        }
    }

    /** Names of {@code {name}} / {@code {name:regex}} segments of an Ant-style path. */
    public static List<String> pathVariables(String path) {
        List<String> variables = new ArrayList<>();
        Matcher matcher = PATH_VARIABLE.matcher(path);
        while (matcher.find()) {
            String variable = matcher.group(1);
            int colon = variable.indexOf(':');
            variables.add(colon >= 0 ? variable.substring(0, colon) : variable);
        }
        return variables;
    }

    private MappingDefinition toMapping(
            DiscoveredSoapService service,
            WsdlOperationModel operation,
            BridgeProperties.AutoMapping auto
    ) {
        MappingDefinition mapping = new MappingDefinition();
        mapping.setPath(buildPath(auto.getPathPrefix(), service.name(), operation.name()));
        mapping.setMethod(auto.getHttpMethod());

        MappingDefinition.SoapTarget soap = new MappingDefinition.SoapTarget();
        soap.setService(service.name());
        soap.setOperation(operation.name());
        soap.setWsdl(service.wsdlUrl());
        soap.setEndpointAddress(bridgeProperties.getBackend().endpointAddress(service.name()));
        soap.setEndpoint(operation.endpoint());
        mapping.setSoap(soap);
        mapping.setFaults(operation.faults());
        mapping.setDocumentation(operation.documentation());

        Map<String, String> request = new LinkedHashMap<>();
        for (String field : operation.inputFields()) {
            if (isCollectionItemField(field) || isJaxbMapEntryField(field)) {
                continue;
            }
            request.put(toRestJsonPath(field), toSoapJsonPath(operation.inputElement(), field));
        }
        mapping.setRequest(request);

        List<String> requestSchemaPaths = new ArrayList<>();
        for (String field : operation.inputFields()) {
            if (isJaxbMapEntryField(field)) {
                continue;
            }
            requestSchemaPaths.add(toRestJsonPath(field));
        }
        List<SchemaField> requestSchemaFields = new ArrayList<>(operation.inputSchemaFields().stream()
                .filter(field -> !isJaxbMapEntryField(field.path()))
                .toList());

        Map<String, String> headerRequest = new LinkedHashMap<>();
        String headerRoot = bridgeProperties.getSoapHeaders().getJsonField();
        if (!operation.inputHeaders().isEmpty() && request.keySet().stream()
                .anyMatch(field -> topLevelField(field.substring(2)).equals(headerRoot))) {
            log.warn("Operation {}.{} has a request field '{}' that is also bridge.soap-headers.json-field; "
                    + "SOAP headers shadow it", service.name(), operation.name(), headerRoot);
        }
        for (SoapHeaderModel header : operation.inputHeaders()) {
            for (SchemaField field : headerFields(header)) {
                String headerField = field.path().isEmpty() || field.path().startsWith("[")
                        ? header.part() + field.path()
                        : header.part() + "." + field.path();
                String restField = headerRoot + "." + headerField;
                if (isJaxbMapEntryField(headerField)) {
                    continue;
                }
                if (!isCollectionItemField(headerField)) {
                    String soapField = headerField.endsWith("[]")
                            ? headerField.substring(0, headerField.length() - 2)
                            : headerField;
                    headerRequest.put(toRestJsonPath(restField), "$." + soapField);
                }
                requestSchemaPaths.add(toRestJsonPath(restField));
                // A header is optional as a whole, so none of its fields is required.
                requestSchemaFields.add(field.withPath(restField).withRequired(false));
            }
        }
        mapping.setHeaderRequest(headerRequest);
        mapping.setRequestType(requestType(operation, headerRoot));
        mapping.setResponseType(operation.outputType());
        mapping.setSchemaTypes(operation.types());
        mapping.setRequestSchemaPaths(requestSchemaPaths);
        mapping.setRequestSchemaFields(toRestSchemaFields(requestSchemaFields));

        Map<String, String> response = new LinkedHashMap<>();
        String payloadWrapperField = payloadWrapperField(operation);
        for (String field : operation.outputFields()) {
            if (isCollectionItemField(field) || isJaxbMapEntryField(field)) {
                continue;
            }
            response.put(toSpelPath(field, payloadWrapperField), toRestJsonPath(field));
        }
        mapping.setResponse(response);

        List<String> responseSchemaPaths = new ArrayList<>();
        for (String field : operation.outputFields()) {
            if (isJaxbMapEntryField(field)) {
                continue;
            }
            responseSchemaPaths.add(toRestJsonPath(field));
        }
        mapping.setResponseSchemaPaths(responseSchemaPaths);
        mapping.setResponseSchemaFields(toRestSchemaFields(
                operation.outputSchemaFields().stream()
                        .filter(field -> !isJaxbMapEntryField(field.path()))
                        .toList()
        ));

        return mapping;
    }

    /**
     * The REST request body as a type: the fields of the request element plus, when the binding declares SOAP
     * headers, the optional headers object (which shadows a body field of its name).
     */
    private static SchemaType requestType(WsdlOperationModel operation, String headerRoot) {
        if (operation.inputType() == null && operation.inputHeaders().isEmpty()) {
            return null;
        }
        List<SchemaProperty> properties = new ArrayList<>();
        if (operation.inputType() != null) {
            operation.inputType().properties().stream()
                    .filter(property -> operation.inputHeaders().isEmpty() || !property.name().equals(headerRoot))
                    .forEach(properties::add);
        }
        if (!operation.inputHeaders().isEmpty()) {
            List<SchemaProperty> headers = operation.inputHeaders().stream()
                    .map(SoapHeaderModel::property)
                    .filter(Objects::nonNull)
                    .toList();
            properties.add(new SchemaProperty(headerRoot, "object", null, null, new SchemaType(null, headers,
                    "SOAP headers declared in the WSDL binding, one property per header part"),
                    false, false, false, null, null, null, List.of()));
        }
        return new SchemaType(null, properties,
                operation.inputType() != null ? operation.inputType().documentation() : null);
    }

    private static List<SchemaField> toRestSchemaFields(
            List<SchemaField> fields
    ) {
        return fields.stream()
                .map(field -> field.withPath(toRestJsonPath(field.path())))
                .toList();
    }

    /** A header element without indexed fields (e.g. {@code xs:any} content) is passed on as a whole object. */
    private static List<SchemaField> headerFields(SoapHeaderModel header) {
        return header.schemaFields().isEmpty()
                ? List.of(new SchemaField("", "object", false, false))
                : header.schemaFields();
    }

    private static boolean isCollectionItemField(String field) {
        return field.contains("[].");
    }

    /**
     * Defense in depth: even if XSD indexing expands Map entry trees, never map SpEL to {@code .entry}.
     */
    private static boolean isJaxbMapEntryField(String field) {
        return field.equals("entry")
                || field.equals("entry[]")
                || field.startsWith("entry.")
                || field.startsWith("entry[].")
                || field.matches(".*\\.entry(\\[\\])?(\\..*)?");
    }

    private static String toRestJsonPath(String field) {
        String path = field.endsWith("[]") ? field.substring(0, field.length() - 2) : field;
        StringBuilder restPath = new StringBuilder("$");
        for (String segment : path.split("\\.", -1)) {
            restPath.append('.').append(restFieldName(segment));
        }
        return restPath.toString();
    }

    /**
     * CXF names some elements with a leading underscore ({@code _return} for {@code Map} results); REST exposes
     * the plain name. SOAP paths keep the XSD name, and arguments are matched with the same normalization.
     */
    private static String restFieldName(String segment) {
        return SchemaTrees.restName(segment);
    }

    private static String toSoapJsonPath(String inputElement, String field) {
        String soapField = field.endsWith("[]") ? field.substring(0, field.length() - 2) : field;
        return "$." + inputElement + "." + soapField;
    }

    private static String toSpelPath(String field) {
        return toSpelPath(field, null);
    }

    private static String toSpelPath(String field, String payloadWrapperField) {
        String spelField = field.endsWith("[]") ? field.substring(0, field.length() - 2) : field;
        if (payloadWrapperField != null && topLevelField(spelField).equals(payloadWrapperField)) {
            spelField = stripTopLevelField(spelField);
        }
        if (spelField.isBlank()) {
            return "#root";
        }
        return "#root" + spelPropertyChain(spelField);
    }

    /**
     * XSD names such as {@code my-field} are not SpEL identifiers; address them with the indexer
     * ({@code ['my-field']}), which goes through the same property accessors.
     */
    private static String spelPropertyChain(String field) {
        StringBuilder chain = new StringBuilder();
        for (String segment : field.split("\\.")) {
            if (SPEL_IDENTIFIER.matcher(segment).matches()) {
                chain.append('.').append(segment);
            } else {
                chain.append("['").append(segment.replace("'", "''")).append("']");
            }
        }
        return chain.toString();
    }

    private static String payloadWrapperField(WsdlOperationModel operation) {
        if (!isResponseWrapperElement(operation)) {
            return null;
        }

        String wrapper = null;
        for (String field : operation.outputFields()) {
            String topLevel = topLevelField(field);
            if (topLevel.isBlank()) {
                continue;
            }
            if (wrapper == null) {
                wrapper = topLevel;
            } else if (!wrapper.equals(topLevel)) {
                return null;
            }
        }
        return wrapper;
    }

    private static boolean isResponseWrapperElement(WsdlOperationModel operation) {
        String outputElement = operation.outputElement();
        return outputElement != null
                && !outputElement.isBlank()
                && outputElement.equalsIgnoreCase(operation.name() + "Response");
    }

    private static String topLevelField(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        String normalized = field.endsWith("[]") ? field.substring(0, field.length() - 2) : field;
        int dotIndex = normalized.indexOf('.');
        return dotIndex >= 0 ? normalized.substring(0, dotIndex) : normalized;
    }

    private static String stripTopLevelField(String field) {
        int dotIndex = field.indexOf('.');
        return dotIndex >= 0 ? field.substring(dotIndex + 1) : "";
    }

    private String buildPath(String prefix, String serviceName, String operationName) {
        return BridgeProperties.normalizePathPrefix(prefix) + "/" + serviceName + "/" + operationName;
    }
}
