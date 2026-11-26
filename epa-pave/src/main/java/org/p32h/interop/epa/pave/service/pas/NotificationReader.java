package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.FhirViews;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.pas.NotificationEventView;
import org.p32h.interop.epa.pave.model.pas.NotificationKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Reads whatever arrived at the notification receiver.
 *
 * <p>A notification of the R4 subscriptions backport is a bundle whose status entry is a Parameters
 * resource. Later FHIR versions use a SubscriptionStatus resource. Both are read, because which the
 * service sends has not been observed, and so is a status that arrives on its own outside a bundle.
 * The resources a notification carries beside its status are read by {@link PasResponseMapper}, so
 * a decision that arrives this way is read by the same rules as one that answers a submission.
 *
 * <p>Nothing here refuses a body. One that is not JSON, or is JSON and not a notification, is
 * described as that and handed back with everything it held, because the receiver records whatever
 * arrives. The body is read through {@link TrackedJson}, so a field nobody anticipated is returned
 * among the additional fields and not lost.
 */
public final class NotificationReader {

    private final PasResponseMapper pas = new PasResponseMapper();

    /** What one status resource said, with the raw references the screen values were built from. */
    private static final class StatusRead {
        private Shown type = Shown.absent();
        private Shown status = Shown.absent();
        private Shown subscription = Shown.absent();
        private String subscriptionReference;
        private Shown topic = Shown.absent();
        private Shown eventsSince = Shown.absent();
        private final List<NotificationEventView> events = new ArrayList<>();
        private final List<Shown> errors = new ArrayList<>();
        // Null in the R4 form, where errors are parameters and there is no list to be null or empty.
        private Presence errorsPresence;
        private final List<String> focusReferences = new ArrayList<>();
        private final List<String> contextReferences = new ArrayList<>();

        private NotificationReading.Status view() {
            return new NotificationReading.Status(PasCodes.coded(PasCodes.NOTIFICATION_TYPE, type),
                    PasCodes.coded(PasCodes.SUBSCRIPTION_STATUS, status), subscription,
                    OnyxPasConventions.idAfter(subscriptionReference, "Subscription"), topic, eventsSince, events, errors, errorsPresence);
        }
    }

    /** @param text the body as received, or the part of it that was kept */
    public NotificationReading read(String text) {
        if (Texts.blank(text)) {
            return unread(NotificationKind.NO_BODY, "The request had no body.", List.of());
        }
        JsonSafe.Parsed parsed = JsonSafe.parse(text);
        if (!parsed.ok()) {
            return unread(NotificationKind.NOT_JSON, "The body could not be read as JSON. It is shown below exactly as it was kept. " + parsed.error(),
                    List.of());
        }
        // Every value of a repeated key is listed, so an earlier value is not in the payload text alone.
        List<AdditionalField> additional = new ArrayList<>(TrackedJson.repeatedKeyFields(parsed));
        JsonNode json = parsed.node();
        TrackedJson tracked = TrackedJson.of(json);
        TrackedJson.Node root = tracked.root();
        NotificationKind kind;
        String note = null;
        StatusRead status = null;
        PasResponseMapper.Carried carried = PasResponseMapper.Carried.nothing();
        List<JsonNode> others = new ArrayList<>();

        if ("Bundle".equals(JsonSafe.text(json, "resourceType"))) {
            List<TrackedJson.Node> entries = root.children("entry");
            int statusAt = -1;
            for (int i = 0; i < entries.size(); i++) {
                JsonNode resource = entries.get(i).child("resource").raw();
                if (statusAt < 0 && OnyxPasConventions.subscriptionStatus(resource)) {
                    statusAt = i;
                } else if (resource.isObject()) {
                    others.add(entries.get(i).raw());
                }
            }
            if (statusAt >= 0) {
                kind = NotificationKind.NOTIFICATION_BUNDLE;
                status = status(entries.get(statusAt).child("resource"));
                carried = pas.readBundle(root, SentClaim.unknown(), Set.of(statusAt));
            } else {
                kind = NotificationKind.OTHER_JSON;
                note = "The body is a bundle that carries no subscription status, so it is not a notification. What it does carry is shown below.";
                carried = pas.readBundle(root, SentClaim.unknown(), Set.of());
            }
        } else if (OnyxPasConventions.subscriptionStatus(json)) {
            kind = NotificationKind.STATUS_ONLY;
            status = status(root);
        } else {
            kind = NotificationKind.OTHER_JSON;
            note = root.isObject() ? "The body is JSON but is not a bundle and not a subscription status, so it is not a notification. All of it "
                    + "is listed below." : "The body is JSON but is not an object, so it is not a notification. All of it is listed below.";
        }
        additional.addAll(tracked.unconsumed());

        List<String> named = status == null ? List.of() : status.focusReferences;
        List<String> context = status == null ? List.of() : status.contextReferences;
        return new NotificationReading(kind, note, status == null ? NotificationReading.Status.none() : status.view(), carried,
                status == null ? null : payloadContent(named, others), identifiers(carried, others, named, context), additional);
    }

    private static NotificationReading unread(NotificationKind kind, String note, List<AdditionalField> additional) {
        return new NotificationReading(kind, note, NotificationReading.Status.none(), PasResponseMapper.Carried.nothing(), null,
                new NotificationReading.Identifiers(null, null, null), additional);
    }

    private static StatusRead status(TrackedJson.Node resource) {
        return OnyxPasConventions.PARAMETERS.equals(JsonSafe.text(resource.raw(), "resourceType")) ? parameters(resource) : statusResource(resource);
    }

    /** The R4 form: a Parameters resource whose parameters are named as the backport names them. */
    private static StatusRead parameters(TrackedJson.Node resource) {
        StatusRead read = new StatusRead();
        resource.text("resourceType");
        for (TrackedJson.Node parameter : resource.children("parameter")) {
            // The name is looked at without being consumed. A parameter this reader does not know
            // is left whole, name and value together, for the list of returned fields.
            String name = parameter.isObject() ? JsonSafe.presentText(parameter.raw(), "name") : null;
            if (name == null) {
                continue;
            }
            switch (name) {
                case "subscription" -> {
                    parameter.text("name");
                    read.subscriptionReference = rawReference(parameter);
                    read.subscription = value(parameter);
                }
                case "topic" -> {
                    parameter.text("name");
                    read.topic = value(parameter);
                }
                case "status" -> {
                    parameter.text("name");
                    read.status = PasResponseMapper.present(FhirViews.extensionCode(parameter));
                }
                case "type" -> {
                    parameter.text("name");
                    read.type = PasResponseMapper.present(FhirViews.extensionCode(parameter));
                }
                case "events-since-subscription-start" -> {
                    parameter.text("name");
                    read.eventsSince = value(parameter);
                }
                case "notification-event" -> {
                    parameter.text("name");
                    read.events.add(event(parameter.children("part"), read));
                }
                case "error" -> {
                    parameter.text("name");
                    read.errors.add(value(parameter));
                }
                default -> {
                }
            }
        }
        return read;
    }

    private static NotificationEventView event(List<TrackedJson.Node> parts, StatusRead read) {
        Shown number = Shown.absent();
        Shown timestamp = Shown.absent();
        Shown focus = Shown.absent();
        List<Shown> context = new ArrayList<>();
        for (TrackedJson.Node part : parts) {
            String name = part.isObject() ? JsonSafe.presentText(part.raw(), "name") : null;
            if (name == null) {
                continue;
            }
            switch (name) {
                case "event-number" -> {
                    part.text("name");
                    number = value(part);
                }
                case "timestamp" -> {
                    part.text("name");
                    timestamp = value(part);
                }
                case "focus" -> {
                    part.text("name");
                    addReference(read.focusReferences, rawReference(part));
                    focus = value(part);
                }
                case "additional-context" -> {
                    part.text("name");
                    addReference(read.contextReferences, rawReference(part));
                    context.add(value(part));
                }
                default -> {
                }
            }
        }
        return new NotificationEventView(number, timestamp, focus, context);
    }

    private static void consumeIfEmptyList(TrackedJson.Node node) {
        if (node.isArray() && node.elements().isEmpty()) {
            node.consume();
        }
    }

    /** The later form: a SubscriptionStatus resource with its values as elements of its own. */
    private static StatusRead statusResource(TrackedJson.Node resource) {
        StatusRead read = new StatusRead();
        resource.text("resourceType");
        read.status = resource.shown("status");
        read.type = resource.shown("type");
        read.eventsSince = resource.shown("eventsSinceSubscriptionStart");
        read.subscriptionReference = referenceOf(resource.child("subscription").raw());
        read.subscription = FhirViews.reference(resource.child("subscription"));
        read.topic = resource.shown("topic");
        for (TrackedJson.Node event : resource.children("notificationEvent")) {
            if (!event.isObject()) {
                continue;
            }
            addReference(read.focusReferences, referenceOf(event.child("focus").raw()));
            List<Shown> context = new ArrayList<>();
            for (TrackedJson.Node further : event.children("additionalContext")) {
                addReference(read.contextReferences, referenceOf(further.raw()));
                context.add(FhirViews.reference(further));
            }
            // The event says "empty" for [], so it is not listed again among the returned fields.
            consumeIfEmptyList(event.child("additionalContext"));
            read.events.add(new NotificationEventView(event.shown("eventNumber"), event.shown("timestamp"),
                    FhirViews.reference(event.child("focus")), context, event.child("additionalContext").presence()));
        }
        for (TrackedJson.Node error : resource.children("error")) {
            read.errors.add(FhirViews.concept(error));
        }
        read.errorsPresence = resource.child("error").presence();
        consumeIfEmptyList(resource.child("error"));
        return read;
    }

    /**
     * How much the notification carried, worked out from what is in it and not from what a
     * subscription asked for. Full when every resource an event names is carried, or when resources
     * are carried and no event names one. Ids only when an event names a resource that is not
     * carried. Empty when it names none and carries none.
     */
    private static String payloadContent(List<String> focusReferences, List<JsonNode> otherEntries) {
        if (focusReferences.isEmpty()) {
            return otherEntries.isEmpty() ? PasCodes.CONTENT_EMPTY : PasCodes.CONTENT_FULL;
        }
        for (String reference : focusReferences) {
            if (otherEntries.stream().noneMatch(entry -> names(reference, entry))) {
                return PasCodes.CONTENT_ID_ONLY;
            }
        }
        return PasCodes.CONTENT_FULL;
    }

    /** True when a reference names the resource a bundle entry holds, by its type and id or by the entry's full url. */
    private static boolean names(String reference, JsonNode entry) {
        JsonNode resource = JsonSafe.child(entry, "resource");
        String type = JsonSafe.presentText(resource, "resourceType");
        String id = JsonSafe.presentText(resource, "id");
        String local = type != null && id != null ? type + "/" + id : null;
        return (local != null && (reference.equals(local) || reference.endsWith("/" + local)))
                || reference.equals(JsonSafe.presentText(entry, "fullUrl"));
    }

    /**
     * What the body carries that can tie it to a submission, read from the resources themselves and
     * from what the events name.
     *
     * <p>The claim identifier is the one the tool put on the claim, as the payer echoes it: in
     * ClaimResponse.request.identifier, else in ClaimResponse.identifier, which is where every
     * PAS 2.0.1 example echoes it. A ClaimResponse inside a bundle the notification carries counts
     * too. The reference to the claim is not used: its id is the payer's own, or a local one every
     * bundle shares.
     */
    private static NotificationReading.Identifiers identifiers(PasResponseMapper.Carried carried, List<JsonNode> otherEntries,
                                                               List<String> focusReferences, List<String> contextReferences) {
        List<JsonNode> responses = new ArrayList<>();
        for (JsonNode entry : otherEntries) {
            claimResponsesIn(JsonSafe.child(entry, "resource"), responses);
        }
        String claimId = null;
        for (JsonNode resource : responses) {
            claimId = Texts.firstPresent(claimId, JsonSafe.presentText(JsonSafe.at(resource, "request", "identifier", "value")));
        }
        for (JsonNode resource : responses) {
            for (JsonNode identifier : JsonSafe.elements(resource, "identifier")) {
                claimId = Texts.firstPresent(claimId, JsonSafe.presentText(identifier, "value"));
            }
        }
        String claimResponseId = PasReading.claimResponseId(carried.claimResponses());
        List<String> references = new ArrayList<>(focusReferences);
        references.addAll(contextReferences);
        for (String reference : references) {
            if (claimResponseId == null) {
                claimResponseId = OnyxPasConventions.idAfter(reference, "ClaimResponse");
            }
        }
        return new NotificationReading.Identifiers(claimId, claimResponseId, PasReading.authorizationRef(carried.claimResponses()));
    }

    /** Every ClaimResponse a resource is or holds as a bundle entry, at any depth. */
    private static void claimResponsesIn(JsonNode resource, List<JsonNode> out) {
        String type = JsonSafe.text(resource, "resourceType");
        if ("ClaimResponse".equals(type)) {
            out.add(resource);
        } else if ("Bundle".equals(type)) {
            for (JsonNode entry : JsonSafe.elements(resource, "entry")) {
                claimResponsesIn(JsonSafe.child(entry, "resource"), out);
            }
        }
    }

    /** The value of a parameter for the screen. One that was sent with no value is empty, not absent. */
    private static Shown value(TrackedJson.Node parameter) {
        return PasResponseMapper.present(FhirViews.extensionValueShown(parameter));
    }

    /** The reference a parameter holds as text, read without consuming it: from a Reference, or from a plain url or string. */
    private static String rawReference(TrackedJson.Node parameter) {
        Map.Entry<String, TrackedJson.Node> value = FhirViews.extensionValue(parameter);
        return value == null ? null : referenceOf(value.getValue().raw());
    }

    private static String referenceOf(JsonNode value) {
        return value != null && value.isObject() ? JsonSafe.presentText(value, "reference") : JsonSafe.presentText(value);
    }

    private static void addReference(List<String> references, String reference) {
        if (reference != null && !references.contains(reference)) {
            references.add(reference);
        }
    }
}
