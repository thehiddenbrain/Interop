package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.event;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.pas.NotificationView;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The notification the second member data audit used (F2): a service that puts a member ID and a
 * surname with a birth date into the subscription, claim and ClaimResponse ids it sends. A line
 * about a notification names no member, so these ids are held to the shape rule alone, and the
 * receiver says that it could not look them up rather than that no run made them.
 */
class NotificationIdsNotKeptTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final OnyxProperties ONYX = OnyxProperties.of(Map.of("subscription.callback-url", "https://pave.example.invalid/api/pas/notifications"));

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.defaults(), ONYX, ledgerProvider(),
            CLOCK);
    private final NotificationReceiver receiver = new NotificationReceiver(gateway, support, ledgerProvider(), scenarios);

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    @Test
    void idsThatReadLikeAMemberIdOrABirthDateAreNotKeptAndTheReceiverSaysItCouldNotLookThemUp() {
        String body = bundle(status("sub-S123456789", "event-notification", event(7, "ClaimResponse/S123456789")),
                claimResponse("S123456789", "Zzlastname-19610723", "A1"));
        NotificationView view = receiver.receive(NotificationReceiver.Inbound.of(body));

        RunRecord line = ledger.last();
        assertThat(line.correlation()).containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, "(not recorded, 14 characters)")
                .containsEntry(RunRecord.CORR_CLAIM_ID, "(not recorded, 19 characters)")
                .containsEntry(RunRecord.CORR_CLAIM_RESPONSE_ID, "(not recorded, 10 characters)");
        assertThat(JsonSafe.mapper().writeValueAsString(line.correlation())).doesNotContain("Zzlastname").doesNotContain("19610723")
                .doesNotContain("sub-S123456789");
        assertThat(view.correlationNotes()).contains(NotificationReceiver.NOT_KEPT_SUBSCRIPTION,
                "The submission it is about could not be looked up by the claim id or the ClaimResponse id it carries: they read like a "
                        + "member identifier or a date, which the ledger does not keep, so no run can be found by them.");
        assertThat(view.correlationNotes()).noneMatch(note -> note.startsWith("No run in the ledger created"))
                .noneMatch(note -> note.startsWith("No submission in the ledger carries"));
    }

    @Test
    void aSubscriptionIdTheLedgerKeepsIsStillMatchedToTheRunThatCreatedIt() {
        SubscriptionService subscriptions = new SubscriptionService(ONYX, gateway, new MockSubscriptionClient(scenarios, new MockExchanges(APP)),
                support);
        String created = subscriptions.run(EpaOperation.SUBSCRIPTION_CREATE, SubscriptionRequest.builder().build(), RunOrigin.manual(), null).runId();

        NotificationView view = receiver.receive(NotificationReceiver.Inbound.of(bundle(status("subscription-placeholder-0001", "heartbeat"))));
        assertThat(view.subscriptionRunId()).isEqualTo(created);
        assertThat(view.correlationNotes()).doesNotContain(NotificationReceiver.NOT_KEPT_SUBSCRIPTION);
    }
}
