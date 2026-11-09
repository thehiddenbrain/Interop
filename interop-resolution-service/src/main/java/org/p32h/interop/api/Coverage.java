package org.p32h.interop.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

/**
 * The coverage answer for the date (or period) of service, flat, in this order:
 * {@code coverageId}, {@code active}, {@code effectiveDate}, {@code endDate}. {@code active} is the flag Onyx acts on.
 * The dates are those of one coverage record of the member, as the member lookup holds it (never two records joined):
 * on an ACTIVE answer the record that covers the date of service (the first day of a period of service); on an
 * INACTIVE answer the member's most recent record, the one that ends last (an open end counting as the latest), which
 * does not cover the date of service. An open end is {@code 3999-12-31}, so every date sent is a real date.
 * {@code coverageId} (in the form the Onyx requirement sets) names that record: MEMBER_ID + EFF_DATE + END_DATE run together with no separator, the resolved
 * member id reduced to letters and digits, the dates as {@code yyyyMMdd}; present whenever the dates are. Why the
 * member is inactive is said in {@code message}.
 */
@Schema(description = "The coverage flag and one coverage record of the member, with the record's own dates. ACTIVE: the record that covers the date of service (the first day of a period of service). INACTIVE: the member's most recent record, the one that ends last (an open end counting as the latest); it does not cover the date of service, so branch on active, never on the dates. message says why the member is inactive")
public record Coverage(
        @Schema(description = "An id for the coverage record: the resolved id reduced to letters and digits, then the record's effective and end dates as yyyyMMdd, run together with no separator (39991231 for an open end). Present whenever the dates are; the same record always gets the same value, and a renewal (a new plan-year record) gets a new one") String coverageId,
        @Schema(description = "The flag: true when coverage holds on the date of service (the first day of a period of service)", requiredMode = Schema.RequiredMode.REQUIRED) boolean active,
        @Schema(description = "First day of the coverage record: on INACTIVE, of the most recent record") LocalDate effectiveDate,
        @Schema(description = "Last day of the coverage record, on INACTIVE of the most recent record; 3999-12-31 for an open end. A period of service can run on into the next record: message says how far the coverage runs") LocalDate endDate) {

    /** The end date sent for an open-ended period, as MMI itself writes it (12/31/3999). */
    public static final LocalDate OPEN_END = LocalDate.of(3999, 12, 31);
}
