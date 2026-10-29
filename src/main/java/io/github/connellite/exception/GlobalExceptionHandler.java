package io.github.connellite.exception;

import io.github.connellite.config.BridgeProperties;
import io.github.connellite.correlation.CorrelationId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
#if SPRING_BOOT_3
import org.springframework.web.servlet.resource.NoResourceFoundException;
#endif
import org.zalando.problem.Problem;
import org.zalando.problem.ProblemBuilder;
import org.zalando.problem.Status;
import org.zalando.problem.StatusType;
import org.zalando.problem.spring.web.advice.ProblemHandling;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler implements ProblemHandling {

    private static final Map<Integer, Status> STATUSES = Arrays.stream(Status.values())
            .collect(Collectors.toUnmodifiableMap(Status::getStatusCode, Function.identity()));

    private final boolean exposeBackendDetails;

    public GlobalExceptionHandler() {
        this(new BridgeProperties());
    }

    @Autowired
    public GlobalExceptionHandler(BridgeProperties bridgeProperties) {
        this.exposeBackendDetails = bridgeProperties.getErrors().isExposeBackendDetails();
    }

    @ExceptionHandler
    public ResponseEntity<Problem> handleRequestValidation(RequestValidationException exception, NativeWebRequest request) {
        Problem problem = Problem.builder()
                .withTitle("Invalid request body")
                .withStatus(Status.BAD_REQUEST)
                .withDetail(exception.getMessage())
                .with("errors", exception.getErrors())
                .build();
        return create(exception, problem, request);
    }

    /**
     * The declared fault ({@code faultName}, {@code faultInfo}) is part of the WSDL contract and always shown;
     * the fault string, code and subcodes only with {@code bridge.errors.expose-backend-details}: they may carry
     * class names, internal host names or stack traces of the backend.
     */
    @ExceptionHandler
    public ResponseEntity<Problem> handleSoapFault(SoapFaultException exception, NativeWebRequest request) {
        ProblemBuilder builder = Problem.builder()
                .withTitle(exception.getTitle())
                .withStatus(statusOf(exception.getHttpStatus()))
                .withDetail(exposeBackendDetails ? exception.getMessage() : hiddenFaultDetail(exception))
                .with("faultName", exception.getFaultName());
        if (exposeBackendDetails && exception.getFaultCode() != null) {
            builder.with("faultCode", exception.getFaultCode());
        }
        if (exposeBackendDetails && !exception.getFaultSubcodes().isEmpty()) {
            builder.with("faultSubcodes", exception.getFaultSubcodes());
        }
        if (exception.getFaultInfo() != null) {
            builder.with("faultInfo", exception.getFaultInfo());
        }
        return create(exception, builder.build(), request);
    }

    /** The message names backend addresses and transport errors: shown only with {@code expose-backend-details}. */
    @ExceptionHandler
    public ResponseEntity<Problem> handleUpstream(UpstreamException exception, NativeWebRequest request) {
        Problem problem = Problem.builder()
                .withTitle(exception.getTitle())
                .withStatus(statusOf(exception.getHttpStatus()))
                .withDetail(exposeBackendDetails ? exception.getMessage() : HIDDEN_DETAIL)
                .build();
        return create(exception, problem, request);
    }

    static final String HIDDEN_DETAIL = "Details are in the server log under the correlation id";

    private static String hiddenFaultDetail(SoapFaultException exception) {
        return "SoapFault".equals(exception.getFaultName())
                ? "The SOAP backend reported an error. " + HIDDEN_DETAIL
                : "The SOAP backend reported " + exception.getFaultName() + ". " + HIDDEN_DETAIL;
    }

#if SPRING_BOOT_3
    @ExceptionHandler
    public ResponseEntity<Problem> handleNoResourceFound(NoResourceFoundException exception, NativeWebRequest request) {
        return create(Status.NOT_FOUND, exception, request);
    }
#endif

    /**
     * Anything not classified above is a bug in the bridge: 500, without the internal message
     * (it may expose implementation details); the stack trace is logged under the same correlation id.
     */
    @Override
    public ResponseEntity<Problem> handleThrowable(Throwable throwable, NativeWebRequest request) {
        Problem problem = Problem.builder()
                .withTitle(Status.INTERNAL_SERVER_ERROR.getReasonPhrase())
                .withStatus(Status.INTERNAL_SERVER_ERROR)
                .withDetail("Unexpected " + throwable.getClass().getSimpleName() + " in the bridge; see server logs")
                .build();
        return create(throwable, problem, request);
    }

    /**
     * Backend failures are expected operational events: one WARN line without a stack trace.
     * 5xx raised by the bridge itself keep the full stack trace.
     */
    @Override
    public void log(Throwable throwable, Problem problem, NativeWebRequest request, HttpStatus status) {
        if (throwable instanceof UpstreamException upstream) {
            // The exception, not the problem: its detail may be hidden from the client.
            log.warn("{} {}: {}{}", status.value(), problem.getTitle(), upstream.getMessage(), faultCodes(upstream));
        } else if (status.is5xxServerError()) {
            log.error("{} {}", status.value(), problem.getTitle(), throwable);
        } else {
            ProblemHandling.super.log(throwable, problem, request, status);
        }
    }

    private static String faultCodes(UpstreamException exception) {
        if (!(exception instanceof SoapFaultException fault) || fault.getFaultCode() == null) {
            return "";
        }
        return " (fault code " + fault.getFaultCode()
                + (fault.getFaultSubcodes().isEmpty() ? "" : ", subcodes " + fault.getFaultSubcodes()) + ")";
    }

    @Override
    public ResponseEntity<Problem> process(ResponseEntity<Problem> entity) {
        String correlationId = CorrelationId.current();
        Problem problem = entity.getBody();
        if (correlationId == null || problem == null || problem.getParameters().containsKey("correlationId")) {
            return entity;
        }
        ProblemBuilder builder = Problem.builder()
                .withType(problem.getType())
                .withTitle(problem.getTitle())
                .withStatus(problem.getStatus())
                .withDetail(problem.getDetail())
                .withInstance(problem.getInstance());
        problem.getParameters().forEach(builder::with);
        builder.with("correlationId", correlationId);
        return new ResponseEntity<>(builder.build(), entity.getHeaders(), entity.getStatusCode());
    }

    /**
     * Problem responses need a standard HTTP status; a non-standard configured code falls back to 502.
     */
    static StatusType statusOf(int statusCode) {
        Status status = STATUSES.get(statusCode);
        if (status == null) {
            log.warn("HTTP status {} is not a standard status; answering 502 instead", statusCode);
            return Status.BAD_GATEWAY;
        }
        return status;
    }
}
