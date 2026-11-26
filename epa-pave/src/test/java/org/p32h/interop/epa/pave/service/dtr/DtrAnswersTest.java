package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireJson;
import tools.jackson.databind.JsonNode;

/**
 * Reading what a step was posted: the questionnaire, the response so far and the answers, each
 * answer turned into the FHIR value its question takes, and every problem reported by field.
 */
class DtrAnswersTest {

    private static final String QUESTIONNAIRE = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/"
            + "types\",\"version\":\"1.0\",\"item\":["
            + "{\"linkId\":\"bool\",\"type\":\"boolean\"},{\"linkId\":\"int\",\"type\":\"integer\"},{\"linkId\":\"dec\",\"type\":\"decimal\"},"
            + "{\"linkId\":\"date\",\"type\":\"date\"},{\"linkId\":\"dt\",\"type\":\"dateTime\"},{\"linkId\":\"time\",\"type\":\"time\"},"
            + "{\"linkId\":\"str\",\"type\":\"string\"},{\"linkId\":\"txt\",\"type\":\"text\"},{\"linkId\":\"url\",\"type\":\"url\"},"
            + "{\"linkId\":\"choice\",\"type\":\"choice\",\"repeats\":true,\"answerOption\":[{\"valueCoding\":{\"system\":\"s\",\"code\":\"a\","
            + "\"display\":\"Option A\"}},{\"valueCoding\":{\"system\":\"s\",\"code\":\"b\"}}]},"
            + "{\"linkId\":\"numbers\",\"type\":\"choice\",\"answerOption\":[{\"valueInteger\":1},{\"valueInteger\":2}]},"
            + "{\"linkId\":\"open\",\"type\":\"open-choice\",\"answerOption\":[{\"valueCoding\":{\"code\":\"a\"}}]},"
            + "{\"linkId\":\"fromValueSet\",\"type\":\"choice\",\"answerValueSet\":\"https://example.org/p32h-placeholder/ValueSet/x\"},"
            + "{\"linkId\":\"qty\",\"type\":\"quantity\"},{\"linkId\":\"untyped\"},"
            + "{\"linkId\":\"group\",\"type\":\"group\",\"item\":[{\"linkId\":\"inner\",\"type\":\"string\"}]},"
            + "{\"linkId\":\"note\",\"type\":\"display\"}]}";

    private static DtrAnswers.Posted read(DtrAnswerInput... answers) {
        return DtrAnswers.read(QUESTIONNAIRE, null, List.of(answers));
    }

    /** The one FHIR answer a single plain value becomes for a question, as compact JSON. */
    private static String converted(String linkId, String value) {
        DtrAnswers.Posted posted = read(DtrAnswerInput.of(linkId, value));
        assertThat(posted.errors()).as(linkId + " = " + value).isEmpty();
        return posted.given().get(linkId).get(0).toString();
    }

    private static FieldError refused(DtrAnswerInput answer) {
        DtrAnswers.Posted posted = read(answer);
        assertThat(posted.errors()).as(answer.linkId()).hasSize(1);
        return posted.errors().get(0);
    }

    private static JsonNode json(String text) {
        return JsonSafe.parse(text).node();
    }

    @Test
    void aPlainValueBecomesTheFhirValueItsQuestionTakes() {
        assertThat(converted("bool", "true")).isEqualTo("{\"valueBoolean\":true}");
        assertThat(converted("bool", "FALSE")).isEqualTo("{\"valueBoolean\":false}");
        assertThat(converted("int", " 14 ")).isEqualTo("{\"valueInteger\":14}");
        // A decimal keeps the digits it was typed with.
        assertThat(converted("dec", "1.50")).isEqualTo("{\"valueDecimal\":1.50}");
        assertThat(converted("date", "2026-01-31")).isEqualTo("{\"valueDate\":\"2026-01-31\"}");
        assertThat(converted("date", "2026-01")).isEqualTo("{\"valueDate\":\"2026-01\"}");
        assertThat(converted("dt", "2026-01-31T09:30:00-05:00")).isEqualTo("{\"valueDateTime\":\"2026-01-31T09:30:00-05:00\"}");
        // A time without seconds is the same time, written the way FHIR writes it.
        assertThat(converted("time", "09:30")).isEqualTo("{\"valueTime\":\"09:30:00\"}");
        assertThat(converted("str", "as typed")).isEqualTo("{\"valueString\":\"as typed\"}");
        assertThat(converted("txt", "a longer note")).isEqualTo("{\"valueString\":\"a longer note\"}");
        assertThat(converted("url", "https://example.org/x")).isEqualTo("{\"valueUri\":\"https://example.org/x\"}");
        // The code of an option becomes that option's whole coding, and an option of another type is chosen by its value.
        assertThat(converted("choice", "a")).isEqualTo("{\"valueCoding\":{\"system\":\"s\",\"code\":\"a\",\"display\":\"Option A\"}}");
        assertThat(converted("numbers", "2")).isEqualTo("{\"valueInteger\":2}");
        assertThat(converted("open", "a")).isEqualTo("{\"valueCoding\":{\"code\":\"a\"}}");
        assertThat(converted("open", "something else")).isEqualTo("{\"valueString\":\"something else\"}");
    }

    @Test
    void aJsonNumberOrBooleanIsReadLikeTheSameValueTypedAsText() {
        DtrAnswers.Posted posted = read(new DtrAnswerInput("int", List.of(json("14"))), new DtrAnswerInput("bool", List.of(json("true"))),
                new DtrAnswerInput("dec", List.of(json("2.50"))));
        assertThat(posted.errors()).isEmpty();
        assertThat(posted.given().get("int").get(0).toString()).isEqualTo("{\"valueInteger\":14}");
        assertThat(posted.given().get("bool").get(0).toString()).isEqualTo("{\"valueBoolean\":true}");
        assertThat(posted.given().get("dec").get(0).toString()).isEqualTo("{\"valueDecimal\":2.50}");
    }

    @Test
    void aFhirAnswerObjectIsUsedAsWrittenWhichIsHowAnAnswerOfAnotherTypeIsSent() {
        DtrAnswers.Posted posted = read(new DtrAnswerInput("qty", List.of(json("{\"valueQuantity\":{\"value\":5,\"unit\":\"mg\"}}"))),
                new DtrAnswerInput("fromValueSet", List.of(json("{\"valueCoding\":{\"system\":\"s\",\"code\":\"z\"}}"))),
                new DtrAnswerInput("int", List.of(json("{\"valueString\":\"deliberately the wrong type\"}"))));
        assertThat(posted.errors()).isEmpty();
        assertThat(posted.given().get("qty").get(0).toString()).isEqualTo("{\"valueQuantity\":{\"value\":5,\"unit\":\"mg\"}}");
        assertThat(posted.given().get("fromValueSet").get(0).at("/valueCoding/code").asString()).isEqualTo("z");
        assertThat(posted.given().get("int").get(0).has("valueString")).isTrue();
        // An object with no value in it is not an answer.
        assertThat(refused(new DtrAnswerInput("qty", List.of(json("{\"unit\":\"mg\"}")))).field()).isEqualTo("answers[0].values[0]");
        assertThat(refused(new DtrAnswerInput("str", List.of(json("{\"valueString\":\"\"}")))).message()).startsWith("This answer object carries no value.");
    }

    @Test
    void aValueTheQuestionCannotTakeIsRefusedByFieldAndTheMessageNeverRepeatsTheAnswer() {
        String typed = "a clinical remark typed into the wrong box";
        for (String linkId : List.of("bool", "int", "dec", "date", "dt", "time", "choice", "numbers", "fromValueSet", "qty", "untyped")) {
            FieldError error = refused(DtrAnswerInput.of(linkId, typed));
            assertThat(error.field()).as(linkId).isEqualTo("answers[0].values[0]");
            assertThat(error.message()).as(linkId).doesNotContain("clinical").doesNotContain("remark");
        }
        assertThat(refused(DtrAnswerInput.of("int", "14.5")).message()).isEqualTo("This question takes a whole number.");
        assertThat(refused(DtrAnswerInput.of("bool", "yes")).message()).isEqualTo("This question takes true or false.");
        // A date that looks like one and is not a real day.
        assertThat(refused(DtrAnswerInput.of("date", "2026-02-30")).message()).startsWith("This question takes a date written as YYYY-MM-DD");
        assertThat(refused(DtrAnswerInput.of("date", "01/31/2026")).message()).startsWith("This question takes a date");
        assertThat(refused(DtrAnswerInput.of("time", "25:00")).message()).startsWith("This question takes a time of day");
        assertThat(refused(DtrAnswerInput.of("choice", "c")).message()).isEqualTo("The answer given is not one of the options of this question. "
                + "Give the code of one of them.");
        assertThat(refused(DtrAnswerInput.of("fromValueSet", "a")).message()).startsWith("This question lists no options of its own");
        assertThat(refused(DtrAnswerInput.of("qty", "5")).message()).startsWith("This question is of the type quantity");
        assertThat(refused(DtrAnswerInput.of("untyped", "x")).message()).startsWith("This question states no type");
        assertThat(refused(DtrAnswerInput.of("str", "  ")).message()).startsWith("This answer is empty.");
    }

    @Test
    void anAnswerHasToBeForOneQuestionOfTheQuestionnaireAndOnlyARepeatingQuestionTakesSeveral() {
        assertThat(refused(DtrAnswerInput.of(" ", "x")).field()).isEqualTo("answers[0].linkId");
        assertThat(refused(DtrAnswerInput.of("missing", "x")).message()).isEqualTo("The questionnaire has no item with the linkId missing.");
        assertThat(refused(DtrAnswerInput.of("group", "x")).message()).isEqualTo("The item group is a group, which takes no answer.");
        assertThat(refused(DtrAnswerInput.of("note", "x")).message()).isEqualTo("The item note is display text, which takes no answer.");
        assertThat(refused(DtrAnswerInput.of("str", "one", "two")).field()).isEqualTo("answers[0].values");
        // A repeating question takes each value, in order, and a question inside a group is answered by its own linkId.
        DtrAnswers.Posted posted = read(DtrAnswerInput.of("choice", "b", "a"), DtrAnswerInput.of("inner", "nested"));
        assertThat(posted.errors()).isEmpty();
        assertThat(posted.given().get("choice")).extracting(answer -> answer.at("/valueCoding/code").asString()).containsExactly("b", "a");
        assertThat(posted.given().get("inner").get(0).get("valueString").asString()).isEqualTo("nested");
        // The same question twice in one request: the second is refused, and every problem is reported at once.
        DtrAnswers.Posted twice = read(DtrAnswerInput.of("str", "one"), DtrAnswerInput.of("str", "two"), DtrAnswerInput.of("int", "x"));
        assertThat(twice.errors()).extracting(FieldError::field).containsExactly("answers[1].linkId", "answers[2].values[0]");
    }

    @Test
    void theAnswersBeingAddedReplaceTheEarlierOnesAndAnEmptyListTakesAnAnswerBack() {
        String soFar = "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"https://example.org/p32h-placeholder/Questionnaire/types|1.0\","
                + "\"status\":\"in-progress\",\"item\":[{\"linkId\":\"str\",\"answer\":[{\"valueString\":\"earlier\"}]},"
                + "{\"linkId\":\"int\",\"answer\":[{\"valueInteger\":1}]},{\"linkId\":\"bool\",\"answer\":[{\"valueBoolean\":true}]}]}";
        DtrAnswers.Posted posted = DtrAnswers.read(QUESTIONNAIRE, soFar, List.of(DtrAnswerInput.of("str", "changed"),
                new DtrAnswerInput("int", List.of()), DtrAnswerInput.of("dec", "2.5")));
        assertThat(posted.errors()).isEmpty();
        assertThat(posted.merged().keySet()).containsExactly("str", "bool", "dec");
        assertThat(posted.merged().get("str").get(0).get("valueString").asString()).isEqualTo("changed");
        assertThat(posted.merged().get("bool").get(0).get("valueBoolean").asBoolean()).isTrue();
        // With nothing added, the response so far is what there is.
        assertThat(DtrAnswers.read(QUESTIONNAIRE, soFar, List.of()).merged().keySet()).containsExactly("str", "int", "bool");
    }

    @Test
    void theQuestionnaireAResponseCarriesInsideItselfIsTheOneThatIsAnswered() {
        String fromTheLoop = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"item\":["
                + "{\"linkId\":\"asked-now\",\"type\":\"string\"}]}],\"questionnaire\":\"#q\",\"status\":\"in-progress\"}";
        // It is the current state of the questionnaire, so it wins over the one posted beside it.
        DtrAnswers.Posted posted = DtrAnswers.read(QUESTIONNAIRE, fromTheLoop, List.of(DtrAnswerInput.of("asked-now", "x")));
        assertThat(posted.errors()).isEmpty();
        assertThat(QuestionnaireJson.questions(posted.questionnaire())).extracting(item -> item.linkId()).containsExactly("asked-now");
        assertThat(DtrAnswers.read(QUESTIONNAIRE, fromTheLoop, List.of(DtrAnswerInput.of("str", "x"))).errors()).extracting(FieldError::field)
                .containsExactly("answers[0].linkId");
        // The response can also arrive in the Parameters wrapper a service may use.
        assertThat(DtrAnswers.read(null, "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"return\",\"resource\":" + fromTheLoop + "}]}",
                List.of()).errors()).isEmpty();
    }

    @Test
    void whatWasPostedHasToBeUsableBeforeAnyAnswerIsLookedAt() {
        assertThat(DtrAnswers.read(null, null, List.of()).errors()).extracting(FieldError::field).containsExactly("questionnaire");
        assertThat(DtrAnswers.read("  ", "  ", null).errors()).extracting(FieldError::field).containsExactly("questionnaire");
        assertThat(DtrAnswers.read("{\"resourceType\": ", null, List.of()).errors()).extracting(FieldError::field).containsExactly("questionnaire");
        assertThat(DtrAnswers.read("{\"resourceType\":\"ValueSet\"}", null, List.of()).errors().get(0).message())
                .startsWith("What was given here is not a Questionnaire resource.");
        assertThat(DtrAnswers.read(QUESTIONNAIRE, "not json", List.of()).errors()).extracting(FieldError::field).containsExactly("questionnaireResponse");
        assertThat(DtrAnswers.read(QUESTIONNAIRE, "{\"resourceType\":\"Bundle\"}", List.of()).errors().get(0).message())
                .startsWith("What was given here is not a QuestionnaireResponse.");
        // A response that points at a questionnaire inside itself which is not there, or names another questionnaire.
        assertThat(DtrAnswers.read(QUESTIONNAIRE, "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"#gone\"}", List.of()).errors()
                .get(0).message()).contains("#gone").contains("carries none with that id");
        assertThat(DtrAnswers.read(QUESTIONNAIRE, "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":"
                + "\"https://example.org/p32h-placeholder/Questionnaire/other\"}", List.of()).errors().get(0).message())
                .contains("which is not the questionnaire given beside it");
        // The same questionnaire named without its version, or by another version, is and is not the one given.
        assertThat(DtrAnswers.read(QUESTIONNAIRE, "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":"
                + "\"https://example.org/p32h-placeholder/Questionnaire/types\"}", List.of()).errors()).isEmpty();
        assertThat(DtrAnswers.read(QUESTIONNAIRE, "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":"
                + "\"https://example.org/p32h-placeholder/Questionnaire/types|2.0\"}", List.of()).errors()).hasSize(1);
        // A questionnaire an answer could not be tied to is refused as a whole, with the reason.
        DtrAnswers.Posted unusable = DtrAnswers.read("{\"resourceType\":\"Questionnaire\",\"item\":[{\"linkId\":\"q\",\"type\":\"string\"},"
                + "{\"linkId\":\"q\",\"type\":\"string\"}]}", null, List.of(DtrAnswerInput.of("q", "x")));
        assertThat(unusable.errors()).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("questionnaire");
            assertThat(error.message()).startsWith("This questionnaire cannot be answered.").contains("is used by 2 items");
        });
    }
}
