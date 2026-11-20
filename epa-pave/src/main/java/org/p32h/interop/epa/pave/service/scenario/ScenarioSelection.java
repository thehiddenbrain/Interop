package org.p32h.interop.epa.pave.service.scenario;

/**
 * The scenario a mock answered from, and why that one.
 *
 * @param how    one plain phrase saying how it was chosen, shown to the tester so a default is never
 *               mistaken for a deliberate choice
 * @param by     which rule chose it, so that a caller acts on the rule and not on the wording of
 *               {@code how}. Null only for a selection a caller built itself
 * @param inBody whether the identifier that chose it was found in a pasted request body rather
 *               than typed on the form
 * @param via    for {@link By#DTR_SENTINEL}, the DTR scenario whose sentinel it was. Null otherwise
 */
public record ScenarioSelection(Scenario scenario, String how, By by, boolean inBody, Scenario via) {

    /** The rules a scenario is chosen by, in the order {@link ScenarioRepository#select} applies them. */
    public enum By {
        /** Chosen in the scenario selector. */
        CHOSEN,
        /** One of the scenario file's own sentinel member IDs. */
        SENTINEL,
        /** A CRD sentinel member ID the file names under {@code crdSentinelMemberIds}, for the same case. */
        CRD_SENTINEL,
        /** In CRD, the sentinel member ID of a DTR scenario, answered by the CRD file that answers every DTR sentinel. */
        DTR_SENTINEL,
        /** One of the scenario file's own sentinel NPIs. */
        SENTINEL_NPI,
        /** Nothing chose a scenario, and the first one in the list answered. */
        DEFAULT
    }

    /** A selection a caller makes itself, outside the rules of the repository. */
    public ScenarioSelection(Scenario scenario, String how) {
        this(scenario, how, null, false, null);
    }
}
