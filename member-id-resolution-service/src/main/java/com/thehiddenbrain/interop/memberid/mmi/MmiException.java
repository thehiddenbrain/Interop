package com.thehiddenbrain.interop.memberid.mmi;

import org.springframework.http.HttpStatus;

/**
 * MMI could not give a usable answer. 503 when MMI could not be reached or answered 5xx (Onyx may retry
 * later); 502 when MMI rejected the request or returned something the service cannot read (someone has
 * to look).
 */
public class MmiException extends RuntimeException {

    public static final String UNAVAILABLE = "MMI_UNAVAILABLE";
    public static final String REJECTED = "MMI_ERROR";
    public static final String INVALID_RESPONSE = "MMI_INVALID_RESPONSE";

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
