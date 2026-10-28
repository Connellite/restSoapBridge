package io.github.connellite.exception;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.connellite.mapper.path.SoapJsonMapper;
import lombok.experimental.UtilityClass;
import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.transport.http.HTTPException;
#if SPRING_BOOT_3
import jakarta.xml.bind.UnmarshalException;
import jakarta.xml.ws.WebFault;
#else
import javax.xml.bind.UnmarshalException;
import javax.xml.ws.WebFault;
#endif

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    private static final String DECLARED_FAULT_SUFFIX = "_Exception";


    public static RuntimeException classify(Throwable failure, Map<String, Integer> faultStatus) {
        Throwable declaredFault = find(failure, BackendFailures::isDeclaredFault);
        SoapFault soapFault = (SoapFault) find(failure, SoapFault.class::isInstance);
        if (declaredFault != null || soapFault != null) {
            return soapFault(failure, declaredFault, soapFault, faultStatus);
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
        Throwable invalidResponse = find(failure, cause -> cause instanceof XMLStreamException
                || cause instanceof UnmarshalException);
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
     * Failure to build a dynamic client: an unreachable WSDL is the backend's problem (503); a WSDL that
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

    private static SoapFaultException soapFault(Throwable failure, Throwable declaredFault, SoapFault soapFault,
                                                Map<String, Integer> faultStatus) {
        String faultName = declaredFault != null ? declaredFaultName(declaredFault) : "SoapFault";
        String message = firstText(
                declaredFault != null ? declaredFault.getMessage() : null,
                soapFault != null ? soapFault.getMessage() : null,
                faultName);
        String faultCode = soapFault != null && soapFault.getFaultCode() != null ? soapFault.getFaultCode().toString() : null;
        List<String> subcodes = soapFault != null && soapFault.getSubCodes() != null
                ? soapFault.getSubCodes().stream().map(QName::toString).toList()
                : List.of();
        int status = statusFor(faultName, faultStatus);
        return new SoapFaultException(faultName, message, status, faultCode, subcodes, faultInfo(declaredFault), failure);
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

    /**
     * Dynamic client classes for WSDL faults carry {@code @WebFault} and expose the fault bean via {@code getFaultInfo()}.
     */
    private static boolean isDeclaredFault(Throwable throwable) {
        return throwable.getClass().isAnnotationPresent(WebFault.class) || faultInfoMethod(throwable) != null;
    }

    private static String declaredFaultName(Throwable declaredFault) {
        String name = declaredFault.getClass().getSimpleName();
        return name.endsWith(DECLARED_FAULT_SUFFIX) ? name.substring(0, name.length() - DECLARED_FAULT_SUFFIX.length()) : name;
    }

    private static JsonNode faultInfo(Throwable declaredFault) {
        Method method = declaredFault != null ? faultInfoMethod(declaredFault) : null;
        if (method == null) {
            return null;
        }
        try {
            Object info = method.invoke(declaredFault);
            return info != null ? SoapJsonMapper.instance().valueToTree(info) : null;
        } catch (ReflectiveOperationException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static Method faultInfoMethod(Throwable throwable) {
        try {
            Method method = throwable.getClass().getMethod("getFaultInfo");
            return method.getParameterCount() == 0 ? method : null;
        } catch (NoSuchMethodException exception) {
            return null;
        }
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
