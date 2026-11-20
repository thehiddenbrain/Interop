package org.p32h.interop.epa.pave.model.crd;

import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One suggestion on a card, with its actions.
 *
 * @param lists whether {@code actions} was not returned, null, empty or held something
 */
public record SuggestionView(Shown label, Shown uuid, Shown isRecommended, List<ActionView> actions, Map<String, Presence> lists) {

    public SuggestionView {
        actions = actions == null ? List.of() : List.copyOf(actions);
        lists = lists == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(lists));
    }
}
