package org.point32health.memberid.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDate;

/**
 * The coverage answer for the date of service. {@code active} is the flag Onyx acts on; {@code span} is the
 * coverage period that covers the date, present when active. {@code span.endDate} is an explicit {@code null}
 * for open-ended coverage. Why the member is inactive is said in the response's {@code message}.
 */
public record Coverage(boolean active, Span span) {

    public record Span(LocalDate effectiveDate, @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate) {
    }
}
