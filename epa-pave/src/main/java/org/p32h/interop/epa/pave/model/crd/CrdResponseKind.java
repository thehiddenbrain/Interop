package org.p32h.interop.epa.pave.model.crd;

/** What kind of thing came back from a CRD call, so the screen can say so before showing any of it. */
public enum CrdResponseKind {
    /** A JSON object, read as a CDS Hooks response. */
    CDS_HOOKS,
    /** A FHIR OperationOutcome. */
    OPERATION_OUTCOME,
    /**
     * JSON that is neither of the above, such as an array, or a FHIR resource of another type.
     * Shown in full as additional fields, and never read as an answer that said nothing.
     */
    OTHER_JSON,
    /** A body that could not be parsed as JSON. The raw bytes are in the wire view. */
    NOT_JSON,
    /** A response with no body. */
    NO_BODY,
    /** No response arrived at all. */
    NO_RESPONSE
}
