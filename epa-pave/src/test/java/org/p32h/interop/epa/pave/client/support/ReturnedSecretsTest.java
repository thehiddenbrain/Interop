package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.ScenarioBehavior;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.WireResponse;
import org.p32h.interop.epa.pave.model.WireView;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioSelection;
import org.p32h.interop.epa.pave.service.scenario.ScenarioStep;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * A secret a service sends back is not shown in the wire view.
 *
 * <p>The request side hides a secret by the name of the variable it came from. These tests hold
 * the response side to the same thing, at the level of the shared types, so it holds for every
 * protocol without a line in any of their services: on live through the one executor, on mock
 * through the one mock, and for a mock client that rewrites the body it was given.
 *
 * <p>Every secret here is a placeholder made up for the test.
 */
class ReturnedSecretsTest {

    private static final EpaOperation CREATE = EpaOperation.SUBSCRIPTION_CREATE;
    private static final String URL = "https://onyx.example.invalid/fhir/Subscription";
    private static final String TOKEN = "placeholder-callback-token";
    private static final String KEY = "placeholder-api-key-0001";
    private static final String CONTEXT = "placeholder-context-secret";
    private static final String LITERAL = "placeholder-literal-credential";
    private static final String ACCESS = "placeholder-access-token";
    private static final String EXPLAINED = " A masked place is shown as [secret ...], naming the variable, the header or the token whose "
            + "value it repeats. What is shown here is therefore not exactly what was received. The byte count is the count of what was "
            + "received.";
    private static final ExchangeOptions FHIR = ExchangeOptions.json("application/fhir+json").withBodyRequired(false);

    private static WireRequest request(SentSecrets secrets) {
        return new WireRequest("POST", URL, URL, List.of(), "{}", true, null, secrets);
    }

    private static SentSecrets tokenAndKey() {
        return SentSecrets.builder().variable("callbackToken", TOKEN).variable("apiKey", KEY).build();
    }

    // ---- the shared types ----

    @Test
    void theWireViewMasksEveryPlaceThatRepeatsASecretAndSaysHowManyWhileAMapperStillReadsWhatArrived() {
        String body = "{\"resourceType\":\"Subscription\",\"channel\":{\"header\":[\"Authorization: Bearer " + TOKEN + "\"],\"endpoint\":\""
                + "https://pave.example.invalid/notify?key=" + KEY + "\"}}";
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        List<WireHeader> headers = List.of(new WireHeader("Content-Type", "application/fhir+json"),
                new WireHeader("Location", URL + "/s-1?key=" + KEY), new WireHeader("X-Echo", TOKEN + " " + TOKEN));
        VendorExchange exchange = Exchanges.fromBytes(request(tokenAndKey()), 201, headers, raw, "application/fhir+json", 12L, true);

        WireResponse shown = exchange.wire(null).response();

        assertThat(shown.body()).isEqualTo("{\"resourceType\":\"Subscription\",\"channel\":{\"header\":[\"Authorization: Bearer "
                + "[secret callbackToken]\"],\"endpoint\":\"https://pave.example.invalid/notify?key=[secret apiKey]\"}}");
        assertThat(shown.headers()).containsExactly(new WireHeader("Content-Type", "application/fhir+json"),
                new WireHeader("Location", URL + "/s-1?key=[secret apiKey]"), new WireHeader("X-Echo", "[secret callbackToken] [secret callbackToken]"));
        assertThat(shown.maskedPlaces()).isEqualTo(5);
        assertThat(shown.maskNote()).isEqualTo("5 places in this response, 3 in the headers and 2 in the body, are masked because they repeat "
                + "a secret this request sent." + EXPLAINED);
        // The count is of what arrived, not of the text that is shown in its place.
        assertThat(shown.byteLength()).isEqualTo((long) raw.length);
        assertThat(shown.status()).isEqualTo(201);
        assertThat(shown.parsed()).isTrue();

        // Nothing that reads the response is given the masked form.
        assertThat(exchange.responseBody()).isEqualTo(body);
        assertThat(exchange.json().at("/channel/header/0").asString()).isEqualTo("Authorization: Bearer " + TOKEN);
        assertThat(exchange.responseHeaders()).isEqualTo(headers);
        assertThat(exchange.responseBytes()).isEqualTo((long) raw.length);
        assertThat(exchange.bodyFields()).isEmpty();
    }

    @Test
    void aResponseThatRepeatsNoSecretIsShownAsReceivedAndSaysNothingAboutMasking() {
        String body = "{\"resourceType\":\"Subscription\",\"status\":\"active\"}";
        List<WireHeader> headers = List.of(new WireHeader("Location", URL + "/s-1"));
        for (SentSecrets secrets : List.of(tokenAndKey(), SentSecrets.none())) {
            WireResponse shown = Exchanges.fromResponse(request(secrets), 200, headers, body, 12L, true).wireResponse();
            assertThat(shown.body()).isEqualTo(body);
            assertThat(shown.headers()).isEqualTo(headers);
            assertThat(shown.maskedPlaces()).isZero();
            assertThat(shown.maskNote()).isNull();
        }
        // A request built with no word about secrets carries none.
        WireRequest plain = new WireRequest("POST", URL, URL, List.of(), "{}", true, null);
        assertThat(plain.secrets()).isSameAs(SentSecrets.none());
        assertThat(new WireRequest("POST", URL, URL, List.of(), "{}", true, null, null).secrets()).isSameAs(SentSecrets.none());
        assertThat(Exchanges.fromResponse(plain, 200, List.of(), TOKEN, 1L, false).wireResponse().body()).isEqualTo(TOKEN);
    }

    @Test
    void oneMaskedPlaceIsWordedAsOneAndTheNoteSaysWhichPartOfTheResponseItIsIn() {
        WireResponse inBody = Exchanges.fromResponse(request(tokenAndKey()), 200, List.of(new WireHeader("X-Request-Id", "r-1")),
                "{\"echo\":\"" + TOKEN + "\"}", 1L, true).wireResponse();
        assertThat(inBody.maskedPlaces()).isEqualTo(1);
        assertThat(inBody.maskNote()).isEqualTo("1 place in the body of this response is masked because it repeats a secret this request sent."
                + EXPLAINED);

        WireResponse inHeaders = Exchanges.fromResponse(request(tokenAndKey()), 200, List.of(new WireHeader("X-Echo", KEY),
                new WireHeader("X-Echo-Again", KEY)), "{}", 1L, true).wireResponse();
        assertThat(inHeaders.maskedPlaces()).isEqualTo(2);
        assertThat(inHeaders.maskNote()).isEqualTo("2 places in the headers of this response are masked because they repeat a secret this "
                + "request sent." + EXPLAINED);
        assertThat(inHeaders.body()).isEqualTo("{}");
    }

    @Test
    void aBodyThatCouldNotBeReadIsMaskedAmongTheReturnedFieldsExactlyAsItIsInTheWireView() {
        // A gateway in front of the service answers with a page that quotes the request it refused.
        String page = "<html><body>Refused. Request headers: Authorization: Bearer " + TOKEN + "; X-Api-Key: " + KEY + "</body></html>";
        VendorExchange exchange = Exchanges.fromResponse(request(tokenAndKey()), 502, List.of(), page, 3L, true);

        String shown = "<html><body>Refused. Request headers: Authorization: Bearer [secret callbackToken]; X-Api-Key: [secret apiKey]</body></html>";
        assertThat(exchange.wireResponse().body()).isEqualTo(shown);
        List<AdditionalField> fields = exchange.bodyFields();
        assertThat(fields).extracting(AdditionalField::kind).containsExactly(AdditionalField.Kind.RAW_BODY, AdditionalField.Kind.NOTE);
        assertThat(fields.get(0).value()).isEqualTo(shown);
        assertThat(fields.get(1).value()).isEqualTo("2 places in the body as shown here are masked because they repeat a secret this request sent.");
        // What arrived is still there for whoever has to read it.
        assertThat(exchange.responseBody()).isEqualTo(page);

        // With nothing to mask, the body is listed as received and no note is added.
        List<AdditionalField> untouched = Exchanges.fromResponse(request(tokenAndKey()), 502, List.of(), "<html>Bad Gateway</html>", 3L, true)
                .bodyFields();
        assertThat(untouched).singleElement().satisfies(field -> assertThat(field.value()).isEqualTo("<html>Bad Gateway</html>"));
    }

    @Test
    void aSecretTooShortToSearchForIsSaidInTheWireViewAndNothingIsSaidWhenNoResponseArrived() {
        SentSecrets oneShort = SentSecrets.builder().variable("pin", "12345").build();
        WireResponse shown = Exchanges.fromResponse(request(oneShort), 200, List.of(), "{\"pin\":\"12345\"}", 1L, true).wireResponse();
        assertThat(shown.body()).isEqualTo("{\"pin\":\"12345\"}");
        assertThat(shown.maskedPlaces()).isZero();
        assertThat(shown.maskNote()).isEqualTo("This request sent 1 secret shorter than 6 characters. A secret that short is not searched "
                + "for, because it would be found by accident, so this response is not masked where it repeats that one.");

        SentSecrets mixed = SentSecrets.builder().variable("pin", "12345").variable("code", "abc").variable("apiKey", KEY).build();
        WireResponse both = Exchanges.fromResponse(request(mixed), 200, List.of(), "{\"key\":\"" + KEY + "\"}", 1L, true).wireResponse();
        assertThat(both.maskNote()).isEqualTo("1 place in the body of this response is masked because it repeats a secret this request sent."
                + EXPLAINED + " This request also sent 2 secrets shorter than 6 characters. A secret that short is not searched for, "
                + "because it would be found by accident, so this response is not masked where it repeats one of those.");

        // No response, so nothing is shown and there is nothing to say about how it is shown.
        for (VendorExchange none : List.of(Exchanges.timeout(request(mixed), 5L), Exchanges.unavailable(request(mixed), 5L, "Refused."))) {
            assertThat(none.wireResponse().maskNote()).isNull();
            assertThat(none.wireResponse().maskedPlaces()).isZero();
            assertThat(none.wireResponse().body()).isNull();
        }
    }

    @Test
    void theSecretsOfARequestAreNeverSerialisedOrPrintedWithIt() {
        WireRequest request = request(tokenAndKey());
        VendorExchange exchange = Exchanges.fromResponse(request, 200, List.of(new WireHeader("X-Echo", KEY)), "{\"echo\":\"" + TOKEN + "\"}", 1L, true);
        WireView wire = exchange.wire(null);

        assertThat(JsonSafe.mapper().valueToTree(request).propertyNames()).containsExactlyInAnyOrder("method", "url", "urlTemplate", "headers",
                "body", "sent");
        assertThat(JsonSafe.mapper().valueToTree(wire.response()).propertyNames()).containsExactlyInAnyOrder("status", "headers", "body",
                "byteLength", "parsed", "maskedPlaces", "maskNote");
        assertThat(JsonSafe.mapper().writeValueAsString(wire)).doesNotContain(TOKEN).doesNotContain(KEY).contains("[secret callbackToken]")
                .contains("\"maskedPlaces\":2");
        for (Object printed : new Object[] {request, wire, wire.response(), exchange, request.secrets()}) {
            assertThat(printed.toString()).as(printed.getClass().getSimpleName()).doesNotContain(TOKEN).doesNotContain(KEY);
        }
        // A response that is shown as received says so with a zero, and has no note to carry.
        assertThat(JsonSafe.mapper().valueToTree(Exchanges.fromResponse(request, 200, List.of(), "{}", 1L, true).wireResponse())
                .propertyNames()).containsExactlyInAnyOrder("status", "headers", "body", "byteLength", "parsed", "maskedPlaces");
    }

    @Test
    void aResponseCannotCarryMaskedPlacesWithoutSayingSo() {
        assertThatThrownBy(() -> new WireResponse(200, List.of(), "[secret apiKey]", 9L, false, null, 1, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WireResponse(200, List.of(), "[secret apiKey]", 9L, false, null, 1, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WireResponse(200, List.of(), "{}", 2L, true, null, -1, null)).isInstanceOf(IllegalArgumentException.class);
        WireResponse asReceived = new WireResponse(200, List.of(), "{}", 2L, true, null);
        assertThat(asReceived.maskedPlaces()).isZero();
        assertThat(asReceived.maskNote()).isNull();
    }

    // ---- a call: where the secrets of a request are found ----

    /** A gateway whose create carries a secret in its url, in two headers and, through the body resolved below, in its body. */
    private static FakeGateway gateway() {
        return new FakeGateway().variable("baseUrl", "https://onyx.example.invalid/fhir").variable("apiKey", KEY)
                .variable("callbackToken", TOKEN).variable("contextSecret", CONTEXT)
                .url(CREATE, "{{baseUrl}}/Subscription?apikey={{apiKey}}")
                .headers(CREATE, List.of(
                        // A secret variable inside a header whose own name says nothing.
                        new ResolvedHeader("X-Context", "ctx [secret contextSecret]", "ctx " + CONTEXT),
                        // A credential typed straight into the collection. No variable holds it.
                        new ResolvedHeader("X-Vendor-Key", LITERAL, LITERAL)));
    }

    private static final String SUBSCRIPTION = "{\"resourceType\":\"Subscription\",\"channel\":{\"header\":[\"Authorization: Bearer {{callbackToken}}\"]}}";

    /** What a service that sends everything back would answer with. */
    private static String reflecting() {
        return "{\"resourceType\":\"Subscription\",\"id\":\"s-1\",\"channel\":{\"header\":[\"Authorization: Bearer " + TOKEN + "\"]},"
                + "\"received\":{\"url\":\"https://onyx.example.invalid/fhir/Subscription?apikey=" + KEY + "\",\"context\":\"ctx " + CONTEXT
                + "\",\"vendorKey\":\"" + LITERAL + "\",\"authorization\":\"Bearer " + ACCESS + "\"}}";
    }

    private static final String REFLECTED_AS_SHOWN = "{\"resourceType\":\"Subscription\",\"id\":\"s-1\",\"channel\":{\"header\":[\"Authorization: "
            + "Bearer [secret callbackToken]\"]},\"received\":{\"url\":\"https://onyx.example.invalid/fhir/Subscription?apikey=[secret apiKey]\","
            + "\"context\":\"ctx [secret contextSecret]\",\"vendorKey\":\"[secret X-Vendor-Key header]\",\"authorization\":\"Bearer "
            + "[secret access token]\"}}";

    @Test
    void onLiveEverySecretTheRequestCarriedIsMaskedInTheResponseUnderTheNameItIsKnownBy() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PreparedCall call = gateway().prepare(CallIntent.of(CREATE));
        server.expect(requestTo("https://onyx.example.invalid/fhir/Subscription?apikey=" + KEY))
                .andExpect(header("X-Context", "ctx " + CONTEXT)).andExpect(header("X-Vendor-Key", LITERAL))
                .andExpect(header("Authorization", "Bearer " + ACCESS))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(reflecting())
                        .header("Location", "https://onyx.example.invalid/fhir/Subscription/s-1?apikey=" + KEY));

        VendorExchange exchange = new ExchangeExecutor().execute(builder.build(), call, call.resolve(SUBSCRIPTION), FHIR.withBearerToken(ACCESS));

        server.verify();
        WireView wire = exchange.wire(null);
        // The request was already shown without them.
        assertThat(wire.request().sent()).isTrue();
        assertThat(wire.request().url()).isEqualTo("https://onyx.example.invalid/fhir/Subscription?apikey=[secret apiKey]");
        assertThat(wire.request().body()).isEqualTo(SUBSCRIPTION.replace("{{callbackToken}}", "[secret callbackToken]"));
        // And now the response is too, each place under the name the request side uses, or says what it is.
        assertThat(wire.response().body()).isEqualTo(REFLECTED_AS_SHOWN);
        assertThat(wire.response().headers()).contains(new WireHeader("Location",
                "https://onyx.example.invalid/fhir/Subscription/s-1?apikey=[secret apiKey]"));
        assertThat(wire.response().maskedPlaces()).isEqualTo(6);
        assertThat(wire.response().maskNote()).startsWith("6 places in this response, 1 in the headers and 5 in the body, are masked");
        assertThat(wire.response().byteLength()).isEqualTo((long) reflecting().getBytes(StandardCharsets.UTF_8).length);
        assertThat(JsonSafe.mapper().writeValueAsString(wire)).doesNotContain(TOKEN).doesNotContain(KEY).doesNotContain(CONTEXT)
                .doesNotContain(LITERAL).doesNotContain(ACCESS);
        // The mapper reads what the service really sent.
        assertThat(exchange.json().at("/channel/header/0").asString()).isEqualTo("Authorization: Bearer " + TOKEN);
    }

    private static ScenarioSelection scenario(String body) {
        ScenarioStep step = new ScenarioStep(ScenarioBehavior.RESPOND, 201, 0L, body, null);
        return new ScenarioSelection(new Scenario("test-01", 1, "Test", "d", null, null, step, Map.of(), null, "Synthetic.", null, null, "test.json"),
                "chosen in the scenario selector");
    }

    @Test
    void onMockTheSameSecretsAreMaskedAlthoughNothingWasSent() {
        MockExchanges mock = new MockExchanges(AppProperties.of(Map.of("mock-latency-enabled", "false")));
        PreparedCall call = gateway().prepare(CallIntent.of(CREATE));

        VendorExchange exchange = mock.answer(call, call.resolve(SUBSCRIPTION), FHIR, scenario(reflecting()), null);

        assertThat(exchange.request().sent()).isFalse();
        // No token is obtained on mock, so the one in the canned answer repeats nothing this request carried.
        assertThat(exchange.wireResponse().body()).isEqualTo(REFLECTED_AS_SHOWN.replace("[secret access token]", ACCESS));
        assertThat(exchange.wireResponse().maskedPlaces()).isEqualTo(4);
        assertThat(exchange.responseBody()).isEqualTo(reflecting());
    }

    @Test
    void aMockClientThatRewritesTheBodyItWasGivenKeepsTheRequestAndSoKeepsWhatMustStayHidden() {
        MockExchanges mock = new MockExchanges(AppProperties.of(Map.of("mock-latency-enabled", "false")));
        PreparedCall call = gateway().prepare(CallIntent.of(CREATE));
        VendorExchange first = mock.answer(call, call.resolve(SUBSCRIPTION), FHIR, scenario("{}"), null);

        // As the CRD, DTR and PAS mocks do when they answer every order or echo an id.
        VendorExchange rebuilt = Exchanges.fromResponse(first.request(), first.httpStatus(), first.responseHeaders(), reflecting(),
                first.elapsedMillis(), true).withScenario("test-01", "chosen in the scenario selector").withElapsedMillis(7).withSignals(java.util.Set.of());

        assertThat(rebuilt.wireResponse().body()).isEqualTo(REFLECTED_AS_SHOWN.replace("[secret access token]", ACCESS));
        assertThat(rebuilt.wire(null).response().maskedPlaces()).isEqualTo(4);
    }

    @Test
    void anAccessTokenThatWasNotSentIsNotASecretOfTheRequestAndTheCredentialThatWentInItsPlaceIs() {
        PreparedCall call = TestPreparedCalls.to(CREATE, "POST", URL,
                List.of(new ResolvedHeader("Authorization", "Basic [redacted]", "Basic cGxhY2Vob2xkZXI6Y3JlZGVudGlhbA==")),
                new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX));

        WireRequest request = Exchanges.wireRequest(call, ResolvedText.plain("{}"), FHIR.withBearerToken(ACCESS), true, null);

        String body = "{\"token\":\"" + ACCESS + "\",\"authorization\":\"Basic cGxhY2Vob2xkZXI6Y3JlZGVudGlhbA==\"}";
        WireResponse shown = Exchanges.fromResponse(request, 200, List.of(), body, 1L, true).wireResponse();
        // The collection's own Authorization header went out, so the token was left off and is nothing this request sent.
        assertThat(shown.body()).isEqualTo("{\"token\":\"" + ACCESS + "\",\"authorization\":\"Basic [secret Authorization header]\"}");
        assertThat(shown.maskedPlaces()).isEqualTo(1);
    }

    @Test
    void aTextWhoseTwoFormsDifferInOnePlaceGivesUpItsSecretWithoutAnyoneBeingAsked() {
        // Built by hand, with a call that resolves nothing: the shown form and the sent form are all there is to go by.
        ResolvedUrl url = new ResolvedUrl("{{baseUrl}}/Subscription?key={{apiKey}}", URL + "?key=[secret apiKey]", URL + "?key=" + KEY,
                List.of(), List.of(), "test");
        PreparedCall call = TestPreparedCalls.with(CREATE, "POST", url,
                List.of(new ResolvedHeader("X-Context", "ctx [secret contextSecret] end", "ctx " + CONTEXT + " end")),
                new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX), "subscription-create Happy Path", false);
        ResolvedText body = new ResolvedText("{\"h\":\"Bearer {{callbackToken}}\"}", "{\"h\":\"Bearer [secret callbackToken]\"}",
                "{\"h\":\"Bearer " + TOKEN + "\"}", List.of(), List.of());

        WireRequest request = Exchanges.wireRequest(call, body, FHIR, true, null);

        WireResponse shown = Exchanges.fromResponse(request, 200, List.of(), "{\"a\":\"" + KEY + "\",\"b\":\"" + CONTEXT + "\",\"c\":\"" + TOKEN + "\"}",
                1L, true).wireResponse();
        assertThat(shown.body()).isEqualTo("{\"a\":\"[secret apiKey]\",\"b\":\"[secret contextSecret]\",\"c\":\"[secret callbackToken]\"}");
        assertThat(shown.maskedPlaces()).isEqualTo(3);
    }

    @Test
    void twoSecretsInOneTextAreEachAskedForByNameBecauseTheTextAloneCannotTellThemApart() {
        FakeGateway gateway = new FakeGateway().variable("clientSecret", "placeholder-part-one").variable("apiKey", "placeholder-part-two")
                .url(CREATE, URL);
        PreparedCall call = gateway.prepare(CallIntent.of(CREATE));

        // Side by side with nothing between them: where one ends and the other begins is not in the text.
        WireRequest request = Exchanges.wireRequest(call, call.resolve("{\"pair\":\"{{clientSecret}}{{apiKey}}\"}"), FHIR, true, null);

        assertThat(request.body()).isEqualTo("{\"pair\":\"[secret clientSecret][secret apiKey]\"}");
        WireResponse shown = Exchanges.fromResponse(request, 200, List.of(),
                "{\"one\":\"placeholder-part-one\",\"two\":\"placeholder-part-two\"}", 1L, true).wireResponse();
        assertThat(shown.body()).isEqualTo("{\"one\":\"[secret clientSecret]\",\"two\":\"[secret apiKey]\"}");
        assertThat(shown.maskedPlaces()).isEqualTo(2);
    }

    @Test
    void aMaskThatWasTypedIntoTheRequestIsOrdinaryTextAndNotASecret() {
        // A tester copies a request out of the wire view, masks and all, and sends it as a body of their own.
        FakeGateway gateway = new FakeGateway().variable("callbackToken", TOKEN).variable("apiKey", KEY).url(CREATE, URL);
        PreparedCall call = gateway.prepare(CallIntent.of(CREATE));
        String pasted = "{\"header\":[\"Authorization: Bearer [secret callbackToken]\"]}";

        WireRequest request = Exchanges.wireRequest(call, call.resolve(pasted), FHIR, true, null);

        // Nothing was resolved, so nothing is hidden, and the response is not said to repeat a secret when it repeats that text.
        assertThat(request.secrets()).isEqualTo(SentSecrets.none());
        WireResponse shown = Exchanges.fromResponse(request, 200, List.of(), pasted, 1L, true).wireResponse();
        assertThat(shown.body()).isEqualTo(pasted);
        assertThat(shown.maskedPlaces()).isZero();
        assertThat(shown.maskNote()).isNull();

        // Beside a placeholder that does resolve, the typed mask is still only text, and the real secret is still found.
        String mixed = "{\"header\":[\"Authorization: Bearer [secret callbackToken]\"],\"key\":\"{{apiKey}}\"}";
        WireRequest second = Exchanges.wireRequest(call, call.resolve(mixed), FHIR, true, null);
        WireResponse again = Exchanges.fromResponse(second, 200, List.of(), mixed.replace("{{apiKey}}", KEY) + " " + TOKEN, 1L, false).wireResponse();
        assertThat(again.body()).isEqualTo(mixed.replace("{{apiKey}}", "[secret apiKey]") + " " + TOKEN);
        assertThat(again.maskedPlaces()).isEqualTo(1);
    }

    @Test
    void aBodyTheMethodDoesNotSendAndAHeaderWithNoValueAddNoSecret() {
        FakeGateway gateway = new FakeGateway().variable("callbackToken", TOKEN).url(EpaOperation.SUBSCRIPTION_GET, URL)
                .headers(EpaOperation.SUBSCRIPTION_GET, List.of(ResolvedHeader.unresolved("X-Trace", List.of("traceId"))));
        PreparedCall call = gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET));

        // A GET sends no body, so a secret in a body that was handed over and not sent is not one the request carried.
        WireRequest request = Exchanges.wireRequest(call, call.resolve(SUBSCRIPTION), ExchangeOptions.read("application/fhir+json"), false, null);

        assertThat(request.body()).isNull();
        assertThat(request.secrets()).isEqualTo(SentSecrets.none());
        assertThat(request.headers()).contains(new WireHeader("X-Trace", "(no value)"));
    }

    // ---- the clients of the other protocols, with nothing added to them ----

    @Test
    void theCrdAndPasMockClientsMaskWhatTheirScenarioSendsBackWithNothingAddedToThem(@TempDir Path directory) throws IOException {
        Files.createDirectories(directory.resolve("crd"));
        Files.createDirectories(directory.resolve("pas"));
        Files.writeString(directory.resolve("crd").resolve("crd-90-key-returned.json"), """
                {"id": "crd-90-key-returned", "number": 90, "label": "A card that quotes the key", "basis": "Synthetic. For a test of this tool only.",
                 "httpStatus": 200,
                 "response": {"cards": [{"summary": "Refused", "indicator": "warning", "source": {"label": "test"},
                   "detail": "The key %s is not valid for this service."}]}}
                """.formatted(KEY), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("pas").resolve("pas-90-key-returned.json"), """
                {"id": "pas-90-key-returned", "number": 90, "label": "An issue that quotes the key", "basis": "Synthetic. For a test of this tool only.",
                 "httpStatus": 401,
                 "response": {"resourceType": "OperationOutcome", "issue": [{"severity": "error", "code": "security",
                   "diagnostics": "The key %s was not accepted for the claim {{claimId}}."}]}}
                """.formatted(KEY), StandardCharsets.UTF_8);
        AppProperties app = AppProperties.of(Map.of("mock-latency-enabled", "false", "scenario-dir", directory.toString()));
        ScenarioRepository scenarios = new ScenarioRepository(app);
        MockExchanges exchanges = new MockExchanges(app);
        FakeGateway gateway = new FakeGateway().variable("apiKey", KEY)
                .url(EpaOperation.CRD_ORDER_SIGN, "https://onyx.example.invalid/cds-services/order-sign?key={{apiKey}}")
                .url(EpaOperation.PAS_SUBMIT, "https://onyx.example.invalid/fhir/Claim/$submit?key={{apiKey}}");

        VendorExchange crd = new MockOnyxCrdClient(scenarios, exchanges).hook(gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)),
                ResolvedText.plain("{\"hook\":\"order-sign\"}"), MockSelection.of("crd-90-key-returned", null, null));
        assertThat(crd.wireResponse().body()).contains("The key [secret apiKey] is not valid for this service.").doesNotContain(KEY);
        assertThat(crd.wireResponse().maskedPlaces()).isEqualTo(1);
        assertThat(crd.json().at("/cards/0/detail").asString()).contains(KEY);

        VendorExchange pas = new MockOnyxPasClient(scenarios, exchanges).submit(gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)),
                ResolvedText.plain("{\"resourceType\":\"Bundle\"}"), MockSelection.of("pas-90-key-returned", null, null));
        // The PAS mock wrote the claim into the answer, so this is a response it rebuilt, and it is masked all the same.
        assertThat(pas.wireResponse().body()).contains("The key [secret apiKey] was not accepted for the claim unidentified-claim.").doesNotContain(KEY);
        assertThat(pas.wireResponse().maskedPlaces()).isEqualTo(1);
        assertThat(pas.wireResponse().status()).isEqualTo(401);
    }

    @Test
    void onLiveAnAccessTokenTheToolObtainedItselfIsMaskedWhenTheServiceQuotesIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FakeGateway gateway = new FakeGateway().url(CREATE, URL);
        OnyxProperties onyx = OnyxProperties.of(Map.of("auth.enabled", "true", "auth.token-url", "https://auth.example.invalid/oauth/token",
                "auth.client-id", "placeholder-client", "auth.client-secret", "placeholder-client-secret"));
        server.expect(requestTo("https://auth.example.invalid/oauth/token"))
                .andRespond(withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON).body("{\"access_token\":\"" + ACCESS + "\",\"expires_in\":3600}"));
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer " + ACCESS))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"login\",\"diagnostics\":\"The token "
                                + ACCESS + " has expired.\"}]}")
                        .header("WWW-Authenticate", "Bearer error=\"invalid_token\", token=\"" + ACCESS + "\""));

        VendorExchange exchange = OnyxAccessTestFactory.create(new ExchangeExecutor(), onyx, gateway, builder.build(), Clock.systemUTC())
                .send(gateway.prepare(CallIntent.of(CREATE)), ResolvedText.plain("{}"), FHIR);

        server.verify();
        WireResponse shown = exchange.wireResponse();
        assertThat(shown.status()).isEqualTo(401);
        assertThat(shown.body()).contains("The token [secret access token] has expired.").doesNotContain(ACCESS);
        assertThat(shown.headers()).contains(new WireHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", token=\"[secret access token]\""));
        assertThat(shown.maskedPlaces()).isEqualTo(2);
        assertThat(exchange.request().headers()).contains(new WireHeader("Authorization", "Bearer [redacted]"));
    }

    // ---- the real gateway and the real resolver ----

    @Test
    void withTheRealGatewayNothingTheRequestHidesIsShownInAResponseThatSendsAllOfItBack(@TempDir Path directory) {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("variables.ymlPassword", "hunter2-from-yml").build();
        fixture.operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "hunter2-from-os");
        fixture.activeEnvironmentOf("Secrets", EnvironmentKind.SANDBOX, "baseUrl", "https://x.example.invalid/fhir", "apiKey", "hunter2-by-name",
                "clientSecret", "{{env:PAVE_ONYX_CLIENT_SECRET}}", "greeting", "Hello {{apiKey}}", "callbackToken", "hunter2-callback",
                "authHeader", "Bearer {{callbackToken}}");
        fixture.collectionOf("Secret users", items(item("subscription-create Happy Path", "POST",
                "{{baseUrl}}/Subscription?k={{apiKey}}&c={{collectionTenant}}&y={{ymlPassword}}&t={{typedPassword}}")
                .header("X-Greeting", "{{greeting}}").header("Authorization", "{{authHeader}}").header("X-Plain", "{{clientSecret}} and more")
                // Typed straight into the collection, under a header that is a credential by its name.
                .header("X-Vendor-Key", "hunter2-literal")),
                "[{\"key\": \"collectionTenant\", \"value\": \"hunter2-by-type\", \"type\": \"secret\"}]");
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(CREATE).withRunValues(Map.of("typedPassword", "hunter2-typed")));
        ResolvedText body = call.resolve("{\"channel\":{\"header\":[\"Authorization: Bearer {{callbackToken}}\"]},\"note\":\"{{greeting}}\"}");

        WireRequest request = Exchanges.wireRequest(call, body, FHIR.withBearerToken("hunter2-unused-token"), true, null);

        // The service sends all of the request back: its url, every header and its body.
        StringBuilder all = new StringBuilder(call.url().value());
        call.headers().forEach(sent -> all.append('\n').append(sent.name()).append(": ").append(sent.value()));
        all.append('\n').append(body.value());
        assertThat(all.toString()).contains("hunter2-by-name", "hunter2-from-os", "hunter2-callback", "hunter2-by-type", "hunter2-from-yml",
                "hunter2-typed", "hunter2-literal");
        VendorExchange exchange = Exchanges.fromResponse(request, 400, List.of(new WireHeader("X-Received-Url", call.url().value())),
                all.toString(), 5L, true);

        WireView wire = exchange.wire(null);
        assertThat(JsonSafe.mapper().writeValueAsString(wire)).doesNotContain("hunter2");
        assertThat(exchange.bodyFields()).extracting(AdditionalField::value).noneMatch(value -> value.contains("hunter2"));
        // Each under the name of the variable it came from, as the request shows it.
        assertThat(wire.response().body()).isEqualTo("https://x.example.invalid/fhir/Subscription?k=[secret apiKey]&c=[secret collectionTenant]"
                + "&y=[secret ymlPassword]&t=[secret typedPassword]\n"
                + "X-Greeting: Hello [secret apiKey]\n"
                + "Authorization: Bearer [secret callbackToken]\n"
                + "X-Plain: [secret clientSecret] and more\n"
                + "X-Vendor-Key: [secret X-Vendor-Key header]\n"
                + "{\"channel\":{\"header\":[\"Authorization: Bearer [secret callbackToken]\"]},\"note\":\"Hello [secret apiKey]\"}");
        assertThat(wire.response().headers()).containsExactly(new WireHeader("X-Received-Url", "https://x.example.invalid/fhir/Subscription"
                + "?k=[secret apiKey]&c=[secret collectionTenant]&y=[secret ymlPassword]&t=[secret typedPassword]"));
        // Ten in the body, four in the header.
        assertThat(wire.response().maskedPlaces()).isEqualTo(14);
        // What the request shows and what the response shows hide the same things.
        assertThat(wire.request().url()).isEqualTo(wire.response().headers().get(0).value());
    }
}
