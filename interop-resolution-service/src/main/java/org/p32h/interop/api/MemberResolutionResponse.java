package org.p32h.interop.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

/**
 * The 200 response of {@code POST /v1/interop/resolve}. Lean by design: the outcome, one sentence, the member id as
 * received, as resolved and in every configured vendor's format with the payer for that vendor's request, the line of
 * business (Onyx routes on it), the date
 * evaluated ({@code dateOfServiceEnd} too when a period was asked about), the coverage flag with its period and id, the
 * caller's request id, and a trace id for support (the id of the lookup behind the answer). An AMBIGUOUS answer lists
 * nobody: it says in {@code message} what to resend. Optional blocks are
 * absent when they do not apply (global non-null inclusion); an open-ended coverage period ends {@code 3999-12-31}.
 * {@code sourceMessage} is what the member lookup said when it had no member for the id (NOT_FOUND only). Nothing in
 * the body names MMI. The correlation id travels in the {@code X-Correlation-Id} response header.
 */
@Schema(description = "The answer: the outcome Onyx branches on, one sentence for a person, the member id as received, as resolved and in every vendor's format with that vendor's payer, the line of business, the date evaluated, the coverage flag with its period, the caller's requestId and a traceId for support. A field that does not apply is absent, never null")
public record MemberResolutionResponse(
        @Schema(description = "ACTIVE: member found, coverage active on the date of service (or on every day of the period). INACTIVE: member found, not covered on that date or not on every day of the period; message says why. NOT_FOUND: no member for this id on that date. AMBIGUOUS: several members match and nothing sent tells them apart; message says what to resend",
                requiredMode = Schema.RequiredMode.REQUIRED) Outcome outcome,
        @Schema(description = "One sentence for a person reading the answer. Branch on outcome, not on this text", requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MemberId memberId,
        @Schema(description = "The line of business Onyx routes on, from the plan in effect on the date of service: SCO, MA-HMO, MA-PPO, D-SNP, MA-TOGETHER, RI-TOGETHER or MA-QHP. When no rule applies, the member record's own category is passed through (MCR, PP, COM today; other values may appear), and the field is absent when the record carries none. ACTIVE and INACTIVE only. Agree with Point32Health how to route a value outside the list, or an absent one",
                example = "MA-HMO") String lineOfBusiness,
        @Schema(description = "The date evaluated; the first date when a period was asked about", requiredMode = Schema.RequiredMode.REQUIRED) LocalDate dateOfService,
        @Schema(description = "The last date evaluated; present only when the request asked about a period") LocalDate dateOfServiceEnd,
        @Schema(description = "true when no dateOfService was sent and today was used", requiredMode = Schema.RequiredMode.REQUIRED) boolean dateOfServiceDefaulted,
        @Schema(description = "The coverage flag and the coverage period behind it. ACTIVE and INACTIVE only") Coverage coverage,
        @Schema(description = "The caller's requestId, echoed", requiredMode = Schema.RequiredMode.REQUIRED) String requestId,
        @Schema(description = "The id of the member lookup behind this answer; quote it to support", requiredMode = Schema.RequiredMode.REQUIRED) String traceId,
        @Schema(description = "What the member lookup said when it had no member for the id. NOT_FOUND only, and only when the lookup sent a message") SourceMessage sourceMessage) {

    /**
     * @param received   as Onyx sent it (surrounding whitespace removed); this exact value was sent to MMI
     * @param resolved   the id it resolved to, exactly as MMI stores it for the member it settled on (ACTIVE / INACTIVE
     *                   only); every vendor format and the coverage id are built from it
     * @param forVendors the resolved id in every configured vendor's format, one entry per vendor sorted by vendor code
     *                   (ACTIVE / INACTIVE only)
     */
    @Schema(description = "The member id as received, as resolved, and in every vendor's format")
    public record MemberId(
            @Schema(description = "The member id as sent, surrounding whitespace removed", requiredMode = Schema.RequiredMode.REQUIRED) String received,
            @Schema(description = "The id the lookup resolved to, as the plan stores it; for a converted member a different number from the one received. ACTIVE and INACTIVE only") String resolved,
            @Schema(description = "The resolved id in every configured vendor's format, each with the payer for that vendor's request; one entry per vendor, sorted by vendor code. ACTIVE and INACTIVE only") List<VendorMemberId> forVendors) {
    }

    /**
     * Everything Onyx puts on one vendor's request.
     *
     * @param vendor    the vendor code as configured in {@code member-id.vendors}
     * @param memberId  the stored id in that vendor's format: what goes into that vendor's payload
     * @param payerId   the payer id that vendor keys on: its own for the member's company when configured, else the default
     * @param payerName the payer name that goes with it
     */
    @Schema(description = "Everything Onyx puts on one vendor's request")
    public record VendorMemberId(
            @Schema(description = "The vendor code", example = "EVICORE", allowableValues = {"CARELON", "EVICORE", "EVOLENT", "MHK", "ONYX", "OPTUM"}) String vendor,
            @Schema(description = "The resolved id in this vendor's format: the member id to put on this vendor's request") String memberId,
            @Schema(description = "The payer id to put on this vendor's request; it differs by vendor and by the member's heritage company, so use the entry's value as given", example = "TUFTS") String payerId,
            @Schema(description = "The payer name that goes with payerId", example = "TUFTS") String payerName) {
    }
}
