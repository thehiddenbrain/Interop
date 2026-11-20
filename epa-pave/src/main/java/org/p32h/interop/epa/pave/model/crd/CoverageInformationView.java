package org.p32h.interop.epa.pave.model.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One coverage information extension from a CRD response: the payer's answer for one service
 * under one coverage.
 *
 * <p>The standard codes are the answer QA signs off against. Card wording is shown too, but it is
 * vendor text and can change; these codes are the contract.
 *
 * @param foundAt              where in the response the extension sat
 * @param appliesTo            the resource the extension was attached to, when that can be told
 * @param procedureCodes       the requested procedure codes this answer is for. Empty when that
 *                             cannot be told. It is never filled by assumption: an answer that names
 *                             an order the request did not carry is for none of the requested codes
 * @param subject              how this answer is named everywhere on the result: its procedure
 *                             codes, else the resource it applies to, else where it sat. When the
 *                             response answers under more than one coverage, the coverage is named too
 * @param outcome              what this one extension establishes
 * @param responses            the 2.0.1 {@code response} sub-extension: references to partly
 *                             completed QuestionnaireResponses that DTR starts from
 * @param laterReasonCodes     reason codes a later version of the guide defines and CRD 2.0.1 does
 *                             not, such as the 2.2.0 ballot's no-member-found, as {@code system|code}
 * @param authOutOfNetworkOnly true when a CRD 2.0.1 detail auth-out-network-only says authorization
 *                             is needed only out of network, false when it says otherwise, null when
 *                             no such detail was returned
 * @param delegatedVendor      the delegated utilization management vendor it names, or null
 * @param delegatedVendorBasis how the vendor was recognised, so the rule is visible
 * @param otherSubExtensions   every sub-extension without a dedicated slot above
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CoverageInformationView(
        String foundAt,
        Shown appliesTo,
        List<String> procedureCodes,
        String subject,
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
        List<Shown> responses,
        List<Shown> reasons,
        List<String> laterReasonCodes,
        List<CoverageDetailView> details,
        Boolean authOutOfNetworkOnly,
        String delegatedVendor,
        String delegatedVendorBasis,
        List<SubExtensionView> otherSubExtensions) {

    public CoverageInformationView {
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
        docPurpose = docPurpose == null ? List.of() : List.copyOf(docPurpose);
        infoNeeded = infoNeeded == null ? List.of() : List.copyOf(infoNeeded);
        questionnaires = questionnaires == null ? List.of() : List.copyOf(questionnaires);
        responses = responses == null ? List.of() : List.copyOf(responses);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        laterReasonCodes = laterReasonCodes == null ? List.of() : List.copyOf(laterReasonCodes);
        details = details == null ? List.of() : List.copyOf(details);
        otherSubExtensions = otherSubExtensions == null ? List.of() : List.copyOf(otherSubExtensions);
    }

    /** The same answer, tied to the requested codes it is for. */
    public CoverageInformationView attributedTo(List<String> codes, String name) {
        return new CoverageInformationView(foundAt, appliesTo, codes, name, outcome, covered, paNeeded, docNeeded, docPurpose,
                infoNeeded, satisfiedPaId, coverage, coverageAssertionId, date, questionnaires, responses, reasons, laterReasonCodes,
                details, authOutOfNetworkOnly, delegatedVendor, delegatedVendorBasis, otherSubExtensions);
    }

    /** True when the extension sat on a CDS Hooks card itself, a place CRD 2.0.1 does not allow it. */
    public boolean onACard() {
        return foundAt != null && foundAt.startsWith("cards[") && !foundAt.contains(".suggestions[");
    }

    /** Prints where it was found and the outcome. A vendor and every value come from a response. */
    @Override
    public String toString() {
        return "CoverageInformationView[" + foundAt + ", " + outcome + "]";
    }
}
