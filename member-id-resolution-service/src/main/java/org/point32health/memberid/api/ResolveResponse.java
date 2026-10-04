package org.point32health.memberid.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The 200 response of {@code POST /api/v1/member-ids/resolve} (one vendor). Lean by design: the outcome, one
 * sentence, the member id as received / stored / for the vendor, the line of business (Onyx routes on it), the
 * date evaluated, the coverage flag with its period, the candidates when AMBIGUOUS, and a trace id for support
 * (the id of the lookup behind the answer). Optional blocks are absent when they do not apply (global non-null inclusion); the one exception is
 * {@code coverage.span.endDate}, an explicit {@code null} for open-ended coverage. {@code sourceMessage} is what the
 * member lookup said when it had no member for the id (NOT_FOUND only). Nothing in the body names MMI (owner). The correlation id travels in the
 * {@code X-Correlation-Id} response header.
 */
public record ResolveResponse(
        Outcome outcome,
        String message,
        MemberId memberId,
        String lineOfBusiness,
        LocalDate dateOfService,
        boolean dateOfServiceDefaulted,
        Coverage coverage,
        List<Candidate> candidates,
        String traceId,
        SourceMessage sourceMessage) {

    /**
     * @param received  as Onyx sent it (surrounding whitespace removed); this exact value was sent to MMI
     * @param stored    exactly as MMI stores it (ACTIVE / INACTIVE only)
     * @param forVendor the stored id in the vendor's format; the only value that may go into a vendor payload
     * @param forVendorParts the two fields for vendors that take the core and the suffix separately
     */
    public record MemberId(String received, String stored, String forVendor, MemberIdParts forVendorParts) {
    }
}
