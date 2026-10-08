package org.p32h.interop.memberinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The member information answer: one entry per member id requested, each with that member's plan. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberInfoResponse(String requestId, List<MemberInfoMember> members) {

    public List<MemberInfoMember> membersOrEmpty() {
        return members == null ? List.of() : members.stream().filter(Objects::nonNull).toList();
    }

    /**
     * The plans returned for {@code memberId}: entries whose member id matches it ignoring spacing and case, with a plan
     * that is not void. Usually one; empty when the service has no plan for the member on the date.
     */
    public List<MemberPlan> plansFor(String memberId) {
        String key = compact(memberId);
        if (key == null) {
            return List.of();
        }
        return membersOrEmpty().stream()
                .filter(m -> key.equals(compact(m.memberId())))
                .map(MemberInfoMember::memberPlan)
                .filter(p -> p != null && !p.voided())
                .toList();
    }

    private static String compact(String id) {
        return id == null ? null : id.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }
}
