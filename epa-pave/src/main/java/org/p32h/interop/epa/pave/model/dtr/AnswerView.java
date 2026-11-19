package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The answers given to one question, as text for the screen.
 *
 * @param text   the question as the payer worded it, or null when it is not known
 * @param values each answer: the display of a coding with its code, or the value as text
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerView(String linkId, String text, List<String> values) {

    public AnswerView {
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** Prints the linkId and how many answers, never the answers: they can be clinical. */
    @Override
    public String toString() {
        return "AnswerView[" + linkId + ", " + values.size() + " answer(s)]";
    }
}
