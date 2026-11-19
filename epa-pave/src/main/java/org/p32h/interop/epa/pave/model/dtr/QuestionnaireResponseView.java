package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * A QuestionnaireResponse in full: one a package arrived with, the one a step of the adaptive loop
 * returned, or the one this tool put together from the answers given.
 *
 * @param foundAt       where in the response it sat, or a plain phrase when the tool built it
 * @param questionnaire the questionnaire it says it answers
 * @param status        its status, which for the adaptive loop is the completion status
 * @param answeredCount how many of its items carry at least one answer
 * @param json          the resource as compact JSON. The next step posts it back, and when it is
 *                      complete it is what the prior authorization request includes
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionnaireResponseView(String foundAt, Shown id, Shown questionnaire, DtrCode status, Shown authored, Shown subject,
                                        Shown author, List<ResponseItemView> items, List<ExtensionView> extensions,
                                        int answeredCount, String json) {

    public QuestionnaireResponseView {
        items = items == null ? List.of() : List.copyOf(items);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Prints where it sat and how much it answers, never the answers: they can be clinical. */
    @Override
    public String toString() {
        return "QuestionnaireResponseView[" + foundAt + ", answered=" + answeredCount + "]";
    }
}
