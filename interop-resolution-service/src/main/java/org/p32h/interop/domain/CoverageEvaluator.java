package org.p32h.interop.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides ACTIVE / INACTIVE for a date of service, or for a period of service (owner feedback 21: a prior
 * authorization may carry a start and an end date), from the readable, non-void spans of one person.
 *
 * <p>Spans that overlap or touch (one starts the day after the other ends, as plan-year records do) are one
 * continuous coverage period. The answer is ACTIVE only when one such period covers every day from the first
 * date of service to the last; the reported span is that period. Pure function; every rule is a row in
 * {@code CoverageEvaluatorTest}.
 */
public final class CoverageEvaluator {

    /** A single date of service: a period of one day. */
    public CoverageDecision evaluate(List<CoverageSpan> spans, LocalDate dateOfService) {
        return evaluate(spans, dateOfService, dateOfService);
    }

    /**
     * @param start the first date of service
     * @param end   the last date of service, {@code start} itself for a single date; never before {@code start}
     */
    public CoverageDecision evaluate(List<CoverageSpan> spans, LocalDate start, LocalDate end) {
        if (spans == null || spans.isEmpty()) {
            return new CoverageDecision(false, CoverageReason.NO_COVERAGE_RECORDS, null);
        }
        List<CoverageSpan> periods = merge(spans);
        CoverageSpan atStart = periods.stream().filter(p -> p.covers(start)).findFirst().orElse(null);
        if (atStart != null) {
            if (atStart.covers(end)) {
                return new CoverageDecision(true, CoverageReason.COVERED, atStart);
            }
            // covered on the first day, but the period ends before the last day asked about
            return new CoverageDecision(false, CoverageReason.COVERAGE_ENDS_WITHIN_PERIOD, atStart);
        }
        LocalDate earliestStart = periods.get(0).effective();
        LocalDate latestEnd = periods.stream().map(CoverageSpan::endForSorting).max(Comparator.naturalOrder()).orElseThrow();
        if (start.isBefore(earliestStart)) {
            return new CoverageDecision(false, CoverageReason.NOT_YET_EFFECTIVE, null);
        }
        if (!latestEnd.equals(LocalDate.MAX) && start.isAfter(latestEnd)) {
            return new CoverageDecision(false, CoverageReason.COVERAGE_ENDED, null);
        }
        return new CoverageDecision(false, CoverageReason.COVERAGE_GAP, null);
    }

    /** Overlapping or adjacent spans joined into continuous periods, sorted by start. */
    static List<CoverageSpan> merge(List<CoverageSpan> spans) {
        List<CoverageSpan> sorted = spans.stream()
                .sorted(Comparator.comparing(CoverageSpan::effective).thenComparing(CoverageSpan::endForSorting))
                .toList();
        List<CoverageSpan> periods = new ArrayList<>();
        for (CoverageSpan s : sorted) {
            if (periods.isEmpty()) {
                periods.add(s);
                continue;
            }
            CoverageSpan last = periods.get(periods.size() - 1);
            if (last.isOpenEnded()) {
                continue; // the open-ended period already covers everything that starts later
            }
            if (!s.effective().isAfter(last.end().plusDays(1))) { // overlaps, or starts the day after
                LocalDate joinedEnd = s.isOpenEnded() || s.end().isAfter(last.end()) ? s.end() : last.end();
                periods.set(periods.size() - 1, new CoverageSpan(last.effective(), joinedEnd));
            } else {
                periods.add(s);
            }
        }
        return periods;
    }
}
