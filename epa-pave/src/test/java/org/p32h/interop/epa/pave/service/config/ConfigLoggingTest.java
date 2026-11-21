package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** What the configuration layer writes to the log: what happened, with names and counts, and never a value. */
@ExtendWith(OutputCaptureExtension.class)
class ConfigLoggingTest {

    @TempDir
    Path directory;

    @Test
    void theLogSaysWhatHappenedAndHoldsNoVariableValueNoSecretAndNothingThatWasTyped(CapturedOutput output) {
        ConfigFixture fixture = ConfigFixture.on(directory).live().allowProdWrites().onyx("variables.ymlPassword", "hunter2-from-yml")
                .lookup("member.url", "https://lookup.example.invalid/members/{id}").lookup("member.headers.X-Api-Key", "hunter2-header").build();
        fixture.operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "hunter2-from-os");

        // Everything the Config tab can do, and the calls a run makes, with secrets at every turn.
        fixture.service.uploadEnvironment("sandbox.json", PostmanFiles.bytes(PostmanFiles.SANDBOX_ENVIRONMENT), EnvironmentKind.SANDBOX, false);
        fixture.service.uploadEnvironment("production.json", PostmanFiles.bytes(PostmanFiles.PRODUCTION_ENVIRONMENT), EnvironmentKind.PROD, false);
        fixture.service.uploadCollection("vendor.json", PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION));
        fixture.service.uploadCollection("nested.json", PostmanFiles.bytes(PostmanFiles.NESTED_COLLECTION));
        StoredCollection keyed = fixture.collectionOf("Keyed", items(item("keyed inquiry", "POST", "{{baseUrl}}/Claim/$inquire?key={{clientSecret}}")
                .header("Authorization", "Bearer literal-token-typed-in").body("{\"password\": \"{{typedPassword}}\"}")));
        assertThatThrownBy(() -> fixture.service.uploadEnvironment("page.html", "<html>placeholder-secret-in-a-page</html>"
                .getBytes(StandardCharsets.UTF_8), EnvironmentKind.SANDBOX, false)).isInstanceOf(BadInputException.class);
        assertThatThrownBy(() -> fixture.service.uploadEnvironment("p.json", PostmanFiles.bytes(PostmanFiles.SANDBOX_IN_NAME_ONLY),
                EnvironmentKind.SANDBOX, false)).isInstanceOf(ValidationException.class);
        fixture.service.activate("Onyx Sandbox", null);
        fixture.service.environments();
        fixture.service.collections();
        fixture.service.effective(null);
        fixture.service.correctMapping(keyed.id(), keyed.requests().get(0).request().id(), EpaOperation.UNMAPPED);
        fixture.service.correctMapping(keyed.id(), keyed.requests().get(0).request().id(), EpaOperation.PAS_INQUIRE);
        Map<String, String> typed = Map.of("typedPassword", "hunter2-typed", "subscriptionId", "typed-subscription-id");
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(ConfigFixture.ref(keyed, "keyed inquiry"))
                .withRunValues(typed));
        call.resolve("{\"password\": \"{{typedPassword}}\", \"secret\": \"{{clientSecret}}\"}");
        fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", "S123456789"));
        fixture.gateway.resolve("{{clientSecret}} {{ymlPassword}}", typed);
        fixture.service.resolve(EpaOperation.PAS_INQUIRE, ConfigFixture.ref(keyed, "keyed inquiry"), typed, Map.of(), null);
        fixture.service.activate("Onyx Production", null);
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT))).isInstanceOf(ProdWriteBlockedException.class);
        assertThatThrownBy(() -> fixture.service.unlock("a-wrong-name-that-was-typed")).isInstanceOf(ValidationException.class);
        ProdUnlock unlock = fixture.service.unlock("Onyx Production");
        fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withUnlock(unlock).withRunValues(typed));
        fixture.service.lock();
        fixture.service.removeCollection(keyed.id());
        fixture.service.removeEnvironment("Onyx Sandbox");
        // And a restart on top of all of it.
        ConfigFixture.on(directory).live().build();

        String log = output.getAll();
        assertThat(log).contains("Environment \"Onyx Sandbox\" uploaded as sandbox, variables 12")
                .contains("Environment \"Onyx Production\" uploaded as prod, variables 5")
                .contains("Collection \"Onyx Sandbox ePA\" uploaded, requests 13")
                .contains("Active environment switched to \"Onyx Sandbox\" (sandbox)")
                .contains("Active environment switched to \"Onyx Production\" (prod)")
                .contains("Mapping of one request corrected to UNMAPPED")
                .contains("An unlock of production writes was refused on \"Onyx Production\": the name typed did not match")
                .contains("Production writes were unlocked for one browser session on \"Onyx Production\"")
                .contains("Production writes were locked again")
                .contains("Collection \"Keyed\" removed, with its mapping corrections")
                .contains("Environment \"Onyx Sandbox\" removed")
                .contains("Configuration store ready at " + directory + ": environments 1, collections 2, active environment: \"Onyx Production\"");
        assertThat(log)
                // Secrets, from every place one can come from.
                .doesNotContain("hunter2").doesNotContain("placeholder-sandbox-secret").doesNotContain("placeholder-tenant-value")
                .doesNotContain("placeholder-collection-key").doesNotContain("literal-token-typed-in").doesNotContain("placeholder-secret-in-a-page")
                // Variable values that are not secrets are not logged either.
                .doesNotContain("onyx.example.invalid").doesNotContain("pave-sandbox-client").doesNotContain("sample-subscription-1")
                .doesNotContain("collection-order-sign-service").doesNotContain("lookup.example.invalid")
                // Nor anything that was typed: a run value, an identifier, a name that was not the environment's.
                .doesNotContain("typed-subscription-id").doesNotContain("S123456789").doesNotContain("a-wrong-name-that-was-typed")
                // Nor a url, a header or a body of a collection request.
                .doesNotContain("Claim/$").doesNotContain("Bearer").doesNotContain("resourceType");
    }

    @Test
    void aConfiguredActiveEnvironmentThatIsNotInTheStoreIsWarnedAboutOnce(CapturedOutput output) {
        ConfigFixture fixture = ConfigFixture.on(directory).activeEnvironment("Onyx Sandbox").build();
        fixture.service.effective(null);
        fixture.service.environments();
        fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);

        assertThat(output.getAll().lines().filter(line -> line.contains("config-store.active-environment names \"Onyx Sandbox\""))).hasSize(1);
        assertThat(output.getAll()).contains("which is not in the configuration store. No environment is active.");
    }

    @Test
    void aNameFromAnUploadedFileIsLoggedOnOneLine(CapturedOutput output) {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        fixture.activeEnvironmentOf("Team\nWARN forged line", EnvironmentKind.SANDBOX, "baseUrl", "https://x.example.invalid");
        fixture.collectionOf("Sample\r\nERROR another forged line", items(item("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire")));

        // The name is whatever the file says. Written as it is, it would start log lines of its own.
        assertThat(output.getAll().lines()).noneMatch(line -> line.startsWith("WARN forged line") || line.startsWith("ERROR another forged line"));
        assertThat(output.getAll()).contains("Environment \"Team WARN forged line\" uploaded as sandbox")
                .contains("Active environment switched to \"Team WARN forged line\" (sandbox)");
        // The stored name is untouched: it is the identity of the environment, and is what an unlock would have to match.
        assertThat(fixture.gateway.activeEnvironment().name()).isEqualTo("Team\nWARN forged line");
    }
}
