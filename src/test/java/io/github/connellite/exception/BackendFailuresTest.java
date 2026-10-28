package io.github.connellite.exception;

import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.transport.http.HTTPException;
import org.junit.jupiter.api.Test;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class BackendFailuresTest {

    private static final QName SERVER = new QName("http://schemas.xmlsoap.org/soap/envelope/", "Server");
    private static final Map<String, Integer> FAULT_STATUS = Map.of("accessdeniedexception", 401, "ConflictException", 409);

    @Test
    void connectionRefusedIsUnavailable() {
        UpstreamException exception = upstream(new Fault(new ConnectException("Connection refused")));

        assertEquals(503, exception.getHttpStatus());
        assertEquals("SOAP backend is not reachable: Connection refused", exception.getMessage());
    }

    @Test
    void unknownHostIsUnavailable() {
        assertEquals(503, upstream(new Fault(new UnknownHostException("soap-backend"))).getHttpStatus());
    }

    @Test
    void readTimeoutIsGatewayTimeout() {
        UpstreamException exception = upstream(new Fault(new SocketTimeoutException("Read timed out")));

        assertEquals(504, exception.getHttpStatus());
        assertEquals("SOAP backend timeout", exception.getTitle());
    }

    @Test
    void backendHttpErrorsKeepUnavailabilityAndTimeoutOtherwiseBadGateway() throws Exception {
        URL url = new URL("http://backend/services/Auth");

        assertEquals(503, upstream(new Fault(new HTTPException(503, "Service Unavailable", url))).getHttpStatus());
        assertEquals(504, upstream(new Fault(new HTTPException(504, "Gateway Timeout", url))).getHttpStatus());
        UpstreamException unauthorized = upstream(new Fault(new HTTPException(401, "Unauthorized", url)));
        assertEquals(502, unauthorized.getHttpStatus());
        assertEquals("SOAP backend answered HTTP 401 Unauthorized (http://backend/services/Auth)", unauthorized.getMessage());
    }

    @Test
    void unreadableResponseIsBadGateway() {
        UpstreamException exception = upstream(new Fault(new XMLStreamException("Unexpected EOF")));

        assertEquals(502, exception.getHttpStatus());
        assertEquals("Invalid SOAP backend response", exception.getTitle());
    }

    @Test
    void errorInsideOutboundChainIsBridgeBugNotUpstream() {
        Fault fault = new Fault(new ClassCastException("String cannot be cast to Call$Params"));

        RuntimeException classified = BackendFailures.classify(fault, FAULT_STATUS);

        assertFalse(classified instanceof UpstreamException);
        assertSame(fault, classified);
    }

    @Test
    void checkedNonIoExceptionBecomesInternalError() {
        RuntimeException classified = BackendFailures.classify(new Exception("unexpected"), FAULT_STATUS);

        assertInstanceOf(IllegalStateException.class, classified);
    }

    @Test
    void genericSoapFaultKeepsCodeSubcodesAndMessage() {
        SoapFault fault = new SoapFault("Folder not found", SERVER);
        fault.addSubCode(new QName("urn:elar", "NotFound"));

        SoapFaultException exception = soapFault(fault);

        assertEquals("SoapFault", exception.getFaultName());
        assertEquals("Folder not found", exception.getMessage());
        assertEquals("{http://schemas.xmlsoap.org/soap/envelope/}Server", exception.getFaultCode());
        assertEquals(List.of("{urn:elar}NotFound"), exception.getFaultSubcodes());
        assertEquals(502, exception.getHttpStatus());
        assertNull(exception.getFaultInfo());
    }

    @Test
    void soapFaultWithoutMessageFallsBackToFaultName() {
        SoapFaultException exception = soapFault(new SoapFault((String) null, SERVER));

        assertEquals("SoapFault", exception.getMessage());
    }

    @Test
    void soapFaultWrappedDeeperInChainIsStillFound() {
        SoapFault fault = new SoapFault("Denied", SERVER);

        SoapFaultException exception = soapFault(new RuntimeException("wrapper", new Fault(fault)));

        assertEquals("{http://schemas.xmlsoap.org/soap/envelope/}Server", exception.getFaultCode());
    }

    @Test
    void declaredFaultUsesConfiguredStatusAndFaultInfo() {
        SoapFaultException exception = soapFault(new AccessDeniedException_Exception("Token expired", new FaultBean(17)));

        assertEquals("AccessDeniedException", exception.getFaultName());
        assertEquals(401, exception.getHttpStatus());
        assertEquals("Token expired", exception.getMessage());
        assertEquals(17, exception.getFaultInfo().get("code").asInt());
    }

    @Test
    void declaredFaultTakesPrecedenceOverItsSoapFaultCause() {
        AccessDeniedException_Exception declared = new AccessDeniedException_Exception("Denied", new FaultBean(1));
        declared.initCause(new SoapFault("Denied", SERVER));

        SoapFaultException exception = soapFault(declared);

        assertEquals("AccessDeniedException", exception.getFaultName());
        assertEquals("{http://schemas.xmlsoap.org/soap/envelope/}Server", exception.getFaultCode());
    }

    @Test
    void unlistedDeclaredFaultIsBadGateway() {
        assertEquals(502, soapFault(new RepositoryException_Exception("db down", null)).getHttpStatus());
    }

    @Test
    void wsdlThatCannotBeLoadedIsUnavailableAndUncompilableIsContractMismatch() {
        UpstreamException unreachable = BackendFailures.clientCreation("http://backend/x?wsdl",
                new RuntimeException("Failed to create service", new ConnectException("Connection refused")));
        UpstreamException uncompilable = BackendFailures.clientCreation("http://backend/x?wsdl",
                new IllegalStateException("Unable to compile generated classes"));

        assertEquals(503, unreachable.getHttpStatus());
        assertEquals(502, uncompilable.getHttpStatus());
        assertEquals("SOAP contract mismatch", uncompilable.getTitle());
    }

    private static UpstreamException upstream(Throwable failure) {
        return assertInstanceOf(UpstreamException.class, BackendFailures.classify(failure, FAULT_STATUS));
    }

    private static SoapFaultException soapFault(Throwable failure) {
        return assertInstanceOf(SoapFaultException.class, BackendFailures.classify(failure, FAULT_STATUS));
    }

    /** Shape of a dynamic client fault class: {@code <Fault>_Exception} with {@code getFaultInfo()}. */
    @SuppressWarnings("checkstyle:TypeName")
    static class AccessDeniedException_Exception extends Exception {
        private final FaultBean faultInfo;

        AccessDeniedException_Exception(String message, FaultBean faultInfo) {
            super(message);
            this.faultInfo = faultInfo;
        }

        public FaultBean getFaultInfo() {
            return faultInfo;
        }
    }

    @SuppressWarnings("checkstyle:TypeName")
    static class RepositoryException_Exception extends Exception {
        private final FaultBean faultInfo;

        RepositoryException_Exception(String message, FaultBean faultInfo) {
            super(message);
            this.faultInfo = faultInfo;
        }

        public FaultBean getFaultInfo() {
            return faultInfo;
        }
    }

    public static class FaultBean {
        private final int code;

        FaultBean(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
