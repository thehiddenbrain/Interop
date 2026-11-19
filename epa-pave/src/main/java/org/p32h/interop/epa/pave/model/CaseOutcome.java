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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseOutcome(CaseSpec spec, CaseStatus status, String reason, List<RunRecord> runs, List<String> notRecorded) {

    public CaseOutcome {
        runs = runs == null ? List.of() : List.copyOf(runs);
        notRecorded = notRecorded == null ? List.of() : List.copyOf(notRecorded);
    }

    /** An outcome whose runs were all kept by the ledger, or that ran nothing. */
    public CaseOutcome(CaseSpec spec, CaseStatus status, String reason, List<RunRecord> runs) {
        this(spec, status, reason, runs, List.of());
    }

    /** The record that states the result of the case: the last step that ran. Null when nothing ran. */
    public RunRecord finalRun() {
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }
}
