package com.thehiddenbrain.interop.patientaccess.api;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import com.thehiddenbrain.interop.patientaccess.common.ApiError;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

/** Every error leaves the API as an {@link ApiError}. */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(WorkbenchException.class)
    public ResponseEntity<ApiError> workbench(WorkbenchException e) {
        HttpStatus status = e.getCode().status();
        if (status.is5xxServerError() && e.getCode() != ErrorCode.UPSTREAM_ERROR && e.getCode() != ErrorCode.AUTH_FAILED
                && e.getCode() != ErrorCode.UPSTREAM_UNREACHABLE) {
            log.error("{}: {}", e.getCode(), e.getMessage(), e);
        } else {
            log.warn("{}: {}", e.getCode(), e.getMessage());
        }
        return respond(status, e.toApiError());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        List<ApiError.Detail> details = new ArrayList<>();
        Throwable cause = e.getCause();
        if (cause instanceof DatabindException jme) {
            String path = jsonPath(jme);
            String message;
            if (jme instanceof UnrecognizedPropertyException upe) {
                message = "unknown property '" + upe.getPropertyName() + "'";
            } else if (jme instanceof InvalidFormatException ife) {
                message = "value '" + ife.getValue() + "' is not a valid " + ife.getTargetType().getSimpleName();
            } else {
                message = jme.getOriginalMessage();
            }
            details.add(new ApiError.Detail(path.isEmpty() ? "body" : path, message));
        } else {
            details.add(new ApiError.Detail("body", e.getMostSpecificCause().getMessage()));
        }
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ErrorCode.MALFORMED_REQUEST, "request body is not valid", details, null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        List<ApiError.Detail> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.Detail(f.getField(), f.getDefaultMessage())).toList();
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ErrorCode.VALIDATION_ERROR, "request rejected", details, null));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> badParameter(Exception e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ErrorCode.MALFORMED_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiError.of(ErrorCode.MALFORMED_REQUEST, "unsupported content type; send application/json"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of(ErrorCode.MALFORMED_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of(ErrorCode.NOT_FOUND, "no such endpoint: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of(ErrorCode.INTERNAL_ERROR, "unexpected error: " + e.getMessage()));
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error);
    }

    static String jsonPath(DatabindException e) {
        List<String> parts = new ArrayList<>();
        for (JacksonException.Reference ref : e.getPath()) {
            if (ref.getPropertyName() != null) {
                parts.add(ref.getPropertyName());
            } else if (ref.getIndex() >= 0 && !parts.isEmpty()) {
                parts.set(parts.size() - 1, parts.get(parts.size() - 1) + "[" + ref.getIndex() + "]");
            }
        }
        return String.join(".", parts);
    }
}
