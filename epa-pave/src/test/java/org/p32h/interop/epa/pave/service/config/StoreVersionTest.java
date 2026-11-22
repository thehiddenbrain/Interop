package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * store.json stays version 1 until a setting is first saved, then becomes version 2 after a copy of
 * the version 1 file is kept once (R-05). A version 1 store in the operator's shape opens and every
 * configuration read answers; a store from a later version is refused by name.
 */
class StoreVersionTest {

    @TempDir
    Path directory;

    private static final String LONG_BODY_COLLECTION = "long-body.postman_collection.json";

    /** A collection whose one request carries a body of 10,000 characters, as the operator's does. */
    private static byte[] longBodyCollection() {
        String body = "{\"note\": \"" + "x".repeat(10_000 - 12) + "\"}";
        return ("{\"info\": {\"name\": \"Long Body\", \"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, "
                + "\"item\": " + ConfigFixture.items(ConfigFixture.item("order-sign Happy Path", "POST",
                "{{crdUrl}}/cds-services/order-sign-crd").body(body)) + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** The operator's shape: an environment, the sample collection and a collection with a long body, written as version 1. */
    private ConfigFixture operatorsStore() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        fixture.store.uploadCollection(LONG_BODY_COLLECTION, longBodyCollection());
        return fixture;
    }

    private JsonNode state() throws Exception {
        return JsonSafe.parse(Files.readString(directory.resolve(ConfigStore.STATE_FILE))).node();
    }

    private static MockHttpServletResponse call(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void aVersionOneStoreInTheOperatorsShapeOpensAndEveryConfigurationReadAnswers() throws Exception {
        operatorsStore();
        assertThat(state().get("version").intValue()).isEqualTo(1);
        String before = Files.readString(directory.resolve(ConfigStore.STATE_FILE));

        ConfigFixture reopened = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(reopened.service));
        StoredEnvironment sandbox = reopened.store.snapshot().environments().get(0);
        assertThat(sandbox.settings().empty()).isTrue();
        StoredCollection longBody = reopened.store.snapshot().collections().get(1);
        for (String path : List.of("/api/config/environments", "/api/config/collections", "/api/config/effective",
                "/api/config/environments/" + sandbox.id() + "/settings",
                "/api/config/collections/" + longBody.id() + "/requests/" + longBody.requests().get(0).request().id())) {
            assertThat(call(mvc, get(path)).getStatus()).as(path).isEqualTo(200);
        }
        // Reading never writes: the version 1 file is as it was.
        assertThat(Files.readString(directory.resolve(ConfigStore.STATE_FILE))).isEqualTo(before);
        assertThat(directory.resolve(ConfigStore.BEFORE_V2)).doesNotExist();
    }

    @Test
    void theFirstSaveOfASettingWritesVersionTwoAndKeepsTheVersionOneFileOnce() throws Exception {
        ConfigFixture fixture = operatorsStore();
        // An upload or an activation with no setting keeps version 1, which an older build still opens.
        fixture.store.activate(fixture.store.snapshot().environments().get(0).name());
        assertThat(state().get("version").intValue()).isEqualTo(1);
        String versionOne = Files.readString(directory.resolve(ConfigStore.STATE_FILE));

        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));
        String id = fixture.store.snapshot().environments().get(0).id();
        MockHttpServletResponse saved = call(mvc, post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\": \"" + id + "\", \"timeouts\": {\"readMs\": 60000}}"));
        assertThat(saved.getStatus()).as(saved.getContentAsString()).isEqualTo(200);

        JsonNode state = state();
        assertThat(state.get("version").intValue()).isEqualTo(2);
        assertThat(state.get("environments").get(0).get("settings").get("timeouts").get("readMs").intValue()).isEqualTo(60000);
        assertThat(Files.readString(directory.resolve(ConfigStore.BEFORE_V2))).isEqualTo(versionOne);

        // A second save never replaces the copy of version 1, and the store stays version 2.
        call(mvc, post("/api/config/environments/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\": \"" + id + "\", \"timeouts\": {\"readMs\": null}}"));
        assertThat(state().get("version").intValue()).isEqualTo(2);
        assertThat(Files.readString(directory.resolve(ConfigStore.BEFORE_V2))).isEqualTo(versionOne);

        // Version 2 opens again with what was saved, and every read answers.
        ConfigFixture reopened = ConfigFixture.mock(directory);
        assertThat(reopened.store.snapshot().environments().get(0).settings().timeouts().empty()).isTrue();
        assertThat(call(TestMvc.standalone(new ConfigController(reopened.service)), get("/api/config/environments")).getStatus())
                .isEqualTo(200);
    }

    @Test
    void aStoreWrittenByALaterVersionIsRefusedByName() throws Exception {
        operatorsStore();
        Path file = directory.resolve(ConfigStore.STATE_FILE);
        ObjectNode root = (ObjectNode) state();
        root.put("version", 3);
        Files.writeString(file, JsonSafe.pretty(root));

        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(file.toString())
                .hasMessageContaining("it was written by a later version of this tool (version 3; this one reads versions 1 and 2)");
    }

    @Test
    void settingsInAVersionOneEntryAreNotReadAsSettings() throws Exception {
        operatorsStore();
        Path file = directory.resolve(ConfigStore.STATE_FILE);
        ObjectNode root = (ObjectNode) state();
        ((ObjectNode) root.get("environments").get(0)).putObject("settings").put("memberIdRule", "SPACED");
        Files.writeString(file, JsonSafe.pretty(root));

        ConfigFixture reopened = ConfigFixture.mock(directory);
        assertThat(reopened.store.snapshot().environments().get(0).settings().empty()).isTrue();
    }
}
