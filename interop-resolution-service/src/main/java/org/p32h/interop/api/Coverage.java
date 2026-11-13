package org.p32h.interop.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * The coverage answer for the date (or period) of service, flat, in this order:
 * {@code coverageId}, {@code active}, {@code effectiveDate}, {@code endDate}. All four come from the member's rows in
 * the coverage view ({@code member_coverage_rel_v}), which hold what Onyx has from the coverage extract. {@code active}
 * is the flag Onyx acts on: true when a row covers the date of service (the first day of a period of service). The id
 * and the two dates are those of that row, else of the member's most recent row, the one that ends last, so on an
 * active answer the dates include the date of service. An open end is {@code 3999-12-31}. {@code coverageId} (in the
 * form the Onyx requirement sets) names that row: MEMBER_ID + EFF_DATE + END_DATE run together with no separator, the
 * resolved member id reduced to letters and digits, the dates as {@code yyyyMMdd}. The id and the dates are absent when
 * the view has no row for the member, who is then not covered. Why the member is inactive is said in {@code message}.
 */
@Schema(description = "The coverage flag and the member's coverage record. active says whether coverage holds on the date of service: it is true when one of the plan's coverage records for the member covers that date (the first day of a period of service). coverageId, effectiveDate and endDate are those of that record; when no record covers the date they are those of the member's most recent record, the one that ends last. Branch on active, never on the dates. message says why the member is inactive")
public record Coverage(
        @Schema(description = "An id for the coverage record: the resolved id reduced to letters and digits, then the record's effective and end dates as yyyyMMdd, run together with no separator (39991231 for an open end). Present whenever the dates are; absent when the plan holds no coverage record for the member, who is then inactive") String coverageId,
        @Schema(description = "The flag: true when coverage holds on the date of service (the first day of a period of service)", requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
        @Schema(description = "First day of the coverage record") LocalDate effectiveDate,
        @Schema(description = "Last day of the coverage record; 3999-12-31 for an open end") LocalDate endDate) {
}
