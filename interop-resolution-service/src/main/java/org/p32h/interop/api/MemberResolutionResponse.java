package org.p32h.interop.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The 200 response of {@code POST /v1/interop/resolve}. Lean by design: the outcome, one sentence, the member
 * id as received and as stored, the line of business (Onyx routes on it), the date evaluated ({@code dateOfServiceEnd}
 * too when a period was asked about), the coverage flag with its period and id, the candidates when AMBIGUOUS, the id
 * in every configured vendor's format, and a trace id for support (the id of the lookup behind the answer).
 * {@code vendorMemberIds} is present only when a member was identified (ACTIVE / INACTIVE), one entry per vendor,
 * sorted by vendor code. Optional blocks are absent when they do not apply (global non-null inclusion); the one
 * exception is {@code coverage.span.endDate}, an explicit {@code null} for open-ended coverage. {@code sourceMessage}
 * is what the member lookup said when it had no member for the id (NOT_FOUND only). Nothing in the body names MMI
 * (owner). The correlation id travels in the {@code X-Correlation-Id} response header.
 */
public record MemberResolutionResponse(
        Outcome outcome,
        String message,
        MemberId memberId,
        String lineOfBusiness,
        LocalDate dateOfService,
        LocalDate dateOfServiceEnd,
        boolean dateOfServiceDefaulted,
        Coverage coverage,
        List<Candidate> candidates,
        List<VendorMemberId> vendorMemberIds,
        String traceId,
        SourceMessage sourceMessage) {

    /**
     * @param received as Onyx sent it (surrounding whitespace removed); this exact value was sent to MMI
     * @param stored   exactly as MMI stores it (ACTIVE / INACTIVE only)
     */
    public record MemberId(String received, String stored) {
    }

    /**
     * @param vendor   the vendor code as configured in {@code member-id.vendors}
     * @param memberId the stored id in that vendor's format: what goes into that vendor's payload
     */
    public record VendorMemberId(String vendor, String memberId) {
    }
}
