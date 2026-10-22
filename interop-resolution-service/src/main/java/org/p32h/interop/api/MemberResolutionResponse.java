package org.p32h.interop.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.p32h.interop.domain.MemberName;

/**
 * The 200 response of {@code POST /v1/interop/resolve}. Lean by design: the outcome, one sentence, the member id as
 * received, as resolved and in every configured vendor's format with the payer and the member's name for that vendor's request, the line of
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
        @Schema(description = "ACTIVE: member found, coverage active on the date of service (the first day of a period). INACTIVE: member found, not covered on that date; message says why. NOT_FOUND: no member for this id, or none with coverage on that date or at any time in the last 24 months. AMBIGUOUS: several members match and nothing sent tells them apart; message says what to resend",
                requiredMode = Schema.RequiredMode.REQUIRED) Outcome outcome,
        @Schema(description = "One sentence for a person reading the answer. Branch on outcome, not on this text", requiredMode = Schema.RequiredMode.REQUIRED) String message,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MemberId memberId,
        @Schema(description = "The line of business Onyx routes on, from the plan in effect on the date of service, or from the member's most recent plan when none is in effect on it, for example SCO, MA-HMO, MA-PPO, D-SNP, MA-TOGETHER, RI-TOGETHER or MA-QHP; values can be added, so never treat the list as closed. When no rule applies, the member record's own category is passed through (for example MCR, PP, COM), and the field is absent when the record carries none. Present on ACTIVE and INACTIVE answers only. Agree with Point32Health how to route a value outside the list, or an absent one",
                example = "MA-HMO") String lineOfBusiness,
        @Schema(description = "The date evaluated; the first date when a period was asked about", requiredMode = Schema.RequiredMode.REQUIRED) LocalDate dateOfService,
        @Schema(description = "The last date evaluated; present only when the request asked about a period") LocalDate dateOfServiceEnd,
        @Schema(description = "true when no dateOfService was sent and today was used", requiredMode = Schema.RequiredMode.REQUIRED) boolean dateOfServiceDefaulted,
        @Schema(description = "The coverage flag and the coverage period behind it. coverageId, effectiveDate and endDate are present when active, and when inactive only because the period ends before the last date asked about; otherwise only active is sent and message says why. Present on ACTIVE and INACTIVE answers only") Coverage coverage,
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
            @Schema(description = "The id the lookup resolved to, as the plan stores it; for a converted member a different number from the one received. Present on ACTIVE and INACTIVE answers only") String resolved,
            @Schema(description = "The resolved id in every configured vendor's format, each with the payer and the member's name for that vendor's request; one entry per vendor, sorted by vendor code. Present on ACTIVE and INACTIVE answers only") List<VendorMemberId> forVendors) {
    }

    /**
     * Everything Onyx puts on one vendor's request.
     *
     * @param vendor    the vendor code as configured in {@code member-id.vendors}
     * @param memberId  the stored id in that vendor's format: what goes into that vendor's payload
     * @param payerId   the payer id that vendor keys on: its own for the member's company when configured, else the default
     * @param payerName the payer name that goes with it
     * @param name      the member's name as the plan has it on record, one FHIR HumanName; null when the plan has none
     */
    @Schema(description = "Everything Onyx puts on one vendor's request")
    public record VendorMemberId(
            @Schema(description = "The vendor code, for example EVICORE; today CARELON, EVICORE, EVOLENT, MHK, ONYX and OPTUM. Vendors can be added, so never treat the list as closed", example = "EVICORE") String vendor,
            @Schema(description = "The resolved id in this vendor's format: the member id to put on this vendor's request") String memberId,
            @Schema(description = "The payer id to put on this vendor's request; it differs by vendor and by the member's heritage company and plan population, so use the entry's value as given", example = "TUFTS") String payerId,
            @Schema(description = "The payer name that goes with payerId", example = "TUFTS") String payerName,
            @Schema(description = "The member's name as the plan has it on record, one entry in the FHIR HumanName shape: given[0] the first name, "
                    + "given[1] the middle name when there is one, family the last name, text all of them joined. Put it on the vendor's request as given. "
                    + "Absent only when the plan has no name on record; then use the name from the EMR") List<HumanName> name) {
    }

    /**
     * The member's name in the FHIR HumanName shape, the form the UM vendors take.
     *
     * @param text   the full name: the given names and the family name, joined with spaces
     * @param family the last name, or null when the record has none
     * @param given  the first name, then the middle name when there is one; null when the record has no first name (a
     *               middle name alone is not a given name and is left out of text as well)
     */
    @Schema(description = "A name in the FHIR HumanName shape; either given or family is always present")
    public record HumanName(
            @Schema(description = "The full name: the given names and the family name joined with spaces", example = "Taylor A Morgan") String text,
            @Schema(description = "The last name; absent when the plan has none on record", example = "Morgan") String family,
            @Schema(description = "The first name, then the middle name when there is one; absent when the plan has no first name on record") List<String> given) {

        /** One HumanName for the record's name, or null when there is no name. */
        public static List<HumanName> of(MemberName name) {
            if (name == null) {
                return null;
            }
            List<String> given = name.first() == null ? null
                    : name.middle() == null ? List.of(name.first()) : List.of(name.first(), name.middle());
            String text = Stream.concat(given == null ? Stream.<String>empty() : given.stream(), Stream.ofNullable(name.last()))
                    .collect(Collectors.joining(" "));
            return List.of(new HumanName(text, name.last(), given));
        }
    }
}
