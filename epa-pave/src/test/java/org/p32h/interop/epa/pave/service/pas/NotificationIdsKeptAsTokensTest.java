package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.event;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.status;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.pas.NotificationView;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.ledger.JsonlRunLedger;
import org.p32h.interop.epa.pave.service.ledger.LedgerFixtures;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Identifiers a service or a notification supplies, on the real ledger with the default tracking
 * settings. Found by the second member data audit (F2: a member ID and a surname with a birth date
 * in the subscription, claim and ClaimResponse ids) and by the independent check of its fix (N2: a
 * member ID whose digits a separator splits was kept in clear; N4: a number a FHIR server mints was
 * no longer kept, so its notification matched nothing). Shape cannot tell these apart, so each is
 * written as a token, and a notification is matched by comparing tokens.
 */
class NotificationIdsKeptAsTokensTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), LedgerFixtures.ZONE);
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final OnyxProperties ONYX = OnyxProperties.of(Map.of("subscription.callback-url", "https://pave.example.invalid/api/pas/notifications"));

    @TempDir
    Path home;

    private JsonlRunLedger ledger;
    private RunSupport support;
    private NotificationReceiver receiver;
    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private void start() {
        ledger = LedgerFixtures.ledger(home, CLOCK);
        support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.defaults(), ONYX, provider(), CLOCK);
        receiver = new NotificationReceiver(gateway, support, provider(), scenarios);
    }

    private ObjectProvider<RunLedger> provider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private String file() {
        return String.join("\n", LedgerFixtures.lines(LedgerFixtures.dayFile(home, java.time.LocalDate.of(2026, 3, 10))));
    }

    private void recorded(String runId, EpaOperation operation, String outcome, String key, String value) {
        ledger.record(LedgerFixtures.draft(runId, CLOCK).protocol(operation == EpaOperation.PAS_SUBMIT ? Protocol.PAS : Protocol.SUBSCRIPTION)
                .operation(operation).outcome(outcome).memberId(null).coverageCodes(List.of()).expected(null)
                .result(org.p32h.interop.epa.pave.model.TestResult.UNVERIFIED).correlation(Map.of(key, value)).build());
    }

    @Test
    void theAuditorsNotificationLeavesOnlyTokensOnTheLineAndIsLookedUpByThem() {
        start();
        String body = bundle(status("sub-S123456789", "event-notification", event(7, "ClaimResponse/S123456789")),
                claimResponse("S123456789", "Zzlastname-19610723", "A1"));
        NotificationView view = receiver.receive(NotificationReceiver.Inbound.of(body));

        RunRecord line = ledger.find(view.id()).orElseThrow();
        assertThat(line.correlation()).containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, ledger.tokenFor("sub-S123456789"))
                .containsEntry(RunRecord.CORR_CLAIM_ID, ledger.tokenFor("Zzlastname-19610723"))
                .containsEntry(RunRecord.CORR_CLAIM_RESPONSE_ID, ledger.tokenFor("S123456789"));
        assertThat(file()).doesNotContain("S123456789").doesNotContain("Zzlastname").doesNotContain("19610723");
        // Every id was looked up, so the receiver can say that no run created the subscription.
        assertThat(view.correlationNotes()).contains("No run in the ledger created the subscription it names.")
                .doesNotContain(NotificationReceiver.NOT_KEPT_SUBSCRIPTION).noneMatch(note -> note.contains("reads like"));
        // The screen still shows what arrived: a result is not the ledger.
        assertThat(view.subscriptionId()).isEqualTo("sub-S123456789");
    }

    @Test
    void aSubscriptionWhoseIdIsAPlainNumberIsMatchedToTheRunThatCreatedItAndSoIsAClaimResponseId() {
        start();
        recorded("run-sub", EpaOperation.SUBSCRIPTION_CREATE, SubscriptionOutcome.CREATED.name(), RunRecord.CORR_SUBSCRIPTION_ID, "1052344");
        recorded("run-pas", EpaOperation.PAS_SUBMIT, "APPROVED", RunRecord.CORR_CLAIM_RESPONSE_ID, "cr-1052344");
        assertThat(file()).doesNotContain("1052344");

        NotificationView view = receiver.receive(NotificationReceiver.Inbound.of(bundle(status("1052344", "event-notification",
                event(1, "ClaimResponse/cr-1052344")))));

        assertThat(view.subscriptionRunId()).isEqualTo("run-sub");
        assertThat(view.correlatedRunId()).isEqualTo("run-pas");
        assertThat(view.correlationNotes()).contains("The subscription it names was created by the run run-sub.",
                "It is about the submission run-pas, matched by the ClaimResponse id it carries.");
    }

    @Test
    void aMemberIdWithItsDigitsSplitIsATokenOnTheSubscriptionLineAndOnTheNotificationLineAndTheyMatch() {
        start();
        SubscriptionService subscriptions = new SubscriptionService(ONYX, gateway, new MockSubscriptionClient(scenarios, new MockExchanges(APP)),
                support);
        String created = subscriptions.run(EpaOperation.SUBSCRIPTION_CREATE, SubscriptionRequest.builder().build(), RunOrigin.manual(), null)
                .runId();
        // The mock's own id is not a UUID, so on the default settings it is a token too, and still matched.
        assertThat(ledger.find(created).orElseThrow().correlation().get(RunRecord.CORR_SUBSCRIPTION_ID)).matches("[0-9a-f]{64}");
        assertThat(receiver.receive(NotificationReceiver.Inbound.of(bundle(status("subscription-placeholder-0001", "heartbeat"))))
                .subscriptionRunId()).isEqualTo(created);

        List<String> ids = List.of("S123-456-789", "S12345-6789", "S12345.6789", "sub-S12345-6789", "ZZQ12345-01");
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            recorded("run-" + i, EpaOperation.SUBSCRIPTION_CREATE, SubscriptionOutcome.CREATED.name(), RunRecord.CORR_SUBSCRIPTION_ID, id);
            NotificationView view = receiver.receive(NotificationReceiver.Inbound.of(bundle(status(id, "heartbeat"))));
            assertThat(view.subscriptionRunId()).as(id).isEqualTo("run-" + i);
            assertThat(ledger.find(view.id()).orElseThrow().correlation().get(RunRecord.CORR_SUBSCRIPTION_ID)).isEqualTo(ledger.tokenFor(id));
        }
        assertThat(file()).doesNotContain("S123-456-789").doesNotContain("S12345").doesNotContain("ZZQ12345");
    }

    @Test
    void aUuidAnOidAndTheClaimIdThisToolMintsAreWrittenInClear() {
        start();
        String uuid = "0d1e2f3a-4b5c-4d6e-8f70-0a1b2c3d4e5f";
        NotificationView view = receiver.receive(NotificationReceiver.Inbound.of(bundle(status(uuid, "event-notification",
                event(1, "ClaimResponse/claimresponse-claim-" + uuid)), claimResponse("claimresponse-claim-" + uuid, "claim-" + uuid, "A1"))));
        RunRecord line = ledger.find(view.id()).orElseThrow();
        assertThat(line.correlation()).containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, uuid).containsEntry(RunRecord.CORR_CLAIM_ID, "claim-" + uuid)
                .containsEntry(RunRecord.CORR_CLAIM_RESPONSE_ID, "claimresponse-claim-" + uuid);
    }
}
