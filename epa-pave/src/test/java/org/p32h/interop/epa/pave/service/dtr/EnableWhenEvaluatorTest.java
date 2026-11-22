package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.dtr.ConditionState;
import org.p32h.interop.epa.pave.model.dtr.ItemEnabled;
import org.p32h.interop.epa.pave.model.dtr.QuestionRef;
import org.p32h.interop.epa.pave.service.dtr.EnableWhenEvaluator.Condition;
import org.p32h.interop.epa.pave.service.dtr.EnableWhenEvaluator.Form;
import tools.jackson.databind.JsonNode;

/**
 * The enableWhen evaluation as a pure function: a questionnaire and a set of answers in, the state
 * of every item out. Which items a static form shows is the behaviour under test, so each operator
 * and each way of combining conditions is pinned here.
 */
class EnableWhenEvaluatorTest {

    private static JsonNode json(String text) {
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        assertThat(parsed.ok()).as(text).isTrue();
        return parsed.node();
    }

    /** A question q1, and a required question q2 that the given conditions enable. */
    private static JsonNode guarded(String behavior, String... conditions) {
        return json("{\"resourceType\":\"Questionnaire\",\"item\":[{\"linkId\":\"q1\",\"text\":\"First\",\"type\":\"string\",\"repeats\":true},"
                + "{\"linkId\":\"q2\",\"text\":\"Second\",\"type\":\"string\",\"required\":true,"
                + (behavior == null ? "" : "\"enableBehavior\":\"" + behavior + "\",")
                + "\"enableWhen\":[" + String.join(",", conditions) + "]}]}");
    }

    private static String on(String question, String operator, String answer) {
        return "{\"question\":\"" + question + "\",\"operator\":\"" + operator + "\"," + answer + "}";
    }

    /** Answers by linkId: each pair is a linkId and one FHIR answer object. A linkId may be given more than once. */
    private static Map<String, List<JsonNode>> answers(String... linkIdThenAnswer) {
        Map<String, List<JsonNode>> out = new LinkedHashMap<>();
        for (int i = 0; i < linkIdThenAnswer.length; i += 2) {
            out.computeIfAbsent(linkIdThenAnswer[i], key -> new ArrayList<>()).add(json(linkIdThenAnswer[i + 1]));
        }
        return out;
    }

    /** The state of the one condition on q2, given one answer to q1 or none. */
    private static Condition condition(String operator, String expected, String answerToFirst) {
        Form form = EnableWhenEvaluator.evaluate(guarded(null, on("q1", operator, expected)),
                answerToFirst == null ? Map.of() : answers("q1", answerToFirst));
        return form.state("item[1]").conditions().get(0);
    }

    private static ConditionState state(String operator, String expected, String answerToFirst) {
        return condition(operator, expected, answerToFirst).state();
    }

    // ---- every operator ----

    @Test
    void existsLooksOnlyAtWhetherThereIsAnAnswer() {
        assertThat(state("exists", "\"answerBoolean\":true", "{\"valueString\":\"anything\"}")).isEqualTo(ConditionState.TRUE);
        assertThat(state("exists", "\"answerBoolean\":true", null)).isEqualTo(ConditionState.FALSE);
        assertThat(state("exists", "\"answerBoolean\":false", null)).isEqualTo(ConditionState.TRUE);
        assertThat(state("exists", "\"answerBoolean\":false", "{\"valueString\":\"anything\"}")).isEqualTo(ConditionState.FALSE);
        assertThat(condition("exists", "\"answerBoolean\":true", null).explanation())
                .isEqualTo("The condition does not hold: the question it looks at has no answer.");
    }

    @Test
    void equalsHoldsForEachAnswerTypeTheEvaluatorHandles() {
        String[][] cases = {
                {"\"answerBoolean\":true", "{\"valueBoolean\":true}", "{\"valueBoolean\":false}"},
                {"\"answerInteger\":6", "{\"valueInteger\":6}", "{\"valueInteger\":7}"},
                {"\"answerDecimal\":1.50", "{\"valueDecimal\":1.5}", "{\"valueDecimal\":1.51}"},
                {"\"answerInteger\":6", "{\"valueDecimal\":6.0}", "{\"valueDecimal\":6.1}"},
                {"\"answerDate\":\"2026-01-10\"", "{\"valueDate\":\"2026-01-10\"}", "{\"valueDate\":\"2026-01-11\"}"},
                {"\"answerDateTime\":\"2026-01-10T09:00:00-05:00\"", "{\"valueDateTime\":\"2026-01-10T14:00:00Z\"}",
                        "{\"valueDateTime\":\"2026-01-10T09:00:00Z\"}"},
                {"\"answerTime\":\"09:30:00\"", "{\"valueTime\":\"09:30:00\"}", "{\"valueTime\":\"09:31:00\"}"},
                {"\"answerString\":\"Yes\"", "{\"valueString\":\"Yes\"}", "{\"valueString\":\"yes\"}"},
                {"\"answerCoding\":{\"system\":\"s\",\"code\":\"a\"}", "{\"valueCoding\":{\"system\":\"s\",\"code\":\"a\",\"display\":\"A\"}}",
                        "{\"valueCoding\":{\"system\":\"s\",\"code\":\"b\"}}"},
                {"\"answerReference\":{\"reference\":\"Condition/c1\"}", "{\"valueReference\":{\"reference\":\"Condition/c1\"}}",
                        "{\"valueReference\":{\"reference\":\"Condition/c2\"}}"}};
        for (String[] c : cases) {
            assertThat(state("=", c[0], c[1])).as(c[0] + " against " + c[1]).isEqualTo(ConditionState.TRUE);
            assertThat(state("=", c[0], c[2])).as(c[0] + " against " + c[2]).isEqualTo(ConditionState.FALSE);
            // And the other way round for not-equals.
            assertThat(state("!=", c[0], c[1])).as(c[0] + " != " + c[1]).isEqualTo(ConditionState.FALSE);
            assertThat(state("!=", c[0], c[2])).as(c[0] + " != " + c[2]).isEqualTo(ConditionState.TRUE);
        }
        // With no answer, nothing equals the value.
        assertThat(state("=", "\"answerInteger\":6", null)).isEqualTo(ConditionState.FALSE);
    }

    @Test
    void notEqualsHoldsWhenTheQuestionHasNoAnswerBecauseNoAnswerEqualsTheValue() {
        // The FHIR definition read as written. It shows a question that could have stayed hidden,
        // where the other reading would hide one that may be required.
        Condition unanswered = condition("!=", "\"answerString\":\"x\"", null);
        assertThat(unanswered.state()).isEqualTo(ConditionState.TRUE);
        assertThat(unanswered.explanation()).isEqualTo("The condition holds: the question it looks at has no answer, so no answer equals the value.");
    }

    @Test
    void theOrderingOperatorsCompareNumbersDatesAndTimes() {
        Object[][] cases = {
                {">", "\"answerInteger\":6", "{\"valueInteger\":7}", "{\"valueInteger\":6}"},
                {"<", "\"answerInteger\":6", "{\"valueInteger\":5}", "{\"valueInteger\":6}"},
                {">=", "\"answerInteger\":6", "{\"valueInteger\":6}", "{\"valueInteger\":5}"},
                {"<=", "\"answerInteger\":6", "{\"valueInteger\":6}", "{\"valueInteger\":7}"},
                {">", "\"answerDecimal\":0.5", "{\"valueDecimal\":0.51}", "{\"valueDecimal\":0.5}"},
                // 10 is greater than 9 as a number. Compared as text it would not be.
                {">", "\"answerInteger\":9", "{\"valueInteger\":10}", "{\"valueInteger\":8}"},
                {"<", "\"answerDate\":\"2026-02-01\"", "{\"valueDate\":\"2026-01-31\"}", "{\"valueDate\":\"2026-02-01\"}"},
                {">=", "\"answerDate\":\"2026-02\"", "{\"valueDate\":\"2026-02\"}", "{\"valueDate\":\"2026-01\"}"},
                {">", "\"answerDateTime\":\"2026-01-10T09:00:00-05:00\"", "{\"valueDateTime\":\"2026-01-10T14:00:01Z\"}",
                        "{\"valueDateTime\":\"2026-01-10T14:00:00Z\"}"},
                {"<=", "\"answerTime\":\"09:30:00\"", "{\"valueTime\":\"09:30:00\"}", "{\"valueTime\":\"09:30:01\"}"}};
        for (Object[] c : cases) {
            assertThat(state((String) c[0], (String) c[1], (String) c[2])).as(c[0] + " " + c[1] + " against " + c[2]).isEqualTo(ConditionState.TRUE);
            assertThat(state((String) c[0], (String) c[1], (String) c[3])).as(c[0] + " " + c[1] + " against " + c[3]).isEqualTo(ConditionState.FALSE);
        }
    }

    // ---- a repeating item ----

    @Test
    void withSeveralAnswersAConditionHoldsWhenAnyOneOfThemSatisfiesIt() {
        JsonNode questionnaire = guarded(null, on("q1", "=", "\"answerString\":\"b\""));
        Form anyMatches = EnableWhenEvaluator.evaluate(questionnaire, answers("q1", "{\"valueString\":\"a\"}", "q1", "{\"valueString\":\"b\"}"));
        assertThat(anyMatches.state("item[0]").given()).hasSize(2);
        assertThat(anyMatches.state("item[1]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        Form noneMatches = EnableWhenEvaluator.evaluate(questionnaire, answers("q1", "{\"valueString\":\"a\"}", "q1", "{\"valueString\":\"c\"}"));
        assertThat(noneMatches.state("item[1]").enabled()).isEqualTo(ItemEnabled.DISABLED);

        // Not-equals is the reverse of "any answer equals": one equal answer among several is enough to fail it.
        JsonNode different = guarded(null, on("q1", "!=", "\"answerString\":\"b\""));
        assertThat(EnableWhenEvaluator.evaluate(different, answers("q1", "{\"valueString\":\"a\"}", "q1", "{\"valueString\":\"b\"}"))
                .state("item[1]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        assertThat(EnableWhenEvaluator.evaluate(different, answers("q1", "{\"valueString\":\"a\"}", "q1", "{\"valueString\":\"c\"}"))
                .state("item[1]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        // An ordering operator holds when any one answer satisfies it.
        JsonNode more = guarded(null, on("q1", ">", "\"answerInteger\":5"));
        assertThat(EnableWhenEvaluator.evaluate(more, answers("q1", "{\"valueInteger\":2}", "q1", "{\"valueInteger\":9}"))
                .state("item[1]").enabled()).isEqualTo(ItemEnabled.ENABLED);
    }

    // ---- the words under a question ----

    @Test
    void oneConditionIsSaidByTheQuestionItReadsAndTheAnswerThatAsksTheItem() {
        // Plan 3.2 step 2 (wbm3-9): plain words, from the questionnaire only, never from an answer the tester gave.
        JsonNode yes = guarded(null, on("q1", "=", "\"answerBoolean\":true"));
        assertThat(EnableWhenEvaluator.evaluate(yes, Map.of()).state("item[1]").reason())
                .isEqualTo("Not asked until the answer to \"First\" is Yes.");
        assertThat(EnableWhenEvaluator.evaluate(yes, answers("q1", "{\"valueBoolean\":true}")).state("item[1]").reason())
                .isEqualTo("Asked because the answer to \"First\" is Yes.");
        JsonNode coded = guarded(null, on("q1", "=", "\"answerCoding\":{\"code\":\"y\",\"display\":\"Family history\"}"));
        assertThat(EnableWhenEvaluator.evaluate(coded, Map.of()).state("item[1]").reason())
                .isEqualTo("Not asked until the answer to \"First\" is Family history.");
        JsonNode count = guarded(null, on("q1", ">", "\"answerInteger\":2"));
        assertThat(EnableWhenEvaluator.evaluate(count, answers("q1", "{\"valueInteger\":5}")).state("item[1]").reason())
                .isEqualTo("Asked because the answer to \"First\" is more than 2.");
        JsonNode exists = guarded(null, on("q1", "exists", "\"answerBoolean\":true"));
        assertThat(EnableWhenEvaluator.evaluate(exists, Map.of()).state("item[1]").reason())
                .isEqualTo("Not asked until \"First\" has an answer.");
        // The answer the tester typed is never quoted.
        assertThat(EnableWhenEvaluator.evaluate(count, answers("q1", "{\"valueInteger\":5}")).state("item[1]").reason()).doesNotContain("5");
    }

    // ---- enableBehavior ----

    @Test
    void allNeedsEveryConditionAndAnyNeedsOne() {
        String first = on("q1", "exists", "\"answerBoolean\":true");
        String second = on("q1", "=", "\"answerString\":\"yes\"");
        Map<String, List<JsonNode>> onlyFirstHolds = answers("q1", "{\"valueString\":\"no\"}");
        Map<String, List<JsonNode>> bothHold = answers("q1", "{\"valueString\":\"yes\"}");

        Form all = EnableWhenEvaluator.evaluate(guarded("all", first, second), onlyFirstHolds);
        assertThat(all.state("item[1]").conditions()).extracting(Condition::state).containsExactly(ConditionState.TRUE, ConditionState.FALSE);
        assertThat(all.state("item[1]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        assertThat(all.state("item[1]").reason()).isEqualTo("Not asked until the answers above meet its conditions.");
        assertThat(EnableWhenEvaluator.evaluate(guarded("all", first, second), bothHold).state("item[1]").enabled()).isEqualTo(ItemEnabled.ENABLED);

        Form any = EnableWhenEvaluator.evaluate(guarded("any", first, second), onlyFirstHolds);
        assertThat(any.state("item[1]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(any.state("item[1]").reason()).isEqualTo("Asked because at least one condition on the answers above holds.");
        Form anyOfNone = EnableWhenEvaluator.evaluate(guarded("any", first, second), Map.of());
        assertThat(anyOfNone.state("item[1]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        assertThat(anyOfNone.state("item[1]").reason()).isEqualTo("Not asked until the answers above meet its conditions.");
    }

    @Test
    void severalConditionsWithNoBehaviourStatedAreCombinedOnlyWhenTheyAgree() {
        String first = on("q1", "exists", "\"answerBoolean\":true");
        String second = on("q1", "=", "\"answerString\":\"yes\"");
        // Both hold: enabled whichever way they were meant to combine. Neither holds: disabled either way.
        assertThat(EnableWhenEvaluator.evaluate(guarded(null, first, second), answers("q1", "{\"valueString\":\"yes\"}"))
                .state("item[1]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(EnableWhenEvaluator.evaluate(guarded(null, first, second), Map.of()).state("item[1]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        // They disagree, and nothing says how to combine them. The tool does not pick.
        Form disagree = EnableWhenEvaluator.evaluate(guarded(null, first, second), answers("q1", "{\"valueString\":\"no\"}"));
        assertThat(disagree.state("item[1]").enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
        assertThat(disagree.state("item[1]").reason()).contains("states no enableBehavior").contains("It is still shown");
        // A behaviour that is neither all nor any is no behaviour.
        assertThat(EnableWhenEvaluator.evaluate(guarded("most", first, second), answers("q1", "{\"valueString\":\"no\"}"))
                .state("item[1]").enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
    }

    // ---- a condition on an item that is itself disabled ----

    @Test
    void aQuestionThatIsItselfDisabledIsTreatedAsHavingNoAnswer() {
        JsonNode questionnaire = json("{\"resourceType\":\"Questionnaire\",\"item\":["
                + "{\"linkId\":\"q1\",\"type\":\"boolean\"},"
                + "{\"linkId\":\"q2\",\"type\":\"string\",\"enableWhen\":[" + on("q1", "=", "\"answerBoolean\":true") + "]},"
                + "{\"linkId\":\"q3\",\"type\":\"string\",\"required\":true,\"enableWhen\":[" + on("q2", "exists", "\"answerBoolean\":true") + "]},"
                + "{\"linkId\":\"q4\",\"type\":\"string\",\"required\":true,\"enableWhen\":[" + on("q2", "exists", "\"answerBoolean\":false") + "]}]}");
        // q2 was answered while it was enabled, and then q1 was changed. Its answer is stale.
        Form form = EnableWhenEvaluator.evaluate(questionnaire, answers("q1", "{\"valueBoolean\":false}", "q2", "{\"valueString\":\"stale\"}"));

        assertThat(form.state("item[1]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        Condition onDisabled = form.state("item[2]").conditions().get(0);
        assertThat(onDisabled.state()).isEqualTo(ConditionState.FALSE);
        assertThat(onDisabled.explanation()).isEqualTo("The condition does not hold: the question it looks at has no answer. "
                + "The question it looks at is itself disabled, so it is treated as having no answer.");
        assertThat(form.state("item[2]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        // And "has no answer" holds for the same reason.
        assertThat(form.state("item[3]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        // The stale answer is named as left out. It is not counted as an answer.
        assertThat(form.answersLeftOut()).extracting(QuestionRef::linkId).containsExactly("q2");
        assertThat(form.answered()).extracting(s -> s.item().linkId()).containsExactly("q1");
        assertThat(form.requiredUnanswered()).extracting(QuestionRef::linkId).containsExactly("q4");

        // With q1 answered true the same stale answer counts again.
        Form reenabled = EnableWhenEvaluator.evaluate(questionnaire, answers("q1", "{\"valueBoolean\":true}", "q2", "{\"valueString\":\"stale\"}"));
        assertThat(reenabled.state("item[2]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(reenabled.state("item[3]").enabled()).isEqualTo(ItemEnabled.DISABLED);
    }

    // ---- nested groups ----

    @Test
    void anItemInADisabledGroupIsDisabledAndIsNotAskedForHoweverDeepItSits() {
        JsonNode questionnaire = json("{\"resourceType\":\"Questionnaire\",\"item\":["
                + "{\"linkId\":\"q1\",\"type\":\"boolean\",\"required\":true},"
                + "{\"linkId\":\"g1\",\"type\":\"group\",\"enableWhen\":[" + on("q1", "=", "\"answerBoolean\":true") + "],\"item\":["
                + "{\"linkId\":\"q2\",\"type\":\"string\",\"required\":true},"
                + "{\"linkId\":\"g2\",\"type\":\"group\",\"item\":["
                + "{\"linkId\":\"q3\",\"type\":\"string\",\"required\":true},"
                + "{\"linkId\":\"q4\",\"type\":\"string\",\"required\":true,\"enableWhen\":[" + on("q3", "=", "\"answerString\":\"more\"") + "]}]}]},"
                + "{\"linkId\":\"note\",\"type\":\"display\",\"text\":\"Thank you\"}]}");

        Form closed = EnableWhenEvaluator.evaluate(questionnaire, answers("q1", "{\"valueBoolean\":false}"));
        assertThat(closed.state("item[1]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        assertThat(closed.state("item[1].item[0]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        assertThat(closed.state("item[1].item[0]").reason()).isEqualTo("Disabled: the group it sits in is disabled.");
        assertThat(closed.state("item[1].item[1].item[0]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        assertThat(closed.requiredUnanswered()).isEmpty();
        assertThat(closed.asked()).isEmpty();
        // Groups and display text are items, and are not questions.
        assertThat(closed.questions()).extracting(s -> s.item().linkId()).containsExactly("q1", "q2", "q3", "q4");
        assertThat(closed.all()).hasSize(7);

        Form open = EnableWhenEvaluator.evaluate(questionnaire, answers("q1", "{\"valueBoolean\":true}", "q3", "{\"valueString\":\"more\"}"));
        assertThat(open.state("item[1].item[1].item[1]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(open.requiredUnanswered()).extracting(QuestionRef::linkId).containsExactly("q2", "q4");
        assertThat(open.asked()).extracting(QuestionRef::linkId).containsExactly("q2", "q4");
        assertThat(open.shownQuestions()).hasSize(4);
    }

    @Test
    void aRequiredGroupNeedsAnAnswerSomewhereBeneathIt() {
        JsonNode questionnaire = json("{\"resourceType\":\"Questionnaire\",\"item\":[{\"linkId\":\"g1\",\"text\":\"Group\",\"type\":\"group\","
                + "\"required\":true,\"item\":[{\"linkId\":\"q1\",\"type\":\"string\"},{\"linkId\":\"q2\",\"type\":\"string\"}]}]}");
        Form empty = EnableWhenEvaluator.evaluate(questionnaire, Map.of());
        assertThat(empty.requiredUnanswered()).singleElement().satisfies(ref -> {
            assertThat(ref.linkId()).isEqualTo("g1");
            assertThat(ref.note()).isEqualTo("A required group with no answer beneath it.");
        });
        assertThat(EnableWhenEvaluator.evaluate(questionnaire, answers("q2", "{\"valueString\":\"x\"}")).requiredUnanswered()).isEmpty();
    }

    // ---- a condition that cannot be evaluated ----

    @Test
    void aConditionTheEvaluatorCannotEvaluateIsReportedAsSuchWithTheReasonAndIsNeitherTrueNorFalse() {
        Object[][] cases = {
                // An answer type it does not handle.
                {on("q1", "=", "\"answerQuantity\":{\"value\":5,\"unit\":\"mg\"}"), "{\"valueQuantity\":{\"value\":5,\"unit\":\"mg\"}}",
                        "this tool does not compare quantities"},
                {on("q1", "=", "\"answerAttachment\":{\"url\":\"u\"}"), "{\"valueAttachment\":{\"url\":\"u\"}}", "is not one this tool handles"},
                // The answer is of another type than the condition compares with.
                {on("q1", "=", "\"answerCoding\":{\"code\":\"a\"}"), "{\"valueString\":\"a\"}",
                        "the answer given is a valueString and the condition compares with an answerCoding"},
                {on("q1", "=", "\"answerDate\":\"2026-01-10\""), "{\"valueDateTime\":\"2026-01-10T09:00:00Z\"}", "the answer given is a valueDateTime"},
                // An operator that has no meaning for the type.
                {on("q1", ">", "\"answerBoolean\":true"), "{\"valueBoolean\":true}", "true and false have no order"},
                {on("q1", "<", "\"answerString\":\"m\""), "{\"valueString\":\"a\"}", "does not put text in order"},
                {on("q1", ">=", "\"answerCoding\":{\"code\":\"a\"}"), "{\"valueCoding\":{\"code\":\"a\"}}", "codings have no order"},
                // Two values that cannot be put side by side.
                {on("q1", "=", "\"answerDate\":\"2026-01\""), "{\"valueDate\":\"2026-01-10\"}", "not written to the same precision"},
                {on("q1", ">", "\"answerDateTime\":\"2026-01-10T09:00:00\""), "{\"valueDateTime\":\"2026-01-10T09:00:00Z\"}", "state no time zone"},
                {on("q1", "=", "\"answerTime\":\"nine\""), "{\"valueTime\":\"09:00:00\"}", "not written as one"},
                // A condition that is not complete.
                {on("q1", "~", "\"answerString\":\"a\""), "{\"valueString\":\"a\"}", "its operator is not one FHIR defines"},
                {"{\"question\":\"q1\",\"answerString\":\"a\"}", "{\"valueString\":\"a\"}", "states no operator"},
                {"{\"operator\":\"=\",\"answerString\":\"a\"}", "{\"valueString\":\"a\"}", "names no question"},
                {on("q1", "=", "\"other\":1"), "{\"valueString\":\"a\"}", "states no value to compare with"},
                {on("q1", "exists", "\"answerString\":\"true\""), "{\"valueString\":\"a\"}", "needs true or false"},
                {on("missing", "=", "\"answerString\":\"a\""), "{\"valueString\":\"a\"}", "no item with the linkId it names"}};
        for (Object[] c : cases) {
            Form form = EnableWhenEvaluator.evaluate(guarded(null, (String) c[0]), answers("q1", (String) c[1]));
            Condition condition = form.state("item[1]").conditions().get(0);
            assertThat(condition.state()).as((String) c[0]).isEqualTo(ConditionState.NOT_EVALUATED);
            assertThat(condition.explanation()).as((String) c[0]).startsWith("Not evaluated: ").contains((String) c[2]).endsWith(".");
            // The item it decides is neither shown as enabled nor hidden as disabled.
            assertThat(form.state("item[1]").enabled()).as((String) c[0]).isEqualTo(ItemEnabled.UNDETERMINED);
            assertThat(form.state("item[1]").reason()).isEqualTo("Undetermined: a condition that decides it could not be evaluated. It is still shown.");
            // It is still shown, and being required and unanswered it still stops the response from being complete.
            assertThat(form.state("item[1]").shown()).isTrue();
            assertThat(form.requiredUnanswered()).as((String) c[0]).singleElement().satisfies(ref -> {
                assertThat(ref.linkId()).isEqualTo("q2");
                assertThat(ref.note()).contains("could not be worked out");
            });
        }
    }

    @Test
    void aConditionThatCannotBeEvaluatedDoesNotDecideWhenAnotherConditionAlreadyDoes() {
        String unknown = on("q1", "=", "\"answerQuantity\":{\"value\":5}");
        String holds = on("q1", "exists", "\"answerBoolean\":true");
        String fails = on("q1", "exists", "\"answerBoolean\":false");
        Map<String, List<JsonNode>> answered = answers("q1", "{\"valueQuantity\":{\"value\":5}}");
        // Any: one true condition is enough whatever the other would have been.
        assertThat(EnableWhenEvaluator.evaluate(guarded("any", unknown, holds), answered).state("item[1]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(EnableWhenEvaluator.evaluate(guarded("any", unknown, fails), answered).state("item[1]").enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
        // All: one false condition is enough whatever the other would have been.
        assertThat(EnableWhenEvaluator.evaluate(guarded("all", unknown, fails), answered).state("item[1]").enabled()).isEqualTo(ItemEnabled.DISABLED);
        assertThat(EnableWhenEvaluator.evaluate(guarded("all", unknown, holds), answered).state("item[1]").enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
    }

    @Test
    void codingsAreComparedBySystemAndCodeAndByCodeAloneWhenOneSideStatesNoSystem() {
        assertThat(state("=", "\"answerCoding\":{\"system\":\"s1\",\"code\":\"a\"}", "{\"valueCoding\":{\"system\":\"s2\",\"code\":\"a\"}}"))
                .isEqualTo(ConditionState.FALSE);
        Condition codeOnly = condition("=", "\"answerCoding\":{\"code\":\"a\"}", "{\"valueCoding\":{\"system\":\"s2\",\"code\":\"a\"}}");
        assertThat(codeOnly.state()).isEqualTo(ConditionState.TRUE);
        assertThat(codeOnly.explanation()).isEqualTo("The condition holds. One side states no coding system, so only the codes were compared.");
        // The display is never compared.
        assertThat(state("=", "\"answerCoding\":{\"system\":\"s\",\"code\":\"a\",\"display\":\"One\"}",
                "{\"valueCoding\":{\"system\":\"s\",\"code\":\"a\",\"display\":\"Another\"}}")).isEqualTo(ConditionState.TRUE);
        assertThat(state("=", "\"answerCoding\":{\"display\":\"no code\"}", "{\"valueCoding\":{\"code\":\"a\"}}")).isEqualTo(ConditionState.NOT_EVALUATED);
    }

    @Test
    void conditionsThatDependOnEachOtherInACircleAreReportedAndDoNotLoop() {
        JsonNode circle = json("{\"resourceType\":\"Questionnaire\",\"item\":["
                + "{\"linkId\":\"q1\",\"type\":\"string\",\"enableWhen\":[" + on("q2", "=", "\"answerString\":\"x\"") + "]},"
                + "{\"linkId\":\"q2\",\"type\":\"string\",\"enableWhen\":[" + on("q1", "=", "\"answerString\":\"y\"") + "]},"
                + "{\"linkId\":\"q3\",\"type\":\"string\",\"enableWhen\":[" + on("q3", "exists", "\"answerBoolean\":true") + "]}]}");
        Form form = EnableWhenEvaluator.evaluate(circle, answers("q1", "{\"valueString\":\"y\"}", "q2", "{\"valueString\":\"x\"}"));
        assertThat(form.all()).hasSize(3);
        List<Condition> conditions = form.all().stream().flatMap(state -> state.conditions().stream()).toList();
        assertThat(conditions).anySatisfy(condition -> {
            assertThat(condition.state()).isEqualTo(ConditionState.NOT_EVALUATED);
            assertThat(condition.explanation()).contains("depends on its own item");
        });
        // An item that looks at its own answer cannot decide itself either.
        assertThat(form.state("item[2]").enabled()).isEqualTo(ItemEnabled.UNDETERMINED);
        // Nothing here is called disabled on the strength of a condition that was not evaluated.
        assertThat(form.all()).noneMatch(state -> state.enabled() == ItemEnabled.DISABLED
                && state.conditions().stream().allMatch(c -> c.state() == ConditionState.NOT_EVALUATED));
    }

    // ---- what counts as a question and as an answer ----

    @Test
    void anItemWithNoConditionIsEnabledAndGivesNoReason() {
        Form form = EnableWhenEvaluator.evaluate(guarded(null, on("q1", "exists", "\"answerBoolean\":true")), Map.of());
        assertThat(form.state("item[0]").enabled()).isEqualTo(ItemEnabled.ENABLED);
        assertThat(form.state("item[0]").reason()).isNull();
        assertThat(form.state("item[0]").conditions()).isEmpty();
        assertThat(form.state("item[9]")).isNull();
    }

    @Test
    void aQuestionnaireWithNoItemsEvaluatesToNothingAndAnswersToUnknownLinkIdsAreIgnored() {
        Form form = EnableWhenEvaluator.evaluate(json("{\"resourceType\":\"Questionnaire\"}"), answers("ghost", "{\"valueString\":\"x\"}"));
        assertThat(form.all()).isEmpty();
        assertThat(form.requiredUnanswered()).isEmpty();
        assertThat(form.asked()).isEmpty();
        assertThat(EnableWhenEvaluator.evaluate(json("{\"resourceType\":\"Questionnaire\"}"), null).all()).isEmpty();
    }

    @Test
    void anExplanationNeverQuotesAnAnswer() {
        // Answers can be clinical. The explanation says whether the condition holds, never what was answered.
        Form form = EnableWhenEvaluator.evaluate(guarded("all", on("q1", "=", "\"answerString\":\"Placeholder-Diagnosis\""),
                on("q1", "exists", "\"answerBoolean\":true")), answers("q1", "{\"valueString\":\"Placeholder-Diagnosis\"}"));
        for (Condition condition : form.state("item[1]").conditions()) {
            assertThat(condition.explanation()).doesNotContain("Placeholder-Diagnosis");
        }
        assertThat(form.state("item[1]").reason()).doesNotContain("Placeholder-Diagnosis");
    }
}
