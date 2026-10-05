package org.point32health.interop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberSelectorTest {

    private static final LocalDate DOS = LocalDate.parse("2026-10-15");
    private final MemberSelector selector = new MemberSelector(new CoverageEvaluator());

    private static MemberRecord rec(String stored, String company, String legacy, String dob, CoverageSpan... spans) {
        return new MemberRecord(stored, MemberRecord.matchKeyOf(stored), company, "MCR", MemberRecord.matchKeyOf(legacy),
                dob == null ? null : LocalDate.parse(dob), List.of(spans), 0);
    }

    private static CoverageSpan span(String eff, String end) {
        return new CoverageSpan(LocalDate.parse(eff), end == null ? null : LocalDate.parse(end));
    }

    @Test
    void singleRecordIsSelected() {
        SelectionResult r = selector.select(List.of(rec("123456789   01", "THP", null, "1950-03-15", span("2024-01-01", null))), null, DOS);
        assertThat(r).isInstanceOf(SelectionResult.Selected.class);
        assertThat(((SelectionResult.Selected) r).coverage().active()).isTrue();
    }

    @Test
    void familyWithoutDobIsAmbiguousWithOneCandidatePerPerson() {
        SelectionResult r = selector.select(List.of(
                rec("34567890101", "THP", null, "1985-06-01", span("2025-01-01", null)),
                rec("34567890102", "THP", null, "2012-09-09", span("2025-01-01", null)),
                rec("34567890103", "THP", null, "2012-09-09", span("2023-01-01", "2024-12-31"))), null, DOS);
        assertThat(r).isInstanceOf(SelectionResult.Ambiguous.class);
        SelectionResult.Ambiguous a = (SelectionResult.Ambiguous) r;
        assertThat(a.reason()).isEqualTo(MemberSelector.MULTIPLE_PERSONS);
        assertThat(a.candidates()).extracting(SelectionResult.Candidate::storedMemberId)
                .containsExactly("34567890101", "34567890102", "34567890103");
        assertThat(a.candidates()).extracting(SelectionResult.Candidate::coverageActive).containsExactly(true, true, false);
    }

    @Test
    void dobPicksOnePerson() {
        SelectionResult r = selector.select(List.of(
                rec("34567890101", "THP", null, "1985-06-01", span("2025-01-01", null)),
                rec("34567890102", "THP", null, "2012-09-09", span("2025-01-01", null))), LocalDate.parse("2012-09-09"), DOS);
        assertThat(((SelectionResult.Selected) r).record().storedMemberId()).isEqualTo("34567890102");
    }

    @Test
    void twinsStayAmbiguousWithDob() {
        SelectionResult r = selector.select(List.of(
                rec("34567890102", "THP", null, "2012-09-09", span("2025-01-01", null)),
                rec("34567890103", "THP", null, "2012-09-09", span("2023-01-01", "2024-12-31"))), LocalDate.parse("2012-09-09"), DOS);
        assertThat(((SelectionResult.Ambiguous) r).reason()).isEqualTo(MemberSelector.DOB_NOT_DISCRIMINATING);
    }

    @Test
    void dobMatchingNobodyIsAMismatchEvenForASingleRecord() {
        assertThatThrownBy(() -> selector.select(List.of(rec("123456789   01", "THP", null, "1950-03-15", span("2024-01-01", null))),
                LocalDate.parse("1999-09-09"), DOS)).isInstanceOf(DobMismatchException.class);
    }

    @Test
    void dobIsIgnoredWhenNoRecordCarriesOne() {
        SelectionResult r = selector.select(List.of(rec("123456789   01", "THP", null, null, span("2024-01-01", null))),
                LocalDate.parse("1999-09-09"), DOS);
        assertThat(r).isInstanceOf(SelectionResult.Selected.class);
    }

    @Test
    void convertedPairIsOnePersonAndTheRecordCoveringTheDateWins() {
        MemberRecord thp = rec("567890123   01", "THP", null, "1990-11-11", span("2020-01-01", "2024-12-31"));
        MemberRecord hphc = rec("HP567890123", "HPHC", "567890123   01", "1990-11-11", span("2025-01-01", null));
        SelectionResult after = selector.select(List.of(thp, hphc), null, LocalDate.parse("2026-10-15"));
        assertThat(((SelectionResult.Selected) after).record().storedMemberId()).isEqualTo("HP567890123");
        SelectionResult before = selector.select(List.of(hphc, thp), null, LocalDate.parse("2024-06-01"));
        assertThat(((SelectionResult.Selected) before).record().storedMemberId()).isEqualTo("567890123   01");
    }

    @Test
    void convertedPairWithNoCoverageOnTheDateReportsTheRecordEndingLast() {
        MemberRecord thp = rec("567890123   01", "THP", null, null, span("2020-01-01", "2024-12-31"));
        MemberRecord hphc = rec("HP567890123", "HPHC", "567890123   01", null, span("2025-01-01", "2025-12-31"));
        SelectionResult r = selector.select(List.of(thp, hphc), null, LocalDate.parse("2026-10-15"));
        SelectionResult.Selected s = (SelectionResult.Selected) r;
        assertThat(s.record().storedMemberId()).isEqualTo("HP567890123");
        assertThat(s.coverage().reason()).isEqualTo(CoverageReason.COVERAGE_ENDED);
    }

    @Test
    void convertedPairInTheGapBetweenTheTwoRecordsIsAGapNotNotYetEffective() {
        MemberRecord thp = rec("567890123   01", "THP", null, null, span("2020-01-01", "2024-06-30"));
        MemberRecord hphc = rec("HP567890123", "HPHC", "567890123   01", null, span("2025-01-01", null));
        SelectionResult.Selected s = (SelectionResult.Selected) selector.select(List.of(thp, hphc), null, LocalDate.parse("2024-09-01"));
        assertThat(s.coverage().reason()).isEqualTo(CoverageReason.COVERAGE_GAP);
        assertThat(s.readableSpans()).isEqualTo(2);
    }

    @Test
    void convertedPairWhereBothRecordsCoverTheDatePrefersTheNewerRecord() {
        MemberRecord thp = rec("567890123   01", "THP", null, null, span("2020-01-01", "2025-12-31"));
        MemberRecord hphc = rec("HP567890123", "HPHC", "567890123   01", null, span("2025-01-01", null));
        SelectionResult.Selected s = (SelectionResult.Selected) selector.select(List.of(thp, hphc), null, LocalDate.parse("2025-06-01"));
        assertThat(s.record().storedMemberId()).isEqualTo("HP567890123");
        assertThat(s.coverage().active()).isTrue();
    }

    @Test
    void unreadableSpansAreCountedAcrossThePerson() {
        MemberRecord only = new MemberRecord("880000000   01", "88000000001", "THP", "MCR", null, null, List.of(), 1);
        SelectionResult.Selected s = (SelectionResult.Selected) selector.select(List.of(only), null, DOS);
        assertThat(s.readableSpans()).isZero();
        assertThat(s.unreadableSpans()).isEqualTo(1);
    }

    @Test
    void duplicateRecordsAreMergedNotCountedTwice() {
        MemberRecord a = rec("123456789   01", "THP", null, "1950-03-15", span("2024-01-01", null));
        MemberRecord b = rec("12345678901", "THP", null, "1950-03-15", span("2021-01-01", "2023-12-31"));
        SelectionResult r = selector.select(List.of(a, b), null, DOS);
        assertThat(r).isInstanceOf(SelectionResult.Selected.class);
        assertThat(((SelectionResult.Selected) r).record().spans()).hasSize(2);
    }
}
