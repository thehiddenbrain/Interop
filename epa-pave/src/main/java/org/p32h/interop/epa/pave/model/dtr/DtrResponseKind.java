package org.p32h.interop.epa.pave.model.dtr;

/** What kind of thing a DTR step has to show, so the screen can say so before showing any of it. */
public enum DtrResponseKind {
    /** A FHIR Parameters resource, which is one of the two shapes a questionnaire package arrives in. */
    PARAMETERS("The answer is a Parameters resource."),
    /** A FHIR Bundle, which is the other. */
    BUNDLE("The answer is a Bundle."),
    /** A Questionnaire resource on its own. */
    QUESTIONNAIRE("The answer is a Questionnaire resource by itself."),
    /** A QuestionnaireResponse, which is what a step of the adaptive loop returns. */
    QUESTIONNAIRE_RESPONSE("The answer is a QuestionnaireResponse."),
    /** A FHIR OperationOutcome. */
    OPERATION_OUTCOME("The answer is an OperationOutcome. Its issues are listed with this result."),
    /** JSON that is none of the above. Shown in full as additional fields. */
    OTHER_JSON("The answer is JSON that is not a DTR resource. It is listed in full among the fields returned but not interpreted."),
    /** A body that could not be parsed as JSON. The raw bytes are in the wire view. */
    NOT_JSON("The body of the answer could not be read as JSON. The wire view shows it as it arrived."),
    /** A response with no body. */
    NO_BODY("The answer has no body."),
    /** No response arrived at all. */
    NO_RESPONSE("No response arrived."),
    /** Nothing was sent: the step was completed inside this tool. */
    NOT_SENT("Nothing was sent to the service for this step.");

    private final String note;

    DtrResponseKind(String note) {
        this.note = note;
    }

    /** The sentence a result carries for this kind when the reading of the answer had nothing more particular to say. */
    public String note() {
        return note;
    }
}
