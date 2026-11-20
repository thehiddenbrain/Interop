package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.NextStepView;

/**
 * What one DTR step hands the next: what is passed on unchanged, what a step replaces, and how
 * completed responses add up across the questionnaires of one package.
 */
class DtrHandoffTest {

    private static final String RUN = "run-00000000-0000-4000-8000-000000000001";
    private static final String EARLIER = "run-00000000-0000-4000-8000-000000000002";
    private static final String ONE = "https://example.org/p32h-placeholder/Questionnaire/one";
    private static final String TWO = "https://example.org/p32h-placeholder/Questionnaire/two";

    private static String response(String questionnaire) {
        return "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"" + questionnaire + "\",\"status\":\"completed\"}";
    }

    private static String loopResponse(String url, String version) {
        return "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"url\":\"" + url + "\""
                + (version == null ? "" : ",\"version\":\"" + version + "\"") + "}],\"questionnaire\":\"#q\",\"status\":\"completed\"}";
    }

    /** A response from the loop as the guides shape it: the questionnaire inside says what it is derived from. */
    private static String derivedResponse(String ownUrl, String derivedFrom) {
        return "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[{\"resourceType\":\"Questionnaire\",\"id\":\"q\""
                + (ownUrl == null ? "" : ",\"url\":\"" + ownUrl + "\"") + ",\"derivedFrom\":[\"" + derivedFrom + "\"]}],"
                + "\"questionnaire\":\"#q\",\"status\":\"completed\"}";
    }

    @Test
    void whatCrdSaidIsPassedOnUnchangedAndAKeyThisToolDoesNotDefineIsNot() {
        Map<String, List<String>> given = new LinkedHashMap<>();
        given.put(NextStepView.CARRY_COVERAGE_ASSERTION_ID, List.of("assertion-1"));
        given.put(NextStepView.CARRY_ORDER, List.of("ServiceRequest/service-request-1"));
        given.put(NextStepView.CARRY_DELEGATED_VENDOR, List.of("eviCore"));
        given.put(NextStepView.CARRY_DTR_LINK_URL, List.of("https://example.org/p32h-placeholder/dtr/launch"));
        given.put(NextStepView.CARRY_DTR_APP_CONTEXT, List.of("questionnaire=" + ONE));
        given.put(NextStepView.CARRY_DOC_PURPOSE, List.of("withpa"));
        given.put(NextStepView.CARRY_PROCEDURE_CODES, List.of("00000"));
        given.put("somethingElse", List.of("not a key this tool defines"));
        Map<String, List<String>> carry = new NextStepView(org.p32h.interop.epa.pave.model.NextAction.REVIEW, "Next: read.", "Because.",
                DtrHandoff.afterPackage(RUN, List.of("95810"), List.of(ONE), given).carry(null)).carry();
        assertThat(carry).containsOnlyKeys(NextStepView.CARRY_COVERAGE_ASSERTION_ID, NextStepView.CARRY_ORDER, NextStepView.CARRY_DELEGATED_VENDOR,
                NextStepView.CARRY_DTR_LINK_URL, NextStepView.CARRY_DTR_APP_CONTEXT, NextStepView.CARRY_DOC_PURPOSE, NextStepView.CARRY_PROCEDURE_CODES,
                NextStepView.CARRY_QUESTIONNAIRE, NextStepView.CARRY_RUN_ID);
        assertThat(carry.get(NextStepView.CARRY_DELEGATED_VENDOR)).containsExactly("eviCore");
        // What CRD said the documentation is for reaches the step that builds the response, which reads it as the intended use.
        assertThat(carry.get(NextStepView.CARRY_DOC_PURPOSE)).containsExactly("withpa");
        // What the step establishes itself replaces what it was handed.
        assertThat(carry.get(NextStepView.CARRY_PROCEDURE_CODES)).containsExactly("95810");
        assertThat(carry.get(NextStepView.CARRY_QUESTIONNAIRE)).containsExactly(ONE);
        assertThat(carry.get(NextStepView.CARRY_RUN_ID)).containsExactly(RUN);
    }

    @Test
    void aStepThatAnswersAQuestionnaireKeepsTheRunAndTheListItWasHandedAndOtherwiseStartsItsOwn() {
        DtrHandoff followed = DtrHandoff.afterAnswering(RUN, List.of(), ONE, Map.of(NextStepView.CARRY_RUN_ID, List.of(EARLIER),
                NextStepView.CARRY_QUESTIONNAIRE, List.of(ONE, TWO)));
        assertThat(followed.followsRunId()).isEqualTo(EARLIER);
        assertThat(followed.questionnaires()).containsExactly(ONE, TWO);
        DtrHandoff cold = DtrHandoff.afterAnswering(RUN, List.of(), ONE, Map.of());
        assertThat(cold.followsRunId()).isEqualTo(RUN);
        assertThat(cold.questionnaires()).containsExactly(ONE);
        // A questionnaire with no url cannot be named, so it is not listed.
        assertThat(DtrHandoff.afterAnswering(RUN, List.of(), null, Map.of()).questionnaires()).isEmpty();
        assertThat(DtrHandoff.afterAnswering(RUN, List.of(), null, null).carry(null)).containsEntry(NextStepView.CARRY_RUN_ID, List.of(RUN));
    }

    @Test
    void completedResponsesAddUpAndAResponseForTheQuestionnaireBeingAnsweredIsReplaced() {
        Map<String, List<String>> given = Map.of(NextStepView.CARRY_QUESTIONNAIRE, List.of(ONE, TWO),
                NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(response(ONE), response(TWO)));
        DtrHandoff answeringTwo = DtrHandoff.afterAnswering(RUN, List.of(), TWO, given);
        // Complete: the new response takes the place of the earlier one for the same questionnaire.
        String again = "{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"" + TWO + "\",\"status\":\"completed\",\"id\":\"again\"}";
        assertThat(answeringTwo.completedResponses(again)).containsExactly(response(ONE), again);
        assertThat(answeringTwo.stillToAnswer(again)).isEmpty();
        // Not complete: the earlier response for it is dropped all the same, because the tester's current answers replace it.
        assertThat(answeringTwo.completedResponses(null)).containsExactly(response(ONE));
        assertThat(answeringTwo.stillToAnswer(null)).containsExactly(TWO);
        // A response is kept exactly as it was handed on, with whatever spacing it had.
        String spaced = "  " + response(ONE) + "\n";
        assertThat(DtrHandoff.afterAnswering(RUN, List.of(), TWO, Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(spaced)))
                .completedResponses(null)).containsExactly(spaced);
    }

    @Test
    void aQuestionnaireIsMatchedByItsUrlAndVersionAndTwoVersionsAreTwoQuestionnaires() {
        assertThat(DtrHandoff.same(ONE, ONE)).isTrue();
        assertThat(DtrHandoff.same(ONE + "|1.0", ONE)).isTrue();
        assertThat(DtrHandoff.same(ONE, ONE + "|1.0")).isTrue();
        assertThat(DtrHandoff.same(ONE + "|1.0", ONE + "|1.0")).isTrue();
        assertThat(DtrHandoff.same(ONE + "|1.0", ONE + "|2.0")).isFalse();
        assertThat(DtrHandoff.same(ONE, TWO)).isFalse();
        assertThat(DtrHandoff.same(null, ONE)).isFalse();
        assertThat(DtrHandoff.same(null, null)).isFalse();
        assertThat(DtrHandoff.same(" ", " ")).isFalse();

        // A package with two versions of one questionnaire: a response for one version does not answer the other.
        DtrHandoff versions = DtrHandoff.afterAnswering(RUN, List.of(), ONE + "|1.0", Map.of(NextStepView.CARRY_QUESTIONNAIRE,
                List.of(ONE + "|1.0", ONE + "|2.0")));
        assertThat(versions.stillToAnswer(response(ONE + "|1.0"))).containsExactly(ONE + "|2.0");
        // A response that names no version stands for one of them, never for both.
        assertThat(versions.stillToAnswer(response(ONE))).containsExactly(ONE + "|2.0");
        assertThat(versions.stillToAnswer(null)).containsExactly(ONE + "|1.0", ONE + "|2.0");
    }

    @Test
    void theQuestionnaireAResponseAnswersIsWhatTheOneInsideItIsDerivedFromThenThatOnesOwnUrlElseTheOneItNames() {
        assertThat(DtrHandoff.answeredBy(loopResponse(ONE, "1.0"))).containsExactly(ONE + "|1.0");
        assertThat(DtrHandoff.answeredBy(loopResponse(ONE, null))).containsExactly(ONE);
        assertThat(DtrHandoff.answeredBy(response(TWO + "|3"))).containsExactly(TWO + "|3");
        // The shape the guides give a response from the loop: the questionnaire inside has no url and says what it is derived from.
        assertThat(DtrHandoff.answeredBy(derivedResponse(null, ONE + "|1.0"))).containsExactly(ONE + "|1.0");
        // When it has a url of its own as well, what it is derived from still comes first.
        assertThat(DtrHandoff.answeredBy(derivedResponse(TWO, ONE + "|1.0"))).containsExactly(ONE + "|1.0", TWO);
        // A local reference to something that is not there names nothing, and so does anything that is not a response.
        assertThat(DtrHandoff.answeredBy("{\"resourceType\":\"QuestionnaireResponse\",\"questionnaire\":\"#gone\"}")).isEmpty();
        assertThat(DtrHandoff.answeredBy("{\"resourceType\":\"QuestionnaireResponse\"}")).isEmpty();
        assertThat(DtrHandoff.answeredBy("{\"resourceType\":\"Patient\",\"questionnaire\":\"" + ONE + "\"}")).isEmpty();
        assertThat(DtrHandoff.answeredBy("not json")).isEmpty();
        assertThat(DtrHandoff.answeredBy((String) null)).isEmpty();
        // A response for a questionnaire that cannot be named never counts another questionnaire as answered.
        DtrHandoff handoff = DtrHandoff.afterAnswering(RUN, List.of(), null, Map.of(NextStepView.CARRY_QUESTIONNAIRE, List.of(ONE)));
        String unnamed = "{\"resourceType\":\"QuestionnaireResponse\",\"status\":\"completed\"}";
        assertThat(handoff.stillToAnswer(unnamed)).containsExactly(ONE);
        assertThat(handoff.counts(unnamed)).isFalse();
    }

    @Test
    void aResponseFromTheLoopIsCountedTowardsTheQuestionnaireItsQuestionnaireIsDerivedFrom() {
        String instance = "https://example.org/p32h-placeholder/Questionnaire/an-instance-of-one";
        Map<String, List<String>> ofThePackage = Map.of(NextStepView.CARRY_QUESTIONNAIRE, List.of(ONE + "|1.0", TWO));
        DtrHandoff handoff = DtrHandoff.afterAnswering(RUN, List.of(), ONE + "|1.0", ofThePackage);
        // No url of its own at all, which is what the SDC profile asks for: it is tied by what it is derived from.
        assertThat(handoff.counts(derivedResponse(null, ONE + "|1.0"))).isTrue();
        assertThat(handoff.stillToAnswer(derivedResponse(null, ONE + "|1.0"))).containsExactly(TWO);
        // A url of its own that is not the one in the package does not untie it.
        assertThat(handoff.counts(derivedResponse(instance, ONE + "|1.0"))).isTrue();
        assertThat(handoff.stillToAnswer(derivedResponse(instance, ONE))).containsExactly(TWO);
        // Derived from something the package does not hold: complete or not, it answers none of the package.
        assertThat(handoff.counts(derivedResponse(null, instance))).isFalse();
        assertThat(handoff.stillToAnswer(derivedResponse(null, instance))).containsExactly(ONE + "|1.0", TWO);
        // Derived from something else, and carrying the url of the one in the package: the second way of naming it counts as well.
        assertThat(handoff.counts(derivedResponse(ONE, instance))).isTrue();
        assertThat(handoff.stillToAnswer(derivedResponse(ONE, instance))).containsExactly(TWO);

        // A response handed on for the questionnaire now being answered is replaced, whichever of the two ways it names it.
        Map<String, List<String>> given = Map.of(NextStepView.CARRY_QUESTIONNAIRE, List.of(ONE + "|1.0", TWO),
                NextStepView.CARRY_QUESTIONNAIRE_RESPONSE, List.of(derivedResponse(null, ONE + "|1.0"), response(TWO)));
        assertThat(DtrHandoff.afterAnswering(RUN, List.of(), ONE + "|1.0", given).completedResponses(null)).containsExactly(response(TWO));
        assertThat(DtrHandoff.afterAnswering(RUN, List.of(), TWO, given).completedResponses(null))
                .containsExactly(derivedResponse(null, ONE + "|1.0"));
        assertThat(DtrHandoff.afterAnswering(RUN, List.of(), ONE + "|1.0", Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE,
                List.of(derivedResponse(ONE, instance)))).completedResponses(null)).isEmpty();
    }

    @Test
    void theHandOffPrintsTheNamesOfWhatItHoldsAndNeverAResponse() {
        DtrHandoff handoff = DtrHandoff.afterAnswering(RUN, List.of(), ONE, Map.of(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE,
                List.of("{\"resourceType\":\"QuestionnaireResponse\",\"item\":[{\"answer\":[{\"valueString\":\"a clinical answer\"}]}]}")));
        assertThat(handoff.toString()).isEqualTo("DtrHandoff[given=[questionnaireResponse]]").doesNotContain("clinical");
    }
}
