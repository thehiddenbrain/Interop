package org.point32health.memberprofile.common;

import java.util.List;

/** Shape of every error the REST API returns. */
public record ApiError(String status, ErrorCode code, String message, List<Detail> details) {

    public record Detail(String field, String message) {
    }

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError("ERROR", code, message, List.of());
    }

    public static ApiError of(ErrorCode code, String message, List<Detail> details) {
        return new ApiError("ERROR", code, message, details == null ? List.of() : List.copyOf(details));
    }
}
