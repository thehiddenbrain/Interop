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
 * @param notes            what is wrong with the file that does not stop it being used, such as
 *                         that its expect block claims another HTTP status than it answers with.
 *                         The note that the file is the default is not among them: it is
 *                         {@code defaultNote}. Empty when there is nothing
 * @param defaultNote      the one note that only explains, that the file is the default because its
 *                         number is the lowest, kept apart from {@code notes} so that the screen can
 *                         put it behind "About this step" without telling notes apart by their words.
 *                         Null for every file but the default
 * @param sampleCase       the NPI and procedure codes the file names for its own case, filled in
 *                         with the member ID by one click on the mock scenario table. Null when the
 *                         file names none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioSummary(String id, int number, String label, String description, String sentinelMemberId,
                              String sentinelNpi, ScenarioBehavior behavior, Integer httpStatus, String expectedOutcome,
                              List<String> sentinelMemberIds, List<String> sentinelNpis, List<String> crdSentinelMemberIds,
                              List<String> notes, String defaultNote, SampleCase sampleCase) {

    public ScenarioSummary {
        sentinelMemberIds = sentinelMemberIds == null || sentinelMemberIds.isEmpty()
                ? (sentinelMemberId == null ? List.of() : List.of(sentinelMemberId)) : List.copyOf(sentinelMemberIds);
        sentinelNpis = sentinelNpis == null || sentinelNpis.isEmpty()
                ? (sentinelNpi == null ? List.of() : List.of(sentinelNpi)) : List.copyOf(sentinelNpis);
        crdSentinelMemberIds = crdSentinelMemberIds == null ? List.of() : List.copyOf(crdSentinelMemberIds);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    // The shorter forms stay, so the callers written before a summary carried a sample keep compiling.
    public ScenarioSummary(String id, int number, String label, String description, String sentinelMemberId, String sentinelNpi,
                           ScenarioBehavior behavior, Integer httpStatus, String expectedOutcome, List<String> sentinelMemberIds,
                           List<String> sentinelNpis, List<String> crdSentinelMemberIds, List<String> notes) {
        this(id, number, label, description, sentinelMemberId, sentinelNpi, behavior, httpStatus, expectedOutcome, sentinelMemberIds,
                sentinelNpis, crdSentinelMemberIds, notes, null, null);
    }

    public ScenarioSummary(String id, int number, String label, String description, String sentinelMemberId, String sentinelNpi,
                           ScenarioBehavior behavior, Integer httpStatus, String expectedOutcome, List<String> sentinelMemberIds,
                           List<String> sentinelNpis, List<String> crdSentinelMemberIds) {
        this(id, number, label, description, sentinelMemberId, sentinelNpi, behavior, httpStatus, expectedOutcome, sentinelMemberIds,
                sentinelNpis, crdSentinelMemberIds, null, null, null);
    }

    public ScenarioSummary(String id, int number, String label, String description, String sentinelMemberId, String sentinelNpi,
                           ScenarioBehavior behavior, Integer httpStatus, String expectedOutcome) {
        this(id, number, label, description, sentinelMemberId, sentinelNpi, behavior, httpStatus, expectedOutcome, null, null, null, null, null, null);
    }
}
