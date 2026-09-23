package org.point32health.memberprofile.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/member-profile}. The member id travels in the body, never in the URL, so it
 * does not land in access logs or browser history.
 *
 * @param memberId      the member as known to MemberDomain, e.g. {@code HPxxxxxxx}
 * @param impersonating true when a CSR is impersonating the member; echoed as {@code member.isImpersonating}
 * @param explain       include the rule-by-rule evaluation trace (support and testing; never from the portal)
 */
public record MemberProfileRequest(
        @NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9_-]+", message = "must be letters, digits, '_' or '-'")
        String memberId,
        Boolean impersonating,
        Boolean explain) {

    public MemberProfileRequest {
        impersonating = Boolean.TRUE.equals(impersonating);
        explain = Boolean.TRUE.equals(explain);
    }

    public boolean isImpersonating() {
        return impersonating;
    }

    public boolean isExplain() {
        return explain;
    }
}
