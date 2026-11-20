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
     * A notification is in this group too: it arrived from outside this tool.
     */
    SERVICE,
    /** A scenario file answered in place of the service, on the mock profile. It may have played a fault. */
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
        if (operation == EpaOperation.SUBSCRIPTION_NOTIFICATION) {
            return SERVICE;
        }
        if (!operation.outbound()) {
            return NOBODY;
        }
        return "mock".equalsIgnoreCase(Texts.trimToNull(profile)) ? SCENARIO : SERVICE;
    }
}
