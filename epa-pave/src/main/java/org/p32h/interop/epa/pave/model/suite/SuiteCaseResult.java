package org.p32h.interop.epa.pave.model.suite;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * One row of a suite results table.
 *
 * <p>It never holds the member ID or anything looked up for the member. The row is tied to its
 * ledger records by run id, and the ledger shows the member the way the tracking policy allows.
 *
 * <p>The authorization outcome and the test result are separate components, as they are on a
 * manual run: {@code outcome} is what the service said, {@code result} is whether that matched
 * what the row declared.
 *
 * @param row            the row of the file, counting the first case as 1
 * @param what           what the row runs, in plain words, such as "CRD order-sign" or "Chain"
 * @param status         how the attempt ended. Null until the case has been attempted
 * @param statusLine     one plain sentence saying where the row stands
 * @param expected       what the row declared, as written. Null when it declared nothing
 * @param outcome        the outcome code of the last step that ran. Null when nothing ran
 * @param outcomeLabel   that outcome in plain words
 * @param result         pass, fail or unverified. Null when nothing was sent, so there is nothing
 *                       to compare: a blocked, invalid, unconfigured or unrunnable case
 * @param elapsedMillis  the time the calls of this case took, added up. Null when no call was made
 * @param slow           true when a call of this case completed but took longer than the threshold
 * @param runId          the ledger record that states the result: the last step that ran
 * @param runIds         every ledger record of the case, in order. More than one for a chain
 * @param findings       the kinds of finding raised on the case's runs
 * @param reason         why the case was blocked, invalid, not configured or not runnable, or why
 *                       a chain stopped where it did
 * @param notes          what a reader should know about how the case was run
 * @param notRecorded    the ledger's own message for each run it did not keep
 * @param previousOutcome the outcome the same case had in the suite run this one is compared with
 * @param changed        true when the outcome or the result differs from that earlier run. Null
 *                       when there is no comparison, or the earlier run has no such case
 * @param steps          for a chain, each step it sent, in order, with its own outcome and its own
 *                       result. The case result above is the one for the journey as a whole, so a
 *                       chain that failed on an early step shows that step here. Empty otherwise
 * @param faultReference when this tool failed while running the case, the reference it was logged
 *                       under. Whether a request left before the failure is not known
 * @param outcomeFault   true when the outcome says the service failed or did not answer, as
 *                       opposed to a determination. Null when nothing was sent
 * @param nextStep       what the last step said should happen next, in plain words
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SuiteCaseResult(
        int row,
        String caseId,
        String what,
        boolean chain,
        SuiteRowState state,
        CaseStatus status,
        String statusLine,
        String expected,
        String outcome,
        String outcomeLabel,
        Tone tone,
        TestResult result,
        Long elapsedMillis,
        boolean slow,
        String runId,
        List<String> runIds,
        String chainId,
        Integer httpStatus,
        List<String> findings,
        String nextAction,
        String reason,
        List<String> notes,
        List<String> notRecorded,
        String previousOutcome,
        String previousOutcomeLabel,
        TestResult previousResult,
        Boolean changed,
        List<Step> steps,
        String faultReference,
        Boolean outcomeFault,
        String nextStep) {

    public SuiteCaseResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
        findings = findings == null ? List.of() : List.copyOf(findings);
        notes = notes == null ? List.of() : List.copyOf(notes);
        notRecorded = notRecorded == null ? List.of() : List.copyOf(notRecorded);
    }

    /** True when some of the case's steps went out and the case did not complete or end blocked. */
    public boolean partlySent() {
        return !runIds.isEmpty() && status != null && status != CaseStatus.COMPLETED && status != CaseStatus.BLOCKED;
    }

    /**
     * One step of a chain row.
     *
     * @param what   the operation in plain words
     * @param result the step's own test result, against the part of the expectation that names it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(String what, String outcome, String outcomeLabel, TestResult result, String runId) {

        /** Prints the step and its result. The outcome comes from a response. */
        @Override
        public String toString() {
            return "Step[" + runId + ", " + result + "]";
        }
    }

    /** Prints the row and its state. The case id, the expectation and the reason are cells of a suite file or quote them. */
    @Override
    public String toString() {
        return "SuiteCaseResult[" + row + ", " + state + "]";
    }
}
