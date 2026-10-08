package org.p32h.interop.memberinfo;

import org.springframework.http.HttpStatus;

/**
 * The member information service could not give a usable answer. 503 {@code MEMBER_PLAN_UNAVAILABLE} when it cannot be
 * reached, times out or answers 5xx, 429 or 408 (Onyx may retry later); 502 {@code MEMBER_PLAN_ERROR} for any other status
 * but 200 and 404; 502 {@code MEMBER_PLAN_INVALID_RESPONSE} for a body that cannot be read. Nothing a caller receives names
 * the service: the wording is "the member plan lookup".
 */
public class MemberInfoException extends RuntimeException {

    public static final String UNAVAILABLE = "MEMBER_PLAN_UNAVAILABLE";
    public static final String REJECTED = "MEMBER_PLAN_ERROR";
    public static final String INVALID_RESPONSE = "MEMBER_PLAN_INVALID_RESPONSE";

    private final HttpStatus status;
    private final String code;
    private final String detail;
    private final String traceId;

    private MemberInfoException(HttpStatus status, String code, String detail, String traceId, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.detail = detail;
        this.traceId = traceId;
    }

    public static MemberInfoException unavailable(String detail, String message, Throwable cause) {
        return new MemberInfoException(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE, detail, null, message, cause);
    }

    public static MemberInfoException rejected(String detail, String message) {
        return new MemberInfoException(HttpStatus.BAD_GATEWAY, REJECTED, detail, null, message, null);
    }

    public static MemberInfoException invalidResponse(String detail, String message, Throwable cause) {
        return new MemberInfoException(HttpStatus.BAD_GATEWAY, INVALID_RESPONSE, detail, null, message, cause);
    }

    /** The same failure carrying the member lookup's request id, returned to Onyx as {@code traceId}. */
    public MemberInfoException withTraceId(String traceId) {
        return new MemberInfoException(status, code, detail, traceId, getMessage(), getCause());
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

    public String traceId() {
        return traceId;
    }
}
