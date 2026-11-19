package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import java.util.List;

/**
 * One notification as it arrived at the receiver, with the runs it correlates to.
 *
 * <p>This is held in memory only. The payload is member data, so it is never written to disk and
 * never logged; the ledger line for the notification holds ids and codes and nothing else. After a
 * restart the ledger lines remain and these are gone.
 *
 * @param id                 the run id of the notification's ledger line
 * @param receivedAt         when it arrived, as the receiver stamped it. Stamps never repeat, so a
 *                           poll that passes the latest one it has seen misses nothing
 * @param sample             true when it was delivered by the sample endpoint on the mock profile
 *                           and did not come from a service
 * @param scenarioId         the mock scenario a sample was built from, or null
 * @param verdict            what arrived, in plain language, with its outcome
 * @param kind               what kind of body it was
 * @param type               the notification type code: handshake, heartbeat or event-notification
 * @param subscription       the subscription it names, as the notification refers to it
 * @param subscriptionId     the id read from that reference, or null when it names none
 * @param payloadContent     how much it carried, worked out from what is in it: empty, id-only or
 *                           full-resource. Null when it is not a notification
 * @param claimResponses     each ClaimResponse it carried, read exactly as a PAS response is read
 * @param otherResources     every other resource it carried
 * @param subscriptionRunId  the run that created the subscription, or null when no run of this
 *                           tool did. Null is the unmatched state
 * @param correlatedRunId    the submission the notification is about, or null when it could not be
 *                           established
 * @param claimId            the identifier of the claim it is about, when that could be established
 * @param claimResponseId    the id of the ClaimResponse it names or carries
 * @param correlationNotes   how each of the two links was established, or why it was not
 * @param payload            the complete payload as received. When the body was longer than the
 *                           receiver holds, this is the part that was kept
 * @param truncated          true when the body was cut. The payload then is not all that was sent
 * @param additionalFields   everything the notification carried that the fields above did not use
 * @param ledger             what the ledger did with the notification's line
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationView(
        String id,
        String receivedAt,
        boolean sample,
        String scenarioId,
        VerdictView verdict,
        NotificationKind kind,
        CodedValue type,
        CodedValue subscriptionStatus,
        Shown subscription,
        String subscriptionId,
        Shown topic,
        Shown eventsSinceSubscriptionStart,
        List<NotificationEventView> events,
        String payloadContent,
        List<ClaimResponseView> claimResponses,
        List<BundleResourceView> otherResources,
        String subscriptionRunId,
        String correlatedRunId,
        String claimId,
        String claimResponseId,
        List<String> correlationNotes,
        List<Finding> findings,
        String payload,
        long bytesReceived,
        long bytesKept,
        boolean truncated,
        List<String> payloadNotes,
        List<AdditionalField> additionalFields,
        LedgerReceipt ledger) {

    public NotificationView {
        events = events == null ? List.of() : List.copyOf(events);
        claimResponses = claimResponses == null ? List.of() : List.copyOf(claimResponses);
        otherResources = otherResources == null ? List.of() : List.copyOf(otherResources);
        correlationNotes = correlationNotes == null ? List.of() : List.copyOf(correlationNotes);
        findings = findings == null ? List.of() : List.copyOf(findings);
        payloadNotes = payloadNotes == null ? List.of() : List.copyOf(payloadNotes);
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    /** Redacted, because the payload is member data. */
    @Override
    public String toString() {
        return "NotificationView[" + id + ", " + (verdict == null ? null : verdict.outcomeCode()) + ", payload redacted]";
    }
}
