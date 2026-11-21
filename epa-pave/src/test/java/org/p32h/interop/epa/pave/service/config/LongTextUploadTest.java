package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.PostmanVariable;
import org.p32h.interop.epa.pave.service.config.ConfigViews.CollectionView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.RequestDetail;

/**
 * A long text in a Postman file, the failure of D-275: the upload of a collection with one quoted
 * text of some 1,300 characters threw StackOverflowError after the collection had been stored,
 * and every later reading of it threw again. These tests hold the three things that now hold: the
 * upload works, a check that fails stores nothing, and a store the old version wrote opens.
 */
class LongTextUploadTest {

    private static final String SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    /** A text as long as an encoded attachment of a few kilobytes, the shape real exports carry. */
    private static final String LONG = "QUJD".repeat(750);

    @TempDir
    Path directory;

    private static byte[] collection(String name, String body, String exampleBody) {
        String example = exampleBody == null ? "[]" : "[{\"name\": \"saved\", \"code\": 200, \"header\": [], \"body\": " + quoted(exampleBody) + "}]";
        String json = "{\"info\": {\"name\": " + quoted(name) + ", \"schema\": \"" + SCHEMA + "\"}, \"item\": [{\"name\": \"Submit\", "
                + "\"request\": {\"method\": \"POST\", \"header\": [], \"url\": \"https://{{pasBase}}/Claim/$submit\", \"body\": {\"mode\": "
                + "\"raw\", \"raw\": " + quoted(body) + "}}, \"response\": " + example + "}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String quoted(String text) {
        return org.p32h.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(text);
    }

    private static String attachment(String data) {
        return "{\"resourceType\": \"Binary\", \"contentType\": \"application/pdf\", \"data\": \"" + data + "\"}";
    }

    private static List<Path> filesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    @Test
    void aCollectionWithALongTextInItsBodyAndItsExampleUploadsAndReadsBack() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        String withCredential = "{\"client_secret\": \"ZZLONGTEXTSECRET-1\", \"attachment\": \"" + "A".repeat(2_000_000) + "\"}";

        ConfigViews.CollectionUploaded uploaded = fixture.service.uploadCollection("long.json",
                collection("Long texts", withCredential, attachment(LONG)));

        // The scan still does what it is for: the credential beside a text of two million characters is found and named.
        assertThat(uploaded.warnings()).anySatisfy(warning -> assertThat(warning)
                .startsWith("The request \"Submit\" holds a credential typed into the collection, in the field client_secret of its body."));
        assertThat(String.join(" ", uploaded.warnings())).doesNotContain("ZZLONGTEXTSECRET-1");
        CollectionView listed = fixture.service.collections().collections().get(0);
        RequestDetail detail = fixture.service.request(listed.id(), listed.requests().get(0).id());
        // The detail shows the request as it will be resolved: the typed value stands behind a placeholder of its own.
        assertThat(detail.body()).startsWith("{\"client_secret\": \"{{client_secret as typed}}\", \"attachment\": \"AAAA")
                .doesNotContain("ZZLONGTEXTSECRET-1");
        assertThat(fixture.service.effective(null).operations()).isNotEmpty();
    }

    @Test
    void aCheckThatFailsAfterTheFileWasReadStoresNothing() throws IOException {
        ConfigFixture first = ConfigFixture.mock(directory);
        first.collectionOf("Kept", ConfigFixture.items(ConfigFixture.item("Ping", "GET", "https://{{base}}/metadata")));
        byte[] stateBefore = Files.readAllBytes(directory.resolve(ConfigStore.STATE_FILE));
        List<Path> filesBefore = filesUnder(directory);

        // A store whose last check before the commit throws, as the scan for literal credentials once did.
        ConfigStore failing = new ConfigStore(ConfigStoreProperties.of(Map.of("directory", directory.toString())), ConfigFixture.CLOCK) {
            @Override
            List<String> memberDataWarnings(String what, List<PostmanVariable> variables, List<PostmanRequest> requests, String file) {
                throw new IllegalStateException("planted failure of a check");
            }
        };

        assertThatThrownBy(() -> failing.uploadCollection("long.json", collection("Long texts", attachment(LONG), null)))
                .hasMessage("planted failure of a check");
        assertThatThrownBy(() -> failing.uploadEnvironment("env.json", ("{\"name\": \"Sandbox\", \"values\": [{\"key\": \"base\", "
                + "\"value\": \"sandbox.example.invalid\", \"enabled\": true}]}").getBytes(StandardCharsets.UTF_8), EnvironmentKind.SANDBOX, false))
                .hasMessage("planted failure of a check");

        assertThat(failing.snapshot().collections()).extracting(StoredCollection::name).containsExactly("Kept");
        assertThat(failing.snapshot().environments()).isEmpty();
        assertThat(Files.readAllBytes(directory.resolve(ConfigStore.STATE_FILE))).isEqualTo(stateBefore);
        assertThat(filesUnder(directory)).isEqualTo(filesBefore);
        ConfigFixture restarted = ConfigFixture.mock(directory);
        assertThat(restarted.store.snapshot().collections()).extracting(StoredCollection::name).containsExactly("Kept");
    }

    /**
     * The answer to an upload is built from the configuration the upload leaves, before it is
     * written: a view that cannot be built stops the upload, file for file, instead of answering
     * "internal error" for a file that was stored.
     */
    @Test
    void theAnswerToAnUploadIsBuiltBeforeTheCommitAndAViewThatFailsStoresNothing() throws IOException {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.collectionOf("Kept", ConfigFixture.items(ConfigFixture.item("Ping", "GET", "https://{{base}}/metadata")));
        byte[] stateBefore = Files.readAllBytes(directory.resolve(ConfigStore.STATE_FILE));
        List<Path> filesBefore = filesUnder(directory);

        assertThatThrownBy(() -> fixture.store.uploadCollection("long.json", collection("Long texts", attachment(LONG), null),
                (stored, after) -> {
                    throw new IllegalStateException("planted failure of the view");
                })).hasMessage("planted failure of the view");
        assertThatThrownBy(() -> fixture.store.uploadEnvironment("env.json", ("{\"name\": \"Sandbox\", \"values\": [{\"key\": \"base\", "
                + "\"value\": \"sandbox.example.invalid\", \"enabled\": true}]}").getBytes(StandardCharsets.UTF_8), EnvironmentKind.SANDBOX,
                false, (stored, after) -> {
                    throw new IllegalStateException("planted failure of the view");
                })).hasMessage("planted failure of the view");

        assertThat(fixture.store.snapshot().collections()).extracting(StoredCollection::name).containsExactly("Kept");
        assertThat(fixture.store.snapshot().environments()).isEmpty();
        assertThat(Files.readAllBytes(directory.resolve(ConfigStore.STATE_FILE))).isEqualTo(stateBefore);
        assertThat(filesUnder(directory)).isEqualTo(filesBefore);

        // Built before the commit, the answer is still the view every later reading gives.
        ConfigViews.CollectionUploaded uploaded = fixture.service.uploadCollection("long.json", collection("Long texts", attachment(LONG), null));
        CollectionView listed = fixture.service.collections().collections().stream().filter(view -> view.name().equals("Long texts"))
                .findFirst().orElseThrow();
        assertThat(org.p32h.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(uploaded.collection()))
                .isEqualTo(org.p32h.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(listed));
        ConfigViews.EnvironmentUploaded environment = fixture.service.uploadEnvironment("env.json", ("{\"name\": \"Sandbox\", \"values\": "
                + "[{\"key\": \"base\", \"value\": \"sandbox.example.invalid\", \"enabled\": true}]}").getBytes(StandardCharsets.UTF_8),
                EnvironmentKind.SANDBOX, false);
        assertThat(org.p32h.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(environment.environment()))
                .isEqualTo(org.p32h.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(fixture.service.environments().environments().get(0)));
    }

    /**
     * A collection nested deeper than the parser reads is refused with that reason. The limit is
     * Jackson's own nesting limit, 500 levels as this application configures it, and each folder
     * is two levels: an object and its list of items.
     */
    @Test
    void aCollectionNestedDeeperThanTheParserReadsIsRefusedWithThatReason() throws IOException {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        assertThat(org.p32h.interop.epa.pave.model.Nesting.limit()).isEqualTo(500);
        StringBuilder folders = new StringBuilder();
        int depth = 300;
        folders.append("{\"name\": \"leaf\", \"request\": {\"method\": \"GET\", \"url\": \"https://{{base}}/metadata\"}}");
        for (int i = 0; i < depth; i++) {
            folders.insert(0, "{\"name\": \"f" + i + "\", \"item\": [").append("]}");
        }
        byte[] deep = ("{\"info\": {\"name\": \"Deep\", \"schema\": \"" + SCHEMA + "\"},\n\"item\": [" + folders + "]}")
                .getBytes(StandardCharsets.UTF_8);
        List<Path> filesBefore = filesUnder(directory);

        assertThatThrownBy(() -> fixture.service.uploadCollection("deep.json", deep))
                .isInstanceOf(org.p32h.interop.epa.pave.service.error.BadInputException.class)
                .hasMessageStartingWith("This file nests its objects and lists deeper than the 500 levels this tool reads: the level past "
                        + "that limit opens at line 2, column ")
                .hasMessageEndingWith(". It was not read, and nothing was stored.");
        assertThat(filesUnder(directory)).isEqualTo(filesBefore);

        // 240 folders are 482 levels with the collection around them: read as before.
        StringBuilder fewer = new StringBuilder("{\"name\": \"leaf\", \"request\": {\"method\": \"GET\", \"url\": \"https://{{base}}/m\"}}");
        for (int i = 0; i < 240; i++) {
            fewer.insert(0, "{\"name\": \"f" + i + "\", \"item\": [").append("]}");
        }
        assertThat(fixture.service.uploadCollection("fewer.json", ("{\"info\": {\"name\": \"Fewer\", \"schema\": \"" + SCHEMA
                + "\"}, \"item\": [" + fewer + "]}").getBytes(StandardCharsets.UTF_8)).collection().requests()).hasSize(1);
    }

    /**
     * The operator's working folder may hold a collection the old version stored and then threw
     * on, once for each attempt under another name. The old version wrote the store in the format
     * this one writes, and the bytes of the collection are the bytes that were uploaded: the
     * folder is built here by uploading and restarting, as the old version left it.
     */
    @Test
    void aStoreThatHoldsCollectionsTheOldVersionThrewOnOpensListsAndDeletesThem() {
        ConfigFixture before = ConfigFixture.mock(directory);
        before.service.uploadCollection("export.json", collection("Vendor PAS", attachment(LONG), attachment(LONG)));
        before.service.uploadCollection("export (1).json", collection("Vendor PAS copy", attachment(LONG + LONG), attachment(LONG)));

        ConfigFixture after = ConfigFixture.mock(directory);
        List<CollectionView> listed = after.service.collections().collections();
        assertThat(listed).extracting(CollectionView::name).containsExactly("Vendor PAS", "Vendor PAS copy");
        for (CollectionView each : listed) {
            assertThat(after.service.request(each.id(), each.requests().get(0).id()).examples()).hasSize(1);
        }
        assertThat(after.service.effective(null).operations()).isNotEmpty();

        after.service.removeCollection(listed.get(1).id());
        assertThat(ConfigFixture.mock(directory).service.collections().collections()).extracting(CollectionView::name)
                .containsExactly("Vendor PAS");
    }
}
