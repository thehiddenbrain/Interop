package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A question named the way a tester can find it: by its linkId and its text.
 *
 * @param text the question as the payer worded it, or null when the item carries no text
 * @param note anything more to say about why it is listed, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionRef(String linkId, String text, String note) {

    public QuestionRef(String linkId, String text) {
        this(linkId, text, null);
    }

    /** The linkId with the text in quotes after it, for a sentence. */
    public String named() {
        return text == null ? linkId : linkId + " \"" + text + "\"";
    }

    /** Prints nothing of the question, which comes from a response. */
    @Override
    public String toString() {
        return "QuestionRef[not shown]";
    }
}
