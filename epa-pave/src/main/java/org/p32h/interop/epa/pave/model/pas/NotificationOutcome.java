package org.p32h.interop.epa.pave.model.pas;

import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * What arrived at the notification receiver.
 *
 * <p>A notification is never dropped, so every one of these is recorded. The first four say that
 * a notification arrived for a subscription this tool created, and of which kind. A notification
 * that names no subscription, or one no run in the ledger created, is
 * {@link #UNMATCHED_NOTIFICATION} whatever its kind, because that is the fact a tester has to look
 * at first, and its kind is kept beside it as a code.
 *
 * <p>{@link #UNCHECKED_NOTIFICATION} is a different state and is never reported as unmatched: the
 * ledger could not be asked, so whether this tool created the subscription is not known. The first
 * is a finding about who created a subscription. The second is a finding about this installation.
 */
public enum NotificationOutcome implements OutcomeCode {

    NOTIFICATION_RECEIVED("Notification", "An event notification arrived for a subscription this tool created", Tone.NEUTRAL, false),
    HANDSHAKE_RECEIVED("Handshake", "A handshake arrived for a subscription this tool created", Tone.NEUTRAL, false),
    HEARTBEAT_RECEIVED("Heartbeat", "A heartbeat arrived for a subscription this tool created", Tone.NEUTRAL, false),
    STATUS_RECEIVED("Status report", "A status report arrived for a subscription this tool created", Tone.NEUTRAL, false),
    UNRECOGNISED_TYPE("Unknown type", "A notification arrived whose type this tool does not recognise", Tone.NO_ANSWER, false),
    UNMATCHED_NOTIFICATION("Unmatched", "A notification arrived that matches no subscription this tool created", Tone.NEUTRAL, false),
    UNCHECKED_NOTIFICATION("Not checked", "A notification arrived and the ledger could not be asked which subscription it belongs to", Tone.FAULT, true),
    UNREADABLE_NOTIFICATION("Unreadable", "Something arrived at the receiver that is not a subscription notification", Tone.FAULT, true);

    private final String shortLabel;
    private final String label;
    private final Tone tone;
    private final boolean fault;

    NotificationOutcome(String shortLabel, String label, Tone tone, boolean fault) {
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
