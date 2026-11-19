package org.p32h.interop.epa.pave.service;

import org.p32h.interop.epa.pave.model.CaseOutcome;
import org.p32h.interop.epa.pave.model.CaseSpec;

/**
 * Runs one case from its inputs alone, with no browser. Replay and suite runs both go through this,
 * so a replayed run and a suite case take exactly the path a manual run takes and write the same
 * kind of ledger record.
 *
 * <p>The implementation arrives after the protocol verticals. Until then no bean of this type
 * exists, so anything that needs one injects {@code ObjectProvider<CaseRunner>} and reports the
 * feature as unavailable when there is none.
 */
public interface CaseRunner {

    /**
     * Runs the case. Never throws: a refused production write becomes {@code BLOCKED}, a missing
     * url or an unresolved placeholder becomes {@code NOT_CONFIGURED}, invalid inputs become
     * {@code INVALID}, and a case whose inputs are not kept becomes {@code NOT_RUNNABLE}, each with
     * the reason. A case never carries a production unlock, so a write against a production
     * environment is always reported as blocked.
     *
     * <p>Replay must not call this for a ledger record that lists any
     * {@link org.p32h.interop.epa.pave.model.RunRecord#unrecordedInputs()}: such a run
     * sent something the record does not hold, so running it again would be a different request.
     * Replay reports it as not replayable and quotes the inputs.
     */
    CaseOutcome run(CaseSpec spec);
}
