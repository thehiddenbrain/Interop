package org.p32h.interop.epa.pave.model;

/**
 * Something on a response that a person needs to look at. A finding is not a failure of the call.
 */
public enum FindingKind {
    /** A coded value that is not in the value set the implementation guide defines for it. */
    CODE_OUTSIDE_VALUE_SET,
    /** An element the implementation guide expects is missing, so the response says less than it should. */
    REQUIRED_ELEMENT_MISSING,
    /**
     * The response breaks a rule of the guide that is neither a missing element nor a code outside
     * a value set: an invariant, an element where the guide does not let it sit, or an element sent
     * more often than the guide allows. Filed under one of the other two, such a rule reads as
     * something it is not, and a tester repeats that reading to the vendor.
     */
    GUIDE_RULE_BROKEN,
    /** The procedure code list and the service disagree on whether prior authorization is required. */
    CODE_LIST_DISAGREES_ON_AUTH,
    /** The procedure code list and the service disagree on the delegated vendor. */
    CODE_LIST_DISAGREES_ON_VENDOR,
    /**
     * The code list could not be compared with the service, for one code or for a whole run, and
     * the finding says why. Without this, a result with no disagreement would read as agreement
     * when nothing was compared.
     */
    CODE_LIST_NOT_COMPARED,
    /**
     * A code or an element that the version of the guide this tool is held to does not define,
     * and that a later version does. It is read, shown with its meaning, and named with the
     * version that defines it. It is not called wrong: which version the service follows is the
     * service's to say.
     */
    OTHER_VERSION_CONTENT,
    /**
     * The response does not state something a tester would look for, and the guide does not
     * require it. This is information. An absence the guide does forbid is
     * {@link #REQUIRED_ELEMENT_MISSING}.
     */
    NOT_STATED,
    /**
     * The request this tool sent left out something the guide requires of a request, because
     * nobody supplied it and the tool does not invent values. This is about what was sent, so
     * that a refusal by the service is not read as a fault of the service.
     */
    REQUEST_INCOMPLETE,
    /** A notification arrived that matches no subscription this tool knows of. It is recorded and shown, never dropped. */
    UNMATCHED_NOTIFICATION,
    /**
     * What arrived at the notification receiver could not be checked against the ledger, in whole
     * or in part: the ledger could not be asked which run created the subscription it names, or
     * which submission it is about. A run it belongs to may exist. Without this, a notification
     * with no link would read as one that was checked and matched nothing, which is not known.
     */
    UNCHECKED_NOTIFICATION,
    /**
     * A subscription was sent that names no url to call back, so the service has nowhere to send
     * a notification and none can arrive at this tool for it. This is about what was sent, not
     * about an element missing from a response, which is {@link #REQUIRED_ELEMENT_MISSING}.
     */
    NO_CALLBACK_URL,
    /** The call completed but took longer than the configured threshold. */
    SLOW_RESPONSE,
    /**
     * The collection request carries a pre-request script. It was not run, so a value Postman would
     * have computed was not computed. A failure with no other visible cause often traces to this.
     */
    PRE_REQUEST_SCRIPT_NOT_RUN
}
