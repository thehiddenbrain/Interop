package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * R-32 and hunt-files 5: a stored file that cannot be read, a store.json that names a file that is
 * gone and a remembered production address that cannot be parsed no longer stop the start. Each is
 * left out or kept as written, listed, and "Remove it from the store" moves the file aside.
 */
@ExtendWith(OutputCaptureExtension.class)
class UnreadableEntryTest {

    @TempDir
    Path directory;

    private Path state() {
        return directory.resolve(ConfigStore.STATE_FILE);
    }

    @Test
    void aDamagedEnvironmentFileIsLeftOutListedKeptInStoreJsonAndMovedAsideByRemove(CapturedOutput output) throws IOException {
        ConfigFixture first = ConfigFixture.mock(directory);
        StoredEnvironment sandbox = first.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        first.collection(PostmanFiles.VENDOR_COLLECTION);
        Path file = directory.resolve(sandbox.file());
        Files.writeString(file, "{\"values\": [ cut here");

        ConfigFixture started = ConfigFixture.mock(directory);

        String line = "The file " + file + " of the environment \"Onyx Sandbox\" is no longer a Postman environment export, so the "
                + "environment is left out. Remove it from the store and upload the environment again.";
        assertThat(started.store.snapshot().environments()).isEmpty();
        // The environment that was active is among those left out: none is active, none is put in its place.
        assertThat(started.store.snapshot().activeName()).isNull();
        assertThat(started.store.snapshot().collections()).hasSize(1);
        ConfigViews.Effective effective = started.service.effective(null);
        assertThat(effective.unreadable()).containsExactly(new ConfigViews.UnreadableView(sandbox.file(), line));
        assertThat(effective.problems()).contains(line);
        // Not reported a second time as a file nothing uses.
        assertThat(effective.problems()).noneMatch(problem -> problem.contains("nothing uses it"));

        // Another change keeps the entry in store.json, so a restart lists it again: nothing is lost by a save.
        started.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        assertThat(Files.readString(state())).contains(sandbox.file());
        ConfigFixture again = ConfigFixture.mock(directory);
        assertThat(again.store.unreadable()).extracting(ConfigStore.Unreadable::file).containsExactly(sandbox.file());

        ConfigStore.UnreadableRemoved removed = again.store.removeUnreadable(" " + sandbox.file() + " ");

        Path aside = directory.resolve(ConfigStore.UNREADABLE).resolve(file.getFileName());
        assertThat(removed.removed()).isEqualTo(sandbox.file());
        assertThat(removed.movedTo()).isEqualTo(aside.toString());
        assertThat(removed.epoch()).isGreaterThan(effective.epoch());
        assertThat(Files.exists(file)).isFalse();
        assertThat(Files.readString(aside)).isEqualTo("{\"values\": [ cut here");
        assertThat(again.store.unreadable()).isEmpty();
        assertThat(Files.readString(state())).doesNotContain(sandbox.file());
        ConfigFixture after = ConfigFixture.mock(directory);
        assertThat(after.store.unreadable()).isEmpty();
        assertThat(after.store.problems()).isEmpty();
        assertThat(after.store.snapshot().environments()).extracting(StoredEnvironment::name).containsExactly("Onyx UAT");
        assertThat(output).contains("left out " + sandbox.file()).doesNotContain("cut here");
    }

    @Test
    void aStoreJsonNamingAMissingFileStartsAndRemoveDropsTheEntryWithNothingToMove() throws IOException {
        ConfigFixture first = ConfigFixture.mock(directory);
        first.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        StoredCollection collection = first.collection(PostmanFiles.VENDOR_COLLECTION);
        Files.delete(directory.resolve(collection.file()));

        ConfigFixture started = ConfigFixture.mock(directory);

        assertThat(started.store.snapshot().collections()).isEmpty();
        assertThat(started.store.snapshot().environments()).hasSize(1);
        assertThat(started.store.unreadable()).extracting(ConfigStore.Unreadable::line).containsExactly("The file "
                + directory.resolve(collection.file()) + " of the collection \"Onyx Sandbox ePA\" is missing, so the collection is left "
                + "out. Put the file back and start PAVE again, or remove it from the store.");

        ConfigStore.UnreadableRemoved removed = started.store.removeUnreadable(collection.file());

        assertThat(removed.movedTo()).isNull();
        assertThat(Files.exists(directory.resolve(ConfigStore.UNREADABLE))).isFalse();
        assertThat(ConfigFixture.mock(directory).store.unreadable()).isEmpty();
        assertThatThrownBy(() -> started.store.removeUnreadable(collection.file())).isInstanceOf(NotFoundException.class);
        // store.json itself is never among the files Remove can take away.
        assertThatThrownBy(() -> started.store.removeUnreadable(ConfigStore.STATE_FILE)).isInstanceOf(NotFoundException.class);
        assertThat(Files.exists(state())).isTrue();
    }

    @Test
    void anUploadUnderTheSameNameTakesThePlaceOfTheUnreadableEntry() throws IOException {
        ConfigFixture first = ConfigFixture.mock(directory);
        StoredEnvironment sandbox = first.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        Files.delete(directory.resolve(sandbox.file()));
        ConfigFixture started = ConfigFixture.mock(directory);
        assertThat(started.store.unreadable()).hasSize(1);

        started.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);

        assertThat(started.store.unreadable()).isEmpty();
        // Two entries of one name would stop the next start: the old one is gone from store.json.
        ConfigFixture again = ConfigFixture.mock(directory);
        assertThat(again.store.snapshot().environments()).extracting(StoredEnvironment::name).containsExactly("Onyx Sandbox");
        assertThat(again.store.unreadable()).isEmpty();
    }

    @Test
    void aRememberedProductionAddressThatCannotBeParsedIsKeptAsWrittenAndStillProtectsItsHost() throws IOException {
        ConfigFixture first = ConfigFixture.mock(directory);
        first.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        // What hunt-files 5 left in store.json: a host with a space in it, which stopped every start.
        JsonNode root = JsonSafe.parse(Files.readString(state())).node();
        tools.jackson.databind.node.ArrayNode known = ((tools.jackson.databind.node.ObjectNode) root).putArray("productionDestinations");
        known.addObject().put("environment", "Old Production").put("destination", "api prodvendor.example.invalid");
        known.addObject().put("environment", "Templated Production").put("destination", "https://{{host}}/fhir");
        known.addObject().put("destination", "https://unnamed-prod.example.invalid/fhir");
        Files.writeString(state(), JsonSafe.pretty(root));

        ConfigFixture started = ConfigFixture.mock(directory);

        assertThat(started.store.snapshot().remembered()).extracting(known1 -> known1.environment() + " " + known1.destination().shown())
                .containsExactly("Old Production api prodvendor.example.invalid", "Templated Production https://{{host}}/fhir",
                        ConfigStore.UNNAMED_PRODUCTION + " https://unnamed-prod.example.invalid/fhir");
        assertThat(started.store.snapshot().productionOwners(UrlNormalProbe.of("https://unnamed-prod.example.invalid/fhir/Claim")))
                .containsExactly(ConfigStore.UNNAMED_PRODUCTION);
        // Written back as it was, so the next start reads the same.
        started.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        assertThat(Files.readString(state())).contains("\"api prodvendor.example.invalid\"");
        assertThat(ConfigFixture.mock(directory).store.snapshot().remembered()).hasSize(3);
    }

    @Test
    void theEffectiveViewListsTheFilesAndRemoveAnswersOverTheServer() throws Exception {
        ConfigFixture first = ConfigFixture.mock(directory);
        StoredEnvironment sandbox = first.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        Files.writeString(directory.resolve(sandbox.file()), "not json");
        ConfigFixture started = ConfigFixture.mock(directory);
        MockMvc mvc = TestMvc.standalone(new ConfigController(started.service));

        JsonNode effective = JsonSafe.parse(mvc.perform(get("/api/config/effective")).andReturn().getResponse().getContentAsString()).node();
        assertThat(JsonSafe.text(JsonSafe.elements(effective, "unreadable").get(0), "file")).isEqualTo(sandbox.file());
        assertThat(JsonSafe.text(JsonSafe.elements(effective, "unreadable").get(0), "line")).startsWith("The file ");

        MockHttpServletResponse unknown = mvc.perform(post("/api/config/unreadable/remove").contentType(MediaType.APPLICATION_JSON)
                .content("{\"file\": \"environments/none.json\"}")).andReturn().getResponse();
        assertThat(unknown.getStatus()).isEqualTo(404);
        MockHttpServletResponse misspelt = mvc.perform(post("/api/config/unreadable/remove").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fiel\": \"x\"}")).andReturn().getResponse();
        assertThat(misspelt.getStatus()).isEqualTo(422);

        MockHttpServletResponse removed = mvc.perform(post("/api/config/unreadable/remove").contentType(MediaType.APPLICATION_JSON)
                .content("{\"file\": \"" + sandbox.file() + "\"}")).andReturn().getResponse();
        assertThat(removed.getStatus()).isEqualTo(200);
        JsonNode answer = JsonSafe.parse(removed.getContentAsString()).node();
        assertThat(JsonSafe.text(answer, "removed")).isEqualTo(sandbox.file());
        assertThat(JsonSafe.text(answer, "movedTo")).endsWith(directory.resolve(sandbox.file()).getFileName().toString());
        assertThat(JsonSafe.longValue(answer, "epoch")).isEqualTo(started.store.epoch());
    }

    /** Reads an address the way the guardrail does, for asking who owns it. */
    private static final class UrlNormalProbe {
        static org.p32h.interop.epa.pave.postman.UrlNormal.Destination of(String url) {
            return org.p32h.interop.epa.pave.postman.UrlNormal.destination(url);
        }
    }
}
