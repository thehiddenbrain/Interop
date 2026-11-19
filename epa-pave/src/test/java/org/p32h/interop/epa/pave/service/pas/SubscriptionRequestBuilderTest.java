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
    void aCreateWithNothingStatedSendsDefaultsThatAreVisiblyPlaceholdersAndSaysSoForEachOne() {
        SubscriptionRequestBuilder.Built built = new SubscriptionRequestBuilder(RECEIVER).build(null, variables(Map.of()));
        JsonNode subscription = json(built);

        assertThat(subscription.get("resourceType").asString()).isEqualTo("Subscription");
        assertThat(subscription.has("id")).isFalse();
        assertThat(subscription.at("/meta/profile/0").asString()).isEqualTo(OnyxPasConventions.SUBSCRIPTION_PROFILE);
        assertThat(subscription.get("status").asString()).isEqualTo("requested");
        assertThat(subscription.get("reason").asString()).isEqualTo("Prior authorization status notifications for a PAVE test run");
        // The topic is not known, and what is sent in its place cannot be taken for a real one.
        assertThat(subscription.get("criteria").asString()).isEqualTo(OnyxPasConventions.PLACEHOLDER_TOPIC).contains("p32h-placeholder");
        assertThat(subscription.has("_criteria")).isFalse();
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
                "The status was set to requested, which is what a client asks for and leaves the service to activate. It is this tool's "
                        + "default and not something known about the service. Set the variable subscriptionStatus to send another.",
                "The reason was set to Prior authorization status notifications for a PAVE test run, which is this tool's own wording. It is "
                        + "this tool's default and not something known about the service. Set the variable subscriptionReason to send another.",
                "The subscription topic was set to " + OnyxPasConventions.PLACEHOLDER_TOPIC + ". That is a placeholder and names no real "
                        + "topic: what the service expects here is not known. Set the variable subscriptionTopic to the topic the service "
                        + "documents.",
                "No filter was sent with the topic: the variable subscriptionFilter is not set. Whether the service needs one, and what it "
                        + "must say, is not known.",
                "No heartbeat period was sent: the variable subscriptionHeartbeatSeconds is not set.",
                "The channel type was set to rest-hook, which is the kind of channel that calls a receiver back. It is this tool's default "
                        + "and not something known about the service. Set the variable subscriptionChannelType to send another.",
                "The payload content was set to id-only, which puts the least member data on the wire. It is this tool's default and not "
                        + "something known about the service. Set the variable subscriptionPayloadContent to send another.",
                "No header was asked for on the notifications. This tool's receiver accepts a notification without one.");
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
        assertThat(built.notes()).containsExactly("No header was asked for on the notifications. This tool's receiver accepts a notification "
                + "without one.");
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
