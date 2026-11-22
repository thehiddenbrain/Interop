package org.p32h.interop.epa.pave.client.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.node.ObjectNode;

/**
 * I-28 (b): the token client refuses, at the moment it would send, a sign-in whose token url is,
 * or lies beneath, an address a production environment uses for a call, whatever was stored, and
 * nothing is sent.
 */
class TokenUrlAtSendingTest {

    private static final String HOST = "http://127.0.0.1:18950";

    @TempDir
    Path directory;

    private static int save(MockMvc mvc, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andReturn().getResponse().getStatus();
    }

    @Test
    void aTokenUrlThatBecameAProductionAddressAfterItWasStoredIsNeverPostedTo() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        // Stored while no production environment named the address, as an older store holds it.
        ObjectNode sandbox = JsonSafe.mapper().createObjectNode();
        sandbox.put("name", "sbx-tokenurl");
        sandbox.put("kind", "sandbox");
        sandbox.put("acknowledgeNotProd", true);
        ObjectNode signIn = sandbox.putObject("signIn");
        signIn.put("method", "CLIENT_CREDENTIALS");
        signIn.put("tokenUrl", "http://LOCALHOST:018950/fhir/x/../Claim/%24submit?grant=1");
        signIn.put("clientId", "placeholder-client");
        signIn.put("clientSecret", "placeholder-client-secret");
        signIn.put("clientAuthentication", "BODY");
        assertThat(save(mvc, sandbox)).isEqualTo(200);
        ObjectNode production = JsonSafe.mapper().createObjectNode();
        production.put("name", "vendor-live");
        production.put("kind", "prod");
        production.putObject("addresses").put("PAS_SUBMIT", HOST + "/fhir/Claim/$submit");
        assertThat(save(mvc, production)).isEqualTo(200);
        fixture.service.activate("sbx-tokenurl", null);

        RestClient.Builder builder = RestClient.builder();
        // No request is expected: one would fail the verification below.
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OnyxTokenService tokens = new OnyxTokenService(OnyxProperties.of(Map.of()), fixture.gateway, builder.build(), ConfigFixture.CLOCK);

        OnyxTokenService.TokenResult result = tokens.tokenFor(fixture.gateway.activeSignIn());
        server.verify();
        assertThat(result.token()).isNull();
        assertThat(result.cause()).isEqualTo(OnyxTokenService.TokenFailure.SETTING_MISSING);
        // Quoted by scheme, host and port only, as the client reads them: never the path or the query.
        assertThat(result.failure()).isEqualTo("Nothing was sent: the token url, at http://localhost:18950, is an address of the production "
                + "environment \"vendor-live\", or lies beneath one, and a sign-in sends the client secret to it. A token url is never an "
                + "address a production environment uses for a call. Set the token url under Sign-in in Configuration > Environments.");
        // The state the screens read says the same, and asks no one.
        assertThat(tokens.state(fixture.gateway.activeSignIn()).toString()).doesNotContain("placeholder-client-secret");
    }

    @Test
    void theApplicationYmlTokenUrlIsHeldToTheSameRule() throws Exception {
        ConfigFixture fixture = ConfigFixture.live(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        ObjectNode production = JsonSafe.mapper().createObjectNode();
        production.put("name", "vendor-live");
        production.put("kind", "prod");
        production.putObject("addresses").put("PAS_SUBMIT", HOST + "/fhir/Claim/$submit");
        assertThat(save(mvc, production)).isEqualTo(200);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OnyxProperties onyx = OnyxProperties.of(Map.of("auth.enabled", "true", "auth.token-url", HOST + "/fhir/Claim/$submit/token",
                "auth.client-id", "placeholder-client", "auth.client-secret", "placeholder-secret"));
        OnyxTokenService tokens = new OnyxTokenService(onyx, fixture.gateway, builder.build(), ConfigFixture.CLOCK);

        OnyxTokenService.TokenResult result = tokens.token();
        server.verify();
        assertThat(result.token()).isNull();
        assertThat(result.failure()).startsWith("Nothing was sent: the token url, at " + HOST + ", is an address of the production "
                + "environment \"vendor-live\"");
    }
}
