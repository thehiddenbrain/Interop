package org.p32h.interop.epa.pave.model.pas;

import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * What arrived at the notification receiver.
 *
 * <p>A notification is never dropped, so every one of these is recorded. The first four say that
 * a notification arrived for a subscription this tool created, and of which kind. A notification
 * for a subscription the tool does not know is {@link #UNMATCHED_NOTIFICATION} whatever its kind,
 * because that is the fact a tester has to look at first, and its kind is kept beside it as a code.
 */
public enum NotificationOutcome implements OutcomeCode {

    NOTIFICATION_RECEIVED("An event notification arrived for a subscription this tool created", Tone.NEUTRAL, false),
    HANDSHAKE_RECEIVED("A handshake arrived for a subscription this tool created", Tone.NEUTRAL, false),
    HEARTBEAT_RECEIVED("A heartbeat arrived for a subscription this tool created", Tone.NEUTRAL, false),
    STATUS_RECEIVED("A status report arrived for a subscription this tool created", Tone.NEUTRAL, false),
    UNRECOGNISED_TYPE("A notification arrived whose type this tool does not recognise", Tone.NO_ANSWER, false),
    UNMATCHED_NOTIFICATION("A notification arrived that matches no subscription this tool created", Tone.DATA, false),
    UNREADABLE_NOTIFICATION("Something arrived at the receiver that is not a subscription notification", Tone.FAULT, true);

    private final String label;
    private final Tone tone;
    private final boolean fault;

    NotificationOutcome(String label, Tone tone, boolean fault) {
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
}
