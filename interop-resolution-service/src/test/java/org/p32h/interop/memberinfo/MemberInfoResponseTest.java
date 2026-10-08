package org.p32h.interop.memberinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberInfoResponseTest {

    /** A coverage record with only the void flag, the hierarchy line of business and the plan dates set. */
    private static MemberPlan record(String hierarchyLineOfBusiness, String voidFlag, String planStartDate, String planEndDate) {
        return new MemberPlan(null, null, null, null, null, null, null, null, null, null, null, null, planEndDate, null, planStartDate,
                null, null, null, null, voidFlag, null, hierarchyLineOfBusiness, null, null, null);
    }

    private static MemberPlan record(String hierarchyLineOfBusiness, String voidFlag) {
        return record(hierarchyLineOfBusiness, voidFlag, null, null);
    }

    @Test
    void coverageRecordsForMatchTheMemberIgnoringSpacingAndCaseKeepTheOrderAndSkipVoidRecords() {
        MemberInfoResponse response = new MemberInfoResponse(null, Arrays.asList(
                new MemberInfoMember(null, "testmember 01", List.of(record("A", "N"), record("VOID", "y"))),
                new MemberInfoMember(null, "TESTMEMBER   01", null),
                new MemberInfoMember(null, "OTHERMEMBER   01", List.of(record("OTHER", "N"))),
                null,
                new MemberInfoMember(null, "TESTMEMBER-01", Arrays.asList(null, record("B", null)))));
        assertThat(response.coverageRecordsFor("TESTMEMBER   01")).extracting(MemberPlan::hierarchyLineOfBusiness).containsExactly("A", "B");
        assertThat(response.coverageRecordsFor(null)).isEmpty();
    }

    @Test
    void anAnswerWithoutMembersHasNoRecords() {
        assertThat(new MemberInfoResponse(null, null).coverageRecordsFor("TESTMEMBER   01")).isEmpty();
        assertThat(new MemberInfoResponse("x", List.of()).coverageRecordsFor("TESTMEMBER   01")).isEmpty();
    }

    @Test
    void aRecordCoversTheDaysFromItsStartToItsEndInclusive() {
        MemberPlan feb = record("X", "N", "2024-02-01T05:00:00.000+00:00", "2024-04-30T05:00:00.000+00:00");
        assertThat(feb.covers(LocalDate.parse("2024-01-31"))).isFalse();
        assertThat(feb.covers(LocalDate.parse("2024-02-01"))).isTrue();
        assertThat(feb.covers(LocalDate.parse("2024-04-30"))).isTrue();
        assertThat(feb.covers(LocalDate.parse("2024-05-01"))).isFalse();
        MemberPlan open = record("X", "N", "2025-01-01T05:00:00.000+00:00", null);
        assertThat(open.covers(LocalDate.parse("2030-06-01"))).as("no end date is open-ended").isTrue();
        assertThat(record("X", "N", "2025-01-01T05:00:00.000+00:00", "9999-12-31T05:00:00.000+00:00").covers(LocalDate.parse("2030-06-01"))).isTrue();
        assertThat(record("X", "N", null, null).covers(LocalDate.parse("2025-06-01"))).as("no start date").isFalse();
        assertThat(record("X", "N", "2025-01-01", "junk").covers(LocalDate.parse("2025-06-01"))).as("an unreadable end").isFalse();
    }

    @Test
    void planDatesAreReadAsWritten() {
        assertThat(MemberPlan.dateOf("2024-02-01T05:00:00.000+00:00")).isEqualTo("2024-02-01");
        assertThat(MemberPlan.dateOf("2024-02-01T00:00:00.000+00:00")).as("midnight UTC is still the date written").isEqualTo("2024-02-01");
        assertThat(MemberPlan.dateOf("2024-02-01T00:00:00")).isEqualTo("2024-02-01");
        assertThat(MemberPlan.dateOf("2024-02-01T05:00:00.000+0000")).as("an offset without a colon").isEqualTo("2024-02-01");
        assertThat(MemberPlan.dateOf("2024-02-01T05:00:00Z")).isEqualTo("2024-02-01");
        assertThat(MemberPlan.dateOf(" 2024-02-01 ")).isEqualTo("2024-02-01");
        assertThat(MemberPlan.dateOf("02/01/2024")).isNull();
        assertThat(MemberPlan.dateOf("")).isNull();
        assertThat(MemberPlan.dateOf("2024-13-01T00:00:00")).isNull();
    }
}
