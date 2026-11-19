package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import java.util.List;

/**
 * What was decided for one thing that was asked about: one requested item, or the request as a
 * whole when its items are not known.
 *
 * <p>There is one of these for every requested item, whether or not the response lists it, so an
 * item the payer said nothing about is on the result as exactly that and cannot be read as sharing
 * another item's decision.
 *
 * @param subject       how this decision is named everywhere on the result: the requested procedure
 *                      code, else the item's sequence, else where it sat. Null when the result holds
 *                      one decision about the whole request
 * @param procedureCode the requested procedure code, or null when the item is tied to none
 * @param sequence      the sequence of the requested item, or null
 * @param foundAt       where the item sat in the response, or null when the response does not list it
 * @param reviewAction  the review action code that decides this item, with what the tool knows of it
 * @param source        whether that code sat on the item or on the response as a whole
 * @param outcome       what the code establishes for this item
 * @param reasonCodes   the coded reasons given with the decision
 * @param errorCodes    the codes of the errors the response reported against this item, or against
 *                      the request as a whole
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasDecision(String subject, String procedureCode, Integer sequence, String foundAt, CodedValue reviewAction,
                          DecisionSource source, PasOutcome outcome, List<String> reasonCodes, List<String> errorCodes) {

    public PasDecision {
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        errorCodes = errorCodes == null ? List.of() : List.copyOf(errorCodes);
    }
}
