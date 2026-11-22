package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OnyxTokenServiceTest {

    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String GRANT = "{\"access_token\":\"placeholder-access-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

    /** A clock a test can move forward. */
    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-03-10T15:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovingClock clock = new MovingClock();
    private final FakeGateway gateway = new FakeGateway().variable("tokenUrl", TOKEN_URL).variable("clientId", "placeholder-client")
            .variable("clientSecret", "placeholder-secret");
    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    private OnyxTokenService service(Map<String, String> auth) {
        Map<String, String> properties = new HashMap<>();
        auth.forEach((key, value) -> properties.put("auth." + key, value));
        return new OnyxTokenService(OnyxProperties.of(properties), gateway, client, clock);
    }

    private static Map<String, String> enabled() {
        return Map.of("enabled", "true", "token-url", "{{tokenUrl}}", "client-id", "{{clientId}}", "client-secret", "{{clientSecret}}",
                "scopes", "system/*.read system/Claim.write");
    }

    @Test
    void withAuthenticationOffThereIsNoTokenAndNoFailure() {
        OnyxTokenService.TokenResult result = service(Map.of()).token();
        assertThat(result.token()).isNull();
        assertThat(result.failure()).isNull();
        server.verify();
    }

    @Test
    void theTokenIsRequestedWithTheClientCredentialsGrant() {
        String basic = Base64.getEncoder().encodeToString("placeholder-client:placeholder-secret".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + basic))
                .andExpect(header("Content-Type", "application/x-www-form-urlencoded"))
                .andExpect(content().string("grant_type=client_credentials&scope=system%2F*.read+system%2FClaim.write"))
                .andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));

        OnyxTokenService.TokenResult result = service(enabled()).token();

        server.verify();
        assertThat(result.token()).isEqualTo("placeholder-access-token");
        assertThat(result.failure()).isNull();
    }

    @Test
    void aTokenIsReusedUntilShortlyBeforeItExpiresAndThenReplaced() {
        server.expect(times(2), requestTo(TOKEN_URL)).andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));
        OnyxTokenService service = service(enabled());

        assertThat(service.token().token()).isEqualTo("placeholder-access-token");
        clock.advance(Duration.ofSeconds(3600 - OnyxTokenService.REFRESH_SKEW_SECONDS - 1));
        assertThat(service.token().token()).isEqualTo("placeholder-access-token");
        // Inside the refresh skew the token is replaced, so one is never sent in its last moments.
        clock.advance(Duration.ofSeconds(2));
        assertThat(service.token().token()).isEqualTo("placeholder-access-token");
        server.verify();
    }

    @Test
    void aTokenWithNoStatedLifetimeIsNotCached() {
        server.expect(times(2), requestTo(TOKEN_URL)).andRespond(withSuccess("{\"access_token\":\"t\"}", MediaType.APPLICATION_JSON));
        OnyxTokenService service = service(enabled());
        assertThat(service.token().token()).isEqualTo("t");
        assertThat(service.token().token()).isEqualTo("t");
        server.verify();
    }

    @Test
    void aTokenDoesNotOutliveTheConfigurationItWasIssuedFor() {
        server.expect(times(2), requestTo(TOKEN_URL)).andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));
        OnyxTokenService service = service(enabled());
        service.token();
        // The tester switches environment. The cached token belongs to the old one.
        gateway.changeConfiguration();
        service.token();
        server.verify();
    }

    @Test
    void aSwitchedOnServiceWithNothingToCallFailsBeforeAnythingIsSent() {
        assertThat(service(Map.of("enabled", "true")).token().failure()).contains("onyx.auth.token-url is not set").contains("not sent");
        assertThat(service(Map.of("enabled", "true", "token-url", "{{undefinedUrl}}", "client-id", "a", "client-secret", "b")).token().failure())
                .contains("placeholder");
        assertThat(service(Map.of("enabled", "true", "token-url", TOKEN_URL, "client-id", "{{clientId}}", "client-secret", "{{undefinedSecret}}"))
                .token().failure()).contains("Authentication is switched on but onyx.auth.client-secret is not set, or a placeholder the active "
                + "environment does not define. Configuration > Advanced > In force now says where it is read from.");
        // The setting that is missing is the one named.
        assertThat(service(Map.of("enabled", "true", "token-url", TOKEN_URL, "client-secret", "b")).token().failure())
                .contains("but onyx.auth.client-id is not set");
        assertThat(service(Map.of("enabled", "true", "token-url", TOKEN_URL)).token().failure())
                .contains("but onyx.auth.client-id and onyx.auth.client-secret are not set");
        server.verify();
    }

    /**
     * Before D-305 the sentence held the status only. The endpoint's {@code error} is now said, so
     * a tester can tell a wrong secret from credentials sent the wrong way; a secret it repeats is
     * masked as the wire view masks one, so still nothing secret is quoted.
     */
    @Test
    void aRefusalFromTheTokenEndpointIsReportedByStatusAndReasonAndNothingSecretIsQuoted() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid_client placeholder-secret\"}"));
        OnyxTokenService.TokenResult result = service(enabled()).token();
        assertThat(result.token()).isNull();
        assertThat(result.failure()).isEqualTo("No access token: the token endpoint answered HTTP 401 (invalid_client [secret client secret]). "
                + "The request was not sent.");
        assertThat(result.failure()).doesNotContain("placeholder-secret").doesNotContain("placeholder-client").doesNotContain("auth.example.invalid");
        server.reset();
        // An access token held from an earlier sign-in is masked as well, should a misconfigured endpoint echo it.
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"placeholder-access-token was already used\"}"));
        OnyxTokenService once = service(enabled());
        assertThat(once.token().token()).isEqualTo("placeholder-access-token");
        clock.advance(Duration.ofHours(2));
        assertThat(once.token().failure()).isEqualTo("No access token: the token endpoint answered HTTP 400 (invalid_grant). It said: "
                + "[secret access token] was already used. The request was not sent.");
    }

    @Test
    void anUnreachableOrUnreadableTokenEndpointIsAFailureNotAnException() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new ConnectException("Connection refused to auth.example.invalid")));
        assertThat(service(enabled()).token().failure())
                .isEqualTo("No access token: the token endpoint could not be reached (unavailable). The request was not sent.");
        server.reset();
        // An endpoint that took the connection and then dropped it was reached, and is not said to be unreachable.
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new java.net.SocketException("Connection reset by auth.example.invalid")));
        OnyxTokenService.TokenResult dropped = service(enabled()).token();
        assertThat(dropped.failure())
                .isEqualTo("No access token: the token endpoint closed the connection without answering. The request was not sent.")
                .doesNotContain("auth.example.invalid");
        assertThat(dropped.cause()).isEqualTo(OnyxTokenService.TokenFailure.ENDPOINT_FAULT);
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("<html>login</html>", MediaType.TEXT_HTML));
        assertThat(service(enabled()).token().failure()).contains("a body that is not JSON");
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
        assertThat(service(enabled()).token().failure()).contains("answered without one");
    }

    /**
     * Each sentence is true of its case, with the HTTP client the application uses, on loopback: a
     * connection that was taken and closed without an answer is said to be closed; a port nothing
     * listens on, an unknown host and a failure this tool cannot place are said to be unreachable;
     * a token url that is no absolute address is the setting, and nothing is sent.
     */
    @Test
    void theTokenFailureSaysAClosedConnectionOnlyWhereOneWasOpenAndEnded() throws Exception {
        RestClient real = RestClients.withTimeouts(RestClient.builder(), 2000, 2000);
        try (java.net.ServerSocket dropping = new java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())) {
            Thread taker = new Thread(() -> {
                try (java.net.Socket taken = dropping.accept()) {
                    taken.getInputStream().read(new byte[8192]);
                } catch (java.io.IOException ignored) {
                    // The test reads the client's side; a failure here shows there as a failed assertion.
                }
            });
            taker.start();
            gateway.variable("tokenUrl", "http://127.0.0.1:" + dropping.getLocalPort() + "/oauth2/token");
            OnyxTokenService.TokenResult dropped = new OnyxTokenService(OnyxProperties.of(prefixed(enabled())), gateway, real, clock).token();
            taker.join(5000);
            assertThat(dropped.failure())
                    .isEqualTo("No access token: the token endpoint closed the connection without answering. The request was not sent.");
            assertThat(dropped.cause()).isEqualTo(OnyxTokenService.TokenFailure.ENDPOINT_FAULT);
        }
        int free;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            free = probe.getLocalPort();
        }
        gateway.variable("tokenUrl", "http://127.0.0.1:" + free + "/oauth2/token");
        assertThat(new OnyxTokenService(OnyxProperties.of(prefixed(enabled())), gateway, real, clock).token().failure())
                .isEqualTo("No access token: the token endpoint could not be reached (unavailable). The request was not sent.");

        gateway.variable("tokenUrl", TOKEN_URL);
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new java.net.UnknownHostException("auth.example.invalid")));
        assertThat(service(enabled()).token().failure())
                .isEqualTo("No access token: the token endpoint could not be reached (unavailable). The request was not sent.");
        // A failure of a type this tool does not place: the fallback claims no closed connection.
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new java.io.IOException("placeholder")));
        assertThat(service(enabled()).token().failure())
                .isEqualTo("No access token: the token endpoint could not be reached (unavailable). The request was not sent.");
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new java.io.EOFException()));
        assertThat(service(enabled()).token().failure()).contains("closed the connection without answering");

        // No absolute address: the setting is named, never its value, and nothing is sent.
        server.reset();
        for (String notAbsolute : new String[] {"auth-placeholder/oauth2/token", "/oauth2/token", "ftp://auth.example.invalid/t", "http:// x",
                "http://127.0.0.1:99999/oauth2/token", "https://auth.example.invalid:0/t"}) {
            gateway.variable("tokenUrl", notAbsolute);
            OnyxTokenService.TokenResult result = service(enabled()).token();
            // One sentence that is true of each: an address with a port no connection can use is absolute, so it is not called otherwise.
            assertThat(result.failure()).as(notAbsolute).isEqualTo("Authentication is switched on but onyx.auth.token-url, as configured, is "
                    + "not an address this tool can use: it must be an absolute http or https address with a host, and a port from 1 to "
                    + "65535 if it names one. The request was not sent.").doesNotContain(notAbsolute);
            assertThat(result.cause()).isEqualTo(OnyxTokenService.TokenFailure.SETTING_MISSING);
        }
        server.verify();
    }

    private static Map<String, String> prefixed(Map<String, String> auth) {
        Map<String, String> properties = new HashMap<>();
        auth.forEach((key, value) -> properties.put("auth." + key, value));
        return properties;
    }

    @Test
    void aFailedTokenIsNotCached() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        OnyxTokenService service = service(enabled());
        assertThat(service.token().failure()).contains("HTTP 503");
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));
        assertThat(service.token().token()).isEqualTo("placeholder-access-token");
    }

    @Test
    void theTokenResultNeverPrintsTheToken() {
        assertThat(new OnyxTokenService.TokenResult("placeholder-access-token", null, null).toString()).doesNotContain("placeholder-access-token");
    }

    @Test
    void eachFailureSaysWhetherItIsTheTestersSettingsOrTheTokenEndpoint() {
        // A setting that is not given, or a placeholder the environment does not define: the configuration.
        assertThat(service(Map.of("enabled", "true")).token().cause()).isEqualTo(OnyxTokenService.TokenFailure.SETTING_MISSING);
        assertThat(service(Map.of("enabled", "true", "token-url", TOKEN_URL, "client-id", "{{clientId}}", "client-secret",
                "{{undefinedSecret}}")).token().cause()).isEqualTo(OnyxTokenService.TokenFailure.SETTING_MISSING);

        // A 4xx: the endpoint refused this client, which the tester's settings decide.
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"invalid_client\"}"));
        OnyxTokenService.TokenResult refused = service(enabled()).token();
        assertThat(refused.cause()).isEqualTo(OnyxTokenService.TokenFailure.CLIENT_REFUSED);
        assertThat(refused.failure()).contains("answered HTTP 400");

        // A 5xx, an endpoint that cannot be reached, and a success with no usable token: the environment.
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        OnyxTokenService.TokenResult failed = service(enabled()).token();
        assertThat(failed.cause()).isEqualTo(OnyxTokenService.TokenFailure.ENDPOINT_FAULT);
        assertThat(failed.failure()).contains("answered HTTP 503");
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new ConnectException("Connection refused")));
        OnyxTokenService.TokenResult unreachable = service(enabled()).token();
        assertThat(unreachable.cause()).isEqualTo(OnyxTokenService.TokenFailure.ENDPOINT_FAULT);
        assertThat(unreachable.failure()).contains("could not be reached");
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
        assertThat(service(enabled()).token().cause()).isEqualTo(OnyxTokenService.TokenFailure.ENDPOINT_FAULT);

        // No failure, no cause.
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));
        assertThat(service(enabled()).token().cause()).isNull();
        assertThat(service(Map.of()).token().cause()).isNull();
    }
}
