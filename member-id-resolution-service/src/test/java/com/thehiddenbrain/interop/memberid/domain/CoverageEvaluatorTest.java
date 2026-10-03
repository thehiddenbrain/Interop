package com.thehiddenbrain.interop.memberid.domain;

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
        assertThat(after.lastEndDate()).isEqualTo(LocalDate.parse("2024-05-31"));
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
        assertThat(d.nextEffectiveDate()).isEqualTo(LocalDate.parse("2027-01-01"));
    }

    @Test
    void gapBetweenSpans() {
        CoverageDecision d = evaluator.evaluate(List.of(span("2023-01-01", "2024-05-31"), span("2025-01-01", null)),
                LocalDate.parse("2024-08-15"));
        assertThat(d.active()).isFalse();
        assertThat(d.reason()).isEqualTo(CoverageReason.COVERAGE_GAP);
        assertThat(d.lastEndDate()).isEqualTo(LocalDate.parse("2024-05-31"));
        assertThat(d.nextEffectiveDate()).isEqualTo(LocalDate.parse("2025-01-01"));
    }

    @Test
    void noSpans() {
        CoverageDecision d = evaluator.evaluate(List.of(), LocalDate.parse("2026-10-15"));
        assertThat(d.active()).isFalse();
        assertThat(d.reason()).isEqualTo(CoverageReason.NO_COVERAGE_RECORDS);
    }

    @Test
    void reportsTheLatestCoveringSpanWhenSpansOverlap() {
        CoverageDecision d = evaluator.evaluate(List.of(span("2020-01-01", null), span("2025-01-01", null)), LocalDate.parse("2026-10-15"));
        assertThat(d.span().effective()).isEqualTo(LocalDate.parse("2025-01-01"));
    }

    @Test
    void retroTerminationAfterTheDateOfServiceIsStillActiveOnThatDate() {
        CoverageDecision d = evaluator.evaluate(List.of(span("2024-01-01", "2026-06-30")), LocalDate.parse("2026-03-01"));
        assertThat(d.active()).isTrue();
    }
}
