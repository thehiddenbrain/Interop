package org.p32h.interop.epa.pave.service.pas;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds subscription notifications for tests, in the R4 form of the subscriptions backport, so
 * each test states only the part it is about. Every value here is synthetic.
 */
public final class NotificationJson {

    public static final String TOPIC = "https://example.org/p32h-placeholder/SubscriptionTopic/prior-authorization-status";
    public static final String STATUS_PROFILE = "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-subscription-status-r4";

    private NotificationJson() {
    }

    /**
     * The status of a notification as a Parameters resource.
     *
     * @param subscriptionId    the subscription it names, or null to name none
     * @param type              the notification type code, or null to state none
     * @param furtherParameters further parameters, each written as a JSON object
     */
    public static String status(String subscriptionId, String type, String... furtherParameters) {
        List<String> parameters = new ArrayList<>();
        if (subscriptionId != null) {
            parameters.add("{\"name\":\"subscription\",\"valueReference\":{\"reference\":\"https://example.org/p32h-placeholder/fhir/Subscription/"
                    + subscriptionId + "\"}}");
        }
        parameters.add("{\"name\":\"topic\",\"valueCanonical\":\"" + TOPIC + "\"}");
        parameters.add("{\"name\":\"status\",\"valueCode\":\"active\"}");
        if (type != null) {
            parameters.add("{\"name\":\"type\",\"valueCode\":\"" + type + "\"}");
        }
        parameters.addAll(List.of(furtherParameters));
        return "{\"resourceType\":\"Parameters\",\"meta\":{\"profile\":[\"" + STATUS_PROFILE + "\"]},\"parameter\":[" + String.join(",", parameters) + "]}";
    }

    /** One notification event that names the resource it is about. */
    public static String event(int number, String focusReference) {
        return "{\"name\":\"notification-event\",\"part\":[{\"name\":\"event-number\",\"valueString\":\"" + number + "\"},"
                + "{\"name\":\"timestamp\",\"valueInstant\":\"2026-01-16T09:30:00Z\"},"
                + "{\"name\":\"focus\",\"valueReference\":{\"reference\":\"" + focusReference + "\"}}]}";
    }

    /** A notification bundle: the status first, then whatever resources it carries. */
    public static String bundle(String status, String... resources) {
        List<String> entries = new ArrayList<>();
        entries.add("{\"resource\":" + status + "}");
        for (String resource : resources) {
            entries.add("{\"resource\":" + resource + "}");
        }
        return "{\"resourceType\":\"Bundle\",\"type\":\"history\",\"timestamp\":\"2026-01-16T09:30:00Z\",\"entry\":[" + String.join(",", entries) + "]}";
    }

    /** An event notification that carries ids only: the subscription and the ClaimResponse it is about. */
    public static String idOnly(String subscriptionId, String claimResponseId) {
        return bundle(status(subscriptionId, "event-notification", event(1, "ClaimResponse/" + claimResponseId)));
    }

    /**
     * A ClaimResponse as a notification carries it, decided by one review action.
     *
     * @param claimId the claim identifier it echoes, or null to echo none
     */
    public static String claimResponse(String id, String claimId, String reviewAction, String... furtherFields) {
        List<String> fields = new ArrayList<>(List.of(furtherFields));
        if (claimId != null) {
            fields.add(0, "\"request\":{\"identifier\":{\"value\":\"" + claimId + "\"}}");
        }
        fields.add("\"outcome\":\"complete\"");
        fields.add(PasJson.items(PasJson.decided(1, reviewAction)));
        return PasJson.claimResponseWithId(id, fields.toArray(String[]::new));
    }
}
