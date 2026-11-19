package org.p32h.interop.epa.pave.model.suite;

/** Where one case of a suite run stands. */
public enum SuiteRowState {
    /** Not reached yet. */
    PENDING,
    /** Being run now. */
    RUNNING,
    /** Attempted. Its status says how the attempt ended. */
    DONE,
    /** The suite stopped before this case was reached, so nothing is known about it. */
    NOT_RUN
}
