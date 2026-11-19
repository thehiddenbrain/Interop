package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import java.util.List;

/**
 * One review action: the payer's coded decision, as returned on the response or on one item.
 *
 * <p>The code is the decision. Everything else here is shown beside it and never decides anything.
 *
 * @param foundAt     where in the response the review action sat
 * @param code        the review action code with what the tool knows about it. An unknown code is
 *                    kept and shown as "Unrecognised code: X". A review action that carries no
 *                    code at all has the presence EMPTY here, never ABSENT: the review action was
 *                    sent, it just decides nothing
 * @param returned    the code element exactly as the payer wrote it, with its code system and its
 *                    own display
 * @param number      the authorization number the review action carries, when it carries one
 * @param reason      the coded reason for the decision, for display
 * @param reasonCodes the reason code values, for a declared expectation to assert on
 * @param otherParts  every part of the review action without a slot above
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewActionView(String foundAt, CodedValue code, Shown returned, Shown number, Shown reason, List<String> reasonCodes,
                               Shown secondSurgicalOpinionNeeded, List<ExtensionView> otherParts) {

    public ReviewActionView {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        otherParts = otherParts == null ? List.of() : List.copyOf(otherParts);
    }

    /** Prints where it was found. Everything else comes from a response. */
    @Override
    public String toString() {
        return "ReviewActionView[" + foundAt + "]";
    }
}
