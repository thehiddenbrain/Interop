package org.p32h.interop.epa.pave.model;

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
    /**
     * The code list could not be compared with the service for a code, and the finding says why.
     * Without this, a result with no disagreement would read as agreement when nothing was compared.
     */
    CODE_LIST_NOT_COMPARED,
    /** A notification arrived that matches no subscription this tool knows of. It is recorded and shown, never dropped. */
    UNMATCHED_NOTIFICATION,
    /** The call completed but took longer than the configured threshold. */
    SLOW_RESPONSE,
    /**
     * The collection request carries a pre-request script. It was not run, so a value Postman would
     * have computed was not computed. A failure with no other visible cause often traces to this.
     */
    PRE_REQUEST_SCRIPT_NOT_RUN
}
