package org.point32health.interop.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/**
 * The coverage answer for the date (or period) of service. {@code active} is the flag Onyx acts on; {@code span} is the
 * continuous coverage period that covers the first date of service, present when there is one (active, or inactive
 * only because it ends before the last date asked about). {@code span.endDate} is an explicit {@code null} for
 * open-ended coverage. {@code coverageId} (owner feedback 24) names that period: the stored member id without its
 * spaces, the period's effective date and its end date as {@code yyyyMMdd} ({@code 99991231} when open-ended),
 * joined with hyphens; present whenever {@code span} is. Why the member is inactive is said in {@code message}.
 */
public record Coverage(boolean active, Span span, String coverageId) {

    public record Span(LocalDate effectiveDate, @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate) {
    }
}
