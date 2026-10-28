package io.github.connellite.exception;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;

import java.util.List;

@Getter
public class SoapFaultException extends UpstreamException {

    private final String faultName;
    private final String faultCode;
    private final List<String> faultSubcodes;
    /** Declared fault detail bean as JSON, or {@code null}. */
    private final JsonNode faultInfo;

    public SoapFaultException(String faultName, String message, int httpStatus, String faultCode,
                              List<String> faultSubcodes, JsonNode faultInfo, Throwable cause) {
        super(httpStatus, "SOAP Fault", message, cause);
        this.faultName = faultName;
        this.faultCode = faultCode;
        this.faultSubcodes = faultSubcodes == null ? List.of() : List.copyOf(faultSubcodes);
        this.faultInfo = faultInfo;
    }

    public SoapFaultException(String faultName, String message, int httpStatus, String faultCode, Throwable cause) {
        this(faultName, message, httpStatus, faultCode, List.of(), null, cause);
    }
}
