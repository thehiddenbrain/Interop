package org.p32h.interop.epa.pave.model;

/**
 * Outcomes that say something about this tool and not about the service, so every protocol shares
 * them: the guardrail refused to send, the tool could not sign in, or the tool failed after the
 * call was made.
 */
public enum RunOutcome implements OutcomeCode {

    /** The production guardrail refused a write. Nothing was sent. */
    BLOCKED("Blocked", "Blocked: nothing was sent", Tone.BLOCKED, false),
    /**
     * Authentication is on and this tool obtained no access token, so nothing was sent. Neutral, not
     * the guardrail's purple: that frame keeps the one meaning the colour key gives it.
     */
    NOT_SIGNED_IN("Not signed in", "Not sent: this tool could not sign in", Tone.NEUTRAL, false),
    /** The service was called and this tool then failed to read its answer. */
    TOOL_ERROR("Tool error", "This tool failed after the call was made", Tone.FAULT, true);

    private final String shortLabel;
    private final String label;
    private final Tone tone;
    private final boolean fault;

    RunOutcome(String shortLabel, String label, Tone tone, boolean fault) {
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
