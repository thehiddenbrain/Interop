package org.p32h.interop.memberinfo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The member information answer: one entry per member id requested, each with that member's coverage records. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberInfoResponse(String requestId, List<MemberInfoMember> members) {

    public List<MemberInfoMember> membersOrEmpty() {
        return members == null ? List.of() : members.stream().filter(Objects::nonNull).toList();
    }

    /**
     * The coverage records returned for {@code memberId}, in the order sent: those of the entries whose member id matches it
     * ignoring spacing and case, void records left out. Empty when the service has none for the member.
     */
    public List<MemberPlan> coverageRecordsFor(String memberId) {
        String key = compact(memberId);
        if (key == null) {
            return List.of();
        }
        return membersOrEmpty().stream()
                .filter(m -> key.equals(compact(m.memberId())) && m.coverageRecords() != null)
                .flatMap(m -> m.coverageRecords().stream())
                .filter(c -> c != null && !c.voided())
                .toList();
    }

    private static String compact(String id) {
        return id == null ? null : id.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }
}
