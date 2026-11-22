package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Sign-in as the environment states it (R-03): each method, the cache, and the one retry after a 401. Nothing touches a network. */
class TokenSettingsTest {

    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String OTHER_TOKEN_URL = "https://auth2.example.invalid/oauth/token";
    private static final String HOOK = "https://vendor.example.invalid/cds-services/order-sign";
    private static final String BODY = "{\"hook\":\"order-sign\"}";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC);

    private final FakeGateway gateway = new FakeGateway().environment("Placeholder Sandbox", EnvironmentKind.SANDBOX)
            .variable("tokenUrl", TOKEN_URL).variable("clientId", "placeholder-client").variable("clientSecret", "placeholder-client-secret")
            .url(EpaOperation.CRD_ORDER_SIGN, HOOK);
    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    private OnyxTokenService tokens(Map<String, String> onyx) {
        return OnyxAccessTestFactory.tokens(OnyxProperties.of(onyx), gateway, client, CLOCK);
    }

    private static String grant(String token) {
        return "{\"access_token\":\"" + token + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";
    }

    private static SignInSettings clientCredentials(SignInSettings.ClientAuthentication how) {
        return SignInSettings.clientCredentials("{{tokenUrl}}", "{{clientId}}", "{{clientSecret}}", "system/*.read", how);
    }

    private PreparedCall hook() {
        return gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
    }

    private static ExchangeOptions json() {
        return ExchangeOptions.json("application/json");
    }

    @Test
    void noneSendsNoTokenAndAsksNoOne() {
        OnyxTokenService.TokenResult result = tokens(Map.of("auth.enabled", "true", "auth.token-url", TOKEN_URL)).token(SignInSettings.none());
        server.verify();
        assertThat(result.token()).isNull();
        assertThat(result.failure()).isNull();
    }

    @Test
    void aStatedBearerTokenIsUsedAsItIsWithoutAnyRequest() {
        gateway.variable("vendorKey", "placeholder-bearer-token");
        OnyxTokenService.TokenResult result = tokens(Map.of()).token(SignInSettings.bearerToken("{{vendorKey}}"));
        server.verify();
        assertThat(result.token()).isEqualTo("placeholder-bearer-token");
        assertThat(result.source()).isEqualTo(OnyxTokenService.TokenSource.CONFIGURED);
        assertThat(result.signsInAgainOnRefusal()).isFalse();

        OnyxTokenService.TokenResult missing = tokens(Map.of()).token(SignInSettings.bearerToken("{{undefinedKey}}"));
        assertThat(missing.token()).isNull();
        assertThat(missing.cause()).isEqualTo(OnyxTokenService.TokenFailure.SETTING_MISSING);
        assertThat(missing.failure()).isEqualTo("This environment signs in with a bearer token, but none is stored, or it names a "
                + "placeholder the active environment does not define. Set it under Sign-in in Configuration > Environments. The request "
                + "was not sent.");
    }

    @Test
    void clientCredentialsInABasicHeader() {
        String basic = Base64.getEncoder().encodeToString("placeholder-client:placeholder-client-secret".getBytes(StandardCharsets.UTF_8));
        server.expect(requestTo(TOKEN_URL)).andExpect(header("Authorization", "Basic " + basic))
                .andExpect(content().string("grant_type=client_credentials&scope=system%2F*.read"))
                .andRespond(withSuccess(grant("placeholder-access-token"), MediaType.APPLICATION_JSON));

        OnyxTokenService.TokenResult result = tokens(Map.of()).token(clientCredentials(SignInSettings.ClientAuthentication.BASIC));

        server.verify();
        assertThat(result.token()).isEqualTo("placeholder-access-token");
        assertThat(result.source()).isEqualTo(OnyxTokenService.TokenSource.ISSUED);
    }

    @Test
    void clientCredentialsInTheFormBody() {
        server.expect(requestTo(TOKEN_URL)).andExpect(headerDoesNotExist("Authorization"))
                .andExpect(content().string("grant_type=client_credentials&client_id=placeholder-client"
                        + "&client_secret=placeholder-client-secret&scope=system%2F*.read"))
                .andRespond(withSuccess(grant("placeholder-access-token"), MediaType.APPLICATION_JSON));

        OnyxTokenService.TokenResult result = tokens(Map.of()).token(clientCredentials(SignInSettings.ClientAuthentication.BODY));

        server.verify();
        assertThat(result.token()).isEqualTo("placeholder-access-token");
    }

    @Test
    void theEnvironmentsSignInReplacesOnyxAuthWholly() {
        // onyx.auth names another token url; the environment's own one is the only one asked.
        server.expect(once(), requestTo(TOKEN_URL)).andRespond(withSuccess(grant("from-the-environment"), MediaType.APPLICATION_JSON));
        OnyxTokenService service = tokens(Map.of("auth.enabled", "true", "auth.token-url", OTHER_TOKEN_URL, "auth.client-id", "a",
                "auth.client-secret", "b"));

        assertThat(service.token(clientCredentials(SignInSettings.ClientAuthentication.BASIC)).token()).isEqualTo("from-the-environment");
        server.verify();
    }

    @Test
    void aMissingSettingIsNamedByItsFieldAndNothingIsAsked() {
        OnyxTokenService service = tokens(Map.of());
        OnyxTokenService.TokenResult noId = service.token(SignInSettings.clientCredentials(TOKEN_URL, "{{undefinedId}}", "s", null, null));
        assertThat(noId.cause()).isEqualTo(OnyxTokenService.TokenFailure.SETTING_MISSING);
        assertThat(noId.failure()).contains("its Client id is not set").contains("Configuration > Environments")
                .doesNotContain("undefinedId");
        assertThat(service.token(SignInSettings.clientCredentials(null, "a", "s", null, null)).failure()).contains("its Token url is not set");
        assertThat(service.token(SignInSettings.clientCredentials("not an address", "a", "s", null, null)).failure())
                .isEqualTo(OnyxTokenService.ENVIRONMENT_TOKEN_URL_NOT_USABLE);
        assertThat(service.token(SignInSettings.clientCredentials(TOKEN_URL, null, null, null, null)).failure())
                .contains("its Client id and Client secret are not set");
        server.verify();
    }

    @Test
    void aSaveChangesTheToken() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("before-the-save"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("after-the-save"), MediaType.APPLICATION_JSON));
        OnyxTokenService service = tokens(Map.of());
        SignInSettings settings = clientCredentials(SignInSettings.ClientAuthentication.BASIC);

        assertThat(service.token(settings).token()).isEqualTo("before-the-save");
        assertThat(service.token(settings).token()).isEqualTo("before-the-save");
        gateway.changeConfiguration();
        assertThat(service.token(settings).token()).isEqualTo("after-the-save");
        server.verify();
    }

    @Test
    void tokensAreCachedPerTokenUrlAndClientId() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("for-client-a"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("for-client-b"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(OTHER_TOKEN_URL)).andRespond(withSuccess(grant("for-client-a-elsewhere"), MediaType.APPLICATION_JSON));
        OnyxTokenService service = tokens(Map.of());
        SignInSettings a = SignInSettings.clientCredentials(TOKEN_URL, "client-a", "s", null, null);
        SignInSettings b = SignInSettings.clientCredentials(TOKEN_URL, "client-b", "s", null, null);
        SignInSettings elsewhere = SignInSettings.clientCredentials(OTHER_TOKEN_URL, "client-a", "s", null, null);

        assertThat(service.token(a).token()).isEqualTo("for-client-a");
        assertThat(service.token(b).token()).isEqualTo("for-client-b");
        assertThat(service.token(elsewhere).token()).isEqualTo("for-client-a-elsewhere");
        // Each again, from the cache: no further request.
        assertThat(service.token(a).token()).isEqualTo("for-client-a");
        assertThat(service.token(b).token()).isEqualTo("for-client-b");
        assertThat(service.token(elsewhere).token()).isEqualTo("for-client-a-elsewhere");
        server.verify();
    }

    @Test
    void a401DropsTheTokenSignsInAgainAndSendsTheCallOnceMore() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("refused-early"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOOK)).andExpect(header("Authorization", "Bearer refused-early"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("fresh"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOOK)).andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess("{\"cards\":[]}", MediaType.APPLICATION_JSON));
        OnyxTokenService tokens = tokens(Map.of());
        OnyxAccess access = new OnyxAccess(new ExchangeExecutor(), tokens, gateway, client);

        VendorExchange exchange = access.send(hook(), clientCredentials(SignInSettings.ClientAuthentication.BASIC), ResolvedText.plain(BODY),
                json());

        server.verify();
        assertThat(exchange.httpStatus()).isEqualTo(200);
        assertThat(exchange.request().note()).isEqualTo("The first attempt was refused with HTTP 401; PAVE signed in again and sent it "
                + "once more.");
    }

    @Test
    void theRetryAppliesToOnyxAuthToo() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("refused-early"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOOK)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("fresh"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOOK)).andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess("{\"cards\":[]}", MediaType.APPLICATION_JSON));
        OnyxTokenService tokens = tokens(Map.of("auth.enabled", "true", "auth.token-url", "{{tokenUrl}}", "auth.client-id", "{{clientId}}",
                "auth.client-secret", "{{clientSecret}}"));
        OnyxAccess access = new OnyxAccess(new ExchangeExecutor(), tokens, gateway, client);

        VendorExchange exchange = access.send(hook(), ResolvedText.plain(BODY), json());

        server.verify();
        assertThat(exchange.httpStatus()).isEqualTo(200);
        assertThat(exchange.request().note()).contains("sent it once more");
    }

    @Test
    void aSecond401IsTheAnswerAndTheNextCallSignsInAfresh() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("first"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOOK)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("second"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOOK)).andExpect(header("Authorization", "Bearer second"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":\"refused\"}").contentType(MediaType.APPLICATION_JSON));
        OnyxTokenService tokens = tokens(Map.of());
        OnyxAccess access = new OnyxAccess(new ExchangeExecutor(), tokens, gateway, client);
        SignInSettings settings = clientCredentials(SignInSettings.ClientAuthentication.BASIC);

        VendorExchange exchange = access.send(hook(), settings, ResolvedText.plain(BODY), json());

        server.verify();
        assertThat(exchange.httpStatus()).isEqualTo(401);
        assertThat(exchange.responseBody()).contains("refused");
        assertThat(exchange.request().note()).contains("sent it once more");

        // The refused token is gone: the next token is asked for, not taken from the cache.
        server.reset();
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("third"), MediaType.APPLICATION_JSON));
        assertThat(tokens.token(settings).token()).isEqualTo("third");
        server.verify();
    }

    @Test
    void aBearerTokenIsNeverRetried() {
        gateway.variable("vendorKey", "placeholder-bearer-token");
        server.expect(once(), requestTo(HOOK)).andExpect(header("Authorization", "Bearer placeholder-bearer-token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        OnyxAccess access = new OnyxAccess(new ExchangeExecutor(), tokens(Map.of()), gateway, client);

        VendorExchange exchange = access.send(hook(), SignInSettings.bearerToken("{{vendorKey}}"), ResolvedText.plain(BODY), json());

        server.verify();
        assertThat(exchange.httpStatus()).isEqualTo(401);
        assertThat(exchange.request().note()).isNull();
    }

    @Test
    void whenSigningInAgainFailsTheFirstAnswerStandsWithTheReason() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("first"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(HOOK)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        OnyxAccess access = new OnyxAccess(new ExchangeExecutor(), tokens(Map.of()), gateway, client);

        VendorExchange exchange = access.send(hook(), clientCredentials(SignInSettings.ClientAuthentication.BASIC), ResolvedText.plain(BODY),
                json());

        server.verify();
        assertThat(exchange.httpStatus()).isEqualTo(401);
        assertThat(exchange.request().note()).isEqualTo("The service refused this call with HTTP 401. PAVE signed in again to send it "
                + "once more, and could not: No access token: the token endpoint answered HTTP 400.");
    }

    @Test
    void aCallWhoseConfigurationChangedIsNotSentAgain() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("first"), MediaType.APPLICATION_JSON));
        server.expect(times(1), requestTo(HOOK)).andRespond(request -> {
            gateway.changeConfiguration();
            return withStatus(HttpStatus.UNAUTHORIZED).createResponse(request);
        });
        OnyxAccess access = new OnyxAccess(new ExchangeExecutor(), tokens(Map.of()), gateway, client);

        VendorExchange exchange = access.send(hook(), clientCredentials(SignInSettings.ClientAuthentication.BASIC), ResolvedText.plain(BODY),
                json());

        server.verify();
        assertThat(exchange.httpStatus()).isEqualTo(401);
    }

    @Test
    void theStateSaysEachMethodInTheOperatorsWords() {
        OnyxTokenService off = tokens(Map.of());
        assertThat(off.state().code()).isEqualTo(SignInState.Code.NONE);
        assertThat(off.state().shortWords()).isEqualTo("No sign-in needed");
        assertThat(off.state(SignInSettings.none()).shortWords()).isEqualTo("No sign-in needed");
        gateway.variable("vendorKey", "placeholder-bearer-token");
        SignInState bearer = off.state(SignInSettings.bearerToken("{{vendorKey}}"));
        assertThat(bearer.shortWords()).isEqualTo("Access key configured");
        assertThat(bearer.detail()).doesNotContain("placeholder-bearer-token");

        SignInSettings settings = clientCredentials(SignInSettings.ClientAuthentication.BASIC);
        assertThat(off.state(settings).shortWords()).isEqualTo("Signs in on first call");
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(grant("placeholder-access-token"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        off.token(settings);
        SignInState signedIn = off.state(settings);
        assertThat(signedIn.shortWords()).isEqualTo("Signed in (renews automatically)");
        assertThat(signedIn.detail()).isEqualTo("Client credentials, from this environment's Sign-in. The token is good until 16:00:00 UTC "
                + "and is renewed before then.");
        assertThat(signedIn.detail()).doesNotContain("placeholder");

        gateway.changeConfiguration();
        off.token(settings);
        SignInState failed = off.state(settings);
        assertThat(failed.shortWords()).isEqualTo("Sign-in failed");
        assertThat(failed.detail()).isEqualTo("No access token: the token endpoint answered HTTP 401. The request was not sent.");
        server.verify();
    }

    @Test
    void theStateWithoutSettingsReadsTheLastCallsSignInUnderTheConfigurationInForce() {
        gateway.variable("vendorKey", "placeholder-bearer-token");
        OnyxTokenService service = tokens(Map.of());
        service.tokenFor(SignInSettings.bearerToken("{{vendorKey}}"));
        assertThat(service.state().code()).isEqualTo(SignInState.Code.BEARER);
        // A save: the settings the last call had may no longer be the environment's.
        gateway.changeConfiguration();
        assertThat(service.state().code()).isEqualTo(SignInState.Code.NONE);
    }

    @Test
    void theMockStateHasItsOwnWords() {
        assertThat(SignInState.mock().shortWords()).isEqualTo("No sign-in on mock");
        assertThat(SignInState.mock().code()).isEqualTo(SignInState.Code.MOCK);
    }
}
