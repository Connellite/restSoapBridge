package io.github.connellite.service.auto;

import io.github.connellite.config.BridgeProperties;
import io.github.connellite.config.WsdlUrlCollector;
import io.github.connellite.model.DiscoveredSoapService;
import io.github.connellite.model.MappingDefinition;
import io.github.connellite.model.SchemaField;
import io.github.connellite.model.WsdlOperationModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoMappingGenerator {

    private final BridgeProperties bridgeProperties;
    private final WsdlServiceDiscovery serviceDiscovery;
    private final WsdlUrlCollector wsdlUrlCollector;

    public List<MappingDefinition> generate() throws IOException {
        BridgeProperties.AutoMapping auto = bridgeProperties.getAuto();
        List<DiscoveredSoapService> services = serviceDiscovery.discover(auto.getServicesUrl(), wsdlUrlCollector.wsdlUrls());
        List<MappingDefinition> mappings = new ArrayList<>();

        for (DiscoveredSoapService service : services) {
            for (WsdlOperationModel operation : service.operations()) {
                mappings.add(toMapping(service, operation, auto));
            }
        }

        log.info("Generated {} automatic mapping(s)", mappings.size());
        return mappings;
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
        mapping.setSoap(soap);

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
        mapping.setRequestSchemaPaths(requestSchemaPaths);
        mapping.setRequestSchemaFields(toRestSchemaFields(
                operation.inputSchemaFields().stream()
                        .filter(field -> !isJaxbMapEntryField(field.path()))
                        .toList()
        ));

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

    private static List<SchemaField> toRestSchemaFields(
            List<SchemaField> fields
    ) {
        return fields.stream()
                .map(field -> new SchemaField(
                        toRestJsonPath(field.path()),
                        field.xsdType(),
                        field.required(),
                        field.collection()
                ))
                .toList();
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
        if (field.endsWith("[]")) {
            return "$." + field.substring(0, field.length() - 2);
        }
        return "$." + field;
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
        return "#root." + spelField;
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
        String normalizedPrefix = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (!normalizedPrefix.startsWith("/")) {
            normalizedPrefix = "/" + normalizedPrefix;
        }
        return normalizedPrefix + "/" + serviceName + "/" + operationName;
    }
}
