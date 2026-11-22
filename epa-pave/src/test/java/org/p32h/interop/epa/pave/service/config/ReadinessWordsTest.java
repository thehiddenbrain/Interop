package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;

/**
 * I-1 and I-15: the readiness of an operation, as the Workbench and /api/config/effective say it,
 * in plain words: one sentence with no environment active, the values as the tester knows them
 * without braces, and the place in Configuration where an address or a value is typed.
 */
class ReadinessWordsTest {

    @TempDir
    Path directory;

    private static void plain(String sentence) {
        assertThat(sentence).doesNotContain("{{").doesNotContain("onyx.").doesNotContain(".url").doesNotContain("Config tab");
    }

    @Test
    void eachFormIsOneExactSentence() {
        ConfigFixture none = ConfigFixture.on(directory.resolve("none")).live().onyx("crd.url", "{{crdUrl}}").build();
        String noEnvironment = none.gateway.describe(none.store.snapshot(), CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).incomplete();
        assertThat(noEnvironment).isEqualTo("No environment is active. Open Configuration and make one active.");
        String noTarget = none.gateway.describe(none.store.snapshot(), CallIntent.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE)).incomplete();
        assertThat(noTarget).isEqualTo("No environment is active. Open Configuration and make one active.");

        ConfigFixture active = ConfigFixture.on(directory.resolve("active")).live().onyx("crd.url", "{{crdUrl}}").build();
        active.activeEnvironmentOf("Standin Sandbox", EnvironmentKind.SANDBOX, "baseUrl", "http://127.0.0.1:18745/sbx");
        String values = active.gateway.describe(active.store.snapshot(), CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).incomplete();
        assertThat(values).isEqualTo("The order-sign address needs a value for crdUrl, crdOrderSignServiceId. Add it under Values of the "
                + "environment in Configuration.");
        String address = active.gateway.describe(active.store.snapshot(), CallIntent.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE)).incomplete();
        assertThat(address).isEqualTo("No address for DTR package. Type one under Addresses of the environment in Configuration.");
        for (String sentence : List.of(noEnvironment, values, address)) {
            plain(sentence);
        }
        // The rows of /api/config/effective say the same on live.
        for (ConfigViews.OperationRow row : active.service.effective(null).operations()) {
            if (row.unavailable() != null) {
                plain(row.unavailable());
            }
        }
    }
}
