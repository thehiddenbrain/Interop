package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.AnsweredBy;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Who a notification's ledger line says answered it. A sample a scenario file played on the mock
 * profile was answered by a mock scenario, beside the scenario id on the same line; a notification
 * that arrived on the live profile came from the service. The History row and the export read it.
 */
class NotificationAnsweredByTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final OnyxProperties ONYX = OnyxProperties.of(Map.of("subscription.callback-url", "https://pave.example.invalid/api/pas/notifications"));

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private NotificationReceiver receiver(String mode) {
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", mode)), TrackingProperties.defaults(), ONYX, ledgerProvider(), CLOCK);
        return new NotificationReceiver(FakeGateway.unconfigured(), support, ledgerProvider(), scenarios);
    }

    @Test
    void aSampleAScenarioFilePlayedOnMockIsRecordedAsAnsweredByTheMockScenarioAndNotByTheService() {
        receiver("mock").deliverSample(new NotificationSampleRequest("pas-12-notification-unmatched", null, null));

        RunRecord line = ledger.last();
        assertThat(line.operation()).isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(line.scenarioId()).isEqualTo("pas-12-notification-unmatched");
        assertThat(line.answeredBy()).isEqualTo(AnsweredBy.SCENARIO);
        // Nothing was sent by this tool, so nothing was timed.
        assertThat(line.elapsedMillis()).isNull();
    }

    @Test
    void aNotificationThatArrivedOnLiveIsRecordedAsFromTheService() {
        receiver("live").receive(NotificationReceiver.Inbound.of("{\"resourceType\":\"Bundle\",\"type\":\"history\",\"entry\":[]}"));

        RunRecord line = ledger.last();
        assertThat(line.operation()).isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(line.scenarioId()).isNull();
        assertThat(line.answeredBy()).isEqualTo(AnsweredBy.SERVICE);
    }

    @Test
    void theOneRuleTellsANotificationByTheProfileItArrivedOn() {
        assertThat(AnsweredBy.of(EpaOperation.SUBSCRIPTION_NOTIFICATION, "mock", false)).isEqualTo(AnsweredBy.SCENARIO);
        assertThat(AnsweredBy.of(EpaOperation.SUBSCRIPTION_NOTIFICATION, "live", false)).isEqualTo(AnsweredBy.SERVICE);
        assertThat(AnsweredBy.of(EpaOperation.SUBSCRIPTION_NOTIFICATION, "mock", true)).isEqualTo(AnsweredBy.NOT_SENT);
        // A step done inside the tool stays nobody's answer on both profiles.
        assertThat(AnsweredBy.of(EpaOperation.DTR_RESPONSE, "mock", false)).isEqualTo(AnsweredBy.NOBODY);
    }
}
