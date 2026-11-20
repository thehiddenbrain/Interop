package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.VendorExchange;

/**
 * The next step after a timeout a mock scenario played, for every protocol alike.
 *
 * <p>The error panel says once that a scenario played the timeout, that nothing was sent and that
 * no environment failed. The verdict and the next step therefore say nothing of the kind again.
 * What a tester would see and do after a real timeout is still worth rehearsing, so the next step
 * keeps its advice and its action, worded as what would hold had this been a real service: said
 * as what happened, "the payer may have received the submission" contradicts "nothing was sent".
 */
public final class PlayedTimeouts {

    private static final String LIVE_PREFIX = "Next: ";
    private static final String PLAYED_PREFIX = "Next, had this been a real service: ";

    private PlayedTimeouts() {
    }

    /**
     * The step as a rehearsal when the exchange is a timeout a scenario played, and the step
     * unchanged otherwise. The action and what is carried stay as they are, so a screen offers the
     * same step it would offer after a real timeout.
     *
     * @param rehearsed the reason, worded as what would hold had this been a real service
     */
    public static NextStepView rehearsed(VendorExchange exchange, NextStepView step, String rehearsed) {
        if (exchange == null || !exchange.playedTimeout() || step == null) {
            return step;
        }
        String headline = step.headline().startsWith(LIVE_PREFIX)
                ? PLAYED_PREFIX + step.headline().substring(LIVE_PREFIX.length()) : step.headline();
        return new NextStepView(step.action(), headline, rehearsed, step.carry(), step.carryLabels());
    }

    /** The reason for a call that changes nothing, such as a read, a CRD hook or a DTR fetch, after a played timeout. */
    public static String aboutTheEnvironment(String notAbout) {
        return "Had this been a real service, a timeout would be a finding about the environment, not about " + notAbout + ".";
    }
}
