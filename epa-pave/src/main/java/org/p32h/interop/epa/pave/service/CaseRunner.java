package org.p32h.interop.epa.pave.service;

import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;

/**
 * Runs one case from its inputs alone, with no browser. Replay and suite runs both go through this,
 * so a replayed run and a suite case take exactly the path a manual run takes and write the same
 * kind of ledger record.
 *
 * <p>The one implementation is assembled in {@code config/CaseWiring}, which registers a step for
 * each operation a case can run. Anything that needs a runner still injects
 * {@code ObjectProvider<CaseRunner>}, so a build without one reports the feature as unavailable
 * and does not fail to start.
 */
public interface CaseRunner {

    /**
     * Runs the case. Never throws: a refused production write becomes {@code BLOCKED}, a missing
     * url or an unresolved placeholder becomes {@code NOT_CONFIGURED}, invalid inputs become
     * {@code INVALID}, and a case whose inputs are not kept becomes {@code NOT_RUNNABLE}, each with
     * the reason. An operation that is not outbound, such as a notification that was received, has
     * nothing to send again and is {@code NOT_RUNNABLE}. A case with a key this tool does not know
     * is {@code INVALID}, naming the key. A case whose configuration epoch is no longer current is
     * {@code NOT_CONFIGURED}, saying the configuration changed. A case never carries a production
     * unlock, so a write against a production environment is always reported as blocked.
     *
     * <p>The outcome lists the record of every step that ran, taken from the ledger's receipt,
     * whether or not the ledger kept it, with the ledger's message for each one it did not keep.
     *
     * <p>Replay must not call this for a ledger record that lists any
     * {@link org.p32h.interop.epa.pave.model.RunRecord#unrecordedInputs()}: such a run
     * sent something the record does not hold, so running it again would be a different request.
     * Replay reports it as not replayable and quotes the inputs.
     */
    CaseOutcome run(CaseSpec spec);
}
