package org.p32h.interop.epa.pave.model;

/**
 * A member ID as the tester typed it and as it is sent.
 *
 * <p>Some member IDs are written with spaces and the service requires the no-space form. The tool
 * converts on the way out and always shows both, so the transformation is never silent.
 *
 * @param typed   exactly what the tester entered
 * @param sent    the no-space form that goes on the wire
 * @param changed true when the two differ
 */
public record MemberIdForms(String typed, String sent, boolean changed) {

    /** Redacted, because a member ID must not reach a log through a default toString. */
    @Override
    public String toString() {
        return "MemberIdForms[redacted, changed=" + changed + "]";
    }
}
