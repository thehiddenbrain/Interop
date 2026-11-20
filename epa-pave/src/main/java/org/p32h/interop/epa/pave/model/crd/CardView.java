package org.p32h.interop.epa.pave.model.crd;

import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One CDS Hooks card, every field of it.
 *
 * @param index  the position of the card in the response, starting at zero
 * @param source whether the card's source object was not returned, null, empty or held something.
 *               The four source values say what was inside it, and cannot say this
 * @param lists  for each repeating element of the card ({@code links}, {@code suggestions},
 *               {@code overrideReasons}), whether it was not returned, null, empty or held
 *               something, so an empty list on screen is said to be the one it was
 */
public record CardView(
        int index,
        Shown uuid,
        Shown summary,
        Shown detail,
        Shown indicator,
        Shown sourceLabel,
        Shown sourceUrl,
        Shown sourceIcon,
        Shown sourceTopic,
        Shown selectionBehavior,
        List<CardLinkView> links,
        List<SuggestionView> suggestions,
        List<Shown> overrideReasons,
        Presence source,
        Map<String, Presence> lists) {

    public CardView {
        lists = lists == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(lists));
        links = links == null ? List.of() : List.copyOf(links);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        overrideReasons = overrideReasons == null ? List.of() : List.copyOf(overrideReasons);
    }
}
