package com.sandeep.awsdocumentapi.web;

import com.sandeep.awsdocumentapi.document.DocumentNotFoundException;
import com.sandeep.awsdocumentapi.document.FileTooLargeException;
import com.sandeep.awsdocumentapi.document.InvalidDocumentStateException;
import com.sandeep.awsdocumentapi.document.UploadNotFoundException;
import com.sandeep.awsdocumentapi.storage.StoredObjectNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Objects;

/**
 * Turns every error into an RFC 9457 problem detail ({@code application/problem+json}).
 * Extending {@link ResponseEntityExceptionHandler} covers the framework's own exceptions
 * (malformed JSON, missing header, unparseable UUID, ...); the handlers below add ours.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** One entry per invalid field in a request body. */
    record FieldViolation(String field, String message) {
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail documentNotFound(DocumentNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(InvalidDocumentStateException.class)
    ProblemDetail invalidDocumentState(InvalidDocumentStateException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Confirm called before the bytes reached storage: a state conflict, not a missing resource. */
    @ExceptionHandler(UploadNotFoundException.class)
    ProblemDetail uploadNotFound(UploadNotFoundException ex) {
        return problem(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(FileTooLargeException.class)
    ProblemDetail fileTooLarge(FileTooLargeException ex) {
        return problem(HttpStatus.CONTENT_TOO_LARGE, ex.getMessage());
    }

    /** Metadata says the bytes exist but storage disagrees: a broken invariant on our side, not a client mistake. */
    @ExceptionHandler(StoredObjectNotFoundException.class)
    ProblemDetail contentMissing(StoredObjectNotFoundException ex, HttpServletRequest request) {
        log.error("Stored content missing for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Document content is missing from storage");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail body = ex.getBody();
        body.setDetail("Request validation failed");
        body.setProperty("errors", ex.getFieldErrors().stream().map(ApiExceptionHandler::toViolation).toList());
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** Anything not handled above. The stack trace goes to the log; the client gets a generic message. */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid"));
    }
}
