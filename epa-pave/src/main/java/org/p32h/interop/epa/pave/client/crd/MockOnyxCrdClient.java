package org.p32h.interop.epa.pave.client.crd;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioSelection;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The CRD client on the mock profile. It answers from the scenario files under
 * {@code scenarios/crd} and needs no configuration, no credentials and no network.
 *
 * <p>Every scenario is synthetic. None was captured from the live service, and the files that
 * model behaviour the published profile does not define say what they assume.
 */
@Component
@Profile("mock")
public class MockOnyxCrdClient implements OnyxCrdClient {

    /** The directory under {@code scenarios/} that holds the CRD scenario files. */
    public static final String SCENARIOS = "crd";

    private final ScenarioRepository scenarios;
    private final MockExchanges exchanges;

    public MockOnyxCrdClient(ScenarioRepository scenarios, MockExchanges exchanges) {
        this.scenarios = scenarios;
        this.exchanges = exchanges;
    }

    @Override
    public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
        ScenarioSelection chosen = scenarios.select(SCENARIOS, selection, body == null ? null : body.value());
        VendorExchange exchange = answerEveryOrder(exchanges.answer(call, body, HttpOnyxCrdClient.HOOK_OPTIONS, chosen, null),
                SentOrder.fromBody(body == null ? null : body.value()));
        // The same recognition rules the live client applies, so mock cannot disagree with live about them.
        return exchange.withSignals(OnyxCrdConventions.signals(exchange));
    }

    /**
     * A scenario file holds the answer for one order. A real service answers every order it is
     * sent, so when the request carries several the mock repeats that answer for each of them.
     * Without this, every mock run with two codes would report the second as unanswered, and
     * testers would learn to ignore the one finding that matters most on live. The result says
     * that the answer was repeated, so it is not taken for anything the scenario file holds.
     */
    private static VendorExchange answerEveryOrder(VendorExchange exchange, List<SentOrder> orders) {
        if (orders.size() < 2 || exchange.json() == null || !exchange.json().isObject()) {
            return exchange;
        }
        String firstId = idOf(orders.get(0));
        if (firstId == null) {
            return exchange;
        }
        ObjectNode copy = (ObjectNode) exchange.json().deepCopy();
        boolean repeated = repeat(copy.get("systemActions"), firstId, orders);
        for (JsonNode card : JsonSafe.elements(copy, "cards")) {
            for (JsonNode suggestion : JsonSafe.elements(card, "suggestions")) {
                repeated |= repeat(suggestion.get("actions"), firstId, orders);
            }
        }
        if (!repeated) {
            return exchange;
        }
        return Exchanges.fromResponse(exchange.request(), exchange.httpStatus(), exchange.responseHeaders(), JsonSafe.pretty(copy),
                        exchange.elapsedMillis(), true)
                .withScenario(exchange.scenarioId(), exchange.scenarioNote() + ". The scenario file answers one order, and that "
                        + "answer is repeated here for each of the " + orders.size() + " orders sent");
    }

    /** Copies each action that carries the first order, once for every further order, with that order's id. */
    private static boolean repeat(JsonNode actions, String firstId, List<SentOrder> orders) {
        if (!(actions instanceof ArrayNode array)) {
            return false;
        }
        List<JsonNode> forFirst = new ArrayList<>();
        for (JsonNode action : array) {
            if (firstId.equals(JsonSafe.presentText(JsonSafe.child(action, "resource"), "id"))) {
                forFirst.add(action);
            }
        }
        boolean repeated = false;
        for (int i = 1; i < orders.size(); i++) {
            String id = idOf(orders.get(i));
            if (id == null) {
                continue;
            }
            for (JsonNode action : forFirst) {
                JsonNode again = action.deepCopy();
                ((ObjectNode) again.get("resource")).put("id", id);
                array.add(again);
                repeated = true;
            }
        }
        return repeated;
    }

    private static String idOf(SentOrder order) {
        String reference = order.reference();
        return reference == null ? null : reference.substring(reference.lastIndexOf('/') + 1);
    }

    /**
     * Discovery on mock lists one placeholder service for each hook, so the Config and CRD tabs
     * have something to show. The ids are not the vendor's: those are not known.
     */
    @Override
    public VendorExchange discover(PreparedCall call) {
        String body = """
                {
                  "services": [
                    {
                      "hook": "order-sign",
                      "id": "placeholder-order-sign-service",
                      "title": "Mock coverage requirements discovery, order-sign",
                      "description": "Placeholder entry from the mock profile. The service ids of the live service are not known."
                    },
                    {
                      "hook": "order-select",
                      "id": "placeholder-order-select-service",
                      "title": "Mock coverage requirements discovery, order-select",
                      "description": "Placeholder entry from the mock profile. The service ids of the live service are not known."
                    }
                  ]
                }""";
        ExchangeOptions options = HttpOnyxCrdClient.DISCOVERY_OPTIONS;
        return Exchanges.fromResponse(
                Exchanges.wireRequest(call, null, options, false, "Mock profile. Discovery is answered with placeholder services. Nothing left this machine."),
                200, List.of(new WireHeader("Content-Type", "application/json")), body, 0L, true);
    }
}
