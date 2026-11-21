package org.p32h.interop.epa.pave.model;

/**
 * What kind of thing went wrong. The error panel presents each differently, because the tester's
 * next move differs: fix the form, fix the configuration, fix the test data or report the environment.
 */
public enum ErrorKind {
    /** The submitted form was not valid. Nothing was sent. */
    VALIDATION,
    /** The tool is not configured to send this. Nothing was sent. */
    CONFIGURATION,
    /** The production guardrail refused a write. Nothing was sent. */
    BLOCKED,
    /** The vendor answered with a status that is neither a success nor 5xx: 4xx, and also 1xx and 3xx. */
    VENDOR_REJECTED,
    /** The vendor answered with an HTTP 5xx status. */
    VENDOR_ERROR,
    TIMEOUT,
    UNREACHABLE,
    MALFORMED_RESPONSE,
    /** The thing asked for does not exist in this tool: a run id, an environment name. */
    NOT_FOUND,
    /** A defect in this tool. */
    INTERNAL
}
