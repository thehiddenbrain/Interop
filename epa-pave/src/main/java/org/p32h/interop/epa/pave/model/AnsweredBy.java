package org.p32h.interop.epa.pave.model;

/**
 * Who answered a run: the service, a mock scenario file in its place, or nobody.
 *
 * <p>The screen draws a result by it. A scenario's answer carries the hatch of the mock, a step
 * nobody answered carries neither the hatch nor the solid colour the colour key gives to an answer
 * from the payer. The browser does not work this out from the profile or the operation: the server
 * says it, on the result and on every ledger line, so the strip, the History row and the colour key
 * cannot disagree.
 */
public enum AnsweredBy {
    /**
     * The request went to the service, on the live profile. What came back is the service's, and
     * so is the absence of an answer: a timeout or a refused connection is drawn by its own tone.
     * A notification that arrived on the live profile is in this group too: it came from outside
     * this tool.
     */
    SERVICE,
    /**
     * A scenario file answered in place of the service, on the mock profile. It may have played a
     * fault. A sample notification delivered on the mock profile is in this group: a scenario file
     * played it, and no service sent it.
     */
    SCENARIO,
    /**
     * Nobody answered: the step was done inside this tool, as a questionnaire response is
     * completed, and nothing was sent, on either profile.
     */
    NOBODY,
    /** Nobody answered: the request was refused before it was sent, so nothing left this machine. */
    NOT_SENT;

    /**
     * The one rule, for a result and for a ledger line alike, from what both hold.
     *
     * @param operation the operation of the run, or null for a line that is not a run
     * @param profile   mock or live, as the run recorded it
     * @param refused   true when the run was refused before it was sent
     * @return who answered, or null when there is no operation to judge by
     */
    public static AnsweredBy of(EpaOperation operation, String profile, boolean refused) {
        if (operation == null) {
            return null;
        }
        if (refused) {
            return NOT_SENT;
        }
        // A notification is not sent by this tool, but it is not a step done inside it either:
        // something played it or sent it. On the mock profile that is the scenario file a sample is
        // delivered from, on live the service. The line holds nothing finer to tell them by, so a
        // notification posted to the receiver by hand while the tool runs on mock reads as the mock's.
        if (operation == EpaOperation.SUBSCRIPTION_NOTIFICATION) {
            return "mock".equalsIgnoreCase(Texts.trimToNull(profile)) ? SCENARIO : SERVICE;
        }
        if (!operation.outbound()) {
            return NOBODY;
        }
        return "mock".equalsIgnoreCase(Texts.trimToNull(profile)) ? SCENARIO : SERVICE;
    }
}
