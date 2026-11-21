package org.p32h.interop.epa.pave.model;

/**
 * Outcomes that say something about this tool and not about the service, so every protocol shares
 * them: the guardrail refused to send, or the tool failed after the call was made.
 */
public enum RunOutcome implements OutcomeCode {

    /** The production guardrail refused a write. Nothing was sent. */
    BLOCKED("Blocked: nothing was sent", Tone.BLOCKED, false),
    /** The service was called and this tool then failed to read its answer. */
    TOOL_ERROR("This tool failed after the call was made", Tone.FAULT, true);

    private final String label;
    private final Tone tone;
    private final boolean fault;

    RunOutcome(String label, Tone tone, boolean fault) {
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
