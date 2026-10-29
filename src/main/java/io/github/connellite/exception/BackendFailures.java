package io.github.connellite.exception;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.transport.http.HTTPException;
#if SPRING_BOOT_3
import jakarta.xml.soap.SOAPFault;
import jakarta.xml.ws.soap.SOAPFaultException;
#else
import javax.xml.soap.SOAPFault;
import javax.xml.ws.soap.SOAPFaultException;
#endif
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Classifies what a SOAP call threw, so that the HTTP status says whose problem it is:
 * <ul>
 *     <li>SOAP fault from the backend → {@link SoapFaultException}, status by fault name (default 502);</li>
 *     <li>backend unreachable → 503, timeout → 504, HTTP error or unreadable response → 502;</li>
 *     <li>anything else (e.g. a ClassCastException in the outbound CXF chain) is a bridge bug → 500.</li>
 * </ul>
 */
@UtilityClass
public class BackendFailures {

    /**
     * A fault the WSDL declares, recognised by the element in the fault {@code detail}.
     *
     * @param faultInfo the detail element as JSON, or {@code null}
     */
    public record DeclaredFault(String name, JsonNode faultInfo) {
    }

    public static RuntimeException classify(Throwable failure, Map<String, Integer> faultStatus) {
        return classify(failure, faultStatus, detail -> null);
    }

    /**
     * @param declaredFaults the declared fault a detail entry element stands for, or {@code null} for none
     */
    public static RuntimeException classify(Throwable failure, Map<String, Integer> faultStatus,
                                            Function<Element, DeclaredFault> declaredFaults) {
        Fault fault = fault(failure);
        if (fault != null) {
            return soapFault(failure, fault, faultStatus, declaredFaults);
        }

        // HttpTimeoutException also covers HttpConnectTimeoutException (java.net.http client conduit in CXF 4).
        Throwable timeout = find(failure, cause -> cause instanceof SocketTimeoutException
                || cause instanceof HttpTimeoutException);
        if (timeout != null) {
            return UpstreamException.timeout("SOAP backend did not answer in time: " + describe(timeout), failure);
        }
        Throwable unreachable = find(failure, cause -> cause instanceof ConnectException
                || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException);
        if (unreachable != null) {
            return UpstreamException.unavailable("SOAP backend is not reachable: " + describe(unreachable), failure);
        }
        if (find(failure, HTTPException.class::isInstance) instanceof HTTPException httpError) {
            return httpError(httpError, failure);
        }
        Throwable invalidResponse = find(failure, XMLStreamException.class::isInstance);
        if (invalidResponse != null) {
            return UpstreamException.badResponse("SOAP response cannot be read: " + describe(invalidResponse), failure);
        }
        Throwable io = find(failure, IOException.class::isInstance);
        if (io != null) {
            return UpstreamException.badResponse("SOAP transport error: " + describe(io), failure);
        }
        return failure instanceof RuntimeException runtime
                ? runtime
                : new IllegalStateException("SOAP invocation failed inside the bridge", failure);
    }

    /**
     * Failure to build a SOAP client: an unreachable WSDL is the backend's problem (503); a WSDL that
     * cannot be turned into a client is a contract problem (502).
     */
    public static UpstreamException clientCreation(String wsdlUrl, RuntimeException failure) {
        Throwable io = find(failure, IOException.class::isInstance);
        if (io != null) {
            return UpstreamException.unavailable("Cannot load WSDL " + wsdlUrl + ": " + describe(io), failure);
        }
        return UpstreamException.contractMismatch("Cannot build a SOAP client from WSDL " + wsdlUrl + ": "
                + describe(rootCause(failure)), failure);
    }

    /** What a SOAP fault says, from the CXF fault or the JAX-WS one wrapping it. */
    private record Fault(String message, String code, List<String> subcodes, Element detail) {
    }

    private static Fault fault(Throwable failure) {
        if (find(failure, SoapFault.class::isInstance) instanceof SoapFault soapFault) {
            List<String> subcodes = soapFault.getSubCodes() != null
                    ? soapFault.getSubCodes().stream().map(QName::toString).toList()
                    : List.of();
            return new Fault(soapFault.getMessage(),
                    soapFault.getFaultCode() != null ? soapFault.getFaultCode().toString() : null, subcodes,
                    soapFault.getDetail());
        }
        if (find(failure, SOAPFaultException.class::isInstance) instanceof SOAPFaultException exception
                && exception.getFault() != null) {
            SOAPFault soapFault = exception.getFault();
            List<String> subcodes = new ArrayList<>();
            try {
                for (Iterator<QName> codes = soapFault.getFaultSubcodes(); codes.hasNext(); ) {
                    subcodes.add(codes.next().toString());
                }
            } catch (UnsupportedOperationException soap11) {
                // SOAP 1.1 faults have no subcodes.
            }
            QName code = soapFault.getFaultCodeAsQName();
            return new Fault(soapFault.getFaultString(), code != null ? code.toString() : null, subcodes,
                    soapFault.getDetail());
        }
        return null;
    }

    /** The fault {@code detail} names a declared fault by its first element; anything else is {@code SoapFault}. */
    private static SoapFaultException soapFault(Throwable failure, Fault fault, Map<String, Integer> faultStatus,
                                                Function<Element, DeclaredFault> declaredFaults) {
        Element entry = firstElement(fault.detail());
        DeclaredFault declared = entry != null ? declaredFaults.apply(entry) : null;
        String faultName = declared != null ? declared.name() : "SoapFault";
        String message = firstText(fault.message(), faultName);
        int status = statusFor(faultName, faultStatus);
        return new SoapFaultException(faultName, message, status, fault.code(), fault.subcodes(),
                declared != null ? declared.faultInfo() : null, failure);
    }

    private static Element firstElement(Element detail) {
        if (detail == null) {
            return null;
        }
        for (Node node = detail.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    private static UpstreamException httpError(HTTPException httpError, Throwable failure) {
        String message = "SOAP backend answered HTTP " + httpError.getResponseCode()
                + (httpError.getResponseMessage() != null ? " " + httpError.getResponseMessage() : "")
                + (httpError.getUrl() != null ? " (" + httpError.getUrl() + ")" : "");
        return switch (httpError.getResponseCode()) {
            case 503 -> UpstreamException.unavailable(message, failure);
            case 504 -> UpstreamException.timeout(message, failure);
            default -> UpstreamException.badResponse(message, failure);
        };
    }

    private static int statusFor(String faultName, Map<String, Integer> faultStatus) {
        if (faultStatus == null || faultStatus.isEmpty()) {
            return 502;
        }
        Map<String, Integer> caseInsensitive = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        caseInsensitive.putAll(faultStatus);
        Integer status = caseInsensitive.get(faultName);
        return status != null ? status : 502;
    }

    private static Throwable find(Throwable failure, Predicate<Throwable> predicate) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (predicate.test(current)) {
                return current;
            }
        }
        return null;
    }

    private static Throwable rootCause(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current.getCause() != null && seen.add(current)) {
            current = current.getCause();
        }
        return current;
    }

    private static String describe(Throwable throwable) {
        return firstText(throwable.getMessage(), throwable.getClass().getSimpleName());
    }

    private static String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
