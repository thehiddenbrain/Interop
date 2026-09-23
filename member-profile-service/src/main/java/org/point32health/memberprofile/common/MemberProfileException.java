package org.point32health.memberprofile.common;

import java.util.List;

/**
 * Any failure the API reports to its caller.
 * <p>
 * The caller sees only {@link ErrorCode#message()} and the optional field {@link #getDetails() details};
 * {@link #getDetail()} is the internal explanation (what exactly was wrong, which row, which URL) and goes
 * to the server log only, so upstream hosts, table names and rule ids never leave the service.
 */
public class MemberProfileException extends RuntimeException {

    private final ErrorCode code;
    private final List<ApiError.Detail> details;

    public MemberProfileException(ErrorCode code, String detail) {
        this(code, detail, List.of(), null);
    }

    public MemberProfileException(ErrorCode code, String detail, Throwable cause) {
        this(code, detail, List.of(), cause);
    }

    public MemberProfileException(ErrorCode code, String detail, List<ApiError.Detail> details, Throwable cause) {
        super(detail, cause);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public static MemberProfileException memberNotFound(String memberId) {
        return new MemberProfileException(ErrorCode.MEMBER_NOT_FOUND, "member " + MemberIds.forLog(memberId) + " not found");
    }

    /**
     * @param field which part of the member record is unusable, reported to the caller as a detail
     * @param why   the internal explanation, logged only
     */
    public static MemberProfileException memberDataIncomplete(String memberId, String field, String why) {
        return new MemberProfileException(ErrorCode.MEMBER_DATA_INCOMPLETE,
                "member " + MemberIds.forLog(memberId) + ": " + why,
                List.of(new ApiError.Detail(field, "missing or not recognized")), null);
    }

    public static MemberProfileException ruleDataInvalid(String what) {
        return new MemberProfileException(ErrorCode.RULE_DATA_INVALID, what);
    }

    public ErrorCode getCode() {
        return code;
    }

    /** Internal explanation for the log; never sent to the caller. */
    public String getDetail() {
        return getMessage();
    }

    public List<ApiError.Detail> getDetails() {
        return details;
    }

    public ApiError toApiError() {
        return ApiError.of(code, code.message(), details);
    }
}
