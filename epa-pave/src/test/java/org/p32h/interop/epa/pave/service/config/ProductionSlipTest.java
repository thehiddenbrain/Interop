package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/**
 * An environment uploaded as prod by a slip of the mouse, and removed, leaves its addresses
 * production for good (files hunt, failure 2). The refusal of a later sandbox or uat upload keeps
 * the guardrail's sentence word for word (R-16); its field note says the environment is no longer
 * stored and gives the way to forget the address, should the prod upload have been the mistake,
 * in full, and the way works (R-35). While an environment that made the address production is
 * still stored, the note keeps its words too.
 */
class ProductionSlipTest {

    private static final String SLIPPED = "Vendor Sandbox (Postman 11)";
    private static final String ADDRESS = "https://sandbox.vendor.example.invalid/fhir/r4";

    @TempDir
    Path directory;

    private static byte[] environment(String name, String baseUrl) {
        return ("{\"name\": \"" + name + "\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + baseUrl + "\", \"enabled\": true}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void upload(ConfigFixture fixture, String name, String baseUrl, EnvironmentKind kind) {
        fixture.store.uploadEnvironment(name + ".json", environment(name, baseUrl), kind, true);
    }

    @Test
    void aSlipToProdThatWasRemovedIsNamedAsSuchAndTheWayOutItGivesWorks() throws IOException {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        upload(fixture, SLIPPED, ADDRESS, EnvironmentKind.PROD);
        fixture.store.removeEnvironment(SLIPPED);
        // A restart keeps the address production: it is read back from store.json.
        ConfigFixture restarted = ConfigFixture.mock(directory);

        for (EnvironmentKind kind : new EnvironmentKind[] {EnvironmentKind.SANDBOX, EnvironmentKind.UAT}) {
            assertThatThrownBy(() -> upload(restarted, SLIPPED, ADDRESS, kind)).isInstanceOfSatisfying(ValidationException.class, e -> {
                // The sentence as it was at round 2's head: the guardrail's words never change.
                assertThat(e).hasMessage("This environment was declared as " + kind.wire() + ", and its address " + ADDRESS + " is an "
                        + "address of the production environment \"" + SLIPPED + "\", or lies above or beneath one. It was not stored.");
                assertThat(e.fieldErrors()).singleElement().satisfies(note -> {
                    assertThat(note.field()).isEqualTo("kind");
                    assertThat(note.message()).isEqualTo("\"" + SLIPPED + "\" is no longer stored. If it was uploaded as prod by mistake, "
                            + "forget the address in Configuration > Production writes and upload this environment again. "
                            + "An address that this installation knows as production stays production, even after its environment is "
                            + "deleted. If this environment is production, upload it with the kind prod. If it is not, point its "
                            + "variables at the " + kind.wire() + " service and upload it again.");
                });
            });
        }
        assertThat(restarted.store.snapshot().environments()).isEmpty();

        // The way out, followed as written, with PAVE running: Forget, confirmed by the environment's name (R-35).
        String listed = restarted.service.environments().productionDestinations().get(0).destination();
        restarted.service.forgetDestination(listed, SLIPPED);
        upload(restarted, SLIPPED, ADDRESS, EnvironmentKind.SANDBOX);
        ConfigFixture afterwards = ConfigFixture.mock(directory);
        assertThat(afterwards.store.snapshot().byName(SLIPPED).kind()).isEqualTo(EnvironmentKind.SANDBOX);
    }

    @Test
    void whileTheProductionEnvironmentIsStoredTheRefusalKeepsItsWords() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        upload(fixture, SLIPPED, ADDRESS, EnvironmentKind.PROD);

        // Not removed: the guardrail never forgets an address of a stored prod environment, and offers no way to.
        assertThatThrownBy(() -> upload(fixture, "Vendor Sandbox Copy", ADDRESS, EnvironmentKind.SANDBOX))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e).hasMessage("This environment was declared as sandbox, and its address " + ADDRESS + " is an address of "
                            + "the production environment \"" + SLIPPED + "\", or lies above or beneath one. It was not stored.");
                    assertThat(e.fieldErrors().get(0).message()).isEqualTo("An address that this installation knows as production stays "
                            + "production, even after its environment is deleted. If this environment is production, upload it with the "
                            + "kind prod. If it is not, point its variables at the sandbox service and upload it again.");
                });
    }

    @Test
    void anAddressAlsoHeldByAStoredProductionEnvironmentIsNotOfferedToBeForgotten() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        upload(fixture, "Payer Live A", ADDRESS, EnvironmentKind.PROD);
        upload(fixture, "Payer Live B", ADDRESS, EnvironmentKind.PROD);
        fixture.store.removeEnvironment("Payer Live A");

        assertThatThrownBy(() -> upload(fixture, "Vendor Sandbox Copy", ADDRESS, EnvironmentKind.SANDBOX))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.getMessage()).contains("is an address of the production environments \"Payer Live A\" and \"Payer Live B\"")
                            .doesNotContain("removed");
                    assertThat(e.fieldErrors().get(0).message()).startsWith("An address that this installation knows as production stays "
                            + "production").doesNotContain("Production writes");
                });
    }

    @Test
    void addressesRememberedFromTwoRemovedEnvironmentsNameBoth() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        upload(fixture, "Payer Live A", ADDRESS, EnvironmentKind.PROD);
        upload(fixture, "Payer Live B", ADDRESS, EnvironmentKind.PROD);
        fixture.store.removeEnvironment("Payer Live A");
        fixture.store.removeEnvironment("Payer Live B");

        assertThatThrownBy(() -> upload(fixture, "Vendor Sandbox Copy", ADDRESS, EnvironmentKind.SANDBOX))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.getMessage()).contains("is an address of the production environments \"Payer Live A\" and "
                            + "\"Payer Live B\", or lies above or beneath one.").doesNotContain("removed");
                    assertThat(e.fieldErrors().get(0).message()).startsWith("\"Payer Live A\" and \"Payer Live B\" are no longer stored. "
                            + "If they were uploaded as prod by mistake, forget their addresses in Configuration > Production writes and "
                            + "upload this environment again. ")
                            .endsWith("If it is not, point its variables at the sandbox service and upload it again.");
                });
    }
}
