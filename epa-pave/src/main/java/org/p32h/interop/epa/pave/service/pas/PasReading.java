package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.model.pas.PasItemView;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.ReviewActionView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the mapper made of one PAS response.
 *
 * @param sent             the claim the request carried: its identifier and its items. Empty when
 *                         the request is not known
 * @param additionalFields everything the response carried that the designed view did not use
 */
public record PasReading(PasDetail detail, PasOutcome outcome, SentClaim sent, List<AdditionalField> additionalFields) {

    public PasReading {
        sent = sent == null ? SentClaim.unknown() : sent;
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    /** Every decision the response holds, across its ClaimResponses, in order. */
    public List<PasDecision> decisions() {
        return detail.claimResponses().stream().flatMap(response -> response.decisions().stream()).toList();
    }

    /** True when the result holds more than one decision to tell apart. Each is then named by its subject wherever it is shown. */
    public boolean several() {
        return several(detail.claimResponses());
    }

    private static boolean several(List<ClaimResponseView> claimResponses) {
        return claimResponses.stream().mapToInt(response -> response.decisions().size()).sum() > 1;
    }

    /**
     * What was asked about, for the expectation check: each item the request carried, by the name
     * its decision goes under. The check works out for itself which of these went unanswered.
     */
    public List<String> asked() {
        return sent.items().stream().map(SentClaim.Item::label).distinct().toList();
    }

    /**
     * The answers a declared expectation is compared with: one for each decision, each with its own
     * codes. The codes of different items are never pooled, so the same expectation can hold for
     * one item and fail for another.
     */
    public List<Expectations.Answer> answers() {
        List<Expectations.Answer> out = new ArrayList<>();
        for (ClaimResponseView response : detail.claimResponses()) {
            for (PasDecision decision : response.decisions()) {
                out.add(Expectations.Answer.of(decision.subject(), codes(response, decision)));
            }
        }
        return out;
    }

    /**
     * The codes one decision carried, by the element names an expectation uses. An item with no
     * review action has no {@code review-action} entry at all, so no assertion on that element can
     * hold for it.
     */
    public static Map<String, List<String>> codes(ClaimResponseView response, PasDecision decision) {
        Map<String, List<String>> codes = new LinkedHashMap<>();
        put(codes, PasCodes.REVIEW_ACTION, decision.codes());
        put(codes, PasCodes.REVIEW_REASON, decision.reasonCodes());
        if (response.outcome().code() != null) {
            put(codes, PasCodes.CLAIM_OUTCOME, List.of(response.outcome().code()));
        }
        put(codes, PasCodes.ERROR_CODE, decision.errorCodes());
        return codes;
    }

    /**
     * The codes for the ledger, as {@code name=code} pairs. When the result holds several decisions
     * each pair starts with its subject, {@code 95810: review-action=A1}, and an item with nothing
     * decided and nothing reported against it is written as {@code 95811: (no answer)}, so a later
     * run in which two items swap decisions does not write the same line. An authorization number
     * is left out: it is not a code and has its own place on the record.
     */
    public List<String> ledgerCodes() {
        return ledgerCodes(detail.claimResponses());
    }

    /**
     * The same codes for ClaimResponses that did not arrive as the answer to a call, such as the
     * ones a notification carries, so a decision is written to the ledger the same way however it
     * reached this tool.
     */
    public static List<String> ledgerCodes(List<ClaimResponseView> claimResponses) {
        boolean named = several(claimResponses);
        boolean severalResponses = claimResponses.size() > 1;
        List<String> out = new ArrayList<>();
        for (ClaimResponseView response : claimResponses) {
            if (response.outcome().code() != null) {
                out.add((severalResponses ? response.foundAt() + ": " : "") + PasCodes.CLAIM_OUTCOME + "=" + response.outcome().code());
            }
            for (PasDecision decision : response.decisions()) {
                // The subject written to the ledger is the code that was asked about, or where the
                // item sat in the response. It is never text the response supplied.
                String subject = ledgerSubject(response, decision);
                String prefix = named ? subject + ": " : "";
                int before = out.size();
                decision.codes().forEach(code -> out.add(prefix + PasCodes.REVIEW_ACTION + "=" + code));
                decision.reasonCodes().forEach(code -> out.add(prefix + PasCodes.REVIEW_REASON + "=" + code));
                decision.errorCodes().forEach(code -> out.add(prefix + PasCodes.ERROR_CODE + "=" + code));
                if (named && out.size() == before) {
                    out.add(subject + ": (no answer)");
                }
            }
        }
        return out;
    }

    private static String ledgerSubject(ClaimResponseView response, PasDecision decision) {
        if (decision.procedureCode() != null) {
            return decision.procedureCode();
        }
        if (decision.foundAt() != null) {
            return decision.foundAt();
        }
        return decision.sequence() == null ? response.foundAt() : "item-" + decision.sequence();
    }

    /**
     * The authorization number the response carries: the pre-authorization reference of the first
     * ClaimResponse that has one, otherwise the first number a review action carries. Null when
     * the response carries none.
     */
    public String authorizationRef() {
        return authorizationRef(detail.claimResponses());
    }

    /** As {@link #authorizationRef()}, for ClaimResponses that arrived some other way than as the answer to a call. */
    public static String authorizationRef(List<ClaimResponseView> claimResponses) {
        for (ClaimResponseView response : claimResponses) {
            if (response.preAuthRef().hasValue()) {
                return response.preAuthRef().value();
            }
        }
        for (ClaimResponseView response : claimResponses) {
            List<ReviewActionView> actions = new ArrayList<>(response.reviewActions());
            for (PasItemView item : response.items()) {
                actions.addAll(item.reviewActions());
                item.adjudications().forEach(adjudication -> actions.addAll(adjudication.reviewActions()));
            }
            for (ReviewActionView action : actions) {
                if (action.number().hasValue()) {
                    return action.number().value();
                }
            }
        }
        return null;
    }

    /** The id of the first ClaimResponse that has one, which a later notification may name. Null when there is none. */
    public String claimResponseId() {
        return claimResponseId(detail.claimResponses());
    }

    /** As {@link #claimResponseId()}, for ClaimResponses that arrived some other way than as the answer to a call. */
    public static String claimResponseId(List<ClaimResponseView> claimResponses) {
        return claimResponses.stream().map(ClaimResponseView::id).filter(Shown::hasValue).map(Shown::value).findFirst().orElse(null);
    }

    private static void put(Map<String, List<String>> codes, String element, List<String> values) {
        if (!values.isEmpty()) {
            codes.put(element, List.copyOf(values));
        }
    }
}
