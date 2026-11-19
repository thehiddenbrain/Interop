package org.p32h.interop.epa.pave.service.cases;

import org.p32h.interop.epa.pave.model.RunEnvelope;

/**
 * Runs one operation for a case through the same protocol service a manual run uses, and returns
 * the same result. A case never carries a production unlock, so an implementation passes none.
 */
@FunctionalInterface
public interface CaseStep {

    RunEnvelope<?> run(CaseContext context);
}
