package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.ref;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.postman.VariableResolver;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/**
 * The destination decides. A write to an address the store knows as production is a write to that
 * production environment, whatever is active, wherever the url came from, and after that
 * environment was deleted. Each test repeats a request of the guardrail audit (G1 to G10) at the
 * gateway, on both profiles.
 */
class DestinationGuardrailTest {

    private static final List<String> PROFILES = List.of(EpaProperties.MOCK, EpaProperties.LIVE);
    private static final String LIVE = "http://127.0.0.1:18471/live";
    private static final String LIVE_TWO = "http://127.0.0.1:18472/live2";
    private static final String SANDBOX = "http://127.0.0.1:18470/sbx";

    @TempDir
    Path directory;

    private int fixtures;

    private ConfigFixture.Builder on(String profile) {
        return ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile);
    }

    /** Two production environments and a sandbox, the vendor collection, writes allowed by the floor. */
    private ConfigFixture threeEnvironments(ConfigFixture.Builder builder) {
        ConfigFixture fixture = builder.build();
        fixture.environmentOf("vendor-live", EnvironmentKind.PROD, "baseUrl", LIVE);
        fixture.environmentOf("vendor-live-two", EnvironmentKind.PROD, "baseUrl", LIVE_TWO);
        fixture.environmentOf("pave-sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX);
        fixture.collection(org.p32h.interop.epa.pave.postman.PostmanFiles.VENDOR_COLLECTION);
        return fixture;
    }

    private static ProdWriteBlockedException blocked(ConfigFixture fixture, CallIntent intent) {
        try {
            PreparedCall call = fixture.gateway.prepare(intent);
            throw new AssertionError("The guardrail prepared a call it should have refused: " + call + " to " + call.url().value());
        } catch (ProdWriteBlockedException e) {
            return e;
        }
    }

    private static CallIntent submit() {
        return CallIntent.of(EpaOperation.PAS_SUBMIT);
    }

    private static CallIntent delete(String id) {
        return CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", id);
    }

    // ------------------------------------------------------------------------------------------
    // G1: an unlock for one production environment never opens another
    // ------------------------------------------------------------------------------------------

    @Test
    void anUnlockForOneProductionEnvironmentSendsNothingToAnotherThroughAValueTypedForTheRun() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = threeEnvironments(on(profile).allowProdWrites());
            fixture.store.activate("vendor-live");
            ProdUnlock unlock = fixture.gateway.unlock("vendor-live");
            Map<String, String> elsewhere = Map.of("baseUrl", LIVE_TWO);

            // Its own address, unlocked: sent.
            assertThat(fixture.gateway.prepare(submit().withUnlock(unlock)).url().value()).as(profile).isEqualTo(LIVE + "/Claim/$submit");

            for (CallIntent intent : List.of(submit(), delete("SUB-9"), CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE))) {
                ProdWriteBlockedException refusal = blocked(fixture, intent.withUnlock(unlock).withRunValues(elsewhere));
                assertThat(refusal.getMessage()).as(profile + " " + intent.operation())
                        .contains("the value typed for this run for baseUrl would change where it goes");
                assertThat(refusal.target().value()).as(profile).startsWith(LIVE_TWO);
            }
            // A raw send of a request, the same.
            RequestRef raw = ref(fixture.store.snapshot().collections().get(0), "submit Happy Path");
            blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(raw).withUnlock(unlock).withRunValues(elsewhere));

            // A literal in a collection that points at the other one is refused and names it.
            StoredCollection literal = fixture.collectionOf("Literal", items(item("submit literal", "POST", LIVE_TWO + "/Claim/$submit")));
            ProdWriteBlockedException named = blocked(fixture, submit().withRequest(ref(literal, "submit literal"))
                    .withUnlock(fixture.gateway.unlock("vendor-live")));
            assertThat(named.getMessage()).as(profile).isEqualTo("PAS submit with the request \"submit literal\" is blocked: the address "
                    + "it would go to belongs to the production environment \"vendor-live-two\", and the active environment is "
                    + "\"vendor-live\". A write goes to a production address only from that production environment, active and unlocked.");
            assertThat(named.action()).as(profile).isEqualTo("Nothing was sent. To write to \"vendor-live-two\", activate it on the Config "
                    + "tab and unlock writes there. A value typed for one run, application.yml or another environment cannot send a write "
                    + "to it.");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rule 3: a value typed for one run may not move a write, wherever it sits
    // ------------------------------------------------------------------------------------------

    @Test
    void aValueTypedForTheRunThatSitsInsideAnEnvironmentValueOrPointsAtAnotherVariableCannotMoveAWrite() {
        String elsewhere = "http://127.0.0.1:18479/other";
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.activeEnvironmentOf("pave-sandbox", EnvironmentKind.SANDBOX, "baseUrl", "http://{{host}}/sbx",
                    "host", "127.0.0.1:18470", "spare", elsewhere);
            fixture.collection(org.p32h.interop.epa.pave.postman.PostmanFiles.VENDOR_COLLECTION);
            assertThat(fixture.gateway.prepare(submit()).url().value()).as(profile).isEqualTo(SANDBOX + "/Claim/$submit");

            // The host is typed for the run, inside the environment's own base url.
            ProdWriteBlockedException host = blocked(fixture, submit().withRunValues(Map.of("host", "127.0.0.1:18479")));
            assertThat(host.getMessage()).as(profile).contains("the value typed for this run for host would change where it goes");
            // The base url typed for the run refers to another variable of the environment.
            ProdWriteBlockedException pointer = blocked(fixture, submit().withRunValues(Map.of("baseUrl", "{{spare}}")));
            assertThat(pointer.getMessage()).as(profile).contains("the value typed for this run for baseUrl would change where it goes");
            assertThat(pointer.action()).as(profile).isEqualTo("Nothing was sent. To send a write somewhere else, change the environment: "
                    + "upload one whose values point there, with its kind, and activate it.");
            // A blank value typed for the run is no value: the environment's own is used, and the write goes.
            assertThat(fixture.gateway.prepare(submit().withRunValues(Map.of("baseUrl", " "))).url().value()).as(profile)
                    .isEqualTo(SANDBOX + "/Claim/$submit");

            // A value that fills a segment after the base still works, and one that climbs back out of it does not.
            StoredCollection own = fixture.collectionOf("Own", items(item("subscription-delete by value", "DELETE",
                    "{{baseUrl}}/Subscription/{{subId}}")));
            CallIntent delete = CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withRequest(ref(own, "subscription-delete by value"));
            assertThat(fixture.gateway.prepare(delete.withRunValues(Map.of("subId", "SUB-7"))).url().value()).as(profile)
                    .isEqualTo(SANDBOX + "/Subscription/SUB-7");
            assertThat(blocked(fixture, delete.withRunValues(Map.of("subId", "../../other"))).getMessage()).as(profile)
                    .contains("the value typed for this run for subId would change where it goes");
            // A read may still be pointed anywhere for one run.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRunValues(Map.of("host", "127.0.0.1:18479")))
                    .url().value()).as(profile).isEqualTo("http://127.0.0.1:18479/sbx/Claim/$inquire");
        }
    }

    // ------------------------------------------------------------------------------------------
    // Rules 1 and 5: what counts as a production destination
    // ------------------------------------------------------------------------------------------

    @Test
    void aCallbackOrATokenEndpointSharedWithProductionDoesNotRefuseTheSandboxUnlessARequestIsSentToIt() {
        String callback = "https://callback.example.invalid/api/pas/notifications";
        String tokens = "https://login.example.invalid/oauth2/token";
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.environmentOf("vendor-live", EnvironmentKind.PROD, "baseUrl", LIVE, "callbackUrl", callback, "tokenUrl", tokens,
                    "subscriptionCallback", callback);
            assertThat(fixture.service.environments().productionDestinations()).as(profile)
                    .containsExactly(new ConfigViews.ProductionDestinationView("vendor-live", LIVE, true));

            // The sandbox shares both addresses no write goes to, and is stored.
            fixture.environmentOf("pave-sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX, "callbackUrl", callback, "tokenUrl", tokens);
            assertThat(fixture.store.snapshot().byName("pave-sandbox")).as(profile).isNotNull();

            // Once a request builds its url on the callback, that address is one a write can go to.
            fixture.collectionOf("Calls the callback", items(item("notify", "POST", "{{callbackUrl}}/x")));
            assertThat(fixture.service.environments().productionDestinations()).as(profile)
                    .contains(new ConfigViews.ProductionDestinationView("vendor-live", callback, true));
            byte[] again = ("{\"name\": \"pave-sandbox-2\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + SANDBOX + "\"}, "
                    + "{\"key\": \"callbackUrl\", \"value\": \"" + callback + "\"}]}").getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("env.json", again, EnvironmentKind.SANDBOX, true)).as(profile)
                    .isInstanceOf(ValidationException.class).hasMessage("This environment was declared as sandbox, and its address "
                            + "https://callback.example.invalid/api/pas/notifications is an address of the production environment "
                            + "\"vendor-live\", or lies above or beneath one. It was not stored.");
        }
        assertThat(ProductionDestinations.notACall("callbackUrl")).isTrue();
        assertThat(ProductionDestinations.notACall("OAuth2TokenURL")).isTrue();
        assertThat(ProductionDestinations.notACall("baseUrl")).isFalse();
        assertThat(ProductionDestinations.notACall("pasBaseUrl")).isFalse();
        assertThat(ProductionDestinations.notACall("fhirServer")).isFalse();
    }

    @Test
    void aSecretOfAProductionEnvironmentIsNeverShownOrKeptAsPartOfItsAddress() throws IOException {
        for (String profile : PROFILES) {
            Path shared = directory.resolve("secret-" + profile);
            ConfigFixture fixture = ConfigFixture.on(shared).mode(profile).allowProdWrites().build();
            fixture.store.uploadEnvironment("p.json", ("{\"name\": \"prod-secret-path\", \"values\": [{\"key\": \"baseUrl\", \"value\": "
                    + "\"http://127.0.0.1:18473/{{tenantKey}}/live\"}, {\"key\": \"tenantKey\", \"value\": \"ZZTENANTKEY-51\", \"type\": "
                    + "\"secret\"}]}").getBytes(StandardCharsets.UTF_8), EnvironmentKind.PROD, false);
            fixture.store.uploadEnvironment("h.json", ("{\"name\": \"prod-secret-host\", \"values\": [{\"key\": \"baseUrl\", \"value\": "
                    + "\"https://{{gatewayHost}}/fhir\"}, {\"key\": \"gatewayHost\", \"value\": \"zzhost-52.example.invalid\", \"type\": "
                    + "\"secret\"}]}").getBytes(StandardCharsets.UTF_8), EnvironmentKind.PROD, false);

            // The path ends before the secret, which errs toward production. The host built from a secret, and the secret
            // whose own value is a host, are listed and not shown.
            ConfigViews.ProductionDestinationView hidden = new ConfigViews.ProductionDestinationView("prod-secret-host", "an address whose "
                    + "host is built from a secret of the environment, not shown", true);
            assertThat(fixture.service.environments().productionDestinations()).as(profile).containsExactly(
                    new ConfigViews.ProductionDestinationView("prod-secret-path", "http://127.0.0.1:18473", true), hidden, hidden);
            assertThat(fixture.service.effective(null).productionDestinations().toString()).as(profile)
                    .doesNotContain("ZZTENANTKEY-51").doesNotContain("zzhost-52");
            String state = Files.readString(shared.resolve(ConfigStore.STATE_FILE));
            assertThat(state).as(profile).doesNotContain("ZZTENANTKEY-51").doesNotContain("zzhost-52")
                    .contains("\"destination\" : \"http://127.0.0.1:18473\"");

            // Both are still judged by: a sandbox cannot take either address.
            for (String address : List.of("http://127.0.0.1:18473/other", "https://zzhost-52.example.invalid/fhir")) {
                byte[] copy = ("{\"name\": \"copy\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + address + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                assertThatThrownBy(() -> fixture.store.uploadEnvironment("copy.json", copy, EnvironmentKind.SANDBOX, true)).as(profile)
                        .isInstanceOf(ValidationException.class);
            }
        }
    }

    @Test
    void anAudienceOrAPortalAddressOfProductionDoesNotMakeItsWholeHostProduction() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.environmentOf("vendor-live-aud", EnvironmentKind.PROD, "baseUrl", LIVE, "audience", "http://127.0.0.1:18471",
                    "tokenAudience", "http://127.0.0.1:18471/", "portalUrl", "http://127.0.0.1:18471/", "docsUrl", "http://127.0.0.1:18471/docs");
            assertThat(fixture.service.environments().productionDestinations()).as(profile)
                    .containsExactly(new ConfigViews.ProductionDestinationView("vendor-live-aud", LIVE, true));
            // A sandbox on the same host under another path is stored, and writes to it go.
            fixture.environmentOf("sbx-on-prod-host", EnvironmentKind.SANDBOX, "baseUrl", "http://127.0.0.1:18471/sandbox");
            fixture.store.activate("sbx-on-prod-host");
            fixture.collection(org.p32h.interop.epa.pave.postman.PostmanFiles.VENDOR_COLLECTION);
            assertThat(fixture.gateway.prepare(submit()).url().value()).as(profile).isEqualTo("http://127.0.0.1:18471/sandbox/Claim/$submit");
        }
        assertThat(ProductionDestinations.notACall("audience")).isTrue();
        assertThat(ProductionDestinations.notACall("developerPortalUrl")).isTrue();
    }

    // ------------------------------------------------------------------------------------------
    // G2: a sandbox cannot send a write to a production address
    // ------------------------------------------------------------------------------------------

    @Test
    void aSandboxSendsNoWriteToAProductionAddressWhateverSuppliesIt() {
        for (String profile : PROFILES) {
            for (boolean floor : new boolean[] {true, false}) {
                ConfigFixture.Builder builder = on(profile);
                if (floor) {
                    builder.allowProdWrites();
                }
                ConfigFixture fixture = threeEnvironments(builder);
                fixture.store.activate("pave-sandbox");
                // The sandbox's own address is written to as before.
                assertThat(fixture.gateway.prepare(submit()).url().value()).isEqualTo(SANDBOX + "/Claim/$submit");

                for (CallIntent intent : List.of(submit(), delete("SUB-9"), CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE))) {
                    blocked(fixture, intent.withRunValues(Map.of("baseUrl", LIVE_TWO)));
                }
                // A literal in a collection.
                StoredCollection literal = fixture.collectionOf("Literal", items(item("submit literal", "POST", LIVE_TWO + "/Claim/$submit")));
                ProdWriteBlockedException refusal = blocked(fixture, submit().withRequest(ref(literal, "submit literal")));
                String what = profile + " floor " + floor;
                assertThat(refusal.getMessage()).as(what).contains("belongs to the production environment \"vendor-live-two\"");
                if (!floor) {
                    assertThat(refusal.getMessage()).as(what).endsWith("and an owner has locked this installation against production writes.");
                }
                // A read still goes anywhere a run value points.
                assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRunValues(Map.of("baseUrl", LIVE_TWO)))
                        .url().value()).as(what).isEqualTo(LIVE_TWO + "/Claim/$inquire");
            }
        }
    }

    @Test
    void aProductionAddressInApplicationYmlIsNotWrittenToUnderASandbox() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().onyx("pas.url", LIVE_TWO).onyx("subscription.url", LIVE_TWO).build();
            fixture.environmentOf("vendor-live-two", EnvironmentKind.PROD, "baseUrl", LIVE_TWO);
            fixture.activeEnvironmentOf("pave-sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX);
            for (CallIntent intent : List.of(submit(), delete("SUB-9"), CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE))) {
                ProdWriteBlockedException refusal = blocked(fixture, intent);
                assertThat(refusal.getMessage()).as(profile).contains("belongs to the production environment \"vendor-live-two\", and the "
                        + "active environment is \"pave-sandbox\"");
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // G7: the confirmation "not production" covers the environment's own addresses only
    // ------------------------------------------------------------------------------------------

    @Test
    void theConfirmationThatAnEnvironmentIsNotProductionDoesNotCoverAUrlFromApplicationYml() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).onyx("pas.url", "https://api.prod.payer.example.invalid/fhir").build();
            // Confirmed as not production at upload, with no collection: the submit goes to the application.yml url.
            fixture.store.uploadEnvironment("team.json", ("{\"name\": \"Team Sandbox\", \"values\": [{\"key\": \"baseUrl\", \"value\": "
                    + "\"https://api.preprod.payer.example.invalid/fhir\"}]}").getBytes(StandardCharsets.UTF_8), EnvironmentKind.SANDBOX, true);
            fixture.store.activate("Team Sandbox");
            assertThat(fixture.store.snapshot().active().acknowledgedNotProd()).isTrue();
            ProdWriteBlockedException refusal = blocked(fixture, submit());
            assertThat(refusal.getMessage()).as(profile).isEqualTo("PAS submit is blocked: the url it would go to contains \"prod\", and the "
                    + "confirmation that \"Team Sandbox\" is not production covers that environment's own addresses only, while part of "
                    + "this url came from application.yml. This tool cannot tell whether that url is production.");
            // A read to the same address is not held to this.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).url().value()).as(profile)
                    .isEqualTo("https://api.prod.payer.example.invalid/fhir/Claim/$inquire");
        }
    }

    // ------------------------------------------------------------------------------------------
    // G6: production stays production after its environment is deleted
    // ------------------------------------------------------------------------------------------

    @Test
    void aDeletedProductionEnvironmentCannotComeBackAsASandboxAndItsAddressStaysProduction() throws IOException {
        for (String profile : PROFILES) {
            Path shared = directory.resolve("deleted-" + profile);
            ConfigFixture fixture = threeEnvironments(ConfigFixture.on(shared).mode(profile).allowProdWrites());
            fixture.store.removeEnvironment("vendor-live-two");

            byte[] same = ("{\"name\": \"vendor-live-two\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + LIVE_TWO + "\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] renamed = ("{\"name\": \"copy-of-two\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + LIVE_TWO + "/\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            for (byte[] file : List.of(same, renamed)) {
                for (EnvironmentKind kind : List.of(EnvironmentKind.SANDBOX, EnvironmentKind.UAT)) {
                    assertThatThrownBy(() -> fixture.store.uploadEnvironment("env.json", file, kind, true)).as(profile)
                            .isInstanceOfSatisfying(ValidationException.class, e -> {
                                assertThat(e.getMessage()).isEqualTo("This environment was declared as " + kind.wire() + ", and its "
                                        + "address " + LIVE_TWO + " is an address of the production environment \"vendor-live-two\", or "
                                        + "lies above or beneath one. It was not stored.");
                                assertThat(e.fieldErrors().get(0).field()).isEqualTo("kind");
                            });
                }
            }
            // Beneath the address counts too, and so does the address above it.
            byte[] beneath = ("{\"name\": \"deeper\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + LIVE_TWO + "/R4\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] above = "{\"name\": \"above\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"http://127.0.0.1:18472\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("env.json", beneath, EnvironmentKind.SANDBOX, true)).isInstanceOf(ValidationException.class);
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("env.json", above, EnvironmentKind.SANDBOX, true)).isInstanceOf(ValidationException.class);
            // Another port of the same host is another address.
            fixture.store.uploadEnvironment("env.json", ("{\"name\": \"other-port\", \"values\": [{\"key\": \"baseUrl\", \"value\": "
                    + "\"http://127.0.0.1:18479/live2\"}]}").getBytes(StandardCharsets.UTF_8), EnvironmentKind.SANDBOX, false);

            // A write from the sandbox to the forgotten address is still refused, and names it.
            fixture.store.activate("pave-sandbox");
            StoredCollection literal = fixture.collectionOf("Literal", items(item("submit literal", "POST", LIVE_TWO + "/Claim/$submit")));
            assertThat(blocked(fixture, submit().withRequest(ref(literal, "submit literal"))).getMessage()).as(profile)
                    .contains("belongs to the production environment \"vendor-live-two\"");

            // The store keeps the list on disk, and a restart reads it back.
            String state = Files.readString(shared.resolve(ConfigStore.STATE_FILE));
            assertThat(state).contains("\"productionDestinations\"").contains("\"environment\" : \"vendor-live-two\"")
                    .contains("\"destination\" : \"" + LIVE_TWO + "\"");
            // The Config endpoints show it, so nothing is remembered out of sight.
            assertThat(fixture.service.environments().productionDestinations()).as(profile).contains(
                    new ConfigViews.ProductionDestinationView("vendor-live-two", LIVE_TWO, false),
                    new ConfigViews.ProductionDestinationView("vendor-live", LIVE, true));
            assertThat(fixture.service.effective(null).productionDestinations()).as(profile)
                    .contains(new ConfigViews.ProductionDestinationView("vendor-live-two", LIVE_TWO, false));
            ConfigFixture restarted = ConfigFixture.on(shared).mode(profile).allowProdWrites().build();
            assertThatThrownBy(() -> restarted.store.uploadEnvironment("env.json", same, EnvironmentKind.SANDBOX, true))
                    .isInstanceOf(ValidationException.class);
            assertThat(blocked(restarted, submit().withRequest(ref(restarted.store.snapshot().collections().get(1), "submit literal")))
                    .getMessage()).as(profile).contains("belongs to the production environment \"vendor-live-two\"");
            // Declaring it production is the way through.
            assertThat(restarted.store.uploadEnvironment("env.json", same, EnvironmentKind.PROD, false).environment().kind())
                    .isEqualTo(EnvironmentKind.PROD);
        }
    }

    // ------------------------------------------------------------------------------------------
    // G5 and G10: one normal form, judged and sent
    // ------------------------------------------------------------------------------------------

    @Test
    void dotSegmentsAreRemovedBeforeTheCallIsJudgedAndTheNormalFormIsWhatIsSent() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = threeEnvironments(on(profile).allowProdWrites());
            fixture.store.activate("vendor-live");

            // A run value that turns an inquiry into a create.
            blocked(fixture, CallIntent.of(EpaOperation.PAS_INQUIRE)
                    .withRunValues(Map.of("baseUrl", LIVE + "/Claim/$inquire/../../Claim?x=")));

            // A collection url written that way, with nothing typed for the run.
            StoredCollection dots = fixture.collectionOf("Dots", items(
                    item("inquiry dot segments", "POST", "{{baseUrl}}/Claim/$inquire/../../Claim"),
                    item("subscription-get dotted", "GET", "{{baseUrl}}/Subscription/x/./../SUB-1"),
                    item("inquiry escaped", "POST", "{{baseUrl}}/Claim/%24inquire/%2e%2e/%2E%2E/Claim")));
            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_INQUIRE)
                    .withRequest(ref(dots, "inquiry dot segments")));
            assertThat(refusal.target().value()).as(profile).isEqualTo(LIVE + "/Claim");
            assertThat(refusal.getMessage()).as(profile).contains("It counts as a write because");
            blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(dots, "inquiry escaped")));

            // A read is sent in the same normal form.
            PreparedCall read = fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(dots, "subscription-get dotted")));
            assertThat(read.url().value()).as(profile).isEqualTo(LIVE + "/Subscription/SUB-1");
            assertThat(read.url().display()).as(profile).isEqualTo(LIVE + "/Subscription/SUB-1");
        }
    }

    @Test
    void anAddressAServerTreatsAsAProductionOneIsThatProductionAddressAtUploadAndAtWrite() {
        String[] forms = {"http://127.0.0.1:18472//live2", "http://127.0.0.1:18472/live2;x=1", "http://127.0.0.1:18472/LIVE2",
                "http://localhost:18472/live2"};
        for (String profile : PROFILES) {
            ConfigFixture fixture = threeEnvironments(on(profile).allowProdWrites());
            // At upload, under a sandbox's kind: refused and named, confirmation or not.
            for (String form : forms) {
                byte[] copy = ("{\"name\": \"copy\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + form + "\"}]}")
                        .getBytes(StandardCharsets.UTF_8);
                for (boolean confirmed : new boolean[] {false, true}) {
                    assertThatThrownBy(() -> fixture.store.uploadEnvironment("copy.json", copy, EnvironmentKind.SANDBOX, confirmed))
                            .as(profile + " " + form).isInstanceOf(ValidationException.class)
                            .hasMessageContaining("is an address of the production environment \"vendor-live-two\"");
                }
            }
            // At write, as a literal of a collection: from the other production environment unlocked, and from a sandbox.
            StoredCollection literal = fixture.collectionOf("Literal", items(
                    item("literal to two dslash", "POST", "http://127.0.0.1:18472//live2/Claim/$submit"),
                    item("literal to two semicolon", "POST", "http://127.0.0.1:18472/live2;a=b/Claim/$submit"),
                    item("literal to two upper", "POST", "http://127.0.0.1:18472/LIVE2/Claim/$submit"),
                    item("literal to two localhost", "POST", "http://localhost:18472/live2/Claim/$submit")));
            for (String active : List.of("vendor-live", "pave-sandbox")) {
                fixture.store.activate(active);
                ProdUnlock unlock = active.equals("vendor-live") ? fixture.gateway.unlock("vendor-live") : null;
                for (String name : List.of("literal to two dslash", "literal to two semicolon", "literal to two upper", "literal to two localhost")) {
                    ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(literal, name))
                            .withUnlock(unlock));
                    assertThat(refusal.getMessage()).as(profile + " " + active + " " + name)
                            .contains("belongs to the production environment \"vendor-live-two\"");
                }
            }
            // The doubled slash does not go on the wire: the normal form is what is sent.
            fixture.store.activate("vendor-live-two");
            PreparedCall own = fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(literal, "literal to two dslash"))
                    .withUnlock(fixture.gateway.unlock("vendor-live-two")));
            assertThat(own.url().value()).as(profile).isEqualTo(LIVE_TWO + "/Claim/$submit");
        }
    }

    @Test
    void aDotSegmentWithAParameterIsRemovedSoAnInquiryThatIsACreateIsAWrite() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = threeEnvironments(on(profile).allowProdWrites());
            fixture.store.activate("vendor-live");
            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_INQUIRE)
                    .withRunValues(Map.of("baseUrl", LIVE + "/Claim/$inquire/..;/..;/Claim?x=")));
            assertThat(refusal.target().value()).as(profile).isEqualTo(LIVE + "/Claim?x=/Claim/$inquire");
            assertThat(refusal.getMessage()).as(profile).contains("It counts as a write because");
        }
    }

    @Test
    void aWriteWhosePathClimbsAboveItsRootIsRefused() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.activeEnvironmentOf("pave-sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX);
            StoredCollection climbing = fixture.collectionOf("Climbing", items(item("submit climbing", "POST",
                    "{{baseUrl}}/../../../Claim/$submit")));
            ProdWriteBlockedException refusal = blocked(fixture, submit().withRequest(ref(climbing, "submit climbing")));
            assertThat(refusal.getMessage()).as(profile).isEqualTo("PAS submit with the request \"submit climbing\" is blocked: its url "
                    + "climbs above the start of its path with \"..\", so it would address something other than what it says.");
        }
    }

    @Test
    void theWordProdIsLookedForAfterEscapesAreDecoded() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.store.uploadEnvironment("team.json", ("{\"name\": \"pave-sandbox\", \"values\": [{\"key\": \"baseUrl\", \"value\": \""
                    + SANDBOX + "\"}]}").getBytes(StandardCharsets.UTF_8), EnvironmentKind.SANDBOX, false);
            fixture.store.activate("pave-sandbox");
            StoredCollection escaped = fixture.collectionOf("Escaped", items(
                    item("submit r", "POST", "http://127.0.0.1:18472/p%72od/Claim/$submit"),
                    item("submit p", "POST", "http://127.0.0.1:18472/%70rod/Claim/$submit"),
                    item("submit host", "POST", "http://pr%6Fd.example.invalid/Claim/$submit")));
            for (String name : List.of("submit r", "submit p", "submit host")) {
                assertThat(blocked(fixture, submit().withRequest(ref(escaped, name))).getMessage()).as(profile + " " + name)
                        .contains("the url it would go to contains \"prod\"");
            }
        }
    }

    @Test
    void aUrlReadsAsProductionOnceEveryEscapeIsDecodedAndByItsHost() {
        // Escapes the normal form keeps, because they do not stand for an unreserved character, are read as well.
        assertThat(ConfigStoreGateway.readsAsProduction("http://127.0.0.1:18472/p%72od/Claim")).isTrue();
        assertThat(ConfigStoreGateway.readsAsProduction("http://127.0.0.1:18472/pr%6fd")).isTrue();
        assertThat(ConfigStoreGateway.readsAsProduction("http://127.0.0.1:18472/pr%22od")).isFalse();
        assertThat(ConfigStoreGateway.readsAsProduction("http://API.PROD.example.invalid/fhir")).isTrue();
        assertThat(ConfigStoreGateway.readsAsProduction(SANDBOX + "/Claim/$submit")).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // G8: the unlock is checked against the environment's name, not only the epoch
    // ------------------------------------------------------------------------------------------

    /** A store whose active environment can be swapped without moving the epoch, which a real store never does. */
    private static final class SwappingStore extends ConfigStore {

        private String swappedTo;

        SwappingStore(Path directory) {
            super(ConfigStoreProperties.of(Map.of("directory", directory.toString(), "allow-prod-writes", "true")), ConfigFixture.CLOCK);
        }

        @Override
        ConfigSnapshot snapshot() {
            ConfigSnapshot real = super.snapshot();
            return swappedTo == null ? real
                    : new ConfigSnapshot(real.epoch(), real.environments(), swappedTo, real.collections(), real.remembered());
        }
    }

    @Test
    void aValueTypedForTheRunThatRepeatsTheEnvironmentsOwnValueMovesNothing() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = threeEnvironments(on(profile).allowProdWrites());
            fixture.store.activate("vendor-live");
            ProdUnlock unlock = fixture.gateway.unlock("vendor-live");
            assertThat(fixture.gateway.prepare(submit().withUnlock(unlock).withRunValues(Map.of("baseUrl", LIVE))).url().value())
                    .as(profile).isEqualTo(LIVE + "/Claim/$submit");
            // Another value is still refused.
            assertThat(blocked(fixture, submit().withUnlock(unlock).withRunValues(Map.of("baseUrl", LIVE + "/other"))).getMessage())
                    .as(profile).contains("the value typed for this run for baseUrl would change where it goes");
        }
    }

    @Test
    void aCallAValueTypedForTheRunPointsElsewhereCarriesNoSecretOfTheConfiguration() {
        String elsewhere = "http://127.0.0.1:18479/a";
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.store.uploadEnvironment("s.json", ("{\"name\": \"pave-sandbox\", \"values\": [{\"key\": \"baseUrl\", \"value\": \""
                    + SANDBOX + "\"}, {\"key\": \"apiKey\", \"value\": \"ZZENVAPIKEY-1\", \"type\": \"secret\"}, {\"key\": "
                    + "\"subscriptionKey\", \"value\": \"ZZSUBKEY-2\"}]}").getBytes(StandardCharsets.UTF_8), EnvironmentKind.SANDBOX, false);
            fixture.store.activate("pave-sandbox");
            StoredCollection keyed = fixture.collectionOf("Keyed", items(
                    item("subscription-get keyed", "GET", "{{baseUrl}}/Subscription/{{subscriptionId}}")
                            .header("x-api-key", "{{apiKey}}").header("Ocp-Apim-Subscription-Key", "{{subscriptionKey}}"),
                    item("inquiry keyed url", "POST", "{{baseUrl}}/Claim/$inquire?code={{apiKey}}"),
                    item("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire")));
            CallIntent get = CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withRequest(ref(keyed, "subscription-get keyed"))
                    .withUrlValue("id", "SUB-1");

            // At its own address it goes with its headers, and a value that fills the id moves nothing.
            assertThat(fixture.gateway.prepare(get).url().value()).as(profile).isEqualTo(SANDBOX + "/Subscription/SUB-1");
            assertThat(fixture.gateway.prepare(get.withRunValues(Map.of("subscriptionId", "SUB-2"))).headers()).as(profile).hasSize(2);
            // Pointed elsewhere, it is not sent, and the refusal names the places and never the values.
            assertThatThrownBy(() -> fixture.gateway.prepare(get.withRunValues(Map.of("baseUrl", elsewhere)))).as(profile)
                    .isInstanceOf(org.p32h.interop.epa.pave.service.error.BadInputException.class)
                    .hasMessage("Subscription get was not sent: a value typed for this run points it at http://127.0.0.1:18479, which is "
                            + "not the address the configuration names, and it carries a secret of the configuration in its header "
                            + "x-api-key and in its header Ocp-Apim-Subscription-Key. A secret is sent only to the address it was configured "
                            + "for.");
            CallIntent inquiry = CallIntent.of(EpaOperation.PAS_INQUIRE).withRunValues(Map.of("baseUrl", elsewhere));
            assertThatThrownBy(() -> fixture.gateway.prepare(inquiry.withRequest(ref(keyed, "inquiry keyed url")))).as(profile)
                    .hasMessageContaining("carries a secret of the configuration in its url");
            // A body that holds a secret is refused when it is resolved for sending.
            PreparedCall plain = fixture.gateway.prepare(inquiry.withRequest(ref(keyed, "inquiry Happy Path")));
            assertThat(plain.url().value()).as(profile).isEqualTo(elsewhere + "/Claim/$inquire");
            assertThat(plain.resolve("{\"resourceType\": \"Bundle\"}").value()).as(profile).isEqualTo("{\"resourceType\": \"Bundle\"}");
            assertThatThrownBy(() -> plain.resolve("{\"key\": \"{{apiKey}}\"}")).as(profile)
                    .hasMessageContaining("carries a secret of the configuration in its body");
        }
    }

    @Test
    void anAddressOfTwoProductionEnvironmentsNamesBoth() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = threeEnvironments(on(profile).allowProdWrites());
            fixture.environmentOf("vendor-live-two-copy", EnvironmentKind.PROD, "baseUrl", LIVE_TWO);
            fixture.store.activate("pave-sandbox");
            StoredCollection literal = fixture.collectionOf("Literal", items(item("submit literal", "POST", LIVE_TWO + "/Claim/$submit")));
            assertThat(blocked(fixture, submit().withRequest(ref(literal, "submit literal"))).getMessage()).as(profile)
                    .contains("belongs to the production environments \"vendor-live-two\" and \"vendor-live-two-copy\", and the active");
        }
    }

    @Test
    void anUnlockMintedForOneEnvironmentIsNotHonouredForAnotherEvenInTheSameEpoch() {
        SwappingStore store = new SwappingStore(directory.resolve("swap"));
        ConfigStoreGateway gateway = new ConfigStoreGateway(store, OnyxProperties.of(Map.of()), LookupProperties.of(Map.of()),
                EpaProperties.of(Map.of("mode", EpaProperties.MOCK)), ConfigFixture.CLOCK,
                new VariableResolver(name -> null, Clock.fixed(ConfigFixture.CLOCK.instant(), ConfigFixture.CLOCK.getZone()), () -> "g"));
        for (String name : List.of("vendor-live", "vendor-live-two")) {
            store.uploadEnvironment(name + ".json", ("{\"name\": \"" + name + "\", \"values\": []}").getBytes(StandardCharsets.UTF_8),
                    EnvironmentKind.PROD, false);
        }
        store.activate("vendor-live");
        ProdUnlock unlock = gateway.unlock("vendor-live");
        gateway.prepare(submit().withUnlock(unlock));
        long epoch = gateway.epoch();

        store.swappedTo = "vendor-live-two";
        assertThat(gateway.epoch()).isEqualTo(epoch);
        assertThatThrownBy(() -> gateway.prepare(submit().withUnlock(unlock))).isInstanceOf(ProdWriteBlockedException.class);
        assertThat(gateway.writeAccess(unlock).writesAllowed()).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // G9: locking again moves no epoch
    // ------------------------------------------------------------------------------------------

    @Test
    void lockingAgainLeavesTheEpochAloneAndStillMakesAWritePreparedUnderTheUnlockStale() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = threeEnvironments(on(profile).allowProdWrites());
            fixture.store.activate("vendor-live");
            long pinned = fixture.gateway.epoch();
            ProdUnlock unlock = fixture.gateway.unlock("vendor-live");
            PreparedCall write = fixture.gateway.prepare(submit().withUnlock(unlock));
            PreparedCall read = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE));
            assertThat(write.epoch()).isEqualTo(pinned);

            fixture.gateway.relock();

            // What a suite or a screen is pinned to has not moved, and a read prepared before is still current.
            assertThat(fixture.gateway.epoch()).as(profile).isEqualTo(pinned);
            assertThat(read.epoch()).as(profile).isEqualTo(pinned);
            // The write rested on the unlock, so the sending layer refuses it, and says that writes were locked, not that the
            // configuration changed.
            assertThatThrownBy(() -> org.p32h.interop.epa.pave.client.support.Exchanges.requireSendable(write, null, fixture.gateway))
                    .as(profile).isInstanceOf(org.p32h.interop.epa.pave.service.error.ConfigurationChangedException.class)
                    .hasMessage("Production writes were locked again before this write was sent, so it was not sent. Unlock them on the "
                            + "Config tab by typing the environment name, then send it again.");
            // And the unlock itself is gone.
            blocked(fixture, submit().withUnlock(unlock));
        }
    }

    // ------------------------------------------------------------------------------------------
    // G4: a refusal says what is true
    // ------------------------------------------------------------------------------------------

    @Test
    void aReplayOrASuiteIsToldItNeverCarriesTheUnlockAndNotToUnlock() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = threeEnvironments(on(profile).allowProdWrites());
            fixture.store.activate("vendor-live");
            fixture.gateway.unlock("vendor-live");

            ProdWriteBlockedException replay = blocked(fixture, submit().withOrigin(new RunOrigin(null, null, "run-1")));
            assertThat(replay.getMessage()).as(profile).isEqualTo("PAS submit with the request \"submit Happy Path\" is blocked: "
                    + "\"vendor-live\" is a production environment, and a replay never carries the production unlock, so its writes to "
                    + "production are always blocked.");
            assertThat(replay.action()).as(profile).isEqualTo("Nothing was sent, and unlocking does not change this. To send this write to "
                    + "production, send it on its own from the PAS tab, with writes unlocked there, or switch to an environment that is not "
                    + "production.");
            assertThat(blocked(fixture, submit().withOrigin(new RunOrigin("suite-1", "case-1", null))).getMessage()).as(profile)
                    .contains("and a suite never carries the production unlock");

            // With no origin given, the refusal still does not say that writes are locked when a session has them unlocked.
            ProdWriteBlockedException unnamed = blocked(fixture, submit());
            assertThat(unnamed.getMessage()).as(profile).doesNotContain("locked for this session")
                    .contains("this call carries no production unlock. Writes are unlocked in a browser session, and a replay, a suite and "
                            + "every other browser session never carry that unlock.");
            assertThat(unnamed.action()).as(profile).doesNotContain("unlock production writes on the Config tab");

            // Nothing unlocked anywhere: the plain statement is true. With no origin given, the step names what helps a screen
            // and what helps a replay or a suite.
            fixture.gateway.relock();
            ProdWriteBlockedException plain = blocked(fixture, submit());
            assertThat(plain.getMessage()).as(profile).contains("production writes are locked for this session");
            assertThat(plain.action()).as(profile).contains("A replay or a suite never carries the unlock, so unlocking does not let its "
                    + "writes to production through: send such a write on its own from the PAS tab.");
            // A collection request sent as written is sent by itself from the Config tab, and each sentence reads on from that name.
            RequestRef raw = ref(fixture.store.snapshot().collections().get(0), "submit Happy Path");
            assertThat(blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(raw)).action()).as(profile)
                    .contains("send such a write on its own from the Config tab. To test without production");
            // A call that says it came from a screen is told only what helps a screen, and the raw send is sent again from Config.
            ProdWriteBlockedException fromScreen = blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(raw)
                    .withOrigin(RunOrigin.manual()));
            assertThat(fromScreen.getMessage()).as(profile).contains("production writes are locked for this session");
            assertThat(fromScreen.action()).as(profile).isEqualTo("To send it, unlock production writes on the Config tab by typing the "
                    + "environment name, then send it again from the Config tab. To test without production, switch to a sandbox or uat "
                    + "environment.");
            assertThat(blocked(fixture, submit().withOrigin(RunOrigin.manual())).action()).as(profile)
                    .contains("then send it again from the PAS tab.").doesNotContain("replay");
            assertThat(blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(raw).withOrigin(new RunOrigin(null, null, "run-1")))
                    .action()).as(profile).isEqualTo("Nothing was sent, and unlocking does not change this. To send this write to "
                            + "production, send it on its own from the Config tab, with writes unlocked there, or switch to an environment "
                            + "that is not production.");
            fixture.gateway.unlock("vendor-live");
            assertThat(blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(raw)).action()).as(profile)
                    .isEqualTo("Nothing was sent. To send this write to production, send it on its own from the Config tab of the browser "
                            + "session where writes are unlocked.");
            assertThat(blocked(fixture, delete("SUB-9")).action()).as(profile).endsWith("from the PAS tab of the browser session where "
                    + "writes are unlocked.");
            fixture.gateway.relock();
            // A write prepared under an unlock in a configuration that has since changed is refused for that reason.
            ProdUnlock again = fixture.gateway.unlock("vendor-live");
            PreparedCall prepared = fixture.gateway.prepare(submit().withUnlock(again));
            fixture.store.activate("pave-sandbox");
            assertThatThrownBy(() -> org.p32h.interop.epa.pave.client.support.Exchanges.requireSendable(prepared, null, fixture.gateway))
                    .as(profile).isInstanceOf(org.p32h.interop.epa.pave.service.error.ConfigurationChangedException.class)
                    .hasMessage("The configuration changed while this request was being prepared.");
        }
    }
}
