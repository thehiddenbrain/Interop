package org.p32h.interop.epa.pave.client.pas;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.ValidationException;
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
        ScenarioSelection chosen = select(selection, text);
        if (step != null && chosen.scenario().step(step) == null && Texts.blank(selection.scenarioId())) {
            // Nobody chose this scenario: a sentinel typed on the form chose it. The refusal names
            // that field, so the tester is not told to change a choice they never made.
            refuseBySentinel(chosen, step);
        }
        VendorExchange exchange = asAnswerTo(SentClaim.fromBody(text),
                exchanges.answer(call, body, HttpOnyxPasClient.FHIR_OPTIONS, chosen, step));
        // The same recognition rules the live client applies, so mock cannot disagree with live about them.
        return exchange.withSignals(OnyxPasConventions.signals(exchange));
    }

    /**
     * The scenario that answers, by the rule every mock shares. A CRD sentinel for a case PAS has a
     * scenario of its own for selects that scenario, which names it under
     * {@code crdSentinelMemberIds}: a tester who carries one member from CRD into PAS gets the same
     * case in both, not the PAS default, which is an approval.
     */
    private ScenarioSelection select(MockSelection selection, String text) {
        return scenarios.select(SCENARIOS, selection, text);
    }

    private static void refuseBySentinel(ScenarioSelection chosen, String step) {
        String call = STEP_INQUIRE.equals(step) ? "an inquiry" : "the step \"" + step + "\"";
        ScenarioSelection.By by = chosen.by();
        if (chosen.inBody() && (by == ScenarioSelection.By.SENTINEL || by == ScenarioSelection.By.CRD_SENTINEL)) {
            // The sentinel was in the pasted body, so that is the field named, not the member ID on the form.
            throw ValidationException.of("rawBody", "The request body holds a member ID that selects scenario " + chosen.scenario().id()
                    + ", which has no answer for " + call + ". Use a member ID whose scenario answers " + call + ", or choose a scenario.");
        }
        if (by == ScenarioSelection.By.CRD_SENTINEL) {
            // A CRD sentinel is not this scenario's own: it selects the scenario for the same case.
            throw ValidationException.of("member.memberId", "The member ID selects scenario " + chosen.scenario().id()
                    + " (it means the same case as in CRD), which has no answer for " + call + ". Use a member ID whose scenario answers "
                    + call + ", or choose a scenario.");
        }
        if (by == ScenarioSelection.By.SENTINEL) {
            throw ValidationException.of("member.memberId", "The member ID is the sentinel of scenario " + chosen.scenario().id()
                    + ", which has no answer for " + call + ". Use a member ID whose scenario answers " + call + ", or choose a scenario.");
        }
        if (by == ScenarioSelection.By.SENTINEL_NPI) {
            throw ValidationException.of("provider.npi", "The NPI is the sentinel of scenario " + chosen.scenario().id()
                    + ", which has no answer for " + call + ". Use another NPI, or choose a scenario.");
        }
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

    private static void retrace(ObjectNode item, String claimId, Integer from, Integer to) {
        String was = Texts.orElse(claimId, UNIDENTIFIED_CLAIM) + "-" + from;
        for (JsonNode extension : JsonSafe.elements(item, "extension")) {
            if (OnyxPasConventions.pasExtension(JsonSafe.presentText(extension, "url"), OnyxPasConventions.ITEM_TRACE_NUMBER)
                    && extension.get("valueIdentifier") instanceof ObjectNode identifier
                    && was.equals(JsonSafe.presentText(identifier, "value"))) {
                identifier.put("value", Texts.orElse(claimId, UNIDENTIFIED_CLAIM) + "-" + to);
            }
        }
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
                    // An item trace number the file echoes for the first item is echoed for this one as
                    // the request numbered it, so the copy does not claim to answer the first item.
                    retrace(again, sent.claimId(), sent.items().get(0).sequence(), further.sequence());
                    array.add(again);
                    repeated = true;
                }
            }
            return repeated;
        }
        return false;
    }
}
