package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Signing in is on and the client secret is a placeholder the configuration does not define: the
 * token service refuses before any call, and the run is said as this tool's sign-in settings.
 * The service url points at a port of the loopback interface nothing is expected on; nothing is
 * sent there, which the wire view shows.
 */
@SpringBootTest
@ActiveProfiles("live")
class SignInSettingMissingTest {

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry registry) {
        registry.add("onyx.crd.url", () -> "http://127.0.0.1:9");
        registry.add("onyx.variables[crdOrderSignServiceId]", () -> "placeholder-service");
        registry.add("onyx.auth.enabled", () -> "true");
        registry.add("onyx.auth.token-url", () -> "http://127.0.0.1:9/token");
        registry.add("onyx.auth.client-id", () -> "placeholder-client-id-0043");
        registry.add("onyx.auth.client-secret", () -> "{{undefinedClientSecret}}");
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    @Autowired WebApplicationContext context;

    @Test
    void aSecretThatIsNotSetIsTheTestersSettingsAndNothingIsSent() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        JsonNode answer = JsonSafe.parse(mvc.perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content("{\"member\":"
                + "{\"memberId\":\"S123456789\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":"
                + "[{\"code\":\"E0470\"}],\"dateOfService\":\"2026-03-20\"}")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .node();

        assertThat(answer.at("/verdict/outcomeCode").asString()).as(answer.toString()).isEqualTo("NOT_SIGNED_IN");
        assertThat(answer.at("/verdict/tone").asString()).isEqualTo("NEUTRAL");
        assertThat(answer.at("/verdict/headline").asString()).isEqualTo("Nothing was sent: this tool could not sign in.");
        // The line names the setting, so the next step's "the line above says which setting" is true.
        assertThat(answer.at("/verdict/supporting/0").asString()).isEqualTo("Authentication is switched on but onyx.auth.client-secret is not "
                + "set, or a placeholder the active environment does not define. Configuration > Advanced > In force now says where it is read from. "
                + "The request was not sent.");
        assertThat(answer.at("/error/kind").asString()).isEqualTo("CONFIGURATION");
        assertThat(answer.at("/error/heading").asString()).isEqualTo("Not sent: this tool could not sign in");
        assertThat(answer.at("/nextStep/action").asString()).isEqualTo("OPEN_CONFIG");
        assertThat(answer.at("/answeredBy").asString()).isEqualTo("NOT_SENT");
        assertThat(answer.at("/wire/request/sent").asBoolean()).isFalse();
        assertThat(answer.toString()).doesNotContain("placeholder-client-id-0043");

        JsonNode line = JsonSafe.parse(mvc.perform(get("/api/runs/" + answer.at("/runId").asString())).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).node();
        assertThat(line.at("/outcome").asString()).isEqualTo("NOT_SIGNED_IN");
        assertThat(line.at("/errorType").asString()).isEqualTo("NOT_SIGNED_IN");
        assertThat(line.at("/answeredBy").asString()).isEqualTo("NOT_SENT");
    }
}
