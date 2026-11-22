package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.pas.SubscriptionRequest;
import org.p32h.interop.epa.pave.service.pas.SubscriptionService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * R-42 (integrator-2): a callback url the active environment types is the one a subscription
 * names; application.yml's applies only when the environment types none. It sits in this package
 * because only here can a test give a call the settings an environment would.
 */
class SubscriptionCallbackFromTheEnvironmentTest {

    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String FROM_YML = "https://yml-receiver.example.invalid/api/pas/notifications";
    private static final String TYPED = "https://{{receiverHost}}/api/pas/notifications";

    @Test
    void theEnvironmentsCallbackUrlIsTheOneTheSubscriptionNamesAndApplicationYmlsOtherwise() {
        String withTyped = service(TYPED).preview(EpaOperation.SUBSCRIPTION_CREATE, SubscriptionRequest.builder().build(), null).body();
        assertThat(withTyped).contains("https://env-receiver.example.invalid/api/pas/notifications").doesNotContain("yml-receiver");

        String withNone = service(null).preview(EpaOperation.SUBSCRIPTION_CREATE, SubscriptionRequest.builder().build(), null).body();
        assertThat(withNone).contains(FROM_YML);
    }

    private static SubscriptionService service(String typedCallback) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", new InMemoryLedger());
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        OnyxProperties onyx = OnyxProperties.of(Map.of("subscription.callback-url", FROM_YML));
        ScenarioRepository scenarios = new ScenarioRepository(APP);
        return new SubscriptionService(onyx, gateway(typedCallback), new MockSubscriptionClient(scenarios, new MockExchanges(APP)),
                new RunSupport(epa, TrackingProperties.defaults(), onyx, beans.getBeanProvider(RunLedger.class),
                        Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"))));
    }

    /** A gateway whose calls carry the callback url an environment types, resolving its one placeholder. */
    private static OperationGateway gateway(String typedCallback) {
        return new OperationGateway() {
            @Override
            public PreparedCall prepare(CallIntent intent) {
                return new IssuedCall(intent.operation(), intent.operation().defaultMethod(), ResolvedUrl.notConfigured("not configured"),
                        List.of(), new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX), null, false, () -> 0L,
                        text -> resolve(text, Map.of()), null, SentSecrets::none, Timeouts.APPLICATION,
                        new IssuedCall.Settings(null, null, null, typedCallback));
            }

            @Override
            public ResolvedText resolve(String template, Map<String, String> runValues) {
                return template == null ? null : ResolvedText.plain(template.replace("{{receiverHost}}", "env-receiver.example.invalid"));
            }

            @Override
            public EnvironmentRef activeEnvironment() {
                return new EnvironmentRef("Test Sandbox", EnvironmentKind.SANDBOX);
            }

            @Override
            public WriteAccess writeAccess(ProdUnlock unlock) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long epoch() {
                return 0;
            }
        };
    }
}
