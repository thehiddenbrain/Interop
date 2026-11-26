package org.p32h.interop.epa.pave.service.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The ledger in the application as it starts on the live profile, where the member IDs are real.
 *
 * <p>Nothing is sent anywhere: the test starts the application and writes one run to the ledger.
 */
@SpringBootTest
@ActiveProfiles("live")
class LedgerOnTheLiveProfileTest {

    @TempDir
    static Path home;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("tracking.directory", () -> home.resolve("run-history").toString());
        registry.add("config-store.directory", () -> home.resolve("config-store").toString());
    }

    /**
     * The live clients are given a gateway by another part of the tool. Where the build has none,
     * a stand-in is registered so the live profile can start. Where it has one, that one is used
     * and this does nothing: the real gateway is never replaced.
     */
    @TestConfiguration
    static class AGatewayWhereThereIsNone {

        @Bean
        static BeanDefinitionRegistryPostProcessor standInGateway() {
            return registry -> {
                if (registry instanceof ListableBeanFactory beans && beans.getBeanNamesForType(OperationGateway.class, true, false).length == 0) {
                    registerStandIn(registry);
                }
            };
        }

        private static void registerStandIn(BeanDefinitionRegistry registry) {
            registry.registerBeanDefinition("standInOperationGateway",
                    BeanDefinitionBuilder.genericBeanDefinition(OperationGateway.class, FakeGateway::unconfigured).getBeanDefinition());
        }
    }

    @Autowired RunLedger ledger;
    @Autowired EpaProperties epa;
    @Autowired TrackingProperties tracking;
    @Autowired Clock clock;

    @Test
    void onLiveTheLedgerIsThereAndKeepsATokenInPlaceOfTheMemberIdByDefault() throws IOException {
        assertThat(epa.live()).isTrue();
        assertThat(ledger).isInstanceOf(JsonlRunLedger.class);
        // The default on live. Nobody has to remember to set it.
        assertThat(tracking.recordMemberId()).isFalse();
        assertThat(tracking.recordNpi()).isTrue();

        LedgerReceipt receipt = ledger.record(LedgerFixtures.draft("run-on-live-1", clock).profile("live").scenarioId(null).build());

        assertThat(receipt.recorded()).isTrue();
        // Whatever was written, in whichever day's file: the test must not care what the time is.
        StringBuilder written = new StringBuilder();
        try (Stream<Path> files = Files.list(home.resolve("run-history"))) {
            for (Path file : files.toList()) {
                written.append(Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        String line = written.toString();
        assertThat(line).contains("\"runId\":\"run-on-live-1\"").doesNotContain(LedgerFixtures.MEMBER_ID).doesNotContain("2026-03-20")
                .contains("\"memberToken\":\"" + ledger.tokenFor(LedgerFixtures.MEMBER_ID) + "\"").contains("\"memberIdLast4\":\"6789\"");
        assertThat(ledger.query(null)).extracting(RunRecord::runId).contains("run-on-live-1");
        assertThat(ledger.health().enabled()).isTrue();
        assertThat(ledger.health().writable()).isTrue();
    }
}
