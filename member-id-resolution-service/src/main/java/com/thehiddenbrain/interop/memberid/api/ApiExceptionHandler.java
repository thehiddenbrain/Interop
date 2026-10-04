package com.thehiddenbrain.interop.memberid.api;

import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import com.thehiddenbrain.interop.memberid.domain.InvalidRequestException;
import com.thehiddenbrain.interop.memberid.domain.ResolutionException;
import com.thehiddenbrain.interop.memberid.mmi.MmiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every failure becomes the same error envelope. Jackson and framework messages are never echoed (they can
 * contain the offending value); the detail code and the JSON path are enough for Onyx support.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResolutionException.class)
    public ResponseEntity<ApiErrorResponse> business(ResolutionException e) {
        return respond(e.status(), e.code(), e.getMessage(), e.details(), e.mmiRequestId());
    }

    @ExceptionHandler(MmiException.class)
    public ResponseEntity<ApiErrorResponse> mmi(MmiException e) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(e.status());
        if (e.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            builder.header("Retry-After", "10");
        }
        return builder.body(new ApiErrorResponse(new ApiErrorResponse.Error(e.code(), e.getMessage(),
                List.of(new ErrorDetail(null, e.detail(), e.getMessage()))), CorrelationFilter.current(), e.requestId()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> unreadable(HttpMessageNotReadableException e) {
        Throwable cause = e.getCause();
        String code = "MALFORMED_JSON";
        String field = null;
        if (cause instanceof UnrecognizedPropertyException upe) {
            code = "UNKNOWN_PROPERTY";
            field = path(upe);
        } else if (cause instanceof InvalidFormatException ife) {
            code = "WRONG_JSON_TYPE";
            field = path(ife);
        } else if (cause instanceof MismatchedInputException mie) {
            code = "WRONG_JSON_TYPE";
            field = path(mie);
        }
        return respond(HttpStatus.BAD_REQUEST, InvalidRequestException.CODE, "request body could not be read",
                List.of(new ErrorDetail(field, code, "the request body is not valid JSON for this operation")), null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> mediaType(HttpMediaTypeNotSupportedException e) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, InvalidRequestException.CODE, "Content-Type must be application/json",
                List.of(new ErrorDetail(null, "UNSUPPORTED_MEDIA_TYPE", "send Content-Type: application/json")), null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, HttpMethod.POST.name())
                .body(new ApiErrorResponse(new ApiErrorResponse.Error(InvalidRequestException.CODE, "method not allowed",
                        List.of(new ErrorDetail(null, "METHOD_NOT_ALLOWED", "use POST"))), CorrelationFilter.current(), null));
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiErrorResponse> notAcceptable(HttpMediaTypeNotAcceptableException e) {
        return respond(HttpStatus.NOT_ACCEPTABLE, InvalidRequestException.CODE, "this service answers application/json only",
                List.of(new ErrorDetail(null, "NOT_ACCEPTABLE", "request a JSON representation")), null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> noRoute(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, InvalidRequestException.CODE, "no such route",
                List.of(new ErrorDetail(null, "ROUTE_NOT_FOUND", "the only operation is POST /api/v1/member-ids/resolve")), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> unexpected(Exception e) {
        log.error("unexpected failure correlationId={} type={}", CorrelationFilter.current(), e.getClass().getName(), e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected failure; see the service log with this correlationId",
                List.of(), null);
    }

    private static ResponseEntity<ApiErrorResponse> respond(HttpStatus status, String code, String message, List<ErrorDetail> details,
            String mmiRequestId) {
        return ResponseEntity.status(status)
                .body(new ApiErrorResponse(new ApiErrorResponse.Error(code, message, details), CorrelationFilter.current(), mmiRequestId));
    }

    private static String path(MismatchedInputException e) {
        if (e.getPath() == null || e.getPath().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        e.getPath().forEach(ref -> {
            if (ref.getPropertyName() != null) {
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(ref.getPropertyName());
            }
        });
        return sb.length() == 0 ? null : sb.toString();
    }
}
