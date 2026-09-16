package com.thehiddenbrain.interop.patientaccess.common;

import java.util.ArrayList;
import java.util.List;

/** Any failure the API reports to its caller. Carries the error code, optional field details and upstream info. */
public class WorkbenchException extends RuntimeException {

    private final ErrorCode code;
    private final List<ApiError.Detail> details;
    private final ApiError.Upstream upstream;

    public WorkbenchException(ErrorCode code, String message) {
        this(code, message, List.of(), null, null);
    }

    public WorkbenchException(ErrorCode code, String message, Throwable cause) {
        this(code, message, List.of(), null, cause);
    }

    public WorkbenchException(ErrorCode code, String message, List<ApiError.Detail> details) {
        this(code, message, details, null, null);
    }

    public WorkbenchException(ErrorCode code, String message, List<ApiError.Detail> details, ApiError.Upstream upstream, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details == null ? new ArrayList<>() : new ArrayList<>(details);
        this.upstream = upstream;
    }

    public static WorkbenchException notFound(String what, String id) {
        return new WorkbenchException(ErrorCode.NOT_FOUND, what + " '" + id + "' not found");
    }

    public static WorkbenchException validation(String message, List<ApiError.Detail> details) {
        return new WorkbenchException(ErrorCode.VALIDATION_ERROR, message, details);
    }

    public static ApiError.Detail detail(String field, String message) {
        return new ApiError.Detail(field, message);
    }

    public ErrorCode getCode() {
        return code;
    }

    public List<ApiError.Detail> getDetails() {
        return details;
    }

    public ApiError.Upstream getUpstream() {
        return upstream;
    }

    public ApiError toApiError() {
        return ApiError.of(code, getMessage(), details, upstream);
    }
}
