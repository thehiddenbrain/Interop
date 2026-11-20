package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * One mock scenario as the scenario selector lists it.
 *
 * @param number           the position in the numbered list testers refer to
 * @param sentinelMemberId the member ID that selects this scenario on mock, or null
 * @param sentinelNpi      the NPI that selects this scenario on mock, or null
 * @param expectedOutcome  the outcome code the scenario file claims to produce, or null
 * @param sentinelMemberIds every member ID that selects this scenario on mock, in the order the
 *                         file gives them. {@code sentinelMemberId} is the first of them
 * @param sentinelNpis     every NPI that selects this scenario on mock, in the same order
 * @param crdSentinelMemberIds the CRD sentinel member IDs a DTR scenario also answers for, so that
 *                         a journey started at CRD reaches it. Empty for every other scenario
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioSummary(String id, int number, String label, String description, String sentinelMemberId,
                              String sentinelNpi, ScenarioBehavior behavior, Integer httpStatus, String expectedOutcome,
                              List<String> sentinelMemberIds, List<String> sentinelNpis, List<String> crdSentinelMemberIds) {

    public ScenarioSummary {
        sentinelMemberIds = sentinelMemberIds == null || sentinelMemberIds.isEmpty()
                ? (sentinelMemberId == null ? List.of() : List.of(sentinelMemberId)) : List.copyOf(sentinelMemberIds);
        sentinelNpis = sentinelNpis == null || sentinelNpis.isEmpty()
                ? (sentinelNpi == null ? List.of() : List.of(sentinelNpi)) : List.copyOf(sentinelNpis);
        crdSentinelMemberIds = crdSentinelMemberIds == null ? List.of() : List.copyOf(crdSentinelMemberIds);
    }

    public ScenarioSummary(String id, int number, String label, String description, String sentinelMemberId, String sentinelNpi,
                           ScenarioBehavior behavior, Integer httpStatus, String expectedOutcome) {
        this(id, number, label, description, sentinelMemberId, sentinelNpi, behavior, httpStatus, expectedOutcome, null, null, null);
    }
}
