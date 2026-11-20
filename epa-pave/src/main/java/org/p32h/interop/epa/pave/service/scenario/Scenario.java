package org.p32h.interop.epa.pave.service.scenario;

import org.p32h.interop.epa.pave.model.ScenarioSummary;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * One mock scenario file.
 *
 * <p>A protocol with a single call per run (CRD) uses {@code main}. A protocol whose run spans
 * several calls (PAS submit then inquire, the DTR adaptive loop) adds named {@code steps} and
 * reads anything more particular from {@code raw}.
 *
 * @param number           the position in the numbered list testers refer to
 * @param sentinelMemberId the first member ID that selects this scenario, or null. Kept for the
 *                         places that show one sentinel; selection uses the whole list
 * @param sentinelNpi      the first NPI that selects this scenario, or null
 * @param main             what the mock does for the scenario's only or first call. Null when the
 *                         file describes its calls only as named steps
 * @param steps            further calls by name. Empty when there are none
 * @param expect           what the file claims to produce. Null when the file makes no claim
 * @param basis            where the content came from. Every bundled file is synthetic and says so
 * @param assumption       what the file assumes about the live service, when it assumes anything
 * @param raw              the whole file, for protocol-specific sections
 * @param source           the file name, for messages
 * @param sentinelMemberIds every member ID that selects this scenario, in the order the file gives
 *                         them: {@code sentinelMemberId} first, then {@code sentinelMemberIds}.
 *                         One file may answer a sentinel of another protocol as well as its own
 * @param sentinelNpis     every NPI that selects this scenario, in the same order
 */
public record Scenario(String id, int number, String label, String description, String sentinelMemberId,
                       String sentinelNpi, ScenarioStep main, Map<String, ScenarioStep> steps, ScenarioExpect expect,
                       String basis, String assumption, JsonNode raw, String source, List<String> sentinelMemberIds,
                       List<String> sentinelNpis) {

    public Scenario {
        steps = steps == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(steps));
        // The single sentinel and the list never disagree: each is filled from the other when it is missing.
        sentinelMemberIds = sentinelMemberIds == null || sentinelMemberIds.isEmpty()
                ? (sentinelMemberId == null ? List.of() : List.of(sentinelMemberId)) : List.copyOf(sentinelMemberIds);
        sentinelNpis = sentinelNpis == null || sentinelNpis.isEmpty()
                ? (sentinelNpi == null ? List.of() : List.of(sentinelNpi)) : List.copyOf(sentinelNpis);
        sentinelMemberId = sentinelMemberIds.isEmpty() ? null : sentinelMemberIds.get(0);
        sentinelNpi = sentinelNpis.isEmpty() ? null : sentinelNpis.get(0);
    }

    /** A scenario with at most one sentinel of each kind, as every file was before a file could name several. */
    public Scenario(String id, int number, String label, String description, String sentinelMemberId, String sentinelNpi,
                    ScenarioStep main, Map<String, ScenarioStep> steps, ScenarioExpect expect, String basis, String assumption,
                    JsonNode raw, String source) {
        this(id, number, label, description, sentinelMemberId, sentinelNpi, main, steps, expect, basis, assumption, raw, source,
                null, null);
    }

    public ScenarioSummary summary() {
        return new ScenarioSummary(id, number, label, description, sentinelMemberId, sentinelNpi,
                main == null ? null : main.behavior(), main == null ? null : main.httpStatus(),
                expect == null ? null : expect.outcome());
    }

    /**
     * The step for a call: the main step when no name is given, otherwise the step of that name.
     * A name the file does not define yields null. It does not fall back to the main step, because
     * answering an inquiry with the submission's response would be an answer to a question that
     * was not asked.
     */
    public ScenarioStep step(String name) {
        return name == null ? main : steps.get(name);
    }
}
