package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The same wiring on the live profile, with nothing configured. Nothing is sent: only what the gateway decides is looked at. */
@SpringBootTest
@ActiveProfiles("live")
class ConfigOnTheLiveProfileTest {

    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void storeDirectory(DynamicPropertyRegistry registry) {
        registry.add("config-store.directory", () -> directory.resolve("config-store").toString());
    }

    @Autowired ApplicationContext context;
    @Autowired OperationGateway gateway;
    @Autowired EpaProperties epa;

    @Test
    void thereIsExactlyOneGatewayOnTheLiveProfileToo() {
        assertThat(epa.live()).isTrue();
        assertThat(context.getBeansOfType(OperationGateway.class)).hasSize(1);
        assertThat(gateway).isInstanceOf(ConfigStoreGateway.class);
    }

    @Test
    void withNothingConfiguredAReadHasNowhereToGoAndAWriteIsRefusedBeforeThat() {
        assertThatThrownBy(() -> gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN))).isInstanceOf(EndpointNotConfiguredException.class);
        // With no environment there is no kind, so a write is refused whatever its target.
        assertThatThrownBy(() -> gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT))).isInstanceOf(ProdWriteBlockedException.class);
        assertThat(gateway.activeEnvironment().active()).isFalse();
        assertThat(gateway.writeAccess(null).writesAllowed()).isFalse();
        assertThat(gateway.epoch()).isPositive();
    }
}
