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
    /** The coverage information sub-extension that names a questionnaire, as CRD 2.0.1 gives it. */
    static final String QUESTIONNAIRE = "questionnaire";

    private final ScenarioRepository scenarios;
    private final MockExchanges exchanges;

    public MockOnyxCrdClient(ScenarioRepository scenarios, MockExchanges exchanges) {
        this.scenarios = scenarios;
        this.exchanges = exchanges;
    }

    @Override
    public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
        String text = body == null ? null : body.value();
        ScenarioSelection chosen = scenarios.select(SCENARIOS, selection, text);
        VendorExchange exchange = exchanges.answer(call, body, HttpOnyxCrdClient.HOOK_OPTIONS, chosen, null);
        if (repeatsAKey(exchange)) {
            // Writing the body out again from its tree would keep only the last value of each
            // repeated key, so the scenario could not play the case it was written for, and the
            // wire view would not be the scenario's text. It is played as written instead.
            return exchange.withScenario(exchange.scenarioId(), exchange.scenarioNote() + ". The scenario's body repeats a key, so it "
                    + "is played exactly as written: the orders the request sent are not carried back on it")
                    .withSignals(OnyxCrdConventions.signals(exchange));
        }
        if (chosen.via() != null) {
            // However this scenario came to answer, selected by the DTR sentinel or chosen in the selector with one as the member.
            exchange = nameTheQuestionnairesOf(chosen.via(), exchange, chosen.by() == ScenarioSelection.By.DTR_SENTINEL);
        }
        exchange = echoTheOrders(answerEveryOrder(exchange, SentOrder.fromBody(text)), text);
        // The same recognition rules the live client applies, so mock cannot disagree with live about them.
        return exchange.withSignals(OnyxCrdConventions.signals(exchange));
    }

    private static boolean repeatsAKey(VendorExchange exchange) {
        return exchange.json() != null && exchange.responseBody() != null && JsonSafe.parse(exchange.responseBody()).repeatedKeys();
    }

    /**
     * For a DTR sentinel member, the questionnaires CRD names are the ones the package of that DTR
     * scenario holds, read from the DTR file, so that DTR, given what CRD hands on, answers as its
     * label says and does not report a questionnaire the two synthetic data sets merely disagree
     * on. A DTR scenario whose package holds no questionnaire is answered with the file as written.
     */
    private static VendorExchange nameTheQuestionnairesOf(Scenario dtr, VendorExchange exchange, boolean selectedBySentinel) {
        // The selection note already says the member is a DTR sentinel when that is what selected the file; a choice in the selector does not.
        String why = selectedBySentinel ? "" : ". The member ID is the test member ID of DTR scenario " + dtr.id() + ", and this "
                + "scenario answers every DTR test member ID";
        List<String> held = questionnairesHeldBy(dtr);
        if (held.isEmpty() || exchange.json() == null || !exchange.json().isContainer()) {
            return exchange.withScenario(exchange.scenarioId(), exchange.scenarioNote() + why + ". The package of DTR scenario " + dtr.id()
                    + " holds no questionnaire, so the questionnaire this file names is named");
        }
        JsonNode copy = exchange.json().deepCopy();
        List<String> named = new ArrayList<>();
        collectNamed(copy, named);
        renameQuestionnaires(copy, held, named);
        if (named.isEmpty()) {
            return exchange;
        }
        return Exchanges.fromResponse(exchange.request(), exchange.httpStatus(), exchange.responseHeaders(), JsonSafe.pretty(copy),
                        exchange.elapsedMillis(), true)
                .withScenario(exchange.scenarioId(), exchange.scenarioNote() + why + ". The questionnaire named is the one the package of DTR "
                        + "scenario " + dtr.id() + " holds, " + String.join(" and ", held) + ", in place of " + String.join(" and ", named)
                        + ", so that DTR answers as its label says");
    }

    /** The canonical url of every questionnaire in the package a DTR scenario answers with, in the order the file gives them. */
    public static List<String> questionnairesHeldBy(Scenario dtr) {
        List<String> urls = new ArrayList<>();
        if (dtr.main() != null && dtr.main().body() != null) {
            JsonSafe.Parsed parsed = JsonSafe.parse(dtr.main().body());
            if (parsed.ok()) {
                collectQuestionnaires(parsed.node(), urls);
            }
        }
        return urls;
    }

    private static void collectQuestionnaires(JsonNode node, List<String> urls) {
        if ("Questionnaire".equals(JsonSafe.text(node, "resourceType"))) {
            String url = JsonSafe.presentText(node, "url");
            if (url != null && !urls.contains(url.strip())) {
                urls.add(url.strip());
            }
        }
        for (Map.Entry<String, JsonNode> property : JsonSafe.properties(node)) {
            collectQuestionnaires(property.getValue(), urls);
        }
        for (JsonNode element : JsonSafe.elements(node)) {
            collectQuestionnaires(element, urls);
        }
    }

    /** The questionnaires the file names in a questionnaire sub-extension, each once. */
    private static void collectNamed(JsonNode node, List<String> named) {
        if (QUESTIONNAIRE.equals(JsonSafe.text(node, "url"))) {
            String canonical = JsonSafe.presentText(node, "valueCanonical");
            if (canonical != null && !named.contains(canonical)) {
                named.add(canonical);
            }
        }
        for (Map.Entry<String, JsonNode> property : JsonSafe.properties(node)) {
            collectNamed(property.getValue(), named);
        }
        for (JsonNode element : JsonSafe.elements(node)) {
            collectNamed(element, named);
        }
    }

    /**
     * Replaces every questionnaire sub-extension of every coverage information extension with one
     * for each questionnaire held, where the first one stood, and the questionnaire a card link's
     * appContext names with the same list. {@code named} is what the file names.
     */
    private static void renameQuestionnaires(JsonNode node, List<String> held, List<String> named) {
        if (node instanceof ObjectNode object) {
            for (String name : new ArrayList<>(object.propertyNames())) {
                JsonNode child = object.get(name);
                if ("appContext".equals(name) && child.isString()) {
                    String context = child.stringValue();
                    for (String old : named) {
                        context = context.replace(QUESTIONNAIRE + "=" + old, String.join("&", held.stream().map(u -> QUESTIONNAIRE + "=" + u).toList()));
                    }
                    object.put(name, context);
                } else if (child.isContainer()) {
                    renameQuestionnaires(child, held, named);
                }
            }
        } else if (node instanceof ArrayNode array) {
            int first = -1;
            for (int i = array.size() - 1; i >= 0; i--) {
                JsonNode element = array.get(i);
                if (QUESTIONNAIRE.equals(JsonSafe.text(element, "url")) && element.has("valueCanonical")) {
                    array.remove(i);
                    first = i;
                }
            }
            if (first >= 0) {
                for (int i = 0; i < held.size(); i++) {
                    ObjectNode added = array.insertObject(first + i);
                    added.put("url", QUESTIONNAIRE);
                    added.put("valueCanonical", held.get(i));
                }
            }
            for (JsonNode element : array) {
                if (element.isContainer()) {
                    renameQuestionnaires(element, held, named);
                }
            }
        }
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
