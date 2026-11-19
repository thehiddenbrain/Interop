package org.p32h.interop.epa.pave.model.dtr;

import org.p32h.interop.epa.pave.model.Shown;

/**
 * One enableWhen condition and whether it evaluated true. That is the behaviour under test for a
 * static questionnaire, so it is shown for every condition and never only for the ones that hold.
 *
 * @param question    the linkId of the question the condition looks at
 * @param answer      the value the condition compares with, as returned
 * @param explanation one plain sentence: why it holds, why it does not, or why it could not be evaluated
 */
public record EnableWhenView(Shown question, DtrCode operator, Shown answer, ConditionState state, String explanation) {
}
