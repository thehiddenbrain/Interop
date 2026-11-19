package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.ref;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Can a write reach a production environment without an unlock this gateway minted for it and
 * still holds? Every route that was found through an earlier draft is closed here by name, on the
 * mock profile and on the live profile alike.
 */
class ProductionGuardrailTest {

    private static final List<String> PROFILES = List.of(EpaProperties.MOCK, EpaProperties.LIVE);
    private static final String PRODUCTION = "Onyx Production";
    private static final String PRODUCTION_FHIR = "https://api.onyx.example.invalid/fhir";
    private static final String LOCKED = "\"Onyx Production\" is a production environment and production writes are locked for this session.";
    private static final String HOW_TO_UNLOCK = "To send it, unlock production writes on the Config tab by typing the environment name. "
            + "To test without production, switch to a sandbox or uat environment.";

    @TempDir
    Path directory;

    private int fixtures;

    private ConfigFixture.Builder on(String profile) {
        return ConfigFixture.on(directory.resolve(profile + "-" + (++fixtures))).mode(profile);
    }

    /** The production environment active and the vendor collection uploaded. */
    private ConfigFixture production(String profile, boolean floorAllowsWrites) {
        ConfigFixture.Builder builder = on(profile);
        if (floorAllowsWrites) {
            builder.allowProdWrites();
        }
        ConfigFixture fixture = builder.build();
        fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        return fixture;
    }

    private static StoredCollection vendor(ConfigFixture fixture) {
        return fixture.store.snapshot().collections().get(0);
    }

    private static List<EpaOperation> writes() {
        return Arrays.stream(EpaOperation.values()).filter(EpaOperation::write).toList();
    }

    /** The two operations that address one subscription need the id of that subscription. */
    private static CallIntent intentFor(EpaOperation operation) {
        boolean byId = operation == EpaOperation.SUBSCRIPTION_UPDATE || operation == EpaOperation.SUBSCRIPTION_DELETE;
        return byId ? CallIntent.of(operation).withUrlValue("id", "sub-1") : CallIntent.of(operation);
    }

    private static ProdWriteBlockedException blocked(ConfigFixture fixture, CallIntent intent) {
        try {
            PreparedCall call = fixture.gateway.prepare(intent);
            throw new AssertionError("The guardrail prepared a call it should have refused: " + call);
        } catch (ProdWriteBlockedException e) {
            return e;
        }
    }

    // ------------------------------------------------------------------------------------------
    // Writes are blocked on production. Reads are not.
    // ------------------------------------------------------------------------------------------

    @Test
    void everyWriteOperationIsBlockedOnProductionWithoutAnUnlockAndTheRefusalSaysWhatItWas() {
        // If a write operation is ever added, this fails until it is covered below.
        assertThat(writes()).containsExactly(EpaOperation.PAS_SUBMIT, EpaOperation.SUBSCRIPTION_CREATE, EpaOperation.SUBSCRIPTION_UPDATE,
                EpaOperation.SUBSCRIPTION_DELETE);
        Map<EpaOperation, List<String>> expected = Map.of(
                EpaOperation.PAS_SUBMIT, List.of("POST", "submit Happy Path", "{{baseUrl}}/Claim/$submit", PRODUCTION_FHIR + "/Claim/$submit"),
                EpaOperation.SUBSCRIPTION_CREATE, List.of("POST", "subscription-create Happy Path", "{{baseUrl}}/Subscription",
                        PRODUCTION_FHIR + "/Subscription"),
                EpaOperation.SUBSCRIPTION_UPDATE, List.of("PUT", "subscription-update Happy Path", "{{baseUrl}}/Subscription/{{subscriptionId}}",
                        PRODUCTION_FHIR + "/Subscription/sub-1"),
                EpaOperation.SUBSCRIPTION_DELETE, List.of("DELETE", "subscription-delete Happy Path", "{{baseUrl}}/Subscription/{{subscriptionId}}",
                        PRODUCTION_FHIR + "/Subscription/sub-1"));

        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            for (EpaOperation operation : writes()) {
                ProdWriteBlockedException refusal = blocked(fixture, intentFor(operation));

                String what = profile + " " + operation;
                List<String> wanted = expected.get(operation);
                assertThat(refusal.operation()).as(what).isEqualTo(operation);
                assertThat(refusal.environment().name()).as(what).isEqualTo(PRODUCTION);
                assertThat(refusal.environment().kind()).as(what).isEqualTo(EnvironmentKind.PROD);
                // The target was resolved before the refusal, so the attempt can be shown and recorded as what it was.
                assertThat(refusal.method()).as(what).isEqualTo(wanted.get(0));
                assertThat(refusal.requestName()).as(what).isEqualTo(wanted.get(1));
                assertThat(refusal.target().template()).as(what).isEqualTo(wanted.get(2));
                assertThat(refusal.target().value()).as(what).isEqualTo(wanted.get(3));
                assertThat(refusal.target().resolved()).as(what).isTrue();
                assertThat(refusal.getMessage()).as(what).isEqualTo(operation.label() + " with the request \"" + wanted.get(1)
                        + "\" is blocked: " + LOCKED);
                assertThat(refusal.action()).as(what).isEqualTo(HOW_TO_UNLOCK);
            }
        }
    }

    @Test
    void aWriteFromTheFallbackLayerIsBlockedOnProductionJustTheSame() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().onyx("pas.url", "{{baseUrl}}").onyx("subscription.url", "{{baseUrl}}").build();
            fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);

            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT));
            assertThat(refusal.requestName()).as(profile).isNull();
            assertThat(refusal.method()).as(profile).isEqualTo("POST");
            assertThat(refusal.target().value()).as(profile).isEqualTo(PRODUCTION_FHIR + "/Claim/$submit");
            assertThat(refusal.target().source()).as(profile).isEqualTo("application.yml: onyx.pas.url followed by /Claim/$submit");
            assertThat(refusal.getMessage()).as(profile).isEqualTo("PAS submit is blocked: " + LOCKED);
            for (EpaOperation operation : writes()) {
                blocked(fixture, intentFor(operation));
            }
        }
    }

    @Test
    void aWriteWithNoTargetAtAllIsStillRefusedOnProductionBeforeAnythingElseIsSaid() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).build();
            fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);

            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT));

            assertThat(refusal.target().configured()).as(profile).isFalse();
            assertThat(refusal.method()).as(profile).isEqualTo("POST");
            assertThat(refusal.requestName()).as(profile).isNull();
        }
    }

    @Test
    void readsAreAllowedOnProduction() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).onyx("crd.url", "https://api.onyx.example.invalid")
                    .onyx("variables.crdOrderSelectServiceId", "order-select-crd")
                    .lookup("member.url", "https://api.onyx.example.invalid/members/{id}")
                    .lookup("provider.url", "https://api.onyx.example.invalid/providers?npi={id}").build();
            fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);

            int reads = 0;
            for (EpaOperation operation : EpaOperation.values()) {
                if (!operation.outbound() || operation.write() || operation == EpaOperation.UNMAPPED) {
                    continue;
                }
                CallIntent intent = operation.protocol() == Protocol.LOOKUP
                        ? CallIntent.of(operation).withUrlValue("id", "S123456789") : CallIntent.of(operation);
                PreparedCall call = fixture.gateway.prepare(intent);
                assertThat(call.environment().production()).as(profile + " " + operation).isTrue();
                assertThat(call.url().resolved()).as(profile + " " + operation).isTrue();
                reads++;
            }
            // CRD order-sign, order-select and discovery, the two DTR calls, PAS inquire, subscription get and the two lookups.
            assertThat(reads).as(profile).isEqualTo(9);
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).url().value())
                    .isEqualTo(PRODUCTION_FHIR + "/Claim/$inquire");
            // Reading one subscription by id is a read too.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withUrlValue("id", "sub-1")).url().value())
                    .isEqualTo(PRODUCTION_FHIR + "/Subscription/sub-1");
        }
    }

    @Test
    void uatAndSandboxNeverBlockAWrite() {
        for (String profile : PROFILES) {
            for (EnvironmentKind kind : List.of(EnvironmentKind.UAT, EnvironmentKind.SANDBOX)) {
                ConfigFixture fixture = on(profile).build();
                fixture.activate(kind == EnvironmentKind.UAT ? PostmanFiles.UAT_ENVIRONMENT : PostmanFiles.SANDBOX_ENVIRONMENT, kind);
                fixture.collection(PostmanFiles.VENDOR_COLLECTION);

                for (EpaOperation operation : writes()) {
                    PreparedCall call = fixture.gateway.prepare(intentFor(operation));
                    assertThat(call.environment().kind()).as(profile + " " + operation).isEqualTo(kind);
                    assertThat(call.url().resolved()).as(profile + " " + operation).isTrue();
                }
                // A raw POST is a write, and is not blocked here either.
                assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED)
                        .withRequest(ref(vendor(fixture), "subscription-notification Happy Path")).withRunValues(Map.of("callbackUrl",
                                "https://callback.example.invalid/notify"))).method()).isEqualTo("POST");
                WriteAccess access = fixture.gateway.writeAccess(null);
                assertThat(access.production()).isFalse();
                assertThat(access.writesAllowed()).isTrue();
                assertThat(access.reason()).isEqualTo("\"" + fixture.gateway.activeEnvironment().name() + "\" is a " + kind.wire()
                        + " environment, so writes are not blocked.");
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // A request the tool cannot map
    // ------------------------------------------------------------------------------------------

    @Test
    void anUnmappedRequestIsBlockedOnProductionUnlessItsMethodCannotChangeState() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            StoredCollection extras = fixture.collectionOf("Extras", items(item("Capability statement", "GET", "{{baseUrl}}/metadata"),
                    item("Save draft", "POST", "{{baseUrl}}/drafts")));
            RequestRef get = ref(extras, "Capability statement");
            RequestRef post = ref(extras, "Save draft");

            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(post));
            assertThat(refusal.operation()).isEqualTo(EpaOperation.UNMAPPED);
            assertThat(refusal.method()).isEqualTo("POST");
            assertThat(refusal.requestName()).isEqualTo("Save draft");
            assertThat(refusal.target().value()).isEqualTo(PRODUCTION_FHIR + "/drafts");
            assertThat(refusal.getMessage()).as(profile).isEqualTo("Unmapped request with the request \"Save draft\" is blocked: " + LOCKED
                    + " It counts as a write because a request this tool cannot map may change something when it is sent with POST.");

            PreparedCall read = fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(get));
            assertThat(read.method()).as(profile).isEqualTo("GET");
            assertThat(read.url().value()).as(profile).isEqualTo(PRODUCTION_FHIR + "/metadata");
            for (String harmless : List.of("GET", "HEAD", "OPTIONS", "head")) {
                assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(get).withMethod(harmless)).method())
                        .as(profile + " " + harmless).isEqualTo(harmless.toUpperCase(Locale.ROOT));
                // The same request that is blocked as a POST may be sent with a method that changes nothing.
                fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(post).withMethod(harmless));
            }
            for (String changing : List.of("POST", "PUT", "PATCH", "DELETE", "post", "TRACE", "MADE-UP")) {
                assertThat(blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(get).withMethod(changing)).method())
                        .as(profile + " " + changing).isEqualTo(changing.toUpperCase(Locale.ROOT));
            }
        }
    }

    @Test
    void theVendorsSampleNotificationIsAWriteWhenItIsSentRawWithAMethodThatCanChangeState() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            RequestRef notification = ref(vendor(fixture), "subscription-notification Happy Path");
            Map<String, String> callback = Map.of("callbackUrl", "https://callback.example.invalid/notify");

            // It is detected and listed as a notification, and it can only ever be sent raw.
            assertThat(vendor(fixture).request(notification.requestId()).effective()).isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
            assertThat(blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(notification).withRunValues(callback)).method())
                    .as(profile).isEqualTo("POST");
            assertThat(blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(notification).withRunValues(callback)
                    .withMethod("PUT")).method()).as(profile).isEqualTo("PUT");
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(notification).withRunValues(callback)
                    .withMethod("GET")).url().value()).as(profile).isEqualTo("https://callback.example.invalid/notify");
        }
    }

    // ------------------------------------------------------------------------------------------
    // The six routes
    // ------------------------------------------------------------------------------------------

    @Test
    void routeOneNamingAStateChangingMethodOnAReadChangesNothingAndAReadSentWithOneIsAWrite() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);

            // The caller's method is not honoured for a mapped operation, so this is still a GET and still a read.
            PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withMethod("DELETE"));
            assertThat(call.method()).as(profile).isEqualTo("GET");
            assertThat(call.requestName()).as(profile).isEqualTo("subscription-get Happy Path");

            // A collection request that reads as "get" and is written as a DELETE is judged by the method that would go out.
            StoredCollection odd = fixture.collectionOf("Odd", items(
                    item("subscription-get in a hurry", "DELETE", "{{baseUrl}}/Subscription/{{subscriptionId}}")));
            assertThat(odd.requests().get(0).effective()).isEqualTo(EpaOperation.SUBSCRIPTION_GET);
            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.SUBSCRIPTION_GET)
                    .withRequest(ref(odd, "subscription-get in a hurry")));
            assertThat(refusal.method()).as(profile).isEqualTo("DELETE");
            assertThat(refusal.target().value()).as(profile).isEqualTo(PRODUCTION_FHIR + "/Subscription/stale-subscription-9");
            assertThat(refusal.getMessage()).as(profile).isEqualTo("Subscription get with the request \"subscription-get in a hurry\" is "
                    + "blocked: " + LOCKED + " It counts as a write because it would be sent with DELETE, which is not the method of "
                    + "Subscription get and can change something.");
        }
    }

    @Test
    void aReadThatAsksTheFarSideToActWithAnotherMethodIsJudgedByTheMethodItAsksFor() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            StoredCollection overrides = fixture.collectionOf("Overrides", items(
                    item("subscription-get with an override", "GET", "{{baseUrl}}/Subscription/{{subscriptionId}}").header("X-HTTP-Method-Override", "DELETE"),
                    item("subscription-get with a harmless override", "GET", "{{baseUrl}}/Subscription/{{subscriptionId}}").header("x-http-method-override", "get"),
                    item("inquiry with an override", "POST", "{{baseUrl}}/Claim/$inquire").header("X-Method-Override", "{{overrideMethod}}"),
                    item("inquiry with its own method", "POST", "{{baseUrl}}/Claim/$inquire").header("X-HTTP-Method", "POST")));

            // Sent as a GET, and a delete to any server that honours the header.
            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.SUBSCRIPTION_GET)
                    .withRequest(ref(overrides, "subscription-get with an override")));
            assertThat(refusal.method()).as(profile).isEqualTo("GET");
            assertThat(refusal.getMessage()).as(profile).endsWith("It counts as a write because its header X-HTTP-Method-Override asks the "
                    + "far side to act on it as DELETE.");
            // An override to a method that cannot change state changes nothing, and neither does one to the operation's own method.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withRequest(ref(overrides, "subscription-get with a harmless override")))
                    .method()).as(profile).isEqualTo("GET");
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(overrides, "inquiry with its own method")))
                    .method()).as(profile).isEqualTo("POST");

            // The method arrives through a placeholder. Until it has a value it is not taken for harmless.
            CallIntent byPlaceholder = CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(overrides, "inquiry with an override"));
            assertThat(blocked(fixture, byPlaceholder).getMessage()).as(profile).endsWith("It counts as a write because its header "
                    + "X-Method-Override asks the far side to act with another method, and that method has no value yet.");
            assertThat(blocked(fixture, byPlaceholder.withRunValues(Map.of("overrideMethod", "put"))).getMessage()).as(profile)
                    .endsWith("asks the far side to act on it as PUT.");
            assertThat(fixture.gateway.prepare(byPlaceholder.withRunValues(Map.of("overrideMethod", "POST"))).headers().get(0).value())
                    .as(profile).isEqualTo("POST");
        }
    }

    @Test
    void routeTwoACorrectionCanNeverTurnADetectedWriteIntoARead() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            // The operator maps the vendor's submit request to "PAS inquire", then presses the inquire button.
            StoredCollection remapped = fixture.remap(vendor(fixture), "submit Happy Path", EpaOperation.PAS_INQUIRE);
            assertThat(remapped.request(ref(remapped, "submit Happy Path").requestId()).effective()).isEqualTo(EpaOperation.PAS_INQUIRE);

            // It comes first in the file, so it is now what the inquire button sends by default.
            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_INQUIRE));
            assertThat(refusal.operation()).as(profile).isEqualTo(EpaOperation.PAS_INQUIRE);
            assertThat(refusal.requestName()).as(profile).isEqualTo("submit Happy Path");
            assertThat(refusal.method()).as(profile).isEqualTo("POST");
            assertThat(refusal.target().value()).as(profile).isEqualTo(PRODUCTION_FHIR + "/Claim/$submit");
            assertThat(refusal.getMessage()).as(profile).isEqualTo("PAS inquire with the request \"submit Happy Path\" is blocked: " + LOCKED
                    + " It counts as a write because the name or the url of the request \"submit Happy Path\" reads as PAS submit.");
            blocked(fixture, CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(remapped, "submit Happy Path")));
            // The real inquiry request is still a read.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(remapped, "inquiry Happy Path")))
                    .url().value()).as(profile).isEqualTo(PRODUCTION_FHIR + "/Claim/$inquire");

            // Whatever read it is mapped to, it stays a write. So does every other write the vendor shipped.
            for (EpaOperation read : List.of(EpaOperation.CRD_ORDER_SIGN, EpaOperation.DTR_NEXT_QUESTION, EpaOperation.SUBSCRIPTION_GET,
                    EpaOperation.CRD_DISCOVERY, EpaOperation.MEMBER_LOOKUP)) {
                for (String write : List.of("submit Happy Path", "subscription-create Happy Path", "subscription-update Happy Path",
                        "subscription-delete Happy Path")) {
                    StoredCollection now = fixture.remap(vendor(fixture), write, read);
                    blocked(fixture, CallIntent.of(read).withRequest(ref(now, write)));
                }
            }
            // Mapped to nothing and sent raw with a method that cannot change state, the name and the url still say what it is.
            StoredCollection unmapped = fixture.remap(vendor(fixture), "submit Happy Path", EpaOperation.UNMAPPED);
            ProdWriteBlockedException raw = blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(unmapped, "submit Happy Path"))
                    .withMethod("GET"));
            assertThat(raw.method()).as(profile).isEqualTo("GET");
            assertThat(raw.getMessage()).as(profile).contains("reads as PAS submit");
        }
    }

    @Test
    void routeTwoTheUrlAloneIsEnoughWhenTheNameSaysARead() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            // Named for an inquiry, addressed to submit. Detection follows the name, and the guardrail reads both.
            StoredCollection misnamed = fixture.collectionOf("Misnamed", items(item("inquiry by another name", "POST", "{{baseUrl}}/Claim/$submit")));
            assertThat(misnamed.requests().get(0).effective()).isEqualTo(EpaOperation.PAS_INQUIRE);
            assertThat(misnamed.requests().get(0).detection().conflict()).isTrue();

            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_INQUIRE)
                    .withRequest(ref(misnamed, "inquiry by another name")));

            assertThat(refusal.getMessage()).as(profile).endsWith("It counts as a write because the name or the url of the request "
                    + "\"inquiry by another name\" reads as PAS submit.");
        }
    }

    @Test
    void routeThreeAReadCannotBePointedAtAWriteRequest() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            RequestRef submit = ref(vendor(fixture), "submit Happy Path");

            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(submit))).as(profile)
                    .isInstanceOf(BadInputException.class).hasMessage("The request \"submit Happy Path\" is mapped to PAS submit, so it "
                            + "cannot be sent as PAS inquire.");
            // Named for what it is, it is a write, and blocked.
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withRequest(submit));
            // Sent raw, it is a write for every method.
            blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(submit));
            blocked(fixture, CallIntent.of(EpaOperation.UNMAPPED).withRequest(submit).withMethod("GET"));
        }
    }

    @Test
    void routeFourAUrlThatOnlyBecomesAWriteTargetOnceItIsResolvedIsReadAsResolved() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            StoredCollection byVariable = fixture.collectionOf("By variable", items(item("claim operation", "POST", "{{baseUrl}}/Claim/{{op}}"),
                    item("claim by token", "POST", "{{baseUrl}}/Claim/{op}"),
                    item("claim by secret", "POST", "{{baseUrl}}/Claim/{{operationToken}}")));
            // Nothing in these names or templates says what they are, so the operator says: an inquiry.
            for (String name : List.of("claim operation", "claim by token", "claim by secret")) {
                assertThat(byVariable.request(ref(byVariable, name).requestId()).detection().operation()).isEqualTo(EpaOperation.UNMAPPED);
                byVariable = fixture.remap(byVariable, name, EpaOperation.PAS_INQUIRE);
            }
            CallIntent byPlaceholder = CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(byVariable, "claim operation"));

            // Resolved to the inquiry, it is the read it was mapped as.
            assertThat(fixture.gateway.prepare(byPlaceholder.withRunValues(Map.of("op", "$inquire"))).url().value()).as(profile)
                    .isEqualTo(PRODUCTION_FHIR + "/Claim/$inquire");
            // Resolved to the submit, it is a write, however the value is written.
            for (String op : List.of("$submit", "%24submit", "$SUBMIT", "$submit?async=true", "$submit/")) {
                ProdWriteBlockedException refusal = blocked(fixture, byPlaceholder.withRunValues(Map.of("op", op)));
                assertThat(refusal.target().value()).as(profile + " " + op).isEqualTo(PRODUCTION_FHIR + "/Claim/" + op);
                assertThat(refusal.getMessage()).as(profile + " " + op).isEqualTo("PAS inquire with the request \"claim operation\" is "
                        + "blocked: " + LOCKED + " It counts as a write because the name or the url of the request \"claim operation\" "
                        + "reads as PAS submit.");
            }
            // An operation this tool does not know may do anything.
            assertThat(blocked(fixture, byPlaceholder.withRunValues(Map.of("op", "$process-message"))).getMessage()).as(profile)
                    .contains("reads as something this tool cannot vouch for, and it would be sent with POST");
            // While the placeholder has no value nothing says what the request does, and that is not taken for a read.
            assertThat(blocked(fixture, byPlaceholder).getMessage()).as(profile).contains("nothing in the name or the url of the request "
                    + "\"claim operation\" says what it does, and it would be sent with POST");

            // The same through a single-brace token, which is percent-encoded on its way into the url.
            CallIntent byToken = CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(byVariable, "claim by token"));
            assertThat(blocked(fixture, byToken.withUrlValue("op", "$submit")).target().value()).as(profile)
                    .isEqualTo(PRODUCTION_FHIR + "/Claim/%24submit");
            assertThat(fixture.gateway.prepare(byToken.withUrlValue("op", "$inquire")).url().value()).as(profile)
                    .isEqualTo(PRODUCTION_FHIR + "/Claim/%24inquire");

            // And through a secret: the guardrail reads the value that would be sent, and what is shown stays masked.
            CallIntent bySecret = CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(byVariable, "claim by secret"));
            ProdWriteBlockedException hidden = blocked(fixture, bySecret.withRunValues(Map.of("operationToken", "$submit")));
            assertThat(hidden.target().value()).as(profile).isEqualTo(PRODUCTION_FHIR + "/Claim/$submit");
            assertThat(hidden.target().display()).as(profile).isEqualTo(PRODUCTION_FHIR + "/Claim/[secret operationToken]");
            assertThat(fixture.gateway.prepare(bySecret.withRunValues(Map.of("operationToken", "$inquire"))).url().display()).as(profile)
                    .isEqualTo(PRODUCTION_FHIR + "/Claim/[secret operationToken]");
        }
    }

    @Test
    void routeFourAValueFromTheEnvironmentIsReadTheSameWayAsOneTypedForTheRun() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.activeEnvironmentOf("Payer Live", EnvironmentKind.PROD, "baseUrl", "https://live.example.invalid/fhir", "op", "$submit");
            StoredCollection collection = fixture.collectionOf("By variable", items(item("status inquiry", "POST", "{{baseUrl}}/Claim/{{op}}")));
            assertThat(collection.requests().get(0).effective()).isEqualTo(EpaOperation.PAS_INQUIRE);

            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_INQUIRE));

            assertThat(refusal.target().value()).as(profile).isEqualTo("https://live.example.invalid/fhir/Claim/$submit");
            assertThat(refusal.environment().name()).as(profile).isEqualTo("Payer Live");
        }
    }

    @Test
    void routeFiveAnUnlockThisGatewayDidNotMintIsNotHonoured() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            // The right environment name and the right epoch, and not an unlock this gateway issued.
            ProdUnlock forged = TestPreparedCalls.unlock(PRODUCTION, fixture.gateway.epoch());

            for (EpaOperation operation : writes()) {
                ProdWriteBlockedException refusal = blocked(fixture, intentFor(operation).withUnlock(forged));
                assertThat(refusal.getMessage()).as(profile + " " + operation).contains(LOCKED);
            }
            assertThat(fixture.gateway.writeAccess(forged).writesAllowed()).as(profile).isFalse();

            // A real unlock from another installation, with the same name and the same epoch, is not this gateway's either.
            ConfigFixture other = production(profile, true);
            ProdUnlock foreign = other.gateway.unlock(PRODUCTION);
            assertThat(foreign.epoch()).as("the two installations went through the same steps").isEqualTo(fixture.gateway.epoch());
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(foreign));
            assertThat(fixture.gateway.writeAccess(foreign).writesAllowed()).as(profile).isFalse();
            // Where it was minted, it is honoured.
            assertThat(other.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(foreign)).method()).isEqualTo("POST");

            // And minting a real one here does not make the forged one good.
            ProdUnlock real = fixture.gateway.unlock(PRODUCTION);
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(forged));
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(foreign));
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(real)).url().value())
                    .isEqualTo(PRODUCTION_FHIR + "/Claim/$submit");
        }
    }

    @Test
    void routeSixOnLiveAWriteWithNoActiveEnvironmentIsRefusedAndAReadStillGoesToTheFallbackUrl() {
        ConfigFixture live = on(EpaProperties.LIVE).allowProdWrites().onyx("pas.url", "https://fallback.example.invalid/fhir")
                .onyx("subscription.url", "https://fallback.example.invalid/fhir").build();
        live.collectionOf("Extras", items(item("Save draft", "POST", "https://fallback.example.invalid/fhir/drafts")));

        for (EpaOperation operation : writes()) {
            ProdWriteBlockedException refusal = blocked(live, intentFor(operation));
            assertThat(refusal.environment().active()).as(operation.name()).isFalse();
            assertThat(refusal.operation()).as(operation.name()).isEqualTo(operation);
            assertThat(refusal.method()).as(operation.name()).isEqualTo(operation.defaultMethod());
            assertThat(refusal.target().value()).as(operation.name()).startsWith("https://fallback.example.invalid/fhir/");
            assertThat(refusal.getMessage()).as(operation.name()).isEqualTo(operation.label() + " is blocked: no environment is active, so "
                    + "this tool cannot tell whether the target is production.");
            assertThat(refusal.action()).as(operation.name()).isEqualTo("Upload the environment for this target on the Config tab, state its "
                    + "kind and activate it. A write is sent only to an environment whose kind is known.");
        }
        // A raw request that could change something is a write too.
        StoredCollection extras = live.store.snapshot().collections().get(0);
        assertThat(blocked(live, CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(extras, "Save draft"))).getMessage())
                .startsWith("Unmapped request with the request \"Save draft\" is blocked: no environment is active");
        // There is nothing to unlock, so no unlock can open this.
        assertThatThrownBy(() -> live.gateway.unlock("anything")).isInstanceOf(BadInputException.class)
                .hasMessage("There is nothing to unlock: no environment is active.");
        assertThat(live.gateway.writeAccess(null)).isEqualTo(new WriteAccess(false, false, true, "No environment is active, so writes are "
                + "refused: this tool cannot tell whether a target is production. Upload an environment, state its kind and activate it."));

        // Reads still go to the fallback url.
        assertThat(live.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).url().value())
                .isEqualTo("https://fallback.example.invalid/fhir/Claim/$inquire");
        assertThat(live.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET)).url().value())
                .isEqualTo("https://fallback.example.invalid/fhir/Subscription");

        // Once an environment of a known kind is active, the same write is prepared.
        live.activate(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        assertThat(live.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)).url().value())
                .isEqualTo("https://fallback.example.invalid/fhir/Claim/$submit");
    }

    @Test
    void routeSixOnMockNothingIsSentSoAWriteWithNoEnvironmentIsAllowed() {
        // The out-of-the-box install must be able to run a PAS submit with nothing configured.
        ConfigFixture bare = on(EpaProperties.MOCK).build();
        for (EpaOperation operation : writes()) {
            assertThat(bare.gateway.prepare(intentFor(operation)).environment().active()).as(operation.name()).isFalse();
        }
        assertThat(bare.gateway.writeAccess(null)).isEqualTo(new WriteAccess(false, true, false,
                "No environment is active. On the mock profile nothing is sent, so writes are allowed."));

        ConfigFixture withFallback = on(EpaProperties.MOCK).onyx("pas.url", "https://fallback.example.invalid/fhir").build();
        assertThat(withFallback.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)).url().value())
                .isEqualTo("https://fallback.example.invalid/fhir/Claim/$submit");
    }

    // ------------------------------------------------------------------------------------------
    // The unlock
    // ------------------------------------------------------------------------------------------

    @Test
    void anUnlockMintedForTheActiveProductionEnvironmentLetsThatSessionWrite() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            long epoch = fixture.gateway.epoch();
            assertThat(fixture.gateway.writeAccess(null)).as(profile).isEqualTo(new WriteAccess(true, false, true, "\"Onyx Production\" is "
                    + "production and writes are locked for this session. Unlock them by typing the environment name."));

            ProdUnlock unlock = fixture.gateway.unlock(PRODUCTION);

            assertThat(unlock.environmentName()).isEqualTo(PRODUCTION);
            assertThat(unlock.epoch()).isEqualTo(epoch);
            assertThat(unlock.grantedAt()).isEqualTo(ConfigFixture.CLOCK.instant());
            // Minting changes nothing that is stored, so no open tab has to reload.
            assertThat(fixture.gateway.epoch()).as(profile).isEqualTo(epoch);
            for (EpaOperation operation : writes()) {
                PreparedCall call = fixture.gateway.prepare(intentFor(operation).withUnlock(unlock));
                assertThat(call.environment().production()).as(profile + " " + operation).isTrue();
                assertThat(call.url().resolved()).as(profile + " " + operation).isTrue();
            }
            assertThat(fixture.gateway.writeAccess(unlock)).as(profile).isEqualTo(new WriteAccess(true, true, true, "Production writes are "
                    + "unlocked for this session on \"Onyx Production\". Any change to the configuration locks them again."));

            // It is per session: a session that does not hold it is still blocked, and so is a replay or a suite, which pass none.
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT));
            assertThat(fixture.gateway.writeAccess(null).writesAllowed()).as(profile).isFalse();
            // A second session can hold an unlock of its own at the same time.
            ProdUnlock second = fixture.gateway.unlock(PRODUCTION);
            assertThat(second).isNotSameAs(unlock);
            fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(second));
            fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));
        }
    }

    @Test
    void withTheFloorFalseNoUnlockIsPossibleAndTheRefusalSaysAnOwnerLockedTheInstallation() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, false);

            assertThatThrownBy(() -> fixture.gateway.unlock(PRODUCTION)).as(profile)
                    .isInstanceOfSatisfying(ProdWriteBlockedException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("Production writes cannot be unlocked: an owner has locked this installation "
                                + "against them.");
                        assertThat(e.action()).startsWith("No unlock is possible from this screen. config-store.allow-prod-writes is false");
                        assertThat(e.environment().name()).isEqualTo(PRODUCTION);
                    });

            for (EpaOperation operation : writes()) {
                ProdWriteBlockedException refusal = blocked(fixture, intentFor(operation));
                assertThat(refusal.getMessage()).as(profile + " " + operation).endsWith("is blocked: \"Onyx Production\" is a production "
                        + "environment, and an owner has locked this installation against production writes.");
                // It does not tell the tester to unlock, because they cannot.
                assertThat(refusal.action()).as(profile + " " + operation).isEqualTo("An owner has locked this installation against "
                        + "production writes (config-store.allow-prod-writes is false), so no unlock is possible. Only whoever owns the "
                        + "installation can change that. To test without production, switch to a sandbox or uat environment.");
                assertThat(refusal.action()).doesNotContain("unlock production writes on the Config tab");
                assertThat(refusal.target().resolved()).as(profile + " " + operation).isTrue();
            }
            assertThat(fixture.gateway.writeAccess(null)).as(profile).isEqualTo(new WriteAccess(true, false, false, "\"Onyx Production\" is "
                    + "production, and an owner has locked this installation against production writes (config-store.allow-prod-writes is "
                    + "false). No unlock is possible."));
            // Reads are untouched by the floor.
            fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE));
        }
    }

    @Test
    void theFloorIsAHardFloorThatAnUnlockMintedBeforeItWasClosedCannotOverride() {
        Path shared = directory.resolve("shared");
        ConfigFixture open = ConfigFixture.on(shared).live().allowProdWrites().build();
        open.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        open.collection(PostmanFiles.VENDOR_COLLECTION);
        ProdUnlock unlock = open.gateway.unlock(PRODUCTION);
        open.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));

        // The owner closes the floor and restarts.
        ConfigFixture closed = ConfigFixture.on(shared).live().build();

        assertThat(closed.gateway.activeEnvironment().name()).isEqualTo(PRODUCTION);
        assertThat(blocked(closed, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock)).action()).contains("no unlock is possible");
        assertThat(closed.gateway.writeAccess(unlock).writesAllowed()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void evenAnUnlockTheGatewayHoldsCannotOverrideTheFloor() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, false);
            // No route mints an unlock while the floor is closed, so one is planted in the gateway's own registry:
            // the floor has to hold by itself, and not only because the door to an unlock is shut.
            ProdUnlock planted = TestPreparedCalls.unlock(PRODUCTION, fixture.gateway.epoch());
            ReflectionTestUtils.setField(fixture.gateway, "mintedEpoch", fixture.gateway.epoch());
            ((Set<ProdUnlock>) ReflectionTestUtils.getField(fixture.gateway, "minted")).add(planted);

            for (EpaOperation operation : writes()) {
                ProdWriteBlockedException refusal = blocked(fixture, intentFor(operation).withUnlock(planted));
                assertThat(refusal.action()).as(profile + " " + operation).contains("no unlock is possible");
            }
            assertThat(fixture.gateway.writeAccess(planted).writesAllowed()).as(profile).isFalse();

            // The same planted unlock opens the same gateway once the floor allows it, so the test plants what it means to.
            ConfigFixture open = production(profile, true);
            ProdUnlock honoured = TestPreparedCalls.unlock(PRODUCTION, open.gateway.epoch());
            ReflectionTestUtils.setField(open.gateway, "mintedEpoch", open.gateway.epoch());
            ((Set<ProdUnlock>) ReflectionTestUtils.getField(open.gateway, "minted")).add(honoured);
            assertThat(open.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(honoured)).method()).isEqualTo("POST");
        }
    }

    @Test
    void anUnlockIsRefusedUnlessTheNameTypedIsExactlyTheActiveProductionEnvironmentsName() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);

            for (String typed : new String[] {"onyx production", "Onyx Production ", " Onyx Production", "Onyx  Production", "Onyx", "", "  ",
                    null, "Onyx UAT", "prod"}) {
                assertThatThrownBy(() -> fixture.gateway.unlock(typed)).as(profile + " [" + typed + "]")
                        .isInstanceOfSatisfying(ValidationException.class, e -> {
                            assertThat(e.fieldErrors()).hasSize(1);
                            assertThat(e.fieldErrors().get(0).field()).isEqualTo("environment");
                            assertThat(e.fieldErrors().get(0).message()).isEqualTo("The name typed is not the name of the active production "
                                    + "environment. Type it exactly as the environment strip shows it, with the same capitals and spaces.");
                        });
            }
            // None of those attempts opened anything.
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT));
            assertThat(fixture.gateway.unlock(PRODUCTION).environmentName()).isEqualTo(PRODUCTION);
        }
    }

    @Test
    void anUnlockIsRefusedWhenNoEnvironmentIsActiveAndWhenTheActiveOneIsNotProduction() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.environment(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
            // Production is uploaded and is not the active environment, so naming it unlocks nothing.
            assertThatThrownBy(() -> fixture.gateway.unlock(PRODUCTION)).as(profile).isInstanceOf(BadInputException.class)
                    .hasMessage("There is nothing to unlock: no environment is active.");

            fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
            for (String typed : List.of("Onyx Sandbox", PRODUCTION)) {
                assertThatThrownBy(() -> fixture.gateway.unlock(typed)).as(profile).isInstanceOf(BadInputException.class)
                        .hasMessage("There is nothing to unlock: \"Onyx Sandbox\" is a sandbox environment, and writes to it are not locked.");
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // What drops an unlock
    // ------------------------------------------------------------------------------------------

    @Test
    void switchingEnvironmentDropsTheUnlockAndSwitchingBackDoesNotRestoreIt() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
            ProdUnlock unlock = fixture.gateway.unlock(PRODUCTION);
            fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));

            fixture.store.activate("Onyx UAT");
            // On uat nothing is blocked, with or without the old unlock.
            fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));
            fixture.store.activate(PRODUCTION);

            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));
            assertThat(refusal.getMessage()).as(profile).isEqualTo("PAS submit with the request \"submit Happy Path\" is blocked: " + LOCKED
                    + " An earlier unlock no longer applies, because the configuration has changed or writes were locked again since.");
            assertThat(fixture.gateway.writeAccess(unlock)).as(profile).isEqualTo(fixture.gateway.writeAccess(null));
            assertThat(fixture.gateway.writeAccess(unlock).writesAllowed()).as(profile).isFalse();

            // Unlocking again, by name, is the only way back.
            ProdUnlock again = fixture.gateway.unlock(PRODUCTION);
            fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(again));
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));
        }
    }

    @Test
    void anUnlockForOneProductionEnvironmentDoesNotOpenAnother() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            // Named like a sandbox, declared as production.
            fixture.environment(PostmanFiles.SANDBOX_IN_NAME_ONLY, EnvironmentKind.PROD);
            ProdUnlock unlock = fixture.gateway.unlock(PRODUCTION);

            fixture.store.activate("Team Sandbox");

            ProdWriteBlockedException refusal = blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));
            assertThat(refusal.environment().name()).as(profile).isEqualTo("Team Sandbox");
            assertThat(refusal.target().value()).as(profile).isEqualTo("https://api.PROD.onyx.example.invalid/fhir/Claim/$submit");
            // The name of the other one does not unlock this one.
            assertThatThrownBy(() -> fixture.gateway.unlock(PRODUCTION)).as(profile).isInstanceOf(ValidationException.class);
            fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(fixture.gateway.unlock("Team Sandbox")));
        }
    }

    @Test
    void everyChangeToTheStoredConfigurationDropsTheUnlock() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            List<Runnable> changes = List.of(
                    // A mapping correction, on a request that has nothing to do with the write.
                    () -> fixture.remap(vendor(fixture), "order-sign Error", EpaOperation.UNMAPPED),
                    // A collection upload, a replacement and a removal.
                    () -> fixture.collection(PostmanFiles.NESTED_COLLECTION),
                    () -> fixture.collection(PostmanFiles.VENDOR_COLLECTION),
                    () -> fixture.store.removeCollection(fixture.store.snapshot().collections().get(1).id()),
                    // An environment upload, a replacement of the active one, and a removal.
                    () -> fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT),
                    () -> fixture.environment(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD),
                    () -> fixture.store.removeEnvironment("Onyx UAT"));

            int dropped = 0;
            for (Runnable change : changes) {
                ProdUnlock unlock = fixture.gateway.unlock(PRODUCTION);
                PreparedCall before = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));
                assertThat(fixture.gateway.writeAccess(unlock).writesAllowed()).isTrue();

                change.run();

                assertThat(fixture.gateway.activeEnvironment().name()).as(profile).isEqualTo(PRODUCTION);
                blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));
                assertThat(fixture.gateway.writeAccess(unlock).writesAllowed()).as(profile).isFalse();
                // The call prepared while it was unlocked is no longer current, so the sending layer refuses it.
                assertThat(before.epoch()).as(profile).isNotEqualTo(fixture.gateway.epoch());
                dropped++;
            }
            assertThat(dropped).isEqualTo(7);
        }
    }

    @Test
    void lockingAgainDropsEveryUnlockAndMakesCallsPreparedWhileUnlockedStale() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            long quiet = fixture.gateway.epoch();
            // With no unlock held there is nothing to invalidate, so nothing moves.
            fixture.gateway.relock();
            assertThat(fixture.gateway.epoch()).as(profile).isEqualTo(quiet);

            ProdUnlock first = fixture.gateway.unlock(PRODUCTION);
            ProdUnlock second = fixture.gateway.unlock(PRODUCTION);
            PreparedCall prepared = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(first));

            fixture.gateway.relock();

            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(first));
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(second));
            assertThat(fixture.gateway.writeAccess(first).writesAllowed()).as(profile).isFalse();
            assertThat(prepared.epoch()).as(profile).isNotEqualTo(fixture.gateway.epoch());
            // What is stored did not change, and the environment is still the active one.
            assertThat(fixture.gateway.activeEnvironment().name()).isEqualTo(PRODUCTION);
            fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(fixture.gateway.unlock(PRODUCTION)));
        }
    }

    @Test
    void anUnlockDoesNotSurviveARestartAndIsWrittenNowhere() throws IOException {
        for (String profile : PROFILES) {
            Path shared = directory.resolve("restart-" + profile);
            ConfigFixture first = ConfigFixture.on(shared).mode(profile).allowProdWrites().build();
            first.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
            first.collection(PostmanFiles.VENDOR_COLLECTION);
            ProdUnlock unlock = first.gateway.unlock(PRODUCTION);
            first.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));

            // Nothing in the store directory mentions it.
            try (Stream<Path> files = Files.walk(shared)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file)).as(file.toString()).doesNotContainIgnoringCase("unlock").doesNotContain("grantedAt");
                }
            }

            // A second store on the same directory is what a restart is.
            ConfigFixture restarted = ConfigFixture.on(shared).mode(profile).allowProdWrites().build();

            assertThat(restarted.gateway.activeEnvironment().name()).as(profile).isEqualTo(PRODUCTION);
            assertThat(restarted.gateway.writeAccess(unlock).writesAllowed()).as(profile).isFalse();
            blocked(restarted, CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock));
            blocked(restarted, CallIntent.of(EpaOperation.PAS_SUBMIT));
        }
    }

    // ------------------------------------------------------------------------------------------
    // The kind decides, never the name
    // ------------------------------------------------------------------------------------------

    @Test
    void anEnvironmentNamedLikeASandboxButDeclaredProductionStillBlocks() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = on(profile).allowProdWrites().build();
            fixture.activate(PostmanFiles.SANDBOX_IN_NAME_ONLY, EnvironmentKind.PROD);
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);

            assertThat(fixture.gateway.activeEnvironment().name()).isEqualTo("Team Sandbox");
            for (EpaOperation operation : writes()) {
                ProdWriteBlockedException refusal = blocked(fixture, intentFor(operation));
                assertThat(refusal.environment().kind()).as(profile + " " + operation).isEqualTo(EnvironmentKind.PROD);
                assertThat(refusal.getMessage()).as(profile + " " + operation).contains("\"Team Sandbox\" is a production environment");
            }
            assertThat(fixture.gateway.writeAccess(null).production()).as(profile).isTrue();

            // The files that are a sandbox and a uat environment by name and content block as well, once declared production.
            fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.PROD);
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT));
            fixture.activate(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.PROD);
            blocked(fixture, CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "sub-1"));
        }
    }

    @Test
    void productionCannotBeLabelledASandboxByAccident() {
        for (String profile : PROFILES) {
            ConfigFixture fixture = production(profile, true);
            byte[] file = PostmanFiles.bytes(PostmanFiles.PRODUCTION_ENVIRONMENT);

            // Its name says production, and it is stored here as production. Declared a sandbox without the acknowledgement, it is refused.
            assertThatThrownBy(() -> fixture.store.uploadEnvironment("production.json", file, EnvironmentKind.SANDBOX, false)).as(profile)
                    .isInstanceOf(ValidationException.class)
                    .hasMessage("This environment looks like production and was declared as sandbox. It was not stored.");

            // The refused upload changed nothing: it is still production, and still blocked.
            assertThat(fixture.gateway.activeEnvironment().kind()).as(profile).isEqualTo(EnvironmentKind.PROD);
            blocked(fixture, CallIntent.of(EpaOperation.PAS_SUBMIT));
        }
    }
}
