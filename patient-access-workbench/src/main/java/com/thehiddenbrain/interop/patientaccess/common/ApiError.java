package com.thehiddenbrain.interop.patientaccess.common;

import java.util.ArrayList;
import java.util.List;

/** Shape of every error the REST API returns. */
public record ApiError(String status, ErrorCode code, String message, List<Detail> details, Upstream upstream) {

    public record Detail(String field, String message) {
    }

    /** What the FHIR / authorization server answered, when the error comes from there. */
    public record Upstream(Integer httpStatus, String url, String body, String requestId) {
    }

    public static ApiError of(ErrorCode code, String message) {
        return new ApiError("ERROR", code, message, new ArrayList<>(), null);
    }

    public static ApiError of(ErrorCode code, String message, List<Detail> details, Upstream upstream) {
        return new ApiError("ERROR", code, message, details == null ? new ArrayList<>() : new ArrayList<>(details), upstream);
    }
}
