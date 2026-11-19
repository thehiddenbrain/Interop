package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.ref;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.variables;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;

/** How placeholders resolve through the gateway: the four layers, a call's own collection, the operating system, and what is shown. */
class GatewayResolutionTest {

    @TempDir
    Path directory;

    private static VariableUse use(List<VariableUse> uses, String name) {
        return uses.stream().filter(each -> each.name().equals(name)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------
    // The four layers
    // ------------------------------------------------------------------------------------------

    @Test
    void aPlaceholderResolvesFromTheRunThenTheEnvironmentThenTheCollectionThenApplicationYml() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("variables.x", "from-yml").build();
        fixture.collectionOf("With the variable", items(item("inquiry Happy Path", "POST", "https://layers.example.invalid/{{x}}/Claim/$inquire")),
                variables("x", "from-collection"));
        fixture.collectionOf("Without it", items(item("next-question Happy Path", "POST",
                "https://layers.example.invalid/{{x}}/Questionnaire/$next-question")));
        fixture.environmentOf("Has x", EnvironmentKind.SANDBOX, "x", "from-environment");
        fixture.environmentOf("Blank x", EnvironmentKind.SANDBOX, "x", "");
        CallIntent inquire = CallIntent.of(EpaOperation.PAS_INQUIRE);
        CallIntent nextQuestion = CallIntent.of(EpaOperation.DTR_NEXT_QUESTION);

        // No environment is active: the collection's own variable, and for a request of the other collection application.yml.
        PreparedCall fromCollection = fixture.gateway.prepare(inquire);
        assertThat(fromCollection.url().value()).isEqualTo("https://layers.example.invalid/from-collection/Claim/$inquire");
        assertThat(use(fromCollection.url().variables(), "x").source()).isEqualTo(VariableSource.COLLECTION);
        PreparedCall fromYml = fixture.gateway.prepare(nextQuestion);
        assertThat(fromYml.url().value()).isEqualTo("https://layers.example.invalid/from-yml/Questionnaire/$next-question");
        assertThat(use(fromYml.url().variables(), "x").source()).isEqualTo(VariableSource.APPLICATION_YML);

        // The active environment wins over both.
        fixture.store.activate("Has x");
        for (CallIntent intent : List.of(inquire, nextQuestion)) {
            PreparedCall call = fixture.gateway.prepare(intent);
            assertThat(call.url().value()).contains("/from-environment/");
            assertThat(use(call.url().variables(), "x")).isEqualTo(new VariableUse("x", VariableSource.ENVIRONMENT, false, "from-environment"));
        }

        // A value typed for the run wins over everything.
        PreparedCall typed = fixture.gateway.prepare(inquire.withRunValues(Map.of("x", "from-run")));
        assertThat(typed.url().value()).isEqualTo("https://layers.example.invalid/from-run/Claim/$inquire");
        assertThat(use(typed.url().variables(), "x").source()).isEqualTo(VariableSource.RUN_VALUE);
        // A blank typed value is no value, and the next layer is asked.
        assertThat(fixture.gateway.prepare(inquire.withRunValues(Map.of("x", "   "))).url().value()).contains("/from-environment/");

        // An environment that defines the variable blank does not resolve it. Resolution falls through to the layer below.
        fixture.store.activate("Blank x");
        assertThat(fixture.gateway.prepare(inquire).url().value()).isEqualTo("https://layers.example.invalid/from-collection/Claim/$inquire");
        assertThat(use(fixture.gateway.prepare(inquire).url().variables(), "x").source()).isEqualTo(VariableSource.COLLECTION);
        assertThat(fixture.gateway.prepare(nextQuestion).url().value()).contains("/from-yml/");
    }

    @Test
    void aPlaceholderNoLayerHoldsIsUnresolvedAndABlankOneIsNotSentAsAnEmptyString() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Blank base", EnvironmentKind.SANDBOX, "crdUrl", "");
        fixture.collectionOf("Hooks", items(item("order-sign Happy Path", "POST", "{{crdUrl}}/cds-services/{{neverDefined}}")));

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));

        // Postman would send this to "/cds-services/". Here it has no url to send to at all.
        assertThat(call.url().resolved()).isFalse();
        assertThat(call.url().value()).isNull();
        assertThat(call.url().unresolved()).containsExactly("crdUrl", "neverDefined");
    }

    @Test
    void aValueThatRefersToOtherVariablesIsResolvedAcrossTheLayersAndACycleIsUnresolved() {
        ConfigFixture fixture = ConfigFixture.on(directory).live().onyx("variables.realm", "p32h").build();
        fixture.activeEnvironmentOf("Nested", EnvironmentKind.SANDBOX, "tokenUrl", "{{authHost}}/oauth2/{{realm}}/token", "a", "{{b}}", "b", "{{a}}");
        fixture.collectionOf("Auth", items(item("token inquiry", "POST", "{{tokenUrl}}"), item("looping inquiry", "POST", "https://x.example.invalid/{{a}}")),
                variables("authHost", "https://auth.example.invalid"));
        StoredCollection auth = fixture.store.snapshot().collections().get(0);

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(auth, "token inquiry")));
        // The environment's value, a part from the collection and a part from application.yml.
        assertThat(call.url().value()).isEqualTo("https://auth.example.invalid/oauth2/p32h/token");
        assertThat(use(call.url().variables(), "tokenUrl").source()).isEqualTo(VariableSource.ENVIRONMENT);
        assertThat(use(call.url().variables(), "authHost").source()).isEqualTo(VariableSource.COLLECTION);
        assertThat(use(call.url().variables(), "realm").source()).isEqualTo(VariableSource.APPLICATION_YML);

        // A variable that leads back to itself is unresolved, with the loop named. It is not a stack overflow.
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(auth, "looping inquiry"))))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> {
                    assertThat(e.variables()).containsExactly("a");
                    assertThat(e.reasons()).containsEntry("a", "Its value refers back to itself (a -> b -> a).");
                    assertThat(e.environmentName()).isEqualTo("Nested");
                });
    }

    // ------------------------------------------------------------------------------------------
    // A call's own collection
    // ------------------------------------------------------------------------------------------

    @Test
    void textResolvedThroughACallUsesTheVariablesOfTheCollectionItsRequestCameFrom() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        // Two collections define the same variable differently.
        fixture.collectionOf("Alpha", items(item("order-sign Happy Path", "POST", "https://alpha.example.invalid/cds-services/order-sign-{{tenant}}")
                .body("{\"tenant\": \"{{tenant}}\"}")), variables("tenant", "alpha"));
        fixture.collectionOf("Beta", items(item("order-select Happy Path", "POST", "https://beta.example.invalid/cds-services/order-select-{{tenant}}")
                .body("{\"tenant\": \"{{tenant}}\"}")), variables("tenant", "beta"));
        String body = "{\"tenant\": \"{{tenant}}\", \"sent\": \"{{$isoTimestamp}}\"}";

        PreparedCall alpha = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        PreparedCall beta = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SELECT));

        assertThat(alpha.url().value()).isEqualTo("https://alpha.example.invalid/cds-services/order-sign-alpha");
        assertThat(beta.url().value()).isEqualTo("https://beta.example.invalid/cds-services/order-select-beta");
        ResolvedText alphaBody = alpha.resolve(body);
        ResolvedText betaBody = beta.resolve(body);
        assertThat(alphaBody.value()).isEqualTo("{\"tenant\": \"alpha\", \"sent\": \"2026-01-05T15:04:05.000Z\"}");
        assertThat(betaBody.value()).isEqualTo("{\"tenant\": \"beta\", \"sent\": \"2026-01-05T15:04:05.000Z\"}");
        assertThat(use(alphaBody.variables(), "tenant").source()).isEqualTo(VariableSource.COLLECTION);
        assertThat(alphaBody.template()).isEqualTo(body);
        assertThat(alphaBody.resolved()).isTrue();

        // A value typed for the run still wins for the call it was typed for, and only for that one.
        PreparedCall typed = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN).withRunValues(Map.of("tenant", "typed")));
        assertThat(typed.resolve("{{tenant}}").value()).isEqualTo("typed");
        assertThat(alpha.resolve("{{tenant}}").value()).isEqualTo("alpha");
        // A placeholder with no value is listed and never thrown for.
        ResolvedText missing = alpha.resolve("{{tenant}} {{nobodyDefinesThis}}");
        assertThat(missing.resolved()).isFalse();
        assertThat(missing.value()).isNull();
        assertThat(missing.unresolved()).containsExactly("nobodyDefinesThis");
    }

    @Test
    void aCallFromTheFallbackLayerHasNoCollectionLayer() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("pas.url", "https://yml.example.invalid/fhir").build();
        fixture.collectionOf("Alpha", items(item("order-sign Happy Path", "POST", "https://alpha.example.invalid/cds-services/order-sign-a")),
                variables("tenant", "alpha"));

        PreparedCall inquire = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE));

        // The url came from application.yml, so no collection's variables belong to it.
        assertThat(inquire.requestName()).isNull();
        assertThat(inquire.resolve("{{tenant}}").unresolved()).containsExactly("tenant");
    }

    @Test
    void theVendorsSampleBodyResolvesWithGeneratedValuesAndSaysWhereEachCameFrom() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        String sample = vendor.request(ref(vendor, "order-sign Happy Path").requestId()).request().body();

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        ResolvedText body = call.resolve(sample);

        assertThat(body.resolved()).isTrue();
        assertThat(body.value()).contains("\"hookInstance\": \"00000000-0000-4000-8000-000000000001\"")
                .contains("\"fhirServer\": \"https://sandbox.onyx.example.invalid/fhir\"").doesNotContain("{{");
        assertThat(JsonSafe.parse(body.value()).ok()).isTrue();
        assertThat(use(body.variables(), "$guid").source()).isEqualTo(VariableSource.GENERATED);
        assertThat(use(body.variables(), "baseUrl").source()).isEqualTo(VariableSource.ENVIRONMENT);
        // Each resolution generates its own value, as each send in Postman would.
        assertThat(call.resolve(sample).value()).contains("00000000-0000-4000-8000-000000000002");
    }

    // ------------------------------------------------------------------------------------------
    // Text that belongs to no request
    // ------------------------------------------------------------------------------------------

    @Test
    void textThatBelongsToNoRequestResolvesFromTheRunTheEnvironmentAndApplicationYmlOnly() {
        ConfigFixture fixture = ConfigFixture.on(directory).live().onyx("variables.clientId", "yml-client").onyx("variables.scopes", "system/*.read")
                .build();
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collectionOf("Alpha", items(item("order-sign Happy Path", "POST", "https://alpha.example.invalid/cds-services/order-sign-a")),
                variables("onlyInACollection", "alpha"));

        ResolvedText tokenUrl = fixture.gateway.resolve("{{tokenUrl}}", Map.of());
        assertThat(tokenUrl.value()).isEqualTo("https://auth.sandbox.onyx.example.invalid/oauth2/token");
        assertThat(tokenUrl.display()).isEqualTo(tokenUrl.value());
        // The environment wins over application.yml, and application.yml supplies what the environment does not have.
        assertThat(fixture.gateway.resolve("{{clientId}} {{scopes}}", Map.of()).value()).isEqualTo("pave-sandbox-client system/*.read");
        assertThat(fixture.gateway.resolve("{{clientId}}", Map.of("clientId", "typed-client")).value()).isEqualTo("typed-client");
        assertThat(fixture.gateway.resolve("{{clientId}}", null).value()).isEqualTo("pave-sandbox-client");

        ResolvedText secret = fixture.gateway.resolve("{{clientSecret}}", Map.of());
        assertThat(secret.value()).isEqualTo("placeholder-sandbox-secret");
        assertThat(secret.display()).isEqualTo("[secret clientSecret]");

        // No collection layer: the text belongs to no request, so it belongs to no collection.
        ResolvedText fromCollection = fixture.gateway.resolve("{{onlyInACollection}}", Map.of());
        assertThat(fromCollection.resolved()).isFalse();
        assertThat(fromCollection.unresolved()).containsExactly("onlyInACollection");
        // Never thrown for, on live either. The caller decides what an unresolved setting means.
        assertThat(fixture.gateway.resolve("{{callbackUrl}}", Map.of()).unresolved()).containsExactly("callbackUrl");
        assertThat(fixture.gateway.resolve("plain text", Map.of()).value()).isEqualTo("plain text");
        assertThat(fixture.gateway.resolve("", Map.of()).value()).isEmpty();
        assertThat(fixture.gateway.resolve(null, Map.of())).isNull();
    }

    @Test
    void whatIsResolvedFollowsTheActiveEnvironmentWithNoRestart() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        assertThat(fixture.gateway.resolve("{{baseUrl}}", Map.of()).value()).isEqualTo("https://sandbox.onyx.example.invalid/fhir");

        fixture.store.activate("Onyx UAT");

        assertThat(fixture.gateway.resolve("{{baseUrl}}", Map.of()).value()).isEqualTo("https://uat.onyx.example.invalid/fhir");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).url().value())
                .isEqualTo("https://uat.onyx.example.invalid/fhir/Claim/$inquire");
        // The other environment's variables are gone with it, not merged in.
        assertThat(fixture.gateway.resolve("{{clientId}}", Map.of()).resolved()).isFalse();
    }

    // ------------------------------------------------------------------------------------------
    // Secrets read from the operating system
    // ------------------------------------------------------------------------------------------

    @Test
    void aPaveVariableOfTheOperatingSystemIsReadAtResolutionAndIsAlwaysMasked() {
        ConfigFixture fixture = ConfigFixture.on(directory).live().lookup("member.url", "https://lookup.example.invalid/members/{id}?ref={{gatewayRef}}")
                .lookup("member.headers.X-Member-Api", "{{memberApi}}").onyx("variables.memberApi", "{{env:PAVE_MEMBER_API}}").build();
        // The variable that points at the operating system is not called anything that reads as a credential.
        fixture.activeEnvironmentOf("Pointing", EnvironmentKind.SANDBOX, "gatewayRef", "{{env:PAVE_GATEWAY_REF}}", "clientSecret",
                "{{env:PAVE_ONYX_CLIENT_SECRET}}");
        fixture.operatingSystem.put("PAVE_GATEWAY_REF", "os-value-7781");
        fixture.operatingSystem.put("PAVE_MEMBER_API", "os-value-4410");
        fixture.operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "os-value-9034");

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", "S123456789"));

        assertThat(call.url().value()).isEqualTo("https://lookup.example.invalid/members/S123456789?ref=os-value-7781");
        assertThat(call.url().display()).isEqualTo("https://lookup.example.invalid/members/S123456789?ref=[secret gatewayRef]");
        VariableUse pointing = use(call.url().variables(), "gatewayRef");
        assertThat(pointing.source()).isEqualTo(VariableSource.OPERATING_SYSTEM);
        assertThat(pointing.secret()).isTrue();
        assertThat(pointing.display()).isEqualTo("[secret gatewayRef]");
        assertThat(use(call.url().variables(), "env:PAVE_GATEWAY_REF").source()).isEqualTo(VariableSource.OPERATING_SYSTEM);
        // The same through application.yml, into a header.
        ResolvedHeader header = call.headers().get(0);
        assertThat(header.name()).isEqualTo("X-Member-Api");
        assertThat(header.value()).isEqualTo("os-value-4410");
        assertThat(header.display()).isEqualTo("[secret memberApi]");

        ResolvedText secret = fixture.gateway.resolve("{{clientSecret}}", Map.of());
        assertThat(secret.value()).isEqualTo("os-value-9034");
        assertThat(secret.display()).isEqualTo("[secret clientSecret]");
        assertThat(use(secret.variables(), "clientSecret").source()).isEqualTo(VariableSource.OPERATING_SYSTEM);

        // It is read when it is resolved, so a changed value is picked up without a new upload.
        fixture.operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "os-value-rotated");
        assertThat(fixture.gateway.resolve("{{clientSecret}}", Map.of()).value()).isEqualTo("os-value-rotated");
    }

    @Test
    void anOperatingSystemVariableThatIsNotSetIsUnresolvedAndTheReasonSaysWhichOneIsMissing() {
        ConfigFixture fixture = ConfigFixture.live(directory);
        fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        fixture.collectionOf("Keyed", items(item("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire?key={{clientSecret}}")
                .header("X-Key", "{{clientSecret}}")));

        // The variable clientSecret is there. What is missing is the operating system variable it points at.
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> {
                    assertThat(e.variables()).containsExactly("env:PAVE_ONYX_CLIENT_SECRET");
                    assertThat(e.environmentName()).isEqualTo("Onyx Production");
                    assertThat(e.reasons()).containsOnlyKeys("env:PAVE_ONYX_CLIENT_SECRET");
                    assertThat(e.reasons().get("env:PAVE_ONYX_CLIENT_SECRET")).isEqualTo("The operating system variable "
                            + "PAVE_ONYX_CLIENT_SECRET is not set where this tool is running. Set it and restart the tool, or type a value "
                            + "for this run. It is read because {{clientSecret}} in the environment \"Onyx Production\" points at it.");
                });
        ResolvedText text = fixture.gateway.resolve("{{clientSecret}}", Map.of());
        assertThat(text.resolved()).isFalse();
        assertThat(text.unresolved()).containsExactly("env:PAVE_ONYX_CLIENT_SECRET");

        // Once it is set, the same call is prepared, and its header carries the secret unshown.
        fixture.operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "os-value-9034");
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE));
        assertThat(call.url().value()).endsWith("/Claim/$inquire?key=os-value-9034");
        assertThat(call.url().display()).endsWith("/Claim/$inquire?key=[secret clientSecret]");
        assertThat(call.headers().get(0).value()).isEqualTo("os-value-9034");
        assertThat(call.headers().get(0).display()).isEqualTo("[redacted]");
    }

    @Test
    void anUploadedEnvironmentCannotReadArbitraryVariablesOfTheServerProcess() {
        ConfigFixture fixture = ConfigFixture.live(directory.resolve("live"));
        fixture.operatingSystem.put("HOME", "/home/placeholder-user");
        fixture.operatingSystem.put("AWS_SECRET_ACCESS_KEY", "os-value-must-not-leak");
        fixture.operatingSystem.put("PAVE", "os-value-bare-prefix");
        fixture.activeEnvironmentOf("Curious", EnvironmentKind.SANDBOX, "baseUrl", "https://x.example.invalid/fhir", "leak", "{{env:AWS_SECRET_ACCESS_KEY}}");
        StoredCollection curious = fixture.collectionOf("Curious", items(
                item("direct inquiry", "POST", "{{baseUrl}}/Claim/$inquire?home={{env:HOME}}"),
                item("pointed inquiry", "POST", "{{baseUrl}}/Claim/$inquire?key={{leak}}"),
                item("bare inquiry", "POST", "{{baseUrl}}/Claim/$inquire?p={{env:PAVE}}")));

        for (Map.Entry<String, String> each : Map.of("direct inquiry", "env:HOME", "pointed inquiry", "env:AWS_SECRET_ACCESS_KEY",
                "bare inquiry", "env:PAVE").entrySet()) {
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ref(curious, each.getKey()))))
                    .as(each.getKey()).isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> {
                        assertThat(e.variables()).containsExactly(each.getValue());
                        assertThat(e.reasons().get(each.getValue())).startsWith("Only operating system variables whose names start with "
                                + "PAVE_ and hold nothing but letters, digits and underscores can be read");
                        assertThat(e.getMessage() + e.reasons()).doesNotContain("placeholder-user").doesNotContain("os-value");
                    });
        }
        // On mock, where nothing is refused for being unresolved, the value is not read either.
        ConfigFixture mock = ConfigFixture.on(directory.resolve("mock")).operatingSystem(fixture.operatingSystem).build();
        mock.activeEnvironmentOf("Curious", EnvironmentKind.SANDBOX, "leak", "{{env:HOME}}");
        ResolvedText text = mock.gateway.resolve("{{leak}} {{env:AWS_SECRET_ACCESS_KEY}}", Map.of());
        assertThat(text.value()).isNull();
        assertThat(text.display()).isNull();
        assertThat(text.unresolved()).containsExactly("env:HOME", "env:AWS_SECRET_ACCESS_KEY");
    }

    // ------------------------------------------------------------------------------------------
    // What is shown, printed and serialised
    // ------------------------------------------------------------------------------------------

    @Test
    void noFormOfAPreparedCallThatIsShownPrintedOrSerialisedHoldsASecretValue() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("variables.ymlPassword", "hunter2-from-yml").build();
        fixture.operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "hunter2-from-os");
        fixture.environmentOf("Secrets", EnvironmentKind.SANDBOX, "baseUrl", "https://x.example.invalid/fhir", "apiKey", "hunter2-by-name",
                "clientSecret", "{{env:PAVE_ONYX_CLIENT_SECRET}}", "greeting", "Hello {{apiKey}}", "authHeader", "Bearer {{apiKey}}");
        fixture.store.activate("Secrets");
        fixture.collectionOf("Secret users", items(item("inquiry Happy Path", "POST",
                "{{baseUrl}}/Claim/$inquire?k={{apiKey}}&s={{clientSecret}}&g={{greeting}}&c={{collectionTenant}}&y={{ymlPassword}}&t={{typedPassword}}")
                .header("X-Greeting", "{{greeting}}").header("Authorization", "{{authHeader}}").header("X-Plain", "{{clientSecret}} and more")),
                "[{\"key\": \"collectionTenant\", \"value\": \"hunter2-by-type\", \"type\": \"secret\"}]");
        String body = "{\"key\": \"{{apiKey}}\", \"greeting\": \"{{greeting}}\", \"secret\": \"{{clientSecret}}\", \"typed\": \"{{typedPassword}}\"}";

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRunValues(Map.of("typedPassword", "hunter2-typed")));
        ResolvedText resolvedBody = call.resolve(body);

        // What is sent holds every one of them.
        assertThat(call.url().value()).isEqualTo("https://x.example.invalid/fhir/Claim/$inquire?k=hunter2-by-name&s=hunter2-from-os"
                + "&g=Hello hunter2-by-name&c=hunter2-by-type&y=hunter2-from-yml&t=hunter2-typed");
        assertThat(resolvedBody.value()).contains("hunter2-by-name").contains("hunter2-from-os").contains("hunter2-typed");
        // What is shown holds none, including the one that is reached through another variable.
        assertThat(call.url().display()).isEqualTo("https://x.example.invalid/fhir/Claim/$inquire?k=[secret apiKey]&s=[secret clientSecret]"
                + "&g=Hello [secret apiKey]&c=[secret collectionTenant]&y=[secret ymlPassword]&t=[secret typedPassword]");
        assertThat(resolvedBody.display()).isEqualTo("{\"key\": \"[secret apiKey]\", \"greeting\": \"Hello [secret apiKey]\", "
                + "\"secret\": \"[secret clientSecret]\", \"typed\": \"[secret typedPassword]\"}");
        assertThat(call.headers()).extracting(ResolvedHeader::display).containsExactly("Hello [secret apiKey]", "Bearer [redacted]",
                "[secret clientSecret] and more");

        List<Object> forms = new ArrayList<>(List.of(call, call.url(), call.headers(), call.url().variables(), resolvedBody,
                resolvedBody.variables(), call.url().display(), resolvedBody.display(), fixture.store.snapshot(),
                fixture.store.snapshot().active(), fixture.store.snapshot().active().content()));
        for (Object form : forms) {
            assertThat(form.toString()).as(form.getClass().getSimpleName()).doesNotContain("hunter2");
        }
        String json = JsonSafe.mapper().writeValueAsString(Map.of("url", call.url(), "headers", call.headers(), "body", resolvedBody));
        assertThat(json).doesNotContain("hunter2").contains("[secret apiKey]").contains("[secret clientSecret]");
    }

    @Test
    void noPrintedFormOfWhatTheGatewayWorkedOutHoldsAValue() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Secrets", EnvironmentKind.SANDBOX, "baseUrl", "https://x.example.invalid/fhir", "apiKey", "hunter2-by-name");
        fixture.collectionOf("Secret users", items(item("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire?k={{apiKey}}&t={{typedPassword}}")
                // A credential typed literally into the collection. No variable name masks it.
                .header("Authorization", "Bearer hunter2-literal").header("X-Key", "{{apiKey}}")));

        CallPlan plan = fixture.gateway.describe(fixture.store.snapshot(), CallIntent.of(EpaOperation.PAS_INQUIRE)
                .withRunValues(Map.of("typedPassword", "hunter2-typed")));

        assertThat(plan.url().value()).contains("hunter2-by-name").contains("hunter2-typed");
        assertThat(plan.resolvedHeaders().get(0).value()).isEqualTo("Bearer hunter2-literal");
        for (Object form : List.of(plan, plan.target(), plan.headers(), plan.resolvedHeaders(), plan.filled(), plan.urlVariables(),
                plan.urlVariables().pieces(), plan.intent(), plan.snapshot(), plan.reasons(), plan.tokens())) {
            assertThat(form.toString()).as(form.getClass().getSimpleName()).doesNotContain("hunter2");
        }
    }
}
