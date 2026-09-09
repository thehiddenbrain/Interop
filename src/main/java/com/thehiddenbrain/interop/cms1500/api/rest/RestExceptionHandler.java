package com.thehiddenbrain.interop.cms1500.api.rest;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.thehiddenbrain.interop.cms1500.contract.ClaimFault;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.contract.FaultStatus;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;

/** Every error leaves the REST API as a {@link ClaimFault}, the same shape the SOAP fault detail carries. */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    public static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case MALFORMED_REQUEST, VALIDATION_ERROR -> HttpStatus.BAD_REQUEST;
            case BUNDLE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BUNDLE_EXISTS -> HttpStatus.CONFLICT;
            case NO_ATTACHMENTS, UNSUPPORTED_ATTACHMENT, ATTACHMENT_UNREADABLE -> HttpStatus.UNPROCESSABLE_ENTITY;
            case TEMPLATE_ERROR, STORAGE_ERROR, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    @ExceptionHandler(ClaimException.class)
    public ResponseEntity<ClaimFault> claimError(ClaimException e) {
        HttpStatus status = statusFor(e.getCode());
        if (status.is5xxServerError()) {
            log.error("claim {}: {}", e.getClaimNumber(), e.getMessage(), e);
        } else {
            log.warn("claim {}: {} {}", e.getClaimNumber(), e.getCode(), e.getMessage());
        }
        return respond(status, e.toFault());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ClaimFault> unreadable(HttpMessageNotReadableException e) {
        ClaimFault fault = fault(ErrorCode.MALFORMED_REQUEST, "request body is not a valid CMS-1500 claim");
        Throwable cause = e.getCause();
        if (cause instanceof JsonMappingException jme) {
            String path = jsonPath(jme);
            String message;
            if (jme instanceof UnrecognizedPropertyException upe) {
                message = "unknown property '" + upe.getPropertyName() + "'; known properties: "
                        + upe.getKnownPropertyIds();
            } else if (jme instanceof InvalidFormatException ife) {
                message = "value '" + ife.getValue() + "' is not a valid " + ife.getTargetType().getSimpleName();
            } else {
                message = jme.getOriginalMessage();
            }
            fault.getDetails().add(ClaimException.detail(path.isEmpty() ? "body" : path, message));
        } else {
            fault.getDetails().add(ClaimException.detail("body", e.getMostSpecificCause().getMessage()));
        }
        log.warn("malformed request: {}", fault.getDetails().isEmpty() ? e.getMessage() : fault.getDetails().get(0).getMessage());
        return respond(HttpStatus.BAD_REQUEST, fault);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ClaimFault> mediaType(HttpMediaTypeNotSupportedException e) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, fault(ErrorCode.MALFORMED_REQUEST,
                "unsupported content type; send application/json"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ClaimFault> method(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, fault(ErrorCode.MALFORMED_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ClaimFault> notFound(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, fault(ErrorCode.MALFORMED_REQUEST, "no such endpoint: " + e.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ClaimFault> unexpected(Exception e) {
        log.error("unexpected error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, fault(ErrorCode.INTERNAL_ERROR, "unexpected error: " + e.getMessage()));
    }

    private static ResponseEntity<ClaimFault> respond(HttpStatus status, ClaimFault fault) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(fault);
    }

    private static ClaimFault fault(ErrorCode code, String message) {
        ClaimFault fault = new ClaimFault();
        fault.setStatus(FaultStatus.ERROR);
        fault.setCode(code);
        fault.setMessage(message);
        return fault;
    }

    /** patient.address.zip style path from Jackson's reference chain. */
    static String jsonPath(JsonMappingException e) {
        List<String> parts = new ArrayList<>();
        for (JsonMappingException.Reference ref : e.getPath()) {
            if (ref.getFieldName() != null) {
                parts.add(ref.getFieldName());
            } else if (ref.getIndex() >= 0 && !parts.isEmpty()) {
                parts.set(parts.size() - 1, parts.get(parts.size() - 1) + "[" + ref.getIndex() + "]");
            }
        }
        return String.join(".", parts);
    }
}
