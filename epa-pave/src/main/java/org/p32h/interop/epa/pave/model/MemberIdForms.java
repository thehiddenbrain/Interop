package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * A member ID as the tester typed it and as it is sent.
 *
 * <p>Destinations want different forms of one ID, and the tool converts on the way out by the
 * environment's {@link MemberIdRule}. It always shows both forms and names the rule, so the
 * transformation is never silent.
 *
 * @param typed   exactly what the tester entered
 * @param sent    the form that goes on the wire
 * @param changed true when the two differ
 * @param rule    the word of the rule that produced {@code sent}: packed, spaced or split
 * @param note    why the rule did not apply to this ID (it was sent packed instead), or null when it applied
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"typed", "sent", "changed", "rule", "note"})
public record MemberIdForms(String typed, String sent, boolean changed, String rule, String note) {

    public MemberIdForms {
        note = Texts.present(note) ? note : null;
    }

    /** Both forms under the packed rule, which is what every caller built before rules existed. */
    public MemberIdForms(String typed, String sent, boolean changed) {
        this(typed, sent, changed, MemberIdRule.PACKED.word(), null);
    }

    /** Redacted, because a member ID must not reach a log through a default toString. */
    @Override
    public String toString() {
        return "MemberIdForms[redacted, changed=" + changed + ", rule=" + rule + "]";
    }
}
