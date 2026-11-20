package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.dayFile;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.draft;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.ledger;
import static org.p32h.interop.epa.pave.service.ledger.LedgerFixtures.lines;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.RunQuery;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.RunStamps;

/**
 * The case line of a chain case: the result of the case as a whole, which no step can state,
 * written after the last step and applied to that step when the ledger is read. A query for
 * failed runs finds a chain that failed although each of its steps passed.
 */
class CaseLineLedgerTest {

    private static final String CHAIN = "chain-00000000-0000-4000-8000-0000000000aa";

    @TempDir
    Path home;

    private final LedgerFixtures.MovableClock clock = LedgerFixtures.clock();

    private RunRecord step(String runId) {
        return draft(runId, clock).chainId(CHAIN).suiteRunId("suite-1").caseId("c1").build();
    }

    @Test
    void theCaseResultIsALineOfItsOwnAndIsReadBackOnTheLastStep() {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(step("run-crd"));
        clock.advance(Duration.ofSeconds(1));
        LedgerReceipt receipt = ledger.record(RunRecord.caseLine("run-crd", RunStamps.timestamp(clock), CHAIN, "suite-1", "c1",
                "crd: NO_AUTH_REQUIRED; pas: APPROVED", TestResult.FAIL));

        assertThat(receipt.recorded()).isTrue();
        List<String> written = lines(dayFile(home, clock.today()));
        assertThat(written).hasSize(2);
        assertThat(written.get(1)).startsWith("{\"recordType\":\"case\",\"runId\":\"run-crd\",");
        // A case line is not a run: it is not counted, listed or found as one.
        assertThat(ledger.health().runsToday()).isEqualTo(1);
        assertThat(ledger.query(RunQuery.all())).hasSize(1);

        RunRecord last = ledger.find("run-crd").orElseThrow();
        assertThat(last.result()).isEqualTo(TestResult.PASS);
        assertThat(last.caseResult()).isEqualTo(TestResult.FAIL);
        assertThat(last.caseExpected()).isEqualTo("crd: NO_AUTH_REQUIRED; pas: APPROVED");
        assertThat(ledger.query(RunQuery.forChain(CHAIN))).singleElement().extracting(RunRecord::caseResult).isEqualTo(TestResult.FAIL);
    }

    @Test
    void aQueryForFailedRunsFindsAChainThatFailedAsAWholeAndTheStepsResultStaysItsOwn() {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(step("run-crd"));
        ledger.record(draft("run-other", clock).build());
        ledger.record(RunRecord.caseLine("run-crd", RunStamps.timestamp(clock), CHAIN, "suite-1", "c1", "crd: NO_AUTH_REQUIRED; pas: APPROVED",
                TestResult.FAIL));

        assertThat(ledger.query(RunQuery.builder().result(TestResult.FAIL).build())).extracting(RunRecord::runId).containsExactly("run-crd");
        assertThat(ledger.query(RunQuery.builder().suiteRunId("suite-1").result(TestResult.FAIL).build())).hasSize(1);
        // The step itself passed, and a filter for passed runs still finds it as a step.
        assertThat(ledger.query(RunQuery.builder().result(TestResult.PASS).build())).extracting(RunRecord::runId)
                .containsExactlyInAnyOrder("run-crd", "run-other");
        assertThat(ledger.query(RunQuery.builder().result(TestResult.PASS).build()))
                .filteredOn(r -> r.runId().equals("run-other")).singleElement().extracting(RunRecord::caseResult).isNull();
    }

    @Test
    void aCaseLineWrittenTheNextDayIsStillApplied() {
        JsonlRunLedger ledger = ledger(home, clock);
        clock.set("2026-03-11T04:59:59Z");
        ledger.record(step("run-late"));
        clock.set("2026-03-11T05:00:01Z");
        ledger.record(RunRecord.caseLine("run-late", RunStamps.timestamp(clock), CHAIN, "suite-1", "c1", null, TestResult.UNVERIFIED));

        assertThat(ledger.query(RunQuery.forChain(CHAIN))).singleElement().extracting(RunRecord::caseResult).isEqualTo(TestResult.UNVERIFIED);
        assertThat(ledger.find("run-late").orElseThrow().caseResult()).isEqualTo(TestResult.UNVERIFIED);
    }

    @Test
    void aCaseLineIsReadBackAndNotCountedAsUnreadable() {
        JsonlRunLedger ledger = ledger(home, clock);
        ledger.record(step("run-crd"));
        ledger.record(RunRecord.caseLine("run-crd", RunStamps.timestamp(clock), CHAIN, "suite-1", "c1", null, TestResult.PASS));
        assertThat(ledger.health().unreadableLines()).isZero();
    }
}
