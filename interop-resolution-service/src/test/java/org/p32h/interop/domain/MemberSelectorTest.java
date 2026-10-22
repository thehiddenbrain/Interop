package org.p32h.interop.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberSelectorTest {

    private static final LocalDate DOS = LocalDate.parse("2026-10-15");
    /** 24 months before the fixed "today" of the suite (2026-10-03). */
    private static final LocalDate RECENT_SINCE = LocalDate.parse("2024-10-03");
    private final MemberSelector selector = new MemberSelector(new CoverageEvaluator());

    private static MemberRecord rec(String stored, String company, String legacy, String dob, CoverageSpan... spans) {
        return new MemberRecord(stored, MemberRecord.matchKeyOf(stored), company, "MCR", MemberRecord.matchKeyOf(legacy),
                dob == null ? null : LocalDate.parse(dob), null, List.of(spans), 0);
    }

    private static CoverageSpan span(String eff, String end) {
        return new CoverageSpan(LocalDate.parse(eff), end == null ? null : LocalDate.parse(end));
    }

    @Test
    void singleRecordIsSelected() {
        SelectionResult r = selector.select(List.of(rec("123456789   01", "THP", null, "1950-03-15", span("2024-01-01", null))), null, DOS, RECENT_SINCE);
        assertThat(r).isInstanceOf(SelectionResult.Selected.class);
        assertThat(((SelectionResult.Selected) r).coverage().active()).isTrue();
    }

    @Test
    void familyWithoutDobIsAmbiguous() {
        SelectionResult r = selector.select(List.of(
                rec("34567890101", "THP", null, "1985-06-01", span("2025-01-01", null)),
                rec("34567890102", "THP", null, "2012-09-09", span("2025-01-01", null)),
                rec("34567890103", "THP", null, "2012-09-09", span("2023-01-01", "2024-12-31"))), null, DOS, RECENT_SINCE);
        assertThat(r).isInstanceOf(SelectionResult.Ambiguous.class);
        SelectionResult.Ambiguous a = (SelectionResult.Ambiguous) r;
        assertThat(a.reason()).isEqualTo(MemberSelector.MULTIPLE_PERSONS);
        assertThat(a.persons()).isEqualTo(3);
    }

    @Test
    void dobPicksOnePerson() {
        SelectionResult r = selector.select(List.of(
                rec("34567890101", "THP", null, "1985-06-01", span("2025-01-01", null)),
                rec("34567890102", "THP", null, "2012-09-09", span("2025-01-01", null))), LocalDate.parse("2012-09-09"), DOS, RECENT_SINCE);
        assertThat(((SelectionResult.Selected) r).record().storedMemberId()).isEqualTo("34567890102");
    }

    @Test
    void twinsStayAmbiguousWithDob() {
        SelectionResult r = selector.select(List.of(
                rec("34567890102", "THP", null, "2012-09-09", span("2025-01-01", null)),
                rec("34567890103", "THP", null, "2012-09-09", span("2023-01-01", "2024-12-31"))), LocalDate.parse("2012-09-09"), DOS, RECENT_SINCE);
        assertThat(((SelectionResult.Ambiguous) r).reason()).isEqualTo(MemberSelector.DOB_NOT_DISCRIMINATING);
    }

    @Test
    void dobMatchingNobodyIsAMismatchEvenForASingleRecord() {
        assertThatThrownBy(() -> selector.select(List.of(rec("123456789   01", "THP", null, "1950-03-15", span("2024-01-01", null))),
                LocalDate.parse("1999-09-09"), DOS, RECENT_SINCE)).isInstanceOf(DobMismatchException.class);
    }

    @Test
    void dobIsIgnoredWhenNoRecordCarriesOne() {
        SelectionResult r = selector.select(List.of(rec("123456789   01", "THP", null, null, span("2024-01-01", null))),
                LocalDate.parse("1999-09-09"), DOS, RECENT_SINCE);
        assertThat(r).isInstanceOf(SelectionResult.Selected.class);
    }

    @Test
    void familyWhoseRecordsCarryNoDobStaysAmbiguousWithADob() {
        SelectionResult r = selector.select(List.of(
                rec("34567890101", "THP", null, null, span("2025-01-01", null)),
                rec("34567890102", "THP", null, null, span("2025-01-01", null))), LocalDate.parse("2012-09-09"), DOS, RECENT_SINCE);
        SelectionResult.Ambiguous a = (SelectionResult.Ambiguous) r;
        assertThat(a.reason()).as("a date of birth was sent but could not be checked").isEqualTo(MemberSelector.DOB_NOT_ON_RECORDS);
        assertThat(a.persons()).isEqualTo(2);
    }

    @Test
    void convertedPairIsOnePersonAndTheRecordCoveringTheDateWins() {
        MemberRecord thp = rec("567890123   01", "THP", null, "1990-11-11", span("2020-01-01", "2024-12-31"));
        MemberRecord hphc = rec("HP567890123", "HPHC", "567890123   01", "1990-11-11", span("2025-01-01", null));
        SelectionResult after = selector.select(List.of(thp, hphc), null, LocalDate.parse("2026-10-15"), RECENT_SINCE);
        assertThat(((SelectionResult.Selected) after).record().storedMemberId()).isEqualTo("HP567890123");
        SelectionResult before = selector.select(List.of(hphc, thp), null, LocalDate.parse("2024-06-01"), RECENT_SINCE);
        assertThat(((SelectionResult.Selected) before).record().storedMemberId()).isEqualTo("567890123   01");
    }

    @Test
    void convertedPairWithNoCoverageOnTheDateReportsTheRecordEndingLast() {
        MemberRecord thp = rec("567890123   01", "THP", null, null, span("2020-01-01", "2024-12-31"));
        MemberRecord hphc = rec("HP567890123", "HPHC", "567890123   01", null, span("2025-01-01", "2025-12-31"));
        SelectionResult r = selector.select(List.of(thp, hphc), null, LocalDate.parse("2026-10-15"), RECENT_SINCE);
        SelectionResult.Selected s = (SelectionResult.Selected) r;
        assertThat(s.record().storedMemberId()).isEqualTo("HP567890123");
        assertThat(s.coverage().reason()).isEqualTo(CoverageReason.COVERAGE_ENDED);
    }

    @Test
    void convertedPairInTheGapBetweenTheTwoRecordsIsAGapNotNotYetEffective() {
        MemberRecord thp = rec("567890123   01", "THP", null, null, span("2020-01-01", "2024-06-30"));
        MemberRecord hphc = rec("HP567890123", "HPHC", "567890123   01", null, span("2025-01-01", null));
        SelectionResult.Selected s = (SelectionResult.Selected) selector.select(List.of(thp, hphc), null, LocalDate.parse("2024-09-01"), RECENT_SINCE);
        assertThat(s.coverage().reason()).isEqualTo(CoverageReason.COVERAGE_GAP);
        assertThat(s.readableSpans()).isEqualTo(2);
    }

    @Test
    void convertedPairWhereBothRecordsCoverTheDatePrefersTheNewerRecord() {
        MemberRecord thp = rec("567890123   01", "THP", null, null, span("2020-01-01", "2025-12-31"));
        MemberRecord hphc = rec("HP567890123", "HPHC", "567890123   01", null, span("2025-01-01", null));
        SelectionResult.Selected s = (SelectionResult.Selected) selector.select(List.of(thp, hphc), null, LocalDate.parse("2025-06-01"), RECENT_SINCE);
        assertThat(s.record().storedMemberId()).isEqualTo("HP567890123");
        assertThat(s.coverage().active()).isTrue();
    }

    @Test
    void unreadableSpansAreCountedAcrossThePerson() {
        MemberRecord only = new MemberRecord("880000000   01", "88000000001", "THP", "MCR", null, null, null, List.of(), 1);
        SelectionResult.Selected s = (SelectionResult.Selected) selector.select(List.of(only), null, DOS, RECENT_SINCE);
        assertThat(s.readableSpans()).isZero();
        assertThat(s.unreadableSpans()).isEqualTo(1);
    }

    @Test
    void duplicateRecordsAreMergedNotCountedTwice() {
        MemberRecord a = rec("123456789   01", "THP", null, "1950-03-15", span("2024-01-01", null));
        MemberRecord b = rec("12345678901", "THP", null, "1950-03-15", span("2021-01-01", "2023-12-31"));
        SelectionResult r = selector.select(List.of(a, b), null, DOS, RECENT_SINCE);
        assertThat(r).isInstanceOf(SelectionResult.Selected.class);
        assertThat(((SelectionResult.Selected) r).record().spans()).hasSize(2);
    }

    // ---------------------------------------------------------------- the recent-coverage window

    @Test
    void aPersonWithNoCoverageOnTheDateAndNoneInTheWindowIsNobody() {
        MemberRecord stale = rec("S00000001   01", "THP", null, "1950-03-15", span("2020-01-01", "2023-12-31"));
        SelectionResult r = selector.select(List.of(stale), null, DOS, RECENT_SINCE);
        assertThat(r).isInstanceOfSatisfying(SelectionResult.NoneRecent.class, n -> {
            assertThat(n.persons()).isEqualTo(1);
            assertThat(n.latestCoverageEnd()).isEqualTo(LocalDate.parse("2023-12-31"));
        });
        assertThat(selector.select(List.of(stale), null, LocalDate.parse("2023-06-15"), RECENT_SINCE))
                .as("covered on an old date of service: still the member").isInstanceOf(SelectionResult.Selected.class);
        assertThat(selector.select(List.of(rec("S00000001   01", "THP", null, null)), null, DOS, RECENT_SINCE))
                .as("no coverage at all").isInstanceOf(SelectionResult.NoneRecent.class);
    }

    @Test
    void coverageEndingOnOrAfterTheWindowStartKeepsThePerson() {
        assertThat(selector.select(List.of(rec("S00000001   01", "THP", null, null, span("2020-01-01", "2024-10-03"))), null, DOS, RECENT_SINCE))
                .as("ends on the first day of the window").isInstanceOf(SelectionResult.Selected.class);
        assertThat(selector.select(List.of(rec("S00000001   01", "THP", null, null, span("2020-01-01", "2024-10-02"))), null, DOS, RECENT_SINCE))
                .as("ends the day before the window").isInstanceOf(SelectionResult.NoneRecent.class);
        assertThat(selector.select(List.of(rec("S00000001   01", "THP", null, null, span("2027-01-01", null))), null, DOS, RECENT_SINCE))
                .as("a future, open-ended span is recent: INACTIVE, not yet effective").isInstanceOfSatisfying(SelectionResult.Selected.class,
                        s -> assertThat(s.coverage().reason()).isEqualTo(CoverageReason.NOT_YET_EFFECTIVE));
        assertThat(selector.select(List.of(rec("S00000001   01", "THP", null, null, span("2027-01-01", "2027-06-30"))), null, DOS, RECENT_SINCE))
                .as("a future, closed span is recent too").isInstanceOf(SelectionResult.Selected.class);
    }

    @Test
    void anUnreadableSpanKeepsThePersonSinceItsRecencyIsUnknown() {
        MemberRecord r = new MemberRecord("S00000001   01", "S0000000101", "THP", "MCR", null, null, null, List.of(span("2020-01-01", "2022-12-31")), 1);
        assertThat(selector.select(List.of(r), null, DOS, RECENT_SINCE)).isInstanceOfSatisfying(SelectionResult.Selected.class,
                s -> assertThat(s.unreadableSpans()).isEqualTo(1));
    }

    @Test
    void stalePersonsAreDroppedBeforeTheAmbiguityCheck() {
        MemberRecord current = rec("T00000002   02", "THP", null, "2012-09-09", span("2025-01-01", null));
        MemberRecord stale = rec("T00000002   03", "THP", null, "2012-09-09", span("2019-01-01", "2021-12-31"));
        SelectionResult r = selector.select(List.of(stale, current), null, DOS, RECENT_SINCE);
        assertThat(r).as("the family's only current person is the member").isInstanceOfSatisfying(SelectionResult.Selected.class,
                s -> assertThat(s.record().storedMemberId()).isEqualTo("T00000002   02"));
        assertThat(selector.select(List.of(stale, rec("T00000002   04", "THP", null, "2012-09-09", span("2018-01-01", "2020-12-31"))), null, DOS, RECENT_SINCE))
                .as("two stale persons: nobody").isInstanceOfSatisfying(SelectionResult.NoneRecent.class, n -> assertThat(n.persons()).isEqualTo(2));
        assertThatThrownBy(() -> selector.select(List.of(stale, current), LocalDate.parse("1950-03-15"), DOS, RECENT_SINCE))
                .as("the date of birth is checked first").isInstanceOf(DobMismatchException.class);
    }
}
