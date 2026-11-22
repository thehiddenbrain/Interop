package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;

/**
 * The next step after a subscription a scenario answered names the Status step and its button in the
 * words the button carries (plan 7.4, one name per action), and a subscription a service answered
 * keeps its own next step.
 */
class SubscriptionVerdictsTest {

    /** An answered exchange with the scenario that played it, or with none for a service's answer. */
    private static VendorExchange exchange(String scenarioId) {
        return new VendorExchange(null, 201, List.of(), "{}", 2L, null, null, 5L, OnyxFailureType.NONE, null, Set.of(), scenarioId, null,
                List.of());
    }

    private static NextStepView afterCreate(String scenarioId) {
        SubscriptionReading reading = new SubscriptionReading(null, SubscriptionOutcome.CREATED, List.of());
        return SubscriptionVerdicts.nextStep(reading, exchange(scenarioId), EpaOperation.SUBSCRIPTION_CREATE, "sub-1", null, null,
                false, null);
    }

    @Test
    void aSubscriptionAScenarioCreatedLeadsToTheStatusStepsButtonByItsOwnWords() {
        NextStepView next = afterCreate("pas-10-subscription-lifecycle");
        assertThat(next.action()).isEqualTo(NextAction.DELIVER_SAMPLE_NOTIFICATION);
        assertThat(next.headline()).isEqualTo("Next: in the Status step, Deliver a sample for this submission.");
        assertThat(next.headline()).contains(NextAction.DELIVER_SAMPLE_NOTIFICATION.button());
        assertThat(NextAction.DELIVER_SAMPLE_NOTIFICATION.label()).isEqualTo("In the Status step, Deliver a sample for this submission.");
        assertThat(next.reason()).startsWith("A scenario answered this create, so no service holds the subscription");
    }

    @Test
    void aSubscriptionAServiceCreatedStillLeadsToSubmittingARequest() {
        NextStepView next = afterCreate(null);
        assertThat(next.action()).isEqualTo(NextAction.START_PAS);
        assertThat(next.headline()).doesNotContain("Deliver a sample");
    }
}
