package org.p32h.interop.epa.pave.model.dtr;

import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * What one DTR step established: a package fetch, a step of the adaptive loop, or a response put
 * together inside the tool.
 *
 * <p>Three pairs are kept apart on purpose, because each pair looks alike on a screen that only
 * shows "nothing came back":
 * <ul>
 *   <li>{@link #NO_QUESTIONNAIRE} is the service answering and sending no questionnaire. From
 *       STU 2.1.0 the DTR guide has a service answer that way when none needs to be completed,
 *       and this tool still reports it as what arrived, an empty package, and never as an answer
 *       that no documentation is needed. {@link #NOTHING_REQUIRED} is the service saying through
 *       the adaptive loop that the response is complete without asking a question.</li>
 *   <li>{@link #RESPONSE_COMPLETED} needs every required question that is enabled to have an
 *       answer. {@link #REQUIRED_UNANSWERED} is its own outcome and is never reported as
 *       complete, whatever status the service put on the response.</li>
 *   <li>{@link #MALFORMED_QUESTIONNAIRE} is a body that was read and cannot be used as a
 *       questionnaire. {@link #MALFORMED_RESPONSE} is a body that could not be read at all.</li>
 * </ul>
 *
 * <p>The five faults and the two not-found outcomes carry the same names as in the CRD
 * vocabulary, so the history reads alike across tabs.
 *
 * <p>A step that went right is {@link Tone#NEUTRAL}, not {@link Tone#ACTION}: orange keeps the one
 * meaning a tester can name, that there is something for them to put right on this step, which is
 * true of {@link #RESPONSE_NOT_COMPLETE} and {@link #REQUIRED_UNANSWERED} and of no step that loaded
 * a package, returned the next question or completed the response.
 */
public enum DtrOutcome implements OutcomeCode {

    PACKAGE_LOADED("Package loaded", "The questionnaire package was loaded", Tone.NEUTRAL, false),
    PACKAGE_INCOMPLETE("Package incomplete", "The package does not hold every questionnaire that was asked for", Tone.NO_ANSWER, false),
    NO_QUESTIONNAIRE("No questionnaire", "The service returned no questionnaire", Tone.NO_ANSWER, false),
    MALFORMED_QUESTIONNAIRE("Unusable questionnaire", "The service answered, but what it sent cannot be used as a questionnaire", Tone.FAULT, true),
    NEXT_QUESTION("Next question", "The service returned the next question", Tone.NEUTRAL, false),
    NO_QUESTION_RETURNED("No question returned", "The service returned no question and did not report the response as complete", Tone.NO_ANSWER, false),
    UNRECOGNISED_STATUS("Unknown status", "The service returned a response status this tool does not recognise", Tone.NO_ANSWER, false),
    RESPONSE_COMPLETED("Response complete", "The questionnaire response is complete", Tone.NEUTRAL, false),
    NOTHING_REQUIRED("Nothing asked", "The response is complete and no question was asked", Tone.NEUTRAL, false),
    RESPONSE_NOT_COMPLETE("Not complete", "The questionnaire response is not complete", Tone.ACTION, false),
    REQUIRED_UNANSWERED("Required unanswered", "A required question has no answer", Tone.ACTION, false),
    MEMBER_NOT_FOUND("Member not found", "The service does not recognise this member", Tone.DATA, false),
    PROVIDER_NOT_FOUND("Provider not found", "The service does not recognise this provider", Tone.DATA, false),
    REJECTED("Rejected", "The service did not accept the request as sent", Tone.FAULT, true),
    SERVICE_ERROR("Service error", "The service failed", Tone.FAULT, true),
    TIMEOUT("Timed out", "The service did not answer in time", Tone.FAULT, true),
    UNREACHABLE("Unreachable", "The service could not be reached", Tone.FAULT, true),
    MALFORMED_RESPONSE("Unreadable answer", "The service answered with no body, or with a body that could not be read", Tone.FAULT, true);

    private final String shortLabel;
    private final String label;
    private final Tone tone;
    private final boolean fault;

    DtrOutcome(String shortLabel, String label, Tone tone, boolean fault) {
        this.shortLabel = shortLabel;
        this.label = label;
        this.tone = tone;
        this.fault = fault;
    }

    @Override
    public String shortLabel() {
        return shortLabel;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Tone tone() {
        return tone;
    }

    @Override
    public boolean fault() {
        return fault;
    }
}
