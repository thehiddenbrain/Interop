package org.p32h.interop.api;

import java.time.LocalDate;

/**
 * The coverage answer for the date (or period) of service, flat, in this order (owner feedback 31):
 * {@code coverageId}, {@code active}, {@code effectiveDate}, {@code endDate}. {@code active} is the flag Onyx acts on.
 * The dates are those of the continuous coverage period that covers the first date of service, present when there is
 * one (active, or inactive only because it ends before the last date asked about); an open-ended period ends
 * {@code 9999-12-31}, so every date sent is a real date and absent dates mean there is no period. {@code coverageId}
 * (owner feedback 24, form from the Onyx requirement, feedback 28) names that period: MEMBER_ID + EFF_DATE + END_DATE
 * run together with no separator, the resolved member id reduced to letters and digits, the dates as {@code yyyyMMdd};
 * present whenever the dates are. Why the member is inactive is said in {@code message}.
 */
public record Coverage(String coverageId, boolean active, LocalDate effectiveDate, LocalDate endDate) {

    /** The end date sent for an open-ended period, as MMI itself writes it (12/31/9999). */
    public static final LocalDate OPEN_END = LocalDate.of(9999, 12, 31);
}
