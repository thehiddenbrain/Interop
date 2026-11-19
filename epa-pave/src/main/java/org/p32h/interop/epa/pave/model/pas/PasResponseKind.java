package org.p32h.interop.epa.pave.model.pas;

/** What kind of thing came back from a PAS call, so the screen can say so before showing any of it. */
public enum PasResponseKind {
    /** A FHIR Bundle, read entry by entry. */
    BUNDLE,
    /** A ClaimResponse on its own, not wrapped in a bundle. */
    CLAIM_RESPONSE,
    /** A FHIR OperationOutcome. */
    OPERATION_OUTCOME,
    /** JSON that is none of the above. Shown in full as additional fields. */
    OTHER_JSON,
    /** A body that could not be parsed as JSON. The raw bytes are in the wire view. */
    NOT_JSON,
    /** A response with no body. */
    NO_BODY,
    /** No response arrived at all. */
    NO_RESPONSE
}
