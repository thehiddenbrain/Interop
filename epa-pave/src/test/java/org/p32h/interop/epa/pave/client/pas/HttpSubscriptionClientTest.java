package org.p32h.interop.epa.pave.client.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The live subscription client at the HTTP layer. Nothing here touches a network. */
class HttpSubscriptionClientTest {

    private static final String BASE = "https://onyx.example.invalid/fhir/Subscription";
    private static final String ONE = BASE + "/subscription-placeholder-0001";
    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String FHIR_JSON = "application/fhir+json";
    private static final MediaType FHIR = MediaType.parseMediaType(FHIR_JSON);
    private static final String BODY = "{\"resourceType\":\"Subscription\",\"status\":\"requested\"}";
    private static final String SUBSCRIPTION = "{\"resourceType\":\"Subscription\",\"id\":\"subscription-placeholder-0001\",\"status\":\"active\"}";

    private final FakeGateway gateway = new FakeGateway().environment("Onyx Sandbox", EnvironmentKind.SANDBOX)
            .variable("baseUrl", "https://onyx.example.invalid/fhir")
            .url(EpaOperation.SUBSCRIPTION_CREATE, "{{baseUrl}}/Subscription")
            .url(EpaOperation.SUBSCRIPTION_GET, "{{baseUrl}}/Subscription/{id}")
            .url(EpaOperation.SUBSCRIPTION_UPDATE, "{{baseUrl}}/Subscription/{id}")
            .url(EpaOperation.SUBSCRIPTION_DELETE, "{{baseUrl}}/Subscription/{id}");
    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private HttpSubscriptionClient client(Map<String, String> auth) {
        Map<String, String> properties = new HashMap<>();
        auth.forEach((key, value) -> properties.put("auth." + key, value));
        return new HttpSubscriptionClient(OnyxAccessTestFactory.create(new ExchangeExecutor(), OnyxProperties.of(properties), gateway, restClient,
                Clock.systemUTC()));
    }

    private PreparedCall call(EpaOperation operation) {
        CallIntent intent = CallIntent.of(operation);
        return gateway.prepare(operation == EpaOperation.SUBSCRIPTION_CREATE ? intent : intent.withUrlValue("id", "subscription-placeholder-0001"));
    }

    @Test
    void aCreateIsPostedAsFhirJsonAndTheIdIsReadFromTheBodyOrFromTheLocationHeader() {
        server.expect(requestTo(BASE)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", FHIR_JSON)).andExpect(header("Content-Type", FHIR_JSON))
                .andExpect(headerDoesNotExist("Authorization")).andExpect(content().string(BODY))
                .andRespond(withStatus(HttpStatus.CREATED).body(SUBSCRIPTION).contentType(FHIR));
        VendorExchange withBody = client(Map.of()).create(call(EpaOperation.SUBSCRIPTION_CREATE), ResolvedText.plain(BODY), null);
        server.verify();
        assertThat(withBody.ok()).isTrue();
        assertThat(withBody.httpStatus()).isEqualTo(201);
        assertThat(withBody.request().sent()).isTrue();
        assertThat(withBody.request().method()).isEqualTo("POST");
        assertThat(withBody.request().url()).isEqualTo(BASE);
        assertThat(withBody.json().get("id").asString()).isEqualTo("subscription-placeholder-0001");
        assertThat(withBody.scenarioId()).isNull();

        // A service that answers a create with a status and a Location header only has still answered.
        server.reset();
        server.expect(requestTo(BASE)).andRespond(withStatus(HttpStatus.CREATED).location(URI.create(BASE + "/subscription-placeholder-0002/_history/1")));
        VendorExchange noBody = client(Map.of()).create(call(EpaOperation.SUBSCRIPTION_CREATE), ResolvedText.plain(BODY), null);
        assertThat(noBody.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(noBody.responseBody()).isNull();
        assertThat(OnyxPasConventions.createdSubscriptionId(noBody)).isEqualTo("subscription-placeholder-0002");
    }

    @Test
    void aReadIsAGetOfTheOneSubscriptionWithNoBodyAndNoContentType() {
        server.expect(requestTo(ONE)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", FHIR_JSON)).andExpect(headerDoesNotExist("Content-Type")).andExpect(content().string(""))
                .andRespond(withSuccess(SUBSCRIPTION, FHIR));
        VendorExchange exchange = client(Map.of()).get(call(EpaOperation.SUBSCRIPTION_GET), MockSelection.of("pas-19-subscription-not-found", null, null));
        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.request().url()).isEqualTo(ONE);
        assertThat(exchange.request().urlTemplate()).isEqualTo("{{baseUrl}}/Subscription/{id}");
        assertThat(exchange.request().body()).isNull();
        // The scenario named in the selection is ignored: the answer is the service's.
        assertThat(exchange.scenarioId()).isNull();
        assertThat(exchange.json().get("status").asString()).isEqualTo("active");
    }

    @Test
    void aListIsAGetOfTheCollectionAndAnAnswerWithNothingInItIsNotAnAnswer() {
        FakeGateway listing = new FakeGateway().variable("baseUrl", "https://onyx.example.invalid/fhir")
                .url(EpaOperation.SUBSCRIPTION_GET, "{{baseUrl}}/Subscription");
        HttpSubscriptionClient client = new HttpSubscriptionClient(OnyxAccessTestFactory.create(new ExchangeExecutor(), OnyxProperties.defaults(),
                listing, restClient, Clock.systemUTC()));
        server.expect(requestTo(BASE)).andExpect(method(HttpMethod.GET)).andExpect(header("Accept", FHIR_JSON))
                .andRespond(withSuccess("{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0}", FHIR));
        VendorExchange list = client.get(listing.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET)), null);
        server.verify();
        assertThat(list.ok()).isTrue();
        assertThat(list.json().get("type").asString()).isEqualTo("searchset");

        server.reset();
        server.expect(requestTo(BASE)).andRespond(withSuccess());
        assertThat(client.get(listing.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET)), null).failureType())
                .isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
    }

    @Test
    void anUpdateIsAPutOfTheOneSubscriptionAsFhirJson() {
        server.expect(requestTo(ONE)).andExpect(method(HttpMethod.PUT))
                .andExpect(header("Accept", FHIR_JSON)).andExpect(header("Content-Type", FHIR_JSON)).andExpect(content().string(BODY))
                .andRespond(withSuccess(SUBSCRIPTION, FHIR));
        VendorExchange exchange = client(Map.of()).update(call(EpaOperation.SUBSCRIPTION_UPDATE), ResolvedText.plain(BODY), null);
        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.request().method()).isEqualTo("PUT");
        assertThat(exchange.request().body()).isEqualTo(BODY);

        // An update may answer with no content, which is not a malformed response.
        server.reset();
        server.expect(requestTo(ONE)).andRespond(withStatus(HttpStatus.NO_CONTENT));
        assertThat(client(Map.of()).update(call(EpaOperation.SUBSCRIPTION_UPDATE), ResolvedText.plain(BODY), null).failureType())
                .isEqualTo(OnyxFailureType.NONE);
    }

    @Test
    void aDeleteIsSentWithNoBodyAndNoContentIsItsNormalAnswer() {
        server.expect(requestTo(ONE)).andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Accept", FHIR_JSON)).andExpect(headerDoesNotExist("Content-Type")).andExpect(content().string(""))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        VendorExchange exchange = client(Map.of()).delete(call(EpaOperation.SUBSCRIPTION_DELETE), null);
        server.verify();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(exchange.httpStatus()).isEqualTo(204);
        assertThat(exchange.responseBody()).isNull();
        assertThat(exchange.request().method()).isEqualTo("DELETE");
        assertThat(exchange.request().body()).isNull();
    }

    @Test
    void theAccessTokenIsSentWithAWriteAndIsNeverShown() {
        gateway.variable("tokenUrl", TOKEN_URL).variable("clientId", "placeholder-client").variable("clientSecret", "placeholder-secret");
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"access_token\":\"placeholder-access-token\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(ONE)).andExpect(method(HttpMethod.DELETE)).andExpect(header("Authorization", "Bearer placeholder-access-token"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        VendorExchange exchange = client(Map.of("enabled", "true", "token-url", "{{tokenUrl}}", "client-id", "{{clientId}}",
                "client-secret", "{{clientSecret}}")).delete(call(EpaOperation.SUBSCRIPTION_DELETE), null);
        server.verify();
        assertThat(exchange.request().headers()).contains(new WireHeader("Authorization", "Bearer [redacted]"));
        assertThat(exchange.request().headers().toString()).doesNotContain("placeholder-access-token");
    }

    @Test
    void anUnknownSubscriptionIsToldApartFromAWrongUrlOnLiveAsOnMock() {
        String notFound = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\"}]}";
        server.expect(requestTo(ONE)).andRespond(withStatus(HttpStatus.NOT_FOUND).body(notFound).contentType(FHIR));
        VendorExchange unknownId = client(Map.of()).get(call(EpaOperation.SUBSCRIPTION_GET), null);
        assertThat(unknownId.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(OnyxPasConventions.subscriptionNotFound(unknownId)).isTrue();

        server.reset();
        server.expect(requestTo(ONE)).andRespond(withStatus(HttpStatus.GONE));
        assertThat(OnyxPasConventions.subscriptionNotFound(client(Map.of()).delete(call(EpaOperation.SUBSCRIPTION_DELETE), null))).isTrue();

        // A bare 404 says nothing about the id: a wrong url answers the same way.
        server.reset();
        server.expect(requestTo(ONE)).andRespond(withStatus(HttpStatus.NOT_FOUND).body("<html>Not Found</html>"));
        VendorExchange wrongUrl = client(Map.of()).get(call(EpaOperation.SUBSCRIPTION_GET), null);
        assertThat(wrongUrl.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(OnyxPasConventions.subscriptionNotFound(wrongUrl)).isFalse();
    }

    @Test
    void everyKindOfFailureComesBackAsAnExchangeAndNeverAsAnException() {
        HttpSubscriptionClient client = client(Map.of());
        String invalid = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\"}]}";
        server.expect(requestTo(BASE)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body(invalid).contentType(FHIR));
        VendorExchange rejected = client.create(call(EpaOperation.SUBSCRIPTION_CREATE), ResolvedText.plain(BODY), null);
        assertThat(rejected.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(rejected.httpStatus()).isEqualTo(400);
        assertThat(rejected.json().get("issue")).hasSize(1);

        server.reset();
        server.expect(requestTo(ONE)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("<html>Unavailable</html>"));
        VendorExchange serverError = client.update(call(EpaOperation.SUBSCRIPTION_UPDATE), ResolvedText.plain(BODY), null);
        assertThat(serverError.failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);
        assertThat(serverError.responseBody()).isEqualTo("<html>Unavailable</html>");

        server.reset();
        server.expect(requestTo(BASE)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        VendorExchange timeout = client.create(call(EpaOperation.SUBSCRIPTION_CREATE), ResolvedText.plain(BODY), null);
        assertThat(timeout.failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
        assertThat(timeout.httpStatus()).isNull();

        server.reset();
        server.expect(requestTo(ONE)).andRespond(withException(new ConnectException("Connection refused")));
        assertThat(client.delete(call(EpaOperation.SUBSCRIPTION_DELETE), null).failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);

        server.reset();
        server.expect(requestTo(ONE)).andRespond(withSuccess("{\"resourceType\": \"Subscription\", ", FHIR));
        VendorExchange malformed = client.get(call(EpaOperation.SUBSCRIPTION_GET), null);
        assertThat(malformed.failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
        assertThat(malformed.responseBody()).isEqualTo("{\"resourceType\": \"Subscription\", ");

        // A read that answers with nothing is not an answer. A write that does is, and is told apart from it.
        server.reset();
        server.expect(requestTo(ONE)).andRespond(withSuccess());
        assertThat(client.get(call(EpaOperation.SUBSCRIPTION_GET), null).failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
        server.reset();
        server.expect(requestTo(BASE)).andRespond(withSuccess());
        assertThat(client.create(call(EpaOperation.SUBSCRIPTION_CREATE), ResolvedText.plain(BODY), null).failureType())
                .isEqualTo(OnyxFailureType.NONE);
    }

    @Test
    void aWritePreparedBeforeTheEnvironmentChangedIsRefused() {
        HttpSubscriptionClient client = client(Map.of());
        PreparedCall stale = call(EpaOperation.SUBSCRIPTION_DELETE);
        gateway.changeConfiguration();
        assertThatThrownBy(() -> client.delete(stale, null)).isInstanceOf(ConfigurationChangedException.class);
        server.verify();
    }
}
