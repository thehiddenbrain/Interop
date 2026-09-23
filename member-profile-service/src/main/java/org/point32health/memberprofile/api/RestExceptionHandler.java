package org.point32health.memberprofile.api;

import org.point32health.memberprofile.common.ApiError;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.List;
import java.util.stream.Collectors;

/** Every error leaves the API as an {@link ApiError}; stack traces and internals never reach the caller. */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(MemberProfileException.class)
    public ResponseEntity<ApiError> memberProfile(MemberProfileException e) {
        HttpStatus status = e.getCode().status();
        if (e.getCode() == ErrorCode.RULE_DATA_INVALID || e.getCode() == ErrorCode.INTERNAL_ERROR) {
            log.error("{}: {}", e.getCode(), e.getMessage(), e);
        } else if (status.is5xxServerError()) {
            log.error("{}: {}", e.getCode(), e.getMessage());
        } else {
            log.warn("{}: {}", e.getCode(), e.getMessage());
        }
        return respond(status, e.toApiError());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        List<ApiError.Detail> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.Detail(f.getField(), f.getDefaultMessage())).toList();
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ErrorCode.VALIDATION_ERROR, "request is not valid", details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        String field = "body";
        String message;
        Throwable cause = e.getCause();
        if (cause instanceof DatabindException dbe) {
            field = dbe.getPath().stream()
                    .map(ref -> ref.getPropertyName() == null ? "[" + ref.getIndex() + "]" : ref.getPropertyName())
                    .collect(Collectors.joining("."));
            if (field.isEmpty()) field = "body";
            if (dbe instanceof UnrecognizedPropertyException upe) {
                message = "unknown property '" + upe.getPropertyName() + "'";
            } else if (dbe instanceof InvalidFormatException ife) {
                message = "value '" + ife.getValue() + "' is not a valid " + ife.getTargetType().getSimpleName();
            } else {
                message = dbe.getOriginalMessage();
            }
        } else if (cause instanceof JacksonException je) {
            message = je.getOriginalMessage();
        } else {
            message = e.getMostSpecificCause().getMessage();
        }
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ErrorCode.MALFORMED_REQUEST, "request body is not valid",
                List.of(new ApiError.Detail(field, message))));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> wrongMethod(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of(ErrorCode.MALFORMED_REQUEST,
                "method " + e.getMethod() + " is not supported; use POST"));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> wrongMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiError.of(ErrorCode.MALFORMED_REQUEST,
                "unsupported content type; send application/json"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noSuchEndpoint(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of(ErrorCode.MALFORMED_REQUEST, "no such endpoint: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("unexpected error: {}", e.getMessage(), e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of(ErrorCode.INTERNAL_ERROR, "unexpected error"));
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
