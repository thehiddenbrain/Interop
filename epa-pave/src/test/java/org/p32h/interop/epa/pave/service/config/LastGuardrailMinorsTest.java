package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/**
 * The minors of the last check of the guardrail. G2-4: a character above 0x7F typed raw into a
 * path is sent as its escape, so it is refused as the escape is. G2-8: a production address with
 * three slashes or one after its scheme has no host the client reads, and is refused as production.
 * G2-5: an unreadable remembered address that keeps its path is described as the paths it covers.
 */
class LastGuardrailMinorsTest {

    private static final List<String> PROFILES = List.of(EpaProperties.MOCK, EpaProperties.LIVE);
    private static final String PROD_A = "http://127.0.0.1:18742/pa/fhir";

    @TempDir
    Path directory;

    private int fixtures;

    private ConfigFixture.Builder on(String profile) {
        return ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile).allowProdWrites();
    }

    private static byte[] environment(String name, String... keysAndValues) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.append(i == 0 ? "" : ", ").append("{\"key\": ").append(JsonSafe.mapper().writeValueAsString(keysAndValues[i]))
                    .append(", \"value\": ").append(JsonSafe.mapper().writeValueAsString(keysAndValues[i + 1])).append("}");
        }
        return ("{\"name\": " + JsonSafe.mapper().writeValueAsString(name) + ", \"values\": [" + values + "]}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aRawCharacterAboveAsciiInThePathIsWhatTheClientSendsAsAnEscape() {
        for (String raw : List.of("∕", "／", "⁄", "⧸", "¥", "é")) {
            String found = UrlNormal.encodedSeparator("http://h.example.invalid/a/.." + raw + "..");
            assertThat(found).as(raw).startsWith("a character above 0x7F (sent as %");
        }
        assertThat(UrlNormal.encodedSeparator("http://h.example.invalid/pa∕fhir")).isEqualTo("a character above 0x7F (sent as %E2%88%95)");
        // The query and the fragment are not touched, and neither is the host.
        for (String fine : List.of("http://h.example.invalid/Patient?name=é", "http://h.example.invalid/a#∕",
                "http://hé.example.invalid/a")) {
            assertThat(UrlNormal.encodedSeparator(fine)).as(fine).isNull();
        }
    }

    @Test
    void aLookAlikeSlashTypedRawForOneRunIsNotSentToLockedProduction() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.activeEnvironmentOf("Audit Live A", EnvironmentKind.PROD, "baseUrl", PROD_A, "crdUrl", PROD_A,
                    "crdOrderSignServiceId", "order-sign-crd");
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            for (String hidden : List.of("..∕..∕Claim∕$submit", "..／..／Claim", "..⁄..", "..⧸..", "..¥..")) {
                assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                        .withRunValues(Map.of("crdOrderSignServiceId", hidden)))).as(profile + " " + hidden)
                        .isInstanceOf(BadInputException.class)
                        .hasMessageStartingWith("CRD order-sign was not sent: the path of its url holds a character above 0x7F (sent as %")
                        .hasMessageContaining("which came from the variable \"crdOrderSignServiceId\" (typed for this run)")
                        .hasMessageNotContaining(hidden);
            }
        }
    }

    @Test
    void aSandboxWhoseBaseHoldsARawLookAlikeSlashIsReportedAtUploadAndCannotBeSentTo() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.environmentOf("Audit Live A", EnvironmentKind.PROD, "baseUrl", PROD_A);
            ConfigStore.EnvironmentUpload upload = fixture.store.uploadEnvironment("s.json",
                    environment("Look alike", "baseUrl", "http://127.0.0.1:18742/pa∕fhir"), EnvironmentKind.SANDBOX, true);
            assertThat(upload.warnings()).as(profile).contains("The variable \"baseUrl\" holds an address whose path has an encoded slash, "
                    + "backslash, semicolon or dot segment in it, such as %2F, or a percent-escape of a byte above 0x7F, such as %C0, or a "
                    + "character above 0x7F, which the client sends as such an escape. A server in front of the service can decode it "
                    + "before it reads the path, so no request is sent to that address. Write the path without it and upload the "
                    + "environment again.");
            fixture.store.activate("Look alike");
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT))).as(profile)
                    .isInstanceOf(BadInputException.class).hasMessageContaining("(sent as %E2%88%95)")
                    .hasMessageContaining("which came from the variable \"baseUrl\" (the active environment)");
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE))).as(profile)
                    .isInstanceOf(BadInputException.class);
        }
    }

    @Test
    void aProductionAddressWithThreeSlashesOrOneIsRefusedAtUploadAndCannotBeMadeActive() throws IOException {
        for (String profile : PROFILES) {
            for (String spelling : List.of("http:///127.0.0.1:18965/b8", "http:/127.0.0.1:18965/b9", "HTTPS:///127.0.0.1/b10", "http:")) {
                ConfigFixture fixture = on(profile).build();
                assertThatThrownBy(() -> fixture.store.uploadEnvironment("p.json", environment("P Slashes", "baseUrl", spelling),
                        EnvironmentKind.PROD, false)).as(profile + " " + spelling).isInstanceOf(ValidationException.class)
                        .hasMessage("This environment was declared as prod, and the variable \"baseUrl\" holds the address \""
                                + UrlNormal.asWritten(spelling) + "\", which this tool cannot read the way the HTTP client reads it. It "
                                + "was not stored.");
                assertThat(UrlNormal.asWritten(spelling)).as(spelling).isEqualTo(spelling);
            }
            // Stored by an earlier version, it cannot be made active.
            Path store = directory.resolve(profile + "-earlier-" + (++fixtures));
            Files.createDirectories(store.resolve("environments"));
            Files.write(store.resolve("environments/p.json"), environment("P Slashes", "baseUrl", "http:///127.0.0.1:18965/b8"));
            Files.writeString(store.resolve("store.json"), "{\"version\": 1, \"epoch\": 5, \"environments\": [{\"name\": \"P Slashes\", "
                    + "\"kind\": \"prod\", \"file\": \"environments/p.json\"}], \"productionDestinations\": [], \"collections\": []}");
            ConfigFixture earlier = ConfigFixture.on(store).mode(profile).allowProdWrites().build();
            assertThatThrownBy(() -> earlier.store.activate("P Slashes")).as(profile).isInstanceOf(BadInputException.class)
                    .hasMessageStartingWith("\"P Slashes\" cannot be made active: it is a production environment, and the variable "
                            + "\"baseUrl\" holds the address \"http:///127.0.0.1:18965/b8\"");
        }
    }

    @Test
    void anUnreadableRememberedAddressThatKeepsItsPathIsDescribedAsThePathsItCovers() throws IOException {
        for (String profile : PROFILES) {
            Path store = directory.resolve(profile + "-gone-" + (++fixtures));
            Files.createDirectories(store.resolve("environments"));
            Files.write(store.resolve("environments/sbx.json"), environment("Audit Sandbox", "baseUrl", "http://127.0.0.1:18961/sbx/fhir"));
            Files.writeString(store.resolve("store.json"), "{\"version\": 1, \"epoch\": 5, \"activeEnvironment\": \"Audit Sandbox\", "
                    + "\"environments\": [{\"name\": \"Audit Sandbox\", \"kind\": \"sandbox\", \"file\": \"environments/sbx.json\"}], "
                    + "\"productionDestinations\": [{\"environment\": \"Gone Z\", \"destination\": \"http://127.0.0.7:99999/z/fhir\"}], "
                    + "\"collections\": []}");
            ConfigFixture fixture = ConfigFixture.on(store).mode(profile).allowProdWrites().build();
            assertThat(fixture.service.environments().environments()).filteredOn(view -> view.name().equals("Audit Sandbox"))
                    .singleElement().satisfies(view -> assertThat(view.addressWarnings()).containsExactly("This environment is on the "
                            + "host of the production environment \"Gone Z\", an address of which could not be read the way the HTTP client "
                            + "reads it. This tool takes the path /z/fhir and every path beneath it, on every port of that host, as "
                            + "production. A write from this environment to those paths is refused, and one to another path of that host "
                            + "is not. \"Gone Z\" is no longer stored, and an address that was production stays production."));
            // What the sentence says is what happens: the sandbox's own write is prepared, one beneath /z/fhir is refused.
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)).url().value()).as(profile)
                    .isEqualTo("http://127.0.0.1:18961/sbx/fhir/Claim/$submit");
            StoredCollection literal = fixture.collectionOf("Literal", ConfigFixture.items(
                    ConfigFixture.item("submit under z", "POST", "http://127.0.0.1:18966/z/fhir/Claim/$submit")));
            try {
                fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(ConfigFixture.ref(literal, "submit under z")));
                throw new AssertionError("A write beneath /z/fhir was prepared");
            } catch (ProdWriteBlockedException e) {
                assertThat(e.getMessage()).as(profile).contains("belongs to the production environment \"Gone Z\"");
            }
        }
    }
}
