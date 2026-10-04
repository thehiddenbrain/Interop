package org.point32health.memberid.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * The 200 response of {@code POST /api/v1/member-ids/vendor-map}: the same verification and coverage answer as
 * {@code /resolve}, with the stored id rendered for every configured vendor instead of one. {@code vendorMemberIds}
 * is present only when a member was identified (ACTIVE / INACTIVE), one entry per vendor, sorted by vendor code.
 */
public record VendorMapResponse(
        Outcome outcome,
        MemberId memberId,
        String company,
        String lineOfBusiness,
        LocalDate dateOfService,
        boolean dateOfServiceDefaulted,
        Coverage coverage,
        Ambiguity ambiguity,
        List<Candidate> candidates,
        List<VendorMemberId> vendorMemberIds,
        String correlationId,
        String mmiRequestId) {

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
     * @param memberIdParts the two fields for vendors that take the 9 digits and the suffix separately, else absent
     */
    public record VendorMemberId(String vendor, String memberId, MemberIdParts memberIdParts) {
    }
}
