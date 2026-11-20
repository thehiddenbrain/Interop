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
 * @param attachmentNote  how the attachments of the request travel, or that it carries none, in one
 *                        sentence worked out from {@code attachmentMode} and {@code bodyBuiltByTool}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasDetail(PasResponseKind responseKind, String responseNote, Shown bundleType, Shown bundleId, Shown bundleTimestamp,
                        List<ClaimResponseView> claimResponses, List<BundleResourceView> otherResources, List<IssueView> issues,
                        String claimId, String attachmentMode, List<String> requestNotes, boolean bodyBuiltByTool, String attachmentNote) {

    /** The detail with the sentence on attachments worked out from what the request carried. */
    public PasDetail(PasResponseKind responseKind, String responseNote, Shown bundleType, Shown bundleId, Shown bundleTimestamp,
                     List<ClaimResponseView> claimResponses, List<BundleResourceView> otherResources, List<IssueView> issues,
                     String claimId, String attachmentMode, List<String> requestNotes, boolean bodyBuiltByTool) {
        this(responseKind, responseNote, bundleType, bundleId, bundleTimestamp, claimResponses, otherResources, issues, claimId, attachmentMode,
                requestNotes, bodyBuiltByTool, null);
    }

    /** The sentence for how the attachments of a request travel. In the present tense: it is as true of a preview as of a run. */
    static String attachmentNote(String attachmentMode, boolean bodyBuiltByTool) {
        if (!bodyBuiltByTool) {
            return "This tool did not build the body that was sent, so it does not say whether or how it carries attachments.";
        }
        if (attachmentMode == null) {
            return "The request carries no attachment, because none was given.";
        }
        // The value config/StartupChecks names ATTACHMENT_X12_275 and checks at startup; the model does not depend on the configuration.
        if ("x12-275".equals(attachmentMode)) {
            return "The request names each attachment by an attachment control number, for an X12 275 transaction that this tool does "
                    + "not send. The service receives no document from this tool.";
        }
        return "The request carries each attachment inside the bundle, as a DocumentReference named in the claim's supporting information.";
    }

    public PasDetail {
        attachmentNote = attachmentNote(attachmentMode, bodyBuiltByTool);
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
