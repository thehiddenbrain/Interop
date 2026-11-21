package org.p32h.interop.epa.pave.model.raw;

import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * What happened when a collection request was sent as written. The tool does not know what such a
 * request means, so these outcomes say only what came back, never whether it was the right answer.
 */
public enum RawOutcome implements OutcomeCode {

    ANSWERED("The service answered", Tone.NEUTRAL, false),
    /** A success whose body is not JSON. For a request this tool does not interpret, that is not a fault. */
    ANSWERED_NOT_JSON("The service answered with a body that is not JSON", Tone.NEUTRAL, false),
    ANSWERED_NO_BODY("The service answered with no body", Tone.NEUTRAL, false),
    REJECTED("The service did not accept the request as sent", Tone.FAULT, true),
    SERVICE_ERROR("The service failed", Tone.FAULT, true),
    TIMEOUT("The service did not answer in time", Tone.FAULT, true),
    UNREACHABLE("The service could not be reached", Tone.FAULT, true),
    /**
     * Only a body cut off part way. The shared classification also calls a complete body that is not
     * JSON malformed; here that is ANSWERED_NOT_JSON, since a request this tool does not interpret
     * may answer with something other than JSON.
     */
    MALFORMED_RESPONSE("The service started to answer and the answer did not arrive in full", Tone.FAULT, true);

    private final String label;
    private final Tone tone;
    private final boolean fault;

    RawOutcome(String label, Tone tone, boolean fault) {
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
