package org.p32h.interop.epa.pave.client.crd;

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
import org.p32h.interop.epa.pave.model.VendorSignal;
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

/** The live CRD client at the HTTP layer. Nothing here touches a network. */
class HttpOnyxCrdClientTest {

    private static final String URL = "https://onyx.example.invalid/cds-services/placeholder-service";
    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String BODY = "{\"hook\":\"order-sign\",\"hookInstance\":\"h-1\"}";

    private final FakeGateway gateway = new FakeGateway().environment("Onyx Sandbox", EnvironmentKind.SANDBOX)
            .variable("baseUrl", "https://onyx.example.invalid").variable("crdOrderSignServiceId", "placeholder-service")
            .url(EpaOperation.CRD_ORDER_SIGN, "{{baseUrl}}/cds-services/{{crdOrderSignServiceId}}")
            .url(EpaOperation.CRD_DISCOVERY, "{{baseUrl}}/cds-services");
    private MockRestServiceServer server;
    private RestClient restClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private HttpOnyxCrdClient client(Map<String, String> auth) {
        Map<String, String> properties = new HashMap<>();
        auth.forEach((key, value) -> properties.put("auth." + key, value));
        return new HttpOnyxCrdClient(OnyxAccessTestFactory.create(new ExchangeExecutor(), OnyxProperties.of(properties), gateway, restClient,
                Clock.systemUTC()));
    }

    private static Map<String, String> authOn() {
        return Map.of("enabled", "true", "token-url", "{{tokenUrl}}", "client-id", "{{clientId}}", "client-secret", "{{clientSecret}}");
    }

    private void withCredentials() {
        gateway.variable("tokenUrl", TOKEN_URL).variable("clientId", "placeholder-client").variable("clientSecret", "placeholder-secret");
    }

    private PreparedCall hookCall() {
        return gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
    }

    @Test
    void theHookIsPostedAsPlainJsonToTheResolvedServiceUrl() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Accept", "application/json")).andExpect(header("Content-Type", "application/json"))
                .andExpect(headerDoesNotExist("Authorization")).andExpect(content().string(BODY))
                .andRespond(withSuccess("{\"cards\":[]}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = client(Map.of()).hook(hookCall(), ResolvedText.plain(BODY), MockSelection.of(null, "S123456789", "1234567893"));

        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.request().sent()).isTrue();
        assertThat(exchange.request().url()).isEqualTo(URL);
        assertThat(exchange.request().urlTemplate()).isEqualTo("{{baseUrl}}/cds-services/{{crdOrderSignServiceId}}");
        // A real answer is never attributed to a mock scenario.
        assertThat(exchange.scenarioId()).isNull();
        assertThat(exchange.signals()).isEmpty();
    }

    @Test
    void aScenarioChoiceIsIgnoredOnLive() {
        // Whatever the selection says, the request is the same and the answer is the service's.
        server.expect(requestTo(URL)).andExpect(content().string(BODY)).andRespond(withSuccess("{\"cards\":[]}", MediaType.APPLICATION_JSON));
        VendorExchange exchange = client(Map.of()).hook(hookCall(), ResolvedText.plain(BODY), MockSelection.of("crd-02-no-auth", "P32H-NO-AUTH", null));
        server.verify();
        assertThat(exchange.scenarioId()).isNull();
        assertThat(exchange.responseBody()).isEqualTo("{\"cards\":[]}");
    }

    @Test
    void theAccessTokenIsFetchedAndSentAsABearerCredentialThatIsNeverShown() {
        withCredentials();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"access_token\":\"placeholder-access-token\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andExpect(header("Authorization", "Bearer placeholder-access-token"))
                .andRespond(withSuccess("{\"cards\":[]}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = client(authOn()).hook(hookCall(), ResolvedText.plain(BODY), null);

        server.verify();
        assertThat(exchange.request().headers()).contains(new WireHeader("Authorization", "Bearer [redacted]"));
        assertThat(exchange.request().headers().toString()).doesNotContain("placeholder-access-token");
    }

    @Test
    void whenNoTokenCanBeObtainedNothingIsSentAndTheExchangeSaysWhy() {
        withCredentials();
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        VendorExchange exchange = client(authOn()).hook(hookCall(), ResolvedText.plain(BODY), null);

        // Only the token endpoint was called. The CRD url was never requested.
        server.verify();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(exchange.request().sent()).isFalse();
        assertThat(exchange.failureDetail()).isEqualTo("No access token: the token endpoint answered HTTP 401. The request was not sent.");
        assertThat(exchange.httpStatus()).isNull();
    }

    @Test
    void theVendorSignalsAreReadFromTheResponseOnLiveAsOnMock() {
        String notFound = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\",\"expression\":[\"Patient.identifier\"]}]}";
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND).body(notFound).contentType(MediaType.APPLICATION_JSON));
        VendorExchange exchange = client(Map.of()).hook(hookCall(), ResolvedText.plain(BODY), null);
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(exchange.httpStatus()).isEqualTo(404);
        assertThat(exchange.signals()).containsExactly(VendorSignal.MEMBER_NOT_FOUND);
    }

    @Test
    void everyKindOfFailureComesBackAsAnExchangeAndNeverAsAnException() {
        HttpOnyxCrdClient client = client(Map.of());
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"resourceType\":\"OperationOutcome\",\"issue\":[]}")
                .contentType(MediaType.APPLICATION_JSON));
        assertThat(client.hook(hookCall(), ResolvedText.plain(BODY), null).failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>Bad Gateway</html>"));
        VendorExchange badGateway = client.hook(hookCall(), ResolvedText.plain(BODY), null);
        assertThat(badGateway.failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);
        assertThat(badGateway.responseBody()).isEqualTo("<html>Bad Gateway</html>");
        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));
        assertThat(client.hook(hookCall(), ResolvedText.plain(BODY), null).failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
        server.reset();
        server.expect(requestTo(URL)).andRespond(withException(new ConnectException("Connection refused")));
        assertThat(client.hook(hookCall(), ResolvedText.plain(BODY), null).failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess("{\"cards\": [", MediaType.APPLICATION_JSON));
        VendorExchange malformed = client.hook(hookCall(), ResolvedText.plain(BODY), null);
        assertThat(malformed.failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
        assertThat(malformed.responseBody()).isEqualTo("{\"cards\": [");
        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess());
        assertThat(client.hook(hookCall(), ResolvedText.plain(BODY), null).failureType()).isEqualTo(OnyxFailureType.MALFORMED_RESPONSE);
    }

    @Test
    void discoveryIsAGetWithNoBodyAndNoContentType() {
        server.expect(requestTo("https://onyx.example.invalid/cds-services")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Accept", "application/json")).andExpect(headerDoesNotExist("Content-Type")).andExpect(content().string(""))
                .andRespond(withSuccess("{\"services\":[{\"hook\":\"order-sign\",\"id\":\"crd-order-sign\"}]}", MediaType.APPLICATION_JSON));
        VendorExchange exchange = client(Map.of()).discover(gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY)));
        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.json().get("services")).hasSize(1);
    }

    @Test
    void aCallPreparedBeforeTheEnvironmentChangedIsRefusedBeforeAnyTokenIsFetched() {
        withCredentials();
        HttpOnyxCrdClient client = client(authOn());
        PreparedCall stale = hookCall();
        gateway.changeConfiguration();
        assertThatThrownBy(() -> client.hook(stale, ResolvedText.plain(BODY), null)).isInstanceOf(ConfigurationChangedException.class);
        // Neither the token endpoint nor the service was called: one environment's token must
        // never be fetched for a request prepared against another.
        server.verify();
    }
}
