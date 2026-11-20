package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import java.util.ArrayList;
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

    static List<Finding> check(PasReading reading) {
        List<Finding> findings = new ArrayList<>();
        for (ClaimResponseView response : reading.detail().claimResponses()) {
            CodedValue status = response.outcome();
            if (status.code() != null && !status.recognised()) {
                findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET, "The ClaimResponse outcome code is not one this tool knows",
                        "The outcome code is expected to be one of " + String.join(", ", PasCodes.codes(PasCodes.CLAIM_OUTCOME))
                                + ". The code that came back is shown as it came and is not read as any of them.",
                        PasCodes.CLAIM_OUTCOME, null, status.code()));
            }
            for (PasDecision decision : response.decisions()) {
                for (CodedValue code : decision.reviewActions()) {
                    if (code.code() != null && !code.recognised()) {
                        findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET, "A review action code is not one this tool knows",
                                "A review action code is expected to be one of " + String.join(", ", PasCodes.codes(PasCodes.REVIEW_ACTION))
                                        + ". The code that came back is shown as it came and is not read as any of them.",
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
}
