package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.pas.ExtensionView;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionResponseKind;
import org.p32h.interop.epa.pave.model.pas.SubscriptionView;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

/** How the response to a subscription call is read: the designed view, the outcome, and everything that is left over. */
class SubscriptionResponseMapperTest {

    private static final String ID = "subscription-placeholder-0001";
    private static final String TOPIC = "https://example.org/p32h-placeholder/SubscriptionTopic/prior-authorization-status";
    private static final String RECEIVER = "https://pave.example.invalid/api/pas/notifications";

    private final SubscriptionResponseMapper mapper = new SubscriptionResponseMapper();

    /** A Subscription with the given id and status and the further fields given, each written as "name":value. */
    static String subscription(String id, String status, String... fields) {
        StringBuilder out = new StringBuilder("{\"resourceType\":\"Subscription\"");
        if (id != null) {
            out.append(",\"id\":\"").append(id).append('"');
        }
        if (status != null) {
            out.append(",\"status\":\"").append(status).append('"');
        }
        for (String field : fields) {
            out.append(',').append(field);
        }
        return out.append('}').toString();
    }

    /** The channel of a rest-hook subscription that calls this tool's receiver with ids only. */
    static String channel(String... furtherFields) {
        StringBuilder out = new StringBuilder("\"channel\":{\"type\":\"rest-hook\",\"endpoint\":\"" + RECEIVER + "\",\"payload\":\"application/fhir+json\","
                + "\"_payload\":{\"extension\":[{\"url\":\"" + OnyxPasConventions.PAYLOAD_CONTENT + "\",\"valueCode\":\"id-only\"}]}");
        for (String field : furtherFields) {
            out.append(',').append(field);
        }
        return out.append('}').toString();
    }

    static String list(String... subscriptions) {
        StringBuilder entries = new StringBuilder();
        for (String subscription : subscriptions) {
            entries.append(entries.length() == 0 ? "" : ",").append("{\"resource\":").append(subscription).append('}');
        }
        return "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":" + subscriptions.length + ",\"entry\":[" + entries + "]}";
    }

    private SubscriptionReading read(EpaOperation operation, int status, String body, String askedId) {
        // A write may answer with no body. A read may not.
        boolean bodyRequired = operation == EpaOperation.SUBSCRIPTION_GET;
        return mapper.read(TestExchanges.of(operation, status, body, bodyRequired), operation, askedId);
    }

    private static SubscriptionView only(SubscriptionReading reading) {
        assertThat(reading.detail().subscriptions()).hasSize(1);
        return reading.detail().subscriptions().get(0);
    }

    private static TestResult assess(String expected, SubscriptionReading reading) {
        return Expectations.assess(expected, reading.outcome(), List.of(), reading.answers(), SubscriptionOutcome.values(),
                PasCodes.SUBSCRIPTION_ELEMENTS).result();
    }

    // ---- the designed view ----

    @Test
    void aSubscriptionIsReadWithItsIdStatusCriteriaChannelEndpointAndHeartbeat() {
        String body = subscription(ID, "active", "\"meta\":{\"profile\":[\"" + OnyxPasConventions.SUBSCRIPTION_PROFILE + "\"]}",
                "\"end\":\"2026-12-31T00:00:00Z\"", "\"reason\":\"Placeholder reason\"", "\"criteria\":\"" + TOPIC + "\"",
                "\"_criteria\":{\"extension\":[{\"url\":\"" + OnyxPasConventions.FILTER_CRITERIA + "\",\"valueString\":\"orgIdentifier=placeholder\"}]}",
                channel("\"extension\":[{\"url\":\"" + OnyxPasConventions.HEARTBEAT_PERIOD + "\",\"valueUnsignedInt\":86400},"
                        + "{\"url\":\"" + OnyxPasConventions.TIMEOUT + "\",\"valueUnsignedInt\":60},"
                        + "{\"url\":\"" + OnyxPasConventions.MAX_COUNT + "\",\"valuePositiveInt\":10}]"));
        SubscriptionReading reading = read(EpaOperation.SUBSCRIPTION_GET, 200, body, ID);
        SubscriptionView view = only(reading);

        assertThat(reading.outcome()).isEqualTo(SubscriptionOutcome.READ);
        assertThat(reading.detail().responseKind()).isEqualTo(SubscriptionResponseKind.SUBSCRIPTION);
        assertThat(view.foundAt()).isEqualTo("(root)");
        assertThat(view.id().value()).isEqualTo(ID);
        assertThat(view.profiles()).extracting(Shown::value).containsExactly(OnyxPasConventions.SUBSCRIPTION_PROFILE);
        assertThat(view.status().code()).isEqualTo("active");
        assertThat(view.status().display()).isEqualTo("Active");
        assertThat(view.status().recognised()).isTrue();
        assertThat(view.criteria().value()).isEqualTo(TOPIC);
        assertThat(view.filters()).extracting(Shown::value).containsExactly("orgIdentifier=placeholder");
        assertThat(view.reason().value()).isEqualTo("Placeholder reason");
        assertThat(view.end().value()).isEqualTo("2026-12-31T00:00:00Z");
        assertThat(view.channelType().value()).isEqualTo("rest-hook");
        assertThat(view.endpoint().value()).isEqualTo(RECEIVER);
        assertThat(view.payload().value()).isEqualTo("application/fhir+json");
        assertThat(view.payloadContent().code()).isEqualTo("id-only");
        assertThat(view.payloadContent().display()).startsWith("Ids only");
        assertThat(view.heartbeat().value()).isEqualTo("86400");
        assertThat(view.timeout().value()).isEqualTo("60");
        assertThat(view.maxCount().value()).isEqualTo("10");
        assertThat(view.extensions()).isEmpty();
        // The view took all of it, so nothing is left to list as returned but not interpreted.
        assertThat(reading.additionalFields()).isEmpty();
        assertThat(reading.detail().responseNote()).isNull();
    }

    @Test
    void theValueOfANotificationHeaderIsNeverShownAndNeverListedAsAReturnedFieldEither() {
        String body = subscription(ID, "active", channel("\"header\":[\"Authorization: Bearer placeholder-receiver-secret\","
                + "\"placeholder-secret-with-no-name\",\"X-Api-Key:placeholder-key\"]"));
        SubscriptionReading reading = read(EpaOperation.SUBSCRIPTION_GET, 200, body, ID);
        assertThat(only(reading).headers()).extracting(Shown::value).containsExactly("Authorization: (value not shown)",
                "(a header that could not be read as Name: value, not shown)", "X-Api-Key: (value not shown)");
        assertThat(reading.additionalFields()).isEmpty();
        assertThat(JsonSafe.mapper().writeValueAsString(reading.detail()))
                .doesNotContain("placeholder-receiver-secret").doesNotContain("placeholder-secret-with-no-name").doesNotContain("placeholder-key");
    }

    @Test
    void aStatusOutsideTheValueSetIsShownAsItCameAndAbsentNullAndEmptyAreThreeDifferentThings() {
        SubscriptionView unknown = only(read(EpaOperation.SUBSCRIPTION_GET, 200, subscription(ID, "paused"), ID));
        assertThat(unknown.status().code()).isEqualTo("paused");
        assertThat(unknown.status().display()).isEqualTo("Unrecognised code: paused");
        assertThat(unknown.status().recognised()).isFalse();
        // No channel at all: every part of it is absent, which is not the same as empty.
        assertThat(unknown.channelType().presence()).isEqualTo(Presence.ABSENT);
        assertThat(unknown.endpoint().presence()).isEqualTo(Presence.ABSENT);
        assertThat(unknown.payloadContent().presence()).isEqualTo(Presence.ABSENT);

        SubscriptionView hollow = only(read(EpaOperation.SUBSCRIPTION_GET, 200, "{\"resourceType\":\"Subscription\",\"status\":null,"
                + "\"criteria\":\"\",\"channel\":{\"endpoint\":null,\"type\":\"\",\"_payload\":{\"extension\":[{\"url\":\""
                + OnyxPasConventions.PAYLOAD_CONTENT + "\"}]}}}", ID));
        assertThat(hollow.id().presence()).isEqualTo(Presence.ABSENT);
        assertThat(hollow.status().presence()).isEqualTo(Presence.NULL);
        assertThat(hollow.status().code()).isNull();
        assertThat(hollow.criteria().presence()).isEqualTo(Presence.EMPTY);
        assertThat(hollow.endpoint().presence()).isEqualTo(Presence.NULL);
        assertThat(hollow.channelType().presence()).isEqualTo(Presence.EMPTY);
        // The extension was sent with no value: present and empty, not absent.
        assertThat(hollow.payloadContent().presence()).isEqualTo(Presence.EMPTY);
    }

    // ---- nothing silently dropped ----

    @Test
    void aFieldTheMapperWasNeverToldAboutReachesTheAdditionalFieldsAtEveryDepth() {
        String one = subscription(ID, "active", "\"vendorOwner\":\"placeholder-team\"", "\"contact\":[{\"system\":\"email\",\"value\":\"qa@example.invalid\"}]",
                "\"meta\":{\"profile\":[\"" + OnyxPasConventions.SUBSCRIPTION_PROFILE + "\"],\"versionId\":\"3\"}",
                channel("\"vendorRetries\":{\"count\":2}", "\"extension\":[{\"id\":\"x-1\",\"url\":\"" + OnyxPasConventions.HEARTBEAT_PERIOD
                        + "\",\"valueUnsignedInt\":60}]"));
        SubscriptionReading single = read(EpaOperation.SUBSCRIPTION_GET, 200, one, ID);
        assertThat(single.additionalFields()).containsExactly(
                new AdditionalField("vendorOwner", Presence.VALUE, "\"placeholder-team\""),
                new AdditionalField("contact[0].system", Presence.VALUE, "\"email\""),
                new AdditionalField("contact[0].value", Presence.VALUE, "\"qa@example.invalid\""),
                new AdditionalField("meta.versionId", Presence.VALUE, "\"3\""),
                new AdditionalField("channel.vendorRetries.count", Presence.VALUE, "2"),
                new AdditionalField("channel.extension[0].id", Presence.VALUE, "\"x-1\""));
        // What the mapper does understand is still read around them.
        assertThat(only(single).heartbeat().value()).isEqualTo("60");
        assertThat(single.outcome()).isEqualTo(SubscriptionOutcome.READ);

        // And inside a list, at the root of the bundle, on the entry and on the resource.
        String listed = "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":1,\"vendorPage\":1,\"link\":[{\"relation\":\"self\"}],"
                + "\"entry\":[{\"fullUrl\":\"https://example.org/p32h-placeholder/fhir/Subscription/" + ID + "\",\"vendorEntryFlag\":true,"
                + "\"search\":{\"mode\":\"match\",\"score\":1},\"resource\":" + subscription(ID, "active", "\"vendorOwner\":\"placeholder-team\"") + "}]}";
        SubscriptionReading list = read(EpaOperation.SUBSCRIPTION_GET, 200, listed, null);
        assertThat(list.additionalFields()).extracting(AdditionalField::path).containsExactly("vendorPage", "link[0].relation",
                "entry[0].vendorEntryFlag", "entry[0].search.score", "entry[0].resource.vendorOwner");
        assertThat(only(list).fullUrl().value()).endsWith("/Subscription/" + ID);
        assertThat(only(list).searchMode().value()).isEqualTo("match");
    }

    @Test
    void anExtensionTheToolHasNeverSeenIsShownWithWhereItSat() {
        String unknown = "{\"url\":\"https://example.org/p32h-placeholder/flag\",\"valueBoolean\":true}";
        String nested = "{\"url\":\"https://example.org/p32h-placeholder/complex\",\"extension\":[{\"url\":\"part-a\",\"valueString\":\"x\"}]}";
        String body = subscription(ID, "active", "\"extension\":[" + unknown + "," + nested + "]",
                "\"_criteria\":{\"extension\":[" + unknown + "]}",
                "\"channel\":{\"type\":\"rest-hook\",\"extension\":[" + unknown + "],\"_payload\":{\"extension\":[" + unknown + ",{\"url\":\""
                        + OnyxPasConventions.PAYLOAD_CONTENT + "\",\"valueCode\":\"empty\"}]}}");
        SubscriptionReading reading = read(EpaOperation.SUBSCRIPTION_GET, 200, body, ID);
        assertThat(only(reading).extensions()).containsExactly(
                new ExtensionView("https://example.org/p32h-placeholder/flag", Shown.of("true"), List.of()),
                new ExtensionView("https://example.org/p32h-placeholder/complex", Shown.absent(),
                        List.of(new ExtensionView("part-a", Shown.of("x"), List.of()))),
                new ExtensionView("criteria: https://example.org/p32h-placeholder/flag", Shown.of("true"), List.of()),
                new ExtensionView("channel: https://example.org/p32h-placeholder/flag", Shown.of("true"), List.of()),
                new ExtensionView("payload: https://example.org/p32h-placeholder/flag", Shown.of("true"), List.of()));
        assertThat(only(reading).payloadContent().code()).isEqualTo("empty");
        assertThat(reading.additionalFields()).isEmpty();
    }

    // ---- a list ----

    @Test
    void aListIsReadSubscriptionBySubscriptionWithItsTotalAndWhateverElseItCarries() {
        String body = "{\"resourceType\":\"Bundle\",\"id\":\"search-1\",\"type\":\"searchset\",\"total\":2,\"entry\":["
                + "{\"resource\":" + subscription("sub-a", "active", channel()) + "},"
                + "{\"resource\":" + subscription("sub-b", "off") + "},"
                + "{\"resource\":{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"warning\",\"code\":\"informational\"}]}},"
                + "{\"resource\":{\"resourceType\":\"Organization\",\"id\":\"org-1\",\"name\":\"Placeholder payer\"}}]}";
        SubscriptionReading reading = read(EpaOperation.SUBSCRIPTION_GET, 200, body, null);

        assertThat(reading.outcome()).isEqualTo(SubscriptionOutcome.LISTED);
        assertThat(reading.detail().responseKind()).isEqualTo(SubscriptionResponseKind.BUNDLE);
        assertThat(reading.detail().bundleType().value()).isEqualTo("searchset");
        assertThat(reading.detail().bundleId().value()).isEqualTo("search-1");
        assertThat(reading.detail().total().value()).isEqualTo("2");
        assertThat(reading.detail().subscriptions()).extracting(SubscriptionView::label).containsExactly("sub-a", "sub-b");
        assertThat(reading.detail().subscriptions()).extracting(SubscriptionView::foundAt).containsExactly("entry[0].resource", "entry[1].resource");
        assertThat(reading.detail().issues()).hasSize(1);
        assertThat(reading.detail().otherResources()).singleElement().satisfies(other -> {
            assertThat(other.resourceType().value()).isEqualTo("Organization");
            assertThat(other.fields()).extracting(AdditionalField::path).containsExactly("entry[3].resource.name");
        });
        assertThat(reading.additionalFields()).isEmpty();

        // Each subscription is its own answer. The codes of two are never pooled.
        assertThat(reading.several()).isTrue();
        assertThat(reading.answers()).extracting(Expectations.Answer::subject).containsExactly("sub-a", "sub-b");
        assertThat(assess("LISTED", reading)).isEqualTo(TestResult.PASS);
        assertThat(assess("subscription-status=active", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("sub-a: subscription-status=active", reading)).isEqualTo(TestResult.PASS);
        assertThat(assess("sub-b: subscription-status=active", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("sub-b: channel-type=rest-hook", reading)).isEqualTo(TestResult.FAIL);
        // The ledger names each by where it sat, never by the id the service gave it.
        assertThat(reading.ledgerCodes()).containsExactly("entry[0].resource: subscription-status=active", "entry[0].resource: channel-type=rest-hook",
                "entry[0].resource: payload-content=id-only", "entry[1].resource: subscription-status=off");
    }

    @Test
    void anEmptyListIsStillAListAndNoCodeAssertionCanPassOnIt() {
        SubscriptionReading reading = read(EpaOperation.SUBSCRIPTION_GET, 200, "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0}", null);
        assertThat(reading.outcome()).isEqualTo(SubscriptionOutcome.LISTED);
        assertThat(reading.detail().subscriptions()).isEmpty();
        assertThat(reading.detail().total().value()).isEqualTo("0");
        assertThat(reading.answers()).isEmpty();
        assertThat(reading.ledgerCodes()).isEmpty();
        assertThat(assess("LISTED", reading)).isEqualTo(TestResult.PASS);
        assertThat(assess("subscription-status=active", reading)).isEqualTo(TestResult.FAIL);
        // An entry array that is there and empty is stated, not passed over.
        assertThat(read(EpaOperation.SUBSCRIPTION_GET, 200, "{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"entry\":[]}", null).additionalFields())
                .containsExactly(new AdditionalField("entry", Presence.EMPTY, "[]"));
    }

    // ---- the outcome ----

    @Test
    void aSuccessIsNamedForTheCallThatWasMadeAndOnlyWhenTheBodyIsWhatThatCallReturns() {
        String one = subscription(ID, "requested", channel());
        String outcome = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"information\",\"code\":\"informational\"}]}";
        String other = "{\"error\":\"placeholder gateway page\"}";
        Map<String, SubscriptionOutcome> expected = new LinkedHashMap<>();
        expected.put("create|" + one, SubscriptionOutcome.CREATED);
        expected.put("create|", SubscriptionOutcome.CREATED);
        expected.put("create|" + outcome, SubscriptionOutcome.CREATED);
        expected.put("create|" + other, SubscriptionOutcome.UNRECOGNISED_RESPONSE);
        expected.put("create|" + list(one), SubscriptionOutcome.UNRECOGNISED_RESPONSE);
        expected.put("update|" + one, SubscriptionOutcome.UPDATED);
        expected.put("update|", SubscriptionOutcome.UPDATED);
        expected.put("update|" + other, SubscriptionOutcome.UNRECOGNISED_RESPONSE);
        expected.put("delete|", SubscriptionOutcome.DELETED);
        expected.put("delete|" + outcome, SubscriptionOutcome.DELETED);
        expected.put("delete|" + other, SubscriptionOutcome.UNRECOGNISED_RESPONSE);
        expected.put("delete|[1,2]", SubscriptionOutcome.UNRECOGNISED_RESPONSE);
        expected.put("get|" + one, SubscriptionOutcome.READ);
        expected.put("get|" + list(one), SubscriptionOutcome.LISTED);
        expected.put("get|" + outcome, SubscriptionOutcome.UNRECOGNISED_RESPONSE);
        expected.put("get|" + other, SubscriptionOutcome.UNRECOGNISED_RESPONSE);
        Map<String, EpaOperation> operations = Map.of("create", EpaOperation.SUBSCRIPTION_CREATE, "update", EpaOperation.SUBSCRIPTION_UPDATE,
                "delete", EpaOperation.SUBSCRIPTION_DELETE, "get", EpaOperation.SUBSCRIPTION_GET);
        expected.forEach((key, wanted) -> {
            EpaOperation operation = operations.get(key.substring(0, key.indexOf('|')));
            String body = key.substring(key.indexOf('|') + 1);
            SubscriptionReading reading = read(operation, 200, body.isEmpty() ? null : body, operation == EpaOperation.SUBSCRIPTION_CREATE ? null : ID);
            assertThat(reading.outcome()).as(key).isEqualTo(wanted);
            assertThat(reading.outcome().fault()).as(key).isFalse();
        });
        // Whatever was not understood is still on screen, in full.
        SubscriptionReading unread = read(EpaOperation.SUBSCRIPTION_CREATE, 200, other, null);
        assertThat(unread.detail().responseKind()).isEqualTo(SubscriptionResponseKind.OTHER_JSON);
        assertThat(unread.additionalFields()).containsExactly(new AdditionalField("error", Presence.VALUE, "\"placeholder gateway page\""));
        assertThat(read(EpaOperation.SUBSCRIPTION_DELETE, 204, null, ID).detail().responseNote())
                .isEqualTo("The response has no body. For this call that is a normal answer.");
    }

    @Test
    void anIdTheServiceDoesNotKnowIsAFindingAboutTheIdAndAListThatAnswersTheSameWayIsARejection() {
        String notFound = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\",\"diagnostics\":\"Unknown id.\"}]}";
        for (EpaOperation operation : List.of(EpaOperation.SUBSCRIPTION_GET, EpaOperation.SUBSCRIPTION_UPDATE, EpaOperation.SUBSCRIPTION_DELETE)) {
            SubscriptionReading reading = read(operation, 404, notFound, ID);
            assertThat(reading.outcome()).as(operation.name()).isEqualTo(SubscriptionOutcome.NOT_FOUND);
            assertThat(reading.outcome().fault()).isFalse();
            // The issue is still rendered in full.
            assertThat(reading.detail().issues()).singleElement().satisfies(issue -> assertThat(issue.diagnostics().value()).isEqualTo("Unknown id."));
        }
        assertThat(read(EpaOperation.SUBSCRIPTION_GET, 410, null, ID).outcome()).isEqualTo(SubscriptionOutcome.NOT_FOUND);
        // A bare 404 says nothing about the id: a wrong url answers the same way.
        assertThat(read(EpaOperation.SUBSCRIPTION_GET, 404, "<html>Not Found</html>", ID).outcome()).isEqualTo(SubscriptionOutcome.REJECTED);
        // A list names no id, so "not found" there can only be about the address.
        assertThat(read(EpaOperation.SUBSCRIPTION_GET, 404, notFound, null).outcome()).isEqualTo(SubscriptionOutcome.REJECTED);
        assertThat(read(EpaOperation.SUBSCRIPTION_CREATE, 404, notFound, null).outcome()).isEqualTo(SubscriptionOutcome.REJECTED);
    }

    @Test
    void aRejectionAServerErrorATimeoutAnUnreachableServiceAMalformedBodyAndAnEmptyBodyAreEachShownForWhatTheyAre() {
        SubscriptionReading rejected = read(EpaOperation.SUBSCRIPTION_CREATE, 400, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":"
                + "\"error\",\"code\":\"invalid\",\"diagnostics\":\"Subscription.criteria is not a known topic.\",\"expression\":[\"Subscription.criteria\"]}],"
                + "\"vendorRequestId\":\"req-7\"}", null);
        assertThat(rejected.outcome()).isEqualTo(SubscriptionOutcome.REJECTED);
        assertThat(rejected.outcome().fault()).isTrue();
        assertThat(rejected.detail().responseKind()).isEqualTo(SubscriptionResponseKind.OPERATION_OUTCOME);
        assertThat(rejected.detail().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code().value()).isEqualTo("invalid");
            assertThat(issue.expression()).extracting(Shown::value).containsExactly("Subscription.criteria");
        });
        // A fault response is read for fields nobody anticipated exactly as a success is.
        assertThat(rejected.additionalFields()).containsExactly(new AdditionalField("vendorRequestId", Presence.VALUE, "\"req-7\""));
        assertThat(assess("subscription-status=active", rejected)).isEqualTo(TestResult.FAIL);

        SubscriptionReading gateway = read(EpaOperation.SUBSCRIPTION_UPDATE, 502, "<html>Bad Gateway</html>", ID);
        assertThat(gateway.outcome()).isEqualTo(SubscriptionOutcome.SERVICE_ERROR);
        assertThat(gateway.detail().responseKind()).isEqualTo(SubscriptionResponseKind.NOT_JSON);
        assertThat(gateway.additionalFields()).containsExactly(AdditionalField.unparsedBody("<html>Bad Gateway</html>"));

        VendorExchange timedOut = TestExchanges.timeout(EpaOperation.SUBSCRIPTION_DELETE);
        SubscriptionReading timeout = mapper.read(timedOut, EpaOperation.SUBSCRIPTION_DELETE, ID);
        assertThat(timeout.outcome()).isEqualTo(SubscriptionOutcome.TIMEOUT);
        assertThat(timeout.detail().responseKind()).isEqualTo(SubscriptionResponseKind.NO_RESPONSE);
        assertThat(timeout.detail().responseNote()).isEqualTo("No response arrived.");
        assertThat(mapper.read(TestExchanges.unavailable(EpaOperation.SUBSCRIPTION_GET), EpaOperation.SUBSCRIPTION_GET, null).outcome())
                .isEqualTo(SubscriptionOutcome.UNREACHABLE);

        SubscriptionReading malformed = read(EpaOperation.SUBSCRIPTION_GET, 200, "{\"resourceType\": \"Subscription\", ", ID);
        assertThat(malformed.outcome()).isEqualTo(SubscriptionOutcome.MALFORMED_RESPONSE);
        assertThat(malformed.detail().responseNote()).contains("could not be read as JSON").contains("exactly as received");
        assertThat(malformed.additionalFields()).containsExactly(AdditionalField.unparsedBody("{\"resourceType\": \"Subscription\", "));

        // A read with nothing in it is not an answer, and it is not a delete that succeeded either.
        SubscriptionReading empty = read(EpaOperation.SUBSCRIPTION_GET, 200, null, ID);
        assertThat(empty.outcome()).isEqualTo(SubscriptionOutcome.MALFORMED_RESPONSE);
        assertThat(empty.detail().responseKind()).isEqualTo(SubscriptionResponseKind.NO_BODY);
        assertThat(empty.detail().responseNote()).isEqualTo("The response has no body.");
    }

    // ---- an answer that is not about what was asked ----

    @Test
    void anAnswerAboutSomethingOtherThanWhatWasAskedIsShownAsWhatItIsAndTheTesterIsTold() {
        SubscriptionReading other = read(EpaOperation.SUBSCRIPTION_GET, 200, subscription("subscription-other", "active"), ID);
        assertThat(other.outcome()).isEqualTo(SubscriptionOutcome.READ);
        assertThat(other.detail().responseNote()).isEqualTo("The service was asked about the subscription " + ID
                + " and answered with the subscription subscription-other.");
        assertThat(read(EpaOperation.SUBSCRIPTION_UPDATE, 200, subscription("subscription-other", "off"), ID).detail().responseNote())
                .contains("answered with the subscription subscription-other");

        SubscriptionReading listForOne = read(EpaOperation.SUBSCRIPTION_GET, 200, list(subscription(ID, "active")), ID);
        assertThat(listForOne.outcome()).isEqualTo(SubscriptionOutcome.LISTED);
        assertThat(listForOne.detail().responseNote()).isEqualTo("One subscription was asked for and the service answered with a list.");

        SubscriptionReading oneForList = read(EpaOperation.SUBSCRIPTION_GET, 200, subscription(ID, "active"), null);
        assertThat(oneForList.outcome()).isEqualTo(SubscriptionOutcome.READ);
        assertThat(oneForList.detail().responseNote()).startsWith("A list was asked for and the service answered with one subscription.");
        // The same id as was asked needs no note.
        assertThat(read(EpaOperation.SUBSCRIPTION_GET, 200, subscription(ID, "active"), ID).detail().responseNote()).isNull();
    }

    @Test
    void theCodesOfOneSubscriptionAreItsStatusItsChannelTypeAndItsNotificationContent() {
        SubscriptionReading reading = read(EpaOperation.SUBSCRIPTION_CREATE, 201, subscription(ID, "requested", channel()), null);
        assertThat(reading.several()).isFalse();
        assertThat(reading.answers()).singleElement().satisfies(answer -> {
            assertThat(answer.subject()).isNull();
            assertThat(answer.codes()).containsExactly(Map.entry("subscription-status", List.of("requested")),
                    Map.entry("channel-type", List.of("rest-hook")), Map.entry("payload-content", List.of("id-only")));
        });
        assertThat(reading.ledgerCodes()).containsExactly("subscription-status=requested", "channel-type=rest-hook", "payload-content=id-only");
        assertThat(assess("CREATED; subscription-status=requested; payload-content=id-only", reading)).isEqualTo(TestResult.PASS);
        assertThat(assess("subscription-status=active", reading)).isEqualTo(TestResult.FAIL);
        // A subscription that came back with no status carries no such code, so nothing can be asserted of it.
        SubscriptionReading bare = read(EpaOperation.SUBSCRIPTION_CREATE, 201, "{\"resourceType\":\"Subscription\",\"id\":\"" + ID + "\"}", null);
        assertThat(bare.answers().get(0).codes()).isEmpty();
        assertThat(assess("subscription-status=requested", bare)).isEqualTo(TestResult.FAIL);
        assertThat(bare.outcome()).isEqualTo(SubscriptionOutcome.CREATED);
    }
}
