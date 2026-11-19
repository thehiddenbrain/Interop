package com.thehiddenbrain.interop.epa.pave.service;

import com.thehiddenbrain.interop.epa.pave.model.CaseOutcome;
import com.thehiddenbrain.interop.epa.pave.model.CaseSpec;

/**
 * Runs one case from its inputs alone, with no browser. Replay and suite runs both go through this,
 * so a replayed run and a suite case take exactly the path a manual run takes and write the same
 * kind of ledger record.
 */
public interface CaseRunner {

    /**
     * Runs the case. Never throws for a blocked write, a case that cannot be reproduced or invalid
     * inputs; the outcome says which it was and why. A case never carries a production unlock, so a
     * write against a production environment is always reported as blocked.
     */
    CaseOutcome run(CaseSpec spec);
}
