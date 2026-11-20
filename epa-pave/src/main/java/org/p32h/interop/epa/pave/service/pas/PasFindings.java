package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * What a person should look at on a PAS response, apart from its outcome: a code outside the codes
 * this tool knows, and an item for which no decision is stated. A finding is not a failure
 * of the call. It is kept on the ledger line by its kind, so history can be searched for it.
 */
final class PasFindings {

    private PasFindings() {
    }

    /**
     * True when PAS 2.0.1 itself explains why items carry no decision: the response is an error
     * response that lists its errors, as the guide's own error example does with no item at all,
     * or it is tagged SUBSETTED, which the guide requires of a response that answers only some items.
     */
    static boolean explainedByTheGuide(ClaimResponseView response) {
        return response.subsetted() || (response.outcome().is("error") && !response.errors().isEmpty());
    }

    /**
     * The one finding for a request that went out without elements a PAS 2.0.1 profile requires,
     * naming each, so that a refusal by the service is not read as a fault of the service.
     */
    static Finding requestIncomplete(List<String> leftOut) {
        int count = leftOut.size();
        return new Finding(FindingKind.REQUEST_INCOMPLETE, "The request left out " + count + (count == 1 ? " element" : " elements")
                + " PAS 2.0.1 requires", "Nobody supplied " + (count == 1 ? "it" : "them") + ", and this tool does not "
                + "invent values: " + String.join("; ", leftOut) + ". A refusal by the service may be about this and not a fault of the "
                + "service. The request notes say what supplies each one.", String.join("; ", leftOut), null, null);
    }

    static List<Finding> check(PasReading reading) {
        List<Finding> findings = new ArrayList<>();
        for (ClaimResponseView response : reading.detail().claimResponses()) {
            if (!response.addItemReviewActions().isEmpty()) {
                findings.add(new Finding(FindingKind.OTHER_VERSION_CONTENT, "A review action on an item the payer added",
                        "The response carries a review action on ClaimResponse.addItem. The PAS 2.2.0 ballot defines a review action "
                                + "there; PAS 2.0.1, the version this tool is held to, does not. It is listed among the fields returned but "
                                + "not interpreted, and decides nothing for the items that were requested.",
                        String.join("; ", response.addItemReviewActions()), null, null));
            }
            CodedValue status = response.outcome();
            if (status.code() != null && !status.recognised()) {
                findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET, "The ClaimResponse outcome code is not one this tool knows",
                        "PAS 2.0.1 takes ClaimResponse.outcome from FHIR R4, which binds it, required, to "
                                + listed(PasCodes.codes(PasCodes.CLAIM_OUTCOME)) + ". The code that came back is shown as it came and "
                                + "is not read as any of them.",
                        PasCodes.CLAIM_OUTCOME, null, status.code()));
            }
            for (PasDecision decision : response.decisions()) {
                for (CodedValue code : decision.reviewActions()) {
                    if (code.code() != null && !code.recognised()) {
                        findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET, "A review action code is not one this tool knows",
                                "PAS 2.0.1 binds the review action code, required, to the X12 306 (HCR01) codes; this tool knows "
                                        + listed(PasCodes.codes(PasCodes.REVIEW_ACTION)) + ". The code that came back is shown as it came "
                                        + "and is not read as any of them.",
                                decision.subject(), null, code.code()));
                    }
                }
                if (decision.outcome() == PasOutcome.NO_DECISION && !explainedByTheGuide(response)) {
                    // PAS 2.0.1 makes the review action optional (0..1 on item.adjudication), so its
                    // absence is information and never a claim that the response breaks the guide.
                    findings.add(new Finding(FindingKind.NOT_STATED, "No decision is stated"
                            + (decision.subject() == null ? "" : " for " + decision.subject()),
                            "The response states no review action that applies here, so nothing was decided: this is not an approval, "
                                    + "a denial or a pended request. PAS 2.0.1 does not require a review action on every item.",
                            decision.subject(), null, null));
                }
            }
        }
        return findings;
    }

    /** Codes as a list in a sentence: "A, B and C". */
    private static String listed(Collection<String> known) {
        List<String> codes = List.copyOf(known);
        if (codes.size() < 2) {
            return String.join("", codes);
        }
        return String.join(", ", codes.subList(0, codes.size() - 1)) + " and " + codes.get(codes.size() - 1);
    }
}
