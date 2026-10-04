package org.point32health.memberid.api;

import java.util.List;

/** Every non-200 answer has this shape. {@code error.code} is stable; {@code details[].code} refines it. */
public record ApiErrorResponse(Error error, String correlationId, String mmiRequestId) {

    public record Error(String code, String message, List<ErrorDetail> details) {
    }
}
