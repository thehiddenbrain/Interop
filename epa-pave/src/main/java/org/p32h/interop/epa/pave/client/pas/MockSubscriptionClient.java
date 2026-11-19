package org.p32h.interop.epa.pave.client.pas;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioSelection;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The subscription client on the mock profile. It answers from the named steps of the scenario
 * files under {@code scenarios/pas}, one step for each of the five subscription calls.
 *
 * <p>A subscription call carries no member, so no sentinel member ID can select its scenario. When
 * the tester chose none, the answer comes from the first scenario that defines the step for the
 * call, and the result says that it was a default.
 */
@Component
@Profile("mock")
public class MockSubscriptionClient implements SubscriptionClient {

    public static final String STEP_CREATE = "subscription-create";
    public static final String STEP_READ = "subscription-read";
    public static final String STEP_LIST = "subscription-list";
    public static final String STEP_UPDATE = "subscription-update";
    public static final String STEP_DELETE = "subscription-delete";

    /**
     * The selection hint that carries the id of the subscription a call is about. A read and a list
     * are the same call to the client and differ only in the url, which the mock profile may not
     * have, so the id is what tells the mock which of the two to answer.
     */
    public static final String HINT_SUBSCRIPTION_ID = "subscriptionId";

    private final ScenarioRepository scenarios;
    private final MockExchanges exchanges;

    public MockSubscriptionClient(ScenarioRepository scenarios, MockExchanges exchanges) {
        this.scenarios = scenarios;
        this.exchanges = exchanges;
    }

    @Override
    public VendorExchange create(PreparedCall call, ResolvedText body, MockSelection selection) {
        return answer(call, body, HttpSubscriptionClient.WRITE_OPTIONS, selection, STEP_CREATE);
    }

    @Override
    public VendorExchange get(PreparedCall call, MockSelection selection) {
        boolean one = selection != null && Texts.present(selection.hints().get(HINT_SUBSCRIPTION_ID));
        return answer(call, null, HttpSubscriptionClient.READ_OPTIONS, selection, one ? STEP_READ : STEP_LIST);
    }

    @Override
    public VendorExchange update(PreparedCall call, ResolvedText body, MockSelection selection) {
        return answer(call, body, HttpSubscriptionClient.WRITE_OPTIONS, selection, STEP_UPDATE);
    }

    @Override
    public VendorExchange delete(PreparedCall call, MockSelection selection) {
        return answer(call, null, HttpSubscriptionClient.DELETE_OPTIONS, selection, STEP_DELETE);
    }

    private VendorExchange answer(PreparedCall call, ResolvedText body, ExchangeOptions options, MockSelection selection, String step) {
        return exchanges.answer(call, body, options, choose(selection, step), step);
    }

    /**
     * The scenario a subscription call is answered from: the one the tester chose, otherwise the
     * first that defines the step. A chosen scenario that does not define the step is still
     * returned, and the shared mock code then refuses the call by naming both.
     */
    private ScenarioSelection choose(MockSelection selection, String step) {
        if (selection != null && Texts.present(selection.scenarioId())) {
            return scenarios.select(MockOnyxPasClient.SCENARIOS, MockSelection.of(selection.scenarioId(), null, null));
        }
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            if (scenario.step(step) != null) {
                return new ScenarioSelection(scenario, "the default for a subscription call, because no scenario was chosen");
            }
        }
        throw new IllegalStateException("There is no mock scenario that answers the step " + step + ".");
    }
}
