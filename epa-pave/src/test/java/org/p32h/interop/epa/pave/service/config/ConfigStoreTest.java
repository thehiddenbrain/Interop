package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import tools.jackson.databind.JsonNode;

class ConfigStoreTest {

    @TempDir
    Path directory;

    private static List<Path> filesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static StoredCollection.Mapped named(StoredCollection collection, String requestName) {
        return collection.requests().stream().filter(mapped -> mapped.request().name().equals(requestName)).findFirst().orElseThrow();
    }

    private JsonNode state() throws IOException {
        return JsonSafe.parse(Files.readString(directory.resolve(ConfigStore.STATE_FILE))).node();
    }

    // ------------------------------------------------------------------------------------------
    // What is kept, and that it is kept across a restart
    // ------------------------------------------------------------------------------------------

    @Test
    void anEmptyStoreStartsWithNothingAndCreatesItsDirectories() {
        Path fresh = directory.resolve("not/there/yet");
        ConfigFixture fixture = ConfigFixture.mock(fresh);

        assertThat(Files.isDirectory(fresh.resolve(ConfigStore.ENVIRONMENTS))).isTrue();
        assertThat(Files.isDirectory(fresh.resolve(ConfigStore.COLLECTIONS))).isTrue();
        ConfigSnapshot snapshot = fixture.store.snapshot();
        assertThat(snapshot.environments()).isEmpty();
        assertThat(snapshot.collections()).isEmpty();
        assertThat(snapshot.active()).isNull();
        assertThat(snapshot.activeRef().active()).isFalse();
        assertThat(fixture.store.problems()).isEmpty();
        // Nothing is written until something is stored.
        assertThat(Files.exists(fresh.resolve(ConfigStore.STATE_FILE))).isFalse();
    }

    @Test
    void anUploadedFileIsKeptByteForByte() throws IOException {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredEnvironment environment = fixture.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        StoredCollection collection = fixture.collection(PostmanFiles.VENDOR_COLLECTION);

        assertThat(Files.readAllBytes(directory.resolve(environment.file()))).isEqualTo(PostmanFiles.bytes(PostmanFiles.SANDBOX_ENVIRONMENT));
        assertThat(Files.readAllBytes(directory.resolve(collection.file()))).isEqualTo(PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION));
        assertThat(environment.file()).startsWith("environments/onyx-sandbox-").endsWith(".json");
        assertThat(collection.file()).startsWith("collections/onyx-sandbox-epa-").endsWith(".json");
        assertThat(environment.originalFileName()).isEqualTo(PostmanFiles.SANDBOX_ENVIRONMENT);
        assertThat(environment.uploadedAt()).isEqualTo("2026-01-05T15:04:05Z");
        // The state file and the two uploads, and no temporary file left beside them.
        assertThat(filesUnder(directory)).extracting(path -> directory.relativize(path).toString().replace('\\', '/'))
                .containsExactlyInAnyOrder(collection.file(), environment.file(), ConfigStore.STATE_FILE);
    }

    @Test
    void everythingButTheUnlockSurvivesARestart() {
        ConfigFixture first = ConfigFixture.mock(directory);
        first.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        first.environment(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        first.activate(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        StoredCollection vendor = first.collection(PostmanFiles.VENDOR_COLLECTION);
        first.collection(PostmanFiles.NESTED_COLLECTION);
        first.remap(vendor, "inquiry Happy Path", EpaOperation.UNMAPPED);
        long lastEpoch = first.store.epoch();

        // A second store on the same directory is what a restart is.
        ConfigFixture second = ConfigFixture.mock(directory);
        ConfigSnapshot snapshot = second.store.snapshot();

        assertThat(snapshot.environments()).extracting(StoredEnvironment::name).containsExactly("Onyx Sandbox", "Onyx Production", "Onyx UAT");
        assertThat(snapshot.environments()).extracting(StoredEnvironment::kind)
                .containsExactly(EnvironmentKind.SANDBOX, EnvironmentKind.PROD, EnvironmentKind.UAT);
        assertThat(snapshot.activeName()).isEqualTo("Onyx UAT");
        assertThat(snapshot.activeRef().kind()).isEqualTo(EnvironmentKind.UAT);
        assertThat(snapshot.byName("Onyx Sandbox").content().inForce().get("clientId").value()).isEqualTo("pave-sandbox-client");
        assertThat(snapshot.byName("Onyx Sandbox").originalFileName()).isEqualTo(PostmanFiles.SANDBOX_ENVIRONMENT);
        assertThat(snapshot.byName("Onyx Sandbox").uploadedAt()).isEqualTo("2026-01-05T15:04:05Z");

        assertThat(snapshot.collections()).extracting(StoredCollection::name).containsExactly("Onyx Sandbox ePA", "Nested sample");
        StoredCollection reloaded = snapshot.collection(vendor.id());
        assertThat(reloaded.requests()).hasSize(13);
        assertThat(reloaded.requests()).extracting(mapped -> mapped.request().id())
                .containsExactlyElementsOf(vendor.requests().stream().map(mapped -> mapped.request().id()).toList());
        assertThat(named(reloaded, "inquiry Happy Path").correction()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(named(reloaded, "inquiry Happy Path").effective()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(named(reloaded, "inquiry Happy Path").detection().operation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(named(reloaded, "submit Happy Path").correction()).isNull();
        // The epoch never goes back, so a tab left open across the restart cannot hold a current one.
        assertThat(second.store.epoch()).isGreaterThan(lastEpoch);
    }

    @Test
    void theStateFileHoldsNamesKindsAndCorrectionsAndNoVariableValue() throws IOException {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        fixture.remap(vendor, "inquiry Happy Path", EpaOperation.UNMAPPED);

        String text = Files.readString(directory.resolve(ConfigStore.STATE_FILE));
        JsonNode state = JsonSafe.parse(text).node();

        assertThat(JsonSafe.text(state, "activeEnvironment")).isEqualTo("Onyx Sandbox");
        assertThat(JsonSafe.longValue(state, "epoch")).isEqualTo(fixture.store.epoch());
        JsonNode environment = JsonSafe.elements(state, "environments").get(0);
        assertThat(JsonSafe.text(environment, "name")).isEqualTo("Onyx Sandbox");
        assertThat(JsonSafe.text(environment, "kind")).isEqualTo("sandbox");
        JsonNode corrections = JsonSafe.child(JsonSafe.elements(state, "collections").get(0), "corrections");
        assertThat(JsonSafe.text(corrections, ConfigFixture.ref(vendor, "inquiry Happy Path").requestId())).isEqualTo("UNMAPPED");
        assertThat(JsonSafe.properties(corrections)).hasSize(1);
        // The uploads hold the values. The state file holds none of them.
        assertThat(text).doesNotContain("placeholder-sandbox-secret").doesNotContain("placeholder-tenant-value")
                .doesNotContain("sandbox.onyx.example.invalid").doesNotContain("unlock");
    }

    @Test
    void theActiveEnvironmentSwitchesAtRuntimeAndTheChoiceSurvivesARestart() {
        ConfigFixture first = ConfigFixture.mock(directory);
        first.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        first.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        // Uploading never activates anything.
        assertThat(first.store.snapshot().active()).isNull();

        first.store.activate("Onyx Sandbox");
        assertThat(first.gateway.activeEnvironment().name()).isEqualTo("Onyx Sandbox");
        first.store.activate("Onyx UAT");
        assertThat(first.gateway.activeEnvironment().name()).isEqualTo("Onyx UAT");
        assertThat(first.gateway.activeEnvironment().kind()).isEqualTo(EnvironmentKind.UAT);

        assertThat(ConfigFixture.mock(directory).gateway.activeEnvironment().name()).isEqualTo("Onyx UAT");
    }

    @Test
    void anEnvironmentIsFoundByItsNameOrByItsId() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredEnvironment slashed = fixture.environmentOf("Team A/B; test", EnvironmentKind.SANDBOX, "baseUrl", "https://a.example.invalid");

        // The name cannot travel in one segment of a url path. The id can.
        assertThat(slashed.id()).matches("team-a-b-test-[0-9a-f]{8}");
        assertThat(fixture.store.activate(slashed.id()).activeName()).isEqualTo("Team A/B; test");
        assertThat(fixture.store.snapshot().environment("Team A/B; test")).isSameAs(fixture.store.snapshot().environment(slashed.id()));
        assertThatThrownBy(() -> fixture.store.activate("team a/b; test")).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------------------------------
    // Mapping corrections
    // ------------------------------------------------------------------------------------------

    @Test
    void aCorrectionSurvivesAReUploadOfTheSameFileAndOfANewerExport() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        fixture.remap(vendor, "inquiry Happy Path", EpaOperation.UNMAPPED);

        ConfigStore.CollectionUpload same = fixture.store.uploadCollection("again.json", PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION));
        assertThat(same.replaced()).isTrue();
        assertThat(same.collection().id()).isEqualTo(vendor.id());
        assertThat(named(same.collection(), "inquiry Happy Path").correction()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(fixture.store.snapshot().collections()).hasSize(1);

        // A newer export in which the vendor changed the url and the body of that request.
        String newer = PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION).replace("{{baseUrl}}/Claim/$inquire", "{{pasUrl}}/Claim/$inquire")
                .replace("claim-placeholder-1", "claim-placeholder-2");
        ConfigStore.CollectionUpload edited = fixture.store.uploadCollection("newer.json", newer.getBytes(StandardCharsets.UTF_8));
        assertThat(named(edited.collection(), "inquiry Happy Path").request().url()).isEqualTo("{{pasUrl}}/Claim/$inquire");
        assertThat(named(edited.collection(), "inquiry Happy Path").correction()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(edited.warnings()).noneMatch(warning -> warning.contains("was dropped"));

        // And it is still there after a restart on top of the re-upload.
        assertThat(named(ConfigFixture.mock(directory).store.snapshot().collection(vendor.id()), "inquiry Happy Path").correction())
                .isEqualTo(EpaOperation.UNMAPPED);
    }

    @Test
    void aCorrectionForARequestTheNewFileNoLongerHasIsDroppedAndTheUploadSaysSo() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        fixture.remap(vendor, "inquiry Happy Path", EpaOperation.UNMAPPED);

        String renamed = PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION).replace("\"inquiry Happy Path\"", "\"status check\"");
        ConfigStore.CollectionUpload upload = fixture.store.uploadCollection("renamed.json", renamed.getBytes(StandardCharsets.UTF_8));

        assertThat(upload.warnings()).contains("The correction that mapped \"PAS / inquiry Happy Path\" to Unmapped request was dropped, "
                + "because the new file has no request with that name in that folder.");
        assertThat(upload.collection().requests()).allMatch(mapped -> mapped.correction() == null);
    }

    @Test
    void choosingWhatDetectionProposedRemovesTheCorrection() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        String requestId = ConfigFixture.ref(vendor, "inquiry Happy Path").requestId();

        ConfigStore.MappingChange corrected = fixture.store.correctMapping(vendor.id(), requestId, EpaOperation.UNMAPPED);
        assertThat(corrected.changed()).isTrue();
        assertThat(corrected.request().correction()).isEqualTo(EpaOperation.UNMAPPED);

        ConfigStore.MappingChange again = fixture.store.correctMapping(vendor.id(), requestId, EpaOperation.UNMAPPED);
        assertThat(again.changed()).isFalse();
        assertThat(again.epoch()).isEqualTo(corrected.epoch());

        ConfigStore.MappingChange back = fixture.store.correctMapping(vendor.id(), requestId, EpaOperation.PAS_INQUIRE);
        assertThat(back.changed()).isTrue();
        assertThat(back.request().correction()).isNull();
        assertThat(back.request().effective()).isEqualTo(EpaOperation.PAS_INQUIRE);
    }

    @Test
    void aCorrectionIsRefusedForAnOperationNoRequestCanBeAndForAnIdNobodyHas() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        String requestId = ConfigFixture.ref(vendor, "inquiry Happy Path").requestId();
        long epoch = fixture.store.epoch();

        assertThatThrownBy(() -> fixture.store.correctMapping(vendor.id(), requestId, EpaOperation.DTR_RESPONSE))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.fieldErrors()).hasSize(1);
                    assertThat(e.fieldErrors().get(0).field()).isEqualTo("operation");
                    assertThat(e.fieldErrors().get(0).message()).startsWith("DTR questionnaire response is a step completed inside this tool.");
                });
        assertThatThrownBy(() -> fixture.store.correctMapping(vendor.id(), requestId, null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> fixture.store.correctMapping(vendor.id(), "r-000000000000", EpaOperation.PAS_INQUIRE))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("has no request with the id \"r-000000000000\"");
        assertThatThrownBy(() -> fixture.store.correctMapping("no-such-collection", requestId, EpaOperation.PAS_INQUIRE))
                .isInstanceOf(NotFoundException.class).hasMessage("There is no uploaded collection with the id \"no-such-collection\".");
        // A notification is what the vendor's own sample is detected as, so it can be chosen.
        assertThat(fixture.store.correctMapping(vendor.id(), requestId, EpaOperation.SUBSCRIPTION_NOTIFICATION).request().effective())
                .isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(fixture.store.epoch()).isEqualTo(epoch + 1);
    }

    // ------------------------------------------------------------------------------------------
    // The epoch
    // ------------------------------------------------------------------------------------------

    @Test
    void theEpochMovesOnEveryChangeToWhatIsStoredAndOnNothingElse() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        List<Long> epochs = new ArrayList<>();
        epochs.add(fixture.store.epoch());

        fixture.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        epochs.add(fixture.store.epoch());
        fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        epochs.add(fixture.store.epoch());
        fixture.store.activate("Onyx Sandbox");
        epochs.add(fixture.store.epoch());
        fixture.store.activate("Onyx UAT");
        epochs.add(fixture.store.epoch());
        // Replacing an environment with the very same file is still a replacement.
        fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        epochs.add(fixture.store.epoch());
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        epochs.add(fixture.store.epoch());
        fixture.remap(vendor, "inquiry Happy Path", EpaOperation.UNMAPPED);
        epochs.add(fixture.store.epoch());
        fixture.store.removeCollection(vendor.id());
        epochs.add(fixture.store.epoch());
        fixture.store.removeEnvironment("Onyx Sandbox");
        epochs.add(fixture.store.epoch());

        assertThat(epochs).doesNotHaveDuplicates().isSorted();
        assertThat(epochs).hasSize(10);

        // Reading changes nothing, activating what is already active changes nothing, and neither does a refused upload.
        long settled = fixture.store.epoch();
        fixture.store.activate("Onyx UAT");
        fixture.store.snapshot();
        fixture.gateway.activeEnvironment();
        fixture.gateway.writeAccess(null);
        assertThatThrownBy(() -> fixture.collection(PostmanFiles.TRUNCATED_COLLECTION)).isInstanceOf(BadInputException.class);
        assertThatThrownBy(() -> fixture.store.activate("No such environment")).isInstanceOf(NotFoundException.class);
        assertThat(fixture.store.epoch()).isEqualTo(settled);
        assertThat(fixture.gateway.epoch()).isEqualTo(settled);
    }

    // ------------------------------------------------------------------------------------------
    // Replacing and removing
    // ------------------------------------------------------------------------------------------

    @Test
    void uploadingAnEnvironmentOfTheSameNameReplacesItAndRemovesTheOldFile() throws IOException {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredEnvironment original = fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        String edited = PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT).replace("pave-sandbox-client", "pave-sandbox-client-2");

        ConfigStore.EnvironmentUpload upload = fixture.store.uploadEnvironment("edited.json", edited.getBytes(StandardCharsets.UTF_8),
                EnvironmentKind.SANDBOX, false);

        assertThat(upload.replaced()).isTrue();
        assertThat(upload.environment().id()).isEqualTo(original.id());
        assertThat(upload.environment().file()).isNotEqualTo(original.file());
        assertThat(Files.exists(directory.resolve(original.file()))).isFalse();
        assertThat(fixture.store.snapshot().environments()).hasSize(1);
        // Replacing the active environment leaves it the active one, with the new values in force.
        assertThat(fixture.store.snapshot().activeName()).isEqualTo("Onyx Sandbox");
        assertThat(fixture.store.snapshot().active().content().inForce().get("clientId").value()).isEqualTo("pave-sandbox-client-2");
        assertThat(filesUnder(directory.resolve(ConfigStore.ENVIRONMENTS))).hasSize(1);
    }

    @Test
    void removingTheActiveEnvironmentLeavesNoneActiveAndDeletesItsFile() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredEnvironment sandbox = fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);

        ConfigStore.Removal removal = fixture.store.removeEnvironment("Onyx Sandbox");

        assertThat(removal.name()).isEqualTo("Onyx Sandbox");
        assertThat(removal.wasActive()).isTrue();
        assertThat(removal.warnings()).isEmpty();
        // No other environment is put in its place.
        assertThat(fixture.store.snapshot().active()).isNull();
        assertThat(fixture.store.snapshot().environments()).extracting(StoredEnvironment::name).containsExactly("Onyx UAT");
        assertThat(Files.exists(directory.resolve(sandbox.file()))).isFalse();
        assertThat(ConfigFixture.mock(directory).store.snapshot().active()).isNull();

        assertThat(fixture.store.removeEnvironment("Onyx UAT").wasActive()).isFalse();
        assertThatThrownBy(() -> fixture.store.removeEnvironment("Onyx UAT")).isInstanceOf(NotFoundException.class)
                .hasMessage("There is no uploaded environment named \"Onyx UAT\".");
    }

    @Test
    void removingACollectionRemovesItsFileAndItsCorrections() throws IOException {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        fixture.remap(vendor, "inquiry Happy Path", EpaOperation.UNMAPPED);

        assertThat(fixture.store.removeCollection(vendor.id()).name()).isEqualTo("Onyx Sandbox ePA");

        assertThat(fixture.store.snapshot().collections()).isEmpty();
        assertThat(Files.exists(directory.resolve(vendor.file()))).isFalse();
        assertThat(JsonSafe.elements(state(), "collections")).isEmpty();
        assertThatThrownBy(() -> fixture.store.removeCollection(vendor.id())).isInstanceOf(NotFoundException.class);
        // Uploaded afresh, it starts from detection again.
        assertThat(named(fixture.collection(PostmanFiles.VENDOR_COLLECTION), "inquiry Happy Path").correction()).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // The kind of an environment
    // ------------------------------------------------------------------------------------------

    @Test
    void theKindIsRequiredAndIsNeverGuessed() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        assertThatThrownBy(() -> fixture.store.uploadEnvironment("x.json", PostmanFiles.bytes(PostmanFiles.SANDBOX_ENVIRONMENT), null, false))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).field()).isEqualTo("kind"));
        assertThat(fixture.store.snapshot().environments()).isEmpty();
    }

    @Test
    void anEnvironmentWhoseNameSaysProductionIsNotStoredAsASandboxWithoutTheAcknowledgement() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        byte[] production = PostmanFiles.bytes(PostmanFiles.PRODUCTION_ENVIRONMENT);

        for (EnvironmentKind declared : new EnvironmentKind[] {EnvironmentKind.SANDBOX, EnvironmentKind.UAT}) {
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("p.json", production, declared, false))
                    .isInstanceOfSatisfying(ValidationException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("This environment looks like production and was declared as " + declared.wire()
                                + ". It was not stored.");
                        assertThat(e.fieldErrors().get(0).field()).isEqualTo("acknowledgeNotProd");
                        assertThat(e.fieldErrors().get(0).message()).contains("its name is \"Onyx Production\"")
                                .contains("upload it again with acknowledgeNotProd set to true").contains("declare its kind as prod");
                    });
        }
        assertThat(fixture.store.snapshot().environments()).isEmpty();
        assertThat(Files.exists(directory.resolve(ConfigStore.STATE_FILE))).isFalse();

        // Declared as what it is, it needs no acknowledgement.
        ConfigStore.EnvironmentUpload asProd = fixture.store.uploadEnvironment("p.json", production, EnvironmentKind.PROD, false);
        assertThat(asProd.environment().kind()).isEqualTo(EnvironmentKind.PROD);
        assertThat(asProd.environment().acknowledgedNotProd()).isFalse();
        assertThat(asProd.environment().productionSigns()).containsExactly("its name is \"Onyx Production\"");
    }

    @Test
    void anEnvironmentWhoseUrlSaysProductionIsRefusedTooAndTheUrlIsNotQuoted() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        byte[] misnamed = PostmanFiles.bytes(PostmanFiles.SANDBOX_IN_NAME_ONLY);

        assertThatThrownBy(() -> fixture.store.uploadEnvironment("s.json", misnamed, EnvironmentKind.SANDBOX, false))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    String said = e.getMessage() + e.fieldErrors();
                    // "PROD" in capitals, in a variable value, with a name that says sandbox.
                    assertThat(said).contains("the variable baseUrl holds a url that contains \"prod\"");
                    assertThat(said).doesNotContain("onyx.example.invalid").doesNotContain("crdUrl");
                });

        // With the acknowledgement it is stored as declared, and the store remembers that it was asked.
        ConfigStore.EnvironmentUpload acknowledged = fixture.store.uploadEnvironment("s.json", misnamed, EnvironmentKind.SANDBOX, true);
        assertThat(acknowledged.environment().kind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(acknowledged.environment().acknowledgedNotProd()).isTrue();
        StoredEnvironment reloaded = ConfigFixture.mock(directory).store.snapshot().byName("Team Sandbox");
        assertThat(reloaded.acknowledgedNotProd()).isTrue();
        assertThat(reloaded.productionSigns()).containsExactly("the variable baseUrl holds a url that contains \"prod\"");
    }

    @Test
    void onlyAValueThatLooksLikeAUrlIsReadForSignsOfProduction() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        // "product" is not a url. Neither is a sentence that mentions production.
        ConfigStore.EnvironmentUpload plain = fixture.store.uploadEnvironment("a.json", ("{\"name\": \"Team A\", \"values\": ["
                + "{\"key\": \"line\", \"value\": \"product catalogue\"}, {\"key\": \"note\", \"value\": \"not prod\"}]}")
                .getBytes(StandardCharsets.UTF_8), EnvironmentKind.SANDBOX, false);
        assertThat(plain.environment().productionSigns()).isEmpty();
        assertThat(plain.environment().acknowledgedNotProd()).isFalse();

        for (String url : new String[] {"https://api.prod.example.invalid", "api.Production.example.invalid/fhir", "www.prod-payer.example.invalid",
                "https://{{prodHost}}/fhir"}) {
            byte[] file = ("{\"name\": \"Team B\", \"values\": [{\"key\": \"u\", \"value\": \"" + url + "\"}]}").getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("b.json", file, EnvironmentKind.UAT, false)).as(url)
                    .isInstanceOf(ValidationException.class);
        }
        assertThat(ProductionSigns.looksLikeUrl("product catalogue")).isFalse();
        assertThat(ProductionSigns.looksLikeUrl("api.example.invalid:8443/fhir")).isTrue();
    }

    @Test
    void replacingAStoredProductionEnvironmentWithAnotherKindNeedsTheAcknowledgement() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        // Nothing in this file says production. Only its declared kind does.
        byte[] file = "{\"name\": \"Payer Live\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"https://api.example.invalid/fhir\"}]}"
                .getBytes(StandardCharsets.UTF_8);
        fixture.store.uploadEnvironment("live.json", file, EnvironmentKind.PROD, false);
        fixture.store.activate("Payer Live");

        assertThatThrownBy(() -> fixture.store.uploadEnvironment("live.json", file, EnvironmentKind.SANDBOX, false))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).message())
                        .contains("an environment named \"Payer Live\" is already stored here as prod, and this upload would replace it"));
        assertThat(fixture.store.snapshot().active().kind()).isEqualTo(EnvironmentKind.PROD);

        // Replacing it as production again asks nothing.
        assertThat(fixture.store.uploadEnvironment("live.json", file, EnvironmentKind.PROD, false).replaced()).isTrue();
        ConfigStore.EnvironmentUpload downgraded = fixture.store.uploadEnvironment("live.json", file, EnvironmentKind.SANDBOX, true);
        assertThat(downgraded.environment().kind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(downgraded.environment().acknowledgedNotProd()).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // Secrets
    // ------------------------------------------------------------------------------------------

    @Test
    void anUploadWarnsAboutEachSecretThatNowSitsInAFileNamingTheVariableAndTheFile() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        ConfigStore.EnvironmentUpload upload = fixture.store.uploadEnvironment("sandbox.json",
                PostmanFiles.bytes(PostmanFiles.SANDBOX_ENVIRONMENT), EnvironmentKind.SANDBOX, false);

        String file = directory.resolve(upload.environment().file()).toString();
        assertThat(upload.warnings()).hasSize(2);
        assertThat(upload.warnings().get(0)).startsWith("The variable \"clientSecret\" holds a secret in plain text, and it now sits in the file "
                + file + ".").contains("{{env:PAVE_YOUR_NAME}}");
        // Postman typed this one secret. Its name alone would not have said so.
        assertThat(upload.warnings().get(1)).startsWith("The variable \"tenantCode\" holds a secret in plain text, and it now sits in the file "
                + file + ".");
        assertThat(String.join(" ", upload.warnings())).doesNotContain("placeholder-sandbox-secret").doesNotContain("placeholder-tenant-value");
    }

    @Test
    void aSecretThatOnlyPointsElsewhereIsNotInTheFileAndIsNotWarnedAbout() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        // Its client secret is read from the operating system.
        assertThat(fixture.store.uploadEnvironment("production.json", PostmanFiles.bytes(PostmanFiles.PRODUCTION_ENVIRONMENT),
                EnvironmentKind.PROD, false).warnings()).isEmpty();
        ConfigStore.EnvironmentUpload pointing = fixture.store.uploadEnvironment("pointing.json", ("{\"name\": \"Pointing\", \"values\": ["
                + "{\"key\": \"apiKey\", \"value\": \"{{vaultKey}}\"}, {\"key\": \"password\", \"value\": \"\"},"
                + "{\"key\": \"accessToken\", \"value\": \"placeholder-token\", \"enabled\": false}]}").getBytes(StandardCharsets.UTF_8),
                EnvironmentKind.SANDBOX, false);
        // A reference holds no secret and a blank holds nothing. A disabled variable is unused, and is still in the file.
        assertThat(pointing.warnings()).hasSize(1);
        assertThat(pointing.warnings().get(0)).startsWith("The variable \"accessToken\" holds a secret in plain text");
    }

    @Test
    void aSecretVariableOfACollectionIsWarnedAboutTooWithWhatTheParserSetAside() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        ConfigStore.CollectionUpload upload = fixture.store.uploadCollection("nested.json", PostmanFiles.bytes(PostmanFiles.NESTED_COLLECTION));

        assertThat(upload.replaced()).isFalse();
        assertThat(upload.warnings()).hasSize(2);
        assertThat(upload.warnings().get(0)).startsWith("The variable \"collectionKey\" holds a secret in plain text, and it now sits in the file "
                + directory.resolve(upload.collection().file()) + ".").doesNotContain("placeholder-collection-key");
        assertThat(upload.warnings().get(1)).isEqualTo("1 entry is neither a folder nor a request and is not listed.");
    }

    @Test
    void aCredentialTypedIntoAHeaderOfACollectionIsWarnedAboutByTheNameOfTheHeader() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        ConfigStore.CollectionUpload upload = fixture.store.uploadCollection("typed.json", ("{\"info\": {\"name\": \"Typed\", \"schema\": "
                + "\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, \"item\": [{\"name\": \"PAS\", \"item\": " + ConfigFixture.items(
                ConfigFixture.item("submit Happy Path", "POST", "{{baseUrl}}/Claim/$submit").header("Authorization", "Bearer literal-token-typed-in")
                        .header("X-Api-Key", "{{apiKey}}"),
                ConfigFixture.item("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire").header("authorization", "Basic bGl0ZXJhbA==")
                        .header("Cookie", "session=literal-session").header("Proxy-Authorization", "Bearer {{proxyToken}}")
                        .header("X-Trace", "not-a-credential")) + "}]}").getBytes(StandardCharsets.UTF_8));

        String file = directory.resolve(upload.collection().file()).toString();
        // One warning for each header name, however many requests carry it. A placeholder is no credential and is not warned about.
        assertThat(upload.warnings()).containsExactly(
                "The header \"Authorization\" holds a credential typed into the collection in 2 requests, the first being \"PAS / submit Happy "
                        + "Path\", and it now sits in the file " + file + " in plain text. This tool redacts it wherever it shows the header. To "
                        + "keep it out of that file, replace it in Postman with a {{placeholder}} and export again.",
                "The header \"Cookie\" holds a credential typed into the collection in 1 request, the first being \"PAS / inquiry Happy "
                        + "Path\", and it now sits in the file " + file + " in plain text. This tool redacts it wherever it shows the header. To "
                        + "keep it out of that file, replace it in Postman with a {{placeholder}} and export again.");
        assertThat(String.join(" ", upload.warnings())).doesNotContain("literal-token-typed-in").doesNotContain("bGl0ZXJhbA")
                .doesNotContain("literal-session");
    }

    // ------------------------------------------------------------------------------------------
    // Files that are refused change nothing
    // ------------------------------------------------------------------------------------------

    @Test
    void aFileThatIsRefusedIsNotStoredAndLeavesNothingBehind() throws IOException {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        long epoch = fixture.store.epoch();

        assertThatThrownBy(() -> fixture.collection(PostmanFiles.TRUNCATED_COLLECTION)).isInstanceOf(BadInputException.class)
                .hasMessageStartingWith("This file is not complete");
        assertThatThrownBy(() -> fixture.collection(PostmanFiles.V1_COLLECTION)).isInstanceOf(BadInputException.class);
        assertThatThrownBy(() -> fixture.collection(PostmanFiles.SANDBOX_ENVIRONMENT)).isInstanceOf(BadInputException.class)
                .hasMessage("This file is a Postman environment, not a collection.");
        assertThatThrownBy(() -> fixture.environment(PostmanFiles.VENDOR_COLLECTION, EnvironmentKind.SANDBOX)).isInstanceOf(BadInputException.class)
                .hasMessage("This file is a Postman collection, not an environment.");
        assertThatThrownBy(() -> fixture.store.uploadEnvironment("empty.json", new byte[0], EnvironmentKind.SANDBOX, false))
                .isInstanceOf(BadInputException.class).hasMessage("The uploaded file is empty.");

        assertThat(fixture.store.epoch()).isEqualTo(epoch);
        assertThat(filesUnder(directory)).isEmpty();
    }

    @Test
    void whenTheStoreCannotWriteNothingChangesInMemoryOrOnDisk() throws IOException {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        ConfigSnapshot before = fixture.store.snapshot();
        String stateBefore = Files.readString(directory.resolve(ConfigStore.STATE_FILE));
        // The collections directory is taken away and a file put in its place, so nothing can be written under it.
        Files.delete(directory.resolve(ConfigStore.COLLECTIONS));
        Files.writeString(directory.resolve(ConfigStore.COLLECTIONS), "in the way");

        assertThatThrownBy(() -> fixture.collection(PostmanFiles.VENDOR_COLLECTION)).isInstanceOf(ConfigStoreException.class)
                .hasMessageContaining("The configuration store could not write " + directory.resolve(ConfigStore.COLLECTIONS))
                .hasMessageContaining("Nothing was changed.");

        assertThat(fixture.store.snapshot()).isSameAs(before);
        assertThat(Files.readString(directory.resolve(ConfigStore.STATE_FILE))).isEqualTo(stateBefore);
    }

    // ------------------------------------------------------------------------------------------
    // Startup
    // ------------------------------------------------------------------------------------------

    @Test
    void aDirectoryThatCannotBeCreatedFailsAtStartupNamingThePath() throws IOException {
        Path inTheWay = directory.resolve("a-file");
        Files.writeString(inTheWay, "not a directory");
        Path below = inTheWay.resolve("store");

        assertThatThrownBy(() -> ConfigFixture.mock(below)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(below.toString()).hasMessageContaining("cannot be created")
                .hasMessageContaining("config-store.directory");
        assertThatThrownBy(() -> ConfigFixture.mock(inTheWay)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(inTheWay.toString()).hasMessageContaining("config-store.directory");
    }

    @Test
    void aDirectoryThatCannotBeWrittenFailsAtStartupNamingThePath() throws IOException {
        Path readOnly = Files.createDirectory(directory.resolve("read-only"));
        assumeTrue(readOnly.toFile().setWritable(false, false), "the file system lets a directory be made read-only");
        try {
            // Whoever runs the build may be allowed to write anywhere, in which case there is nothing to observe.
            assumeTrue(!Files.isWritable(readOnly), "this user cannot write to a read-only directory");

            assertThatThrownBy(() -> ConfigFixture.mock(readOnly)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(readOnly.toString()).hasMessageContaining("config-store.directory");
        } finally {
            readOnly.toFile().setWritable(true, false);
        }
    }

    @Test
    void aBlankDirectorySettingFailsAtStartup() {
        ConfigStoreProperties blank = ConfigStoreProperties.of(java.util.Map.of("directory", "  "));

        assertThatThrownBy(() -> new ConfigStore(blank, ConfigFixture.CLOCK)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("config-store.directory is blank.");
    }

    @Test
    void aStateFileThatCannotBeReadFailsAtStartupNamingTheFile() throws IOException {
        ConfigFixture.mock(directory).activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        Path state = directory.resolve(ConfigStore.STATE_FILE);
        String good = Files.readString(state);

        Files.writeString(state, good.substring(0, good.length() / 2));
        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The configuration store file " + state + " cannot be used: it is not a JSON object.")
                .hasMessageContaining("move the directory " + directory + " aside");

        Files.writeString(state, "[]");
        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class).hasMessageContaining(state.toString());

        Files.writeString(state, good.replace("\"version\" : 1", "\"version\" : 99"));
        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("it was written by a later version of this tool");

        // A kind is never guessed, not even for a file this tool wrote and somebody edited.
        Files.writeString(state, good.replace("\"kind\" : \"sandbox\"", "\"kind\" : \"staging\""));
        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the environment \"Onyx Sandbox\" has no kind of sandbox, uat or prod, and a kind is never guessed");

        Files.writeString(state, good.replace("\"activeEnvironment\" : \"Onyx Sandbox\"", "\"activeEnvironment\" : \"Gone\""));
        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("it names \"Gone\" as the active environment, and it lists no environment of that name");

        Files.writeString(state, good);
        assertThat(ConfigFixture.mock(directory).store.snapshot().activeName()).isEqualTo("Onyx Sandbox");
    }

    @Test
    void anUploadedFileThatWentMissingOrWasDamagedFailsAtStartupNamingThatFile() throws IOException {
        ConfigFixture first = ConfigFixture.mock(directory);
        StoredEnvironment environment = first.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        StoredCollection collection = first.collection(PostmanFiles.VENDOR_COLLECTION);
        Path environmentFile = directory.resolve(environment.file());
        Path collectionFile = directory.resolve(collection.file());
        byte[] environmentBytes = Files.readAllBytes(environmentFile);

        Files.writeString(collectionFile, "{\"not\": \"a collection\"}");
        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The configuration store file " + collectionFile + " cannot be used: it is no longer a Postman "
                        + "collection export.");
        Files.write(collectionFile, PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION));

        Files.delete(environmentFile);
        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("The configuration store file " + environmentFile + " cannot be used: it is missing or cannot be read");

        // A file swapped for another environment's is not taken under the old name and the old kind.
        Files.write(environmentFile, PostmanFiles.bytes(PostmanFiles.PRODUCTION_ENVIRONMENT));
        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("it holds an environment named \"Onyx Production\", and store.json lists it as \"Onyx Sandbox\"");

        Files.write(environmentFile, environmentBytes);
        assertThat(ConfigFixture.mock(directory).store.snapshot().environments()).hasSize(1);
    }

    @Test
    void aStateFileThatPointsOutsideTheStoreIsRefused() throws IOException {
        ConfigFixture.mock(directory).environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        Path state = directory.resolve(ConfigStore.STATE_FILE);
        String good = Files.readString(state);
        String file = JsonSafe.text(JsonSafe.elements(JsonSafe.parse(good).node(), "environments").get(0), "file");

        Files.writeString(state, good.replace(file, "../../outside.json"));

        assertThatThrownBy(() -> ConfigFixture.mock(directory)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("it names a file outside the configuration store directory");
    }

    @Test
    void aTemporaryFileLeftByACrashIsRemovedAtStartup() throws IOException {
        ConfigFixture.mock(directory).environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        // What a crash between writing and moving leaves behind. It can hold a credential.
        Path leftover = directory.resolve(ConfigStore.ENVIRONMENTS).resolve("onyx-sandbox-1a2b3c4d-00000000.json.tmp-9f8e7d6c");
        Path leftoverState = directory.resolve("store.json.tmp-11112222");
        Files.writeString(leftover, "{\"clientSecret\": \"placeholder-left-behind\"}");
        Files.writeString(leftoverState, "{}");

        ConfigFixture restarted = ConfigFixture.mock(directory);

        assertThat(Files.exists(leftover)).isFalse();
        assertThat(Files.exists(leftoverState)).isFalse();
        assertThat(restarted.store.snapshot().environments()).hasSize(1);
    }

    @Test
    void aFileTheStoreDoesNotUseIsReportedAndLeftAlone() throws IOException {
        ConfigFixture first = ConfigFixture.mock(directory);
        first.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        assertThat(first.store.problems()).isEmpty();
        // What a crash between writing an upload and committing it leaves behind: a whole file that nothing names.
        Path stray = directory.resolve(ConfigStore.COLLECTIONS).resolve("onyx-sandbox-epa-1a2b3c4d-9f8e7d6c.json");
        Files.write(stray, PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION));

        ConfigFixture restarted = ConfigFixture.mock(directory);

        assertThat(restarted.store.problems()).containsExactly("The file " + stray + " is in the configuration store and nothing uses it. "
                + "It can be left over from an upload that did not finish, and it may hold credentials. Delete it by hand.");
        assertThat(restarted.service.effective(null).problems()).hasSize(1);
        // It is not loaded as a collection, and it is not deleted.
        assertThat(restarted.store.snapshot().collections()).isEmpty();
        assertThat(Files.exists(stray)).isTrue();

        Files.delete(stray);
        assertThat(restarted.store.problems()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // config-store.active-environment
    // ------------------------------------------------------------------------------------------

    @Test
    void theConfiguredActiveEnvironmentIsActivatedAtStartupWhateverWasActiveBefore() {
        ConfigFixture first = ConfigFixture.mock(directory);
        first.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        first.activate(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);

        ConfigFixture configured = ConfigFixture.on(directory).activeEnvironment(" Onyx Sandbox ").build();
        assertThat(configured.gateway.activeEnvironment().name()).isEqualTo("Onyx Sandbox");
        assertThat(configured.store.problems()).isEmpty();
        // It is a starting point, not a lock: the active environment still switches at runtime.
        configured.store.activate("Onyx UAT");
        assertThat(configured.gateway.activeEnvironment().name()).isEqualTo("Onyx UAT");

        // Left blank, the last one chosen stays.
        assertThat(ConfigFixture.mock(directory).gateway.activeEnvironment().name()).isEqualTo("Onyx UAT");
    }

    @Test
    void aConfiguredActiveEnvironmentThatIsNotInTheStoreStillStartsAndIsReported() {
        ConfigFixture.mock(directory).activate(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);

        // A fresh install must be able to start in order to upload the environment it names.
        ConfigFixture fixture = ConfigFixture.on(directory).activeEnvironment("Onyx Sandbox").build();

        // No other environment is activated in its place, not even the one that was active before.
        assertThat(fixture.gateway.activeEnvironment().active()).isFalse();
        assertThat(fixture.store.problems()).containsExactly("config-store.active-environment names \"Onyx Sandbox\", which is not in the "
                + "configuration store, so it was not activated at startup. Upload an environment with exactly that name and activate it, "
                + "or clear the property.");

        // Once it is uploaded there is nothing left to report. It is still for the operator to activate it.
        fixture.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        assertThat(fixture.store.problems()).isEmpty();
        assertThat(fixture.gateway.activeEnvironment().active()).isFalse();
    }

    @Test
    void requestIdsAreTheSameAfterARestartSoAReferenceKeptByTheBrowserStillPoints() {
        StoredCollection before = ConfigFixture.mock(directory).collection(PostmanFiles.NESTED_COLLECTION);

        StoredCollection after = ConfigFixture.mock(directory).store.snapshot().collection(before.id());

        assertThat(after.requests()).extracting(mapped -> mapped.request().id())
                .containsExactlyElementsOf(before.requests().stream().map(mapped -> mapped.request().id()).toList());
        assertThat(after.requests()).extracting(mapped -> mapped.request().name())
                .containsExactlyElementsOf(before.requests().stream().map(mapped -> mapped.request().name()).toList());
        PostmanRequest deep = named(after, "deep order-select").request();
        assertThat(after.request(deep.id()).request()).isSameAs(deep);
        assertThat(after.request(null)).isNull();
    }
}
