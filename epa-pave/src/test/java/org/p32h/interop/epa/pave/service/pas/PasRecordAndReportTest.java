package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.status;
import static org.p32h.interop.epa.pave.service.pas.PasJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.PasJson.decided;
import static org.p32h.interop.epa.pave.service.pas.PasJson.items;
import static org.p32h.interop.epa.pave.service.pas.PasJson.response;
import static org.p32h.interop.epa.pave.service.pas.PasJson.sent;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.pas.NotificationView;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * When the service answered with nothing a business tester can act on, the next step still gives
 * them something to do: record the run by its id as what came back, and report it. The second line
 * keeps the way to the detail for the QA analyst. On PAS, on subscriptions and on a notification,
 * as on CRD for "no determination".
 */
class PasRecordAndReportTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final OnyxProperties ONYX = OnyxProperties.of(Map.of("subscription.callback-url", "https://pave.example.invalid/api/pas/notifications"));
    private static final String REPORT = "\" and report it to the people who look after the service.";
    private static final String NOTHING_TO_DO = "cannot say what should happen next";

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final PasResponseMapper mapper = new PasResponseMapper();

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private RunSupport support() {
        return new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.defaults(), ONYX, ledgerProvider(), CLOCK);
    }

    private PasService pas() {
        return new PasService(ONYX, FakeGateway.unconfigured(), new MockOnyxPasClient(scenarios, new MockExchanges(APP)), new FakeCodeList(),
                support(), ledgerProvider());
    }

    private RunEnvelope<PasDetail> submit(String memberId) {
        return pas().runSubmit(PasSubmitRequest.builder().member(MemberDetail.builder().memberId(memberId).firstName("Given")
                        .lastName("Placeholder-Surname").dateOfBirth("1970-01-31").build()).provider(ProviderDetail.builder().npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("95810"))).dateOfService("2026-03-20").build(), RunOrigin.manual(), null);
    }

    @Test
    void noDecisionTellsABusinessTesterToRecordTheRunByItsIdAndReportItAndKeepsTheDetailForTheAnalyst() {
        RunEnvelope<PasDetail> result = submit("P32H-PAS-NO-DECISION");

        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_DECISION");
        assertThat(result.verdict().headline()).isEqualTo("The service answered but returned no decision.");
        NextStepView next = result.nextStep();
        assertThat(next.headline()).isEqualTo("Next: record run " + result.runId() + " as \"no decision returned" + REPORT);
        assertThat(next.reason()).isEqualTo("The response carries no review action, so it does not say whether the request is approved, "
                + "pended or denied, and this tool does not guess. What came back is in the response detail below.");
        // The action is unchanged, so the ledger, History and a chain read the run as before.
        assertThat(next.action()).isEqualTo(NextAction.REVIEW);
        assertThat(ledger.only().nextAction()).isEqualTo("REVIEW");
    }

    @Test
    void noPasScenarioLeavesTheTesterWithOnlyTheWordsThatTheToolCannotSayWhatToDo() {
        for (String sentinel : List.of("P32H-PAS-APPROVED", "P32H-PAS-PENDED", "P32H-PAS-DENIED", "P32H-PAS-PARTIAL", "P32H-PAS-ATTACH-REQ",
                "P32H-PAS-ATTACH-OK", "P32H-PAS-BAD400", "P32H-PAS-DOWN", "P32H-PAS-NO-DECISION", "P32H-PAS-NOMEM", "P32H-PAS-TIMEOUT",
                "P32H-PAS-ERR500", "P32H-PAS-UNKNOWN-CODE", "P32H-PAS-NOPROV")) {
            RunEnvelope<PasDetail> result = submit(sentinel);
            assertThat(result.nextStep().headline()).as(sentinel).doesNotContain(NOTHING_TO_DO);
            assertThat(result.nextStep().reason()).as(sentinel).doesNotContain(NOTHING_TO_DO);
        }
    }

    @Test
    void anUnrecognisedDecisionCodeAndItemsDecidedDifferentlyAreRecordedAndReportedByTheRunId() {
        String run = "run-00000000-0000-4000-8000-000000000002";
        PasReading unknown = mapper.read(response(200, PasJson.bundle(claimResponse("\"outcome\":\"complete\"", items(decided(1, "ZZ"))))),
                sent("95810"));
        NextStepView code = PasVerdicts.nextStep(unknown, response(200, "{}"), EpaOperation.PAS_SUBMIT, run, run);
        assertThat(code.headline()).isEqualTo("Next: record run " + run + " as \"decision code not recognised" + REPORT);
        assertThat(code.reason()).endsWith("The code is in the response detail below.");

        // One item approved and one cancelled: two final decisions that differ, and nothing pended.
        PasReading mixed = mapper.read(response(200, PasJson.bundle(claimResponse(items(decided(1, "A1"), decided(2, "C"))))),
                sent("95810", "95811"));
        assertThat(mixed.outcome().name()).isEqualTo("MIXED");
        NextStepView differently = PasVerdicts.nextStep(mixed, response(200, "{}"), EpaOperation.PAS_SUBMIT, run, run);
        assertThat(differently.headline()).isEqualTo("Next: record run " + run + " as \"items decided differently" + REPORT);
        assertThat(differently.reason()).endsWith("The decision for each item is below.");

        // Where the run is not known yet the step says "this run", never an empty id.
        assertThat(PasVerdicts.nextStep(unknown, response(200, "{}"), EpaOperation.PAS_SUBMIT, null).headline())
                .startsWith("Next: record this run as ");
    }

    @Test
    void aNotificationOfATypeThisToolDoesNotKnowIsRecordedAndReported() {
        SubscriptionService subscriptions = new SubscriptionService(ONYX, FakeGateway.unconfigured(),
                new MockSubscriptionClient(scenarios, new MockExchanges(APP)), support());
        subscriptions.run(EpaOperation.SUBSCRIPTION_CREATE, SubscriptionRequest.builder().build(), RunOrigin.manual(), null);
        NotificationReceiver receiver = new NotificationReceiver(FakeGateway.unconfigured(), support(), ledgerProvider(), scenarios);

        NotificationView unknown = receiver.receive(NotificationReceiver.Inbound.of(bundle(status("subscription-placeholder-0001",
                "bulk-notification"))));
        assertThat(unknown.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_TYPE");
        assertThat(unknown.nextStep().headline()).isEqualTo("Next: record this notification as \"type not recognised" + REPORT);
        assertThat(unknown.nextStep().reason()).doesNotContain(NOTHING_TO_DO).endsWith("What arrived is shown below.");
    }
}
