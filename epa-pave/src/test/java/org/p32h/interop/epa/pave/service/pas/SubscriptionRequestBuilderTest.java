package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import tools.jackson.databind.JsonNode;

/** What the subscription builder puts on the wire for a create and an update, and what it says it left out or chose. */
class SubscriptionRequestBuilderTest {

    private static final String RECEIVER = "https://pave.example.invalid/api/pas/notifications";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]+)}}");

    /** Resolves every placeholder in a text from a map, and reports the ones the map has no value for. */
    private static Function<String, ResolvedText> variables(Map<String, String> values) {
        return template -> {
            Matcher matcher = PLACEHOLDER.matcher(template);
            StringBuilder out = new StringBuilder();
            List<String> missing = new java.util.ArrayList<>();
            while (matcher.find()) {
                String value = values.get(matcher.group(1));
                if (value == null) {
                    missing.add(matcher.group(1));
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
            }
            matcher.appendTail(out);
            return missing.isEmpty() ? ResolvedText.plain(out.toString()) : new ResolvedText(template, null, null, List.of(), missing);
        };
    }

    private static JsonNode json(SubscriptionRequestBuilder.Built built) {
        return JsonSafe.parse(built.json()).node();
    }

    @Test
    void aCreateWithNothingStatedSendsThe201TopicAndDefaultsSaysSoForEachOneAndSaysFirstThatTheFilterIsMissing() {
        SubscriptionRequestBuilder.Built built = new SubscriptionRequestBuilder(RECEIVER).build(null, variables(Map.of()));
        JsonNode subscription = json(built);

        assertThat(subscription.get("resourceType").asString()).isEqualTo("Subscription");
        assertThat(subscription.has("id")).isFalse();
        assertThat(subscription.at("/meta/profile/0").asString()).isEqualTo(OnyxPasConventions.SUBSCRIPTION_PROFILE);
        assertThat(subscription.get("status").asString()).isEqualTo("requested");
        assertThat(subscription.get("reason").asString()).isEqualTo("Prior authorization status notifications for a PAVE test run");
        // PAS 2.0.1 input/resources/SubscriptionTopic.json: url, named by both CapabilityStatements.
        assertThat(subscription.get("criteria").asString()).isEqualTo("http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic");
        // Nobody named the requesting organization, so there is nothing to filter by, and nothing is invented.
        assertThat(subscription.has("_criteria")).isFalse();
        assertThat(built.leftOut()).containsExactly("the orgIdentifier filter PAS 2.0.1 requires");
        assertThat(subscription.has("end")).isFalse();
        assertThat(subscription.at("/channel/type").asString()).isEqualTo("rest-hook");
        assertThat(subscription.at("/channel/endpoint").asString()).isEqualTo(RECEIVER);
        assertThat(subscription.at("/channel/payload").asString()).isEqualTo("application/fhir+json");
        assertThat(subscription.at("/channel/_payload/extension/0/url").asString()).isEqualTo(OnyxPasConventions.PAYLOAD_CONTENT);
        assertThat(subscription.at("/channel/_payload/extension/0/valueCode").asString()).isEqualTo("id-only");
        assertThat(subscription.at("/channel").has("extension")).isFalse();
        assertThat(subscription.at("/channel").has("header")).isFalse();

        assertThat(built.callbackUrl()).isEqualTo(RECEIVER);
        assertThat(built.callbackProblem()).isNull();
        assertThat(built.notes()).containsExactly(
                "This subscription goes out without the orgIdentifier filter PAS 2.0.1 requires, because nobody supplied what it is built "
                        + "from. The service may refuse it for that alone.",
                "The status was set to requested, which is what a client asks for and leaves the service to activate. It is this tool's "
                        + "default and not something known about the service. PAS 2.0.1 has no Subscription profile; the PAS 2.2.0 ballot "
                        + "fixes the status to active. Set the variable subscriptionStatus to send another.",
                "The reason was set to Prior authorization status notifications for a PAVE test run, which is this tool's own wording. It is "
                        + "this tool's default and not something known about the service. Set the variable subscriptionReason to send another.",
                "The subscription topic was set to http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic, the topic PAS "
                        + "2.0.1 defines for a prior authorization result. Set the variable subscriptionTopic to send another.",
                "No filter was sent with the topic: neither pasRequestingOrgIdentifier nor subscriptionFilter is set. PAS 2.0.1 requires a "
                        + "subscription to be filtered by the requesting provider organization, through the topic's filter orgIdentifier, and "
                        + "the service may refuse it or send notifications about other organizations' requests. Set pasRequestingOrgIdentifier "
                        + "to that organization's identifier, such as its NPI.",
                "No heartbeat period was sent: the variable subscriptionHeartbeatSeconds is not set.",
                "The channel type was set to rest-hook, which is the kind of channel that calls a receiver back and the one PAS 2.0.1 says a "
                        + "server should support. It is this tool's default and not something known about the service. Set the variable "
                        + "subscriptionChannelType to send another.",
                "The payload content was set to id-only, which PAS 2.0.1 requires every client to support and which puts the least member "
                        + "data on the wire. It is this tool's default and not something known about the service. The PAS 2.2.0 ballot fixes "
                        + "full-resource instead. Set the variable subscriptionPayloadContent to send another.",
                "No header was asked for on the notifications. This tool's receiver accepts a notification without one.");
        // Printed, it holds neither the body nor the address of the receiver.
        assertThat(built.toString()).isEqualTo("Built[notes=9, namesReceiver=true]");
    }

    @Test
    void everyValueTheTesterStatesIsSentInPlaceOfADefaultAndThenNoDefaultIsNoted() {
        Map<String, String> stated = new LinkedHashMap<>();
        stated.put("subscriptionTopic", "https://example.org/p32h-placeholder/SubscriptionTopic/stated");
        stated.put("subscriptionFilter", "orgIdentifier=placeholder-organization");
        stated.put("subscriptionStatus", "off");
        stated.put("subscriptionReason", "Placeholder reason");
        stated.put("subscriptionChannelType", "websocket");
        stated.put("subscriptionPayloadContent", "full-resource");
        stated.put("subscriptionHeartbeatSeconds", "600");
        stated.put("subscriptionEnd", "2026-12-31T00:00:00Z");
        SubscriptionRequestBuilder.Built built = new SubscriptionRequestBuilder(RECEIVER).build(null, variables(stated));
        JsonNode subscription = json(built);

        assertThat(subscription.get("criteria").asString()).isEqualTo("https://example.org/p32h-placeholder/SubscriptionTopic/stated");
        assertThat(subscription.at("/_criteria/extension/0/url").asString()).isEqualTo(OnyxPasConventions.FILTER_CRITERIA);
        assertThat(subscription.at("/_criteria/extension/0/valueString").asString()).isEqualTo("orgIdentifier=placeholder-organization");
        assertThat(subscription.get("status").asString()).isEqualTo("off");
        assertThat(subscription.get("reason").asString()).isEqualTo("Placeholder reason");
        assertThat(subscription.get("end").asString()).isEqualTo("2026-12-31T00:00:00Z");
        assertThat(subscription.at("/channel/type").asString()).isEqualTo("websocket");
        assertThat(subscription.at("/channel/extension/0/url").asString()).isEqualTo(OnyxPasConventions.HEARTBEAT_PERIOD);
        assertThat(subscription.at("/channel/extension/0/valueUnsignedInt").asInt()).isEqualTo(600);
        assertThat(subscription.at("/channel/_payload/extension/0/valueCode").asString()).isEqualTo("full-resource");
        // Each value that was stated is noted with where it came from, as a default is.
        assertThat(built.notes()).containsExactly("The status was set to off, from the variable subscriptionStatus.",
                "The end was set to 2026-12-31T00:00:00Z, from the variable subscriptionEnd.",
                "The reason was set to Placeholder reason, from the variable subscriptionReason.",
                "The subscription topic was set to https://example.org/p32h-placeholder/SubscriptionTopic/stated, from the variable "
                        + "subscriptionTopic.",
                "The filter was set to orgIdentifier=placeholder-organization, from the variable subscriptionFilter.",
                "The heartbeat period in seconds was set to 600, from the variable subscriptionHeartbeatSeconds.",
                "The channel type was set to websocket, from the variable subscriptionChannelType.",
                "The payload content was set to full-resource, from the variable subscriptionPayloadContent.",
                "No header was asked for on the notifications. This tool's receiver accepts a notification without one.");
        assertThat(built.leftOut()).isEmpty();
    }

    @Test
    void theFilterIsBuiltFromTheRequestingOrganizationTheTesterNames() {
        // PAS 2.0.1 specification.md: "The Subscription parameters SHALL be identifier = [requesting provider organization id]",
        // and SubscriptionTopic.json canFilterBy orgIdentifier. The string is the form of the 2.2.0 ballot's own example.
        SubscriptionRequestBuilder.Built built = new SubscriptionRequestBuilder(RECEIVER).build(null, variables(Map.of(
                "pasRequestingOrgIdentifier", "1234567893", "pasRequestingOrgIdentifierSystem", "http://hl7.org/fhir/sid/us-npi")));
        JsonNode subscription = json(built);
        assertThat(subscription.at("/_criteria/extension/0/url").asString()).isEqualTo(OnyxPasConventions.FILTER_CRITERIA);
        assertThat(subscription.at("/_criteria/extension/0/valueString").asString()).isEqualTo("orgIdentifier=http://hl7.org/fhir/sid/us-npi|1234567893");
        assertThat(built.leftOut()).isEmpty();
        assertThat(built.notes().get(0)).doesNotStartWith("This subscription goes out without");

        // With no system given, none is assumed: the filter names the identifier alone, and the note says so.
        SubscriptionRequestBuilder.Built bare = new SubscriptionRequestBuilder(RECEIVER).build(null,
                variables(Map.of("pasRequestingOrgIdentifier", "1234567893")));
        assertThat(json(bare).at("/_criteria/extension/0/valueString").asString()).isEqualTo("orgIdentifier=1234567893");
        assertThat(bare.notes()).anySatisfy(note -> assertThat(note).contains("No identifier system was given"));
        assertThat(bare.leftOut()).isEmpty();
    }

    @Test
    void anUpdateCarriesTheIdOfTheSubscriptionItReplaces() {
        JsonNode subscription = json(new SubscriptionRequestBuilder(RECEIVER).build(" subscription-placeholder-0001 ", variables(Map.of())));
        assertThat(subscription.get("id").asString()).isEqualTo("subscription-placeholder-0001");
        // The id comes straight after the resource type, where a reader of the wire view looks for it.
        assertThat(subscription.propertyNames()).startsWith("resourceType", "id", "meta", "status");
    }

    @Test
    void withNoCallbackUrlTheSubscriptionIsStillBuiltWithoutAnEndpointAndTheProblemSaysWhatToSet() {
        for (String blank : new String[] {null, "", "   "}) {
            SubscriptionRequestBuilder.Built built = new SubscriptionRequestBuilder(blank).build(null, variables(Map.of()));
            assertThat(json(built).at("/channel").has("endpoint")).isFalse();
            assertThat(json(built).at("/channel/type").asString()).isEqualTo("rest-hook");
            assertThat(built.callbackUrl()).isNull();
            assertThat(built.callbackProblem()).isEqualTo("The setting onyx.subscription.callback-url is blank. Set it to the address at "
                    + "which the service can reach this tool's receiver, which ends in /api/pas/notifications, and send again.");
            assertThat(built.notes()).contains("No endpoint was sent, so the service has nowhere to call back. " + built.callbackProblem());
        }
    }

    @Test
    void aCallbackUrlMayHoldPlaceholdersAndOneWithNoValueIsNotSentHalfResolved() {
        SubscriptionRequestBuilder builder = new SubscriptionRequestBuilder("{{paveBaseUrl}}/api/pas/notifications");
        SubscriptionRequestBuilder.Built resolved = builder.build(null, variables(Map.of("paveBaseUrl", "https://pave.example.invalid")));
        assertThat(json(resolved).at("/channel/endpoint").asString()).isEqualTo(RECEIVER);
        assertThat(resolved.callbackProblem()).isNull();

        SubscriptionRequestBuilder.Built unresolved = builder.build(null, variables(Map.of()));
        assertThat(json(unresolved).at("/channel").has("endpoint")).isFalse();
        assertThat(unresolved.json()).doesNotContain("paveBaseUrl");
        assertThat(unresolved.callbackUrl()).isNull();
        assertThat(unresolved.callbackProblem()).isEqualTo("The setting onyx.subscription.callback-url holds placeholders that have no value: "
                + "paveBaseUrl. Give each a value, or write the address out in full, and send again.");
    }

    @Test
    void aHeartbeatThatIsNotAWholeNumberOfSecondsIsLeftOutAndTheNoteSaysSo() {
        SubscriptionRequestBuilder.Built built = new SubscriptionRequestBuilder(RECEIVER).build(null,
                variables(Map.of("subscriptionHeartbeatSeconds", "ten minutes")));
        assertThat(json(built).at("/channel").has("extension")).isFalse();
        assertThat(built.notes()).contains("No heartbeat period was sent: the variable subscriptionHeartbeatSeconds does not hold a whole "
                + "number of seconds.");
        assertThat(built.json()).doesNotContain("ten minutes");
    }

    @Test
    void theReceiverABodyOfTheTestersOwnNamesIsReadFromItsChannelEndpoint() {
        assertThat(SubscriptionRequestBuilder.endpointOf("{\"resourceType\":\"Subscription\",\"channel\":{\"endpoint\":\" " + RECEIVER + " \"}}"))
                .isEqualTo(RECEIVER);
        assertThat(SubscriptionRequestBuilder.endpointOf("{\"resourceType\":\"Subscription\",\"channel\":{\"type\":\"rest-hook\"}}")).isNull();
        assertThat(SubscriptionRequestBuilder.endpointOf("{\"resourceType\":\"Subscription\",\"channel\":{\"endpoint\":\"\"}}")).isNull();
        assertThat(SubscriptionRequestBuilder.endpointOf("{\"resourceType\":\"Subscription\"}")).isNull();
        assertThat(SubscriptionRequestBuilder.endpointOf("{\"resourceType\": ")).isNull();
        assertThat(SubscriptionRequestBuilder.endpointOf(null)).isNull();
    }
}
