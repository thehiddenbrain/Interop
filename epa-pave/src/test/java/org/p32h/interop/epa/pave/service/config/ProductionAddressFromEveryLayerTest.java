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
 * G2-1 and G2-2 of the last check of the guardrail. The address of a production environment is
 * read from where the tool would send: through application.yml and the variables of a collection
 * as well as the environment. One that cannot be known before sending (an operating system
 * variable, no value at all, an address the client cannot read) is refused at upload and at
 * activation. A whole host remembered for an unreadable production address is given up only for
 * a plain address of the same environment on the same host.
 */
class ProductionAddressFromEveryLayerTest {

    private static final List<String> PROFILES = List.of(EpaProperties.MOCK, EpaProperties.LIVE);
    private static final String YML_PROD = "http://127.0.0.1:18965/yp/fhir";
    private static final String YML_ODD = "http://127.0.0.1:+18965/yq/fhir";

    @TempDir
    Path directory;

    private int fixtures;

    private ConfigFixture.Builder on(String profile) {
        return ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile).allowProdWrites()
                .onyx("variables.ymlProd", YML_PROD).onyx("variables.ymlProdOdd", YML_ODD)
                .onyx("variables.ymlOs", "{{env:PAVE_YMLOS}}");
    }

    private static byte[] environment(String name, String... keysAndValues) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.append(i == 0 ? "" : ", ").append("{\"key\": ").append(JsonSafe.mapper().writeValueAsString(keysAndValues[i]))
                    .append(", \"value\": ").append(JsonSafe.mapper().writeValueAsString(keysAndValues[i + 1])).append("}");
        }
        return ("{\"name\": " + JsonSafe.mapper().writeValueAsString(name) + ", \"values\": [" + values + "]}").getBytes(StandardCharsets.UTF_8);
    }

    private static void refusedAsSandboxAndUat(ConfigFixture fixture, String address, String production) {
        for (EnvironmentKind kind : List.of(EnvironmentKind.SANDBOX, EnvironmentKind.UAT)) {
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("s.json", environment("Near " + kind.wire(), "baseUrl", address),
                    kind, true)).as(kind + " " + address).isInstanceOf(ValidationException.class)
                    .hasMessageContaining("\"" + production + "\"");
        }
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
    void aProductionAddressThatApplicationYmlSuppliesIsRememberedAndNoSandboxMayUseIt() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            fixture.store.uploadEnvironment("p.json", environment("P YP", "baseUrl", "{{ymlProd}}"), EnvironmentKind.PROD, false);
            assertThat(fixture.store.snapshot().remembered()).as(profile).anySatisfy(known -> {
                assertThat(known.environment()).isEqualTo("P YP");
                assertThat(known.destination()).isEqualTo(UrlNormal.destination(YML_PROD));
            });
            // The checker's request: a sandbox and a uat written at that very address.
            refusedAsSandboxAndUat(fixture, YML_PROD, "P YP");
            // And one that reaches it through the same setting.
            refusedAsSandboxAndUat(fixture, "{{ymlProd}}", "P YP");
            // Deleted, it is still production.
            fixture.store.removeEnvironment("P YP");
            refusedAsSandboxAndUat(fixture, YML_PROD, "P YP");
            ConfigFixture restarted = ConfigFixture.on(fixture.directory).mode(profile).build();
            refusedAsSandboxAndUat(restarted, YML_PROD + "/Claim", "P YP");
        }
    }

    @Test
    void aProductionEnvironmentThatLeavesItsAddressToApplicationYmlIsRememberedAtThatAddress() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).onyx("variables.baseUrl", YML_PROD).build();
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            fixture.store.uploadEnvironment("p.json", environment("P Fallback", "clientId", "pave-client"), EnvironmentKind.PROD, false);
            refusedAsSandboxAndUat(fixture, YML_PROD, "P Fallback");
            // A sandbox that leaves the address to the same setting is stored, and writes there only through production.
            fixture.environmentOf("S Fallback", EnvironmentKind.SANDBOX, "clientId", "pave-client");
            fixture.store.activate("S Fallback");
            assertThat(blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT)).getMessage()).as(profile)
                    .contains("belongs to the production environment \"P Fallback\"");
        }
    }

    @Test
    void aProductionAddressThatCannotBeKnownBeforeSendingIsRefusedAtUpload() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            fixture.operatingSystem.put("PAVE_PRODURL", YML_PROD);
            fixture.operatingSystem.put("PAVE_YMLOS", YML_PROD);
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("p.json", environment("P YO", "baseUrl", "{{ymlProdOdd}}"),
                    EnvironmentKind.PROD, false)).as(profile).isInstanceOf(ValidationException.class)
                    .hasMessage("This environment was declared as prod, and the variable \"baseUrl\" holds \"{{ymlProdOdd}}\", which resolves "
                            + "through application.yml or the variables of a collection to the address \"" + YML_ODD + "\", which this "
                            + "tool cannot read the way the HTTP client reads it. It was not stored.");
            for (String reference : List.of("{{env:PAVE_PRODURL}}", "{{env:PAVE_PRODODD}}", "{{ymlOs}}", "{{env:PAVE_PRODURL}}/fhir")) {
                assertThatThrownBy(() -> fixture.store.uploadEnvironment("p.json", environment("P OS", "baseUrl", reference),
                        EnvironmentKind.PROD, false)).as(profile + " " + reference).isInstanceOf(ValidationException.class)
                        .hasMessage("This environment was declared as prod, and the variable \"baseUrl\" holds \"" + reference + "\", which "
                                + "is read from an operating system variable only at the moment of sending, so the address it would send "
                                + "to is not known before then. It was not stored.")
                        .satisfies(e -> assertThat(((ValidationException) e).fieldErrors().get(0).message()).endsWith("so this tool must know "
                                + "them before anything is sent. Write the address itself into the environment, plainly, as "
                                + "scheme://host:port/path, and upload the environment again."));
            }
            // Not named like an address, but the host of a url is read from the operating system.
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("p.json", environment("P OS", "target", "https://{{env:PAVE_HOST}}/fhir"),
                    EnvironmentKind.PROD, false)).as(profile).isInstanceOf(ValidationException.class)
                    .hasMessageContaining("is read from an operating system variable only at the moment of sending");
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("p.json", environment("P None", "baseUrl", "{{nowhere}}/fhir"),
                    EnvironmentKind.PROD, false)).as(profile).isInstanceOf(ValidationException.class)
                    .hasMessage("This environment was declared as prod, and the variable \"baseUrl\" holds \"{{nowhere}}/fhir\", which has "
                            + "no value in this environment, its collections or application.yml, so the address it would send to is not "
                            + "known. It was not stored.");
            assertThat(fixture.store.snapshot().environments()).as(profile).isEmpty();
            assertThat(fixture.store.snapshot().remembered()).as(profile).isEmpty();
        }
    }

    @Test
    void aProductionEnvironmentMayStillTakeASecretFromTheOperatingSystemAndLeaveValuesForTheRun() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            fixture.store.uploadEnvironment("p.json", environment("P Plain", "baseUrl", "https://p32h-placeholder.example.org/prod/fhir",
                    "clientSecret", "{{env:PAVE_CLIENT_SECRET}}", "claimId", "{{claim}}", "memberSystem", "{{env:PAVE_SYSTEM}}"),
                    EnvironmentKind.PROD, false);
            fixture.store.activate("P Plain");
            assertThat(fixture.store.snapshot().activeName()).isEqualTo("P Plain");
        }
    }

    /** A store as an earlier version left it: production environments whose addresses this version refuses. */
    private static Path earlierStore(Path store, String... prodNamesAndAddresses) throws IOException {
        Files.createDirectories(store.resolve("environments"));
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < prodNamesAndAddresses.length; i += 2) {
            String file = "environments/prod-" + i + ".json";
            Files.write(store.resolve(file), environment(prodNamesAndAddresses[i], "baseUrl", prodNamesAndAddresses[i + 1]));
            entries.append("{\"name\": \"").append(prodNamesAndAddresses[i]).append("\", \"kind\": \"prod\", \"file\": \"").append(file)
                    .append("\"}, ");
        }
        Files.write(store.resolve("environments/audit-sandbox.json"), environment("Audit Sandbox", "baseUrl", "http://127.0.0.1:18961/sbx/fhir"));
        Files.writeString(store.resolve("store.json"), "{\"version\": 1, \"epoch\": 5, \"activeEnvironment\": \"Audit Sandbox\", "
                + "\"environments\": [" + entries + "{\"name\": \"Audit Sandbox\", \"kind\": \"sandbox\", \"file\": "
                + "\"environments/audit-sandbox.json\"}], \"productionDestinations\": [], \"collections\": []}");
        return store;
    }

    @Test
    void aStoredProductionWhoseAddressComesFromAnotherLayerIsRememberedOrCannotBeMadeActive() throws IOException {
        for (String profile : PROFILES) {
            Path store = earlierStore(directory.resolve(profile + "-earlier-" + (++fixtures)), "P YP", "{{ymlProd}}", "P YO", "{{ymlProdOdd}}",
                    "P OO", "{{env:PAVE_PRODODD}}");
            ConfigFixture fixture = ConfigFixture.on(store).mode(profile).allowProdWrites().onyx("variables.ymlProd", YML_PROD)
                    .onyx("variables.ymlProdOdd", YML_ODD).build();
            fixture.operatingSystem.put("PAVE_PRODODD", YML_ODD);
            refusedAsSandboxAndUat(fixture, YML_PROD, "P YP");
            // The unreadable one protects its host, which is the same machine here.
            refusedAsSandboxAndUat(fixture, "http://127.0.0.1:18965/other", "P YO");
            assertThatThrownBy(() -> fixture.store.activate("P YO")).as(profile).isInstanceOf(BadInputException.class)
                    .hasMessageStartingWith("\"P YO\" cannot be made active: it is a production environment, and the variable \"baseUrl\" "
                            + "holds \"{{ymlProdOdd}}\", which resolves through application.yml");
            assertThatThrownBy(() -> fixture.store.activate("P OO")).as(profile).isInstanceOf(BadInputException.class)
                    .hasMessage("\"P OO\" cannot be made active: it is a production environment, and the variable \"baseUrl\" holds "
                            + "\"{{env:PAVE_PRODODD}}\", which is read from an operating system variable only at the moment of sending, so "
                            + "the address it would send to is not known before then.");
            assertThat(fixture.store.snapshot().activeName()).isEqualTo("Audit Sandbox");
            fixture.store.activate("P YP");
            assertThat(fixture.store.snapshot().activeName()).isEqualTo("P YP");
        }
    }

    @Test
    void aWholeHostIsGivenUpOnlyForAPlainAddressOfTheSameEnvironmentOnThatHost() throws IOException {
        for (String profile : PROFILES) {
            Path store = earlierStore(directory.resolve(profile + "-host-" + (++fixtures)), "Legacy P", "http://192.0.2.2:+18965/lg/fhir");
            ConfigFixture fixture = ConfigFixture.on(store).mode(profile).allowProdWrites().build();
            refusedAsSandboxAndUat(fixture, "http://192.0.2.2:18965/lg/fhir", "Legacy P");

            // The checker's request: the same name uploaded at another host, with and without a delete first.
            fixture.store.uploadEnvironment("p.json", environment("Legacy P", "baseUrl", "http://127.0.0.1:18962/pa/fhir"),
                    EnvironmentKind.PROD, false);
            refusedAsSandboxAndUat(fixture, "http://192.0.2.2:18965/lg/fhir", "Legacy P");
            refusedAsSandboxAndUat(fixture, "http://192.0.2.2:18966/elsewhere", "Legacy P");
            fixture.store.removeEnvironment("Legacy P");
            fixture.store.uploadEnvironment("p.json", environment("Legacy P", "baseUrl", "http://127.0.0.1:18963/moved/fhir"),
                    EnvironmentKind.PROD, false);
            refusedAsSandboxAndUat(fixture, "http://192.0.2.2:18965/lg/fhir", "Legacy P");
            // Another production environment written plainly on that host does not give it up either.
            fixture.store.uploadEnvironment("p.json", environment("Other P", "baseUrl", "http://192.0.2.2:18965/lg/fhir"),
                    EnvironmentKind.PROD, false);
            refusedAsSandboxAndUat(fixture, "http://192.0.2.2:18966/elsewhere", "Legacy P");
            ConfigFixture restarted = ConfigFixture.on(store).mode(profile).allowProdWrites().build();
            refusedAsSandboxAndUat(restarted, "http://192.0.2.2:18966/elsewhere", "Legacy P");

            // Written plainly on that same host, the environment replaces its whole host with its address.
            restarted.store.uploadEnvironment("p.json", environment("Legacy P", "baseUrl", "http://192.0.2.2:18965/lg/fhir"),
                    EnvironmentKind.PROD, false);
            assertThat(restarted.store.snapshot().remembered()).as(profile)
                    .noneSatisfy(known -> assertThat(known.destination().everyPortAndPath()).isTrue());
            restarted.store.uploadEnvironment("s.json", environment("Near", "baseUrl", "http://192.0.2.2:18966/elsewhere"),
                    EnvironmentKind.SANDBOX, true);
            refusedAsSandboxAndUat(restarted, "http://192.0.2.2:18965/lg/fhir", "Legacy P");
            refusedAsSandboxAndUat(restarted, "http://127.0.0.1:18962/pa/fhir", "Legacy P");
        }
    }

    @Test
    void aCollectionThatBuildsTheAddressFromApplicationYmlAddsItForTheProductionEnvironment() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.store.uploadEnvironment("p.json", environment("P Late", "baseUrl", "https://p32h-placeholder.example.org/live"),
                    EnvironmentKind.PROD, false);
            // The collection arrives after the environment and builds its url on a name only application.yml defines.
            fixture.collectionOf("Yml based", items(item("submit on yml", "POST", "{{ymlProd}}/Claim/$submit")));
            refusedAsSandboxAndUat(fixture, YML_PROD, "P Late");
        }
    }

    @Test
    void anAddressApplicationYmlGivesBelongsToTheProductionEnvironmentThatNamesItAsItsOwn() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.store.uploadEnvironment("a.json", environment("Live A", "baseUrl", "http://127.0.0.1:18962/pa/fhir"),
                    EnvironmentKind.PROD, false);
            fixture.store.uploadEnvironment("b.json", environment("Live B", "baseUrl", YML_PROD), EnvironmentKind.PROD, false);
            StoredCollection yml = fixture.collectionOf("Yml based", items(item("submit on yml", "POST", "{{ymlProd}}/Claim/$submit")));
            // Live A leaves {{ymlProd}} to application.yml, and that address is Live B's own: an unlock of A does not carry a write there.
            fixture.store.activate("Live A");
            ProdUnlock unlock = fixture.gateway.unlock("Live A");
            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED)
                    .withRequest(ConfigFixture.ref(yml, "submit on yml")).withUnlock(unlock));
            assertThat(refusal.getMessage()).as(profile).contains("belongs to the production environment \"Live B\",");
        }
    }
}
