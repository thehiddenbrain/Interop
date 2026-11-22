package org.p32h.interop.epa.pave.service.chain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.CaseWiring;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.chain.ChainStage;
import org.p32h.interop.epa.pave.model.chain.ChainStageState;
import org.p32h.interop.epa.pave.model.chain.ChainStep;
import org.p32h.interop.epa.pave.model.chain.ChainStep.Step;
import org.p32h.interop.epa.pave.model.chain.ChainView;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * The four steps of a chain (plan 7.3, R-22): Coverage check, Questionnaire, Submit and Status, each
 * with its chip, its state line and its latest run, built from ledger lines beside the three stages,
 * which keep their shape and words.
 */
class ChainStepsTest {

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final String chainId = RunStamps.newChainId();
    private int stamp;

    private ChainService chains() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new ChainService(beans.getBeanProvider(RunLedger.class), new CaseWiring().outcomeCatalogue());
    }

    /** A ledger line as a service writes one, a minute after the one before. */
    private String line(EpaOperation operation, String outcome, NextAction next) {
        String runId = RunStamps.newRunId();
        ++stamp;
        ledger.record(RunRecord.builder().runId(runId).timestamp("2026-03-10T10:" + (stamp < 10 ? "0" : "") + stamp + ":00.000-05:00")
                .protocol(operation.protocol()).operation(operation).target("onyx").profile("mock").outcome(outcome)
                .result(TestResult.UNVERIFIED).httpStatus(200).elapsedMillis(40).chainId(chainId).nextAction(next)
                .procedureCodes(List.of("95810")).build());
        return runId;
    }

    private static ChainStep step(ChainView view, Step step) {
        return view.steps().stream().filter(s -> s.step() == step).findFirst().orElseThrow();
    }

    @Test
    void aChainHasFourStepsInOrderWithTheirNamesAndProtocols() {
        line(EpaOperation.CRD_ORDER_SIGN, "AUTH_REQUIRED", NextAction.START_PAS);
        ChainView view = chains().view(chainId);
        assertThat(view.steps()).extracting(ChainStep::step).containsExactly(Step.COVERAGE, Step.QUESTIONNAIRE, Step.SUBMIT, Step.STATUS);
        assertThat(view.steps()).extracting(ChainStep::name).containsExactly("Coverage check", "Questionnaire", "Submit", "Status");
        assertThat(view.steps()).extracting(ChainStep::protocol).containsExactly(Protocol.CRD, Protocol.DTR, Protocol.PAS, Protocol.PAS);
    }

    @Test
    void aCoverageCheckThatAsksForNoDocumentationLeavesTheQuestionnaireNotNeededAndSubmitNext() {
        String crd = line(EpaOperation.CRD_ORDER_SIGN, "AUTH_REQUIRED", NextAction.START_PAS);
        ChainView view = chains().view(chainId);

        ChainStep coverage = step(view, Step.COVERAGE);
        assertThat(coverage.state()).isEqualTo(ChainStageState.DONE);
        assertThat(coverage.chip()).isEqualTo(new ChainStep.Chip("PA required", Tone.ACTION));
        assertThat(coverage.latestRunId()).isEqualTo(crd);
        assertThat(coverage.nextAction()).isEqualTo(NextAction.START_PAS);
        assertThat(coverage.stateLine()).isEqualTo("Done: Prior authorization is required.");

        ChainStep questionnaire = step(view, Step.QUESTIONNAIRE);
        assertThat(questionnaire.state()).isEqualTo(ChainStageState.NOT_NEEDED);
        assertThat(questionnaire.chip()).isEqualTo(new ChainStep.Chip("Not needed", null));
        assertThat(questionnaire.stateLine()).isEqualTo("Not needed: the coverage check asked for no documentation.");
        assertThat(questionnaire.latestRunId()).isNull();
        // The not-needed step offers the action the journey calls for, which the page draws as "Continue to Submit".
        assertThat(questionnaire.nextAction()).isEqualTo(NextAction.START_PAS);
        assertThat(questionnaire.nextAction().button()).isEqualTo("Continue to Submit");

        ChainStep submit = step(view, Step.SUBMIT);
        assertThat(submit.state()).isEqualTo(ChainStageState.NEXT);
        assertThat(submit.chip()).isEqualTo(new ChainStep.Chip("Next", Tone.NEUTRAL));
        assertThat(submit.stateLine()).isEqualTo("Next: Coverage check answered \"PA required\".");

        ChainStep status = step(view, Step.STATUS);
        assertThat(status.state()).isEqualTo(ChainStageState.NOT_REACHED);
        assertThat(status.chip()).isEqualTo(new ChainStep.Chip("Not run", null));
        assertThat(status.stateLine()).isEqualTo("Not reached: Submit comes first.");
    }

    @Test
    void aCoverageCheckThatAsksForDocumentationMakesTheQuestionnaireNext() {
        line(EpaOperation.CRD_ORDER_SIGN, "DOCUMENTATION_REQUIRED", NextAction.START_DTR);
        ChainStep questionnaire = step(chains().view(chainId), Step.QUESTIONNAIRE);
        assertThat(questionnaire.state()).isEqualTo(ChainStageState.NEXT);
        assertThat(questionnaire.stateLine()).isEqualTo("Next: the coverage check asked for documentation.");
        assertThat(questionnaire.nextAction().button()).isEqualTo("Continue to the questionnaire");
    }

    @Test
    void noPriorAuthorizationLeavesEveryLaterStepNotNeededInThosePlainWords() {
        line(EpaOperation.CRD_ORDER_SIGN, "NO_AUTH_REQUIRED", NextAction.FLOW_ENDS);
        ChainView view = chains().view(chainId);
        assertThat(step(view, Step.COVERAGE).chip()).isEqualTo(new ChainStep.Chip("PA not required", Tone.CLEAR));
        for (Step later : List.of(Step.QUESTIONNAIRE, Step.SUBMIT, Step.STATUS)) {
            assertThat(step(view, later).state()).as(later.name()).isEqualTo(ChainStageState.NOT_NEEDED);
            assertThat(step(view, later).stateLine()).as(later.name()).isEqualTo("Not needed: prior authorization is not required.");
            assertThat(step(view, later).chip().tone()).as(later.name()).isNull();
        }
    }

    @Test
    void aQuestionnaireWaitingForAnswersSaysSoInItsChip() {
        line(EpaOperation.CRD_ORDER_SIGN, "DOCUMENTATION_REQUIRED", NextAction.START_DTR);
        String dtr = line(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "PACKAGE_LOADED", NextAction.SUBMIT_RESPONSE);
        ChainStep questionnaire = step(chains().view(chainId), Step.QUESTIONNAIRE);
        assertThat(questionnaire.state()).isEqualTo(ChainStageState.IN_PROGRESS);
        assertThat(questionnaire.chip()).isEqualTo(new ChainStep.Chip("Answer the questions", Tone.ACTION));
        assertThat(questionnaire.latestRunId()).isEqualTo(dtr);
        assertThat(questionnaire.stateLine()).isEqualTo("In progress: The questionnaire package was loaded. Complete the response.");
    }

    @Test
    void aPendedSubmissionMakesStatusNextAndAnInquiryFillsTheStatusStep() {
        line(EpaOperation.CRD_ORDER_SIGN, "AUTH_REQUIRED", NextAction.START_PAS);
        String submit = line(EpaOperation.PAS_SUBMIT, "PENDED", NextAction.CHECK_STATUS);
        ChainView pended = chains().view(chainId);
        assertThat(step(pended, Step.SUBMIT).chip()).isEqualTo(new ChainStep.Chip("Pended", Tone.PENDING));
        assertThat(step(pended, Step.SUBMIT).latestRunId()).isEqualTo(submit);
        assertThat(step(pended, Step.SUBMIT).state()).isEqualTo(ChainStageState.DONE);
        assertThat(step(pended, Step.STATUS).state()).isEqualTo(ChainStageState.NEXT);
        assertThat(step(pended, Step.STATUS).stateLine()).isEqualTo("Next: Submit answered \"Pended\".");
        assertThat(step(pended, Step.STATUS).nextAction().button()).isEqualTo("Send inquiry");

        String inquiry = line(EpaOperation.PAS_INQUIRE, "APPROVED", NextAction.FLOW_ENDS);
        ChainView answered = chains().view(chainId);
        ChainStep status = step(answered, Step.STATUS);
        assertThat(status.latestRunId()).isEqualTo(inquiry);
        assertThat(status.chip()).isEqualTo(new ChainStep.Chip("Approved", Tone.CLEAR));
        assertThat(status.state()).isEqualTo(ChainStageState.DONE);
        // The Submit step keeps its own run: the inquiry belongs to Status.
        assertThat(step(answered, Step.SUBMIT).latestRunId()).isEqualTo(submit);
    }

    @Test
    void aSubscriptionCallAndANotificationBelongToTheStatusStep() {
        line(EpaOperation.PAS_SUBMIT, "PENDED", NextAction.CHECK_STATUS);
        line(EpaOperation.SUBSCRIPTION_CREATE, "CREATED", NextAction.DELIVER_SAMPLE_NOTIFICATION);
        ChainStep created = step(chains().view(chainId), Step.STATUS);
        assertThat(created.chip()).isEqualTo(new ChainStep.Chip("Created", Tone.NEUTRAL));
        assertThat(created.state()).isEqualTo(ChainStageState.IN_PROGRESS);
        assertThat(created.nextAction().button()).isEqualTo("Deliver a sample");

        String notified = line(EpaOperation.SUBSCRIPTION_NOTIFICATION, "STATUS_RECEIVED", NextAction.NOTHING_FURTHER);
        ChainStep status = step(chains().view(chainId), Step.STATUS);
        assertThat(status.latestRunId()).isEqualTo(notified);
        assertThat(status.chip()).isEqualTo(new ChainStep.Chip("Status report", Tone.NEUTRAL));
    }

    @Test
    void aChainStartedAtSubmitSaysTheEarlierStepsWereNotRun() {
        line(EpaOperation.PAS_SUBMIT, "APPROVED", NextAction.FLOW_ENDS);
        ChainView view = chains().view(chainId);
        assertThat(step(view, Step.COVERAGE).state()).isEqualTo(ChainStageState.NOT_RUN);
        assertThat(step(view, Step.COVERAGE).stateLine()).isEqualTo("Not run in this chain. The chain was started at a later step.");
        assertThat(step(view, Step.COVERAGE).chip()).isEqualTo(new ChainStep.Chip("Not run", null));
        assertThat(step(view, Step.STATUS).state()).isEqualTo(ChainStageState.NOT_NEEDED);
        assertThat(step(view, Step.STATUS).stateLine()).isEqualTo("Not needed: the journey ended at Submit with \"Approved\".");
    }

    @Test
    void aStepThatNeedsAttentionKeepsTheOutcomesShortWordsAndTone() {
        line(EpaOperation.CRD_ORDER_SIGN, "MEMBER_NOT_FOUND", NextAction.FIX_TEST_DATA);
        ChainView view = chains().view(chainId);
        ChainStep coverage = step(view, Step.COVERAGE);
        assertThat(coverage.state()).isEqualTo(ChainStageState.NEEDS_ATTENTION);
        assertThat(coverage.chip()).isEqualTo(new ChainStep.Chip("Member not found", Tone.DATA));
        assertThat(step(view, Step.QUESTIONNAIRE).stateLine()).isEqualTo("Not reached: Coverage check has to finish first. It stands at "
                + "\"Member not found\".");
    }

    @Test
    void aCodeThisToolDoesNotKnowIsShownAsWrittenNeverGuessed() {
        line(EpaOperation.CRD_ORDER_SIGN, "SOMETHING_NEWER", NextAction.REVIEW);
        ChainStep coverage = step(chains().view(chainId), Step.COVERAGE);
        assertThat(coverage.chip().text()).isEqualTo("SOMETHING_NEWER");
        assertThat(coverage.chip().tone()).isEqualTo(Tone.NEUTRAL);
    }

    @Test
    void theStagesKeepTheirShapeAndWordsBesideTheSteps() {
        line(EpaOperation.CRD_ORDER_SIGN, "AUTH_REQUIRED", NextAction.START_PAS);
        line(EpaOperation.PAS_SUBMIT, "PENDED", NextAction.CHECK_STATUS);
        line(EpaOperation.PAS_INQUIRE, "PENDED", NextAction.CHECK_STATUS);
        ChainView view = chains().view(chainId);
        assertThat(view.stages()).extracting(ChainStage::protocol).containsExactly(Protocol.CRD, Protocol.DTR, Protocol.PAS);
        assertThat(view.stages().get(1).stateLine()).isEqualTo("Not needed: CRD answered \"Prior authorization is required\" and went "
                + "straight on to PAS.");
        assertThat(view.stages().get(2).runs()).hasSize(2);
        assertThat(view.stages().get(2).stateLine()).isEqualTo("In progress: The authorization is pended: there is no final decision yet. "
                + "Send inquiry.");
        // The Status step holds the inquiry alone, and is in progress for the same reason.
        assertThat(step(view, Step.STATUS).state()).isEqualTo(ChainStageState.IN_PROGRESS);
        assertThat(step(view, Step.SUBMIT).state()).isEqualTo(ChainStageState.DONE);
    }

    @Test
    void theStepsAreOnTheWireAndAListOfChainsKeepsThem() {
        line(EpaOperation.CRD_ORDER_SIGN, "AUTH_REQUIRED", NextAction.START_PAS);
        ChainView view = chains().view(chainId);
        JsonNode json = JsonSafe.mapper().valueToTree(view);
        JsonNode first = json.get("steps").get(0);
        assertThat(Stream.of("step", "name", "protocol", "state", "chip", "stateLine", "latestRunId", "nextAction")
                .allMatch(first::has)).isTrue();
        assertThat(first.get("chip").get("text").asString()).isEqualTo("PA required");
        assertThat(first.get("chip").get("tone").asString()).isEqualTo("ACTION");
        JsonNode notNeeded = json.get("steps").get(1);
        assertThat(notNeeded.has("latestRunId")).isFalse();
        assertThat(notNeeded.get("chip").has("tone")).isFalse();
        assertThat(view.summary().steps()).isEqualTo(view.steps());
        assertThat(chains().list(null, null, null)).singleElement().extracting(ChainView::steps).isEqualTo(view.steps());
    }
}
