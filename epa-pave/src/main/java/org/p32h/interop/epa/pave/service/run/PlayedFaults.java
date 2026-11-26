package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;

/**
 * The next step after a fault a mock scenario played, for every protocol alike: a timeout, a
 * failure, an unreachable service, a rejection or an answer that cannot be used.
 *
 * <p>The error panel says once that a scenario played the fault, that nothing was sent and that
 * no environment failed. The verdict and the next step therefore say nothing of the kind again:
 * under the banner "nothing leaves this machine", "check the environment" would send a tester to
 * fix something that never failed. What a tester would see and do after a real fault is still
 * worth rehearsing, so the next step keeps its action and what it carries, and its headline is
 * worded as what would hold had this been a real service.
 *
 * <p>Whether a fault was played is read from the scenario and the outcome, not from the failure
 * type of the exchange: a questionnaire that cannot be used arrives as a success with no failure
 * type at all, and is a fault all the same.
 */
public final class PlayedFaults {

    private static final String LIVE_PREFIX = "Next: ";
    private static final String PLAYED_PREFIX = "Next, had this been a real service: ";

    private PlayedFaults() {
    }

    /** True when a mock scenario answered and what it answered is a fault. On live no exchange carries a scenario. */
    public static boolean played(VendorExchange exchange, OutcomeCode outcome) {
        return exchange != null && outcome != null && Texts.present(exchange.scenarioId()) && outcome.fault();
    }

    /**
     * The step as a rehearsal when a scenario played the fault, and the step unchanged otherwise.
     * The action and what is carried stay as they are, so a screen offers the same step it would
     * offer after a real fault.
     *
     * @param timeoutReason the reason after a played timeout, worded as what a real timeout would
     *                      mean: a timeout leaves open whether the request arrived, which is worth
     *                      rehearsing in its own words. Every other played fault gets {@link #pickAnother()}
     */
    public static NextStepView rehearsed(VendorExchange exchange, OutcomeCode outcome, NextStepView step, String timeoutReason) {
        if (step == null || !played(exchange, outcome)) {
            return step;
        }
        String headline = step.headline().startsWith(LIVE_PREFIX)
                ? PLAYED_PREFIX + step.headline().substring(LIVE_PREFIX.length()) : step.headline();
        String reason = exchange.playedTimeout() && Texts.present(timeoutReason) ? timeoutReason : pickAnother();
        return new NextStepView(step.action(), headline, reason, step.carry(), step.carryLabels());
    }

    /**
     * The reason after a played fault other than a timeout. It names no scenario: the line under
     * the verdict already says which one answered and how it was chosen, and a second naming here
     * would repeat it.
     */
    public static String pickAnother() {
        return "On mock a scenario played this answer, to show how this tool reads it; the line under the verdict names it. Pick "
                + "another case to go on.";
    }

    /** The reason for a call that changes nothing, such as a read, a CRD hook or a DTR fetch, after a played timeout. */
    public static String aboutTheEnvironment(String notAbout) {
        return "Had this been a real service, a timeout would be a finding about the environment, not about " + notAbout + ".";
    }
}
