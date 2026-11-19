package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One item of a ClaimResponse as returned: its adjudications and its extensions.
 *
 * <p>What was decided for the item is not here but in {@link PasDecision}, because a decision can
 * also be stated for the response as a whole, and for an item the response does not list at all.
 *
 * @param foundAt       where in the response the item sat
 * @param itemSequence  the sequence of the requested item this answers, as returned
 * @param procedureCode the requested procedure code the item is for, or null when that cannot be told
 * @param subject       how the item is named everywhere on the result
 * @param reviewActions each review action placed on the item itself and not on one of its
 *                      adjudications. Read as well, because where a vendor puts it is not confirmed
 * @param extensions    every other extension on the item
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasItemView(String foundAt, Shown itemSequence, String procedureCode, String subject, List<ReviewActionView> reviewActions,
                          List<AdjudicationView> adjudications, List<ExtensionView> extensions, List<Shown> noteNumbers) {

    public PasItemView {
        reviewActions = reviewActions == null ? List.of() : List.copyOf(reviewActions);
        adjudications = adjudications == null ? List.of() : List.copyOf(adjudications);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        noteNumbers = noteNumbers == null ? List.of() : List.copyOf(noteNumbers);
    }

    /** The same item, tied to the requested code it is for. */
    public PasItemView attributedTo(String code, String name) {
        return new PasItemView(foundAt, itemSequence, code, name, reviewActions, adjudications, extensions, noteNumbers);
    }
}
