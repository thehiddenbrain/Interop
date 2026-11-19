package org.p32h.interop.epa.pave.service.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.chain.ChainRun;
import org.p32h.interop.epa.pave.model.chain.ChainStage;
import org.p32h.interop.epa.pave.model.chain.ChainStageState;
import org.p32h.interop.epa.pave.model.chain.ChainView;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.cases.OutcomeCatalogue;
import org.p32h.interop.epa.pave.service.crd.CrdCodes;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A chain read back from the ledger. The CRD runs are made by the real CRD service against the
 * mock scenario files, so their records are the ones the application writes. The DTR and PAS runs
 * are ledger lines written by hand, since the chain view reads lines and nothing else.
 */
class ChainServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String SURNAME = "Placeholder-Surname";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private int stamp;

    private CrdService crd() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        RunSupport support = new RunSupport(epa, TrackingProperties.of(Map.of("record-member-id", "true")), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
        return new CrdService(epa, gateway, new MockOnyxCrdClient(new ScenarioRepository(APP), new MockExchanges(APP)),
                new FakeCodeList(), support);
    }

    private ChainService chains() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new ChainService(beans.getBeanProvider(RunLedger.class),
                new OutcomeCatalogue().register(Protocol.CRD, CrdOutcome.values(), CrdCodes.EXPECTATION_ELEMENTS));
    }

    /** Runs CRD for a mock member and returns the chain id the run carries. */
    private String crdRun(String memberId, String chainId, String expected) {
        return crd().run(CrdRunRequest.builder()
                .member(MemberDetail.builder().memberId(memberId).lastName(SURNAME).dateOfBirth("1970-01-31").lineOfBusiness("Medicare").build())
                .provider(ProviderDetail.builder().npi("1234567893").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended")))
                .dateOfService("2026-03-20").chainId(chainId).expected(expected).build(), RunOrigin.manual(), null).chainId();
    }

    /** A ledger line for a later stage, written as that stage's service would write it. */
    private RunRecord later(String chainId, EpaOperation operation, String outcome, NextAction next, String memberId) {
        RunRecord record = RunRecord.builder().runId(RunStamps.newRunId()).timestamp("2026-03-10T10:0" + (++stamp) + ":00.000-05:00")
                .protocol(operation.protocol()).operation(operation).target("onyx").profile("mock").outcome(outcome)
                .result(TestResult.UNVERIFIED).httpStatus(200).elapsedMillis(40).chainId(chainId).nextAction(next).memberId(memberId)
                .procedureCodes(List.of("95810")).build();
        ledger.record(record);
        return record;
    }

    private static ChainStage stage(ChainView view, Protocol protocol) {
        return view.stages().stream().filter(s -> s.protocol() == protocol).findFirst().orElseThrow();
    }

    @Test
    void aChainThatEndsAtCrdSaysSoAndSaysTheLaterStagesWereNotNeeded() {
        String chainId = crdRun("P32H-NO-AUTH", "new", "NO_AUTH_REQUIRED");
        ChainView view = chains().view(chainId);

        assertThat(view.chainId()).isEqualTo(chainId);
        assertThat(view.headline()).isEqualTo("Coverage requirements (CRD): Prior authorization is not required. The journey ends here.");
        assertThat(view.ended()).isTrue();
        assertThat(view.nextAction()).isEqualTo(NextAction.FLOW_ENDS);
        assertThat(view.nextHeadline()).isEqualTo("Nothing more to do. The journey is complete at this step.");
        assertThat(view.nextStage()).isNull();
        assertThat(view.runCount()).isEqualTo(1);
        assertThat(view.member()).isEqualTo("P32H-NO-AUTH");
        assertThat(view.procedureCodes()).containsExactly("95810");
        assertThat(view.profile()).isEqualTo("mock");
        assertThat(view.target()).isEqualTo("onyx");
        assertThat(view.startedAt()).isEqualTo(view.lastRunAt());
        assertThat(view.notes()).isEmpty();

        assertThat(view.stages()).extracting(ChainStage::protocol).containsExactly(Protocol.CRD, Protocol.DTR, Protocol.PAS);
        ChainStage crd = stage(view, Protocol.CRD);
        assertThat(crd.name()).isEqualTo("Coverage requirements (CRD)");
        assertThat(crd.state()).isEqualTo(ChainStageState.DONE);
        assertThat(crd.stateLine()).isEqualTo("Done: Prior authorization is not required. The journey ends here.");
        ChainRun run = crd.latest();
        assertThat(run.runId()).isEqualTo(ledger.only().runId());
        assertThat(run.what()).isEqualTo("CRD order-sign");
        // The outcome and the test result are separate, here as everywhere.
        assertThat(run.outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(run.outcomeLabel()).isEqualTo("Prior authorization is not required");
        assertThat(run.tone()).isEqualTo(Tone.CLEAR);
        assertThat(run.expected()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(run.result()).isEqualTo(TestResult.PASS);
        assertThat(run.httpStatus()).isEqualTo(200);
        assertThat(run.elapsedMillis()).isNotNull();
        assertThat(run.latestOfItsKind()).isTrue();
        assertThat(crd.runs()).containsExactly(run);

        // A stage that did not run says why. "Not needed" is a different fact from "not reached yet".
        assertThat(stage(view, Protocol.DTR).state()).isEqualTo(ChainStageState.NOT_NEEDED);
        assertThat(stage(view, Protocol.DTR).stateLine())
                .isEqualTo("Not needed: the journey ended at CRD with \"Prior authorization is not required\".");
        assertThat(stage(view, Protocol.DTR).latest()).isNull();
        assertThat(stage(view, Protocol.PAS).state()).isEqualTo(ChainStageState.NOT_NEEDED);
    }

    @Test
    void noAuthAndNoDeterminationAreTwoDifferentChains() {
        ChainView noAuth = chains().view(crdRun("P32H-NO-AUTH", "new", null));
        ChainView empty = chains().view(crdRun("P32H-EMPTY", "new", null));
        assertThat(noAuth.ended()).isTrue();
        // No determination does not end the journey. Nobody knows yet whether authorization is needed.
        assertThat(empty.ended()).isFalse();
        assertThat(empty.headline()).isEqualTo("Coverage requirements (CRD): The service returned no determination. "
                + "Next: look at the response detail. The tool cannot say what should happen next.");
        assertThat(stage(empty, Protocol.CRD).state()).isEqualTo(ChainStageState.NEEDS_ATTENTION);
        assertThat(stage(empty, Protocol.DTR).state()).isEqualTo(ChainStageState.NOT_REACHED);
        assertThat(stage(empty, Protocol.DTR).stateLine())
                .isEqualTo("Not reached: CRD has to finish first. It stands at \"The service returned no determination\".");
        assertThat(stage(noAuth, Protocol.DTR).state()).isEqualTo(ChainStageState.NOT_NEEDED);
    }

    @Test
    void whenCrdAsksForDocumentationTheChainSaysDtrIsNext() {
        ChainView view = chains().view(crdRun("P32H-DOC-DTR", "new", null));
        assertThat(view.ended()).isFalse();
        assertThat(view.nextAction()).isEqualTo(NextAction.START_DTR);
        assertThat(view.nextStage()).isEqualTo(Protocol.DTR);
        assertThat(view.nextHeadline()).isEqualTo("Answer the payer's questionnaire (DTR).");
        assertThat(view.headline()).endsWith("Next: answer the payer's questionnaire (DTR).");
        assertThat(stage(view, Protocol.CRD).state()).isEqualTo(ChainStageState.DONE);
        assertThat(stage(view, Protocol.DTR).state()).isEqualTo(ChainStageState.NEXT);
        assertThat(stage(view, Protocol.DTR).stateLine()).isEqualTo("Next: answer the payer's questionnaire (DTR).");
        assertThat(stage(view, Protocol.PAS).state()).isEqualTo(ChainStageState.NOT_REACHED);
    }

    @Test
    void whenCrdGoesStraightToPasTheChainSaysDtrWasNotNeeded() {
        ChainView view = chains().view(crdRun("P32H-PA-REQ", "new", null));
        assertThat(view.nextStage()).isEqualTo(Protocol.PAS);
        assertThat(stage(view, Protocol.DTR).state()).isEqualTo(ChainStageState.NOT_NEEDED);
        assertThat(stage(view, Protocol.DTR).stateLine())
                .isEqualTo("Not needed: CRD answered \"Prior authorization is required\" and went straight on to PAS.");
        assertThat(stage(view, Protocol.PAS).state()).isEqualTo(ChainStageState.NEXT);
        assertThat(stage(view, Protocol.PAS).stateLine()).isEqualTo("Next: submit the prior authorization request (PAS).");
    }

    @Test
    void theWholeJourneyIsOneChainWithEachStageWhereItStands() {
        String chainId = crdRun("P32H-DOC-DTR", "new", null);
        later(chainId, EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "QUESTIONNAIRE_LOADED", NextAction.SUBMIT_RESPONSE, "P32H-DOC-DTR");
        ChainView answering = chains().view(chainId);
        assertThat(stage(answering, Protocol.DTR).state()).isEqualTo(ChainStageState.IN_PROGRESS);
        // The DTR outcomes are not registered here, so the code is shown as written, never guessed.
        assertThat(stage(answering, Protocol.DTR).stateLine()).isEqualTo("In progress: QUESTIONNAIRE_LOADED. "
                + "Answer the questionnaire that was loaded and submit the response (DTR).");
        assertThat(stage(answering, Protocol.PAS).state()).isEqualTo(ChainStageState.NOT_REACHED);
        assertThat(answering.nextStage()).isEqualTo(Protocol.DTR);

        later(chainId, EpaOperation.DTR_RESPONSE, "RESPONSE_COMPLETE", NextAction.START_PAS, "P32H-DOC-DTR");
        RunRecord pended = later(chainId, EpaOperation.PAS_SUBMIT, "PENDED", NextAction.CHECK_STATUS, "P32H-DOC-DTR");
        ChainView waiting = chains().view(chainId);
        assertThat(stage(waiting, Protocol.DTR).state()).isEqualTo(ChainStageState.DONE);
        assertThat(stage(waiting, Protocol.DTR).runs()).hasSize(2);
        assertThat(stage(waiting, Protocol.PAS).state()).isEqualTo(ChainStageState.IN_PROGRESS);
        assertThat(stage(waiting, Protocol.PAS).latest().runId()).isEqualTo(pended.runId());
        assertThat(waiting.nextAction()).isEqualTo(NextAction.CHECK_STATUS);
        assertThat(waiting.ended()).isFalse();

        later(chainId, EpaOperation.PAS_INQUIRE, "APPROVED", NextAction.FLOW_ENDS, "P32H-DOC-DTR");
        ChainView done = chains().view(chainId);
        assertThat(done.ended()).isTrue();
        assertThat(done.headline()).isEqualTo("Prior authorization request (PAS): APPROVED. The journey ends here.");
        assertThat(done.stages()).extracting(ChainStage::state)
                .containsExactly(ChainStageState.DONE, ChainStageState.DONE, ChainStageState.DONE);
        assertThat(done.runCount()).isEqualTo(5);
        assertThat(stage(done, Protocol.PAS).runs()).extracting(ChainRun::what).containsExactly("PAS submit", "PAS inquire");
        assertThat(done.elapsedMillis()).isGreaterThanOrEqualTo(160);
        assertThat(done.member()).isEqualTo("P32H-DOC-DTR");
        assertThat(done.notes()).isEmpty();
    }

    @Test
    void aStepRunAgainLeavesTheEarlierRunInTheChainAndTheLatestOneCounts() {
        String chainId = crdRun("P32H-PA-REQ", "new", null);
        later(chainId, EpaOperation.PAS_SUBMIT, "REJECTED", NextAction.FIX_REQUEST, "P32H-PA-REQ");
        ChainView failed = chains().view(chainId);
        assertThat(stage(failed, Protocol.PAS).state()).isEqualTo(ChainStageState.NEEDS_ATTENTION);
        assertThat(stage(failed, Protocol.PAS).stateLine())
                .isEqualTo("Needs attention: REJECTED. Check what was sent. The service did not accept the request.");

        // The tester fixes the request and runs PAS again, without running CRD again.
        later(chainId, EpaOperation.PAS_SUBMIT, "APPROVED", NextAction.FLOW_ENDS, "P32H-PA-REQ");
        ChainView fixed = chains().view(chainId);
        ChainStage pas = stage(fixed, Protocol.PAS);
        assertThat(pas.state()).isEqualTo(ChainStageState.DONE);
        assertThat(pas.runs()).hasSize(2);
        assertThat(pas.runs().get(0).latestOfItsKind()).isFalse();
        assertThat(pas.runs().get(1).latestOfItsKind()).isTrue();
        assertThat(pas.latest().outcome()).isEqualTo("APPROVED");
        assertThat(stage(fixed, Protocol.CRD).runs()).hasSize(1);
        assertThat(fixed.ended()).isTrue();
    }

    @Test
    void aReRunOfAnEarlyStepIsWhereTheJourneyStandsNow() {
        String chainId = crdRun("P32H-PA-REQ", "new", null);
        later(chainId, EpaOperation.PAS_SUBMIT, "APPROVED", NextAction.FLOW_ENDS, "P32H-PA-REQ");
        // CRD is asked again afterwards and now fails.
        crdRun("P32H-ERR500", chainId, null);
        ChainView view = chains().view(chainId);
        assertThat(view.ended()).isFalse();
        assertThat(view.nextAction()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(view.nextStage()).isEqualTo(Protocol.CRD);
        assertThat(stage(view, Protocol.CRD).state()).isEqualTo(ChainStageState.NEEDS_ATTENTION);
        assertThat(stage(view, Protocol.CRD).runs()).extracting(ChainRun::latestOfItsKind).containsExactly(false, true);
        // The PAS run is still in the chain, as it happened.
        assertThat(stage(view, Protocol.PAS).state()).isEqualTo(ChainStageState.DONE);
        // Two members in one chain is something a person should know about.
        assertThat(view.member()).isNull();
        assertThat(view.notes()).singleElement().asString().startsWith("The runs of this chain are not all for the same member.");
    }

    @Test
    void aChainStartedFurtherAlongSaysTheEarlierStagesWereNotRun() {
        String chainId = RunStamps.newChainId();
        later(chainId, EpaOperation.PAS_SUBMIT, "APPROVED", NextAction.FLOW_ENDS, "P32H-PA-REQ");
        ChainView view = chains().view(chainId);
        assertThat(stage(view, Protocol.CRD).state()).isEqualTo(ChainStageState.NOT_RUN);
        assertThat(stage(view, Protocol.CRD).stateLine()).isEqualTo("Not run in this chain. The chain was started at a later step.");
        assertThat(stage(view, Protocol.DTR).state()).isEqualTo(ChainStageState.NOT_RUN);
        assertThat(stage(view, Protocol.PAS).state()).isEqualTo(ChainStageState.DONE);
    }

    @Test
    void aBlockedRunNeedsTheGuardrailDealtWithAndIsNotAFailure() {
        gateway.failing(new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT,
                new org.p32h.interop.epa.pave.model.EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment."));
        ChainView view = chains().view(crdRun("P32H-NO-AUTH", "new", "NO_AUTH_REQUIRED"));
        ChainStage crd = stage(view, Protocol.CRD);
        assertThat(crd.state()).isEqualTo(ChainStageState.NEEDS_ATTENTION);
        assertThat(crd.latest().outcome()).isEqualTo("BLOCKED");
        assertThat(crd.latest().tone()).isEqualTo(Tone.BLOCKED);
        assertThat(crd.latest().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(crd.latest().nextAction()).isEqualTo(NextAction.UNLOCK_OR_SWITCH);
        assertThat(crd.stateLine()).isEqualTo("Needs attention: Blocked: nothing was sent. Unlock production writes or switch environment. "
                + "The guardrail refused to send.");
        assertThat(view.ended()).isFalse();
    }

    @Test
    void aRecordThatDoesNotSayWhatIsNextIsShownAsSuchAndNothingIsInferred() {
        String chainId = RunStamps.newChainId();
        later(chainId, EpaOperation.CRD_ORDER_SIGN, "NO_AUTH_REQUIRED", null, "P32H-NO-AUTH");
        ChainView view = chains().view(chainId);
        assertThat(view.nextAction()).isNull();
        assertThat(view.ended()).isFalse();
        assertThat(view.headline()).isEqualTo("Coverage requirements (CRD): Prior authorization is not required. "
                + "The record does not say what should happen next.");
        assertThat(stage(view, Protocol.CRD).stateLine())
                .isEqualTo("Ran: Prior authorization is not required. The record does not say what should happen next.");
        // The outcome is not used to guess that the journey ended.
        assertThat(stage(view, Protocol.DTR).state()).isEqualTo(ChainStageState.NOT_REACHED);
    }

    @Test
    void runsThatBelongToNoStageAreListedApart() {
        String chainId = crdRun("P32H-PA-REQ", "new", null);
        later(chainId, EpaOperation.PAS_SUBMIT, "PENDED", NextAction.CHECK_STATUS, "P32H-PA-REQ");
        RunRecord notification = later(chainId, EpaOperation.SUBSCRIPTION_NOTIFICATION, "RECEIVED", NextAction.CHECK_STATUS, "P32H-PA-REQ");
        ChainView view = chains().view(chainId);
        assertThat(view.otherRuns()).extracting(ChainRun::runId).containsExactly(notification.runId());
        assertThat(view.runCount()).isEqualTo(3);
        // The stage it relates to still stands where its own last run left it.
        assertThat(stage(view, Protocol.PAS).state()).isEqualTo(ChainStageState.IN_PROGRESS);
        assertThat(view.nextStage()).isEqualTo(Protocol.PAS);
    }

    @Test
    void theViewHoldsNothingAboutTheMemberBeyondWhatTheLedgerLineHolds() {
        String chainId = crdRun("P32H-DOC-DTR", "new", null);
        String json = JsonSafe.mapper().writeValueAsString(chains().view(chainId));
        assertThat(json).doesNotContain(SURNAME).doesNotContain("1970-01-31");
        // When the ledger keeps a token in place of the ID, the chain shows the token.
        String other = RunStamps.newChainId();
        ledger.record(RunRecord.builder().runId(RunStamps.newRunId()).timestamp("2026-03-10T11:00:00.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome("NO_AUTH_REQUIRED").result(TestResult.UNVERIFIED).chainId(other)
                .memberToken("m-4f2a9c").memberIdLast4("6789").nextAction(NextAction.FLOW_ENDS).build());
        assertThat(chains().view(other).member()).isEqualTo("token m-4f2a9c, ending 6789");
    }

    @Test
    void aChainNobodyRecordedIsNotFound() {
        crdRun("P32H-NO-AUTH", "new", null);
        for (String id : new String[] {RunStamps.newChainId(), "chain-1", "", null, "  "}) {
            assertThatThrownBy(() -> chains().view(id)).isInstanceOf(NotFoundException.class)
                    .hasMessageStartingWith("The ledger holds no run of a chain with that id.");
        }
        // With no ledger at all the answer is the same, and it points at the ledger.
        ChainService noLedger = new ChainService(new StaticListableBeanFactory().getBeanProvider(RunLedger.class), new OutcomeCatalogue());
        assertThatThrownBy(() -> noLedger.view(RunStamps.newChainId())).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("test run ledger");
        assertThat(noLedger.list(null, null, null)).isEmpty();
    }

    @Test
    void theListHasOneRowPerChainMostRecentFirstWithoutTheRuns() {
        String first = crdRun("P32H-NO-AUTH", "new", null);
        String second = crdRun("P32H-DOC-DTR", "new", null);
        // A run outside any chain is not a chain.
        crdRun("P32H-PA-REQ", null, null);
        later(first, EpaOperation.CRD_ORDER_SIGN, "NO_AUTH_REQUIRED", NextAction.FLOW_ENDS, "P32H-NO-AUTH");

        List<ChainView> chains = chains().list(null, null, null);
        assertThat(chains).extracting(ChainView::chainId).containsExactly(first, second);
        ChainView row = chains.get(0);
        assertThat(row.runCount()).isEqualTo(2);
        assertThat(row.headline()).endsWith("The journey ends here.");
        assertThat(row.stages()).hasSize(3);
        // The row keeps where each stage stands and its latest run, and leaves the full run lists out.
        assertThat(stage(row, Protocol.CRD).state()).isEqualTo(ChainStageState.DONE);
        assertThat(stage(row, Protocol.CRD).latest()).isNotNull();
        assertThat(stage(row, Protocol.CRD).runs()).isEmpty();
        assertThat(stage(chains.get(1), Protocol.DTR).state()).isEqualTo(ChainStageState.NEXT);

        assertThat(chains().list(null, null, 1)).extracting(ChainView::chainId).containsExactly(first);
        assertThat(chains().list(LocalDate.of(2026, 3, 11), null, null)).isEmpty();
        assertThat(chains().list(LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10), null)).hasSize(2);
    }

    @Test
    void aChainIsReadWholeEvenWhenOnlyPartOfItFallsInTheDateRange() {
        String chainId = RunStamps.newChainId();
        ledger.record(RunRecord.builder().runId(RunStamps.newRunId()).timestamp("2026-03-09T23:50:00.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome("AUTH_REQUIRED").result(TestResult.UNVERIFIED).chainId(chainId)
                .nextAction(NextAction.START_PAS).build());
        ledger.record(RunRecord.builder().runId(RunStamps.newRunId()).timestamp("2026-03-10T00:10:00.000-05:00").protocol(Protocol.PAS)
                .operation(EpaOperation.PAS_SUBMIT).outcome("APPROVED").result(TestResult.UNVERIFIED).chainId(chainId)
                .nextAction(NextAction.FLOW_ENDS).build());
        // Asked for the first day only, the chain is still stated as it stands: it went on to PAS the next day.
        ChainView row = chains().list(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 9), null).get(0);
        assertThat(row.runCount()).isEqualTo(2);
        assertThat(row.ended()).isTrue();
        assertThat(stage(row, Protocol.PAS).state()).isEqualTo(ChainStageState.DONE);
    }

    @Test
    void aListRequestThatMakesNoSenseIsRefused() {
        assertThatThrownBy(() -> chains().list(LocalDate.of(2026, 3, 11), LocalDate.of(2026, 3, 10), null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> chains().list(null, null, 0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> chains().list(null, null, ChainService.MAX_LIMIT + 1)).isInstanceOf(ValidationException.class);
    }

    @Test
    void everyNextActionHasAnInstructionInPlainWords() {
        for (NextAction action : NextAction.values()) {
            assertThat(ChainService.instruction(action)).as(action.name()).isNotBlank().endsWith(".");
        }
    }
}
