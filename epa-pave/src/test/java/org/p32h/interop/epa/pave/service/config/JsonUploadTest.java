package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Environment and collection exports are posted as the text the page read from the file (R-06),
 * read inside their own guard (R-07): the answer is the upload's as it was, a refusal keeps its
 * words, a misspelt property is named, and the multipart form is still taken, for curl.
 */
class JsonUploadTest {

    @TempDir
    Path directory;

    private static MockHttpServletResponse postJson(MockMvc mvc, String path, ObjectNode body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body.toString())).andReturn().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JsonSafe.parse(response.getContentAsString()).node();
    }

    @Test
    void anEnvironmentAndACollectionPostedAsTextAreStoredAsAnUploadStoresThem() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        ObjectNode environment = JsonSafe.mapper().createObjectNode();
        environment.put("fileName", "sandbox.json").put("text", PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT)).put("kind", "sandbox");
        MockHttpServletResponse stored = postJson(mvc, "/api/config/environments", environment);
        assertThat(stored.getStatus()).as(stored.getContentAsString()).isEqualTo(200);
        assertThat(json(stored).get("environment").get("name").stringValue()).isEqualTo("Onyx Sandbox");
        assertThat(fixture.store.snapshot().byName("Onyx Sandbox").originalFileName()).isEqualTo("sandbox.json");

        ObjectNode collection = JsonSafe.mapper().createObjectNode();
        collection.put("fileName", "vendor.json").put("text", PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION));
        MockHttpServletResponse uploaded = postJson(mvc, "/api/config/collections", collection);
        assertThat(uploaded.getStatus()).as(uploaded.getContentAsString()).isEqualTo(200);
        assertThat(fixture.store.snapshot().collections()).hasSize(1);
    }

    @Test
    void theKindIsNeverGuessedAndAMisspeltPropertyIsNamed() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        ObjectNode noKind = JsonSafe.mapper().createObjectNode();
        noKind.put("fileName", "sandbox.json").put("text", PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT));
        MockHttpServletResponse refused = postJson(mvc, "/api/config/environments", noKind);
        assertThat(refused.getStatus()).isEqualTo(422);
        assertThat(json(refused).get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("kind");

        ObjectNode misspelt = noKind.deepCopy();
        misspelt.put("kind", "sandbox").put("acknowledgeNotProduction", true);
        MockHttpServletResponse named = postJson(mvc, "/api/config/environments", misspelt);
        assertThat(named.getStatus()).isEqualTo(422);
        assertThat(named.getContentAsString()).contains("acknowledgeNotProduction");
        assertThat(fixture.store.snapshot().environments()).isEmpty();
    }

    @Test
    void aFileThatIsNotAnExportIsRefusedForWhatItIsAndNothingIsStored() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        ObjectNode notJson = JsonSafe.mapper().createObjectNode();
        notJson.put("fileName", "notes.json").put("text", "this is not JSON").put("kind", "sandbox");
        MockHttpServletResponse refused = postJson(mvc, "/api/config/environments", notJson);
        assertThat(refused.getStatus()).isEqualTo(422);
        // The refusal of the file keeps its own words, never the line for a failure of this tool.
        assertThat(json(refused).get("title").stringValue()).doesNotContain("PAVE could not read this file");
        assertThat(fixture.store.snapshot().environments()).isEmpty();

        ObjectNode noText = JsonSafe.mapper().createObjectNode();
        noText.put("fileName", "vendor.json");
        MockHttpServletResponse empty = postJson(mvc, "/api/config/collections", noText);
        assertThat(empty.getStatus()).isEqualTo(422);
        assertThat(json(empty).get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("text");
    }

    @Test
    void theMultipartFormIsStillTaken() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        MockMultipartFile file = new MockMultipartFile("file", PostmanFiles.SANDBOX_ENVIRONMENT, "application/json",
                PostmanFiles.bytes(PostmanFiles.SANDBOX_ENVIRONMENT));
        MockHttpServletResponse stored = mvc.perform(multipart("/api/config/environments").file(file).param("kind", "sandbox"))
                .andReturn().getResponse();
        assertThat(stored.getStatus()).as(stored.getContentAsString()).isEqualTo(200);
        assertThat(fixture.store.snapshot().byName("Onyx Sandbox")).isNotNull();
    }
}
