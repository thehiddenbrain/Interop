package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;

/**
 * The run an identifier belongs to is one that was sent. A run the guardrail refused names what
 * it was aimed at on its ledger line, and is never the answer.
 */
class SentRunsTest {

    private static final String CLAIM = "claim-test-0001";

    private final InMemoryLedger ledger = new InMemoryLedger();
    private int stamped;

    private RunRecord record(EpaOperation operation, String outcome, String claimId) {
        stamped++;
        RunRecord record = RunRecord.builder().runId("run-" + stamped).timestamp(String.format("2026-03-10T10:%02d:00.000-04:00", stamped % 60))
                .protocol(Protocol.PAS).operation(operation).outcome(outcome).result(TestResult.UNVERIFIED)
                .correlation(Map.of(RunRecord.CORR_CLAIM_ID, claimId)).build();
        ledger.record(record);
        return record;
    }

    private RunRecord newest(RunLedger from) {
        return SentRuns.newest(from, EpaOperation.PAS_SUBMIT, RunRecord.CORR_CLAIM_ID, CLAIM);
    }

    @Test
    void theNewestRunThatWasSentIsTheOneAndARefusedAttemptInFrontOfItIsPassedOver() {
        record(EpaOperation.PAS_SUBMIT, "ATTACHMENT_REQUIRED", CLAIM);
        RunRecord resubmitted = record(EpaOperation.PAS_SUBMIT, "PENDED", CLAIM);
        record(EpaOperation.PAS_SUBMIT, "BLOCKED", CLAIM);
        record(EpaOperation.PAS_SUBMIT, "BLOCKED", CLAIM);
        RunLedger watched = spy(ledger);

        assertThat(newest(watched).runId()).isEqualTo(resubmitted.runId());
        // One short look settled it. The whole ledger was not read.
        verify(watched, times(1)).query(any());
    }

    @Test
    void aRunThatWasSentIsFoundBehindAnyNumberOfRefusedAttempts() {
        RunRecord sent = record(EpaOperation.PAS_SUBMIT, "PENDED", CLAIM);
        for (int i = 0; i < 25; i++) {
            record(EpaOperation.PAS_SUBMIT, "BLOCKED", CLAIM);
        }
        RunLedger watched = spy(ledger);

        // More refused attempts than the first look reads. Stopping there would say no submission carries the claim.
        assertThat(newest(watched).runId()).isEqualTo(sent.runId());
        verify(watched, times(2)).query(any());
    }

    @Test
    void whenNothingThatCarriesTheIdentifierWasSentThereIsNoRun() {
        assertThat(newest(ledger)).isNull();
        record(EpaOperation.PAS_SUBMIT, "BLOCKED", CLAIM);
        // Sent, and about something else: another claim, and an inquiry about this one.
        record(EpaOperation.PAS_SUBMIT, "APPROVED", "claim-test-0002");
        record(EpaOperation.PAS_INQUIRE, "APPROVED", CLAIM);
        assertThat(newest(ledger)).isNull();
        for (int i = 0; i < 12; i++) {
            record(EpaOperation.PAS_SUBMIT, "BLOCKED", CLAIM);
        }
        assertThat(newest(ledger)).isNull();
    }

    @Test
    void aRunThatWasSentAndFailedIsStillTheRunBecauseOnlyARefusalSendsNothing() {
        // No answer came back, or this tool failed after the call: the request went out all the same.
        for (String outcome : new String[] {"TIMEOUT", "UNREACHABLE", "TOOL_ERROR", "REJECTED"}) {
            RunRecord sent = record(EpaOperation.PAS_SUBMIT, outcome, CLAIM);
            assertThat(newest(ledger).runId()).as(outcome).isEqualTo(sent.runId());
        }
    }
}
