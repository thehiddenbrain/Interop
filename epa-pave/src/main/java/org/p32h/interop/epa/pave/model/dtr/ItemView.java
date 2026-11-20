package org.p32h.interop.epa.pave.model.dtr;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One item of a questionnaire: a group, a question or a line of display text, with the items
 * nested beneath it.
 *
 * @param path          where the item sits in its questionnaire, for example {@code item[0].item[1]}
 * @param question      true when the item takes an answer. False for a group and for display text
 * @param initial       the initial values the questionnaire itself states for the item
 * @param prepopulated  the answers the package arrived with for this item, from its
 *                      QuestionnaireResponse. Empty when it arrived with none
 * @param enabled       whether the answers given enable the item
 * @param enabledReason one plain sentence saying why, or null for an item with no condition whose
 *                      group is enabled
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ItemView(
        String path,
        Shown linkId,
        Shown text,
        Shown prefix,
        DtrCode type,
        boolean question,
        Shown required,
        Shown repeats,
        Shown readOnly,
        Shown answerValueSet,
        List<AnswerOptionView> answerOptions,
        List<Shown> initial,
        List<Shown> prepopulated,
        DtrCode enableBehavior,
        List<EnableWhenView> enableWhen,
        ItemEnabled enabled,
        String enabledReason,
        List<ExtensionView> extensions,
        List<ItemView> items) {

    public ItemView {
        answerOptions = answerOptions == null ? List.of() : List.copyOf(answerOptions);
        initial = initial == null ? List.of() : List.copyOf(initial);
        prepopulated = prepopulated == null ? List.of() : List.copyOf(prepopulated);
        enableWhen = enableWhen == null ? List.of() : List.copyOf(enableWhen);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Prints where the item sits. Its text, its answers and its prepopulated values come from a response. */
    @Override
    public String toString() {
        return "ItemView[" + path + "]";
    }
}
