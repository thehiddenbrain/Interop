package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What changed between the QuestionnaireResponse that was sent and the one that came back.
 *
 * <p>A question that appears or disappears because of an earlier answer is the behaviour under
 * test in the adaptive loop, so each is named here by linkId and text and the screen does not have
 * to work it out.
 *
 * @param compared       false when there was nothing to compare with, and the summary says why
 * @param summary        one plain sentence
 * @param appeared       questions the response asks that the request did not hold
 * @param disappeared    questions the request held that the response no longer asks
 * @param answersDropped questions whose answers were sent and are not in the response
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChangesView(boolean compared, String summary, List<QuestionRef> appeared, List<QuestionRef> disappeared,
                          List<QuestionRef> answersDropped) {

    public ChangesView {
        appeared = appeared == null ? List.of() : List.copyOf(appeared);
        disappeared = disappeared == null ? List.of() : List.copyOf(disappeared);
        answersDropped = answersDropped == null ? List.of() : List.copyOf(answersDropped);
    }

    /** A step with nothing to compare against, saying why. */
    public static ChangesView notCompared(String why) {
        return new ChangesView(false, why, List.of(), List.of(), List.of());
    }

    /** Prints whether a comparison was made. The questions it names come from a response. */
    @Override
    public String toString() {
        return "ChangesView[" + (compared ? "compared" : "not compared") + "]";
    }
}
