package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions.Progress;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.dtr.FormStateView;
import org.p32h.interop.epa.pave.model.dtr.ItemEnabled;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireKind;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.dtr.EnableWhenEvaluator.Form;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * What this tool does not evaluate in a questionnaire (DTR-07). It evaluates enableWhen and
 * nothing else. An item whose display, requiredness or answer is decided by a rule it does not
 * apply is undetermined and is never reported as a required question with no answer, and each rule
 * is named to the tester once for the questionnaire.
 */
class DtrNotAppliedTest {

    private static final String SDC = "http://hl7.org/fhir/uv/sdc/StructureDefinition/";
    private static final String EXPRESSION = "\"valueExpression\":{\"language\":\"text/cql\",\"expression\":\"RequiresDetail\"}";

    private static JsonNode json(String text) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        assertThat(parsed.ok()).as(text).isTrue();
        return parsed.node();
    }

    /** A static questionnaire: a plain required question, then the items given, each written in full. */
    private static String questionnaire(String... items) {
        return "{\"resourceType\":\"Questionnaire\",\"id\":\"rules\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/rules\","
                + "\"title\":\"Rules\",\"status\":\"active\",\"item\":[{\"linkId\":\"plain\",\"text\":\"Plain\",\"type\":\"boolean\",\"required\":true}"
                + (items.length == 0 ? "" : "," + String.join(",", items)) + "]}";
    }

    private static String item(String linkId, String extensions) {
        return "{\"linkId\":\"" + linkId + "\",\"text\":\"" + linkId + "\",\"type\":\"string\",\"required\":true,\"extension\":[" + extensions + "]}";
    }

    private static String extension(String url, String value) {
        return "{\"url\":\"" + url + "\"," + value + "}";
    }

    private static List<String> linkIds(List<QuestionRef> refs) {
        return refs.stream().map(QuestionRef::linkId).toList();
    }

    @Test
    void anItemARuleThisToolDoesNotApplyDecidesIsUndeterminedAndNeverRequiredAndUnanswered() {
        JsonNode questionnaire = json(questionnaire(
                item("by-expression", extension(SDC + "sdc-questionnaire-enableWhenExpression", EXPRESSION)),
                item("hidden", extension("http://hl7.org/fhir/StructureDefinition/questionnaire-hidden", "\"valueBoolean\":true")),
                item("shown", extension("http://hl7.org/fhir/StructureDefinition/questionnaire-hidden", "\"valueBoolean\":false")),
                item("calculated", extension(SDC + "sdc-questionnaire-calculatedExpression", EXPRESSION)),
                item("limited", extension("http://hl7.org/fhir/StructureDefinition/maxValue", "\"valueInteger\":3")),
                "{\"linkId\":\"maybe\",\"type\":\"string\",\"_required\":{\"extension\":[" + extension("http://hl7.org/fhir/StructureDefinition/cqf-expression",
                        EXPRESSION) + "]}}",
                "{\"linkId\":\"group\",\"type\":\"group\",\"extension\":[" + extension(SDC + "sdc-questionnaire-enableWhenExpression", EXPRESSION)
                        + "],\"item\":[{\"linkId\":\"inside\",\"type\":\"string\",\"required\":true}]}"));
        Form form = EnableWhenEvaluator.evaluate(questionnaire, Map.of());

        // Only what this tool can judge is required and unanswered: the plain question, the one hidden=false leaves as it was, and the
        // one whose rule only limits the answer.
        assertThat(linkIds(form.requiredUnanswered())).containsExactly("plain", "shown", "limited");
        // Everything a rule it does not apply decides is listed apart: whether it is shown, whether it is required, what its answer is,
        // and a required question in a group whose display such a rule decides. The group itself is not required, so it is not listed.
        assertThat(linkIds(form.undetermined())).containsExactly("by-expression", "hidden", "calculated", "maybe", "inside");
        assertThat(form.undetermined().get(0).note()).isEqualTo("Not judged: it is decided by enableWhenExpression, which this tool does not "
                + "apply, so whether it still needs an answer is not known.");

        // An expression that decides display makes the item undetermined, with the reason. One that decides the answer leaves it enabled.
        assertThat(form.state("item[1]").enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
        assertThat(form.state("item[1]").reason()).isEqualTo("Undetermined: whether it is shown is decided by enableWhenExpression, which "
                + "this tool does not apply. It is still shown, and it is never reported as a required question with no answer.");
        assertThat(form.state("item[2]").enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
        assertThat(form.state("item[3]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(form.state("item[4]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(form.state("item[4]").reason()).isEqualTo("Its answer is decided by calculatedExpression, which this tool does not apply, "
                + "so it is never reported as a required question with no answer.");
        assertThat(form.state("item[7].item[0]").enabled()).isEqualTo(ItemEnabled.UNDETERMINED);

        // An answer settles it: an answered item is in neither list.
        Form answered = EnableWhenEvaluator.evaluate(questionnaire, Map.of("by-expression", List.of(json("{\"valueString\":\"given\"}"))));
        assertThat(linkIds(answered.undetermined())).doesNotContain("by-expression");
    }

    @Test
    void eachRuleIsNamedOnceForTheQuestionnaireWithWhatNotApplyingItMeans() {
        String withRules = questionnaire(
                item("a", extension(SDC + "sdc-questionnaire-initialExpression", EXPRESSION)),
                item("b", extension(SDC + "sdc-questionnaire-initialExpression", EXPRESSION)),
                item("c", extension("http://hl7.org/fhir/StructureDefinition/questionnaire-unitOption",
                        "\"valueCoding\":{\"system\":\"http://unitsofmeasure.org\",\"code\":\"wk\"}")));
        JsonNode node = json(withRules.replace("\"title\":\"Rules\",", "\"title\":\"Rules\",\"extension\":[" + extension(
                "http://hl7.org/fhir/StructureDefinition/cqf-library", "\"valueCanonical\":\"https://example.org/p32h-placeholder/Library/rules\"") + "],"));
        assertThat(DtrNotApplied.describe(node)).containsExactly(
                "The questionnaire carries cqf-library, which names a CQL library whose expressions the initialExpression and similar "
                        + "extensions of the items call to pre-populate answers. This tool runs no CQL, so no answer is pre-populated from it. "
                        + "A question that carries no such extension of its own is judged on the answers given, so a required one with no "
                        + "answer is still reported as unanswered. This tool does not apply it.",
                "2 items carry initialExpression, which fills in the item's first answer by an expression. This tool does not apply it, so "
                        + "whether those items still need an answer is not known: they are marked undetermined, and never reported as a "
                        + "required question with no answer. An item whose enableWhen reads their answer, while there is none, is marked "
                        + "undetermined too.",
                "1 item carries unitOption, which names a unit a quantity answer may use. This tool does not apply it, so an answer is not "
                        + "checked against it.");
        assertThat(DtrNotApplied.describe(json(questionnaire()))).isEmpty();
        // One item takes the singular verb.
        assertThat(DtrNotApplied.describe(json(questionnaire(item("a", extension(SDC + "sdc-questionnaire-calculatedExpression", EXPRESSION))))))
                .containsExactly("1 item carries calculatedExpression, which works out the item's answer by an expression. This tool does not "
                        + "apply it, so whether that item still needs an answer is not known: it is marked undetermined, and never reported as "
                        + "a required question with no answer. An item whose enableWhen reads its answer, while there is none, is marked "
                        + "undetermined too.");
    }

    @Test
    void anItemWhoseEnableWhenReadsAnAnswerARuleThisToolDoesNotApplyFillsInIsUndeterminedWhileThatAnswerIsMissing() {
        JsonNode questionnaire = json(questionnaire(
                item("filled", extension(SDC + "sdc-questionnaire-initialExpression", EXPRESSION)),
                "{\"linkId\":\"follow\",\"text\":\"follow\",\"type\":\"string\",\"required\":true,\"enableWhen\":[{\"question\":\"filled\","
                        + "\"operator\":\"exists\",\"answerBoolean\":false}]}",
                "{\"linkId\":\"either\",\"text\":\"either\",\"type\":\"string\",\"required\":true,\"enableBehavior\":\"any\",\"enableWhen\":["
                        + "{\"question\":\"filled\",\"operator\":\"exists\",\"answerBoolean\":true},{\"question\":\"plain\",\"operator\":\"=\","
                        + "\"answerBoolean\":true}]}"));
        Form form = EnableWhenEvaluator.evaluate(questionnaire, Map.of());

        // Whether follow is enabled rests on an answer initialExpression would fill in, so it is not judged. Without the rule the
        // condition would hold on an empty answer and follow would be called required and unanswered.
        assertThat(linkIds(form.requiredUnanswered())).containsExactly("plain");
        assertThat(linkIds(form.undetermined())).containsExactly("filled", "follow", "either");
        EnableWhenEvaluator.ItemState follow = form.state("item[2]");
        assertThat(follow.enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
        assertThat(follow.conditions()).singleElement().satisfies(condition -> assertThat(condition.explanation()).isEqualTo(
                "Not evaluated: the question it looks at, filled, has no answer here, and its answer is filled in by initialExpression, "
                        + "which this tool does not apply."));
        assertThat(follow.reason()).isEqualTo("Undetermined: whether it is shown is decided by initialExpression on filled, which this tool "
                + "does not apply. It is still shown, and it is never reported as a required question with no answer.");
        assertThat(form.undetermined().get(1).note()).isEqualTo("Not judged: it is decided by initialExpression on filled, which this tool "
                + "does not apply, so whether it still needs an answer is not known.");

        // Another condition that settles it settles it: with enableBehavior any and plain answered true, either is enabled and judged.
        Form plainTrue = EnableWhenEvaluator.evaluate(questionnaire, Map.of("plain", List.of(json("{\"valueBoolean\":true}"))));
        assertThat(plainTrue.state("item[3]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(linkIds(plainTrue.requiredUnanswered())).containsExactly("either");
        assertThat(linkIds(plainTrue.undetermined())).containsExactly("filled", "follow");

        // Once the answer is there, the condition is evaluated on it as usual.
        Form answered = EnableWhenEvaluator.evaluate(questionnaire, Map.of("filled", List.of(json("{\"valueString\":\"given\"}"))));
        assertThat(answered.state("item[2]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        assertThat(linkIds(answered.undetermined())).isEmpty();
    }

    @Test
    void aHiddenItemAndTheQuestionsOfAHiddenGroupAreNotAmongWhatIsAsked() {
        String hide = extension("http://hl7.org/fhir/StructureDefinition/questionnaire-hidden", "\"valueBoolean\":true");
        JsonNode questionnaire = json(questionnaire(item("hidden", hide),
                item("shown", extension("http://hl7.org/fhir/StructureDefinition/questionnaire-hidden", "\"valueBoolean\":false")),
                "{\"linkId\":\"group\",\"type\":\"group\",\"extension\":[" + hide + "],\"item\":[{\"linkId\":\"inside\",\"type\":\"string\"}]}"));
        Form form = EnableWhenEvaluator.evaluate(questionnaire, Map.of());
        assertThat(linkIds(form.asked())).containsExactly("plain", "shown");
        // They are still not judged: hidden is not applied, so whether a hidden required item needs an answer is not known.
        assertThat(linkIds(form.undetermined())).containsExactly("hidden");
    }

    @Test
    void theServiceJudgesAResponseItCompletedAndThisToolDoesNotCompleteOneItCannotJudge() {
        JsonNode questionnaire = json(questionnaire(item("by-expression", extension(SDC + "sdc-questionnaire-enableWhenExpression", EXPRESSION))));
        Form form = EnableWhenEvaluator.evaluate(questionnaire, Map.of("plain", List.of(json("{\"valueBoolean\":true}"))));
        FormStateView state = DtrResponseMapper.formState(form, json("{\"resourceType\":\"QuestionnaireResponse\",\"status\":\"completed\"}"),
                null, true, List.of(), null);
        assertThat(state.requiredUnanswered()).isEmpty();
        // The service completed the response: its judgement of the item this tool could not judge stands.
        assertThat(DtrOutcomeRules.forResponse(QuestionnaireKind.ADAPTIVE, Progress.COMPLETED, state)).isEqualTo(DtrOutcome.RESPONSE_COMPLETED);
        // This tool would complete it: it cannot tell, so it does not, and it does not call it a required question with no answer either.
        assertThat(DtrOutcomeRules.forResponse(QuestionnaireKind.STATIC, Progress.COMPLETED, state)).isEqualTo(DtrOutcome.RESPONSE_NOT_COMPLETE);
    }

    @Test
    void aStaticResponseHeldBackOnlyByAnItemThisToolCannotJudgeSaysSoAndAsksForThatItem() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", new InMemoryLedger());
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()),
                OnyxProperties.defaults(), beans.getBeanProvider(RunLedger.class), DtrFixtures.CLOCK);
        ScenarioRepository scenarios = new ScenarioRepository(DtrFixtures.APP);
        DtrService service = new DtrService(support.epa(), FakeGateway.unconfigured(),
                new MockOnyxDtrClient(scenarios, new MockExchanges(DtrFixtures.APP)), new FakeCodeList(), support);
        String hidden = questionnaire(item("by-expression", extension(SDC + "sdc-questionnaire-enableWhenExpression", EXPRESSION)));

        RunEnvelope<DtrDetail> result = service.runResponse(DtrResponseRequest.builder().member(DtrFixtures.member("S123456789"))
                .provider(DtrFixtures.provider()).procedureCodes(List.of(CodeInput.of("95810"))).questionnaire(hidden)
                .answers(List.of(DtrAnswerInput.of("plain", "true"))).build(), RunOrigin.manual());

        assertThat(result.verdict().outcomeCode()).isEqualTo("RESPONSE_NOT_COMPLETE");
        assertThat(result.detail().form().requiredUnanswered()).isEmpty();
        assertThat(linkIds(result.detail().form().undetermined())).containsExactly("by-expression");
        // The response was not marked completed, so it is not handed to PAS.
        assertThat(result.detail().questionnaireResponses().get(0).status().code()).isEqualTo("in-progress");
        assertThat(result.nextStep().action()).isEqualTo(NextAction.SUBMIT_RESPONSE);
        assertThat(result.nextStep().carry()).doesNotContainKey("questionnaireResponse");
        assertThat(result.verdict().supporting()).contains("Not judged, because a rule this tool does not apply decides whether they still "
                + "need an answer: by-expression \"by-expression\".");
        // The rules not applied are named once, with the questionnaire, and not again in the verdict.
        assertThat(result.verdict().supporting()).noneMatch(line -> line.contains("evaluates enableWhen and nothing else"));
        assertThat(result.detail().questionnaires().get(0).notApplied()).singleElement().asString().startsWith("1 item carries "
                + "enableWhenExpression, which decides by an expression whether the item is enabled.");
    }
}
