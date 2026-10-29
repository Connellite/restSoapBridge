package io.github.connellite.model;

/**
 * The WSDL port a SOAP client calls.
 *
 * @param service QName of the {@code wsdl:service} in {@link javax.xml.namespace.QName#toString()} form
 * @param port    name of the {@code wsdl:port} in that service
 */
public record WsdlEndpoint(String service, String port) {
}
