package org.p32h.interop.epa.pave.model.chain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.model.AnsweredBy;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * One run of a chain, as its ledger record states it. It holds nothing about the member: the run
 * id leads to the record, which shows the member the way the tracking policy allows.
 *
 * <p>The authorization outcome and the test result are separate components, as everywhere.
 *
 * @param what            the operation in plain words
 * @param outcomeLabel    the outcome in plain words
 * @param nextAction      what the run said should happen next. Null on a record written before
 *                        this was kept, or holding a value this version does not know
 * @param latestOfItsKind false when a later run of the same operation exists in the chain, so
 *                        this one has been run again since
 * @param caseResult      on the last step of a chain run as a case, the result of the case as a
 *                        whole, which can differ from this step's own result. Null otherwise
 * @param answeredBy      who answered the run, as its ledger line says: a step nobody answered is
 *                        drawn apart from an answer of the payer or of a scenario
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChainRun(
        String runId,
        String timestamp,
        Protocol protocol,
        EpaOperation operation,
        String what,
        String outcome,
        String outcomeLabel,
        Tone tone,
        String expected,
        TestResult result,
        Long elapsedMillis,
        boolean slow,
        Integer httpStatus,
        NextAction nextAction,
        List<String> findings,
        String scenarioId,
        boolean latestOfItsKind,
        TestResult caseResult,
        AnsweredBy answeredBy) {

    public ChainRun {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** Prints what identifies the run. The expectation is free text a tester typed. */
    @Override
    public String toString() {
        return "ChainRun[" + runId + ", " + operation + ", " + outcome + "]";
    }
}
