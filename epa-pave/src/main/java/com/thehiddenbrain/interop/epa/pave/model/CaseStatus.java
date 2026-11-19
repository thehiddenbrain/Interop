package com.thehiddenbrain.interop.epa.pave.model;

/** How an attempt to run a case ended, before anything is said about its outcome. */
public enum CaseStatus {
    /** The case ran and a ledger record exists for it, whatever the service answered. */
    COMPLETED,
    /** The production guardrail refused the case. Reported as blocked, never as a failure. */
    BLOCKED,
    /** The case cannot be run from what is known: a member ID that was not recorded, an operation whose inputs are not kept. */
    NOT_RUNNABLE,
    /** The case inputs are not valid: a malformed NPI, an unknown expectation. */
    INVALID
}
