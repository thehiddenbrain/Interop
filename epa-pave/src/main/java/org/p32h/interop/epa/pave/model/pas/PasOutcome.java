package org.p32h.interop.epa.pave.model.pas;

import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * What one PAS submission or inquiry established.
 *
 * <p>Unlike CRD, PAS carries real decisions, a denial included, and this vocabulary says so
 * plainly: {@link #DENIED} is a denial and is toned as one. A tester who expects a denial and gets
 * one sees the outcome Denied beside the test result Passed, and neither stands in for the other.
 *
 * <p>{@link #NO_DECISION} is the rule this tool exists for, in its PAS form. A response that
 * carries no review action has not approved, pended or denied anything, and it is never read as
 * any of the three. {@link #APPROVED} needs a review action that says so.
 *
 * <p>The faults and the two not-found outcomes carry the names and the labels the other protocols
 * use for them, so a history list reads alike across tabs.
 */
public enum PasOutcome implements OutcomeCode {

    APPROVED("The authorization is approved", Tone.CLEAR, false),
    APPROVED_WITH_CHANGES("The authorization is approved with changes", Tone.CLEAR, false),
    PARTIALLY_APPROVED("The authorization is partially approved", Tone.ACTION, false),
    DENIED("The authorization is denied", Tone.STOP, false),
    PENDED("The authorization is pended: there is no final decision yet", Tone.PENDING, false),
    ATTACHMENT_REQUIRED("More documentation is required before a decision", Tone.ACTION, false),
    CONTACT_PAYER("The payer asks to be contacted about this request", Tone.ACTION, false),
    CANCELLED("The authorization request is cancelled", Tone.NEUTRAL, false),
    NO_ACTION_REQUIRED("The payer answered that no action is required", Tone.CLEAR, false),
    MIXED("The items did not all receive the same decision", Tone.ACTION, false),
    UNRECOGNISED_CODE("The service returned a decision code this tool does not recognise", Tone.NO_ANSWER, false),
    REQUEST_ERRORS("The payer returned errors and no decision", Tone.ACTION, false),
    NO_DECISION("The service returned no decision", Tone.NO_ANSWER, false),
    MEMBER_NOT_FOUND("The service does not recognise this member", Tone.DATA, false),
    PROVIDER_NOT_FOUND("The service does not recognise this provider", Tone.DATA, false),
    AUTHORIZATION_NOT_FOUND("The service does not recognise this authorization request", Tone.DATA, false),
    /**
     * An inquiry answered with a bundle that holds no ClaimResponse. PAS 2.0.1 answers an inquiry
     * with zero to many ClaimResponses, one for each authorization that matches, so none is the
     * guide's way of saying that nothing matched. It is an answer, not a decision and not a fault.
     */
    NO_MATCHING_AUTHORIZATION("No prior authorization matches the inquiry", Tone.DATA, false),
    REJECTED("The service did not accept the request as sent", Tone.FAULT, true),
    SERVICE_ERROR("The service failed", Tone.FAULT, true),
    TIMEOUT("The service did not answer in time", Tone.FAULT, true),
    UNREACHABLE("The service could not be reached", Tone.FAULT, true),
    MALFORMED_RESPONSE("The service answered with no body, or with a body that could not be read", Tone.FAULT, true);

    private final String label;
    private final Tone tone;
    private final boolean fault;

    PasOutcome(String label, Tone tone, boolean fault) {
        this.label = label;
        this.tone = tone;
        this.fault = fault;
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

    /**
     * True for a decision the payer will not change by being asked again: approved, approved with
     * changes, partially approved, denied. A mix of these across items is a partial approval; a mix
     * that includes anything else is not, because part of the request is still open.
     */
    public boolean finalDecision() {
        return this == APPROVED || this == APPROVED_WITH_CHANGES || this == PARTIALLY_APPROVED || this == DENIED;
    }
}
