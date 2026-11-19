package org.p32h.interop.epa.pave.model.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.Presence;
import java.util.List;

/**
 * Everything the tool understood in a CRD response, for the designed view.
 *
 * @param responseKind          what kind of thing came back
 * @param responseNote          one plain sentence about it, or null when it needs none
 * @param cardsPresence         whether the response carried a cards array at all, and if so whether it was empty
 * @param systemActionsPresence the same for systemActions
 * @param coverageInformation   every coverage information extension found, wherever it sat
 * @param issues                the issues of an OperationOutcome, when that is what came back
 * @param requestNotes          what the tool left out of, or worked out for, the request it built.
 *                              Empty when the tester's own body was sent
 * @param bodyBuiltByTool       false when the tester's edited body was sent in place of a built one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CrdDetail(CrdResponseKind responseKind, String responseNote, Presence cardsPresence, List<CardView> cards,
                        Presence systemActionsPresence, List<ActionView> systemActions,
                        List<CoverageInformationView> coverageInformation, List<IssueView> issues, List<String> requestNotes,
                        boolean bodyBuiltByTool) {

    public CrdDetail {
        cards = cards == null ? List.of() : List.copyOf(cards);
        systemActions = systemActions == null ? List.of() : List.copyOf(systemActions);
        coverageInformation = coverageInformation == null ? List.of() : List.copyOf(coverageInformation);
        issues = issues == null ? List.of() : List.copyOf(issues);
        requestNotes = requestNotes == null ? List.of() : List.copyOf(requestNotes);
    }

    /** The same detail with what is known about the request that produced it. */
    public CrdDetail withRequest(List<String> notes, boolean builtByTool) {
        return new CrdDetail(responseKind, responseNote, cardsPresence, cards, systemActionsPresence, systemActions,
                coverageInformation, issues, notes, builtByTool);
    }
}
