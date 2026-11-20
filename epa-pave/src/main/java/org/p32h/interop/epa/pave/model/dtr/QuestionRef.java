package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A question named the way a tester can find it: by its linkId and its text.
 *
 * @param text the question as the payer worded it, or null when the item carries no text
 * @param note anything more to say about why it is listed, or null
 * @param values the answer or answers this question held, as they are shown, where that is why it is
 *               listed (an answer that was sent and did not come back). Null otherwise. They are
 *               clinical answers: on the result only, never on a ledger line or in a log
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionRef(String linkId, String text, String note, List<String> values) {

    public QuestionRef {
        values = values == null ? null : List.copyOf(values);
    }

    public QuestionRef(String linkId, String text, String note) {
        this(linkId, text, note, null);
    }

    public QuestionRef(String linkId, String text) {
        this(linkId, text, null, null);
    }

    /** The same question with the answers it held. */
    public QuestionRef withValues(List<String> held) {
        return new QuestionRef(linkId, text, note, held);
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
