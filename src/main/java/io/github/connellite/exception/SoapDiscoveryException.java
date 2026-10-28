package io.github.connellite.exception;

/**
 * A configured SOAP discovery source ({@code bridge.auto.services-url}, {@code bridge.auto.wsdl-*-url})
 * is not available at startup.
 */
public class SoapDiscoveryException extends RuntimeException {

    public SoapDiscoveryException(String message, Throwable cause) {
        super("SOAP service discovery failed: " + message, cause);
    }
}
