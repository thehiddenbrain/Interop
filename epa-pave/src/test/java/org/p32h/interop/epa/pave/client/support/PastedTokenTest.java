package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.SignInSettings;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.SignInTokenController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

/** A token pasted for this session (R-43): used first, held in memory only, masked, and let go of on a 401 or a change. */
@ExtendWith(OutputCaptureExtension.class)
class PastedTokenTest {

    private static final String PASTED = "placeholder-pasted-token-0123456789";
    private static final String TOKEN_URL = "https://auth.example.invalid/oauth/token";
    private static final String HOOK = "https://vendor.example.invalid/cds-services/order-sign";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    private final FakeGateway gateway = new FakeGateway().environment("Placeholder Sandbox", EnvironmentKind.SANDBOX)
            .url(EpaOperation.CRD_ORDER_SIGN, HOOK);
    private MockRestServiceServer server;
    private RestClient client;
    private OnyxTokenService tokens;
    private OnyxAccess access;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        tokens = OnyxAccessTestFactory.tokens(OnyxProperties.of(Map.of()), gateway, client, CLOCK);
        access = new OnyxAccess(new ExchangeExecutor(), tokens, gateway, client);
    }

    private static SignInSettings environmentSignIn() {
        return SignInSettings.clientCredentials(TOKEN_URL, "placeholder-client", "placeholder-client-secret", null, null);
    }

    private VendorExchange send() {
        return access.send(gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)), environmentSignIn(),
                ResolvedText.plain("{\"hook\":\"order-sign\"}"), ExchangeOptions.json("application/json"));
    }

    @Test
    void thePastedTokenIsUsedAheadOfTheEnvironmentsSignIn() {
        // No token request is expected: the environment's sign-in is not asked while a token is pasted.
        server.expect(once(), requestTo(HOOK)).andExpect(header("Authorization", "Bearer " + PASTED))
                .andRespond(withSuccess("{\"cards\":[]}", MediaType.APPLICATION_JSON));
        long epoch = gateway.epoch();
        tokens.paste("  " + PASTED + "\n", "Placeholder Sandbox");

        VendorExchange exchange = send();

        server.verify();
        assertThat(exchange.httpStatus()).isEqualTo(200);
        assertThat(gateway.epoch()).as("pasting moves no epoch").isEqualTo(epoch);
        SignInState state = tokens.state();
        assertThat(state.shortWords()).isEqualTo("Using your pasted token");
        assertThat(state.detail()).isEqualTo("A token pasted on Configuration > Access token, held in memory for Placeholder Sandbox until "
                + "a 401, a restart or a change of the configuration.");
    }

    @Test
    void thePastedTokenGoesOnlyToTheEnvironmentsOwnAddresses() {
        server.expect(once(), requestTo("https://elsewhere.example.invalid/cds-services/order-sign"))
                .andExpect(request -> assertThat(request.getHeaders().get("Authorization")).isNull())
                .andRespond(withSuccess("{\"cards\":[]}", MediaType.APPLICATION_JSON));
        tokens.paste(PASTED, "Placeholder Sandbox");
        FakeGateway moved = gateway.url(EpaOperation.CRD_ORDER_SIGN, "{{target}}");
        VendorExchange exchange = access.send(moved.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                        .withRunValues(Map.of("target", "https://elsewhere.example.invalid/cds-services/order-sign"))), environmentSignIn(),
                ResolvedText.plain("{}"), ExchangeOptions.json("application/json"));
        server.verify();
        assertThat(exchange.request().note()).contains("was not attached");
    }

    @Test
    void aServiceRepeatingThePastedTokenIsShownWithItMaskedAndNothingKeptHoldsIt(CapturedOutput output) {
        server.expect(once(), requestTo(HOOK)).andRespond(withSuccess("{\"echo\":\"" + PASTED + "\"}", MediaType.APPLICATION_JSON));
        tokens.paste(PASTED, "Placeholder Sandbox");

        VendorExchange exchange = send();

        server.verify();
        assertThat(exchange.wireResponse().body()).contains("[secret access token]").doesNotContain(PASTED);
        // What the wire view is made from: the request and the answer as the browser receives them.
        String shown = JsonSafe.mapper().writeValueAsString(exchange.request()) + JsonSafe.mapper().writeValueAsString(exchange.wireResponse());
        assertThat(shown).doesNotContain(PASTED);
        assertThat(exchange.request().toString()).doesNotContain(PASTED);
        assertThat(output.getAll()).doesNotContain(PASTED);

        org.p32h.interop.epa.pave.model.SentSecrets.Builder held = org.p32h.interop.epa.pave.model.SentSecrets.builder();
        tokens.addTo(held);
        assertThat(held.build().mask("a notification repeating " + PASTED).text()).doesNotContain(PASTED);
    }

    @Test
    void a401LetsGoOfThePastedTokenAndIsTheAnswer() {
        server.expect(once(), requestTo(HOOK)).andExpect(header("Authorization", "Bearer " + PASTED))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        tokens.paste(PASTED, "Placeholder Sandbox");

        VendorExchange exchange = send();

        server.verify();
        assertThat(exchange.httpStatus()).isEqualTo(401);
        assertThat(exchange.request().note()).isNull();
        assertThat(tokens.pastedHeld()).isFalse();
    }

    @Test
    void aChangeOfTheConfigurationLetsGoOfIt() {
        tokens.paste(PASTED, "Placeholder Sandbox");
        assertThat(tokens.pastedHeld()).isTrue();
        gateway.changeConfiguration();
        assertThat(tokens.pastedHeld()).isFalse();
        assertThat(tokens.state().code()).isNotEqualTo(SignInState.Code.PASTED);

        tokens.paste(PASTED, "Placeholder Sandbox");
        tokens.forgetPasted();
        assertThat(tokens.pastedHeld()).isFalse();
    }

    // ------------------------------------------------------------------ over HTTP

    private static ObjectProvider<OnyxTokenService> provider(OnyxTokenService service) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        if (service != null) {
            beans.addBean("tokens", service);
        }
        return beans.getBeanProvider(OnyxTokenService.class);
    }

    private static String body(String token, String environment) {
        return "{\"token\":\"" + token + "\",\"environment\":\"" + environment + "\"}";
    }

    @Test
    void pastedOverHttpForTheActiveEnvironmentAndNeverWrittenToTheStore(CapturedOutput output) throws Exception {
        ConfigFixture fixture = ConfigFixture.on(directory).live().build();
        fixture.activeEnvironmentOf("Placeholder Sandbox", EnvironmentKind.SANDBOX, "baseUrl", "https://vendor.example.invalid");
        fixture.environmentOf("Placeholder Other", EnvironmentKind.SANDBOX, "baseUrl", "https://other.example.invalid");
        OnyxTokenService service = OnyxAccessTestFactory.tokens(OnyxProperties.of(Map.of()), fixture.gateway, client, CLOCK);
        MockMvc mvc = TestMvc.standalone(new SignInTokenController(provider(service), fixture.service));
        long epoch = fixture.gateway.epoch();

        mvc.perform(post("/api/config/sign-in/token").contentType(MediaType.APPLICATION_JSON)
                        .content(body(PASTED, fixture.environmentId("Placeholder Other"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("environment"));
        assertThat(service.pastedHeld()).isFalse();
        mvc.perform(post("/api/config/sign-in/token").contentType(MediaType.APPLICATION_JSON)
                        .content(body("  ", fixture.environmentId("Placeholder Sandbox"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("token"));
        mvc.perform(post("/api/config/sign-in/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tokn\":\"" + PASTED + "\",\"environment\":\"x\"}"))
                .andExpect(status().isUnprocessableContent());

        mvc.perform(post("/api/config/sign-in/token").contentType(MediaType.APPLICATION_JSON)
                        .content(body(PASTED, fixture.environmentId("Placeholder Sandbox"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.held").value(true));
        assertThat(service.pastedHeld()).isTrue();
        assertThat(fixture.gateway.epoch()).isEqualTo(epoch);
        assertThat(filesUnder(directory)).noneMatch(text -> text.contains(PASTED));
        assertThat(output.getAll()).doesNotContain(PASTED);

        mvc.perform(delete("/api/config/sign-in/token")).andExpect(status().isOk()).andExpect(jsonPath("$.held").value(false));
        assertThat(service.pastedHeld()).isFalse();
    }

    @Test
    void refusedOnMock() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new SignInTokenController(provider(null), fixture.service));

        mvc.perform(post("/api/config/sign-in/token").contentType(MediaType.APPLICATION_JSON).content(body(PASTED, "any")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("No sign-in on mock: the mock profile answers every call itself, so a pasted token "
                        + "is not used."));
        mvc.perform(delete("/api/config/sign-in/token")).andExpect(status().isOk()).andExpect(jsonPath("$.held").value(false));
    }

    private static List<String> filesUnder(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).map(path -> {
                try {
                    return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                } catch (IOException e) {
                    return "";
                }
            }).toList();
        }
    }
}
