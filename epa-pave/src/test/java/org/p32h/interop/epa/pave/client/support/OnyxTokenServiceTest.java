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
                .token().failure()).contains("client id or the client secret is not set");
        server.verify();
    }

    @Test
    void aRefusalFromTheTokenEndpointIsReportedByStatusAndNothingSecretIsQuoted() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid_client placeholder-secret\"}"));
        OnyxTokenService.TokenResult result = service(enabled()).token();
        assertThat(result.token()).isNull();
        assertThat(result.failure()).isEqualTo("No access token: the token endpoint answered HTTP 401. The request was not sent.");
        assertThat(result.failure()).doesNotContain("placeholder-secret").doesNotContain("placeholder-client").doesNotContain("auth.example.invalid");
    }

    @Test
    void anUnreachableOrUnreadableTokenEndpointIsAFailureNotAnException() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withException(new ConnectException("Connection refused to auth.example.invalid")));
        assertThat(service(enabled()).token().failure())
                .isEqualTo("No access token: the token endpoint could not be reached (unavailable). The request was not sent.");
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("<html>login</html>", MediaType.TEXT_HTML));
        assertThat(service(enabled()).token().failure()).contains("a body that is not JSON");
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));
        assertThat(service(enabled()).token().failure()).contains("answered without one");
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
        assertThat(new OnyxTokenService.TokenResult("placeholder-access-token", null).toString()).doesNotContain("placeholder-access-token");
    }
}
