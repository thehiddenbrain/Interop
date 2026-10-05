package org.p32h.interop.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/**
 * The coverage answer for the date (or period) of service. {@code active} is the flag Onyx acts on; {@code span} is the
 * continuous coverage period that covers the first date of service, present when there is one (active, or inactive
 * only because it ends before the last date asked about). {@code span.endDate} is an explicit {@code null} for
 * open-ended coverage. {@code coverageId} (owner feedback 24, form from the Onyx requirement, feedback 28) names that
 * period: MEMBER_ID + EFF_DATE + END_DATE run together with no separator, the stored member id reduced to letters and
 * digits, the dates as {@code yyyyMMdd} ({@code 99991231} when open-ended); present whenever {@code span} is. Why the
 * member is inactive is said in {@code message}.
 */
public record Coverage(boolean active, Span span, String coverageId) {

    public record Span(LocalDate effectiveDate, @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate) {
    }
}
