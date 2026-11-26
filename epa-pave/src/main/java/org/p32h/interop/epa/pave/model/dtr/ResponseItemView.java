package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One item of a QuestionnaireResponse: the question it answers, the answers given, and the items
 * nested beneath it.
 *
 * @param path             where the item sits in its response, for example {@code item[0].item[1]}
 * @param answers          the value of each answer
 * @param origins          where each answer came from, one for each answer in the same order
 * @param answerExtensions every other extension its answers carry, in the order they sit
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseItemView(String path, Shown linkId, Shown text, List<Shown> answers, List<AnswerOriginView> origins,
                               List<ExtensionView> answerExtensions, List<ResponseItemView> items) {

    public ResponseItemView {
        answers = answers == null ? List.of() : List.copyOf(answers);
        origins = origins == null ? List.of() : List.copyOf(origins);
        answerExtensions = answerExtensions == null ? List.of() : List.copyOf(answerExtensions);
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Prints where the item sits. Its answers can be a member's. */
    @Override
    public String toString() {
        return "ResponseItemView[" + path + "]";
    }
}
