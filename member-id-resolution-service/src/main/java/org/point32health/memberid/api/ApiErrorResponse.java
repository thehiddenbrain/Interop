package org.point32health.memberid.api;

import java.util.List;

/**
 * Every non-200 answer has this shape. {@code error.code} is stable; {@code details[].code} refines it. {@code traceId}
 * is the id of the lookup behind the answer, present once the lookup was made. Nothing in the body names MMI.
 */
public record ApiErrorResponse(Error error, String correlationId, String traceId) {

    public record Error(String code, String message, List<ErrorDetail> details) {
    }
}
