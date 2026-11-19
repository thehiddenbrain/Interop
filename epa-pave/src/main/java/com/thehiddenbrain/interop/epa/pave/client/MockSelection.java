package com.thehiddenbrain.interop.epa.pave.client;

import java.util.Map;

/**
 * What a mock client needs to pick a canned response. A live client ignores it entirely: letting a
 * tester force an outcome against the real service would produce a result that did not come from
 * the service.
 *
 * @param scenarioId an explicitly chosen scenario, or null to select by sentinel
 * @param memberId   the member ID in its no-space form. A sentinel ID selects a scenario
 * @param npi        the NPI. A sentinel NPI selects a scenario
 * @param hints      anything else a multi-step scenario needs to find the right step
 */
public record MockSelection(String scenarioId, String memberId, String npi, Map<String, String> hints) {

    public MockSelection {
        hints = hints == null ? Map.of() : Map.copyOf(hints);
    }

    public static MockSelection of(String scenarioId, String memberId, String npi) {
        return new MockSelection(scenarioId, memberId, npi, Map.of());
    }

    /** Redacted, because it carries a member ID. */
    @Override
    public String toString() {
        return "MockSelection[scenarioId=" + scenarioId + "]";
    }
}
