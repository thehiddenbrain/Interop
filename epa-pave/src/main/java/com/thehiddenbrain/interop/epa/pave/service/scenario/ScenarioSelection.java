package com.thehiddenbrain.interop.epa.pave.service.scenario;

/**
 * The scenario a mock answered from, and why that one.
 *
 * @param how one plain phrase saying how it was chosen, shown to the tester so a default is never
 *            mistaken for a deliberate choice
 */
public record ScenarioSelection(Scenario scenario, String how) {
}
