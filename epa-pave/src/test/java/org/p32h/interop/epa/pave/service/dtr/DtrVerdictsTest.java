package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

/**
 * The wording of a DTR result and the step it names next: one headline for every outcome, the
 * sentence about what was and was not exercised on every result, and a completed response handed
 * on only when the response is complete.
 */
class DtrVerdictsTest {

    private static final String RUN = "run-00000000-0000-4000-8000-000000000001";
    private static final String ANSWER = "Placeholder answer that is clinical";
    private static final List<EpaOperation> STEPS = List.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, EpaOperation.DTR_NEXT_QUESTION,
            EpaOperation.DTR_RESPONSE);

    private final DtrResponseMapper mapper = new DtrResponseMapper();

    private static String questionnaire(String marker, String items) {
        return "{\"resourceType\":\"Questionnaire\",\"id\":\"q\",\"url\":\"https://example.org/p32h-placeholder/Questionnaire/verdicts\","
                + "\"title\":\"Verdict questionnaire (placeholder)\"" + marker + (items.isEmpty() ? "" : ",\"item\":[" + items + "]") + "}";
    }

    private static final String ADAPTIVE_MARKER = ",\"extension\":[{\"url\":\"http://hl7.org/fhir/uv/sdc/StructureDefinition/"
            + "sdc-questionnaire-questionnaireAdaptive\",\"valueBoolean\":true}]";
    private static final String ITEMS = "{\"linkId\":\"q1\",\"text\":\"First question?\",\"type\":\"string\",\"required\":true},"
            + "{\"linkId\":\"q2\",\"text\":\"Second question\",\"type\":\"string\"}";

    private static String bundle(String... questionnaires) {
        StringBuilder entries = new StringBuilder();
        for (String one : questionnaires) {
            entries.append(entries.length() == 0 ? "" : ",").append("{\"resource\":").append(one).append("}");
        }
        return "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[" + entries + "]}";
    }

    /** A step of the loop that returned the two questions above, the first of them answered with clinical text. */
    private DtrReading step(String status) {
        String body = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[" + questionnaire(ADAPTIVE_MARKER, ITEMS) + "],"
                + "\"questionnaire\":\"#q\",\"status\":\"" + status + "\",\"item\":[{\"linkId\":\"q1\",\"answer\":[{\"valueString\":\"" + ANSWER
                + "\"}]}]}";
        return mapper.readNextQuestion(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 200, body), null);
    }

    private DtrReading packageOf(String... questionnaires) {
        return mapper.readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, bundle(questionnaires)), List.of());
    }

    private static DtrReading as(DtrReading reading, DtrOutcome outcome) {
        return new DtrReading(reading.detail(), outcome, reading.additionalFields());
    }

    private static DtrHandoff handoff() {
        return DtrHandoff.afterAnswering(RUN, List.of("95810"), "https://example.org/p32h-placeholder/Questionnaire/verdicts", Map.of());
    }

    @Test
    void everyOutcomeHasAHeadlineAndANextStepWhicheverStepItFollows() {
        VendorExchange exchange = DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 200, "{}");
        for (DtrReading shape : List.of(packageOf(questionnaire("", ITEMS)), step("in-progress"), packageOf())) {
            for (EpaOperation operation : STEPS) {
                for (DtrOutcome outcome : DtrOutcome.values()) {
                    String what = outcome + " after " + operation;
                    VerdictView verdict = DtrVerdicts.verdict(operation, as(shape, outcome), exchange);
                    assertThat(verdict.headline()).as(what).isNotBlank().endsWith(".").doesNotContain("null");
                    assertThat(verdict.outcomeCode()).as(what).isEqualTo(outcome.name());
                    assertThat(verdict.tone()).as(what).isEqualTo(outcome.tone());
                    // The sentence about what was exercised closes every result.
                    assertThat(verdict.supporting()).as(what).last().isEqualTo(DtrVerdicts.scope(operation));
                    assertThat(verdict.supporting()).as(what).doesNotHaveDuplicates().allSatisfy(line -> assertThat(line).doesNotContain("null"));
                    NextStepView next = DtrVerdicts.nextStep(operation, as(shape, outcome), exchange, handoff());
                    assertThat(next.action()).as(what).isNotNull();
                    assertThat(next.headline()).as(what).startsWith("Next: ").endsWith(".");
                    assertThat(next.reason()).as(what).isNotBlank();
                    // The run later steps are linked to is handed on whatever happened, so a step can always be sent again.
                    assertThat(next.carry().get(NextStepView.CARRY_RUN_ID)).as(what).containsExactly(RUN);
                }
            }
        }
        // A step completed inside the tool has no exchange, and is still worded.
        assertThat(DtrVerdicts.verdict(EpaOperation.DTR_RESPONSE, as(step("completed"), DtrOutcome.REJECTED), null).headline())
                .isEqualTo("The service did not accept the request as sent.");
    }

    @Test
    void theSentenceAboutWhatWasExercisedNamesTheOperationAndSaysNoSmartLaunchWasInvolved() {
        assertThat(DtrVerdicts.scope(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE)).isEqualTo("This step exercised the $questionnaire-package "
                + "operation behind a provider-facing SMART launch, not the launch itself, which this tool does not host.");
        assertThat(DtrVerdicts.scope(EpaOperation.DTR_NEXT_QUESTION)).isEqualTo("This step exercised the next-question operation behind a "
                + "provider-facing SMART launch, not the launch itself, which this tool does not host.");
        assertThat(DtrVerdicts.scope(EpaOperation.DTR_RESPONSE)).isEqualTo("This step was completed inside this tool and exercised neither a "
                + "DTR operation nor a provider-facing SMART launch, which this tool does not host.");
        for (EpaOperation operation : STEPS) {
            assertThat(DtrVerdicts.scope(operation)).contains("SMART launch").contains("does not host");
        }
    }

    @Test
    void theHeadlineIsChosenByTheOutcomeAndNothingUnderItQuotesAnAnswer() {
        DtrReading reading = step("in-progress");
        assertThat(reading.outcome()).isEqualTo(DtrOutcome.NEXT_QUESTION);
        VerdictView verdict = DtrVerdicts.verdict(EpaOperation.DTR_NEXT_QUESTION, reading, null);
        assertThat(verdict.headline()).isEqualTo("The service returned the next question.");
        // The question is named the way the payer named it. The answer given to the other one is not repeated.
        assertThat(verdict.supporting()).contains("Question count so far: 2. Answered: 1. Completion status: in-progress.",
                "Now asked: q2 \"Second question\".");
        assertThat(String.join(" ", verdict.supporting())).doesNotContain(ANSWER);
        assertThat(verdict.toString()).doesNotContain("First question");
        NextStepView next = DtrVerdicts.nextStep(EpaOperation.DTR_NEXT_QUESTION, reading, null, handoff());
        assertThat(next.headline() + next.reason()).doesNotContain(ANSWER);

        // Several questions asked at once are counted in the headline.
        String two = "{\"resourceType\":\"QuestionnaireResponse\",\"contained\":[" + questionnaire(ADAPTIVE_MARKER, ITEMS) + "],"
                + "\"questionnaire\":\"#q\",\"status\":\"in-progress\"}";
        assertThat(DtrVerdicts.verdict(EpaOperation.DTR_NEXT_QUESTION,
                mapper.readNextQuestion(DtrFixtures.answered(EpaOperation.DTR_NEXT_QUESTION, 200, two), null), null).headline())
                .isEqualTo("The service returned the next 2 questions.");
    }

    @Test
    void theStepAfterAPackageFollowsTheKindOfEveryQuestionnaireInIt() {
        String staticOne = questionnaire("", ITEMS);
        String adaptiveOne = questionnaire(ADAPTIVE_MARKER, "");
        String unmarked = questionnaire("", "");
        DtrHandoff handoff = DtrHandoff.afterPackage(RUN, List.of(), List.of(), Map.of());
        assertThat(DtrVerdicts.nextStep(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, packageOf(staticOne), null, handoff).action())
                .isEqualTo(NextAction.SUBMIT_RESPONSE);
        assertThat(DtrVerdicts.nextStep(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, packageOf(adaptiveOne), null, handoff).action())
                .isEqualTo(NextAction.ANSWER_NEXT_QUESTION);
        assertThat(DtrVerdicts.nextStep(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, packageOf(adaptiveOne, adaptiveOne), null, handoff).headline())
                .isEqualTo("Next: start each adaptive questionnaire with next-question.");
        // One of each, or one this tool cannot tell: the tester chooses, and is told why.
        NextStepView mixed = DtrVerdicts.nextStep(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, packageOf(staticOne, adaptiveOne), null, handoff);
        assertThat(mixed.action()).isEqualTo(NextAction.REVIEW);
        assertThat(mixed.reason()).isEqualTo("The package holds questionnaires of different kinds, or one whose kind this tool cannot tell.");
        assertThat(DtrVerdicts.nextStep(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, packageOf(unmarked), null, handoff).action())
                .isEqualTo(NextAction.REVIEW);
        assertThat(DtrVerdicts.nextStep(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, packageOf(staticOne, unmarked), null, handoff).action())
                .isEqualTo(NextAction.REVIEW);
    }

    @Test
    void onlyAResponseThisToolReportsAsCompleteIsHandedToPas() {
        DtrReading completed = step("completed");
        for (DtrOutcome outcome : DtrOutcome.values()) {
            NextStepView next = DtrVerdicts.nextStep(EpaOperation.DTR_NEXT_QUESTION, as(completed, outcome), null, handoff());
            boolean complete = outcome == DtrOutcome.RESPONSE_COMPLETED || outcome == DtrOutcome.NOTHING_REQUIRED;
            assertThat(next.carry().containsKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE)).as(outcome.name()).isEqualTo(complete);
            assertThat(next.action() == NextAction.START_PAS).as(outcome.name()).isEqualTo(complete);
        }
        // What is handed on is the response exactly as the service returned it.
        assertThat(DtrVerdicts.nextStep(EpaOperation.DTR_NEXT_QUESTION, as(completed, DtrOutcome.RESPONSE_COMPLETED), null, handoff()).carry()
                .get(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE)).containsExactly(completed.detail().questionnaireResponses().get(0).json());
    }

    @Test
    void aRequestThatWasNotAcceptedPointsAtTheLikelyCauseForItsStatusAndAFaultCarriesItsTransportDetail() {
        DtrReading rejected = as(packageOf(), DtrOutcome.REJECTED);
        for (int status : new int[] {401, 403}) {
            NextStepView next = DtrVerdicts.nextStep(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, rejected,
                    TestExchanges.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, status, "{}", true), handoff());
            assertThat(next.action()).isEqualTo(NextAction.OPEN_CONFIG);
            assertThat(next.reason()).contains("HTTP " + status).contains("credentials");
        }
        assertThat(DtrVerdicts.nextStep(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, rejected,
                TestExchanges.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 404, "{}", true), handoff()).headline())
                .isEqualTo("Next: check the DTR url on the Config tab.");
        assertThat(DtrVerdicts.nextStep(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, rejected,
                TestExchanges.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 422, "{}", true), handoff()).action()).isEqualTo(NextAction.FIX_REQUEST);
        assertThat(DtrVerdicts.verdict(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, rejected,
                TestExchanges.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 422, "{}", true)).headline())
                .isEqualTo("The service did not accept the request as sent (HTTP 422).");

        VendorExchange down = TestExchanges.unavailable(EpaOperation.DTR_NEXT_QUESTION);
        VerdictView unreachable = DtrVerdicts.verdict(EpaOperation.DTR_NEXT_QUESTION, mapper.readNextQuestion(down, null), down);
        assertThat(unreachable.headline()).isEqualTo("The service could not be reached.");
        assertThat(unreachable.supporting()).contains("The connection was refused or could not be opened.");
        assertThat(DtrVerdicts.nextStep(EpaOperation.DTR_NEXT_QUESTION, mapper.readNextQuestion(down, null), down, handoff()).action())
                .isEqualTo(NextAction.CHECK_ENVIRONMENT);
    }
}
