package com.thehiddenbrain.interop.patientaccess.common;

import org.springframework.http.HttpStatus;

/** Error codes of the workbench's own API; the HTTP status is derived from the code. */
public enum ErrorCode {
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    NOT_SUPPORTED(HttpStatus.BAD_REQUEST),
    /** A URL was requested that is outside the environment's base URL or auth endpoints. */
    TARGET_NOT_ALLOWED(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    /** Token could not be obtained from the environment's authorization server. */
    AUTH_FAILED(HttpStatus.BAD_GATEWAY),
    /** The FHIR server answered with an error status. */
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY),
    /** The FHIR server could not be reached or timed out. */
    UPSTREAM_UNREACHABLE(HttpStatus.GATEWAY_TIMEOUT),
    STORAGE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
