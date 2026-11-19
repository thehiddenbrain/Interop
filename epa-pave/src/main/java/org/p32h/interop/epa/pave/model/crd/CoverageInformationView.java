package org.p32h.interop.epa.pave.model.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One coverage information extension from a CRD response: the payer's answer for one service.
 *
 * <p>The standard codes are the answer QA signs off against. Card wording is shown too, but it is
 * vendor text and can change; these codes are the contract.
 *
 * @param foundAt              where in the response the extension sat
 * @param appliesTo            the resource the extension was attached to, when that can be told
 * @param outcome              what this one extension establishes
 * @param delegatedVendor      the delegated utilization management vendor it names, or null
 * @param delegatedVendorBasis how the vendor was recognised, so the rule is visible
 * @param otherSubExtensions   every sub-extension without a dedicated slot above
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoverageInformationView(
        String foundAt,
        Shown appliesTo,
        CrdOutcome outcome,
        CodedValue covered,
        CodedValue paNeeded,
        CodedValue docNeeded,
        List<CodedValue> docPurpose,
        List<CodedValue> infoNeeded,
        Shown satisfiedPaId,
        Shown coverage,
        Shown coverageAssertionId,
        Shown date,
        List<Shown> questionnaires,
        List<Shown> reasons,
        List<CoverageDetailView> details,
        String delegatedVendor,
        String delegatedVendorBasis,
        List<SubExtensionView> otherSubExtensions) {

    public CoverageInformationView {
        docPurpose = docPurpose == null ? List.of() : List.copyOf(docPurpose);
        infoNeeded = infoNeeded == null ? List.of() : List.copyOf(infoNeeded);
        questionnaires = questionnaires == null ? List.of() : List.copyOf(questionnaires);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        details = details == null ? List.of() : List.copyOf(details);
        otherSubExtensions = otherSubExtensions == null ? List.of() : List.copyOf(otherSubExtensions);
    }
}
