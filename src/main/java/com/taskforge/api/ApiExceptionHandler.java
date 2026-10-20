package com.taskforge.api;

import com.taskforge.application.InvalidJobRequestException;
import com.taskforge.application.IdempotencyConflictException;
import com.taskforge.application.JobNotFoundException;
import com.taskforge.application.JobResultUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request validation failed", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformedBody() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Malformed or unsupported JSON request");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidPath() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid job ID");
    }

    @ExceptionHandler(InvalidJobRequestException.class)
    ResponseEntity<ApiError> invalidJob(InvalidJobRequestException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiError> idempotencyConflict() {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                "Idempotency key was used for a different job request");
    }

    @ExceptionHandler(JobNotFoundException.class)
    ResponseEntity<ApiError> missingJob(JobNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(JobResultUnavailableException.class)
    ResponseEntity<ApiError> resultUnavailable(JobResultUnavailableException exception) {
        return switch (exception.reason()) {
            case NOT_READY -> error(HttpStatus.CONFLICT, "RESULT_NOT_READY",
                    "Job result is not yet available");
            case FAILED -> error(HttpStatus.CONFLICT, "RESULT_FAILED",
                    "Failed job has no successful result");
            case LEGACY_MISSING -> error(HttpStatus.CONFLICT, "RESULT_UNAVAILABLE",
                    "No stored result is available for this successful job");
        };
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> missingRoute() {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedMethod() {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method not supported");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> unsupportedContentType() {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Content type not supported");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Void> unacceptableResponseType() {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse response) {
            return ResponseEntity.status(response.getStatusCode())
                    .body(new ApiError("HTTP_ERROR", "Request could not be completed", Map.of()));
        }
        logger.error("Unhandled API error type: {}", exception.getClass().getSimpleName());
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return error(status, code, message, Map.of());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message,
                                                   Map<String, String> fieldErrors) {
        return ResponseEntity.status(status).body(new ApiError(code, message, fieldErrors));
    }
}
