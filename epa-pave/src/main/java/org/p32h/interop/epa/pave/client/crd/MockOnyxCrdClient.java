package org.p32h.interop.epa.pave.client.crd;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.ExchangeOptions;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioSelection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    /** The DTR scenario files, read only for their sentinel member IDs. */
    static final String DTR_SCENARIOS = "dtr";
    /**
     * The CRD scenario that asks for documentation, which also answers every DTR sentinel member
     * ID. Without it a journey with a DTR sentinel gets the default CRD answer and goes straight to
     * PAS, so a failing, empty or two-questionnaire package could never be met in a chain on mock.
     */
    static final String DOCUMENTATION_SCENARIO = "crd-04-doc-dtr";

    private final ScenarioRepository scenarios;
    private final MockExchanges exchanges;

    public MockOnyxCrdClient(ScenarioRepository scenarios, MockExchanges exchanges) {
        this.scenarios = scenarios;
        this.exchanges = exchanges;
    }

    @Override
    public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
        String text = body == null ? null : body.value();
        ScenarioSelection chosen = forADtrSentinel(selection);
        if (chosen == null) {
            chosen = scenarios.select(SCENARIOS, selection, text);
        }
        VendorExchange exchange = echoTheOrders(answerEveryOrder(exchanges.answer(call, body, HttpOnyxCrdClient.HOOK_OPTIONS, chosen, null),
                SentOrder.fromBody(text)), text);
        // The same recognition rules the live client applies, so mock cannot disagree with live about them.
        return exchange.withSignals(OnyxCrdConventions.signals(exchange));
    }

    /**
     * The documentation scenario, when the member ID is the sentinel of a DTR scenario and of no
     * CRD scenario, and nothing was chosen explicitly. Null otherwise, and the repository decides.
     * The DTR files are read for their sentinels, so a DTR scenario added later is answered too.
     */
    private ScenarioSelection forADtrSentinel(MockSelection selection) {
        if (selection == null || Texts.present(selection.scenarioId()) || Texts.blank(selection.memberId())) {
            return null;
        }
        String memberId = MemberIds.noSpace(selection.memberId());
        List<Scenario> crd = scenarios.list(SCENARIOS);
        boolean crdSentinel = crd.stream().anyMatch(s -> Texts.present(s.sentinelMemberId())
                && MemberIds.noSpace(s.sentinelMemberId()).equalsIgnoreCase(memberId));
        if (crdSentinel) {
            return null;
        }
        Scenario dtr = scenarios.list(DTR_SCENARIOS).stream().filter(s -> Texts.present(s.sentinelMemberId())
                && MemberIds.noSpace(s.sentinelMemberId()).equalsIgnoreCase(memberId)).findFirst().orElse(null);
        Scenario documentation = crd.stream().filter(s -> s.id().equals(DOCUMENTATION_SCENARIO)).findFirst().orElse(null);
        if (dtr == null || documentation == null) {
            return null;
        }
        return new ScenarioSelection(documentation, "selected because " + dtr.sentinelMemberId() + " is the sentinel member ID of DTR "
                + "scenario " + dtr.id() + ", and CRD answers documentation required for every DTR sentinel so that the journey reaches DTR");
    }

    /**
     * A CDS service that updates an order sends the order back. A scenario file holds only the
     * coverage information, so each update is given the order the request sent with that id, code,
     * authoredOn and requester included, and the file's coverage information on it. An update
     * whose id the request did not send is left as the file has it.
     */
    private static VendorExchange echoTheOrders(VendorExchange exchange, String requestBody) {
        if (exchange.json() == null || !exchange.json().isObject()) {
            return exchange;
        }
        Map<String, JsonNode> sent = new LinkedHashMap<>();
        for (JsonNode entry : JsonSafe.elements(JsonSafe.at(JsonSafe.parse(requestBody).node(), "context", "draftOrders"), "entry")) {
            JsonNode resource = JsonSafe.child(entry, "resource");
            String id = JsonSafe.presentText(resource, "id");
            if (resource.isObject() && id != null) {
                sent.put(JsonSafe.presentText(resource, "resourceType") + "/" + id, resource);
            }
        }
        if (sent.isEmpty()) {
            return exchange;
        }
        ObjectNode copy = (ObjectNode) exchange.json().deepCopy();
        boolean echoed = false;
        for (JsonNode action : JsonSafe.elements(copy, "systemActions")) {
            JsonNode resource = JsonSafe.child(action, "resource");
            String key = JsonSafe.presentText(resource, "resourceType") + "/" + JsonSafe.presentText(resource, "id");
            if (action instanceof ObjectNode updated && resource.isObject() && sent.containsKey(key)) {
                ObjectNode order = (ObjectNode) sent.get(key).deepCopy();
                order.set("extension", resource.get("extension"));
                updated.set("resource", order);
                echoed = true;
            }
        }
        if (!echoed) {
            return exchange;
        }
        return Exchanges.fromResponse(exchange.request(), exchange.httpStatus(), exchange.responseHeaders(), JsonSafe.pretty(copy),
                        exchange.elapsedMillis(), true)
                .withScenario(exchange.scenarioId(), exchange.scenarioNote() + ". Each update carries the order the request sent, as a "
                        + "CDS service sends it back, with the scenario's coverage information on it");
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
                      "description": "Placeholder entry from the mock profile. The service ids of the live service are not known. The prefetch and the configuration option are illustrative: the option uses coverage-info, a card type code of CRD 2.0.1, in the shape of its CDSHookServicesExtensionConfiguration, and the key it sits under is this tool's reading of pages the 2.0.1 package does not ship.",
                      "prefetch": {
                        "patient": "Patient/{{context.patientId}}",
                        "practitionerRole": "{{context.userId}}"
                      },
                      "extension": {
                        "davinci-crd.configuration-options": [
                          {
                            "code": "coverage-info",
                            "type": "boolean",
                            "name": "Coverage information",
                            "description": "Whether the service returns coverage information for the orders it is sent.",
                            "default": true
                          }
                        ]
                      }
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
