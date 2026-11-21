package org.p32h.interop.epa.pave.client.pas;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioSelection;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

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
        return echoTheEndpoint(answer(call, body, HttpSubscriptionClient.WRITE_OPTIONS, selection, STEP_CREATE), body);
    }

    @Override
    public VendorExchange get(PreparedCall call, MockSelection selection) {
        boolean one = selection != null && Texts.present(selection.hints().get(HINT_SUBSCRIPTION_ID));
        return answer(call, null, HttpSubscriptionClient.READ_OPTIONS, selection, one ? STEP_READ : STEP_LIST);
    }

    @Override
    public VendorExchange update(PreparedCall call, ResolvedText body, MockSelection selection) {
        return echoTheEndpoint(answer(call, body, HttpSubscriptionClient.WRITE_OPTIONS, selection, STEP_UPDATE), body);
    }

    /**
     * A subscription the mock answers a create or an update with names the address to call back
     * that the request sent, or none when it sent none. A scenario file holds one address, and
     * answering with it would have the service call back an address nobody gave it: the result
     * then said "It calls back ..." beside "the request named no url to call back".
     */
    static VendorExchange echoTheEndpoint(VendorExchange exchange, ResolvedText body) {
        if (exchange.json() == null || !exchange.json().isObject() || body == null) {
            return exchange;
        }
        JsonNode channel = JsonSafe.child(exchange.json(), "channel");
        if (!(channel instanceof ObjectNode) || !channel.has("endpoint")) {
            return exchange;
        }
        String sent = JsonSafe.presentText(JsonSafe.child(JsonSafe.parse(body.value()).node(), "channel"), "endpoint");
        String answered = JsonSafe.presentText(channel, "endpoint");
        if (java.util.Objects.equals(sent, answered)) {
            return exchange;
        }
        ObjectNode copy = (ObjectNode) exchange.json().deepCopy();
        ObjectNode copied = (ObjectNode) copy.get("channel");
        if (sent == null) {
            copied.remove("endpoint");
        } else {
            copied.put("endpoint", sent);
        }
        return Exchanges.fromResponse(exchange.request(), exchange.httpStatus(), exchange.responseHeaders(), JsonSafe.pretty(copy),
                        exchange.elapsedMillis(), true)
                .withScenario(exchange.scenarioId(), Texts.orElse(exchange.scenarioNote(), "") + (sent == null
                        ? ". The subscription it answers with names no address to call back, because the request named none"
                        : ". The subscription it answers with names the address to call back that the request sent"));
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
