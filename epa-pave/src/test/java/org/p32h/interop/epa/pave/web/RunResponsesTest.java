package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.springframework.http.HttpStatus;

class RunResponsesTest {

    private static RunEnvelope<String> envelope(ErrorView error) {
        RunRecord record = RunRecord.builder().runId("run-1").timestamp("2026-01-05T10:15:30.000-05:00").protocol(Protocol.CRD)
                .operation(EpaOperation.CRD_ORDER_SIGN).outcome(CrdOutcome.REJECTED).result(TestResult.UNVERIFIED).build();
        return RunEnvelope.<String>builder().runId("run-1").protocol(Protocol.CRD).operation(EpaOperation.CRD_ORDER_SIGN)
                .verdict(VerdictView.of(CrdOutcome.REJECTED, "The service did not accept the request as sent.", List.of()))
                .expectation(Expectations.assess(null, CrdOutcome.REJECTED, null, CrdOutcome.values(), List.of()))
                .nextStep(NextStepView.of(NextAction.FIX_REQUEST, "Next.", "Because."))
                .ledger(new LedgerReceipt(true, record, null, 1)).error(error).build();
    }

    @Test
    void aCompletedCallAnswers200EvenWhenTheServiceReturnedAFault() {
        assertThat(RunResponses.of(envelope(null)).getStatusCode()).isEqualTo(HttpStatus.OK);
        // A vendor fault is described in the body. The tester needs to see the request that caused it.
        for (ErrorKind vendorFault : new ErrorKind[] {ErrorKind.VENDOR_REJECTED, ErrorKind.VENDOR_ERROR, ErrorKind.TIMEOUT,
                ErrorKind.UNREACHABLE, ErrorKind.MALFORMED_RESPONSE}) {
            assertThat(RunResponses.of(envelope(ErrorView.of(vendorFault, "t", "a"))).getStatusCode()).as(vendorFault.name())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void aRefusedWriteAnswers403AndAToolFailureAfterTheCallAnswers500BothWithTheResultAsTheBody() {
        RunEnvelope<String> blocked = envelope(ErrorView.of(ErrorKind.BLOCKED, "t", "a"));
        assertThat(RunResponses.of(blocked).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(RunResponses.of(blocked).getBody()).isSameAs(blocked);
        RunEnvelope<String> toolError = envelope(ErrorView.of(ErrorKind.INTERNAL, "t", "a").withReference("ref-1"));
        assertThat(RunResponses.of(toolError).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(RunResponses.of(toolError).getBody().runId()).isEqualTo("run-1");
    }
}
