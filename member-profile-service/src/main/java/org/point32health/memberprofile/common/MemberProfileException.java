package org.point32health.memberprofile.common;

import java.util.List;

/** Any failure the API reports to its caller. Carries the error code and optional field details. */
public class MemberProfileException extends RuntimeException {

    private final ErrorCode code;
    private final List<ApiError.Detail> details;

    public MemberProfileException(ErrorCode code, String message) {
        this(code, message, List.of(), null);
    }

    public MemberProfileException(ErrorCode code, String message, Throwable cause) {
        this(code, message, List.of(), cause);
    }

    public MemberProfileException(ErrorCode code, String message, List<ApiError.Detail> details, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static MemberProfileException memberNotFound(String memberId) {
        return new MemberProfileException(ErrorCode.MEMBER_NOT_FOUND, "member '" + memberId + "' not found");
    }

    public static MemberProfileException memberDataIncomplete(String memberId, String what) {
        return new MemberProfileException(ErrorCode.MEMBER_DATA_INCOMPLETE,
                "member '" + memberId + "' cannot be evaluated: " + what);
    }

    public static MemberProfileException ruleDataInvalid(String what) {
        return new MemberProfileException(ErrorCode.RULE_DATA_INVALID, "rule data invalid: " + what);
    }

    public ErrorCode getCode() {
        return code;
    }

    public List<ApiError.Detail> getDetails() {
        return details;
    }

    public ApiError toApiError() {
        return ApiError.of(code, getMessage(), details);
    }
}
