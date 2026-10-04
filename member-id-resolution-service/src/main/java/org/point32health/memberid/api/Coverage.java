package org.point32health.memberid.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.point32health.memberid.domain.CoverageReason;
import java.time.LocalDate;

/**
 * The coverage answer for the date of service. {@code active} is the flag Onyx acts on; everything else is
 * supporting detail. {@code span.endDate} is an explicit {@code null} for open-ended coverage.
 */
public record Coverage(boolean active, CoverageReason reason, Span span, LocalDate lastEndDate, LocalDate nextEffectiveDate) {

    public record Span(LocalDate effectiveDate, @JsonInclude(JsonInclude.Include.ALWAYS) LocalDate endDate) {
    }
}
