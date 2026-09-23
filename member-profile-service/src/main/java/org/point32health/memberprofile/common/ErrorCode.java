package org.point32health.memberprofile.common;

import org.springframework.http.HttpStatus;

/**
 * Error codes of this service's API. The HTTP status and the caller-facing message are derived from the
 * code; the detailed cause stays in the server log ({@link MemberProfileException#getDetail()}).
 */
public enum ErrorCode {
    /** The request body is not valid JSON or cannot be mapped to the request record. */
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "request body is not valid"),
    /** The request was read but a field is invalid (missing member id, bad format). */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "request is not valid"),
    /** No credentials, or credentials that do not match the configured API key. */
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "authentication required"),
    /** The explain trace is disabled in this environment. */
    EXPLAIN_DISABLED(HttpStatus.FORBIDDEN, "explain is disabled in this environment"),
    /** Unknown path. */
    NOT_FOUND(HttpStatus.NOT_FOUND, "no such endpoint"),
    /** Only POST is supported. */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "method not allowed; use POST"),
    /** The Accept header excludes application/json. */
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "only application/json can be produced"),
    /** The request body is larger than the configured limit. */
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "request body too large"),
    /** The Content-Type is not application/json. */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported content type; send application/json"),
    /** MemberDomain does not know the member id. */
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "member not found"),
    /** MemberDomain knows the member but returned data the rules cannot be evaluated on. */
    MEMBER_DATA_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT, "member data cannot be evaluated"),
    /** MemberDomain answered with an error status or an unreadable body. */
    MEMBER_DOMAIN_ERROR(HttpStatus.BAD_GATEWAY, "member data is temporarily unavailable"),
    /** MemberDomain could not be reached or timed out. */
    MEMBER_DOMAIN_UNREACHABLE(HttpStatus.GATEWAY_TIMEOUT, "member data is temporarily unavailable"),
    /** A rule row in the database cannot be interpreted (unknown operator or relationship label). */
    RULE_DATA_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "rule configuration is invalid"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected error");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    /** The message the caller sees. Never contains internal details. */
    public String message() {
        return message;
    }
}
