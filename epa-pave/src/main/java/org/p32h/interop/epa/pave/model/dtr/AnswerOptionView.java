package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;

/**
 * One answer a question offers.
 *
 * @param label           what to show for the option: the display of a coding, else its code, else
 *                        the value as text. Null when the option carries no value
 * @param answerAs        the text that chooses this option when it is posted back as an answer: the
 *                        code of a coding, else the value as text. Null when the option carries no value
 * @param value           the value exactly as returned
 * @param initialSelected whether the questionnaire marks the option as selected to begin with
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerOptionView(String label, String answerAs, Shown value, Shown initialSelected) {
    /** Prints nothing of the label or the value, which come from a response. */
    @Override
    public String toString() {
        return "AnswerOptionView[not shown]";
    }
}
