package org.p32h.interop.epa.pave.service.cases;

import org.p32h.interop.epa.pave.model.CaseSpec;

/**
 * Runs one operation for a case through the same protocol service a manual run uses, and returns
 * the same result. A case never carries a production unlock, so an implementation passes none.
 */
@FunctionalInterface
public interface CaseStep {

    CaseRun run(CaseContext context);

    /**
     * The expectation the first call of this step is sent with, for a check before sending that
     * holds it to the same rules that call will: the case's own, unless the step gives each of its
     * calls a part of it, as a chain does.
     */
    default String firstExpectation(CaseSpec spec) {
        return spec.expected();
    }
}
