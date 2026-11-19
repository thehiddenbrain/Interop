package org.p32h.interop.epa.pave.service.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.RunRecord;
import java.util.List;

/**
 * What a replay has to say about one run it was asked to run again. Every run that was selected
 * has one of these: a run that could not be sent again is reported with the reason, never left out.
 *
 * @param runId       the original run
 * @param status      what became of it
 * @param reason      why it was not sent again, or why the two runs could not be compared. Null
 *                    when it was sent and compared
 * @param replayRunId the run id of the run that was sent again, when there is one
 * @param differences what came back different, each as it was and as it is now. Empty unless the
 *                    status is changed
 * @param notRecorded the ledger's own message for each run of the replay it did not keep. The
 *                    comparison is still made, against the record the run produced
 * @param original    the original run as the ledger holds it
 * @param replayed    the record this replay produced, whether or not the ledger kept it: the run
 *                    that was sent again, or for a write the guardrail refused, the record of the
 *                    attempt. Left out when there is none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayEntry(String runId, ReplayStatus status, String reason, String replayRunId, List<ReplayDifference> differences,
                          List<String> notRecorded, RunRecord original, RunRecord replayed) {

    public ReplayEntry {
        differences = differences == null ? List.of() : List.copyOf(differences);
        notRecorded = notRecorded == null ? List.of() : List.copyOf(notRecorded);
    }

    /**
     * An entry for a run this replay has no record of. Either it was not sent again, or this tool
     * lost track of it part way and cannot say whether it was. The reason says which.
     */
    static ReplayEntry withNoRecord(RunRecord original, ReplayStatus status, String reason) {
        return new ReplayEntry(original.runId(), status, reason, null, List.of(), List.of(), original, null);
    }
}
