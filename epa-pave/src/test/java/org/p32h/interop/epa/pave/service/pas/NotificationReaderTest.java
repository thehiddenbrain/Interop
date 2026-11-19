package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.event;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.pas.NotificationEventView;
import org.p32h.interop.epa.pave.model.pas.NotificationKind;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;

/** How whatever arrives at the receiver is read: the notification types, how much a notification carries, and everything left over. */
class NotificationReaderTest {

    private static final String SUBSCRIPTION = "subscription-placeholder-0001";

    private final NotificationReader reader = new NotificationReader();

    private static List<String> paths(NotificationReading reading) {
        return reading.additionalFields().stream().map(AdditionalField::path).toList();
    }

    // ---- the three notifications of the backport ----

    @Test
    void anEventNotificationThatCarriesIdsOnlyIsReadWithTheSubscriptionAndTheResourceItNames() {
        NotificationReading reading = reader.read(bundle(status(SUBSCRIPTION, "event-notification",
                "{\"name\":\"events-since-subscription-start\",\"valueString\":\"2\"}", event(2, "ClaimResponse/claimresponse-claim-test-1"))));

        assertThat(reading.kind()).isEqualTo(NotificationKind.NOTIFICATION_BUNDLE);
        assertThat(reading.notification()).isTrue();
        assertThat(reading.kindNote()).isNull();
        assertThat(reading.status().type().code()).isEqualTo("event-notification");
        assertThat(reading.status().type().display()).isEqualTo("Event notification");
        assertThat(reading.status().subscriptionStatus().code()).isEqualTo("active");
        assertThat(reading.status().subscription().value()).isEqualTo("https://example.org/p32h-placeholder/fhir/Subscription/" + SUBSCRIPTION);
        assertThat(reading.status().subscriptionId()).isEqualTo(SUBSCRIPTION);
        assertThat(reading.status().topic().value()).isEqualTo(NotificationJson.TOPIC);
        assertThat(reading.status().eventsSinceSubscriptionStart().value()).isEqualTo("2");
        assertThat(reading.status().events()).containsExactly(new NotificationEventView(Shown.of("2"), Shown.of("2026-01-16T09:30:00Z"),
                Shown.of("ClaimResponse/claimresponse-claim-test-1"), List.of()));
        // It names a resource and does not carry it.
        assertThat(reading.payloadContent()).isEqualTo("id-only");
        assertThat(reading.carried().claimResponses()).isEmpty();
        assertThat(reading.carried().bundleType().value()).isEqualTo("history");
        assertThat(reading.carried().bundleTimestamp().value()).isEqualTo("2026-01-16T09:30:00Z");
        // The one thing it holds that can tie it to a submission is the id of the ClaimResponse.
        assertThat(reading.identifiers().claimResponseId()).isEqualTo("claimresponse-claim-test-1");
        assertThat(reading.identifiers().claimId()).isNull();
        assertThat(reading.identifiers().authorizationRef()).isNull();
    }

    @Test
    void anEventNotificationThatCarriesTheResourceIsReadByTheSameRulesAsAResponseToASubmission() {
        NotificationReading reading = reader.read(bundle(status(SUBSCRIPTION, "event-notification", event(1, "ClaimResponse/cr-7")),
                claimResponse("cr-7", "claim-test-1", "A1", "\"preAuthRef\":\"PA-PLACEHOLDER-0006\"", "\"disposition\":\"Denied.\"")));

        assertThat(reading.payloadContent()).isEqualTo("full-resource");
        assertThat(reading.carried().claimResponses()).singleElement().satisfies(response -> {
            // Its review action decides. The sentence beside it says the opposite and decides nothing.
            assertThat(response.decision()).isEqualTo(PasOutcome.APPROVED);
            assertThat(response.disposition().value()).isEqualTo("Denied.");
            assertThat(response.foundAt()).isEqualTo("entry[1].resource");
        });
        assertThat(reading.identifiers().claimId()).isEqualTo("claim-test-1");
        assertThat(reading.identifiers().claimResponseId()).isEqualTo("cr-7");
        assertThat(reading.identifiers().authorizationRef()).isEqualTo("PA-PLACEHOLDER-0006");
        assertThat(reading.identifiers().toString()).doesNotContain("PA-PLACEHOLDER").doesNotContain("claim-test-1");
    }

    @Test
    void aHandshakeAndAHeartbeatCarryNoEventAndAreToldApartByTheirTypeCode() {
        NotificationReading handshake = reader.read(bundle(status(SUBSCRIPTION, "handshake")));
        NotificationReading heartbeat = reader.read(bundle(status(SUBSCRIPTION, "heartbeat")));
        assertThat(handshake.status().type().code()).isEqualTo("handshake");
        assertThat(handshake.status().type().display()).isEqualTo("Handshake");
        assertThat(heartbeat.status().type().code()).isEqualTo("heartbeat");
        for (NotificationReading reading : List.of(handshake, heartbeat)) {
            assertThat(reading.kind()).isEqualTo(NotificationKind.NOTIFICATION_BUNDLE);
            assertThat(reading.status().events()).isEmpty();
            assertThat(reading.payloadContent()).isEqualTo("empty");
            assertThat(reading.identifiers().any()).isFalse();
            assertThat(reading.status().subscriptionId()).isEqualTo(SUBSCRIPTION);
        }
    }

    @Test
    void howMuchANotificationCarriedIsWorkedOutFromWhatIsInItAndNeverAssumed() {
        // Two events, one resource carried: the tool cannot call that the full resource.
        NotificationReading partial = reader.read(bundle(status(SUBSCRIPTION, "event-notification", event(1, "ClaimResponse/cr-1"),
                event(2, "ClaimResponse/cr-2")), claimResponse("cr-1", "claim-test-1", "A1")));
        assertThat(partial.payloadContent()).isEqualTo("id-only");
        // A resource named by its full url is carried when an entry has that full url.
        String byFullUrl = "{\"resourceType\":\"Bundle\",\"type\":\"history\",\"entry\":[{\"resource\":" + status(SUBSCRIPTION, "event-notification",
                event(1, "urn:uuid:00000000-0000-4000-8000-0000000000c1")) + "},{\"fullUrl\":\"urn:uuid:00000000-0000-4000-8000-0000000000c1\","
                + "\"resource\":" + claimResponse("cr-1", "claim-test-1", "A4") + "}]}";
        assertThat(reader.read(byFullUrl).payloadContent()).isEqualTo("full-resource");
        // Resources carried with no event naming them are still the full resource.
        assertThat(reader.read(bundle(status(SUBSCRIPTION, "event-notification"), claimResponse("cr-1", null, "A1"))).payloadContent())
                .isEqualTo("full-resource");
        // And an event that names nothing, with nothing carried, is empty.
        assertThat(reader.read(bundle(status(SUBSCRIPTION, "event-notification", "{\"name\":\"notification-event\",\"part\":[{\"name\":"
                + "\"event-number\",\"valueString\":\"1\"}]}"))).payloadContent()).isEqualTo("empty");
    }

    @Test
    void aStatusThatArrivesOnItsOwnAndOneInTheLaterResourceFormAreBothRead() {
        NotificationReading alone = reader.read(status(SUBSCRIPTION, "heartbeat"));
        assertThat(alone.kind()).isEqualTo(NotificationKind.STATUS_ONLY);
        assertThat(alone.notification()).isTrue();
        assertThat(alone.status().type().code()).isEqualTo("heartbeat");
        assertThat(alone.payloadContent()).isEqualTo("empty");

        String later = "{\"resourceType\":\"SubscriptionStatus\",\"status\":\"active\",\"type\":\"event-notification\","
                + "\"eventsSinceSubscriptionStart\":3,\"subscription\":{\"reference\":\"Subscription/" + SUBSCRIPTION + "\"},\"topic\":\""
                + NotificationJson.TOPIC + "\",\"notificationEvent\":[{\"eventNumber\":3,\"timestamp\":\"2026-01-16T09:30:00Z\","
                + "\"focus\":{\"reference\":\"ClaimResponse/cr-9\"},\"additionalContext\":[{\"reference\":\"Claim/claim-1\"}]}],"
                + "\"error\":[{\"coding\":[{\"code\":\"placeholder-error\"}]}]}";
        NotificationReading resource = reader.read("{\"resourceType\":\"Bundle\",\"type\":\"subscription-notification\",\"entry\":[{\"resource\":"
                + later + "}]}");
        assertThat(resource.kind()).isEqualTo(NotificationKind.NOTIFICATION_BUNDLE);
        assertThat(resource.status().type().code()).isEqualTo("event-notification");
        assertThat(resource.status().subscriptionStatus().code()).isEqualTo("active");
        assertThat(resource.status().subscriptionId()).isEqualTo(SUBSCRIPTION);
        assertThat(resource.status().eventsSinceSubscriptionStart().value()).isEqualTo("3");
        assertThat(resource.status().events()).singleElement().satisfies(one -> {
            assertThat(one.eventNumber().value()).isEqualTo("3");
            assertThat(one.focus().value()).isEqualTo("ClaimResponse/cr-9");
            assertThat(one.additionalContext()).extracting(Shown::value).containsExactly("Claim/claim-1");
        });
        assertThat(resource.status().errors()).extracting(Shown::value).containsExactly("placeholder-error");
        assertThat(resource.payloadContent()).isEqualTo("id-only");
        assertThat(resource.identifiers().claimResponseId()).isEqualTo("cr-9");
        assertThat(resource.additionalFields()).isEmpty();
    }

    @Test
    void aTypeOutsideTheValueSetIsShownAsItCameAndAbsentNullAndEmptyAreToldApart() {
        NotificationReading unknown = reader.read(bundle(status(SUBSCRIPTION, "bulk-notification")));
        assertThat(unknown.status().type().code()).isEqualTo("bulk-notification");
        assertThat(unknown.status().type().display()).isEqualTo("Unrecognised code: bulk-notification");
        assertThat(unknown.status().type().recognised()).isFalse();

        NotificationReading absent = reader.read(bundle(status(SUBSCRIPTION, null)));
        assertThat(absent.status().type().presence()).isEqualTo(Presence.ABSENT);
        assertThat(absent.notification()).isTrue();
        NotificationReading empty = reader.read(bundle(status(SUBSCRIPTION, null, "{\"name\":\"type\"}")));
        assertThat(empty.status().type().presence()).isEqualTo(Presence.EMPTY);
        assertThat(empty.status().type().code()).isNull();
        NotificationReading explicitNull = reader.read(bundle(status(SUBSCRIPTION, null, "{\"name\":\"type\",\"valueCode\":null}")));
        assertThat(explicitNull.status().type().presence()).isEqualTo(Presence.NULL);
        // A notification that names no subscription is still a notification. It just cannot be matched.
        NotificationReading nameless = reader.read(bundle(status(null, "handshake")));
        assertThat(nameless.status().subscription().presence()).isEqualTo(Presence.ABSENT);
        assertThat(nameless.status().subscriptionId()).isNull();
        assertThat(nameless.notification()).isTrue();
    }

    // ---- nothing is refused ----

    @Test
    void aBodyThatIsNotANotificationIsDescribedAsWhatItIsAndNothingOfItIsLost() {
        NotificationReading notJson = reader.read("<notification>not json</notification>");
        assertThat(notJson.kind()).isEqualTo(NotificationKind.NOT_JSON);
        assertThat(notJson.notification()).isFalse();
        assertThat(notJson.kindNote()).startsWith("The body could not be read as JSON. It is shown below exactly as it was kept.");
        assertThat(notJson.payloadContent()).isNull();
        assertThat(notJson.status().type().presence()).isEqualTo(Presence.ABSENT);

        for (String blank : new String[] {null, "", "  \n"}) {
            NotificationReading none = reader.read(blank);
            assertThat(none.kind()).isEqualTo(NotificationKind.NO_BODY);
            assertThat(none.kindNote()).isEqualTo("The request had no body.");
        }

        NotificationReading other = reader.read("{\"event\":\"placeholder\",\"detail\":{\"depth\":2}}");
        assertThat(other.kind()).isEqualTo(NotificationKind.OTHER_JSON);
        assertThat(other.kindNote()).contains("not a bundle and not a subscription status");
        assertThat(paths(other)).containsExactly("event", "detail.depth");
        assertThat(paths(reader.read("[1,{\"a\":2}]"))).containsExactly("[0]", "[1].a");

        // A bundle with no status is not a notification, and what it does carry is still read and shown.
        NotificationReading bare = reader.read("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":"
                + claimResponse("cr-7", "claim-test-1", "A3") + "}]}");
        assertThat(bare.kind()).isEqualTo(NotificationKind.OTHER_JSON);
        assertThat(bare.kindNote()).contains("carries no subscription status");
        assertThat(bare.carried().claimResponses()).singleElement().satisfies(response -> assertThat(response.decision()).isEqualTo(PasOutcome.DENIED));
        assertThat(bare.identifiers().claimId()).isEqualTo("claim-test-1");
        assertThat(bare.payloadContent()).isNull();
    }

    @Test
    void aFieldTheReaderWasNeverToldAboutReachesTheAdditionalFieldsAtEveryDepth() {
        String statusResource = "{\"resourceType\":\"Parameters\",\"id\":\"status-1\",\"vendorTrace\":\"t-1\",\"parameter\":["
                + "{\"name\":\"subscription\",\"valueReference\":{\"reference\":\"Subscription/" + SUBSCRIPTION + "\",\"type\":\"Subscription\"}},"
                + "{\"name\":\"type\",\"valueCode\":\"event-notification\",\"vendorFlag\":true},"
                + "{\"name\":\"vendor-priority\",\"valueString\":\"high\"},"
                + "{\"name\":\"notification-event\",\"part\":[{\"name\":\"event-number\",\"valueString\":\"1\"},"
                + "{\"name\":\"vendor-part\",\"valueBoolean\":true},{\"name\":\"focus\",\"valueReference\":{\"reference\":\"ClaimResponse/cr-7\"}}]}]}";
        String body = "{\"resourceType\":\"Bundle\",\"type\":\"history\",\"vendorBatch\":7,\"entry\":[{\"fullUrl\":\"urn:uuid:placeholder-1\","
                + "\"request\":{\"method\":\"GET\",\"url\":\"Subscription/" + SUBSCRIPTION + "/$status\"},\"resource\":" + statusResource + "},"
                + "{\"resource\":" + claimResponse("cr-7", "claim-test-1", "A1", "\"vendorScore\":{\"value\":0.50}") + "}]}";
        NotificationReading reading = reader.read(body);

        assertThat(reading.additionalFields()).containsExactly(
                new AdditionalField("vendorBatch", Presence.VALUE, "7"),
                new AdditionalField("entry[0].fullUrl", Presence.VALUE, "\"urn:uuid:placeholder-1\""),
                new AdditionalField("entry[0].request.method", Presence.VALUE, "\"GET\""),
                new AdditionalField("entry[0].request.url", Presence.VALUE, "\"Subscription/" + SUBSCRIPTION + "/$status\""),
                new AdditionalField("entry[0].resource.id", Presence.VALUE, "\"status-1\""),
                new AdditionalField("entry[0].resource.vendorTrace", Presence.VALUE, "\"t-1\""),
                new AdditionalField("entry[0].resource.parameter[0].valueReference.type", Presence.VALUE, "\"Subscription\""),
                new AdditionalField("entry[0].resource.parameter[1].vendorFlag", Presence.VALUE, "true"),
                // A parameter nobody knows is listed whole, its name with its value.
                new AdditionalField("entry[0].resource.parameter[2].name", Presence.VALUE, "\"vendor-priority\""),
                new AdditionalField("entry[0].resource.parameter[2].valueString", Presence.VALUE, "\"high\""),
                new AdditionalField("entry[0].resource.parameter[3].part[1].name", Presence.VALUE, "\"vendor-part\""),
                new AdditionalField("entry[0].resource.parameter[3].part[1].valueBoolean", Presence.VALUE, "true"),
                new AdditionalField("entry[1].resource.vendorScore.value", Presence.VALUE, "0.50"));
        // What the reader does understand is still read around them.
        assertThat(reading.status().type().code()).isEqualTo("event-notification");
        assertThat(reading.status().events().get(0).focus().value()).isEqualTo("ClaimResponse/cr-7");
        assertThat(reading.payloadContent()).isEqualTo("full-resource");
        assertThat(reading.carried().claimResponses().get(0).decision()).isEqualTo(PasOutcome.APPROVED);
    }

    @Test
    void somethingWorthKnowingAboutTheBodyAsAWholeComesFirst() {
        NotificationReading reading = reader.read("{\"resourceType\":\"Bundle\",\"type\":\"history\",\"type\":\"collection\",\"entry\":[{\"resource\":"
                + status(SUBSCRIPTION, "handshake") + "}]}");
        assertThat(reading.additionalFields().get(0).kind()).isEqualTo(AdditionalField.Kind.NOTE);
        assertThat(reading.additionalFields().get(0).value()).contains("repeats a key");
        assertThat(reading.status().type().code()).isEqualTo("handshake");
    }
}
