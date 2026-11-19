package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import org.p32h.interop.epa.pave.model.pas.NotificationEventView;
import org.p32h.interop.epa.pave.model.pas.NotificationKind;
import java.util.List;

/**
 * What the reader made of one body that arrived at the receiver, before it is matched to any run.
 *
 * @param kind             what kind of body it was
 * @param kindNote         one plain sentence about a body that is not a notification, or null
 * @param status           what the subscription status said. Every part is absent when the body is not a notification
 * @param carried          the resources the body carried beside the status
 * @param payloadContent   how much it carried, worked out from what is in it: empty, id-only or
 *                         full-resource. Null when the body is not a notification
 * @param identifiers      what it carries that can tie it to a submission
 * @param additionalFields everything the body carried that the fields above did not use
 */
public record NotificationReading(NotificationKind kind, String kindNote, Status status, PasResponseMapper.Carried carried,
                                  String payloadContent, Identifiers identifiers, List<AdditionalField> additionalFields) {

    /**
     * The subscription status a notification carries.
     *
     * @param type               the notification type code with what the tool knows of it
     * @param subscriptionStatus the status the service reports for the subscription
     * @param subscription       the subscription the notification arrives on, as it refers to it
     * @param subscriptionId     the id read from that reference, or null when it names none
     * @param errors             the errors the service reports on the subscription, as returned
     */
    public record Status(CodedValue type, CodedValue subscriptionStatus, Shown subscription, String subscriptionId, Shown topic,
                         Shown eventsSinceSubscriptionStart, List<NotificationEventView> events, List<Shown> errors) {

        public Status {
            events = events == null ? List.of() : List.copyOf(events);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        static Status none() {
            return new Status(CodedValue.notReturned(), CodedValue.notReturned(), Shown.absent(), null, Shown.absent(), Shown.absent(),
                    List.of(), List.of());
        }
    }

    /**
     * What a notification carries that can tie it to the submission it is about. Each is null when
     * the notification does not carry it.
     *
     * @param claimId          the identifier of the claim, as a carried ClaimResponse echoes it
     * @param claimResponseId  the id of the ClaimResponse it carries, or names as the focus of an event
     * @param authorizationRef the authorization number a carried ClaimResponse states
     */
    public record Identifiers(String claimId, String claimResponseId, String authorizationRef) {

        /** Prints which are there, never what they are: an authorization number identifies a member's request. */
        @Override
        public String toString() {
            return "Identifiers[claimId=" + (claimId != null) + ", claimResponseId=" + (claimResponseId != null) + ", authorizationRef="
                    + (authorizationRef != null) + "]";
        }

        boolean any() {
            return claimId != null || claimResponseId != null || authorizationRef != null;
        }
    }

    public NotificationReading {
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    /** True when the body is a subscription notification, whatever its type. */
    public boolean notification() {
        return kind == NotificationKind.NOTIFICATION_BUNDLE || kind == NotificationKind.STATUS_ONLY;
    }
}
