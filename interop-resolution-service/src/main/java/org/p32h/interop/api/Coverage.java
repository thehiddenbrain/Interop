package org.p32h.interop.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * The coverage answer for the date (or period) of service, flat, in this order:
 * {@code coverageId}, {@code active}, {@code effectiveDate}, {@code endDate}. {@code active} is the flag Onyx acts on.
 * The dates are those of the continuous coverage period that covers the first date of service, present when there is
 * one (active, or inactive only because it ends before the last date asked about); an open-ended period ends
 * {@code 3999-12-31}, so every date sent is a real date and absent dates mean there is no period. {@code coverageId}
 * (in the form the Onyx requirement sets) names that period: MEMBER_ID + EFF_DATE + END_DATE
 * run together with no separator, the resolved member id reduced to letters and digits, the dates as {@code yyyyMMdd};
 * present whenever the dates are. Why the member is inactive is said in {@code message}.
 */
@Schema(description = "The coverage flag and the continuous coverage period that covers the (first) date of service. The dates are present when active, and when inactive only because the period ends before the last date asked about; otherwise absent, and message says why")
public record Coverage(
        @Schema(description = "An id for the coverage period: the resolved id reduced to letters and digits, then the effective and end dates as yyyyMMdd, run together with no separator (39991231 for an open-ended period). Present whenever the dates are; the same member with the same coverage always gets the same value") String coverageId,
        @Schema(description = "The flag: true when coverage holds on the date of service, or on every day of the period of service", requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
        @Schema(description = "First day of the coverage period") LocalDate effectiveDate,
        @Schema(description = "Last day of the coverage period; 3999-12-31 for an open-ended period") LocalDate endDate) {

    /** The end date sent for an open-ended period, as MMI itself writes it (12/31/3999). */
    public static final LocalDate OPEN_END = LocalDate.of(3999, 12, 31);
}
