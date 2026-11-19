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
 * this tool knows, and an item for which no review action was returned. A finding is not a failure
 * of the call. It is kept on the ledger line by its kind, so history can be searched for it.
 */
final class PasFindings {

    private PasFindings() {
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
                if (decision.outcome() == PasOutcome.NO_DECISION) {
                    findings.add(new Finding(FindingKind.REQUIRED_ELEMENT_MISSING, "No review action was returned"
                            + (decision.subject() == null ? "" : " for " + decision.subject()),
                            "A ClaimResponse states its decision in a review action. None applies here, so nothing was decided: "
                                    + "this is not an approval, a denial or a pended request.", decision.subject(), null, null));
                }
            }
        }
        return findings;
    }
}
