package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;

/**
 * hunt-files 12: the address checks of an environment are worked out once per epoch and reused by
 * every list read at that epoch, and worked out again after a change, so a warning is never stale.
 */
class AddressChecksOncePerEpochTest {

    @TempDir
    Path directory;

    private static byte[] environment(String name, String baseUrl) {
        return ("{\"name\": \"" + name + "\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + baseUrl + "\", \"enabled\": true}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> warnings(ConfigFixture fixture, String name) {
        return fixture.service.environments().environments().stream().filter(view -> view.name().equals(name)).findFirst().orElseThrow()
                .addressWarnings();
    }

    @Test
    void oneEpochIsCheckedOnceAndTheNextIsCheckedAgain() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.store.uploadEnvironment("sbx.json", environment("Vendor Sandbox", "https://vendor.example.invalid/sandbox"),
                EnvironmentKind.SANDBOX, false);

        List<String> first = warnings(fixture, "Vendor Sandbox");
        assertThat(first).isEmpty();
        // The same answer object: nothing was read through the production rules a second time.
        assertThat(warnings(fixture, "Vendor Sandbox")).isSameAs(first);

        // A production environment on the same server under another path is a change, and the list says so at once.
        fixture.store.uploadEnvironment("live.json", environment("Vendor Live", "https://vendor.example.invalid/live"),
                EnvironmentKind.PROD, false);
        List<String> after = warnings(fixture, "Vendor Sandbox");
        assertThat(after).singleElement().asString().startsWith("This environment is on the same server, by scheme, host and port, as "
                + "the production environment \"Vendor Live\"");
        assertThat(fixture.service.activate("Vendor Sandbox", null).addressWarnings()).isEqualTo(after);
    }
}
