package org.p32h.interop.epa.pave.model.dtr;

/** What kind of thing a DTR step has to show, so the screen can say so before showing any of it. */
public enum DtrResponseKind {
    /** A FHIR Parameters resource, which is one of the two shapes a questionnaire package arrives in. */
    PARAMETERS,
    /** A FHIR Bundle, which is the other. */
    BUNDLE,
    /** A Questionnaire resource on its own. */
    QUESTIONNAIRE,
    /** A QuestionnaireResponse, which is what a step of the adaptive loop returns. */
    QUESTIONNAIRE_RESPONSE,
    /** A FHIR OperationOutcome. */
    OPERATION_OUTCOME,
    /** JSON that is none of the above. Shown in full as additional fields. */
    OTHER_JSON,
    /** A body that could not be parsed as JSON. The raw bytes are in the wire view. */
    NOT_JSON,
    /** A response with no body. */
    NO_BODY,
    /** No response arrived at all. */
    NO_RESPONSE,
    /** Nothing was sent: the step was completed inside this tool. */
    NOT_SENT
}
