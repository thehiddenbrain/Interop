package org.p32h.interop.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoverageEvaluatorTest {

    private final CoverageEvaluator evaluator = new CoverageEvaluator();

    private static CoverageSpan span(String eff, String end) {
        return new CoverageSpan(LocalDate.parse(eff), end == null ? null : LocalDate.parse(end));
    }

    @Test
    void openEndedSpanCoversFutureDate() {
        CoverageDecision d = evaluator.evaluate(List.of(span("2025-01-01", null)), LocalDate.parse("2026-10-15"));
        assertThat(d.active()).isTrue();
        assertThat(d.reason()).isEqualTo(CoverageReason.COVERED);
        assertThat(d.span().end()).isNull();
    }

    @Test
    void endDateIsInclusive() {
        List<CoverageSpan> spans = List.of(span("2024-01-01", "2024-05-31"));
        assertThat(evaluator.evaluate(spans, LocalDate.parse("2024-05-31")).active()).isTrue();
        CoverageDecision after = evaluator.evaluate(spans, LocalDate.parse("2024-06-01"));
        assertThat(after.active()).isFalse();
        assertThat(after.reason()).isEqualTo(CoverageReason.COVERAGE_ENDED);
        assertThat(after.span()).isNull();
    }

    @Test
    void effectiveDateIsInclusive() {
        assertThat(evaluator.evaluate(List.of(span("2024-01-01", "2024-05-31")), LocalDate.parse("2024-01-01")).active()).isTrue();
    }

    @Test
    void notYetEffective() {
        CoverageDecision d = evaluator.evaluate(List.of(span("2027-01-01", null)), LocalDate.parse("2026-10-15"));
        assertThat(d.active()).isFalse();
        assertThat(d.reason()).isEqualTo(CoverageReason.NOT_YET_EFFECTIVE);
        assertThat(d.span()).isNull();
    }

    @Test
    void gapBetweenSpans() {
        CoverageDecision d = evaluator.evaluate(List.of(span("2023-01-01", "2024-05-31"), span("2025-01-01", null)),
                LocalDate.parse("2024-08-15"));
        assertThat(d.active()).isFalse();
        assertThat(d.reason()).isEqualTo(CoverageReason.COVERAGE_GAP);
        assertThat(d.span()).isNull();
    }

    @Test
    void noSpans() {
        CoverageDecision d = evaluator.evaluate(List.of(), LocalDate.parse("2026-10-15"));
        assertThat(d.active()).isFalse();
        assertThat(d.reason()).isEqualTo(CoverageReason.NO_COVERAGE_RECORDS);
    }

    @Test
    void adjacentSpansAreOneContinuousPeriod() {
        // plan-year records that touch (one ends 12/31, the next starts 01/01) are one coverage period
        CoverageDecision d = evaluator.evaluate(List.of(span("2021-01-01", "2023-12-31"), span("2024-01-01", "2026-12-31"), span("2027-01-01", null)),
                LocalDate.parse("2026-10-03"));
        assertThat(d.active()).isTrue();
        assertThat(d.span().effective()).isEqualTo(LocalDate.parse("2021-01-01"));
        assertThat(d.span().end()).as("the last record is open-ended, so the period is").isNull();
    }

    @Test
    void overlappingSpansAreOnePeriodFromTheEarliestStart() {
        CoverageDecision d = evaluator.evaluate(List.of(span("2020-01-01", null), span("2025-01-01", null)), LocalDate.parse("2026-10-15"));
        assertThat(d.span().effective()).isEqualTo(LocalDate.parse("2020-01-01"));
        assertThat(d.span().end()).isNull();
    }

    @Test
    void aOneDayHoleBetweenSpansIsAGap() {
        List<CoverageSpan> spans = List.of(span("2025-01-01", "2025-12-31"), span("2026-01-02", null));
        assertThat(evaluator.evaluate(spans, LocalDate.parse("2026-01-01")).reason()).isEqualTo(CoverageReason.COVERAGE_GAP);
        assertThat(evaluator.evaluate(spans, LocalDate.parse("2026-01-02")).span().effective()).isEqualTo(LocalDate.parse("2026-01-02"));
    }

    // ---------------------------------------------------------------- a period of service

    @Test
    void aPeriodOfServiceIsJudgedOnItsFirstDay() {
        List<CoverageSpan> planYears = List.of(span("2025-01-01", "2025-12-31"), span("2026-01-01", "2026-12-31"));
        CoverageDecision across = evaluator.evaluate(planYears, LocalDate.parse("2025-12-20"));
        assertThat(across.active()).as("a period across two adjacent records starts on a covered day").isTrue();
        assertThat(across.span().effective()).isEqualTo(LocalDate.parse("2025-01-01"));
        assertThat(across.span().end()).as("the span's end says how far the coverage runs").isEqualTo(LocalDate.parse("2026-12-31"));
        CoverageDecision outlasts = evaluator.evaluate(planYears, LocalDate.parse("2026-12-20"));
        assertThat(outlasts.active()).as("a period that outlasts the coverage is still active on its first day").isTrue();
        assertThat(outlasts.span().end()).isEqualTo(LocalDate.parse("2026-12-31"));

        List<CoverageSpan> spans = List.of(span("2023-01-01", "2024-05-31"), span("2025-01-01", null));
        assertThat(evaluator.evaluate(spans, LocalDate.parse("2024-08-15")).reason()).as("first day in a gap").isEqualTo(CoverageReason.COVERAGE_GAP);
        assertThat(evaluator.evaluate(spans, LocalDate.parse("2022-12-01")).reason()).isEqualTo(CoverageReason.NOT_YET_EFFECTIVE);
        assertThat(evaluator.evaluate(List.of(span("2023-01-01", "2024-05-31")), LocalDate.parse("2024-06-01")).reason()).isEqualTo(CoverageReason.COVERAGE_ENDED);
    }

    @Test
    void retroTerminationAfterTheDateOfServiceIsStillActiveOnThatDate() {
        CoverageDecision d = evaluator.evaluate(List.of(span("2024-01-01", "2026-06-30")), LocalDate.parse("2026-03-01"));
        assertThat(d.active()).isTrue();
    }
}
