package org.p32h.interop.epa.pave.service.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.VerdictView;
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
 * @param verdict     what the run that was sent again found, as its protocol endpoint returned it:
 *                    the designed verdict, never the wire view, which stays behind the run's own
 *                    page. Left out when no run was made
 * @param nextStep    the next step that run named, as its protocol endpoint returned it. Left out
 *                    when no run was made
 * @param faultReference when this tool failed while running it again, the reference the failure
 *                    was logged under. Left out otherwise
 * @param faultCause  with that reference, the first line of its log entry: the chain of exception
 *                    types and the detail the log allows, so the tester can report the cause from
 *                    the screen. A replay is answered and not kept, so the ledger never holds it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayEntry(String runId, ReplayStatus status, String reason, String replayRunId, List<ReplayDifference> differences,
                          List<String> notRecorded, RunRecord original, RunRecord replayed, VerdictView verdict, NextStepView nextStep,
                          String faultReference, String faultCause) {

    public ReplayEntry {
        differences = differences == null ? List.of() : List.copyOf(differences);
        notRecorded = notRecorded == null ? List.of() : List.copyOf(notRecorded);
    }

    public ReplayEntry(String runId, ReplayStatus status, String reason, String replayRunId, List<ReplayDifference> differences,
                       List<String> notRecorded, RunRecord original, RunRecord replayed) {
        this(runId, status, reason, replayRunId, differences, notRecorded, original, replayed, null, null, null, null);
    }

    /** The same entry with what the run that was sent again found. */
    ReplayEntry withFound(VerdictView found, NextStepView next) {
        return new ReplayEntry(runId, status, reason, replayRunId, differences, notRecorded, original, replayed, found, next,
                faultReference, faultCause);
    }

    /** The same entry with the reference and the cause of a failure of this tool, when there was one. */
    ReplayEntry withFault(String reference, String cause) {
        return new ReplayEntry(runId, status, reason, replayRunId, differences, notRecorded, original, replayed, verdict, nextStep,
                reference, reference == null ? null : cause);
    }

    /**
     * An entry for a run this replay has no record of. Either it was not sent again, or this tool
     * lost track of it part way and cannot say whether it was. The reason says which.
     */
    static ReplayEntry withNoRecord(RunRecord original, ReplayStatus status, String reason) {
        return new ReplayEntry(original.runId(), status, reason, null, List.of(), List.of(), original, null);
    }

    /** Prints the run and how it ended. The reason can quote an expectation a tester typed. */
    @Override
    public String toString() {
        return "ReplayEntry[" + runId + ", " + status + "]";
    }
}
