package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import java.util.List;

/**
 * One ClaimResponse as returned: the payer's answer to one prior authorization request.
 *
 * <p>The decision is in {@code decisions}, read from review action codes only. The outcome code
 * and the disposition are shown beside it. The disposition is the payer's wording and is never
 * interpreted: a sentence that says "approved" on a response that carries no review action is
 * still a response with no decision.
 *
 * @param foundAt           where in the response the ClaimResponse sat
 * @param fullUrl           the full url of the bundle entry it came in, when it came in one
 * @param outcome           the ClaimResponse outcome code (queued, complete, error, partial) with
 *                          what the tool knows of it. It says how far processing got, not what was decided
 * @param disposition       the payer's wording, shown and never interpreted
 * @param preAuthRef        the authorization number
 * @param request           the claim this answers, as the response refers to it
 * @param requestIdentifier the identifier of that claim, when the response echoes it
 * @param reviewActions     each review action stated for the response as a whole. Normally none or one
 * @param decision          what this ClaimResponse establishes across all its decisions
 * @param decisions         one decision for each requested item, or one for the whole request
 *                          when its items are not known
 * @param attachments       each request for supporting documentation the response refers to
 * @param extensions        every other extension on the ClaimResponse
 * @param subsetted         true when the ClaimResponse declares the security tag SUBSETTED, by which
 *                          PAS 2.0.1 says it answers only some of the items of the request
 * @param addItemReviewActions where a review action sat on ClaimResponse.addItem.adjudication, which
 *                          the PAS 2.2.0 ballot defines and 2.0.1 does not. Such an action is listed
 *                          among the fields returned but not interpreted and decides nothing here
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClaimResponseView(
        String foundAt,
        Shown fullUrl,
        Shown id,
        Shown status,
        Shown type,
        Shown use,
        Shown patient,
        Shown created,
        Shown insurer,
        Shown requestor,
        CodedValue outcome,
        Shown disposition,
        Shown preAuthRef,
        Shown preAuthPeriodStart,
        Shown preAuthPeriodEnd,
        Shown request,
        Shown requestIdentifier,
        List<Shown> identifiers,
        List<ReviewActionView> reviewActions,
        PasOutcome decision,
        List<PasDecision> decisions,
        List<PasItemView> items,
        List<PasErrorView> errors,
        List<AttachmentRefView> attachments,
        List<ProcessNoteView> processNotes,
        List<ExtensionView> extensions,
        boolean subsetted,
        List<String> addItemReviewActions) {

    public ClaimResponseView {
        addItemReviewActions = addItemReviewActions == null ? List.of() : List.copyOf(addItemReviewActions);
        identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
        reviewActions = reviewActions == null ? List.of() : List.copyOf(reviewActions);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        items = items == null ? List.of() : List.copyOf(items);
        errors = errors == null ? List.of() : List.copyOf(errors);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        processNotes = processNotes == null ? List.of() : List.copyOf(processNotes);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** How this ClaimResponse is named when a result holds several: by its id, else by where it sat. */
    public String label() {
        return id.hasValue() ? "ClaimResponse/" + id.value() : foundAt;
    }

    /** True when the response refers to a request for documentation that is not shown to be closed. */
    public boolean documentationWanted() {
        return attachments.stream().anyMatch(AttachmentRefView::open);
    }

    /** True when the response reports errors, by listing them or by its outcome code. */
    public boolean errorsReported() {
        return !errors.isEmpty() || outcome.is("error");
    }

    /** Prints where it sat and what it decided. It holds an authorization number and the payer's wording. */
    @Override
    public String toString() {
        return "ClaimResponseView[" + foundAt + ", " + decision + "]";
    }
}
