package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;

/**
 * A value for a single-brace url part is held to the rule a value typed for the run is held to: it
 * may fill its own segment, and it may not take a write out of the path the configuration wrote
 * before it. Encoding keeps a slash or a scheme in the value inside the segment; a value of
 * {@code ..} needs this rule.
 */
class UrlPartGuardrailTest {

    private static final String BASE = "https://onyx.example.invalid/fhir";

    @TempDir
    Path directory;

    private ConfigFixture fixture() {
        ConfigFixture fixture = ConfigFixture.on(directory).live().build();
        fixture.activeEnvironmentOf("Onyx Sandbox", EnvironmentKind.SANDBOX, "baseUrl", BASE);
        fixture.collectionOf("Vendor", ConfigFixture.items(
                ConfigFixture.item("subscription-delete Happy Path", "DELETE", "{{baseUrl}}/Subscription/{id}"),
                ConfigFixture.item("subscription-get Happy Path", "GET", "{{baseUrl}}/Subscription/{id}")));
        return fixture;
    }

    @Test
    void aWriteWhoseUrlPartValueClimbsOutOfItsPathIsRefusedAndOneThatStaysInItsSegmentGoes() {
        ConfigFixture fixture = fixture();
        CallIntent delete = CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE);

        assertThatThrownBy(() -> fixture.gateway.prepare(delete.withUrlValue("id", ".."))).isInstanceOfSatisfying(
                ProdWriteBlockedException.class, e -> assertThat(e.getMessage()).contains("the value typed for this run for the url part "
                        + "{id} would change where it goes"));

        assertThat(fixture.gateway.prepare(delete.withUrlValue("id", "SUB-9")).url().value()).isEqualTo(BASE + "/Subscription/SUB-9");
        // Encoded into its one segment, it would still hold an encoded slash, which no request carries in its path.
        assertThatThrownBy(() -> fixture.gateway.prepare(delete.withUrlValue("id", "../Claim"))).isInstanceOf(
                org.p32h.interop.epa.pave.service.error.BadInputException.class);
    }

    @Test
    void aReadIsNotAWriteAndIsNotHeldToTheRuleOfWrites() {
        ConfigFixture fixture = fixture();
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withUrlValue("id", "SUB-1")).url().value())
                .isEqualTo(BASE + "/Subscription/SUB-1");
    }
}
