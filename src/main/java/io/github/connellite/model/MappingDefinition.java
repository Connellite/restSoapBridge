package io.github.connellite.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MappingDefinition {

    private String path;
    private String method;
    private SoapTarget soap;
    private Map<String, String> request = new LinkedHashMap<>();
    /** REST JSON path → {@code $.<header part>[.field]} of a SOAP header declared in the WSDL binding. */
    private Map<String, String> headerRequest = new LinkedHashMap<>();
    private Map<String, String> response = new LinkedHashMap<>();
    private List<String> requestSchemaPaths;
    private List<String> responseSchemaPaths;
    private List<SchemaField> requestSchemaFields;
    private List<SchemaField> responseSchemaFields;

    public void setRequest(Map<String, String> request) {
        this.request = request != null ? new LinkedHashMap<>(request) : new LinkedHashMap<>();
    }

    public void setHeaderRequest(Map<String, String> headerRequest) {
        this.headerRequest = headerRequest != null ? new LinkedHashMap<>(headerRequest) : new LinkedHashMap<>();
    }

    public void setResponse(Map<String, String> response) {
        this.response = response != null ? new LinkedHashMap<>(response) : new LinkedHashMap<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SoapTarget {
        private String service;
        private String operation;
        private String wsdl;
        /** Configured SOAP endpoint address ({@code bridge.backend.endpoint-addresses}); {@code null}: from WSDL. */
        private String endpointAddress;
    }
}
