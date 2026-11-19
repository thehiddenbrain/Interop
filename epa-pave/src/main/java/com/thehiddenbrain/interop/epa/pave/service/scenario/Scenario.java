package com.thehiddenbrain.interop.epa.pave.service.scenario;

import com.thehiddenbrain.interop.epa.pave.model.ScenarioSummary;
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
 * @param sentinelMemberId the member ID that selects this scenario, or null
 * @param sentinelNpi      the NPI that selects this scenario, or null
 * @param main             what the mock does for the scenario's only or first call. Null when the
 *                         file describes its calls only as named steps
 * @param steps            further calls by name. Empty when there are none
 * @param expect           what the file claims to produce. Null when the file makes no claim
 * @param basis            where the content came from. Every bundled file is synthetic and says so
 * @param assumption       what the file assumes about the live service, when it assumes anything
 * @param raw              the whole file, for protocol-specific sections
 * @param source           the file name, for messages
 */
public record Scenario(String id, int number, String label, String description, String sentinelMemberId,
                       String sentinelNpi, ScenarioStep main, Map<String, ScenarioStep> steps, ScenarioExpect expect,
                       String basis, String assumption, JsonNode raw, String source) {

    public Scenario {
        steps = steps == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(steps));
    }

    public ScenarioSummary summary() {
        return new ScenarioSummary(id, number, label, description, sentinelMemberId, sentinelNpi,
                main == null ? null : main.behavior(), main == null ? null : main.httpStatus(),
                expect == null ? null : expect.outcome());
    }

    /** The named step, or the main step when there is no step of that name. Null when neither exists. */
    public ScenarioStep step(String name) {
        ScenarioStep named = name == null ? null : steps.get(name);
        return named != null ? named : main;
    }
}
