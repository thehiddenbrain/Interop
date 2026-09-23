package org.point32health.memberprofile.memberdomain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemberDomainMemberTest {

    private static MemberDomainMember with(Map<String, Object> attributes, List<MemberDomainMember.FamilyMember> family) {
        return new MemberDomainMember("HP1", null, null, null, null, "01", "HPHC", "M", null, 42, null, null, null, attributes, family);
    }

    @Test
    void nullCollectionsBecomeEmpty() {
        MemberDomainMember m = with(null, null);
        assertThat(m.attributes()).isEmpty();
        assertThat(m.familyMembers()).isEmpty();
    }

    @Test
    void nullValuedAttributesAreKeptAsAbsentFactsNotRejected() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("sourceSystemId", 2001);
        attributes.put("planCode", null);

        MemberDomainMember m = with(attributes, List.of());

        assertThat(m.attributes()).containsEntry("sourceSystemId", 2001).containsKey("planCode");
        assertThat(m.attributes().get("planCode")).isNull();
        assertThat(m.attributes()).isUnmodifiable();
    }

    @Test
    void nullFamilyEntriesAreDropped() {
        MemberDomainMember.FamilyMember kid = new MemberDomainMember.FamilyMember("HP2", "Kid", "03", null, 7);

        MemberDomainMember m = with(Map.of(), Arrays.asList(null, kid, null));

        assertThat(m.familyMembers()).containsExactly(kid);
        assertThat(m.familyMembers()).isUnmodifiable();
    }
}
