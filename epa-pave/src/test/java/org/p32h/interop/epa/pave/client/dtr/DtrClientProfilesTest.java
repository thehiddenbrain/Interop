package org.p32h.interop.epa.pave.client.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.client.support.OnyxAccess;
import org.p32h.interop.epa.pave.client.support.OnyxAccessTestFactory;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The DTR service is handed one client by Spring, and the profile decides which. A client that
 * lost its profile, or its place among the beans, would not be noticed until the service is
 * registered: nothing in the application asks for a DTR client before then.
 *
 * <p>Only the client package is scanned, and what its two classes are built on is handed in. The
 * whole application cannot be started on the live profile here, because the live sender needs the
 * gateway and the gateway is not a bean yet.
 */
class DtrClientProfilesTest {

    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));

    @Configuration
    @ComponentScan(basePackageClasses = OnyxDtrClient.class)
    static class DtrClientsOnly {
    }

    private static ApplicationContextRunner onProfile(String profile) {
        return new ApplicationContextRunner()
                // As a property and not through an initializer: the runner registers the beans below before it runs
                // an initializer, and the live sender is itself held to the live profile at the moment it is registered.
                .withPropertyValues("spring.profiles.active=" + profile)
                .withBean(ScenarioRepository.class, () -> new ScenarioRepository(APP))
                .withBean(MockExchanges.class, () -> new MockExchanges(APP))
                // Never called: the test only asks which client was built.
                .withBean(OnyxAccess.class, () -> OnyxAccessTestFactory.create(new ExchangeExecutor(), OnyxProperties.defaults(),
                        new FakeGateway(), RestClient.builder().build(), Clock.systemUTC()))
                .withUserConfiguration(DtrClientsOnly.class);
    }

    @Test
    void onTheMockProfileTheOnlyDtrClientIsTheMockOne() {
        onProfile("mock").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OnyxDtrClient.class);
            assertThat(context.getBean(OnyxDtrClient.class)).isExactlyInstanceOf(MockOnyxDtrClient.class);
        });
    }

    @Test
    void onTheLiveProfileTheOnlyDtrClientIsTheLiveOne() {
        onProfile("live").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OnyxDtrClient.class);
            assertThat(context.getBean(OnyxDtrClient.class)).isExactlyInstanceOf(HttpOnyxDtrClient.class);
        });
    }
}
