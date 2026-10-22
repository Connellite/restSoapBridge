package io.github.connellite.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
#if SPRING_BOOT_3
import org.springframework.web.servlet.resource.NoResourceFoundException;
#endif
import org.zalando.problem.Problem;
import org.zalando.problem.Status;
import org.zalando.problem.StatusType;
import org.zalando.problem.spring.web.advice.ProblemHandling;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler implements ProblemHandling {

    @ExceptionHandler
    public ResponseEntity<Problem> handleRequestValidation(RequestValidationException exception, NativeWebRequest request) {
        log.debug("Rejected request body: {}", exception.getErrors());
        Problem problem = Problem.builder()
                .withTitle("Invalid request body")
                .withStatus(Status.BAD_REQUEST)
                .withDetail(exception.getMessage())
                .with("errors", exception.getErrors())
                .build();
        return create(exception, problem, request);
    }

    @ExceptionHandler
    public ResponseEntity<Problem> handleSoapFault(SoapFaultException exception, NativeWebRequest request) {
        log.warn("SOAP fault: {} - {}", exception.getFaultName(), exception.getMessage());
        var builder = Problem.builder()
                .withTitle("SOAP Fault")
                .withStatus(statusOf(exception.getHttpStatus()))
                .withDetail(exception.getMessage())
                .with("faultName", exception.getFaultName());
        if (exception.getFaultCode() != null) {
            builder.with("faultCode", exception.getFaultCode());
        }
        return create(exception, builder.build(), request);
    }

#if SPRING_BOOT_3
    @ExceptionHandler
    public ResponseEntity<Problem> handleNoResourceFound(NoResourceFoundException exception, NativeWebRequest request) {
        return create(Status.NOT_FOUND, exception, request);
    }
#endif

    @Override
    public ResponseEntity<Problem> handleThrowable(Throwable throwable, NativeWebRequest request) {
        log.error("Bridge request failed", throwable);
        Throwable root = NestedExceptionUtils.getMostSpecificCause(throwable);
        Problem problem = Problem.builder()
                .withTitle(root.getClass().getSimpleName())
                .withStatus(Status.BAD_GATEWAY)
                .withDetail(root.getMessage() != null ? root.getMessage() : root.toString())
                .build();
        return create(throwable, problem, request);
    }

    private static StatusType statusOf(int statusCode) {
        for (Status status : Status.values()) {
            if (status.getStatusCode() == statusCode) {
                return status;
            }
        }
        return Status.BAD_GATEWAY;
    }
}
