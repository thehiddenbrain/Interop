package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import java.util.List;

/**
 * Decides the outcome of a PAS submission or inquiry from review action codes, the structure of
 * the response and transport facts. The disposition and every other piece of wording are never
 * read here.
 *
 * <p>The rule that matters most: a decision is only ever derived from a review action code that
 * was actually returned. A review action that is absent, null or empty contributes nothing, so a
 * response with none is {@link PasOutcome#NO_DECISION}: not approved, not pended and not denied.
 * The ClaimResponse outcome code says how far processing got and decides nothing either: a
 * response that is "complete" with no review action has still decided nothing.
 */
public final class PasOutcomeRules {

    private PasOutcomeRules() {
    }

    /** What one review action code establishes. A code outside the value set is its own outcome and is never mapped to a known one. */
    public static PasOutcome forCode(String code) {
        if (code == null) {
            return PasOutcome.NO_DECISION;
        }
        return switch (code) {
            case PasCodes.APPROVED -> PasOutcome.APPROVED;
            case PasCodes.PARTIAL -> PasOutcome.PARTIALLY_APPROVED;
            case PasCodes.DENIED -> PasOutcome.DENIED;
            case PasCodes.PENDED -> PasOutcome.PENDED;
            case PasCodes.MODIFIED -> PasOutcome.APPROVED_WITH_CHANGES;
            case PasCodes.CANCELLED -> PasOutcome.CANCELLED;
            case PasCodes.CONTACT_PAYER -> PasOutcome.CONTACT_PAYER;
            case PasCodes.NO_ACTION -> PasOutcome.NO_ACTION_REQUIRED;
            default -> PasOutcome.UNRECOGNISED_CODE;
        };
    }

    /**
     * What the review actions that apply to one item establish for it. None is no decision. One is
     * what its code says. Several that agree are that one decision. Several that disagree are not
     * settled by picking one: the item is {@link PasOutcome#MIXED}.
     *
     * @param reviewActions each review action code that applies. One that was present with no code
     *                      in it is ignored: it decides nothing
     */
    public static PasOutcome forItem(List<CodedValue> reviewActions) {
        List<PasOutcome> stated = reviewActions.stream().filter(action -> action.code() != null)
                .map(action -> forCode(action.code())).distinct().toList();
        if (stated.isEmpty()) {
            return PasOutcome.NO_DECISION;
        }
        return stated.size() == 1 ? stated.get(0) : PasOutcome.MIXED;
    }

    /**
     * What one ClaimResponse establishes.
     *
     * @param decisions           the decision for each requested item, or the one decision for the
     *                            whole request when its items are not known. An item the payer said
     *                            nothing about is in this list as no decision
     * @param errorsReported      true when the response lists errors or has the outcome code error
     * @param documentationWanted true when the response refers to a request for supporting
     *                            documentation that is not shown to be closed
     */
    public static PasOutcome forClaimResponse(List<PasOutcome> decisions, boolean errorsReported, boolean documentationWanted) {
        PasOutcome combined = combine(decisions);
        if (combined == PasOutcome.NO_DECISION && errorsReported) {
            return PasOutcome.REQUEST_ERRORS;
        }
        // A request for documentation changes the answer only while nothing final has been said.
        // An approval or a denial that also refers to one stays what it is.
        if (documentationWanted && (combined == PasOutcome.PENDED || combined == PasOutcome.NO_DECISION)) {
            return PasOutcome.ATTACHMENT_REQUIRED;
        }
        return combined;
    }

    /**
     * One outcome for several decisions. When they differ none of them is chosen to stand for the
     * request. Decisions that are all final, some approving and some not, are a partial approval.
     * Anything else that differs, such as one item approved and one pended or undecided, is
     * {@link PasOutcome#MIXED}: an item with no decision counts as exactly that, so an approval for
     * one item can never be read as the answer for another the payer said nothing about.
     */
    static PasOutcome combine(List<PasOutcome> decisions) {
        if (decisions.isEmpty()) {
            return PasOutcome.NO_DECISION;
        }
        PasOutcome first = decisions.get(0);
        if (decisions.stream().allMatch(first::equals)) {
            return first;
        }
        if (decisions.stream().allMatch(PasOutcome::finalDecision)) {
            boolean allApproved = decisions.stream().allMatch(d -> d == PasOutcome.APPROVED || d == PasOutcome.APPROVED_WITH_CHANGES);
            return allApproved ? PasOutcome.APPROVED_WITH_CHANGES : PasOutcome.PARTIALLY_APPROVED;
        }
        return PasOutcome.MIXED;
    }

    /**
     * The outcome of the whole call.
     *
     * @param authorizationNotFound  true when the client layer recognised that the payer does not
     *                               know the request an inquiry asked about
     * @param claimResponseOutcomes  what each ClaimResponse in the response establishes, in order.
     *                               Empty when the response carried none
     */
    public static PasOutcome overall(VendorExchange exchange, boolean authorizationNotFound, List<PasOutcome> claimResponseOutcomes) {
        return overall(exchange, authorizationNotFound, claimResponseOutcomes, false);
    }

    /**
     * @param emptyInquiryAnswer true when an inquiry was answered with a bundle that holds no
     *                           ClaimResponse and no OperationOutcome, which PAS 2.0.1 defines as
     *                           "zero matching authorizations"
     */
    public static PasOutcome overall(VendorExchange exchange, boolean authorizationNotFound, List<PasOutcome> claimResponseOutcomes,
                                     boolean emptyInquiryAnswer) {
        OnyxFailureType failure = exchange.failureType();
        if (failure == OnyxFailureType.TIMEOUT) {
            return PasOutcome.TIMEOUT;
        }
        if (failure == OnyxFailureType.UNAVAILABLE) {
            return PasOutcome.UNREACHABLE;
        }
        if (failure == OnyxFailureType.MALFORMED_RESPONSE) {
            return PasOutcome.MALFORMED_RESPONSE;
        }
        // Not found is a finding about the test data, so it is told apart from a fault even when it
        // arrives with an error status.
        if (exchange.has(VendorSignal.MEMBER_NOT_FOUND)) {
            return PasOutcome.MEMBER_NOT_FOUND;
        }
        if (exchange.has(VendorSignal.PROVIDER_NOT_FOUND)) {
            return PasOutcome.PROVIDER_NOT_FOUND;
        }
        if (authorizationNotFound) {
            return PasOutcome.AUTHORIZATION_NOT_FOUND;
        }
        if (failure == OnyxFailureType.SERVER_ERROR) {
            return PasOutcome.SERVICE_ERROR;
        }
        if (failure == OnyxFailureType.BAD_REQUEST) {
            return PasOutcome.REJECTED;
        }
        if (claimResponseOutcomes.isEmpty()) {
            // The service answered and sent no ClaimResponse. This is never treated as a decision.
            // For an inquiry, PAS 2.0.1 says what an empty answer bundle means: nothing matched.
            return emptyInquiryAnswer ? PasOutcome.NO_MATCHING_AUTHORIZATION : PasOutcome.NO_DECISION;
        }
        PasOutcome first = claimResponseOutcomes.get(0);
        return claimResponseOutcomes.stream().allMatch(first::equals) ? first : PasOutcome.MIXED;
    }
}
