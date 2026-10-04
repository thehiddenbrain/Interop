package org.point32health.memberid.domain;

import java.time.LocalDate;

/**
 * @param active            the flag Onyx acts on
 * @param reason            why
 * @param span              the covering span when active
 * @param lastEndDate       when inactive: the latest span end before the date of service, if any
 * @param nextEffectiveDate when inactive: the earliest span start after the date of service, if any
 */
public record CoverageDecision(boolean active, CoverageReason reason, CoverageSpan span, LocalDate lastEndDate,
        LocalDate nextEffectiveDate) {
}
