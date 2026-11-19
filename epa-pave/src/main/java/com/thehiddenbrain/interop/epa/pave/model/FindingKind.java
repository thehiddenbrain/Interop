package com.thehiddenbrain.interop.epa.pave.model;

/**
 * Something on a response that a person needs to look at. A finding is not a failure of the call.
 */
public enum FindingKind {
    /** A coded value that is not in the value set the implementation guide defines for it. */
    CODE_OUTSIDE_VALUE_SET,
    /** An element the implementation guide expects is missing, so the response says less than it should. */
    REQUIRED_ELEMENT_MISSING,
    /** The procedure code list and the service disagree on whether prior authorization is required. */
    CODE_LIST_DISAGREES_ON_AUTH,
    /** The procedure code list and the service disagree on the delegated vendor. */
    CODE_LIST_DISAGREES_ON_VENDOR,
    /** The call completed but took longer than the configured threshold. */
    SLOW_RESPONSE
}
