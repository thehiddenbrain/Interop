package org.p32h.interop.epa.pave.service.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.CaseWiring;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A chain run as a case, through the real CRD, DTR and PAS services, the mock clients and the
 * scenario files, with a gateway that can be told to refuse or fail one step. Every route the
 * chain can take is driven here, with what the case then says about each step it sent.
 */
class ChainCaseStepTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final EpaProperties EPA = EpaProperties.of(Map.of("mode", "mock"));

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final StepGateway gateway = new StepGateway(FakeGateway.unconfigured());
    private final OutcomeCatalogue outcomes = new CaseWiring().outcomeCatalogue();

    private final org.p32h.interop.epa.pave.service.MemberLookup members = id -> new MemberLookupResult(LookupStatus.NOT_FOUND,
            MemberIds.forms(id), null, List.of(), List.of(), List.of(), null, null, "message");
    private final org.p32h.interop.epa.pave.service.ProviderLookup providers = npi -> new ProviderLookupResult(LookupStatus.FOUND, npi,
            ProviderDetail.builder().npi(npi).lastName("Provider").build(), List.of(), List.of(), List.of(), null, null, "message");

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private ChainCaseStep chain() {
        RunSupport support = new RunSupport(EPA, TrackingProperties.defaults(), OnyxProperties.defaults(), ledgerProvider(), CLOCK);
        ScenarioRepository scenarios = new ScenarioRepository(APP);
        CrdService crd = new CrdService(EPA, gateway, new MockOnyxCrdClient(scenarios, new MockExchanges(APP)), new FakeCodeList(), support);
        DtrService dtr = new DtrService(EPA, gateway, new MockOnyxDtrClient(scenarios, new MockExchanges(APP)), new FakeCodeList(), support);
        PasService pas = new PasService(OnyxProperties.defaults(), gateway, new MockOnyxPasClient(scenarios, new MockExchanges(APP)),
                new FakeCodeList(), support, ledgerProvider());
        return new ChainCaseStep(crd, dtr, pas, outcomes, support);
    }

    private ProtocolCaseRunner runner() {
        return new ProtocolCaseRunner(members, providers).registerChain(chain());
    }

    private static CaseSpec.Builder chainCase(String memberId) {
        return CaseSpec.builder().caseId("chain-case").chain(true).memberId(memberId).npi("1234567893").procedureCodes(List.of("95810"))
                .dateOfService("2026-03-20").suiteRunId("suite-1");
    }

    private List<RunRecord> runLines() {
        return ledger.records().stream().filter(r -> RunRecord.TYPE_RUN.equals(r.recordType())).toList();
    }

    private List<RunRecord> caseLines() {
        return ledger.records().stream().filter(RunRecord::caseLine).toList();
    }

    @Test
    void aFlowThatEndsAtCrdStopsThereAndSaysSo() {
        CaseOutcome outcome = runner().run(chainCase("P32H-NO-AUTH").expected("crd: NO_AUTH_REQUIRED").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(outcome.runs()).extracting(RunRecord::operation).containsExactly(EpaOperation.CRD_ORDER_SIGN);
        assertThat(outcome.reason()).startsWith("The flow ended at CRD.");
        assertThat(outcome.result()).isEqualTo(TestResult.PASS);
    }

    @Test
    void crdThatCallsForASubmissionIsFollowedByPasWithWhatCrdHandedOnAndTheRunItFollowed() {
        CaseOutcome outcome = runner().run(chainCase("P32H-PA-REQ").expected("crd: AUTH_REQUIRED; pas: APPROVED").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(outcome.runs()).extracting(RunRecord::operation).containsExactly(EpaOperation.CRD_ORDER_SIGN, EpaOperation.PAS_SUBMIT);
        RunRecord crd = outcome.runs().get(0);
        RunRecord pas = outcome.runs().get(1);
        // One chain, and the submission names the run it followed, so the ledger line of a step points back to the step before.
        assertThat(pas.chainId()).isEqualTo(crd.chainId()).isNotBlank();
        assertThat(pas.correlation()).containsEntry(RunRecord.CORR_RUN_ID, crd.runId());
        assertThat(pas.procedureCodes()).isEqualTo(crd.procedureCodes());
        assertThat(outcome.result()).isEqualTo(TestResult.PASS);
        assertThat(outcome.reason()).isNull();
    }

    @Test
    void documentationIsFetchedAndTheChainStopsWhereAPersonHasToAnswer() {
        CaseOutcome outcome = runner().run(chainCase("P32H-DOC-DTR").expected("crd: DOCUMENTATION_REQUIRED; dtr: PACKAGE_LOADED").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(outcome.runs()).extracting(RunRecord::operation)
                .containsExactly(EpaOperation.CRD_ORDER_SIGN, EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        assertThat(outcome.runs().get(1).correlation()).containsEntry(RunRecord.CORR_RUN_ID, outcome.runs().get(0).runId());
        assertThat(outcome.reason()).startsWith("The chain stopped at DTR: the questionnaire has to be answered by a person.");
        assertThat(outcome.result()).isEqualTo(TestResult.PASS);
        // Nothing was submitted after a stop at DTR.
        assertThat(runLines()).noneMatch(r -> r.operation() == EpaOperation.PAS_SUBMIT);
    }

    @Test
    void aServiceThatFailsAtCrdStopsTheChainThereWithWhatTheStepSaid() {
        CaseOutcome outcome = runner().run(chainCase("P32H-ERR500").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(outcome.runs()).hasSize(1);
        assertThat(outcome.reason()).startsWith("The chain stopped at CRD.");
        assertThat(outcome.result()).isEqualTo(TestResult.UNVERIFIED);
    }

    @Test
    void aSubmissionTheServiceFailsOnIsTheLastStepAndTheCaseFailsWhenItExpectedApproval() {
        CaseOutcome outcome = runner().run(chainCase("P32H-PAS-ERR500").expected("pas: APPROVED").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.COMPLETED);
        assertThat(outcome.runs()).extracting(RunRecord::operation).containsExactly(EpaOperation.CRD_ORDER_SIGN, EpaOperation.PAS_SUBMIT);
        assertThat(outcome.finalRun().outcome()).isEqualTo("SERVICE_ERROR");
        assertThat(outcome.result()).isEqualTo(TestResult.FAIL);
        assertThat(outcome.reason()).startsWith("The PAS step is not what the row expected:");
    }

    @Test
    void aStepThatFailedItsExpectationIsNamedAndEachStepKeepsItsOwnResult() {
        CaseOutcome outcome = runner().run(chainCase("P32H-PA-REQ").expected("crd: NO_AUTH_REQUIRED; pas: APPROVED").build());
        assertThat(outcome.result()).isEqualTo(TestResult.FAIL);
        // The last step matched; the case failed on the first, and the reason says which.
        assertThat(outcome.finalRun().result()).isEqualTo(TestResult.PASS);
        assertThat(outcome.runs().get(0).result()).isEqualTo(TestResult.FAIL);
        assertThat(outcome.reason()).startsWith("The CRD step is not what the row expected:");
    }

    @Test
    void aCaseThatExpectedAStepTheChainNeverReachedHasFailedAndTheLedgerHoldsThat() {
        CaseOutcome outcome = runner().run(chainCase("P32H-NO-AUTH").expected("crd: NO_AUTH_REQUIRED; pas: APPROVED").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.COMPLETED);
        // Every step that ran passed, and still the case failed: it expected an answer from PAS.
        assertThat(outcome.runs()).allMatch(r -> r.result() == TestResult.PASS);
        assertThat(outcome.result()).isEqualTo(TestResult.FAIL);
        assertThat(outcome.reason()).startsWith("The case expected an answer from PAS, and the chain did not get that far.");
        // The case result is on a line of its own, naming the last step, so a ledger query for failed runs can find it.
        assertThat(caseLines()).singleElement().satisfies(line -> {
            assertThat(line.runId()).isEqualTo(outcome.finalRun().runId());
            assertThat(line.result()).isEqualTo(TestResult.FAIL);
            assertThat(line.expected()).isEqualTo("crd: NO_AUTH_REQUIRED; pas: APPROVED");
            assertThat(line.chainId()).isEqualTo(outcome.finalRun().chainId());
            assertThat(line.suiteRunId()).isEqualTo("suite-1");
            assertThat(line.caseId()).isEqualTo("chain-case");
            assertThat(line.memberId()).isNull();
        });
    }

    @Test
    void aCaseThatExpectedNothingIsUnverifiedAndNeverPasses() {
        CaseOutcome outcome = runner().run(chainCase("P32H-PA-REQ").build());
        assertThat(outcome.result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(caseLines()).singleElement().extracting(RunRecord::result).isEqualTo(TestResult.UNVERIFIED);
    }

    @Test
    void aSubmissionTheGuardrailBlocksKeepsTheCrdStepThatWentOutAndNeverSaysNothingWasSent() {
        gateway.failing(EpaOperation.PAS_SUBMIT, new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT,
                new EnvironmentRef("Onyx Production", EnvironmentKind.PROD), "Writes to the production environment Onyx Production are blocked.",
                "Unlock writes on the Config tab, or switch environment."));
        CaseOutcome outcome = runner().run(chainCase("P32H-PA-REQ").expected("crd: AUTH_REQUIRED; pas: APPROVED").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(outcome.runs()).extracting(RunRecord::outcome).containsExactly("AUTH_REQUIRED", "BLOCKED");
        assertThat(outcome.reason()).isEqualTo("CRD order-sign was sent; PAS submit was blocked by the production guardrail, so it was not sent.");
        assertThat(outcome.reason()).doesNotContain("Nothing was sent");
        // A blocked case has no result, so no case line claims one.
        assertThat(caseLines()).isEmpty();
    }

    @Test
    void aStepRefusedAfterCrdWentOutKeepsCrdAndSaysWhichWasSentAndWhichWasNot() {
        gateway.failing(EpaOperation.PAS_SUBMIT, new ConfigurationChangedException());
        CaseOutcome outcome = runner().run(chainCase("P32H-PA-REQ").configEpoch(0L).build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.NOT_CONFIGURED);
        assertThat(outcome.runs()).extracting(RunRecord::operation).containsExactly(EpaOperation.CRD_ORDER_SIGN);
        assertThat(outcome.reason()).isEqualTo("CRD order-sign was sent; PAS submit was not sent. " + CaseFaults.CONFIGURATION_MOVED);
        assertThat(outcome.toolFailed()).isFalse();
        // The CRD line is in the ledger under the suite, as it went out.
        assertThat(runLines()).singleElement().extracting(RunRecord::suiteRunId).isEqualTo("suite-1");

        ledger.records().clear();
        gateway.failing(EpaOperation.PAS_SUBMIT, ValidationException.of("provider.npi", "Enter the ordering provider's NPI."));
        CaseOutcome invalid = runner().run(chainCase("P32H-PA-REQ").build());
        assertThat(invalid.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(invalid.runs()).hasSize(1);
        assertThat(invalid.reason()).isEqualTo("CRD order-sign was sent; PAS submit was not sent. npi: Enter the ordering provider's NPI.");
    }

    @Test
    void aToolFailureAfterCrdKeepsCrdAndSaysWhetherTheNextRequestLeftIsNotKnown() {
        gateway.failing(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, new IllegalStateException("a defect"));
        CaseOutcome outcome = runner().run(chainCase("P32H-DOC-DTR").build());
        assertThat(outcome.status()).isEqualTo(CaseStatus.NOT_RUNNABLE);
        assertThat(outcome.toolFailed()).isTrue();
        assertThat(outcome.runs()).extracting(RunRecord::operation).containsExactly(EpaOperation.CRD_ORDER_SIGN);
        assertThat(outcome.reason()).startsWith("CRD order-sign was sent; this tool then failed on DTR questionnaire package, so whether "
                + "that request left is not known").endsWith("Reference " + outcome.faultReference() + ".");
        // The lookup note says the request went out with the ID alone, because one did.
        assertThat(outcome.notes()).contains("The member lookup does not know this member ID. The request was sent with the member ID alone.");
    }

    @Test
    void whatTheChainCannotDoIsRefusedBeforeAnythingIsSent() {
        CaseOutcome scenario = runner().run(chainCase("P32H-PA-REQ").scenarioId("crd-01-pa-required").build());
        assertThat(scenario.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(scenario.reason()).contains("scenarioId: " + ChainCaseStep.SCENARIO_REFUSED);
        CaseOutcome operation = runner().run(chainCase("P32H-PA-REQ").operation(EpaOperation.PAS_SUBMIT).build());
        assertThat(operation.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(operation.reason()).contains("operation: " + ChainCaseStep.OPERATION_REFUSED);
        CaseOutcome expectation = runner().run(chainCase("P32H-PA-REQ").expected("APPROVED").build());
        assertThat(expectation.status()).isEqualTo(CaseStatus.INVALID);
        assertThat(expectation.reason()).contains("does not say which step of the chain it is about");
        assertThat(ledger.records()).isEmpty();
        // Nothing sent, so no note says anything was.
        assertThat(scenario.notes()).containsExactly("The member lookup does not know this member ID.");
    }

    @Test
    void theDefaultMockScenarioIsNamedOnTheCaseAsOnAManualRun() {
        CaseOutcome outcome = runner().run(chainCase("P32H-PA-REQ").build());
        // CRD was picked by its sentinel; PAS had no sentinel for this member and answered from its default.
        assertThat(outcome.notes()).anyMatch(note -> note.startsWith("PAS submit: Mock scenario pas-01-approved answered: the default"));
        assertThat(outcome.notes()).noneMatch(note -> note.startsWith("CRD order-sign: Mock scenario"));
    }

    @Test
    void aLedgerThatCannotKeepTheCaseLineSaysSoOnTheCase() {
        ledger.refusing("The ledger file could not be written.");
        CaseOutcome outcome = runner().run(chainCase("P32H-NO-AUTH").expected("crd: NO_AUTH_REQUIRED").build());
        assertThat(outcome.notRecorded()).contains("The ledger file could not be written.",
                "The result of this case as a whole was NOT recorded in the ledger, so History cannot show it: The ledger file could not be written.");
    }

    @Test
    void theChainStartsWithTheHookTheCaseNames() {
        CaseOutcome outcome = runner().run(chainCase("P32H-NO-AUTH").operation(EpaOperation.CRD_ORDER_SELECT).build());
        assertThat(outcome.runs()).extracting(RunRecord::operation).containsExactly(EpaOperation.CRD_ORDER_SELECT);
    }
}
