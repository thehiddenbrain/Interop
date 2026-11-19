package com.thehiddenbrain.interop.epa.pave.model.crd;

import com.thehiddenbrain.interop.epa.pave.model.Shown;
import java.util.List;

/** One suggestion on a card, with its actions. */
public record SuggestionView(Shown label, Shown uuid, Shown isRecommended, List<ActionView> actions) {

    public SuggestionView {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
