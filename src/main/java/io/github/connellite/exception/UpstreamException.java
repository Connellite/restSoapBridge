package io.github.connellite.exception;

import lombok.Getter;

/**
 * The SOAP backend failed or answered something the bridge cannot use: a 5xx that is not a bridge bug.
 * Unexpected errors inside the bridge are not wrapped in this type and end up as 500.
 */
@Getter
public class UpstreamException extends RuntimeException {

    private final int httpStatus;
    private final String title;

    public UpstreamException(int httpStatus, String title, String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.title = title;
    }

    /** Backend cannot be reached (connection refused, unknown host, WSDL not loadable): 503. */
    public static UpstreamException unavailable(String message, Throwable cause) {
        return new UpstreamException(503, "SOAP backend unavailable", message, cause);
    }

    /** Backend did not answer within the configured timeout: 504. */
    public static UpstreamException timeout(String message, Throwable cause) {
        return new UpstreamException(504, "SOAP backend timeout", message, cause);
    }

    /** Backend answered with an HTTP error or a body that is not a valid SOAP response: 502. */
    public static UpstreamException badResponse(String message, Throwable cause) {
        return new UpstreamException(502, "Invalid SOAP backend response", message, cause);
    }

    /** Backend contract differs from the mappings generated from its WSDL: 502. */
    public static UpstreamException contractMismatch(String message, Throwable cause) {
        return new UpstreamException(502, "SOAP contract mismatch", message, cause);
    }
}
