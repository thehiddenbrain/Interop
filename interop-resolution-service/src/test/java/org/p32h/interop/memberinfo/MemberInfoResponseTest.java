package org.p32h.interop.memberinfo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberInfoResponseTest {

    /** A plan with only the void flag and the hierarchy line of business set. */
    private static MemberPlan plan(String hierarchyLineOfBusiness, String voidFlag) {
        return new MemberPlan(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, voidFlag, null, hierarchyLineOfBusiness, null, null, null);
    }

    @Test
    void plansForMatchTheMemberIgnoringSpacingAndCaseAndSkipVoidAndMissingPlans() {
        MemberInfoResponse response = new MemberInfoResponse(null, Arrays.asList(
                new MemberInfoMember(null, "testmember 01", plan("A", "N")),
                new MemberInfoMember(null, "TESTMEMBER   01", plan("VOID", "y")),
                new MemberInfoMember(null, "TESTMEMBER   01", null),
                new MemberInfoMember(null, "OTHERMEMBER   01", plan("OTHER", "N")),
                null,
                new MemberInfoMember(null, "TESTMEMBER-01", plan("B", null))));
        assertThat(response.plansFor("TESTMEMBER   01")).extracting(MemberPlan::hierarchyLineOfBusiness).containsExactly("A", "B");
        assertThat(response.plansFor(null)).isEmpty();
    }

    @Test
    void anAnswerWithoutMembersHasNoPlan() {
        assertThat(new MemberInfoResponse(null, null).plansFor("TESTMEMBER   01")).isEmpty();
        assertThat(new MemberInfoResponse("x", List.of()).plansFor("TESTMEMBER   01")).isEmpty();
    }
}
