package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * Everything the tool understood in the response to a PAS submission or inquiry, for the designed view.
 *
 * @param responseKind    what kind of thing came back
 * @param responseNote    one plain sentence about it, or null when it needs none
 * @param bundleType      the type of the bundle that came back, when it was a bundle
 * @param claimResponses  every ClaimResponse found, in the order returned. An inquiry can return several
 * @param otherResources  every other resource the bundle carried
 * @param issues          the issues of an OperationOutcome, whether it was the whole response or
 *                        came inside a bundle
 * @param claimId         the identifier on the claim that was sent, which is how a later inquiry
 *                        and a notification are matched to this run. Null when the request carried none
 * @param attachmentMode  how the supporting documentation of the request travelled, fhir or
 *                        x12-275. Null when the request carried no attachment
 * @param requestNotes    what the tool left out of, or worked out for, the request it built.
 *                        Empty when the tester's own body was sent
 * @param bodyBuiltByTool false when the tester's edited body was sent in place of a built one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasDetail(PasResponseKind responseKind, String responseNote, Shown bundleType, Shown bundleId, Shown bundleTimestamp,
                        List<ClaimResponseView> claimResponses, List<BundleResourceView> otherResources, List<IssueView> issues,
                        String claimId, String attachmentMode, List<String> requestNotes, boolean bodyBuiltByTool) {

    public PasDetail {
        claimResponses = claimResponses == null ? List.of() : List.copyOf(claimResponses);
        otherResources = otherResources == null ? List.of() : List.copyOf(otherResources);
        issues = issues == null ? List.of() : List.copyOf(issues);
        requestNotes = requestNotes == null ? List.of() : List.copyOf(requestNotes);
    }

    /** The same detail with what is known about the request that produced it. */
    public PasDetail withRequest(String sentClaimId, String sentAttachmentMode, List<String> notes, boolean builtByTool) {
        return new PasDetail(responseKind, responseNote, bundleType, bundleId, bundleTimestamp, claimResponses, otherResources, issues,
                sentClaimId, sentAttachmentMode, notes, builtByTool);
    }

    /** Prints the kind of response only. The views hold an authorization number and the payer's wording. */
    @Override
    public String toString() {
        return "PasDetail[" + responseKind + ", claimResponses=" + claimResponses.size() + "]";
    }
}
