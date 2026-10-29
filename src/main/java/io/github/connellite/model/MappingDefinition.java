package io.github.connellite.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
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
    /** Faults the WSDL declares for the operation. */
    private List<SoapFaultModel> faults = new ArrayList<>();
    /** {@code wsdl:documentation} of the operation, or {@code null}. */
    private String documentation;
    /**
     * Type tree of the REST request body (body fields plus the SOAP headers object); {@code null}: published from
     * {@link #requestSchemaFields}.
     */
    private SchemaType requestType;
    /** Type tree of the REST response body; {@code null}: published from {@link #responseSchemaFields}. */
    private SchemaType responseType;
    /** Named types the type trees refer to, by QName; shared by the operations of one WSDL. */
    private Map<String, SchemaType> schemaTypes = Map.of();

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
        /** WSDL service and port to call; {@code null}: the one CXF picks (a WSDL without SOAP ports). */
        private WsdlEndpoint endpoint;
    }
}
