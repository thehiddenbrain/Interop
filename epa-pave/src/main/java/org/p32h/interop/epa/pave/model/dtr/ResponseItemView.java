package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One item of a QuestionnaireResponse: the question it answers, the answers given, and the items
 * nested beneath it.
 *
 * @param path where the item sits in its response, for example {@code item[0].item[1]}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResponseItemView(String path, Shown linkId, Shown text, List<Shown> answers, List<ResponseItemView> items) {

    public ResponseItemView {
        answers = answers == null ? List.of() : List.copyOf(answers);
        items = items == null ? List.of() : List.copyOf(items);
    }
}
