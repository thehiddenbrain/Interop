package org.p32h.interop.epa.pave.client.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.client.support.OnyxHosts;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Sending a request raw on the live profile, at the HTTP layer. Nothing here touches a network. */
class HttpRawRequestClientTest {

    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String ONYX_URL = "https://onyx.example.invalid/Claim/some-operation";
    private static final String OTHER_URL = "https://another-service.example.invalid/api/thing";
    private static final EnvironmentRef SANDBOX = new EnvironmentRef("Onyx Sandbox", EnvironmentKind.SANDBOX);
    private static final Map<String, String> AUTH_ON = Map.of("auth.enabled", "true", "auth.token-url", "{{tokenUrl}}",
            "auth.client-id", "{{clientId}}", "auth.client-secret", "{{clientSecret}}");

    private final FakeGateway gateway = new FakeGateway().environment("Onyx Sandbox", EnvironmentKind.SANDBOX)
            .variable("baseUrl", "https://onyx.example.invalid").variable("tokenUrl", TOKEN_URL)
            .variable("clientId", "placeholder-client").variable("clientSecret", "placeholder-secret")
            .url(EpaOperation.PAS_SUBMIT, "{{baseUrl}}/Claim/$submit").url(EpaOperation.CRD_DISCOVERY, "{{baseUrl}}/cds-services");
    private MockRestServiceServer server;
    private HttpRawRequestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new HttpRawRequestClient(new ExchangeExecutor(),
                OnyxAccessTestFactory.tokens(OnyxProperties.of(AUTH_ON), gateway, restClient, Clock.systemUTC()), gateway, restClient);
    }

    private static PreparedCall call(String method, String url, List<ResolvedHeader> headers) {
        return TestPreparedCalls.to(EpaOperation.UNMAPPED, method, url, headers, SANDBOX);
    }

    private void expectToken() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"access_token\":\"placeholder-onyx-token\",\"expires_in\":3600}",
                MediaType.APPLICATION_JSON));
    }

    @Test
    void theOnyxTokenGoesWithARequestToAnOnyxHost() {
        expectToken();
        server.expect(requestTo(ONYX_URL)).andExpect(method(HttpMethod.POST)).andExpect(header("Authorization", "Bearer placeholder-onyx-token"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = client.send(call("POST", ONYX_URL, List.of()), ResolvedText.plain("{}"));

        server.verify();
        assertThat(exchange.ok()).isTrue();
        // Shown as sent, never in clear.
        assertThat(exchange.request().headers()).contains(new WireHeader("Authorization", "Bearer [redacted]"));
        assertThat(exchange.request().headers().toString()).doesNotContain("placeholder-onyx-token");
    }

    @Test
    void theOnyxTokenIsNeverSentToAHostThatIsNotAnOnyxTarget() {
        // A vendor collection can hold a request to another service. The Onyx token is a credential
        // for Onyx: no token request is made and no Authorization header goes out.
        server.expect(requestTo(OTHER_URL)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = client.send(call("POST", OTHER_URL, List.of()), ResolvedText.plain("{}"));

        server.verify();
        assertThat(exchange.ok()).isTrue();
        assertThat(exchange.request().headers()).extracting(WireHeader::name).doesNotContain("Authorization");
        assertThat(HttpRawRequestClient.tokenApplies(call("POST", OTHER_URL, List.of()), gateway)).isFalse();
        // A host that only looks like the Onyx one is not it.
        assertThat(HttpRawRequestClient.tokenApplies(call("GET", "https://onyx.example.invalid.attacker.example/x", List.of()), gateway)).isFalse();
        assertThat(HttpRawRequestClient.tokenApplies(call("GET", "https://ONYX.example.invalid/x", List.of()), gateway)).isTrue();
    }

    @Test
    void aRequestThatCarriesItsOwnAuthorizationIsSentWithThatAndNoTokenIsFetched() {
        server.expect(requestTo(ONYX_URL)).andExpect(header("Authorization", "Basic cGxhY2Vob2xkZXI="))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = client.send(call("POST", ONYX_URL,
                List.of(new ResolvedHeader("Authorization", "Basic [redacted]", "Basic cGxhY2Vob2xkZXI="))), ResolvedText.plain("{}"));

        server.verify();
        assertThat(exchange.request().headers()).contains(new WireHeader("Authorization", "Basic [redacted]"));
    }

    @Test
    void whenNoTokenCanBeObtainedNothingIsSentAndTheResultSaysWhy() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid_client\"}")
                .contentType(MediaType.APPLICATION_JSON));

        VendorExchange exchange = client.send(call("POST", ONYX_URL, List.of()), ResolvedText.plain("{}"));

        server.verify();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.NOT_SIGNED_IN);
        assertThat(exchange.request().sent()).isFalse();
        assertThat(exchange.httpStatus()).isNull();
    }

    @Test
    void theRequestsOwnMethodAndHeadersAreSentAndASuccessWithNoBodyIsNotAFault() {
        server.expect(requestTo(OTHER_URL)).andExpect(method(HttpMethod.DELETE)).andExpect(header("X-Trace", "t-1"))
                .andExpect(header("Accept", "text/plain")).andRespond(withStatus(HttpStatus.NO_CONTENT));

        VendorExchange exchange = client.send(call("DELETE", OTHER_URL,
                List.of(new ResolvedHeader("X-Trace", "t-1", "t-1"), new ResolvedHeader("Accept", "text/plain", "text/plain"))), null);

        server.verify();
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(exchange.httpStatus()).isEqualTo(204);
    }

    @Test
    void theTokenGoesOnlyToTheSchemeHostAndPortTheOnyxOperationsResolveToRightNow() {
        // The same scheme, host and port, written with the default port or another case, is the Onyx address.
        assertThat(OnyxHosts.admits(gateway, "https://onyx.example.invalid/anything")).isTrue();
        assertThat(OnyxHosts.admits(gateway, "https://onyx.example.invalid:443/anything")).isTrue();
        assertThat(HttpRawRequestClient.tokenApplies(call("GET", "https://onyx.example.invalid:443/x", List.of()), gateway)).isTrue();
        // Another port of the same host can be another service; plain http would carry the token in clear.
        assertThat(OnyxHosts.admits(gateway, "https://onyx.example.invalid:8443/anything")).isFalse();
        assertThat(OnyxHosts.admits(gateway, "http://onyx.example.invalid/anything")).isFalse();
        assertThat(HttpRawRequestClient.tokenApplies(call("GET", "https://onyx.example.invalid:8443/x", List.of()), gateway)).isFalse();
        assertThat(HttpRawRequestClient.tokenApplies(call("GET", "http://onyx.example.invalid/x", List.of()), gateway)).isFalse();
        // Nothing configured, nothing admitted; an address that is not one is never admitted.
        assertThat(OnyxHosts.admits(FakeGateway.unconfigured(), ONYX_URL)).isFalse();
        assertThat(OnyxHosts.admits(gateway, "not a url")).isFalse();
        assertThat(OnyxHosts.admits(gateway, null)).isFalse();
    }

    @Test
    void noTokenIsFetchedOrSentForAnotherPortOfTheOnyxHost() {
        String otherPort = "https://onyx.example.invalid:8443/Claim/some-operation";
        server.expect(requestTo(otherPort)).andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));

        VendorExchange exchange = client.send(call("POST", otherPort, List.of()), ResolvedText.plain("{}"));

        server.verify();
        assertThat(exchange.request().headers()).extracting(WireHeader::name).doesNotContain("Authorization");
    }
}
