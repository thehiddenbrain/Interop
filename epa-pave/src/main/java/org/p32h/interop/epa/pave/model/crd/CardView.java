package org.p32h.interop.epa.pave.model.crd;

import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One CDS Hooks card, every field of it.
 *
 * @param index the position of the card in the response, starting at zero
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
        List<Shown> overrideReasons) {

    public CardView {
        links = links == null ? List.of() : List.copyOf(links);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        overrideReasons = overrideReasons == null ? List.of() : List.copyOf(overrideReasons);
    }
}
