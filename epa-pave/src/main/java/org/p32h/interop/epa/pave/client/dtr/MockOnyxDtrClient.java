package org.p32h.interop.epa.pave.client.dtr;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioSelection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The DTR client on the mock profile. It answers from the scenario files under
 * {@code scenarios/dtr} and needs no configuration, no credentials and no network.
 *
 * <p>Every scenario is synthetic. None was captured from the live service, and the files that
 * model behaviour the published guides do not define say what they assume.
 *
 * <p>A package fetch is answered by the scenario's main step. A next-question call is answered by
 * one of the scenario's named steps, chosen from the answers in the QuestionnaireResponse that was
 * sent, by the rule the scenario file states in its {@code nextQuestion} section. The answer comes
 * back in the shape the request was sent in: by itself, or inside a Parameters resource.
 *
 * <p>A sentinel means the same case in every protocol. The CRD sentinels for a member not found,
 * a provider not found, a server error, a timeout and a rejected request select the DTR scenario
 * for the same case, which names them under {@link #CRD_SENTINELS}. A scenario file can name one
 * sentinel of its own only, so the CRD ones are kept beside it.
 */
@Component
@Profile("mock")
public class MockOnyxDtrClient implements OnyxDtrClient {

    /** The directory under {@code scenarios/} that holds the DTR scenario files. */
    public static final String SCENARIOS = "dtr";

    /** The key of a DTR scenario file that lists the CRD sentinel member IDs it also answers. */
    public static final String CRD_SENTINELS = "crdSentinelMemberIds";

    /** Matches any answer at all in a step rule. */
    public static final String ANY_ANSWER = "(any)";
    /** Matches a question that has no answer in a step rule. */
    public static final String NO_ANSWER = "(none)";

    /**
     * The step asked for when a scenario states no rule for choosing one. A scenario with a single
     * next-question answer can define a step of this name. One that defines none refuses the call.
     */
    static final String DEFAULT_STEP = "next-question";
    /** The step asked for when a scenario's rule fits none of the answers. No file defines it, so the call is refused by name. */
    static final String NO_STEP_FITS = "next-question after answers this scenario does not expect";

    private final ScenarioRepository scenarios;
    private final MockExchanges exchanges;

    public MockOnyxDtrClient(ScenarioRepository scenarios, MockExchanges exchanges) {
        this.scenarios = scenarios;
        this.exchanges = exchanges;
    }

    @Override
    public VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection) {
        ScenarioSelection chosen = select(selection, body == null ? null : body.value());
        VendorExchange exchange = exchanges.answer(call, body, HttpOnyxDtrClient.OPTIONS, chosen, null);
        // The same recognition rules the live client applies, so mock cannot disagree with live about them.
        return exchange.withSignals(OnyxDtrConventions.signals(exchange));
    }

    @Override
    public VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection) {
        String sentText = body == null ? null : body.value();
        ScenarioSelection chosen = select(selection, sentText);
        JsonSafe.Parsed parsed = JsonSafe.parse(sentText);
        JsonNode sent = parsed.ok() ? QuestionnaireJson.findResponse(parsed.node()) : null;
        Map<String, List<JsonNode>> answers = sent == null ? Map.of() : QuestionnaireJson.answers(sent);
        VendorExchange exchange = exchanges.answer(call, body, HttpOnyxDtrClient.OPTIONS, chosen, step(chosen.scenario(), answers));
        exchange = returnWhatWasSent(exchange, sent, answers);
        exchange = answerInTheShapeOfTheRequest(exchange, parsed.ok() ? parsed.node() : null);
        return exchange.withSignals(OnyxDtrConventions.signals(exchange));
    }

    /**
     * The scenario that answers, by the shared rule, with one addition: a CRD sentinel member ID
     * selects the DTR scenario that names it under {@link #CRD_SENTINELS}. It is looked at where
     * the shared rule looks at a member ID, after a scenario chosen explicitly and a DTR sentinel,
     * and before a sentinel NPI, so that a member not found is not answered as a provider not
     * found, as in CRD.
     */
    private ScenarioSelection select(MockSelection selection, String requestBody) {
        if (selection == null || Texts.present(selection.scenarioId())) {
            return scenarios.select(SCENARIOS, selection, requestBody);
        }
        List<Scenario> all = scenarios.list(SCENARIOS);
        String memberId = Texts.present(selection.memberId()) ? MemberIds.noSpace(selection.memberId()) : null;
        boolean ownSentinel = memberId != null && all.stream().anyMatch(scenario -> Texts.present(scenario.sentinelMemberId())
                && MemberIds.noSpace(scenario.sentinelMemberId()).equalsIgnoreCase(memberId));
        if (memberId != null && !ownSentinel) {
            for (Scenario scenario : all) {
                for (String crd : crdSentinels(scenario)) {
                    if (MemberIds.noSpace(crd).equalsIgnoreCase(memberId)) {
                        return new ScenarioSelection(scenario, "selected by the CRD sentinel member ID " + crd
                                + ", which this scenario answers for the same case");
                    }
                }
            }
        }
        // The shared rule falls back on a sentinel in the request body only when nothing on the form selected a scenario. So does this.
        String npi = Texts.present(selection.npi()) ? selection.npi().strip() : null;
        boolean formSelects = ownSentinel || all.stream().anyMatch(scenario -> npi != null && Texts.present(scenario.sentinelNpi())
                && scenario.sentinelNpi().strip().equals(npi));
        boolean bodySelects = Texts.present(requestBody) && all.stream().anyMatch(scenario -> Texts.present(scenario.sentinelMemberId())
                && requestBody.contains("\"" + scenario.sentinelMemberId() + "\""));
        if (!formSelects && !bodySelects && Texts.present(requestBody)) {
            for (Scenario scenario : all) {
                for (String crd : crdSentinels(scenario)) {
                    if (requestBody.contains("\"" + crd + "\"")) {
                        return new ScenarioSelection(scenario, "selected by the CRD sentinel member ID " + crd
                                + " found in the request body, which this scenario answers for the same case");
                    }
                }
            }
        }
        return scenarios.select(SCENARIOS, selection, requestBody);
    }

    /** The CRD sentinel member IDs a scenario file says it also answers. Empty when it names none. */
    static List<String> crdSentinels(Scenario scenario) {
        List<String> out = new ArrayList<>();
        for (JsonNode value : JsonSafe.elements(scenario.raw(), CRD_SENTINELS)) {
            String text = JsonSafe.presentText(value);
            if (text != null) {
                out.add(text.strip());
            }
        }
        return out;
    }

    /**
     * A service that takes the QuestionnaireResponse inside a Parameters resource answers with one
     * inside a Parameters resource too, under the name its guide gives the answer. A scenario step
     * holds the response by itself, so the mock puts it in the shape the request came in, and the
     * result says so. Nothing else about the step changes.
     */
    private static VendorExchange answerInTheShapeOfTheRequest(VendorExchange exchange, JsonNode sentBody) {
        if (!exchange.ok() || !QuestionnaireJson.isType(sentBody, "Parameters") || !QuestionnaireJson.isType(exchange.json(), "QuestionnaireResponse")) {
            return exchange;
        }
        String sentUnder = null;
        for (JsonNode parameter : JsonSafe.elements(sentBody, "parameter")) {
            if (QuestionnaireJson.isType(JsonSafe.child(parameter, "resource"), "QuestionnaireResponse")) {
                sentUnder = JsonSafe.presentText(parameter, "name");
                break;
            }
        }
        String returnedUnder = OnyxDtrConventions.WRAP_DTR_BALLOT.equals(sentUnder) ? OnyxDtrConventions.RETURN_DTR_BALLOT
                : OnyxDtrConventions.RETURN_SDC;
        ObjectNode wrapped = JsonNodeFactory.instance.objectNode();
        wrapped.put("resourceType", "Parameters");
        wrapped.putArray("parameter").addObject().put("name", returnedUnder).set("resource", exchange.json().deepCopy());
        return Exchanges.fromResponse(exchange.request(), exchange.httpStatus(), exchange.responseHeaders(), JsonSafe.pretty(wrapped),
                        exchange.elapsedMillis(), true)
                .withScenario(exchange.scenarioId(), exchange.scenarioNote() + ". The response came back inside a Parameters resource, "
                        + "under " + returnedUnder + ", because the request was sent inside one");
    }

    /**
     * The step a scenario answers a next-question call with. The rule is the scenario file's own:
     * each entry of {@code nextQuestion.select} lists linkIds with the answer each must have, and
     * the first entry that fits names the step.
     */
    static String step(Scenario scenario, Map<String, List<JsonNode>> answers) {
        JsonNode rule = JsonSafe.child(scenario.raw(), "nextQuestion");
        if (!rule.isObject()) {
            return DEFAULT_STEP;
        }
        for (JsonNode entry : JsonSafe.elements(rule, "select")) {
            String step = JsonSafe.presentText(entry, "step");
            if (step != null && fits(JsonSafe.child(entry, "when"), answers)) {
                return step;
            }
        }
        return NO_STEP_FITS;
    }

    private static boolean fits(JsonNode when, Map<String, List<JsonNode>> answers) {
        for (Map.Entry<String, JsonNode> condition : JsonSafe.properties(when)) {
            String wanted = JsonSafe.text(condition.getValue());
            List<String> given = answers.getOrDefault(condition.getKey(), List.of()).stream().map(QuestionnaireJson::answerText).toList();
            boolean holds;
            if (NO_ANSWER.equals(wanted)) {
                holds = given.isEmpty();
            } else if (ANY_ANSWER.equals(wanted)) {
                holds = !given.isEmpty();
            } else {
                holds = wanted != null && given.contains(wanted);
            }
            if (!holds) {
                return false;
            }
        }
        return true;
    }

    /**
     * A scenario step holds the questions the service asks at that point and the status of the
     * response. It cannot hold the answers, because a tester can give any number or any text. A
     * real service returns the QuestionnaireResponse it was sent, with its questionnaire and
     * status brought up to date, so the mock does the same: what was sent comes back, the step
     * replaces what the step states, and an answer to a question the step no longer asks is
     * dropped. The result says so, so the answers are not taken for anything the scenario file holds.
     */
    private static VendorExchange returnWhatWasSent(VendorExchange exchange, JsonNode sent, Map<String, List<JsonNode>> answers) {
        if (sent == null || exchange.json() == null || !exchange.ok()) {
            return exchange;
        }
        JsonNode copy = exchange.json().deepCopy();
        JsonNode returned = QuestionnaireJson.findResponse(copy);
        JsonNode questionnaire = returned == null ? null : QuestionnaireJson.containedQuestionnaire(returned);
        if (!(returned instanceof ObjectNode target) || questionnaire == null) {
            return exchange;
        }
        ObjectNode merged = (ObjectNode) sent.deepCopy();
        target.properties().forEach(property -> merged.set(property.getKey(), property.getValue()));
        ArrayNode items = QuestionnaireJson.buildItems(QuestionnaireJson.items(questionnaire), answers,
                QuestionnaireJson.responseItems(sent), item -> true);
        if (items.isEmpty()) {
            merged.remove("item");
        } else {
            merged.set("item", items);
        }
        target.removeAll();
        target.setAll(merged);
        return Exchanges.fromResponse(exchange.request(), exchange.httpStatus(), exchange.responseHeaders(), JsonSafe.pretty(copy),
                        exchange.elapsedMillis(), true)
                .withScenario(exchange.scenarioId(), exchange.scenarioNote() + ". The scenario file holds the questions and the status "
                        + "of this step. The answers are the ones that were sent, returned for the questions the step still asks");
    }
}
