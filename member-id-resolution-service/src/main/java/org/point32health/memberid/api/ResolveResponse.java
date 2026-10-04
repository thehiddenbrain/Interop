package org.point32health.memberid.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.point32health.memberid.domain.CoverageReason;
import java.time.LocalDate;
import java.util.List;

/**
 * The 200 response. Optional blocks are absent when they do not apply (global non-null inclusion); the one
 * exception is {@code coverage.span.endDate}, which is an explicit {@code null} for open-ended coverage.
 */
public record ResolveResponse(
        Outcome outcome,
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
        String mmiRequestId) {

    /**
     * @param received  as Onyx sent it (surrounding whitespace removed); this exact value was sent to MMI
     * @param stored    exactly as MMI stores it (ACTIVE / INACTIVE only)
     * @param forVendor the stored id in the vendor's format; the only value that may go into a vendor payload
     * @param forVendorParts the two fields for vendors that take the 9 digits and the suffix separately
     */
    public record MemberId(String received, String stored, String forVendor, Parts forVendorParts) {
    }

    public record Parts(String memberId, String suffix) {
    }

    public record Coverage(boolean active, CoverageReason reason, Span span, LocalDate lastEndDate, LocalDate nextEffectiveDate) {
    }

    public record Span(LocalDate effectiveDate, @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate) {
    }

    public record Ambiguity(String reason, String hint) {
    }

    public record Candidate(String storedMemberId, String company, String lineOfBusiness, boolean coverageActive) {
    }
}
