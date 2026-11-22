package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A secret typed into an environment's sign-in never leaves the server (R-04): no answer, no view
 * and no log line holds it, and neither does any call prepared under the environment. A secret
 * left blank keeps the stored one; the remove box removes it; a reference is kept and shown as one.
 */
@ExtendWith(OutputCaptureExtension.class)
class SettingsSecretsTest {

    /** A placeholder that stands for a client secret and a bearer token: never a real one. */
    private static final String SECRET = "P32H-placeholder-secret-7Q2";
    private static final String TOKEN = "P32H-placeholder-token-4K9";

    @TempDir
    Path directory;

    private static MockHttpServletResponse call(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    private static MockHttpServletResponse save(MockMvc mvc, ObjectNode body) throws Exception {
        return call(mvc, post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private static ObjectNode signIn(String id, String method, String clientSecret, Boolean removeClientSecret, String bearerToken) {
        ObjectNode body = JsonSafe.mapper().createObjectNode();
        if (id == null) {
            body.put("kind", "sandbox");
            body.putObject("environmentExport").put("fileName", "sandbox.json").put("text", PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT));
        } else {
            body.put("id", id);
        }
        ObjectNode in = body.putObject("signIn");
        in.put("method", method);
        in.put("tokenUrl", "{{tokenUrl}}");
        in.put("clientId", "{{clientId}}");
        if (clientSecret != null) {
            in.put("clientSecret", clientSecret);
        }
        if (removeClientSecret != null) {
            in.put("removeClientSecret", removeClientSecret);
        }
        if (bearerToken != null) {
            in.put("bearerToken", bearerToken);
        }
        return body;
    }

    @Test
    void aTypedSecretIsInNoAnswerNoViewNoLogLineAndNoPreparedCall(CapturedOutput output) throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        MockHttpServletResponse saved = save(mvc, signIn(null, "CLIENT_CREDENTIALS", SECRET, null, TOKEN));
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        assertThat(sandbox.settings().signIn().clientSecret()).isEqualTo(SECRET);
        fixture.store.activate(sandbox.name());

        JsonNode answer = JsonSafe.parse(saved.getContentAsString()).node();
        JsonNode clientSecret = answer.get("settings").get("signIn").get("clientSecret");
        assertThat(clientSecret.get("set").booleanValue()).isTrue();
        assertThat(clientSecret.has("reference")).isFalse();
        // Stored as typed, so the answer says so once, and names no value.
        List<String> warnings = new ArrayList<>();
        answer.get("warnings").forEach(warning -> warnings.add(warning.stringValue()));
        assertThat(warnings).anySatisfy(warning -> assertThat(warning).startsWith("The client secret of \"Onyx Sandbox\" is stored as typed"))
                .anySatisfy(warning -> assertThat(warning).startsWith("The bearer token of \"Onyx Sandbox\" is stored as typed"));

        List<String> shown = new ArrayList<>();
        shown.add(saved.getContentAsString());
        for (String path : List.of("/api/config/environments", "/api/config/effective", "/api/config/collections",
                "/api/config/environments/" + sandbox.id() + "/settings")) {
            MockHttpServletResponse read = call(mvc, get(path));
            assertThat(read.getStatus()).as(path).isEqualTo(200);
            shown.add(read.getContentAsString());
        }
        PreparedCall prepared = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY));
        shown.add(String.valueOf(prepared.url()));
        shown.add(String.valueOf(prepared.headers()));
        shown.add(prepared.toString());
        shown.add(sandbox.toString());
        shown.add(sandbox.settings().toString());
        shown.add(output.getAll());
        for (String each : shown) {
            assertThat(each).doesNotContain(SECRET).doesNotContain(TOKEN);
        }
        // Kept where it must be: in the store, which only this account reads.
        assertThat(Files.readString(directory.resolve(ConfigStore.STATE_FILE))).contains(SECRET);
    }

    @Test
    void blankKeepsTheStoredSecretTheBoxRemovesItAndAReferenceIsShownAsOne() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        save(mvc, signIn(null, "CLIENT_CREDENTIALS", SECRET, null, null));
        String id = fixture.store.snapshot().byName("Onyx Sandbox").id();

        MockHttpServletResponse kept = save(mvc, signIn(id, "CLIENT_CREDENTIALS", "", null, null));
        assertThat(kept.getStatus()).isEqualTo(200);
        assertThat(fixture.store.snapshot().byName("Onyx Sandbox").settings().signIn().clientSecret()).isEqualTo(SECRET);
        // Nothing new was typed, so no warning repeats.
        assertThat(kept.getContentAsString()).doesNotContain("is stored as typed").doesNotContain(SECRET);

        save(mvc, signIn(id, "CLIENT_CREDENTIALS", "", true, null));
        assertThat(fixture.store.snapshot().byName("Onyx Sandbox").settings().signIn().clientSecret()).isNull();
        assertThat(Files.readString(directory.resolve(ConfigStore.STATE_FILE))).doesNotContain(SECRET);

        MockHttpServletResponse reference = save(mvc, signIn(id, "CLIENT_CREDENTIALS", "{{env:PAVE_ONYX_CLIENT_SECRET}}", null, null));
        JsonNode view = JsonSafe.parse(reference.getContentAsString()).node().get("settings").get("signIn").get("clientSecret");
        assertThat(view.get("set").booleanValue()).isTrue();
        assertThat(view.get("reference").stringValue()).isEqualTo("{{env:PAVE_ONYX_CLIENT_SECRET}}");
        assertThat(reference.getContentAsString()).doesNotContain("is stored as typed");

        // A blank method is not stated: application.yml's sign-in applies again, and nothing of the old one is kept.
        ObjectNode notStated = signIn(id, "", null, null, null);
        save(mvc, notStated);
        assertThat(fixture.store.snapshot().byName("Onyx Sandbox").settings().signIn()).isNull();
        JsonNode cleared = JsonSafe.parse(call(mvc, get("/api/config/environments/" + id + "/settings")).getContentAsString()).node()
                .get("signIn");
        assertThat(cleared.has("method")).isFalse();
        assertThat(cleared.get("inForceWords").stringValue()).startsWith("Not stated: application.yml applies");
    }
}
