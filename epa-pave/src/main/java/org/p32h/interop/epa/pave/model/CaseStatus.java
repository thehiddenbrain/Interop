package org.p32h.interop.epa.pave.model;

/** How an attempt to run a case ended, before anything is said about its outcome. */
public enum CaseStatus {
    /** The case ran and a ledger record exists for it, whatever the service answered. */
    COMPLETED,
    /** The production guardrail refused the case. Reported as blocked, never as a failure. */
    BLOCKED,
    /** The case cannot be run from what is known: a member ID that was not recorded, an operation whose inputs are not kept. */
    NOT_RUNNABLE,
    /** The case inputs are not valid: a malformed NPI, an unknown expectation. */
    INVALID,
    /**
     * The tool has no url, or an unresolved placeholder, for this operation in the active
     * environment. The record is not at fault: fix the configuration and run again.
     */
    NOT_CONFIGURED
}
