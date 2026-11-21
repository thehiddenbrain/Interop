package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.WireResponse;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.PreparedCall;

/**
 * A service can repeat a secret this tool holds that the request did not carry: the client secret
 * in an error answer to a call sent without sign-in, a secret variable of the collection, the key
 * of the other lookup. Such an answer is masked exactly as one that repeats a secret the request
 * carried, in the parsed body every mapper reads, in the wire view and among the returned fields.
 *
 * <p>Every secret here is a placeholder made up for the test.
 */
class HeldSecretsTest {

    private static final String CLIENT_SECRET = "placeholder-secret-value-7f3a";
    private static final String COLLECTION_SECRET = "placeholder-collection-secret-91c2";
    private static final String RUN_SECRET = "placeholder/run-secret-55aa";
    private static final String LOOKUP_KEY = "placeholder-lookup-key-3c1d";
    private static final ExchangeOptions FHIR = ExchangeOptions.json("application/fhir+json").withBodyRequired(false);

    @TempDir
    Path directory;

    /**
     * An environment and a collection that hold secrets no request here uses. The request is the
     * subscription create of the collection, whose url and headers name none of them.
     */
    private ConfigFixture configured(String... environment) {
        ConfigFixture fixture = ConfigFixture.on(directory).lookup("provider.url", "https://lookup.example.invalid/npi/{id}")
                .lookup("provider.headers.[X-Api-Key]", LOOKUP_KEY).build();
        fixture.activeEnvironmentOf("Held", EnvironmentKind.SANDBOX, environment);
        fixture.collectionOf("Plain", items(item("subscription-create Happy Path", "POST", "{{baseUrl}}/Subscription")
                .header("Accept", "application/fhir+json")),
                "[{\"key\": \"tenantPassphrase\", \"value\": \"" + COLLECTION_SECRET + "\", \"type\": \"secret\"}]");
        return fixture;
    }

    private static WireRequest sent(ConfigFixture fixture, Map<String, String> runValues) {
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE).withRunValues(runValues));
        ResolvedText body = call.resolve("{\"resourceType\":\"Subscription\"}");
        WireRequest request = Exchanges.wireRequest(call, body, FHIR, true, null);
        // Nothing the request carries holds any of them.
        assertThat(JsonSafe.mapper().writeValueAsString(request)).doesNotContain("placeholder");
        assertThat(call.url().value() + call.headers() + body.value()).doesNotContain("placeholder");
        return request;
    }

    @Test
    void anAnswerThatRepeatsASecretTheConfigurationHoldsAndTheRequestDidNotCarryIsMaskedWhereverItIsShown() {
        ConfigFixture fixture = configured("baseUrl", "https://onyx.example.invalid/fhir", "clientSecret", CLIENT_SECRET,
                "apiPassword", "placeholder-env-value-not-in-force");
        // A value given for this run in place of the environment's: the one in force is the one held.
        WireRequest request = sent(fixture, Map.of("apiPassword", RUN_SECRET));

        // In a field, as a property name, written with JSON escapes, and inside a long text.
        String body = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"security\",\"diagnostics\":"
                + "\"" + "x".repeat(4000) + " client " + CLIENT_SECRET + " refused " + "y".repeat(4000) + "\"}],"
                + "\"" + COLLECTION_SECRET + "\":\"tenant\",\"escaped\":\"placeholder\\/run-secret-55aa\",\"key\":\"" + LOOKUP_KEY + "\"}";
        VendorExchange exchange = Exchanges.fromResponse(request, 401, List.of(new WireHeader("WWW-Authenticate",
                "Basic realm=\"" + CLIENT_SECRET + "\"")), body, 7L, true);

        // The parsed body every mapper reads.
        String parsed = exchange.withReturnedSecretsMasked().json().toString();
        assertThat(parsed).doesNotContain(CLIENT_SECRET).doesNotContain(COLLECTION_SECRET).doesNotContain("run-secret-55aa")
                .doesNotContain(LOOKUP_KEY)
                .contains(" client [secret clientSecret] refused ")
                .contains("\"[secret tenantPassphrase]\":\"tenant\"")
                .contains("\"escaped\":\"[secret apiPassword]\"")
                .contains("\"key\":\"[secret X-Api-Key header]\"");
        // The fields returned but not interpreted are read from it.
        assertThat(JsonSafe.mapper().writeValueAsString(TrackedJson.of(exchange.withReturnedSecretsMasked().json()).unconsumed()))
                .doesNotContain("placeholder");

        // The wire view: the body as text and the header.
        WireResponse wire = exchange.wireResponse();
        assertThat(wire.body()).doesNotContain("placeholder").contains(" client [secret clientSecret] refused ");
        assertThat(wire.headers()).containsExactly(new WireHeader("WWW-Authenticate", "Basic realm=\"[secret clientSecret]\""));
        assertThat(wire.maskedPlaces()).isEqualTo(5);
        assertThat(wire.maskNote()).startsWith("5 places in this response, 1 in the headers and 4 in the body, are masked because they "
                + "repeat a secret this tool holds.");
        assertThat(JsonSafe.mapper().writeValueAsString(exchange.wire(null))).doesNotContain("placeholder");
    }

    @Test
    void aBodyThatIsNotJsonIsMaskedAmongTheReturnedFieldsForASecretTheRequestDidNotCarry() {
        ConfigFixture fixture = configured("baseUrl", "https://onyx.example.invalid/fhir", "clientSecret", CLIENT_SECRET);
        VendorExchange exchange = Exchanges.fromResponse(sent(fixture, Map.of()), 500, List.of(),
                "<html>invalid_client " + CLIENT_SECRET + "</html>", 7L, true);

        assertThat(exchange.bodyFields()).extracting(AdditionalField::value)
                .containsExactly("<html>invalid_client [secret clientSecret]</html>",
                        "1 place in the body as shown here is masked because it repeats a secret this tool holds.");
    }

    @Test
    void aHeldSecretOfFiveCharactersIsNotSearchedForAndIsCountedAndABlankOneMasksNothing() {
        ConfigFixture fixture = configured("baseUrl", "https://onyx.example.invalid/fhir", "apiKey", "ab-12", "clientSecret", "");
        WireRequest request = sent(fixture, Map.of());
        assertThat(request.secrets().searched()).isEqualTo(2);
        assertThat(request.secrets().tooShort()).isEqualTo(1);

        WireResponse wire = Exchanges.fromResponse(request, 400, List.of(), "{\"error\":\"ab-12 is not a key\",\"secret\":\"\"}", 3L, true)
                .wireResponse();
        assertThat(wire.body()).isEqualTo("{\"error\":\"ab-12 is not a key\",\"secret\":\"\"}");
        assertThat(wire.maskedPlaces()).isZero();
        assertThat(wire.maskNote()).isEqualTo("This tool holds 1 secret shorter than 6 characters. A secret that short is not searched for, "
                + "because it would be found by accident, so this response is not masked where it repeats that one.");
    }

    @Test
    void withNoSecretHeldOrCarriedAnAnswerIsShownAsReceivedAndNothingIsSaidAboutMasking() {
        ConfigFixture fixture = ConfigFixture.on(directory).build();
        fixture.activeEnvironmentOf("Plain", EnvironmentKind.SANDBOX, "baseUrl", "https://onyx.example.invalid/fhir", "clientSecret", "   ");
        fixture.collectionOf("Plain", items(item("subscription-create Happy Path", "POST", "{{baseUrl}}/Subscription")));
        WireRequest request = sent(fixture, Map.of());
        assertThat(request.secrets()).isSameAs(SentSecrets.none());
        WireResponse wire = Exchanges.fromResponse(request, 400, List.of(), "{\"error\":\"   \"}", 3L, true).wireResponse();
        assertThat(wire.body()).isEqualTo("{\"error\":\"   \"}");
        assertThat(wire.maskNote()).isNull();
    }

    @Test
    void aHeaderWhoseNameRepeatsAHeldSecretIsShownUnderTheMask() {
        ConfigFixture fixture = configured("baseUrl", "https://onyx.example.invalid/fhir", "clientSecret", CLIENT_SECRET);
        VendorExchange exchange = Exchanges.fromResponse(sent(fixture, Map.of()), 200, List.of(new WireHeader(CLIENT_SECRET, "x1"),
                new WireHeader("X-Trace", "t1")), "{}", 2L, true);

        WireResponse wire = exchange.wireResponse();
        assertThat(wire.headers()).extracting(WireHeader::name).containsExactly("[secret clientSecret]", "X-Trace");
        assertThat(wire.maskedPlaces()).isEqualTo(1);
        assertThat(wire.maskNote()).startsWith("1 place in the headers of this response is masked because it repeats a secret this tool holds.");
        assertThat(JsonSafe.mapper().writeValueAsString(exchange.wire(null))).doesNotContain(CLIENT_SECRET);
    }
}
