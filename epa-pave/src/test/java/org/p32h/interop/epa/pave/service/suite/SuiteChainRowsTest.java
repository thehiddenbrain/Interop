package org.p32h.interop.epa.pave.service.suite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.suite.SuiteCaseResult;
import org.p32h.interop.epa.pave.model.suite.SuiteRunView;
import org.p32h.interop.epa.pave.model.suite.SuiteState;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.ledger.LedgerFixtures;

/**
 * Chain rows and single rows of a suite, read as a tester reads them: each row says what was sent
 * and what was not, which step failed, and how the case compares with an earlier run.
 */
class SuiteChainRowsTest {

    private static final String HEADER = "caseId,protocol,memberId,npi,codes,expected";
    private static final String NPI = "1234567893";

    private final SuiteTestBed bed = new SuiteTestBed();

    @TempDir
    Path home;

    private static String chainRow(String caseId, String memberId, String expected) {
        return caseId + ",chain," + memberId + "," + NPI + ",95810," + expected;
    }

    private SuiteRunView run(String... rows) {
        return bed.service().start("chains.csv", HEADER + "\n" + String.join("\n", rows), null, null, null);
    }

    @Test
    void aChainBlockedAtItsSubmissionSaysCrdWasSentAndNeverThatNothingWas() {
        bed.gateway.environment("Onyx Production", EnvironmentKind.PROD);
        bed.steps.failing(EpaOperation.PAS_SUBMIT, new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT,
                new EnvironmentRef("Onyx Production", EnvironmentKind.PROD), "Writes to the production environment Onyx Production are blocked.",
                "Unlock writes on the Config tab, or switch environment."));
        SuiteCaseResult row = run(chainRow("P1", "P32H-PA-REQ", "crd: AUTH_REQUIRED; pas: APPROVED")).results().get(0);
        assertThat(row.status()).isEqualTo(CaseStatus.BLOCKED);
        assertThat(row.statusLine()).isEqualTo("Blocked part way: CRD order-sign was sent; the production guardrail refused the next step, "
                + "a write, so it was not sent. This is not a failure.");
        assertThat(row.reason()).isEqualTo("CRD order-sign was sent; PAS submit was blocked by the production guardrail, so it was not sent.");
        assertThat(row.statusLine() + row.reason()).doesNotContain("Nothing was sent");
        assertThat(row.runIds()).hasSize(2);
        // The CRD call took time and went out; the blocked submission took none.
        assertThat(row.elapsedMillis()).isNotNull();
        assertThat(row.result()).isNull();
    }

    @Test
    void aChainStoppedAfterCrdWentOutKeepsCrdAndIsCountedApartFromRowsThatSentNothing() {
        bed.steps.failing(EpaOperation.PAS_SUBMIT, new ConfigurationChangedException());
        SuiteRunView run = run(chainRow("SW1", "P32H-PA-REQ", ""), "TC-2,crd,P32H-NO-AUTH," + NPI + ",95810,");
        SuiteCaseResult row = run.results().get(0);
        assertThat(row.status()).isEqualTo(CaseStatus.NOT_CONFIGURED);
        assertThat(row.statusLine()).isEqualTo("Stopped part way: CRD order-sign was sent; the next step was not sent. The reason says why.");
        assertThat(row.reason()).startsWith("CRD order-sign was sent; PAS submit was not sent.");
        assertThat(row.runIds()).hasSize(1);
        assertThat(row.outcome()).isEqualTo("AUTH_REQUIRED");
        assertThat(run.counts().partly()).isEqualTo(1);
        assertThat(run.counts().notConfigured()).isZero();
        assertThat(run.headline()).isEqualTo("Finished: 2 cases. 1 unverified, 1 stopped part way.");
        // The CRD line is in the ledger under the suite.
        assertThat(bed.ledger.records()).filteredOn(r -> RunRecord.TYPE_RUN.equals(r.recordType())).extracting(RunRecord::caseId)
                .contains("SW1");
    }

    @Test
    void aChainThatFailedOnADeterminationIsNotCountedAsAFaultBecauseItsLastStepTimedOut() {
        // CRD answers that authorization is required, which is not what the row expected; PAS then times out.
        SuiteRunView run = run(chainRow("x2", "P32H-PAS-TIMEOUT", "crd: NO_AUTH_REQUIRED"),
                chainRow("x4", "P32H-PAS-TIMEOUT", "crd: AUTH_REQUIRED; pas: APPROVED"));
        SuiteCaseResult determination = run.results().get(0);
        assertThat(determination.result()).isEqualTo(TestResult.FAIL);
        assertThat(determination.steps()).extracting(SuiteCaseResult.Step::outcome).containsExactly("AUTH_REQUIRED", "TIMEOUT");
        assertThat(determination.outcomeFault()).isFalse();
        // Here the step that failed is the one that timed out, so the failure is the service's.
        SuiteCaseResult fault = run.results().get(1);
        assertThat(fault.result()).isEqualTo(TestResult.FAIL);
        assertThat(fault.outcomeFault()).isTrue();
        assertThat(run.counts().failedByFault()).isEqualTo(1);
        assertThat(run.headline()).isEqualTo("Finished: 2 cases. 2 failed (1 of them because the service failed or did not answer).");
    }

    @Test
    void aLedgerThatKeepsNothingIsCountedRunByRunAndTheCaseLineApart() {
        bed.ledger.refusing("The ledger file could not be written.");
        // Three runs in all: CRD and PAS for the chain, and the single CRD row.
        SuiteRunView run = run(chainRow("n1", "P32H-PA-REQ", "crd: AUTH_REQUIRED"), "n2,crd,P32H-NO-AUTH," + NPI + ",95810,");
        assertThat(run.counts().notRecorded()).isEqualTo(3);
        assertThat(run.notes()).contains("3 runs were NOT recorded in the ledger. The rows say which, and why. Their results are shown here "
                        + "and will not be in History or in the ledger export.",
                "The result as a whole of 1 chain case was NOT recorded in the ledger, so History cannot show it. The rows say which, and why.");
        assertThat(run.results().get(0).notRecorded()).hasSize(3);
    }

    @Test
    void aChainThatFailedOnAnEarlyStepNamesItAndShowsEachStepsOutcome() {
        SuiteCaseResult row = run(chainRow("E03", "P32H-PA-REQ", "crd: NO_AUTH_REQUIRED; pas: APPROVED")).results().get(0);
        assertThat(row.result()).isEqualTo(TestResult.FAIL);
        assertThat(row.statusLine()).isEqualTo("Failed: the journey is not what the row expected. The reason names the step.");
        assertThat(row.reason()).startsWith("The CRD step is not what the row expected:");
        assertThat(row.steps()).extracting(SuiteCaseResult.Step::what, SuiteCaseResult.Step::outcome, SuiteCaseResult.Step::result)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("CRD order-sign", "AUTH_REQUIRED", TestResult.FAIL),
                        org.assertj.core.groups.Tuple.tuple("PAS submit", "APPROVED", TestResult.PASS));
    }

    @Test
    void aToolFailurePartWayIsItsOwnBucketWithItsReference() {
        bed.steps.failing(EpaOperation.PAS_SUBMIT, new IllegalStateException("a defect"));
        SuiteRunView run = run(chainRow("T1", "P32H-PA-REQ", ""));
        SuiteCaseResult row = run.results().get(0);
        assertThat(row.faultReference()).isNotBlank();
        assertThat(row.statusLine()).isEqualTo("Tool failure part way: CRD order-sign was sent; this tool then failed, so whether the next "
                + "request left is not known. The reason gives the reference.");
        assertThat(run.counts().toolFailed()).isEqualTo(1);
        assertThat(run.counts().notRunnable()).isZero();
        assertThat(run.notes()).anyMatch(note -> note.startsWith("1 case ended in a failure of this tool."));
    }

    @Test
    void aRowSaysWhenTheDefaultMockScenarioAnswered() {
        SuiteCaseResult row = run("M06,pas,P32H-NOT-KNOWN," + NPI + ",95810,").results().get(0);
        assertThat(row.notes()).contains("Mock scenario pas-01-approved answered: the default, because no scenario was chosen and no "
                + "test member ID or NPI matched.");
    }

    @Test
    void failuresBecauseTheServiceFailedAreToldApartFromDeterminations() {
        SuiteRunView run = run("C6,crd,P32H-ERR500," + NPI + ",95810,NO_AUTH_REQUIRED", "C7,crd,P32H-PA-REQ," + NPI + ",95810,NO_AUTH_REQUIRED");
        assertThat(run.counts().failed()).isEqualTo(2);
        assertThat(run.counts().failedByFault()).isEqualTo(1);
        assertThat(run.headline()).isEqualTo("Finished: 2 cases. 2 failed (1 of them because the service failed or did not answer).");
        assertThat(run.results().get(0).outcomeFault()).isTrue();
        assertThat(run.results().get(1).outcomeFault()).isFalse();
    }

    @Test
    void aStopThatWasAskedForIsShownAtOnce() {
        bed.hold = true;
        SuiteService service = bed.service();
        SuiteRunView started = service.start("chains.csv", HEADER + "\nTC-1,crd,P32H-NO-AUTH," + NPI + ",95810,", null, null, null);
        SuiteRunView stopping = service.stop(started.suiteRunId());
        assertThat(stopping.state()).isEqualTo(SuiteState.RUNNING);
        assertThat(stopping.stopping()).isTrue();
        assertThat(stopping.headline()).isEqualTo("Stopping after the current case: 0 of 1 case done.");
        bed.queued.poll().run();
        SuiteRunView stopped = service.get(started.suiteRunId(), false);
        assertThat(stopped.state()).isEqualTo(SuiteState.STOPPED);
        assertThat(stopped.stopping()).isNull();
    }

    @Test
    void aChainComparedWithAnEarlierRunComparesCaseResultWithCaseResult() {
        LedgerFixtures.MovableClock clock = LedgerFixtures.clock();
        bed.store = LedgerFixtures.ledger(home, clock);
        String rows = HEADER + "\n" + chainRow("E05", "P32H-NO-AUTH", "crd: NO_AUTH_REQUIRED; pas: APPROVED") + "\n"
                + chainRow("E01", "P32H-PA-REQ", "crd: AUTH_REQUIRED; pas: APPROVED");
        SuiteRunView first = bed.service().start("chains.csv", rows, null, null, null);
        assertThat(first.results()).extracting(SuiteCaseResult::result).containsExactly(TestResult.FAIL, TestResult.PASS);

        // The ledger's filter for failed runs finds the chain that failed as a whole, although each of its steps passed.
        List<RunRecord> failed = bed.store.query(RunQuery.builder().suiteRunId(first.suiteRunId()).result(TestResult.FAIL).build());
        assertThat(failed).extracting(RunRecord::caseId).containsExactly("E05");
        assertThat(failed.get(0).result()).isEqualTo(TestResult.PASS);
        assertThat(failed.get(0).caseResult()).isEqualTo(TestResult.FAIL);

        SuiteRunView second = bed.service().start("chains.csv", rows, null, first.suiteRunId(), null);
        SuiteCaseResult e05 = second.results().get(0);
        assertThat(e05.previousResult()).isEqualTo(TestResult.FAIL);
        assertThat(e05.changed()).isFalse();
        SuiteCaseResult e01 = second.results().get(1);
        assertThat(e01.previousResult()).isEqualTo(TestResult.PASS);
        assertThat(e01.changed()).isFalse();
        assertThat(second.counts().changed()).isZero();

        // The same file with a different expectation for E05: the journey is the same, and now it passes.
        String changedRows = HEADER + "\n" + chainRow("E05", "P32H-NO-AUTH", "crd: NO_AUTH_REQUIRED") + "\n"
                + chainRow("E01", "P32H-PA-REQ", "crd: AUTH_REQUIRED; pas: APPROVED");
        SuiteRunView third = bed.service().start("chains.csv", changedRows, null, first.suiteRunId(), null);
        assertThat(third.results().get(0).result()).isEqualTo(TestResult.PASS);
        assertThat(third.results().get(0).previousResult()).isEqualTo(TestResult.FAIL);
        assertThat(third.results().get(0).changed()).isTrue();
    }
}
