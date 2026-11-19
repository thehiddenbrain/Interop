package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What running one case produced.
 *
 * @param status how the attempt ended
 * @param reason why the case was blocked, not runnable or invalid. Null when it completed
 * @param runs   the ledger records written, in order. One for a single operation, up to three for a chain
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseOutcome(CaseSpec spec, CaseStatus status, String reason, List<RunRecord> runs) {

    public CaseOutcome {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    /** The record that states the result of the case: the last step that ran. Null when nothing ran. */
    public RunRecord finalRun() {
        return runs.isEmpty() ? null : runs.get(runs.size() - 1);
    }
}
