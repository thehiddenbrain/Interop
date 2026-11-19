package org.p32h.interop.epa.pave.service.cases;


/**
 * Runs one operation for a case through the same protocol service a manual run uses, and returns
 * the same result. A case never carries a production unlock, so an implementation passes none.
 */
@FunctionalInterface
public interface CaseStep {

    CaseRun run(CaseContext context);
}
