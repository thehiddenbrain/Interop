package org.point32health.memberid.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * The 200 response of {@code POST /api/v1/member-ids/vendor-map}: the same verification and coverage answer as
 * {@code /resolve}, with the stored id rendered for every configured vendor instead of one. {@code vendorMemberIds}
 * is present only when a member was identified (ACTIVE / INACTIVE), one entry per vendor, sorted by vendor code.
 * {@code message} is one human-readable sentence for the outcome; {@code sourceMessage} is what the member lookup said
 * when it had no member for the id (NOT_FOUND only). Nothing in the body names MMI (owner). The correlation id travels in the {@code X-Correlation-Id} header.
 */
public record VendorMapResponse(
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
    @Schema(name = "VendorMapMemberId") // distinct from ResolveResponse.MemberId in the OpenAPI document
    public record MemberId(String received, String stored) {
    }

    /**
     * @param vendor        the vendor code as configured in {@code member-id.vendors}
     * @param memberId      the stored id in that vendor's format: what goes into that vendor's payload
     * @param memberIdParts the two fields for vendors that take the core and the suffix separately, else absent
     */
    public record VendorMemberId(String vendor, String memberId, MemberIdParts memberIdParts) {
    }
}
