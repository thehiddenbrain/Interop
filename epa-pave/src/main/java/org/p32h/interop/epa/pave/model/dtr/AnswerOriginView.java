package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * Where one answer of a QuestionnaireResponse came from, as its information-origin extension says.
 * The DTR guide has every answer say it: pre-populated, pre-populated and then changed by a
 * person, or given by a person. An answer that carries no such extension has every part absent,
 * which is itself something a tester needs to see.
 *
 * @param source auto, override or manual, as returned
 * @param author the practitioner the extension names as the author of the answer
 * @param roles  each role it gives that author
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerOriginView(Shown source, Shown author, List<Shown> roles) {

    public AnswerOriginView {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    public static AnswerOriginView notStated() {
        return new AnswerOriginView(Shown.absent(), Shown.absent(), List.of());
    }
}
