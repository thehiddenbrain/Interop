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
 * @param reviewActions every review action code that applies to this item, each with what the tool
 *                      knows of it. Normally one. Empty when no review action applies, which is the
 *                      state "no decision". More than one only when the response states several for
 *                      the same item, and then none of them is chosen over the others
 * @param source        whether those codes sat on the item or on the response as a whole
 * @param outcome       what the codes establish for this item
 * @param reasonCodes   the coded reasons given with the decision
 * @param errorCodes    the codes of the errors the response reported against this item, or against
 *                      the request as a whole
 * @param authorizationNumbers the authorization numbers stated for this item: the number part of its
 *                      review action, which is where PAS 2.0.1 puts the item's authorization number.
 *                      Empty when none was stated
 * @param authorizedFrom the start of the period the item's authorization is valid for, from the item's
 *                      itemPreAuthPeriod extension, where PAS 2.0.1 puts it. Null when not stated
 * @param authorizedTo  the end of that period. Null when not stated
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasDecision(String subject, String procedureCode, Integer sequence, String foundAt, List<CodedValue> reviewActions,
                          DecisionSource source, PasOutcome outcome, List<String> reasonCodes, List<String> errorCodes,
                          List<String> authorizationNumbers, String authorizedFrom, String authorizedTo) {

    public PasDecision {
        reviewActions = reviewActions == null ? List.of() : List.copyOf(reviewActions);
        reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        errorCodes = errorCodes == null ? List.of() : List.copyOf(errorCodes);
        authorizationNumbers = authorizationNumbers == null ? List.of() : List.copyOf(authorizationNumbers);
    }

    /** A decision with no authorization number and no period stated. */
    public PasDecision(String subject, String procedureCode, Integer sequence, String foundAt, List<CodedValue> reviewActions,
                       DecisionSource source, PasOutcome outcome, List<String> reasonCodes, List<String> errorCodes) {
        this(subject, procedureCode, sequence, foundAt, reviewActions, source, outcome, reasonCodes, errorCodes, null, null, null);
    }

    /** The review action code values that were actually returned, without the ones that were present and empty. */
    public List<String> codes() {
        return reviewActions.stream().map(CodedValue::code).filter(code -> code != null).distinct().toList();
    }

    /** The same decision under another name, for a result that holds several ClaimResponses. */
    public PasDecision named(String newSubject) {
        return new PasDecision(newSubject, procedureCode, sequence, foundAt, reviewActions, source, outcome, reasonCodes, errorCodes,
                authorizationNumbers, authorizedFrom, authorizedTo);
    }
}
