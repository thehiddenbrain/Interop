package org.p32h.interop.epa.pave.model.pas;

import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * What one subscription operation established: create, read, list, update or delete.
 *
 * <p>{@link #NOT_FOUND} is a finding about the id that was asked for, not about the environment,
 * and is kept apart from the faults for that reason. The faults carry the names and the labels the
 * other protocols use for them.
 */
public enum SubscriptionOutcome implements OutcomeCode {

    CREATED("Created", "The subscription was created", Tone.NEUTRAL, false),
    READ("Read", "The subscription was returned", Tone.NEUTRAL, false),
    LISTED("Listed", "The subscriptions were listed", Tone.NEUTRAL, false),
    UPDATED("Updated", "The subscription was updated", Tone.NEUTRAL, false),
    DELETED("Deleted", "The subscription was deleted", Tone.CLEAR, false),
    NOT_FOUND("Not found", "The service does not know this subscription", Tone.DATA, false),
    // Not "Unreadable answer", which MALFORMED_RESPONSE says: this one is a readable answer that is not a subscription.
    UNRECOGNISED_RESPONSE("Not a subscription", "The service answered with something this tool could not read as a subscription", Tone.NO_ANSWER, false),
    REJECTED("Rejected", "The service did not accept the request as sent", Tone.FAULT, true),
    SERVICE_ERROR("Service error", "The service failed", Tone.FAULT, true),
    TIMEOUT("Timed out", "The service did not answer in time", Tone.FAULT, true),
    UNREACHABLE("Unreachable", "The service could not be reached", Tone.FAULT, true),
    MALFORMED_RESPONSE("Unreadable answer", "The service answered with no body, or with a body that could not be read", Tone.FAULT, true);

    private final String shortLabel;
    private final String label;
    private final Tone tone;
    private final boolean fault;

    SubscriptionOutcome(String shortLabel, String label, Tone tone, boolean fault) {
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
