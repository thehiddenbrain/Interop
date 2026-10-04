package org.point32health.memberid.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Decides ACTIVE / INACTIVE for one date of service from the readable, non-void spans of one record.
 * Pure function; every rule is a row in {@code CoverageEvaluatorTest}.
 */
public final class CoverageEvaluator {

    public CoverageDecision evaluate(List<CoverageSpan> spans, LocalDate dateOfService) {
        if (spans == null || spans.isEmpty()) {
            return new CoverageDecision(false, CoverageReason.NO_COVERAGE_RECORDS, null);
        }
        CoverageSpan covering = spans.stream()
                .filter(s -> s.covers(dateOfService))
                .max(Comparator.comparing(CoverageSpan::effective).thenComparing(CoverageSpan::endForSorting))
                .orElse(null);
        if (covering != null) {
            return new CoverageDecision(true, CoverageReason.COVERED, covering);
        }
        LocalDate earliestStart = spans.stream().map(CoverageSpan::effective).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate latestEnd = spans.stream().map(CoverageSpan::endForSorting).max(Comparator.naturalOrder()).orElseThrow();
        if (dateOfService.isBefore(earliestStart)) {
            return new CoverageDecision(false, CoverageReason.NOT_YET_EFFECTIVE, null);
        }
        if (!latestEnd.equals(LocalDate.MAX) && dateOfService.isAfter(latestEnd)) {
            return new CoverageDecision(false, CoverageReason.COVERAGE_ENDED, null);
        }
        return new CoverageDecision(false, CoverageReason.COVERAGE_GAP, null);
    }
}
