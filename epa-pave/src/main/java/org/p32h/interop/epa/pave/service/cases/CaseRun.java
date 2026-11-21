package org.p32h.interop.epa.pave.service.cases;

import java.util.List;
import org.p32h.interop.epa.pave.model.CaseStatus;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.TestResult;

/**
 * What running a case produced: the result of each call that was made, in order.
 *
 * @param envelopes   one result for a single operation, several for a chain. Never empty
 * @param stopped     for a chain that did not go all the way, why it stopped where it did. Null for
 *                    a single operation
 * @param overall     for a chain, the test result of the case as a whole. Null for a single
 *                    operation, whose result is the one its own call produced, and null for a
 *                    chain that was interrupted, because then nothing was compared
 * @param interrupted for a chain whose later step could not be sent, how and why, with the steps
 *                    already sent still in {@code envelopes}. Null when every step that was due
 *                    was made
 * @param notRecorded the ledger's message when the line that records the result of the case as a
 *                    whole was not kept. Empty otherwise
 */
public record CaseRun(List<RunEnvelope<?>> envelopes, String stopped, TestResult overall, Interruption interrupted,
                      List<String> notRecorded) {

    public CaseRun {
        envelopes = envelopes == null ? List.of() : List.copyOf(envelopes);
        notRecorded = notRecorded == null ? List.of() : List.copyOf(notRecorded);
        if (envelopes.isEmpty()) {
            throw new IllegalArgumentException("A case that ran has at least one result.");
        }
    }

    public CaseRun(List<RunEnvelope<?>> envelopes, String stopped, TestResult overall) {
        this(envelopes, stopped, overall, null, List.of());
    }

    public static CaseRun of(RunEnvelope<?> envelope) {
        return new CaseRun(List.of(envelope), null, null);
    }

    public RunEnvelope<?> last() {
        return envelopes.get(envelopes.size() - 1);
    }

    /**
     * Why a case stopped before a step that was due could be sent.
     *
     * @param status         how the case is reported: not valid, not configured, or not runnable
     *                       when this tool failed
     * @param reason         what was sent and what was not, and why, in plain words
     * @param faultReference the reference logged with a failure of this tool, or null for any other cause
     * @param faultCause     with a failure of this tool, the first line of its log entry: the chain
     *                       of exception types and the detail {@code Faults.cause} allows. The log
     *                       can be on a machine the tester never looks at. Null for any other cause
     */
    public record Interruption(CaseStatus status, String reason, String faultReference, String faultCause) {

        /** An interruption that is not a failure of this tool. */
        public Interruption(CaseStatus status, String reason, String faultReference) {
            this(status, reason, faultReference, null);
        }
    }
}
