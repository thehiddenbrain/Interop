package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import org.p32h.interop.epa.pave.service.config.WriteAccess;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * "Test the sign-in" (plan 5.3, D5): it asks the token endpoint afresh under the sign-in in force,
 * says the outcome in one line with no value in it, and on mock says that nothing signs in. The
 * chip in {@code /api/mode} follows what the test found.
 */
class SignInTestEndpointTest {

    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String SECRET = "placeholder-client-secret";
    private static final String ACCESS = "placeholder-access-token";
    private static final String GRANT = "{\"access_token\":\"" + ACCESS + "\",\"token_type\":\"Bearer\",\"expires_in\":3600}";

    private final Clock clock = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC);
    private final FakeGateway fake = new FakeGateway().variable("tokenUrl", TOKEN_URL).variable("clientId", "placeholder-client")
            .variable("clientSecret", SECRET);
    private MockRestServiceServer server;
    private RestClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
    }

    /** The fake gateway with the active environment's sign-in this test states. */
    private static final class SignInGateway implements OperationGateway {
        private final FakeGateway delegate;
        private final SignInSettings signIn;

        SignInGateway(FakeGateway delegate, SignInSettings signIn) {
            this.delegate = delegate;
            this.signIn = signIn;
        }

        @Override
        public PreparedCall prepare(CallIntent intent) {
            return delegate.prepare(intent);
        }

        @Override
        public ResolvedText resolve(String template, Map<String, String> runValues) {
            return delegate.resolve(template, runValues);
        }

        @Override
        public EnvironmentRef activeEnvironment() {
            return delegate.activeEnvironment();
        }

        @Override
        public WriteAccess writeAccess(ProdUnlock unlock) {
            return delegate.writeAccess(unlock);
        }

        @Override
        public long epoch() {
            return delegate.epoch();
        }

        @Override
        public SignInSettings activeSignIn() {
            return signIn;
        }
    }

    private record Live(OnyxTokenService tokens, OperationGateway gateway, OnyxProperties onyx) {
    }

    private Live live(SignInSettings signIn, boolean onyxAuth) {
        Map<String, String> properties = new HashMap<>();
        if (onyxAuth) {
            properties.put("auth.enabled", "true");
            properties.put("auth.token-url", "{{tokenUrl}}");
            properties.put("auth.client-id", "{{clientId}}");
            properties.put("auth.client-secret", "{{clientSecret}}");
        }
        OnyxProperties onyx = OnyxProperties.of(properties);
        OperationGateway gateway = new SignInGateway(fake, signIn);
        return new Live(OnyxAccessTestFactory.tokens(onyx, gateway, client, clock), gateway, onyx);
    }

    private static ObjectProvider<OnyxTokenService> provider(OnyxTokenService tokens) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (tokens != null) {
            beans.addBean("onyxTokenService", tokens);
        }
        return beans.getBeanProvider(OnyxTokenService.class);
    }

    private static String test(Live live) throws Exception {
        return TestMvc.standalone(new SignInTestController(provider(live.tokens()), live.gateway(), live.onyx()))
                .perform(post("/api/config/sign-in/test")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static JsonNode mode(Live live) throws Exception {
        return JsonSafe.parse(TestMvc.standalone(new ModeController(EpaProperties.of(Map.of("mode", "live")), live.gateway(),
                new MockEnvironment(), provider(live.tokens()))).perform(get("/api/mode")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).node();
    }

    private static SignInSettings environmentSignIn() {
        return SignInSettings.clientCredentials(TOKEN_URL, "placeholder-client", SECRET, null, null);
    }

    @Test
    void onMockTheTestSaysNothingSignsInAndAsksNobody() throws Exception {
        OnyxProperties onyx = OnyxProperties.defaults();
        String body = TestMvc.standalone(new SignInTestController(provider(null), fake, onyx)).perform(post("/api/config/sign-in/test"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode answer = JsonSafe.parse(body).node();

        assertThat(answer.get("state").stringValue()).isEqualTo("MOCK");
        assertThat(answer.get("line").stringValue())
                .isEqualTo("No sign-in on mock. The mock profile answers every call itself, so nothing signs in.");
        assertThat(answer.has("httpStatus")).isFalse();
        assertThat(answer.has("elapsedMillis")).isFalse();
        server.verify();
    }

    @Test
    void theEnvironmentsClientCredentialsAreAskedAfreshOnEveryTestAndNoValueIsSaid() throws Exception {
        server.expect(times(2), requestTo(TOKEN_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));
        Live live = live(environmentSignIn(), false);

        String first = test(live);
        // A token held from the first test would answer from the cache and test nothing: the second test asks again.
        String second = test(live);

        server.verify();
        for (String body : new String[] {first, second}) {
            JsonNode answer = JsonSafe.parse(body).node();
            assertThat(answer.get("state").stringValue()).isEqualTo("SIGNED_IN");
            assertThat(answer.get("line").stringValue())
                    .isEqualTo("Signed in: the token endpoint issued a token (client credentials, from this environment's Sign-in).");
            assertThat(answer.get("elapsedMillis").isNumber()).isTrue();
            assertThat(answer.has("httpStatus")).isFalse();
            assertThat(body).doesNotContain(SECRET).doesNotContain(ACCESS).doesNotContain("placeholder-client\"");
        }
        assertThat(mode(live).at("/signIn/state").stringValue()).isEqualTo("SIGNED_IN");
        assertThat(mode(live).at("/signIn/short").stringValue()).isEqualTo("Signed in (renews automatically)");
    }

    @Test
    void onyxAuthIsTestedWhenTheEnvironmentStatesNoSignIn() throws Exception {
        server.expect(requestTo(TOKEN_URL)).andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));

        JsonNode answer = JsonSafe.parse(test(live(null, true))).node();

        server.verify();
        assertThat(answer.get("state").stringValue()).isEqualTo("SIGNED_IN");
        assertThat(answer.get("line").stringValue())
                .isEqualTo("Signed in: the token endpoint issued a token (client credentials, from onyx.auth in application.yml).");
    }

    @Test
    void aRefusalIsSaidWithoutTheWordsAboutARequestAndTheChipThenSaysFailed() throws Exception {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid_client\"}")
                .contentType(MediaType.APPLICATION_JSON));
        Live live = live(environmentSignIn(), false);

        String body = test(live);
        JsonNode answer = JsonSafe.parse(body).node();

        server.verify();
        assertThat(answer.get("state").stringValue()).isEqualTo("FAILED");
        assertThat(answer.get("line").stringValue()).isEqualTo("No access token: the token endpoint answered HTTP 401.");
        assertThat(answer.get("elapsedMillis").isNumber()).isTrue();
        assertThat(body).doesNotContain(SECRET).doesNotContain("invalid_client");
        JsonNode chip = mode(live).get("signIn");
        assertThat(chip.get("state").stringValue()).isEqualTo("FAILED");
        assertThat(chip.get("short").stringValue()).isEqualTo("Sign-in failed");
    }

    @Test
    void aMissingSettingIsSaidByItsFieldAndNothingIsAsked() throws Exception {
        Live live = live(SignInSettings.clientCredentials(TOKEN_URL, "placeholder-client", "{{undefinedSecret}}", null, null), false);

        JsonNode answer = JsonSafe.parse(test(live)).node();

        server.verify();
        assertThat(answer.get("state").stringValue()).isEqualTo("FAILED");
        assertThat(answer.get("line").stringValue()).isEqualTo("This environment signs in with client credentials, but its Client "
                + "secret is not set, or a placeholder the active environment does not define. Set it under Sign-in in Configuration > "
                + "Environments.");
        assertThat(answer.has("elapsedMillis")).isFalse();
    }

    @Test
    void noneAndABearerTokenAskNoTokenEndpoint() throws Exception {
        JsonNode none = JsonSafe.parse(test(live(SignInSettings.none(), true))).node();
        assertThat(none.get("state").stringValue()).isEqualTo("NONE");
        assertThat(none.get("line").stringValue())
                .isEqualTo("Nothing to test: this environment's Sign-in says None, so calls go without a token.");

        JsonNode off = JsonSafe.parse(test(live(null, false))).node();
        assertThat(off.get("state").stringValue()).isEqualTo("NONE");
        assertThat(off.get("line").stringValue()).isEqualTo("Nothing to test: this environment states no sign-in and onyx.auth in "
                + "application.yml is switched off, so calls go without a token.");

        String bearerBody = test(live(SignInSettings.bearerToken("placeholder-bearer-token"), false));
        JsonNode bearer = JsonSafe.parse(bearerBody).node();
        assertThat(bearer.get("state").stringValue()).isEqualTo("BEARER");
        assertThat(bearer.get("line").stringValue()).isEqualTo("Nothing to ask a token endpoint: this environment's Sign-in states a "
                + "bearer token, sent as it is. Whether the service accepts it shows on the first call.");
        assertThat(bearerBody).doesNotContain("placeholder-bearer-token");
        server.verify();
    }

    @Test
    void withAPastedTokenHeldTheEnvironmentsOwnSignInIsStillAskedAndTheLineSaysThePastedOneGoesFirst() throws Exception {
        server.expect(times(2), requestTo(TOKEN_URL)).andRespond(withSuccess(GRANT, MediaType.APPLICATION_JSON));
        Live live = live(environmentSignIn(), false);
        live.tokens().paste("placeholder-pasted-token", "Placeholder Sandbox");

        String body = test(live);
        JsonNode answer = JsonSafe.parse(body).node();

        server.verify();
        assertThat(answer.get("state").stringValue()).isEqualTo("SIGNED_IN");
        assertThat(answer.get("line").stringValue()).isEqualTo("Signed in: the token endpoint issued a token (client credentials, from "
                + "this environment's Sign-in). A pasted token is held and goes on calls ahead of it until a 401, a restart or a change "
                + "of the configuration.");
        assertThat(body).doesNotContain("placeholder-pasted-token");
        assertThat(live.tokens().pastedHeld()).isTrue();
        assertThat(mode(live).at("/signIn/short").stringValue()).isEqualTo("Using your pasted token");
    }
}
