package org.p32h.interop.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Every non-200 answer has this shape. {@code error.code} is stable; {@code details[].code} refines it. {@code requestId}
 * echoes the caller's id when the request carried a usable one. {@code traceId} is the id of the lookup behind the
 * answer, present once the lookup was made. Nothing in the body names MMI.
 */
@Schema(description = "The shape of every non-200 answer")
public record ApiErrorResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Error error,
        @Schema(description = "The X-Correlation-Id of the call, echoed or generated", requiredMode = Schema.RequiredMode.REQUIRED) String correlationId,
        @Schema(description = "The caller's requestId, when the request carried a usable one") String requestId,
        @Schema(description = "The id of the member lookup behind the answer, present once the lookup was made") String traceId) {

    @Schema(description = "The error: a stable code to branch on, a message for a person, and one detail per problem")
    public record Error(
            @Schema(description = "INVALID_REQUEST, MEMBER_LOOKUP_REJECTED, DOB_MISMATCH, MEMBER_LOOKUP_ERROR, MEMBER_LOOKUP_INVALID_RESPONSE, MEMBER_LOOKUP_UNAVAILABLE, MEMBER_PLAN_ERROR, MEMBER_PLAN_INVALID_RESPONSE, MEMBER_PLAN_UNAVAILABLE or INTERNAL_ERROR",
                    example = "INVALID_REQUEST", requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(description = "One sentence for a person", requiredMode = Schema.RequiredMode.REQUIRED) String message,
            @Schema(description = "One entry per problem found; every problem with a request is reported together") List<ErrorDetail> details) {
    }
}
