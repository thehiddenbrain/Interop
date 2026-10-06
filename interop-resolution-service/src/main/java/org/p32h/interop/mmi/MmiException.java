package org.p32h.interop.mmi;

import org.springframework.http.HttpStatus;

/**
 * MMI could not give a usable answer. MMI's contract has three error statuses: 404 (no member: not an exception,
 * answered 200 NOT_FOUND), 400 (bad request: forwarded as 400 {@code MEMBER_LOOKUP_REJECTED} with MMI's text) and 500
 * (internal error: 503 {@code MEMBER_LOOKUP_UNAVAILABLE}, Onyx may retry later). 503 is also the answer when MMI cannot be
 * reached; 502 when the endpoint answered something outside MMI's contract or a body the service cannot read.
 * Nothing in the codes or messages a caller receives names MMI: the wording is "the member lookup".
 */
public class MmiException extends RuntimeException {

    public static final String UNAVAILABLE = "MEMBER_LOOKUP_UNAVAILABLE";
    public static final String BAD_REQUEST = "MEMBER_LOOKUP_REJECTED";
    public static final String REJECTED = "MEMBER_LOOKUP_ERROR";
    public static final String INVALID_RESPONSE = "MEMBER_LOOKUP_INVALID_RESPONSE";

    private final HttpStatus status;
    private final String code;
    private final String detail;
    private final String requestId;

    private MmiException(HttpStatus status, String code, String detail, String requestId, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.requestId = requestId;
    }

    public static MmiException unavailable(String requestId, String detail, String message, Throwable cause) {
        return new MmiException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, detail, requestId, message, cause);
    }

    /** MMI answered 400: it could not process the request as sent. Forwarded to Onyx as a 400 with MMI's own text. */
    public static MmiException badRequest(String requestId, String detail, String message) {
        return new MmiException(HttpStatus.BAD_REQUEST, BAD_REQUEST, detail, requestId, message, null);
    }

    public static MmiException rejected(String requestId, String detail, String message) {
        return new MmiException(HttpStatus.BAD_GATEWAY, REJECTED, detail, requestId, message, null);
    }

    public static MmiException invalidResponse(String requestId, String detail, String message, Throwable cause) {
        return new MmiException(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE, detail, requestId, message, cause);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public String requestId() {
        return requestId;
    }
}
