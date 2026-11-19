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
 * @param reviewAction  a review action placed on the item itself and not on one of its adjudications,
 *                      or null. Read as well, because where a vendor puts it is not confirmed
 * @param extensions    every other extension on the item
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasItemView(String foundAt, Shown itemSequence, String procedureCode, String subject, ReviewActionView reviewAction,
                          List<AdjudicationView> adjudications, List<ExtensionView> extensions, List<Shown> noteNumbers) {

    public PasItemView {
        adjudications = adjudications == null ? List.of() : List.copyOf(adjudications);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
        noteNumbers = noteNumbers == null ? List.of() : List.copyOf(noteNumbers);
    }
}
