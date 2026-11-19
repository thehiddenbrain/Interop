package com.thehiddenbrain.interop.epa.pave.model.crd;

import com.thehiddenbrain.interop.epa.pave.model.IssueView;
import com.thehiddenbrain.interop.epa.pave.model.Presence;
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
 */
public record CrdDetail(CrdResponseKind responseKind, String responseNote, Presence cardsPresence, List<CardView> cards,
                        Presence systemActionsPresence, List<ActionView> systemActions,
                        List<CoverageInformationView> coverageInformation, List<IssueView> issues) {

    public CrdDetail {
        cards = cards == null ? List.of() : List.copyOf(cards);
        systemActions = systemActions == null ? List.of() : List.copyOf(systemActions);
        coverageInformation = coverageInformation == null ? List.of() : List.copyOf(coverageInformation);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
