package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;

/**
 * One enableWhen condition and whether it evaluated true. That is the behaviour under test for a
 * static questionnaire, so it is shown for every condition and never only for the ones that hold.
 *
 * @param question    the linkId of the question the condition looks at
 * @param answer      the value the condition compares with, as returned
 * @param explanation one plain sentence: why it holds, why it does not, or why it could not be evaluated
 * @param stateLabel  the words for {@code state}, taken from the state. Null only when the state is null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnableWhenView(Shown question, DtrCode operator, Shown answer, ConditionState state, String explanation, String stateLabel) {

    public EnableWhenView {
        stateLabel = state == null ? null : state.label();
    }

    /** A condition with the words for its state taken from the state. */
    public EnableWhenView(Shown question, DtrCode operator, Shown answer, ConditionState state, String explanation) {
        this(question, operator, answer, state, explanation, null);
    }

    /** Prints the state only. The question and the answer come from a response. */
    @Override
    public String toString() {
        return "EnableWhenView[" + state + "]";
    }
}
