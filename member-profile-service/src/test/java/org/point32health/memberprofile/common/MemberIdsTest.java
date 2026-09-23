package org.point32health.memberprofile.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemberIdsTest {

    @Test
    void logFormIsAStableShortHashThatDoesNotContainTheId() {
        String a = MemberIds.forLog("HP0000001");

        assertThat(a).matches("m:[0-9a-f]{12}").doesNotContain("HP0000001");
        assertThat(MemberIds.forLog("HP0000001")).isEqualTo(a);
        assertThat(MemberIds.forLog("HP0000002")).isNotEqualTo(a);
        assertThat(MemberIds.forLog("hp0000001")).isNotEqualTo(a);
    }

    @Test
    void nullHasAPlaceholder() {
        assertThat(MemberIds.forLog(null)).isEqualTo("m:-");
    }

    @Test
    void exceptionsCarryTheHashedIdOnly() {
        MemberProfileException e = MemberProfileException.memberNotFound("HP0000001");

        assertThat(e.getDetail()).contains(MemberIds.forLog("HP0000001")).doesNotContain("HP0000001");
        assertThat(e.toApiError().message()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND.message());
        assertThat(e.toApiError().details()).isEmpty();

        MemberProfileException incomplete = MemberProfileException.memberDataIncomplete("HP0000001", "relationshipCode", "code '99' unknown");
        assertThat(incomplete.toApiError().message()).doesNotContain("99").doesNotContain("HP0000001");
        assertThat(incomplete.toApiError().details()).singleElement().satisfies(d -> {
            assertThat(d.field()).isEqualTo("relationshipCode");
            assertThat(d.message()).isEqualTo("missing or not recognized");
        });
    }
}
