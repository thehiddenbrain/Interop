package com.thehiddenbrain.interop.memberid.domain;

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
            return new CoverageDecision(false, CoverageReason.NO_COVERAGE_RECORDS, null, null, null);
        }
        // nearest boundaries around the date, reported whatever the outcome (recent past and future coverage)
        LocalDate lastEndBefore = spans.stream().map(CoverageSpan::end)
                .filter(e -> e != null && e.isBefore(dateOfService))
                .max(Comparator.naturalOrder()).orElse(null);
        LocalDate nextStartAfter = spans.stream().map(CoverageSpan::effective)
                .filter(e -> e.isAfter(dateOfService))
                .min(Comparator.naturalOrder()).orElse(null);
        CoverageSpan covering = spans.stream()
                .filter(s -> s.covers(dateOfService))
                .max(Comparator.comparing(CoverageSpan::effective).thenComparing(CoverageSpan::endForSorting))
                .orElse(null);
        if (covering != null) {
            return new CoverageDecision(true, CoverageReason.COVERED, covering, lastEndBefore, nextStartAfter);
        }
        LocalDate earliestStart = spans.stream().map(CoverageSpan::effective).min(Comparator.naturalOrder()).orElseThrow();
        LocalDate latestEnd = spans.stream().map(CoverageSpan::endForSorting).max(Comparator.naturalOrder()).orElseThrow();
        if (dateOfService.isBefore(earliestStart)) {
            return new CoverageDecision(false, CoverageReason.NOT_YET_EFFECTIVE, null, null, nextStartAfter);
        }
        if (!latestEnd.equals(LocalDate.MAX) && dateOfService.isAfter(latestEnd)) {
            return new CoverageDecision(false, CoverageReason.COVERAGE_ENDED, null, lastEndBefore, null);
        }
        return new CoverageDecision(false, CoverageReason.COVERAGE_GAP, null, lastEndBefore, nextStartAfter);
    }
}
