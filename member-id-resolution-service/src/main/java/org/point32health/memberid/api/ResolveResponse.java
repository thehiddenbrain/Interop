package org.point32health.memberid.api;

import java.time.LocalDate;
import java.util.List;

/**
 * The 200 response of {@code POST /api/v1/member-ids/resolve} (one vendor). Optional blocks are absent when
 * they do not apply (global non-null inclusion); the one exception is {@code coverage.span.endDate}, which is
 * an explicit {@code null} for open-ended coverage. {@code message} is one human-readable sentence for the
 * outcome; {@code mmiMessage} is what MMI itself said when it had no member for the id (NOT_FOUND only).
 */
public record ResolveResponse(
        Outcome outcome,
        String message,
        MemberId memberId,
        String vendor,
        String company,
        String lineOfBusiness,
        LocalDate dateOfService,
        boolean dateOfServiceDefaulted,
        Coverage coverage,
        Ambiguity ambiguity,
        List<Candidate> candidates,
        String correlationId,
        String mmiRequestId,
        MmiNote mmiMessage) {

    /**
     * @param received  as Onyx sent it (surrounding whitespace removed); this exact value was sent to MMI
     * @param stored    exactly as MMI stores it (ACTIVE / INACTIVE only)
     * @param forVendor the stored id in the vendor's format; the only value that may go into a vendor payload
     * @param forVendorParts the two fields for vendors that take the 9 digits and the suffix separately
     */
    public record MemberId(String received, String stored, String forVendor, MemberIdParts forVendorParts) {
    }
}
