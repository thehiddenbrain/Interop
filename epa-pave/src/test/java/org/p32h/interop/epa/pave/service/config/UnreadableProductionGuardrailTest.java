package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * N1 of the check of the guardrail fixes: a production environment whose address the HTTP client
 * cannot read is refused at upload and cannot be made active, and a production address that
 * reaches the store anyway, from an earlier version or through a collection, protects its whole
 * host. Every spelling the checker used (t95), and neighbours, each followed by a sandbox at the
 * plain spelling of the same address.
 */
class UnreadableProductionGuardrailTest {

    private static final List<String> PROFILES = List.of(EpaProperties.MOCK, EpaProperties.LIVE);
    private static final String PLAIN = "http://127.0.0.1:18746/zq/fhir";

    /** The checker's spellings and their neighbours: each names 127.0.0.1:18746/zq/fhir to a person, and none can be read by the client. */
    static final List<String> UNREADABLE = List.of("http://127.0.0.1:+18746/zq/fhir", "http://127.0.0.1:18746\\zq\\fhir",
            "http://127.0.0.1:18746:/zq/fhir", "http://127.0.0.1:18746/zq/fh\tir", "http://127.0.0.1:99999/zq/fhir",
            "http://127.0.0.1:18746:80/zq/fhir", "http://127.0.0.1: 18746/zq/fhir", "http://127.0.0.1:-18746/zq/fhir",
            "http://127.0.0.1:18746 /zq/fhir", "http://u@v@127.0.0.1:18746/zq/fhir", "http://127.0.0.1:0x4952/zq/fhir");

    @TempDir
    Path directory;

    private int fixtures;

    private ConfigFixture.Builder on(String profile) {
        return ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile);
    }

    private static byte[] environment(String name, String... keysAndValues) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.append(i == 0 ? "" : ", ").append("{\"key\": ").append(JsonSafe.mapper().writeValueAsString(keysAndValues[i]))
                    .append(", \"value\": ").append(JsonSafe.mapper().writeValueAsString(keysAndValues[i + 1])).append("}");
        }
        return ("{\"name\": " + JsonSafe.mapper().writeValueAsString(name) + ", \"values\": [" + values + "]}").getBytes(StandardCharsets.UTF_8);
    }

    private static ProdWriteBlockedException blocked(ConfigFixture fixture, CallIntent intent) {
        try {
            PreparedCall call = fixture.gateway.prepare(intent);
            throw new AssertionError("The guardrail prepared a call it should have refused, to " + call.url().value());
        } catch (ProdWriteBlockedException e) {
            return e;
        }
    }

    @Test
    void everySpellingIsUnreadableToTheClientAndStandsForItsWholeHost() {
        UrlNormal.Destination plain = UrlNormal.destination(PLAIN);
        for (String spelling : UNREADABLE) {
            UrlNormal.Destination read = UrlNormal.destination(spelling);
            assertThat(read == null || !read.readable()).as(spelling).isTrue();
            assertThat(UrlNormal.sendable(spelling)).as(spelling).isNull();
            UrlNormal.Destination host = UrlNormal.wholeHost(spelling);
            assertThat(host.everyPortAndPath()).as(spelling).isTrue();
            assertThat(host.covers(plain)).as(spelling).isTrue();
            assertThat(host.covers(UrlNormal.destination("https://localhost:1/anything"))).as(spelling).isTrue();
            // Written and read back as the store does.
            assertThat(UrlNormal.destination(host.shown())).as(spelling).isEqualTo(host);
        }
        assertThat(UrlNormal.wholeHost("http://ｌｏｃａｌｈｏｓｔ:18746/x").covers(plain)).isTrue();
        assertThat(UrlNormal.wholeHost("http://[::1]:+5/x").host()).isEqualTo("[::1]");
        assertThat(UrlNormal.wholeHost("http://{{host}}/x")).isNull();
        // Quoted as the file writes it, never with user information or a query.
        assertThat(UrlNormal.asWritten("http://u:pw@127.0.0.1:+1/a\tb?api_key=k")).isEqualTo("http://127.0.0.1:+1/a\\tb");
        assertThat(UrlNormal.asWritten("http://127.0.0.1:1\\a\\b")).isEqualTo("http://127.0.0.1:1\\a\\b");
    }

    @Test
    void aProductionEnvironmentWhoseAddressCannotBeReadIsRefusedAtUploadAndPlainSpellingThenProtects() {
        for (String profile : PROFILES) {
            for (String spelling : UNREADABLE) {
                ConfigFixture fixture = on(profile).allowProdWrites().build();
                assertThatThrownBy(() -> fixture.store.uploadEnvironment("p.json", environment("UP", "baseUrl", spelling),
                        EnvironmentKind.PROD, false)).as(profile + " " + spelling).isInstanceOf(ValidationException.class)
                        .hasMessage("This environment was declared as prod, and the variable \"baseUrl\" holds the address \""
                                + UrlNormal.asWritten(spelling) + "\", which this tool cannot read the way the HTTP client reads it. "
                                + "It was not stored.");
                assertThat(fixture.store.snapshot().environments()).as(spelling).isEmpty();
                // Written plainly, it is stored, and the sandbox at the plain spelling is refused.
                fixture.store.uploadEnvironment("p.json", environment("UP", "baseUrl", PLAIN), EnvironmentKind.PROD, false);
                assertThatThrownBy(() -> fixture.store.uploadEnvironment("s.json", environment("UP plain", "baseUrl", PLAIN),
                        EnvironmentKind.SANDBOX, true)).as(profile + " " + spelling).isInstanceOf(ValidationException.class)
                        .hasMessageContaining("is an address of the production environment \"UP\"");
            }
        }
    }

    @Test
    void aSecretAddressThatCannotBeReadIsNamedAndNeverQuoted() {
        ConfigFixture fixture = on(EpaProperties.LIVE).allowProdWrites().build();
        byte[] secret = ("{\"name\": \"Secret Prod\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"http://127.0.0.1:+18746/zq\", "
                + "\"type\": \"secret\"}]}").getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> fixture.store.uploadEnvironment("p.json", secret, EnvironmentKind.PROD, false))
                .isInstanceOf(ValidationException.class).hasMessageContaining("the variable \"baseUrl\" holds an address that is not "
                        + "quoted here because the variable is a secret").hasMessageNotContaining("18746");
    }

    /** Writes a store by hand, as an earlier version of this tool left it: a production environment with an unreadable address. */
    private static Path earlierStore(Path store, String spelling) throws IOException {
        Files.createDirectories(store.resolve("environments"));
        Files.write(store.resolve("environments/legacy-prod.json"), environment("Legacy Prod", "baseUrl", spelling));
        Files.write(store.resolve("environments/audit-sandbox.json"), environment("Audit Sandbox", "baseUrl", "http://127.0.0.1:18745/sbx"));
        Files.writeString(store.resolve("store.json"), "{\"version\": 1, \"epoch\": 5, \"activeEnvironment\": \"Audit Sandbox\", "
                + "\"environments\": [{\"name\": \"Legacy Prod\", \"kind\": \"prod\", \"file\": \"environments/legacy-prod.json\"}, "
                + "{\"name\": \"Audit Sandbox\", \"kind\": \"sandbox\", \"file\": \"environments/audit-sandbox.json\"}], "
                + "\"productionDestinations\": [], \"collections\": []}");
        return store;
    }

    @Test
    void aProductionAddressStoredBeforeThisRuleProtectsItsWholeHostUntilItIsReplaced() throws IOException {
        for (String profile : PROFILES) {
            for (String spelling : UNREADABLE) {
                Path store = earlierStore(directory.resolve(profile + "-legacy-" + (++fixtures)), spelling);
                ConfigFixture fixture = ConfigFixture.on(store).mode(profile).allowProdWrites().build();
                assertThat(fixture.store.snapshot().remembered()).as(spelling)
                        .anySatisfy(known -> assertThat(known.destination().everyPortAndPath()).isTrue());
                // The plain spelling, and any other port of that host, cannot be uploaded as a sandbox.
                for (String plain : List.of(PLAIN, "http://127.0.0.1:18747/other", "http://localhost/zq/fhir")) {
                    assertThatThrownBy(() -> fixture.store.uploadEnvironment("s.json", environment("UP plain", "baseUrl", plain),
                            EnvironmentKind.SANDBOX, true)).as(profile + " " + spelling + " " + plain).isInstanceOf(ValidationException.class)
                            .hasMessageContaining("\"Legacy Prod\"");
                }
                assertThatThrownBy(() -> fixture.store.uploadEnvironment("s.json", environment("UP plain", "baseUrl",
                        "http://127.0.0.1:18747/other"), EnvironmentKind.SANDBOX, true)).as(spelling).hasMessage("This environment was "
                        + "declared as sandbox, and its address http://127.0.0.1:18747/other is on the host of the production environment "
                        + "\"Legacy Prod\". That production environment has an address this tool cannot read the way the HTTP client reads "
                        + "it, so every port and every path of its host is taken as production. It was not stored.");
                // The sandbox stored before is told why, and its writes to that host are refused.
                assertThat(fixture.service.environments().environments()).filteredOn(view -> view.name().equals("Audit Sandbox"))
                        .singleElement().satisfies(view -> assertThat(view.addressWarnings()).containsExactly("This environment is on the "
                                + "host of the production environment \"Legacy Prod\", an address of which could not be read the way the "
                                + "HTTP client reads it. This tool takes every port and every path of that host as production, so a write "
                                + "from this environment to it is refused. Upload \"Legacy Prod\" again with its address written plainly, "
                                + "as scheme://host:port/path."));
                assertThat(fixture.service.environments().productionDestinations()).as(spelling)
                        .anySatisfy(view -> assertThat(view.destination()).isEqualTo("every port and every path of the host 127.0.0.1, "
                                + "because an address of this environment could not be read the way the HTTP client reads it"));
                fixture.collection(PostmanFiles.VENDOR_COLLECTION);
                assertThat(blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT)).getMessage()).as(profile + " " + spelling)
                        .contains("belongs to the production environment \"Legacy Prod\"");
                // It cannot be made active.
                assertThatThrownBy(() -> fixture.store.activate("Legacy Prod")).as(spelling).isInstanceOf(BadInputException.class)
                        .hasMessage("\"Legacy Prod\" cannot be made active: it is a production environment, and the variable \"baseUrl\" "
                                + "holds the address \"" + UrlNormal.asWritten(spelling) + "\", which this tool cannot read the way the "
                                + "HTTP client reads it.");
                assertThat(fixture.store.snapshot().activeName()).isEqualTo("Audit Sandbox");

                // Remembered across a restart, and after the environment is deleted, as its host.
                fixture.store.removeEnvironment("Legacy Prod");
                ConfigFixture restarted = ConfigFixture.on(store).mode(profile).allowProdWrites().build();
                assertThat(restarted.store.snapshot().remembered()).as(spelling)
                        .anySatisfy(known -> assertThat(known.destination().everyPortAndPath()).isTrue());
                assertThat(Files.readString(store.resolve("store.json"))).contains("\"destination\" : \"127.0.0.1\"");
                assertThatThrownBy(() -> restarted.store.uploadEnvironment("s.json", environment("UP plain", "baseUrl", PLAIN),
                        EnvironmentKind.SANDBOX, true)).as(spelling).isInstanceOf(ValidationException.class);

                // Replaced: uploaded again as production with its address written plainly, it protects that address only.
                restarted.store.uploadEnvironment("p.json", environment("Legacy Prod", "baseUrl", PLAIN), EnvironmentKind.PROD, false);
                assertThat(restarted.store.snapshot().remembered()).as(spelling)
                        .noneSatisfy(known -> assertThat(known.destination().everyPortAndPath()).isTrue());
                restarted.collection(PostmanFiles.VENDOR_COLLECTION);
                restarted.store.activate("Audit Sandbox");
                assertThat(restarted.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)).url().value()).as(spelling)
                        .isEqualTo("http://127.0.0.1:18745/sbx/Claim/$submit");
            }
        }
    }

    @Test
    void aSandboxRefusedForAnAddressItCannotReadIsQuotedAsTheFileWritesIt() {
        // F-ADDR-MISQUOTE of the honesty audit: the refusal quoted http://127.0.0.1/fhir, which the file does not hold.
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.environmentOf("V6 P", EnvironmentKind.PROD, "baseUrl", "http://127.0.0.1:18742/fhir");
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("s.json", environment("V6 S", "baseUrl", "http://127.0.0.1:99999/fhir"),
                    EnvironmentKind.SANDBOX, true)).as(profile).isInstanceOf(ValidationException.class)
                    .hasMessage("This environment was declared as sandbox, and its address \"http://127.0.0.1:99999/fhir\", in the variable "
                            + "\"baseUrl\", cannot be read the way the HTTP client reads it. Read as closely as this tool can, it is an "
                            + "address of the production environment \"V6 P\", or lies above or beneath one. It was not stored.");
            // An address that reads well is quoted as this tool reads it, as before.
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("s.json", environment("V6 S", "baseUrl", "http://127.0.0.1:018742/fhir"),
                    EnvironmentKind.SANDBOX, true)).as(profile).isInstanceOf(ValidationException.class)
                    .hasMessageStartingWith("This environment was declared as sandbox, and its address http://127.0.0.1:18742/fhir is an "
                            + "address of the production environment \"V6 P\"");
        }
    }

    @Test
    void aProductionEnvironmentThatACollectionMakesUnreadableProtectsItsHostAndCannotBeUnlocked() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.store.uploadEnvironment("p.json", environment("Odd Prod", "baseUrl", PLAIN, "authUrl", "http://127.0.0.1:+18746/zq/fhir"),
                    EnvironmentKind.PROD, false);
            fixture.store.activate("Odd Prod");
            // A collection whose request builds its address on authUrl makes it an address of the environment.
            fixture.collectionOf("Auth based", items(item("submit on auth", "POST", "{{authUrl}}/Claim/$submit")));
            assertThat(fixture.store.snapshot().remembered()).as(profile)
                    .anySatisfy(known -> assertThat(known.destination().everyPortAndPath()).isTrue());
            assertThatThrownBy(() -> fixture.gateway.unlock("Odd Prod")).as(profile).isInstanceOf(BadInputException.class)
                    .hasMessageStartingWith("Writes to \"Odd Prod\" cannot be unlocked: it is a production environment, and the variable "
                            + "\"authUrl\" holds the address \"http://127.0.0.1:+18746/zq/fhir\"");
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("s.json", environment("Near", "baseUrl", "http://127.0.0.1:18799/x"),
                    EnvironmentKind.SANDBOX, true)).as(profile).isInstanceOf(ValidationException.class).hasMessageContaining("\"Odd Prod\"");
        }
    }
}
