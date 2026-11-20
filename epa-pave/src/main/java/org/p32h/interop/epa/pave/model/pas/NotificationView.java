package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
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
 * <p>Construct with {@link #builder()}.
 *
 * @param id                 the run id of the notification's ledger line
 * @param receivedAt         when it arrived, as the receiver stamped it. Stamps never repeat, so a
 *                           poll that passes the latest one it has seen misses nothing
 * @param sample             true when it was delivered by the sample endpoint on the mock profile
 *                           and did not come from a service
 * @param scenarioId         the mock scenario a sample was built from, or null
 * @param verdict            what arrived, in plain language, with its outcome
 * @param nextStep           what the tester should do about it
 * @param kind               what kind of body it was
 * @param type               the notification type code: handshake, heartbeat or event-notification
 * @param subscription       the subscription it names, as the notification refers to it
 * @param subscriptionId     the id read from that reference, or null when it names none
 * @param errors             the errors the service reports on the subscription, as returned
 * @param payloadContent     how much it carried, worked out from what is in it: empty, id-only or
 *                           full-resource. Null when it is not a notification
 * @param claimResponses     each ClaimResponse it carried, read exactly as a PAS response is read
 * @param otherResources     every other resource it carried
 * @param issues             the issues of an OperationOutcome it carried
 * @param subscriptionRunId  the run that created the subscription, or null when none was found.
 *                           Null alone does not say why: the outcome tells a subscription no run
 *                           of this tool created from a ledger that could not be asked
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
 * @param errorsPresence     what the notification held for its errors: not returned, null, empty or a
 *                           value, which the list of errors alone cannot say
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationView(
        String id,
        String receivedAt,
        boolean sample,
        String scenarioId,
        VerdictView verdict,
        NextStepView nextStep,
        NotificationKind kind,
        CodedValue type,
        CodedValue subscriptionStatus,
        Shown subscription,
        String subscriptionId,
        Shown topic,
        Shown eventsSinceSubscriptionStart,
        List<NotificationEventView> events,
        List<Shown> errors,
        String payloadContent,
        Shown bundleType,
        Shown bundleId,
        Shown bundleTimestamp,
        List<ClaimResponseView> claimResponses,
        List<BundleResourceView> otherResources,
        List<IssueView> issues,
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
        LedgerReceipt ledger,
        Presence errorsPresence) {

    public NotificationView {
        events = events == null ? List.of() : List.copyOf(events);
        errors = errors == null ? List.of() : List.copyOf(errors);
        claimResponses = claimResponses == null ? List.of() : List.copyOf(claimResponses);
        otherResources = otherResources == null ? List.of() : List.copyOf(otherResources);
        issues = issues == null ? List.of() : List.copyOf(issues);
        correlationNotes = correlationNotes == null ? List.of() : List.copyOf(correlationNotes);
        findings = findings == null ? List.of() : List.copyOf(findings);
        payloadNotes = payloadNotes == null ? List.of() : List.copyOf(payloadNotes);
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Redacted, because the payload is member data. */
    @Override
    public String toString() {
        return "NotificationView[" + id + ", " + (verdict == null ? null : verdict.outcomeCode()) + ", payload redacted]";
    }

    public static final class Builder {
        private String id;
        private String receivedAt;
        private boolean sample;
        private String scenarioId;
        private VerdictView verdict;
        private NextStepView nextStep;
        private NotificationKind kind;
        private CodedValue type = CodedValue.notReturned();
        private CodedValue subscriptionStatus = CodedValue.notReturned();
        private Shown subscription = Shown.absent();
        private String subscriptionId;
        private Shown topic = Shown.absent();
        private Shown eventsSinceSubscriptionStart = Shown.absent();
        private List<NotificationEventView> events = List.of();
        private List<Shown> errors = List.of();
        private String payloadContent;
        private Shown bundleType = Shown.absent();
        private Shown bundleId = Shown.absent();
        private Shown bundleTimestamp = Shown.absent();
        private List<ClaimResponseView> claimResponses = List.of();
        private List<BundleResourceView> otherResources = List.of();
        private List<IssueView> issues = List.of();
        private String subscriptionRunId;
        private String correlatedRunId;
        private String claimId;
        private String claimResponseId;
        private List<String> correlationNotes = List.of();
        private List<Finding> findings = List.of();
        private String payload;
        private long bytesReceived;
        private long bytesKept;
        private boolean truncated;
        private List<String> payloadNotes = List.of();
        private List<AdditionalField> additionalFields = List.of();
        private LedgerReceipt ledger;
        private Presence errorsPresence;

        private Builder() {
        }

        public Builder id(String value) {
            this.id = value;
            return this;
        }

        public Builder receivedAt(String value) {
            this.receivedAt = value;
            return this;
        }

        public Builder sample(boolean value) {
            this.sample = value;
            return this;
        }

        public Builder scenarioId(String value) {
            this.scenarioId = value;
            return this;
        }

        public Builder verdict(VerdictView value) {
            this.verdict = value;
            return this;
        }

        public Builder nextStep(NextStepView value) {
            this.nextStep = value;
            return this;
        }

        public Builder kind(NotificationKind value) {
            this.kind = value;
            return this;
        }

        public Builder type(CodedValue value) {
            this.type = value;
            return this;
        }

        public Builder subscriptionStatus(CodedValue value) {
            this.subscriptionStatus = value;
            return this;
        }

        public Builder subscription(Shown value) {
            this.subscription = value;
            return this;
        }

        public Builder subscriptionId(String value) {
            this.subscriptionId = value;
            return this;
        }

        public Builder topic(Shown value) {
            this.topic = value;
            return this;
        }

        public Builder eventsSinceSubscriptionStart(Shown value) {
            this.eventsSinceSubscriptionStart = value;
            return this;
        }

        public Builder events(List<NotificationEventView> value) {
            this.events = value;
            return this;
        }

        public Builder errors(List<Shown> value) {
            this.errors = value;
            return this;
        }

        public Builder payloadContent(String value) {
            this.payloadContent = value;
            return this;
        }

        public Builder bundleType(Shown value) {
            this.bundleType = value;
            return this;
        }

        public Builder bundleId(Shown value) {
            this.bundleId = value;
            return this;
        }

        public Builder bundleTimestamp(Shown value) {
            this.bundleTimestamp = value;
            return this;
        }

        public Builder claimResponses(List<ClaimResponseView> value) {
            this.claimResponses = value;
            return this;
        }

        public Builder otherResources(List<BundleResourceView> value) {
            this.otherResources = value;
            return this;
        }

        public Builder issues(List<IssueView> value) {
            this.issues = value;
            return this;
        }

        public Builder subscriptionRunId(String value) {
            this.subscriptionRunId = value;
            return this;
        }

        public Builder correlatedRunId(String value) {
            this.correlatedRunId = value;
            return this;
        }

        public Builder claimId(String value) {
            this.claimId = value;
            return this;
        }

        public Builder claimResponseId(String value) {
            this.claimResponseId = value;
            return this;
        }

        public Builder correlationNotes(List<String> value) {
            this.correlationNotes = value;
            return this;
        }

        public Builder findings(List<Finding> value) {
            this.findings = value;
            return this;
        }

        public Builder payload(String value) {
            this.payload = value;
            return this;
        }

        public Builder bytesReceived(long value) {
            this.bytesReceived = value;
            return this;
        }

        public Builder bytesKept(long value) {
            this.bytesKept = value;
            return this;
        }

        public Builder truncated(boolean value) {
            this.truncated = value;
            return this;
        }

        public Builder payloadNotes(List<String> value) {
            this.payloadNotes = value;
            return this;
        }

        public Builder additionalFields(List<AdditionalField> value) {
            this.additionalFields = value;
            return this;
        }

        public Builder errorsPresence(Presence value) {
            this.errorsPresence = value;
            return this;
        }

        public Builder ledger(LedgerReceipt value) {
            this.ledger = value;
            return this;
        }

        /** Builds the view, refusing one that could not be traced to its ledger line or does not say what arrived. */
        public NotificationView build() {
            if (id == null || receivedAt == null || verdict == null || nextStep == null || kind == null || ledger == null) {
                throw new IllegalStateException("A notification needs an id, a stamp, a verdict, a next step, a kind and a ledger receipt.");
            }
            return new NotificationView(id, receivedAt, sample, scenarioId, verdict, nextStep, kind, type, subscriptionStatus, subscription,
                    subscriptionId, topic, eventsSinceSubscriptionStart, events, errors, payloadContent, bundleType, bundleId, bundleTimestamp,
                    claimResponses, otherResources, issues, subscriptionRunId, correlatedRunId, claimId, claimResponseId, correlationNotes,
                    findings, payload, bytesReceived, bytesKept, truncated, payloadNotes, additionalFields, ledger, errorsPresence);
        }
    }
}
