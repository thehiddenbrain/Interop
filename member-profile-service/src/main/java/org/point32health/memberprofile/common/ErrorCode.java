package org.point32health.memberprofile.common;

import org.springframework.http.HttpStatus;

/** Error codes of this service's API; the HTTP status is derived from the code. */
public enum ErrorCode {
    /** The request could not be read: bad JSON, wrong method, wrong media type, unknown path. */
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST),
    /** The request was read but a field is invalid (missing member id, bad format). */
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST),
    /** MemberDomain does not know the member id. */
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** MemberDomain knows the member but returned data the rules cannot be evaluated on (no company, no age, unknown relationship code). */
    MEMBER_DATA_INCOMPLETE(HttpStatus.UNPROCESSABLE_CONTENT),
    /** MemberDomain answered with an error status. */
    MEMBER_DOMAIN_ERROR(HttpStatus.BAD_GATEWAY),
    /** MemberDomain could not be reached or timed out. */
    MEMBER_DOMAIN_UNREACHABLE(HttpStatus.GATEWAY_TIMEOUT),
    /** A rule row in the database cannot be interpreted (unknown operator, relationship, or segment). */
    RULE_DATA_INVALID(HttpStatus.INTERNAL_SERVER_ERROR),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
