package org.p32h.interop.epa.pave.model.suite;

/** Where a suite run stands. */
public enum SuiteState {
    /** Cases are still being run, one after another. */
    RUNNING,
    /** Every case was attempted. */
    COMPLETED,
    /** The run ended before every case was attempted. The view says why and how many were left. */
    STOPPED
}
