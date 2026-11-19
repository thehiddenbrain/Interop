package org.p32h.interop.epa.pave.client.pas;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
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
 * The PAS client on the mock profile. It answers from the scenario files under
 * {@code scenarios/pas} and needs no configuration, no credentials and no network.
 *
 * <p>A submission is answered from a scenario's main step and an inquiry from its step named
 * {@code inquire}. A scenario that defines no such step does not answer that call: an inquiry is
 * never answered with the submission's response.
 *
 * <p>Every scenario is synthetic. None was captured from the live service, and the files that
 * model behaviour the published profile does not define say what they assume.
 */
@Component
@Profile("mock")
public class MockOnyxPasClient implements OnyxPasClient {

    /** The directory under {@code scenarios/} that holds the PAS, subscription and notification scenario files. */
    public static final String SCENARIOS = "pas";

    /** The step of a scenario that answers an inquiry. */
    public static final String STEP_INQUIRE = "inquire";

    /** The step of a scenario that holds the notification a service would send. Nothing calls it: the receiver is given it. */
    public static final String STEP_NOTIFY = "notify";

    /**
     * Where a scenario file writes this, the mock puts the identifier of the claim it is answering.
     * A real payer answers about the claim it was sent, and a ClaimResponse that named the same
     * claim for every submission could not be matched to one of them afterwards.
     */
    public static final String CLAIM_ID_TOKEN = "{{claimId}}";

    /** What the token becomes when the request carried no claim identifier. */
    static final String UNIDENTIFIED_CLAIM = "unidentified-claim";

    private final ScenarioRepository scenarios;
    private final MockExchanges exchanges;

    public MockOnyxPasClient(ScenarioRepository scenarios, MockExchanges exchanges) {
        this.scenarios = scenarios;
        this.exchanges = exchanges;
    }

    @Override
    public VendorExchange submit(PreparedCall call, ResolvedText body, MockSelection selection) {
        return answer(call, body, selection, null);
    }

    @Override
    public VendorExchange inquire(PreparedCall call, ResolvedText body, MockSelection selection) {
        return answer(call, body, selection, STEP_INQUIRE);
    }

    private VendorExchange answer(PreparedCall call, ResolvedText body, MockSelection selection, String step) {
        String text = body == null ? null : body.value();
        ScenarioSelection chosen = scenarios.select(SCENARIOS, selection, text);
        VendorExchange exchange = asAnswerTo(SentClaim.fromBody(text),
                exchanges.answer(call, body, HttpOnyxPasClient.FHIR_OPTIONS, chosen, step));
        // The same recognition rules the live client applies, so mock cannot disagree with live about them.
        return exchange.withSignals(OnyxPasConventions.signals(exchange));
    }

    /**
     * Makes a canned answer an answer to the claim that was sent, in the two ways a real payer's
     * answer always is. It names that claim wherever the file leaves room for its identifier. And
     * it answers every item: a scenario file that holds the decision for one item has that
     * decision repeated for each further item sent, because otherwise every mock run with two
     * codes would report the second as undecided and testers would learn to ignore the finding
     * that matters most on live. The result says that the answer was repeated, so it is not taken
     * for anything the scenario file holds.
     */
    private static VendorExchange asAnswerTo(SentClaim sent, VendorExchange exchange) {
        if (exchange.json() == null || !exchange.json().isContainer()) {
            return exchange;
        }
        JsonNode copy = exchange.json().deepCopy();
        boolean named = replace(copy, CLAIM_ID_TOKEN, Texts.orElse(sent.claimId(), UNIDENTIFIED_CLAIM));
        boolean repeated = repeatTheOneItem(copy, sent);
        if (!named && !repeated) {
            return exchange;
        }
        String note = exchange.scenarioNote() + (repeated ? ". The scenario file answers one item, and that answer is repeated here "
                + "for each of the " + sent.items().size() + " items sent" : "");
        return Exchanges.fromResponse(exchange.request(), exchange.httpStatus(), exchange.responseHeaders(), JsonSafe.pretty(copy),
                exchange.elapsedMillis(), true).withScenario(exchange.scenarioId(), note);
    }

    /** Replaces the token in every string of the document. Done on the tree, so the value can hold any character. */
    static boolean replace(JsonNode node, String token, String value) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (String name : new ArrayList<>(object.propertyNames())) {
                JsonNode child = object.get(name);
                if (child.isString() && child.stringValue().contains(token)) {
                    object.put(name, child.stringValue().replace(token, value));
                    changed = true;
                } else if (child.isContainer()) {
                    changed |= replace(child, token, value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode child = array.get(i);
                if (child.isString() && child.stringValue().contains(token)) {
                    array.set(i, child.stringValue().replace(token, value));
                    changed = true;
                } else if (child.isContainer()) {
                    changed |= replace(child, token, value);
                }
            }
        }
        return changed;
    }

    /**
     * When the first ClaimResponse answers exactly one item, and that item is the first one sent,
     * copies its answer for every further item sent. A file that answers several items is left as
     * written: the difference between its items is the point of it.
     */
    private static boolean repeatTheOneItem(JsonNode body, SentClaim sent) {
        if (sent.items().size() < 2 || sent.items().get(0).sequence() == null) {
            return false;
        }
        for (JsonNode resource : OnyxPasConventions.resources(body)) {
            if (!"ClaimResponse".equals(JsonSafe.text(resource, "resourceType"))) {
                continue;
            }
            List<JsonNode> items = JsonSafe.elements(resource, "item");
            if (items.size() != 1 || !(resource.get("item") instanceof ArrayNode array)
                    || !sent.items().get(0).sequence().equals(JsonSafe.integer(items.get(0), "itemSequence"))) {
                return false;
            }
            boolean repeated = false;
            for (SentClaim.Item further : sent.items().subList(1, sent.items().size())) {
                if (further.sequence() != null && items.get(0) instanceof ObjectNode first) {
                    ObjectNode again = first.deepCopy();
                    again.put("itemSequence", further.sequence().intValue());
                    array.add(again);
                    repeated = true;
                }
            }
            return repeated;
        }
        return false;
    }
}
