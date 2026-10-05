package org.point32health.interop.domain;

import org.point32health.interop.api.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

/** A business error that maps to one HTTP status and one stable error code. */
public class ResolutionException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ErrorDetail> details;

    public ResolutionException(HttpStatus status, String code, String message, List<ErrorDetail> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ErrorDetail> details() {
        return details;
    }

    private String mmiRequestId;

    /** Set when the failure happened after MMI answered, so the error body can carry the trace id. */
    public ResolutionException withMmiRequestId(String requestId) {
        this.mmiRequestId = requestId;
        return this;
    }

    public String mmiRequestId() {
        return mmiRequestId;
    }
}
