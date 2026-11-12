package org.p32h.interop.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * The coverage answer for the date (or period) of service, flat, in this order:
 * {@code coverageId}, {@code active}, {@code effectiveDate}, {@code endDate}. {@code active} is the flag Onyx acts on;
 * it comes from the coverage decision made on MMI's records. The id and the two dates are those of the member's row in
 * the coverage view ({@code member_coverage_rel_v}): the row that covers the date of service (the first day of a period
 * of service), else the member's most recent row, the one that ends last. They are what Onyx holds from the coverage
 * extract, and they need not agree with the flag: Onyx branches on {@code active}, never on the dates. An open end is
 * {@code 3999-12-31}. {@code coverageId} (in the form the Onyx requirement sets) names that row: MEMBER_ID + EFF_DATE +
 * END_DATE run together with no separator, the resolved member id reduced to letters and digits, the dates as
 * {@code yyyyMMdd}. The id and the dates are absent when the view has no row for the member. Why the member is inactive
 * is said in {@code message}.
 */
@Schema(description = "The coverage flag and the member's coverage record. active says whether coverage holds on the date of service. coverageId, effectiveDate and endDate are those of the plan's coverage record for the member: the record that covers the date of service (the first day of a period of service) when there is one, otherwise the member's most recent record, the one that ends last. The dates need not agree with the flag, so branch on active, never on the dates. message says why the member is inactive")
public record Coverage(
        @Schema(description = "An id for the coverage record: the resolved id reduced to letters and digits, then the record's effective and end dates as yyyyMMdd, run together with no separator (39991231 for an open end). Present whenever the dates are; absent when the plan holds no coverage record for the member") String coverageId,
        @Schema(description = "The flag: true when coverage holds on the date of service (the first day of a period of service)", requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
        @Schema(description = "First day of the coverage record") LocalDate effectiveDate,
        @Schema(description = "Last day of the coverage record; 3999-12-31 for an open end") LocalDate endDate) {
}
