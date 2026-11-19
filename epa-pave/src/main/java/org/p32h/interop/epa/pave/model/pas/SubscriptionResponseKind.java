package org.p32h.interop.epa.pave.model.pas;

/** What kind of thing came back from a subscription call. */
public enum SubscriptionResponseKind {
    /** One Subscription resource. */
    SUBSCRIPTION,
    /** A bundle, read as a list of subscriptions. */
    BUNDLE,
    /** A FHIR OperationOutcome. */
    OPERATION_OUTCOME,
    /** JSON that is none of the above. Shown in full as additional fields. */
    OTHER_JSON,
    /** A body that could not be parsed as JSON. The raw bytes are in the wire view. */
    NOT_JSON,
    /** A response with no body. For a delete, and for a create or update that answers without one, this is a normal answer. */
    NO_BODY,
    /** No response arrived at all. */
    NO_RESPONSE
}
