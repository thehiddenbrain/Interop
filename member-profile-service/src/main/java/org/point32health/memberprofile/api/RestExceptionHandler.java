package org.point32health.memberprofile.api;

import org.point32health.memberprofile.common.ApiError;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Every error leaves the API as an {@link ApiError} with a fixed, caller-safe message per {@link ErrorCode};
 * the internal detail (upstream URL, rule row, exception text) is logged here and never returned.
 * Error responses carry {@code Cache-Control: no-store} like successes.
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(MemberProfileException.class)
    public ResponseEntity<ApiError> memberProfile(MemberProfileException e) {
        ErrorCode code = e.getCode();
        if (code == ErrorCode.RULE_DATA_INVALID || code == ErrorCode.INTERNAL_ERROR) {
            log.error("{}: {}", code, e.getDetail(), e);
        } else if (code.status().is5xxServerError()) {
            log.error("{}: {}", code, e.getDetail());
        } else {
            log.warn("{}: {}", code, e.getDetail());
        }
        return respond(e.toApiError());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        List<ApiError.Detail> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.Detail(f.getField(), f.getDefaultMessage())).toList();
        return respond(ApiError.of(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.message(), details));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        String field = "body";
        String message = "missing or not readable as JSON";
        if (e.getCause() instanceof DatabindException dbe) {
            String path = dbe.getPath().stream()
                    .map(ref -> ref.getPropertyName() == null ? "[" + ref.getIndex() + "]" : ref.getPropertyName())
                    .collect(Collectors.joining("."));
            if (!path.isEmpty()) field = path;
            if (dbe instanceof UnrecognizedPropertyException upe) {
                message = "unknown property '" + upe.getPropertyName() + "'";
            } else if (dbe instanceof InvalidFormatException ife) {
                message = "not a valid " + ife.getTargetType().getSimpleName();
            } else {
                message = "cannot be read";
            }
        }
        log.warn("MALFORMED_REQUEST: {}", e.getMessage());
        return respond(ApiError.of(ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.message(),
                List.of(new ApiError.Detail(field, message))));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> wrongMethod(HttpRequestMethodNotSupportedException e) {
        return respond(ApiError.of(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.message()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> wrongMediaType(HttpMediaTypeNotSupportedException e) {
        return respond(ApiError.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE.message()));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> notAcceptable(HttpMediaTypeNotAcceptableException e) {
        return respond(ApiError.of(ErrorCode.NOT_ACCEPTABLE, ErrorCode.NOT_ACCEPTABLE.message()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noSuchEndpoint(NoResourceFoundException e) {
        return respond(ApiError.of(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.message()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("unexpected error: {}", e.toString(), e);
        return respond(ApiError.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.message()));
    }

    private static ResponseEntity<ApiError> respond(ApiError body) {
        HttpStatus status = body.code().status();
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }
}
