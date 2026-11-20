package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What running one case produced.
 *
 * @param status      how the attempt ended
 * @param reason      why the case was blocked, not runnable or invalid. For a chain that completed
 *                    but stopped before its last step, why it stopped there: the flow ended, or the
 *                    next step needs a person, such as answering a questionnaire. Null otherwise
 * @param runs        the record of each step that ran, in order, whether or not the ledger kept it.
 *                    One for a single operation, up to three for a chain
 * @param notRecorded for each run the ledger did not keep, the ledger's own message saying why.
 *                    Empty when every run was kept. A suite table shows these, so a full disk or
 *                    tracking switched off is never a column of blank rows
 * @param notes       what a reader of the result should know about how the case was run, such as
 *                    that the member could not be looked up and the request went out with the
 *                    member ID alone. Never member data
 * @param overall     the test result of the case as a whole, when the case is a chain whose
 *                    expectation names several steps. Null for a single operation, whose result
 *                    is the one on its record. A chain that expected an answer from a step it
 *                    never reached has failed, and this is where that is stated: no single
 *                    record could say it
 * @param faultReference the reference logged when this tool failed while running the case, so a
 *                    tester can tell a failure of the tool from a case that cannot be run and
 *                    quote it. Null otherwise. Whether a request left before the failure is not
 *                    known, and the reason says so
 * @param verdict     the verdict of the last step that ran, as its protocol endpoint returned it.
 *                    Null when no step ran or none answered with one
 * @param nextStep    the next step the last step named, as its protocol endpoint returned it. Null
 *                    when no step ran or none answered with one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseOutcome(CaseSpec spec, CaseStatus status, String reason, List<RunRecord> runs, List<String> notRecorded,
                          List<String> notes, TestResult overall, String faultReference, VerdictView verdict, NextStepView nextStep) {

    public CaseOutcome {
        runs = runs == null ? List.of() : List.copyOf(runs);
        notRecorded = notRecorded == null ? List.of() : List.copyOf(notRecorded);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public CaseOutcome(CaseSpec spec, CaseStatus status, String reason, List<RunRecord> runs, List<String> notRecorded,
                       List<String> notes, TestResult overall, String faultReference) {
        this(spec, status, reason, runs, notRecorded, notes, overall, faultReference, null, null);
    }

    public CaseOutcome(CaseSpec spec, CaseStatus status, String reason, List<RunRecord> runs, List<String> notRecorded,
                       List<String> notes, TestResult overall) {
        this(spec, status, reason, runs, notRecorded, notes, overall, null);
    }

    /** The same outcome with the verdict and the next step of the last step that ran. */
    public CaseOutcome withFound(VerdictView lastVerdict, NextStepView lastNextStep) {
        return new CaseOutcome(spec, status, reason, runs, notRecorded, notes, overall, faultReference, lastVerdict, lastNextStep);
    }

    /** True when this tool failed while running the case, which is not the same as a case that cannot be run. */
    public boolean toolFailed() {
        return faultReference != null;
    }

    /** An outcome whose test result is the one on its last record. */
    public CaseOutcome(CaseSpec spec, CaseStatus status, String reason, List<RunRecord> runs, List<String> notRecorded,
                       List<String> notes) {
        this(spec, status, reason, runs, notRecorded, notes, null);
    }

    /** An outcome with nothing to add about how the case was run. */
    public CaseOutcome(CaseSpec spec, CaseStatus status, String reason, List<RunRecord> runs, List<String> notRecorded) {
        this(spec, status, reason, runs, notRecorded, List.of());
    }

    /** An outcome whose runs were all kept by the ledger, or that ran nothing. */
    public CaseOutcome(CaseSpec spec, CaseStatus status, String reason, List<RunRecord> runs) {
        this(spec, status, reason, runs, List.of());
    }

    /**
     * The test result of the case: the one stated for the case as a whole when there is one,
     * otherwise the one on the last record. Null when nothing ran, or when the case did not
     * complete, because then nothing was compared.
     */
    public TestResult result() {
        if (status != CaseStatus.COMPLETED) {
            return null;
        }
        return overall != null ? overall : finalRun() == null ? null : finalRun().result();
    }

    /** The record that states the result of the case: the last step that ran. Null when nothing ran. */
    public RunRecord finalRun() {
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }

    /** Prints how the case ended. The case holds a member ID and its reason can quote what was typed. */
    @Override
    public String toString() {
        return "CaseOutcome[" + status + ", " + overall + "]";
    }
}
