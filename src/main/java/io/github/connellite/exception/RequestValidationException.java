package io.github.connellite.exception;

import lombok.Getter;

import java.util.List;

/**
 * REST request body does not match the schema generated for the SOAP operation.
 */
@Getter
public class RequestValidationException extends RuntimeException {

    private final List<String> errors;

    public RequestValidationException(List<String> errors) {
        super("Request body does not match the operation schema");
        this.errors = List.copyOf(errors);
    }
}
