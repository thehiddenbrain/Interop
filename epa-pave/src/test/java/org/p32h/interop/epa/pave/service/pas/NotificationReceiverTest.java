package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.event;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.idOnly;
import static org.p32h.interop.epa.pave.service.pas.NotificationJson.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.p32h.interop.epa.pave.client.pas.MockNotifications;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.pas.NotificationKind;
import org.p32h.interop.epa.pave.model.pas.NotificationList;
import org.p32h.interop.epa.pave.model.pas.NotificationView;
import org.p32h.interop.epa.pave.model.pas.PasDetail;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioExpect;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioStep;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The notification receiver, with the real PAS and subscription services beside it writing to the
 * same in-memory ledger, so that what a notification is matched to are runs those services made.
 */
class NotificationReceiverTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String SUBSCRIPTION = "subscription-placeholder-0001";
    private static final String SURNAME = "Placeholder-Surname";
    private static final OnyxProperties ONYX = OnyxProperties.of(Map.of("subscription.callback-url", "https://pave.example.invalid/api/pas/notifications"));

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final RunSupport support = support("mock");
    private final PasService pas = new PasService(ONYX, gateway, new MockOnyxPasClient(scenarios, new MockExchanges(APP)), new FakeCodeList(), support,
            ledgerProvider());
    private final SubscriptionService subscriptions = new SubscriptionService(ONYX, gateway, new MockSubscriptionClient(scenarios, new MockExchanges(APP)),
            support);
    private final NotificationReceiver receiver = new NotificationReceiver(gateway, support, ledgerProvider(), scenarios);

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private RunSupport support(String mode) {
        return new RunSupport(EpaProperties.of(Map.of("mode", mode)), TrackingProperties.defaults(), ONYX, ledgerProvider(), CLOCK);
    }

    /** Creates the mock's one subscription and returns the run that created it. */
    private RunEnvelope<SubscriptionDetail> createSubscription() {
        return subscriptions.run(EpaOperation.SUBSCRIPTION_CREATE, SubscriptionRequest.builder().build(), RunOrigin.manual(), null);
    }

    private RunEnvelope<PasDetail> submit(String memberId) {
        return pas.runSubmit(PasSubmitRequest.builder().member(MemberDetail.builder().memberId(memberId).firstName("Given").lastName(SURNAME)
                        .dateOfBirth("1970-01-31").build()).provider(ProviderDetail.builder().npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("95810"))).build(), RunOrigin.manual(), null);
    }

    private NotificationView receive(String body) {
        return receiver.receive(NotificationReceiver.Inbound.of(body));
    }

    private static String claimResponseIdOf(RunEnvelope<PasDetail> submitted) {
        return submitted.ledger().record().correlation().get(RunRecord.CORR_CLAIM_RESPONSE_ID);
    }

    // ---- correlation ----

    @Test
    void aNotificationIsMatchedToTheRunThatCreatedItsSubscriptionAndToTheSubmissionItIsAbout() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX);
        RunEnvelope<SubscriptionDetail> created = createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-NOTIFY");
        assertThat(submitted.verdict().outcomeCode()).isEqualTo("PENDED");

        NotificationView view = receive(idOnly(SUBSCRIPTION, claimResponseIdOf(submitted)));

        assertThat(view.verdict().outcomeCode()).isEqualTo("NOTIFICATION_RECEIVED");
        assertThat(view.verdict().headline()).isEqualTo("An event notification arrived for a subscription this tool created.");
        assertThat(view.verdict().tone()).isEqualTo(Tone.NEUTRAL);
        assertThat(view.kind()).isEqualTo(NotificationKind.NOTIFICATION_BUNDLE);
        assertThat(view.type().code()).isEqualTo("event-notification");
        assertThat(view.subscriptionId()).isEqualTo(SUBSCRIPTION);
        assertThat(view.payloadContent()).isEqualTo("id-only");
        // The two links, each to a run this tool made.
        assertThat(view.subscriptionRunId()).isEqualTo(created.runId());
        assertThat(view.correlatedRunId()).isEqualTo(submitted.runId());
        // The notification carries the ClaimResponse id only. The claim id comes from the submission it was matched to.
        assertThat(view.claimResponseId()).isEqualTo(claimResponseIdOf(submitted));
        assertThat(view.claimId()).isEqualTo(submitted.detail().claimId());
        assertThat(view.correlationNotes()).containsExactly("The subscription it names was created by the run " + created.runId() + ".",
                "It is about the submission " + submitted.runId() + ", matched by the ClaimResponse id it carries.");
        assertThat(view.verdict().supporting()).contains("The subscription status it reports: Active.",
                "It carries ids only: it says that something changed and not what. An inquiry shows the decision.");
        assertThat(view.findings()).isEmpty();
        assertThat(view.sample()).isFalse();
        assertThat(view.scenarioId()).isNull();
        // The complete payload, exactly as it arrived.
        assertThat(view.payload()).isEqualTo(idOnly(SUBSCRIPTION, claimResponseIdOf(submitted)));
        assertThat(view.truncated()).isFalse();
        assertThat(view.bytesReceived()).isEqualTo(view.payload().length()).isEqualTo(view.bytesKept());
        assertThat(view.receivedAt()).isEqualTo("2026-03-10T15:00:00Z");

        // Its own ledger line: ids and codes, and the links.
        RunRecord record = ledger.last();
        assertThat(view.id()).isEqualTo(record.runId());
        assertThat(view.ledger().recorded()).isTrue();
        assertThat(record.protocol()).isEqualTo(Protocol.SUBSCRIPTION);
        assertThat(record.operation()).isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(record.outcome()).isEqualTo("NOTIFICATION_RECEIVED");
        assertThat(record.result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_INBOUND);
        assertThat(record.coverageCodes()).containsExactly("notification-kind=NOTIFICATION_BUNDLE", "notification-type=event-notification",
                "subscription-status=active", "payload-content=id-only");
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, SUBSCRIPTION),
                Map.entry(RunRecord.CORR_SUBSCRIPTION_RUN_ID, created.runId()), Map.entry(RunRecord.CORR_RUN_ID, submitted.runId()),
                Map.entry(RunRecord.CORR_CLAIM_ID, submitted.detail().claimId()),
                Map.entry(RunRecord.CORR_CLAIM_RESPONSE_ID, claimResponseIdOf(submitted)));
        assertThat(record.nextAction()).isEqualTo("CHECK_STATUS");
        assertThat(record.environment()).isEqualTo("Onyx Sandbox");
        assertThat(record.httpStatus()).isNull();
        assertThat(record.scenarioId()).isNull();
        assertThat(record.memberId()).isNull();
        assertThat(record.findings()).isEmpty();
        assertThat(record.timestamp()).isEqualTo("2026-03-10T11:00:00-04:00");

        // And what to do next starts from it: an inquiry about that claim, linked to that submission.
        assertThat(view.nextStep().action()).isEqualTo(NextAction.CHECK_STATUS);
        assertThat(view.nextStep().carry()).containsExactly(Map.entry(NextStepView.CARRY_CLAIM_ID, List.of(submitted.detail().claimId())),
                Map.entry(NextStepView.CARRY_RUN_ID, List.of(submitted.runId())), Map.entry(NextStepView.CARRY_SUBSCRIPTION_ID, List.of(SUBSCRIPTION)));
        RunEnvelope<PasDetail> inquired = pas.runInquire(PasInquireRequest.builder().member(MemberDetail.builder().memberId("P32H-PAS-NOTIFY").build())
                .provider(ProviderDetail.builder().npi("1234567893").build()).carry(view.nextStep().carry()).build(), RunOrigin.manual(), null);
        assertThat(inquired.detail().claimId()).isEqualTo(submitted.detail().claimId());
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, submitted.runId());
    }

    @Test
    void aNotificationThatCarriesTheDecisionIsReadLikeAResponseAndItsCodesGoOnItsLedgerLine() {
        RunEnvelope<SubscriptionDetail> created = createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-NOTIFY");
        NotificationView view = receive(bundle(status(SUBSCRIPTION, "event-notification", event(1, "ClaimResponse/" + claimResponseIdOf(submitted))),
                claimResponse(claimResponseIdOf(submitted), submitted.detail().claimId(), "A1", "\"preAuthRef\":\"PA-PLACEHOLDER-0006\"",
                        "\"disposition\":\"Certified in total.\"")));

        assertThat(view.verdict().outcomeCode()).isEqualTo("NOTIFICATION_RECEIVED");
        assertThat(view.payloadContent()).isEqualTo("full-resource");
        assertThat(view.claimResponses()).singleElement().satisfies(response -> {
            assertThat(response.decision()).isEqualTo(PasOutcome.APPROVED);
            assertThat(response.preAuthRef().value()).isEqualTo("PA-PLACEHOLDER-0006");
        });
        assertThat(view.verdict().supporting()).contains("It carries a ClaimResponse. What its codes establish: the authorization is approved "
                + "(review action A1).");
        assertThat(view.correlationNotes()).containsExactly("The subscription it names was created by the run " + created.runId() + ".",
                "It is about the submission " + submitted.runId() + ", matched by the claim id it carries.");
        // The decision has arrived, so there is nothing left to ask.
        assertThat(view.nextStep().action()).isEqualTo(NextAction.FLOW_ENDS);

        RunRecord record = ledger.last();
        assertThat(record.coverageCodes()).containsExactly("notification-kind=NOTIFICATION_BUNDLE", "notification-type=event-notification",
                "subscription-status=active", "payload-content=full-resource", "claim-outcome=complete", "review-action=A1");
        assertThat(record.correlation()).containsEntry(RunRecord.CORR_RUN_ID, submitted.runId())
                .containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0006");
        assertThat(record.nextAction()).isEqualTo("FLOW_ENDS");

        // A ClaimResponse that is still pended is not the end: the status is still worth asking for.
        NotificationView pended = receive(bundle(status(SUBSCRIPTION, "event-notification"),
                claimResponse(claimResponseIdOf(submitted), submitted.detail().claimId(), "A4")));
        assertThat(pended.nextStep().action()).isEqualTo(NextAction.CHECK_STATUS);
        assertThat(pended.verdict().supporting()).anySatisfy(line -> assertThat(line).contains("the authorization is pended"));
    }

    @Test
    void aNotificationIsAboutTheSubmissionThatWasSentAndNeverAboutALaterAttemptTheGuardrailRefused() {
        createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-NOTIFY");
        String claimId = submitted.detail().claimId();
        // The same claim is submitted again and the guardrail refuses it. Nothing is sent, and the line names the claim.
        gateway.failing(new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment.",
                null, "POST", null));
        RunEnvelope<PasDetail> refused = pas.runSubmit(PasSubmitRequest.builder().claimId(claimId).member(MemberDetail.builder()
                        .memberId("P32H-PAS-NOTIFY").firstName("Given").lastName(SURNAME).dateOfBirth("1970-01-31").build())
                .provider(ProviderDetail.builder().npi("1234567893").build()).procedureCodes(List.of(CodeInput.of("95810"))).build(),
                RunOrigin.manual(), null);
        assertThat(RunSupport.isBlocked(refused)).isTrue();
        assertThat(ledger.last().correlation()).containsExactly(Map.entry(RunRecord.CORR_CLAIM_ID, claimId));
        gateway.failing(null);

        NotificationView view = receive(bundle(status(SUBSCRIPTION, "event-notification"),
                claimResponse(claimResponseIdOf(submitted), claimId, "A1")));

        // The newest line for that claim is the refused one. The notification is about the request the payer holds.
        assertThat(view.correlatedRunId()).isEqualTo(submitted.runId());
        assertThat(view.correlationNotes()).contains("It is about the submission " + submitted.runId() + ", matched by the claim id it carries.");
        assertThat(view.correlationNotes()).noneMatch(note -> note.contains(refused.runId()));
    }

    @Test
    void aNotificationForASubscriptionNobodyHereCreatedIsRecordedAsUnmatchedAndShownAndNeverDropped() {
        createSubscription();
        NotificationView view = receive(idOnly("subscription-of-another-client", "claimresponse-nobody-submitted"));

        assertThat(view.verdict().outcomeCode()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(view.verdict().headline()).isEqualTo("A notification arrived that matches no subscription this tool created.");
        assertThat(view.verdict().tone()).isEqualTo(Tone.DATA);
        assertThat(view.verdict().supporting()).contains("It is recorded and shown all the same. It is not dropped.", "Its type: Event notification.");
        assertThat(view.subscriptionRunId()).isNull();
        assertThat(view.correlatedRunId()).isNull();
        assertThat(view.correlationNotes()).containsExactly("No run in the ledger created the subscription it names.",
                "No submission in the ledger carries the ClaimResponse id this notification carries.");
        Finding finding = view.findings().stream().filter(f -> f.kind() == FindingKind.UNMATCHED_NOTIFICATION).findFirst().orElseThrow();
        assertThat(finding.title()).isEqualTo("This notification matches no subscription this tool created");
        assertThat(finding.detail()).isEqualTo("It names the subscription subscription-of-another-client, and no run in the ledger created a "
                + "subscription with that id. Another client may have created it, or it was created before this ledger began, or by another "
                + "installation of this tool. It is recorded and shown, never dropped.");
        assertThat(view.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(view.nextStep().headline()).isEqualTo("Next: find out who created the subscription it names. The list of subscriptions "
                + "shows what the service holds.");
        assertThat(view.nextStep().reason()).isEqualTo("No run in the ledger created a subscription with the id this notification names.");

        // It has its ledger line, with the kind it was kept beside the fact that it is unmatched.
        RunRecord record = ledger.last();
        assertThat(record.outcome()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(record.findings()).containsExactly("UNMATCHED_NOTIFICATION");
        assertThat(record.coverageCodes()).contains("notification-type=event-notification");
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, "subscription-of-another-client"),
                Map.entry(RunRecord.CORR_CLAIM_RESPONSE_ID, "claimresponse-nobody-submitted"));
        // And it is in the list, with its whole payload.
        assertThat(receiver.list(null).notifications()).singleElement().satisfies(held -> {
            assertThat(held.id()).isEqualTo(view.id());
            assertThat(held.payload()).contains("subscription-of-another-client");
        });

        // One that names no subscription at all is unmatched too, and says why.
        NotificationView nameless = receive(bundle(status(null, "heartbeat")));
        assertThat(nameless.verdict().outcomeCode()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(nameless.correlationNotes()).containsExactly("It names no subscription, so it cannot be matched to one this tool created.");
        assertThat(nameless.findings().get(0).detail()).isEqualTo("It names no subscription, so there is none to look up. It is recorded and "
                + "shown, never dropped.");
        // Nobody is sent looking for the creator of a subscription that was never named.
        assertThat(nameless.nextStep().headline()).isEqualTo("Next: read the notification below and find out what sent it.");
        assertThat(nameless.nextStep().reason()).isEqualTo("The notification names no subscription, so it cannot be matched to one.");
    }

    @Test
    void aLedgerThatCannotBeAskedLeavesItUnknownWhoseSubscriptionItIsAndThatIsNeverReportedAsUnmatched() {
        // The subscription and the submission are both in the ledger. Only asking about them fails.
        createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-NOTIFY");
        // The message names a member on purpose: a caller that copied it into a result or a line would be caught below.
        ledger.throwingOnRead(new IllegalStateException("the ledger directory is gone for " + SURNAME));

        NotificationView view = receiver.receive(NotificationReceiver.Inbound.of(idOnly(SUBSCRIPTION, claimResponseIdOf(submitted))));

        // Not "unmatched": that would say nobody here created the subscription, and this tool did.
        assertThat(view.verdict().outcomeCode()).isEqualTo("UNCHECKED_NOTIFICATION");
        assertThat(view.verdict().tone()).isEqualTo(Tone.FAULT);
        assertThat(view.verdict().headline()).isEqualTo("A notification arrived, and the ledger could not be asked which subscription it belongs to.");
        assertThat(view.verdict().supporting()).containsExactly("It is recorded and shown all the same. It is not dropped.",
                "This says nothing about who created the subscription. It is a finding about this installation of the tool.",
                "Its type: Event notification.",
                "The subscription status it reports: Active.",
                "It carries ids only: it says that something changed and not what. An inquiry shows the decision.",
                "The ledger could not be read, so the subscription it names could not be looked up.",
                "The ledger could not be read, so the submission it is about could not be looked up.");
        // A finding of its own kind says what was not looked up, so a notification with no link is never read as one that was
        // checked and matched nothing.
        assertThat(view.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(FindingKind.UNCHECKED_NOTIFICATION);
            assertThat(finding.title()).isEqualTo("This notification could not be checked against the ledger");
            assertThat(finding.detail()).isEqualTo("The ledger could not be read, so the subscription it names could not be looked up. "
                    + "The ledger could not be read, so the submission it is about could not be looked up. A run that could not be looked "
                    + "up may exist all the same. The notification is recorded and shown, never dropped.");
            assertThat(finding.subject()).isEqualTo(SUBSCRIPTION);
        });
        assertThat(view.subscriptionRunId()).isNull();
        assertThat(view.correlatedRunId()).isNull();
        assertThat(view.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(view.nextStep().headline()).isEqualTo("Next: find out why the ledger could not be read. Until it can, this tool cannot say "
                + "whether it created the subscription this notification names.");
        assertThat(view.nextStep().reason()).isEqualTo("The ledger could not be asked which run created the subscription.");
        // The notification still has its line, with what it carries and no link that was not established.
        RunRecord record = ledger.last();
        assertThat(record.operation()).isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(record.outcome()).isEqualTo("UNCHECKED_NOTIFICATION");
        assertThat(record.findings()).containsExactly("UNCHECKED_NOTIFICATION");
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, SUBSCRIPTION),
                Map.entry(RunRecord.CORR_CLAIM_RESPONSE_ID, claimResponseIdOf(submitted)));
        assertThat(receiver.list(null).notifications()).singleElement().satisfies(held -> assertThat(held.id()).isEqualTo(view.id()));
        // What the ledger said when it failed is in neither the result nor its line.
        assertThat(JsonSafe.mapper().writeValueAsString(List.of(view, record))).doesNotContain("ledger directory").doesNotContain("is gone for");

        // A notification that names no subscription is unmatched whatever the ledger can do: there was nothing to ask,
        // so nothing went unchecked either.
        NotificationView nameless = receiver.receive(NotificationReceiver.Inbound.of(bundle(status(null, "heartbeat"))));
        assertThat(nameless.verdict().outcomeCode()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(nameless.findings()).extracting(Finding::kind).containsExactly(FindingKind.UNMATCHED_NOTIFICATION);

        // On mock, a sample that has to ask which subscription was created last says that it could not.
        NotificationView sample = receiver.deliverSample(new NotificationSampleRequest("pas-20-heartbeat", null, null));
        assertThat(sample.verdict().outcomeCode()).isEqualTo("UNCHECKED_NOTIFICATION");
        assertThat(sample.findings()).extracting(Finding::kind).containsExactly(FindingKind.UNCHECKED_NOTIFICATION);
        assertThat(sample.payloadNotes()).containsExactly("The ledger could not be asked which subscription this tool created last, so this "
                + "sample names one nobody created.");
    }

    @Test
    void aHandshakeAndAHeartbeatAreEachTheirOwnOutcomeAndAreNotAboutARequest() {
        RunEnvelope<SubscriptionDetail> created = createSubscription();
        NotificationView handshake = receive(bundle(status(SUBSCRIPTION, "handshake")));
        NotificationView heartbeat = receive(bundle(status(SUBSCRIPTION, "heartbeat")));

        assertThat(handshake.verdict().outcomeCode()).isEqualTo("HANDSHAKE_RECEIVED");
        assertThat(handshake.verdict().headline()).isEqualTo("A handshake arrived for a subscription this tool created.");
        assertThat(heartbeat.verdict().outcomeCode()).isEqualTo("HEARTBEAT_RECEIVED");
        assertThat(heartbeat.verdict().headline()).isEqualTo("A heartbeat arrived for a subscription this tool created.");
        for (NotificationView view : List.of(handshake, heartbeat)) {
            assertThat(view.subscriptionRunId()).isEqualTo(created.runId());
            assertThat(view.correlatedRunId()).isNull();
            assertThat(view.payloadContent()).isEqualTo("empty");
            assertThat(view.events()).isEmpty();
            // Nothing is said about a submission, because neither is about one.
            assertThat(view.correlationNotes()).containsExactly("The subscription it names was created by the run " + created.runId() + ".");
            // There is nothing to do, and that is not the end of a case's journey: no request was decided by this.
            assertThat(view.nextStep().action()).isEqualTo(NextAction.NOTHING_FURTHER);
            assertThat(view.findings()).isEmpty();
        }
        assertThat(handshake.nextStep().headline()).isEqualTo("Nothing to do. The subscription is answering.");
        assertThat(heartbeat.nextStep().headline()).isEqualTo("Nothing to do. The subscription is still there.");
        assertThat(ledger.records()).extracting(RunRecord::outcome).containsExactly("CREATED", "HANDSHAKE_RECEIVED", "HEARTBEAT_RECEIVED");
        assertThat(ledger.records()).extracting(RunRecord::nextAction).containsExactly("START_PAS", "NOTHING_FURTHER", "NOTHING_FURTHER");
        assertThat(ledger.last().coverageCodes()).containsExactly("notification-kind=NOTIFICATION_BUNDLE", "notification-type=heartbeat",
                "subscription-status=active", "payload-content=empty");
        assertThat(ledger.last().correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, SUBSCRIPTION),
                Map.entry(RunRecord.CORR_SUBSCRIPTION_RUN_ID, created.runId()));
    }

    @Test
    void aTypeThisToolDoesNotKnowOrOneThatIsMissingIsNeverTakenForAnEvent() {
        createSubscription();
        NotificationView unknown = receive(bundle(status(SUBSCRIPTION, "bulk-notification")));
        assertThat(unknown.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_TYPE");
        assertThat(unknown.verdict().supporting()).contains("Unrecognised code: bulk-notification.");
        assertThat(unknown.findings()).extracting(Finding::kind).containsExactly(FindingKind.CODE_OUTSIDE_VALUE_SET);
        assertThat(ledger.last().coverageCodes()).contains("notification-type=bulk-notification");

        NotificationView missing = receive(bundle(status(SUBSCRIPTION, null)));
        assertThat(missing.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_TYPE");
        assertThat(missing.verdict().supporting()).contains("It states no notification type.");
        assertThat(ledger.last().coverageCodes()).noneMatch(code -> code.startsWith("notification-type="));
        // A status report is told apart from an event.
        assertThat(receive(bundle(status(SUBSCRIPTION, "query-status"))).verdict().outcomeCode()).isEqualTo("STATUS_RECEIVED");
    }

    @Test
    void anAuthorizationNumberIsMatchedByTheLedgerAndNeverByComparingStoredTextHere() {
        createSubscription();
        RunEnvelope<PasDetail> approved = submit("P32H-PAS-APPROVED");
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0001");
        // The notification carries no claim id and a ClaimResponse id nobody here has seen. Its
        // authorization number is written with a space the stored one does not have: a plain
        // comparison of the two texts would not match, and the ledger's own does.
        NotificationView view = receive(bundle(status(SUBSCRIPTION, "event-notification"),
                claimResponse("claimresponse-reissued", null, "A1", "\"preAuthRef\":\"PA-PLACEHOLDER- 0001\"")));

        assertThat(view.correlatedRunId()).isEqualTo(approved.runId());
        assertThat(view.claimId()).isEqualTo(approved.detail().claimId());
        assertThat(view.correlationNotes()).contains("It is about the submission " + approved.runId() + ", matched by the authorization number it carries.",
                "The ClaimResponse id it carries is not the one that submission recorded.");
        assertThat(view.correlationNotes()).noneMatch(note -> note.startsWith("The authorization number it carries is not"));
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, approved.runId())
                .containsEntry(RunRecord.CORR_CLAIM_ID, approved.detail().claimId());
    }

    @Test
    void anAuthorizationNumberTheLedgerHoldsAsATokenIsStillMatchedByTheNumberTheNotificationCarries() {
        // As the real ledger writes it where member IDs are not recorded: a token, and never the number.
        ledger.tokenisingAuthorizationRefs();
        createSubscription();
        RunEnvelope<PasDetail> approved = submit("P32H-PAS-APPROVED");
        String stored = ledger.last().correlation().get(RunRecord.CORR_AUTHORIZATION_REF);
        assertThat(stored).matches("[0-9a-f]{64}");

        NotificationView view = receive(bundle(status(SUBSCRIPTION, "event-notification"),
                claimResponse("claimresponse-reissued", null, "A1", "\"preAuthRef\":\"PA-PLACEHOLDER-0001\"")));

        // The stored text and the number have nothing in common. Only the ledger's own comparison finds the run.
        assertThat(view.correlatedRunId()).isEqualTo(approved.runId());
        assertThat(view.correlationNotes()).contains("It is about the submission " + approved.runId() + ", matched by the authorization number it carries.");
        // And the number is not said to differ from the one the submission recorded, which a comparison of the two texts would say.
        assertThat(view.correlationNotes()).noneMatch(note -> note.startsWith("The authorization number it carries is not"));
        // The line of the notification holds the token too.
        assertThat(ledger.last().operation()).isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_AUTHORIZATION_REF, stored)
                .containsEntry(RunRecord.CORR_RUN_ID, approved.runId());

        // Another number is another request, and is tied to nothing.
        NotificationView other = receive(bundle(status(SUBSCRIPTION, "event-notification"),
                claimResponse("claimresponse-other", null, "A1", "\"preAuthRef\":\"PA-PLACEHOLDER-0002\"")));
        assertThat(other.correlatedRunId()).isNull();
        assertThat(other.correlationNotes()).contains("No submission in the ledger carries the ClaimResponse id or the authorization number this "
                + "notification carries.");
    }

    @Test
    void identifiersThatPointAtDifferentRunsAreNotSettledInSilence() {
        createSubscription();
        RunEnvelope<PasDetail> first = submit("P32H-PAS-PENDED");
        RunEnvelope<PasDetail> second = submit("P32H-PAS-PENDED");
        NotificationView view = receive(bundle(status(SUBSCRIPTION, "event-notification"),
                claimResponse(claimResponseIdOf(second), first.detail().claimId(), "A1")));
        // The claim id is the identifier this tool minted, so it is the one that is believed.
        assertThat(view.correlatedRunId()).isEqualTo(first.runId());
        assertThat(view.correlationNotes()).contains("It is about the submission " + first.runId() + ", matched by the claim id it carries.",
                "By the ClaimResponse id it carries it would be about another run, " + second.runId() + ". The match by the claim id was kept.",
                "The ClaimResponse id it carries is not the one that submission recorded.");
    }

    @Test
    void aClaimResponseOnlyAnInquirySawStillLeadsBackToTheSubmission() {
        createSubscription();
        String submissionRun = "run-00000000-0000-4000-8000-0000000000a1";
        ledger.record(RunRecord.builder().runId(submissionRun).timestamp("2026-03-09T11:00:00-04:00").protocol(Protocol.PAS)
                .operation(EpaOperation.PAS_SUBMIT).outcome("PENDED").result(TestResult.UNVERIFIED)
                .correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim-test-0009")).build());
        ledger.record(RunRecord.builder().runId("run-00000000-0000-4000-8000-0000000000a2").timestamp("2026-03-09T12:00:00-04:00")
                .protocol(Protocol.PAS).operation(EpaOperation.PAS_INQUIRE).outcome("PENDED").result(TestResult.UNVERIFIED)
                .correlation(Map.of(RunRecord.CORR_CLAIM_ID, "claim-test-0009", RunRecord.CORR_CLAIM_RESPONSE_ID, "claimresponse-late",
                        RunRecord.CORR_RUN_ID, submissionRun)).build());

        NotificationView view = receive(idOnly(SUBSCRIPTION, "claimresponse-late"));
        // The link is to the submission, never to the inquiry that happened to see the id.
        assertThat(view.correlatedRunId()).isEqualTo(submissionRun);
        assertThat(view.claimId()).isEqualTo("claim-test-0009");
        assertThat(view.nextStep().action()).isEqualTo(NextAction.CHECK_STATUS);
        assertThat(view.nextStep().first(NextStepView.CARRY_CLAIM_ID)).isEqualTo("claim-test-0009");
    }

    @Test
    void anEventThatNamesNothingCannotBeTiedToASubmissionAndTheResultSaysSo() {
        createSubscription();
        NotificationView view = receive(bundle(status(SUBSCRIPTION, "event-notification", "{\"name\":\"notification-event\",\"part\":[{\"name\":"
                + "\"event-number\",\"valueString\":\"1\"}]}")));
        assertThat(view.verdict().outcomeCode()).isEqualTo("NOTIFICATION_RECEIVED");
        assertThat(view.correlatedRunId()).isNull();
        assertThat(view.correlationNotes()).contains("It reports an event and names no claim, ClaimResponse or authorization number, so it "
                + "cannot be tied to a submission.");
        // With no claim to ask about, the next step is not an inquiry.
        assertThat(view.nextStep().action()).isEqualTo(NextAction.REVIEW);
    }

    // ---- accept anything, drop nothing ----

    @Test
    void aBodyThatIsNotJsonIsRecordedAndKeptExactlyAsItArrived() {
        NotificationView view = receive("<notification>not json for " + SURNAME + "</notification>");
        assertThat(view.verdict().outcomeCode()).isEqualTo("UNREADABLE_NOTIFICATION");
        assertThat(view.verdict().headline()).isEqualTo("Something arrived at the receiver that is not a subscription notification.");
        assertThat(view.verdict().tone()).isEqualTo(Tone.FAULT);
        assertThat(view.verdict().supporting()).anySatisfy(line -> assertThat(line).startsWith("The body could not be read as JSON."));
        assertThat(view.kind()).isEqualTo(NotificationKind.NOT_JSON);
        assertThat(view.payload()).isEqualTo("<notification>not json for " + SURNAME + "</notification>");
        assertThat(view.payloadContent()).isNull();
        assertThat(view.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(view.findings()).isEmpty();
        RunRecord record = ledger.only();
        assertThat(record.outcome()).isEqualTo("UNREADABLE_NOTIFICATION");
        assertThat(record.coverageCodes()).containsExactly("notification-kind=NOT_JSON");
        assertThat(record.correlation()).isEmpty();
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_INBOUND);

        NotificationView empty = receiver.receive(NotificationReceiver.Inbound.read(new ByteArrayInputStream(new byte[0])));
        assertThat(empty.kind()).isEqualTo(NotificationKind.NO_BODY);
        assertThat(empty.payload()).isNull();
        assertThat(empty.bytesReceived()).isZero();
        assertThat(empty.verdict().supporting()).contains("The request had no body.");
        assertThat(receiver.receive(null).kind()).isEqualTo(NotificationKind.NO_BODY);

        NotificationView other = receive("{\"event\":\"placeholder\",\"detail\":{\"depth\":2}}");
        assertThat(other.kind()).isEqualTo(NotificationKind.OTHER_JSON);
        assertThat(other.additionalFields()).extracting(f -> f.path()).containsExactly("event", "detail.depth");

        // Bytes that are not text are kept as far as they can be shown, with a note.
        NotificationView binary = receiver.receive(NotificationReceiver.Inbound.read(new ByteArrayInputStream(new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'})));
        assertThat(binary.payloadNotes()).anySatisfy(note -> assertThat(note).startsWith("The body is not valid UTF-8."));
        assertThat(binary.kind()).isEqualTo(NotificationKind.NOT_JSON);

        assertThat(ledger.records()).hasSize(5);
        assertThat(receiver.list(null).notifications()).hasSize(5);
    }

    @Test
    void aBodyLongerThanTheReceiverHoldsIsCutAndTheRecordSaysSo() {
        String start = "{\"resourceType\":\"Bundle\",\"type\":\"history\",\"padding\":\"";
        byte[] body = (start + "a".repeat(NotificationReceiver.MAX_KEPT_BYTES + 500) + "\"}").getBytes(StandardCharsets.UTF_8);
        NotificationReceiver.Inbound inbound = NotificationReceiver.Inbound.read(new ByteArrayInputStream(body));
        assertThat(inbound.kept()).hasSize(NotificationReceiver.MAX_KEPT_BYTES);
        assertThat(inbound.bytesReceived()).isEqualTo(body.length);
        assertThat(inbound.complete()).isTrue();
        assertThat(inbound.cut()).isTrue();
        assertThat(inbound.toString()).doesNotContain("aaaa").contains("bytesReceived=" + body.length);

        NotificationView view = receiver.receive(inbound);
        assertThat(view.truncated()).isTrue();
        assertThat(view.bytesReceived()).isEqualTo(body.length);
        assertThat(view.bytesKept()).isEqualTo(NotificationReceiver.MAX_KEPT_BYTES);
        assertThat(view.payload()).hasSize(NotificationReceiver.MAX_KEPT_BYTES).startsWith(start);
        String cut = "The body was " + body.length + " bytes long. Only its first " + NotificationReceiver.MAX_KEPT_BYTES
                + " bytes are held and shown, so what is read here is not all that was sent.";
        assertThat(view.payloadNotes()).containsExactly(cut);
        assertThat(view.verdict().supporting()).contains(cut);
        // What was kept ends in the middle of the document, so it cannot be read as a notification.
        assertThat(view.verdict().outcomeCode()).isEqualTo("UNREADABLE_NOTIFICATION");
        assertThat(ledger.only().coverageCodes()).containsExactly("notification-kind=NOT_JSON", "body=truncated");

        // A body exactly as long as the receiver holds is not cut.
        NotificationReceiver.Inbound exact = NotificationReceiver.Inbound.read(new ByteArrayInputStream(new byte[NotificationReceiver.MAX_KEPT_BYTES]));
        assertThat(exact.cut()).isFalse();
        assertThat(NotificationReceiver.Inbound.of("a".repeat(NotificationReceiver.MAX_KEPT_BYTES + 1)).cut()).isTrue();
    }

    @Test
    void aCutThatFallsInsideOneCharacterLeavesThatCharacterOutAndDoesNotCallTheBodyInvalid() {
        // The accented letter is two bytes, and the first of them is the last byte the receiver holds.
        String shown = "{\"padding\":\"" + "a".repeat(NotificationReceiver.MAX_KEPT_BYTES - 13);
        byte[] body = (shown + "é".repeat(40) + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(shown.length()).isEqualTo(NotificationReceiver.MAX_KEPT_BYTES - 1);

        NotificationView view = receiver.receive(NotificationReceiver.Inbound.read(new ByteArrayInputStream(body)));
        assertThat(view.truncated()).isTrue();
        assertThat(view.bytesKept()).isEqualTo(NotificationReceiver.MAX_KEPT_BYTES);
        // Nothing stands in for the half character, and the sender is not blamed for where this tool cut.
        assertThat(view.payload()).isEqualTo(shown);
        assertThat(view.payloadNotes()).containsExactly("What is held ends inside one character. That character is left out of the text below.",
                "The body was " + body.length + " bytes long. Only its first " + NotificationReceiver.MAX_KEPT_BYTES
                        + " bytes are held and shown, so what is read here is not all that was sent.");

        // The same bytes as a whole body do end in half a character, and that is the sender's.
        byte[] ending = java.util.Arrays.copyOf(body, NotificationReceiver.MAX_KEPT_BYTES);
        NotificationView whole = receiver.receive(NotificationReceiver.Inbound.read(new ByteArrayInputStream(ending)));
        assertThat(whole.truncated()).isFalse();
        assertThat(whole.payloadNotes()).hasSize(1).allSatisfy(note -> assertThat(note).startsWith("The body is not valid UTF-8."));
        assertThat(whole.payload()).startsWith(shown).hasSize(NotificationReceiver.MAX_KEPT_BYTES);
    }

    @Test
    void readingStopsAtALimitAndASenderThatGoesAwayPartWayIsStillRecorded() {
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return 'a';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                java.util.Arrays.fill(buffer, offset, offset + length, (byte) 'a');
                return length;
            }
        };
        NotificationReceiver.Inbound unbounded = NotificationReceiver.Inbound.read(endless);
        assertThat(unbounded.bytesReceived()).isEqualTo(NotificationReceiver.MAX_READ_BYTES);
        assertThat(unbounded.kept()).hasSize(NotificationReceiver.MAX_KEPT_BYTES);
        assertThat(unbounded.complete()).isFalse();
        NotificationView view = receiver.receive(unbounded);
        assertThat(view.payloadNotes()).containsExactly("The body was longer than the " + NotificationReceiver.MAX_READ_BYTES + " bytes that were "
                + "read, and reading stopped there. Only its first " + NotificationReceiver.MAX_KEPT_BYTES + " bytes are held and shown, so what "
                + "is read here is not all that was sent.");

        InputStream failing = new InputStream() {
            private int sent;

            @Override
            public int read() throws IOException {
                if (sent++ < 10) {
                    return '{';
                }
                throw new IOException("connection reset by " + SURNAME);
            }
        };
        NotificationReceiver.Inbound partial = NotificationReceiver.Inbound.read(failing);
        assertThat(partial.kept()).hasSize(10);
        assertThat(partial.complete()).isFalse();
        assertThat(partial.cut()).isTrue();
        assertThat(receiver.receive(partial).truncated()).isTrue();
        assertThat(NotificationReceiver.Inbound.read(null).bytesReceived()).isZero();
        assertThat(ledger.records()).hasSize(2);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aFailureOfThisToolWhileReadingStillRecordsAndKeepsWhatArrived(CapturedOutput output) {
        NotificationReceiver broken = new NotificationReceiver(gateway, support, ledgerProvider(), scenarios, text -> {
            throw new IllegalStateException("the reader exploded on " + text);
        });
        String body = idOnly(SUBSCRIPTION, "claimresponse-claim-test-1");
        NotificationView view = broken.receive(NotificationReceiver.Inbound.of(body));

        assertThat(view.verdict().outcomeCode()).isEqualTo("TOOL_ERROR");
        assertThat(view.verdict().headline()).isEqualTo("Something arrived at the receiver, and this tool then failed while reading it.");
        assertThat(view.payload()).isEqualTo(body);
        assertThat(view.kind()).isEqualTo(NotificationKind.OTHER_JSON);
        assertThat(view.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(view.ledger().recorded()).isTrue();
        RunRecord record = ledger.only();
        assertThat(record.outcome()).isEqualTo("TOOL_ERROR");
        assertThat(record.operation()).isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_INBOUND);
        assertThat(broken.list(null).notifications()).hasSize(1);
        // The log holds the exception type under a reference, and nothing of the payload the message quoted.
        assertThat(output.getAll()).contains("java.lang.IllegalStateException").doesNotContain("exploded").doesNotContain(SUBSCRIPTION)
                .doesNotContain("claimresponse-claim-test-1").doesNotContain("resourceType");
        assertThat(view.verdict().supporting().get(0)).contains("Report the reference");
    }

    @Test
    void aLedgerThatCannotWriteAndNoLedgerAtAllDoNotCostTheNotification() {
        createSubscription();
        ledger.refusing("This run was NOT recorded: the ledger file could not be written.");
        NotificationView unwritten = receive(bundle(status(SUBSCRIPTION, "heartbeat")));
        assertThat(unwritten.ledger().recorded()).isFalse();
        assertThat(unwritten.ledger().message()).contains("NOT recorded");
        assertThat(unwritten.verdict().outcomeCode()).isEqualTo("HEARTBEAT_RECEIVED");
        assertThat(receiver.list(null).notifications()).hasSize(1);

        // With no ledger at all nothing can be looked up, and the notification is still read, shown and held.
        NotificationReceiver noLedger = new NotificationReceiver(gateway, new RunSupport(EpaProperties.defaults(), TrackingProperties.defaults(), ONYX,
                new StaticListableBeanFactory().getBeanProvider(RunLedger.class), CLOCK), new StaticListableBeanFactory().getBeanProvider(RunLedger.class),
                scenarios);
        NotificationView view = noLedger.receive(NotificationReceiver.Inbound.of(idOnly(SUBSCRIPTION, "claimresponse-claim-test-1")));
        // Nothing was asked, so nothing is claimed about who created the subscription.
        assertThat(view.verdict().outcomeCode()).isEqualTo("UNCHECKED_NOTIFICATION");
        assertThat(view.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(FindingKind.UNCHECKED_NOTIFICATION);
            assertThat(finding.detail()).isEqualTo("No ledger is available, so the subscription it names could not be looked up. No ledger "
                    + "is available, so the submission it is about could not be looked up. A run that could not be looked up may exist all "
                    + "the same. The notification is recorded and shown, never dropped.");
            assertThat(finding.subject()).isEqualTo(SUBSCRIPTION);
        });
        assertThat(view.correlationNotes()).containsExactly("No ledger is available, so the subscription it names could not be looked up.",
                "No ledger is available, so the submission it is about could not be looked up.");
        assertThat(view.ledger().recorded()).isFalse();
        assertThat(noLedger.list(null).held()).isEqualTo(1);

        // One that names no subscription is unmatched, because there was none to look up. The submission it is about
        // was to be looked up and could not be, and that is a second fact with its own finding.
        NotificationView nameless = noLedger.receive(NotificationReceiver.Inbound.of(bundle(status(null, "event-notification"),
                claimResponse("claimresponse-claim-test-1", null, "A4"))));
        assertThat(nameless.verdict().outcomeCode()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(nameless.findings()).extracting(Finding::kind).containsExactly(FindingKind.UNMATCHED_NOTIFICATION,
                FindingKind.UNCHECKED_NOTIFICATION);
        assertThat(nameless.findings().get(1).detail()).isEqualTo("No ledger is available, so the submission it is about could not be looked "
                + "up. A run that could not be looked up may exist all the same. The notification is recorded and shown, never dropped.");
        assertThat(nameless.findings().get(1).subject()).isNull();

        // A bundle with no subscription status is not a notification. The ClaimResponse it carries was still to be looked up,
        // so the finding is raised for it too, and does not call it a notification.
        NotificationView notOne = noLedger.receive(NotificationReceiver.Inbound.of("{\"resourceType\":\"Bundle\",\"type\":\"collection\","
                + "\"entry\":[{\"resource\":" + claimResponse("claimresponse-claim-test-1", null, "A4") + "}]}"));
        assertThat(notOne.verdict().outcomeCode()).isEqualTo("UNREADABLE_NOTIFICATION");
        assertThat(notOne.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(FindingKind.UNCHECKED_NOTIFICATION);
            assertThat(finding.title()).isEqualTo("What arrived could not be checked against the ledger");
            assertThat(finding.detail()).isEqualTo("No ledger is available, so the submission it is about could not be looked up. A run that "
                    + "could not be looked up may exist all the same. What arrived is recorded and shown, never dropped.");
            assertThat(finding.subject()).isNull();
        });
    }

    @Test
    void aSubmissionThatCouldNotBeLookedUpIsAFindingEvenWhenTheSubscriptionWasFound() {
        RunEnvelope<SubscriptionDetail> created = createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-NOTIFY");
        // The ledger answers when it is asked who created a subscription and fails when it is asked about a submission.
        RunLedger failingOnSubmissions = spy(ledger);
        doThrow(new IllegalStateException("the ledger directory is gone for " + SURNAME)).when(failingOnSubmissions)
                .query(argThat(query -> query != null && query.operation() != EpaOperation.SUBSCRIPTION_CREATE));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", failingOnSubmissions);
        NotificationReceiver partly = new NotificationReceiver(gateway, support, beans.getBeanProvider(RunLedger.class), scenarios);

        NotificationView view = partly.receive(NotificationReceiver.Inbound.of(idOnly(SUBSCRIPTION, claimResponseIdOf(submitted))));

        // What arrived is an event for a subscription this tool created. That much was looked up and found.
        assertThat(view.verdict().outcomeCode()).isEqualTo("NOTIFICATION_RECEIVED");
        assertThat(view.subscriptionRunId()).isEqualTo(created.runId());
        assertThat(view.correlatedRunId()).isNull();
        assertThat(view.correlationNotes()).containsExactly("The subscription it names was created by the run " + created.runId() + ".",
                "The ledger could not be read, so the submission it is about could not be looked up.");
        // Without the finding, the ledger line would read like one for a notification about a submission nobody here made.
        assertThat(view.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(FindingKind.UNCHECKED_NOTIFICATION);
            assertThat(finding.title()).isEqualTo("This notification could not be checked against the ledger");
            assertThat(finding.detail()).isEqualTo("The ledger could not be read, so the submission it is about could not be looked up. A run "
                    + "that could not be looked up may exist all the same. The notification is recorded and shown, never dropped.");
            // The subscription was found, so the finding is not about it.
            assertThat(finding.subject()).isNull();
        });
        RunRecord record = ledger.last();
        assertThat(record.outcome()).isEqualTo("NOTIFICATION_RECEIVED");
        assertThat(record.findings()).containsExactly("UNCHECKED_NOTIFICATION");
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, SUBSCRIPTION),
                Map.entry(RunRecord.CORR_SUBSCRIPTION_RUN_ID, created.runId()),
                Map.entry(RunRecord.CORR_CLAIM_RESPONSE_ID, claimResponseIdOf(submitted)));
        assertThat(JsonSafe.mapper().writeValueAsString(List.of(view, record))).doesNotContain("ledger directory").doesNotContain("is gone for");

        // The same notification, with a ledger that answers, is matched and raises nothing.
        NotificationView checked = receive(idOnly(SUBSCRIPTION, claimResponseIdOf(submitted)));
        assertThat(checked.correlatedRunId()).isEqualTo(submitted.runId());
        assertThat(checked.findings()).isEmpty();
        assertThat(ledger.last().findings()).isEmpty();
    }

    // ---- the list the screen polls ----

    @Test
    void theListIsNewestFirstAndAPollThatPassesTheLatestStampGetsOnlyWhatArrivedSince() {
        createSubscription();
        NotificationView first = receive(bundle(status(SUBSCRIPTION, "handshake")));
        NotificationView second = receive(bundle(status(SUBSCRIPTION, "heartbeat")));
        NotificationView third = receive("not json");
        // The clock did not move between them, and no two share a stamp.
        assertThat(List.of(first.receivedAt(), second.receivedAt(), third.receivedAt()))
                .containsExactly("2026-03-10T15:00:00Z", "2026-03-10T15:00:00.001Z", "2026-03-10T15:00:00.002Z");

        NotificationList all = receiver.list(null);
        assertThat(all.notifications()).extracting(NotificationView::id).containsExactly(third.id(), second.id(), first.id());
        assertThat(all.held()).isEqualTo(3);
        assertThat(all.received()).isEqualTo(3);
        assertThat(all.evicted()).isZero();
        assertThat(all.capacity()).isEqualTo(NotificationReceiver.CAPACITY);
        assertThat(all.latest()).isEqualTo(third.receivedAt());
        assertThat(all.note()).contains("held in memory only").contains("none of them after a restart").contains("ledger line of every notification remains");

        assertThat(receiver.list(first.receivedAt()).notifications()).extracting(NotificationView::id).containsExactly(third.id(), second.id());
        assertThat(receiver.list(" " + second.receivedAt() + " ").notifications()).extracting(NotificationView::id).containsExactly(third.id());
        NotificationList none = receiver.list(all.latest());
        assertThat(none.notifications()).isEmpty();
        // Nothing new is still told how much is held, so an empty answer is not read as an empty receiver.
        assertThat(none.held()).isEqualTo(3);
        assertThat(none.latest()).isEqualTo(third.receivedAt());
        assertThat(receiver.list("2026-03-10T11:00:00.001-04:00").notifications()).extracting(NotificationView::id).containsExactly(third.id());

        assertThatThrownBy(() -> receiver.list("yesterday")).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("since"));
        NotificationList empty = new NotificationReceiver(gateway, support, ledgerProvider(), scenarios).list(null);
        assertThat(empty.notifications()).isEmpty();
        assertThat(empty.latest()).isNull();
        assertThat(empty.toString()).isEqualTo("NotificationList[held=0, received=0]");
    }

    @Test
    void theReceiverHoldsABoundedNumberAndSaysHowManyItLetGoWhileTheLedgerKeepsEveryLine() {
        int arrivals = NotificationReceiver.CAPACITY + 3;
        NotificationView firstEver = receive("arrival 0");
        for (int i = 1; i < arrivals; i++) {
            receive("arrival " + i);
        }
        NotificationList list = receiver.list(null);
        assertThat(list.held()).isEqualTo(NotificationReceiver.CAPACITY);
        assertThat(list.notifications()).hasSize(NotificationReceiver.CAPACITY);
        assertThat(list.received()).isEqualTo(arrivals);
        assertThat(list.evicted()).isEqualTo(3);
        assertThat(list.notifications().get(0).payload()).isEqualTo("arrival " + (arrivals - 1));
        assertThat(list.notifications()).extracting(NotificationView::id).doesNotContain(firstEver.id());
        // The payload of the oldest is gone from memory. Its ledger line is not.
        assertThat(ledger.records()).hasSize(arrivals);
        assertThat(ledger.find(firstEver.id())).isPresent();
    }

    // ---- member data stays in memory ----

    @Test
    void theLedgerLineOfANotificationHoldsNoMemberDataNoWordingAndNoJson() {
        createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-NOTIFY");
        String patient = "{\"resourceType\":\"Patient\",\"id\":\"patient-1\",\"name\":[{\"family\":\"" + SURNAME + "\",\"given\":[\"Given\"]}],"
                + "\"birthDate\":\"1970-01-31\",\"identifier\":[{\"value\":\"SUB-PLACEHOLDER-1\"}]}";
        NotificationView view = receive(bundle(status(SUBSCRIPTION, "event-notification"),
                claimResponse(claimResponseIdOf(submitted), submitted.detail().claimId(), "A3", "\"disposition\":\"Not certified for " + SURNAME + ".\""),
                patient));

        // The screen holds all of it, in memory: that is what the list is for.
        assertThat(view.payload()).contains(SURNAME).contains("1970-01-31");
        assertThat(view.otherResources()).singleElement().satisfies(other -> assertThat(other.resourceType().value()).isEqualTo("Patient"));
        assertThat(view.claimResponses().get(0).decision()).isEqualTo(PasOutcome.DENIED);

        String line = JsonSafe.mapper().writeValueAsString(ledger.last());
        assertThat(line).doesNotContain(SURNAME).doesNotContain("Given").doesNotContain("1970-01-31").doesNotContain("SUB-PLACEHOLDER-1")
                .doesNotContain("Not certified").doesNotContain("resourceType").doesNotContain("Parameters").doesNotContain("\"entry\"")
                .doesNotContain("valueReference");
        assertThat(ledger.last().coverageCodes()).contains("review-action=A3");
        // Neither the view nor the list prints a payload when it is logged by accident.
        assertThat(view.toString()).doesNotContain(SURNAME).doesNotContain("Bundle").contains("payload redacted");
        assertThat(receiver.list(null).toString()).doesNotContain(SURNAME);
    }

    @Test
    void aNotificationThatPutsTextWhereCodesAndIdsBelongIsShownOnScreenAndKeptOutOfTheLedgerLine() {
        NotificationView view = receive("{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"subscription\",\"valueReference\":{\"reference\":"
                + "\"Subscription/{\\\"patient\\\":\\\"" + SURNAME + "\\\"}\"}},{\"name\":\"type\",\"valueCode\":\"event for " + SURNAME + " born 1970-01-31\"}]}");
        assertThat(view.type().code()).isEqualTo("event for " + SURNAME + " born 1970-01-31");
        String line = JsonSafe.mapper().writeValueAsString(ledger.only());
        assertThat(line).doesNotContain(SURNAME).doesNotContain("1970-01-31");
        assertThat(ledger.only().coverageCodes()).anySatisfy(code -> assertThat(code).startsWith("notification-type=(not a code, "));
        assertThat(ledger.only().correlation().get(RunRecord.CORR_SUBSCRIPTION_ID)).startsWith("(not recorded, ");
    }

    @Test
    void theReceiverAndItsReaderHaveNoWayToWriteAPayloadToDisk() throws IOException {
        // The payload is held in a list and nowhere else. The one thing that reaches a file is the
        // ledger line, which is built from ids and codes. This fails if a file is ever opened here.
        // Assembled so this file does not itself contain what it looks for.
        List<String> marks = List.of("java.nio" + ".file", "java.io" + ".File", "FileOutput" + "Stream", "FileWr" + "iter", "Random" + "AccessFile",
                "Files" + ".write", "Files" + ".newOutput", "createTemp" + "File");
        for (String name : List.of("NotificationReceiver", "NotificationReader", "NotificationReading", "NotificationVerdicts")) {
            Path source = Path.of("src/main/java/org/p32h/interop/epa/pave/service/pas/" + name + ".java");
            assertThat(Files.isRegularFile(source)).as("the test runs from the project directory and finds " + name).isTrue();
            String text = Files.readString(source);
            for (String mark : marks) {
                assertThat(text).as(name + " uses " + mark).doesNotContain(mark);
            }
        }
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void theLogOfANotificationHoldsItsOutcomeAndItsSizeAndNoIdentifierAndNoBody(CapturedOutput output) {
        createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-NOTIFY");
        NotificationView view = receive(bundle(status(SUBSCRIPTION, "event-notification"),
                claimResponse(claimResponseIdOf(submitted), submitted.detail().claimId(), "A1", "\"preAuthRef\":\"PA-PLACEHOLDER-0006\"")));
        assertThat(output.getAll()).contains("Notification received: outcome NOTIFICATION_RECEIVED, " + view.bytesReceived() + " bytes read")
                .doesNotContain(SUBSCRIPTION).doesNotContain(submitted.detail().claimId()).doesNotContain("PA-PLACEHOLDER")
                .doesNotContain("resourceType").doesNotContain(SURNAME).doesNotContain("P32H-PAS-NOTIFY");
    }

    // ---- the sample, on mock only ----

    @Test
    void everyNotificationAScenarioFileHoldsProducesExactlyWhatTheFileClaimsWhenItIsDelivered() {
        createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-NOTIFY");
        int driven = 0;
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            ScenarioStep notify = MockNotifications.step(scenario);
            if (notify == null) {
                continue;
            }
            ScenarioExpect claim = notify.expect();
            assertThat(claim).as(scenario.id() + " makes a claim for its notification").isNotNull();
            NotificationView view = receiver.deliverSample(new NotificationSampleRequest(scenario.id(),
                    MockNotifications.aboutASubmission(notify) ? submitted.runId() : null, null));
            RunRecord record = ledger.last();
            assertThat(view.verdict().outcomeCode()).as(scenario.id() + " outcome").isEqualTo(claim.outcome());
            assertThat(record.outcome()).as(scenario.id() + " ledger outcome").isEqualTo(claim.outcome());
            claim.codes().forEach((element, code) -> assertThat(record.coverageCodes()).as(scenario.id() + " " + element).contains(element + "=" + code));
            assertThat(record.scenarioId()).isEqualTo(scenario.id());
            assertThat(view.sample()).isTrue();
            assertThat(view.payload()).as(scenario.id() + " leaves no token unfilled").doesNotContain("{{");
            driven++;
        }
        assertThat(driven).isEqualTo(5);
    }

    @Test
    void aSampleIsDeliveredToTheSameCodeARealNotificationReachesAndIsMatchedToTheRunTheTesterNames() {
        RunEnvelope<SubscriptionDetail> created = createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-NOTIFY");

        // With no scenario named, the notification is the one that belongs to the answer that submission was given.
        NotificationView decided = receiver.deliverSample(new NotificationSampleRequest(null, submitted.runId(), null));
        assertThat(decided.scenarioId()).isEqualTo("pas-06-submit-then-notified");
        assertThat(decided.sample()).isTrue();
        assertThat(decided.verdict().supporting().get(0)).isEqualTo("This is a sample delivered on the mock profile from scenario "
                + "pas-06-submit-then-notified. It did not come from a service.");
        assertThat(decided.verdict().outcomeCode()).isEqualTo("NOTIFICATION_RECEIVED");
        assertThat(decided.subscriptionRunId()).isEqualTo(created.runId());
        assertThat(decided.correlatedRunId()).isEqualTo(submitted.runId());
        assertThat(decided.claimId()).isEqualTo(submitted.detail().claimId());
        assertThat(decided.claimResponseId()).isEqualTo(claimResponseIdOf(submitted));
        assertThat(decided.claimResponses().get(0).decision()).isEqualTo(PasOutcome.APPROVED);
        assertThat(decided.payload()).contains(submitted.detail().claimId()).contains(SUBSCRIPTION);
        assertThat(ledger.last().scenarioId()).isEqualTo("pas-06-submit-then-notified");
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, submitted.runId())
                .containsEntry(RunRecord.CORR_SUBSCRIPTION_RUN_ID, created.runId())
                .containsEntry(RunRecord.CORR_AUTHORIZATION_REF, "PA-PLACEHOLDER-0006");

        // The id-only scenario about the same run.
        NotificationView idOnly = receiver.deliverSample(new NotificationSampleRequest("pas-11-notification-known-run", " " + submitted.runId() + " ", null));
        assertThat(idOnly.payloadContent()).isEqualTo("id-only");
        assertThat(idOnly.correlatedRunId()).isEqualTo(submitted.runId());
        // It is in the list like any other, marked as a sample.
        assertThat(receiver.list(null).notifications()).extracting(NotificationView::sample).containsExactly(true, true);

        // A subscription the tester names is used, and one nobody created leaves the sample unmatched.
        NotificationView elsewhere = receiver.deliverSample(new NotificationSampleRequest("pas-20-heartbeat", null, "subscription-of-another-client"));
        assertThat(elsewhere.verdict().outcomeCode()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(elsewhere.subscriptionId()).isEqualTo("subscription-of-another-client");
    }

    @Test
    void aSampleDeliveredBeforeAnySubscriptionExistsIsUnmatchedAndSaysHowToSeeAMatchedOne() {
        NotificationView view = receiver.deliverSample(new NotificationSampleRequest("pas-10-subscription-lifecycle", null, null));
        assertThat(view.verdict().outcomeCode()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(view.subscriptionId()).isEqualTo(MockNotifications.NO_SUBSCRIPTION);
        assertThat(view.type().code()).isEqualTo("handshake");
        assertThat(view.payloadNotes()).containsExactly("No run in the ledger has created a subscription, so this sample names one nobody created "
                + "and is shown as unmatched. Create a subscription first to see a notification that is matched to it.");
    }

    @Test
    void aSampleIsRefusedOnLiveAndWhenItCannotBeMadeAboutWhatWasNamed() {
        RunEnvelope<SubscriptionDetail> created = createSubscription();
        RunEnvelope<PasDetail> submitted = submit("P32H-PAS-APPROVED");
        int lines = ledger.records().size();

        NotificationReceiver live = new NotificationReceiver(gateway, support("live"), ledgerProvider(), scenarios);
        assertThatThrownBy(() -> live.deliverSample(new NotificationSampleRequest("pas-20-heartbeat", null, null)))
                .isInstanceOfSatisfying(ValidationException.class, e -> {
                    assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("scenarioId");
                    assertThat(e.fieldErrors().get(0).message()).contains("mock profile only");
                });
        // The receiver itself works on live: only the sample does not.
        assertThat(live.receive(NotificationReceiver.Inbound.of("{}")).kind()).isEqualTo(NotificationKind.OTHER_JSON);
        lines++;

        assertSampleInvalid(new NotificationSampleRequest(null, null, null), "scenarioId");
        assertSampleInvalid(new NotificationSampleRequest("pas-99-nope", null, null), "scenarioId");
        assertSampleInvalid(new NotificationSampleRequest("pas-11-notification-known-run", null, null), "runId");
        assertSampleInvalid(new NotificationSampleRequest("pas-11-notification-known-run", "not a run id", null), "runId");
        assertSampleInvalid(new NotificationSampleRequest("pas-11-notification-known-run", created.runId(), null), "runId");
        assertSampleInvalid(new NotificationSampleRequest("pas-20-heartbeat", null, "the subscription of " + SURNAME), "subscriptionId");
        assertSampleInvalid(null, "scenarioId");
        // A scenario that holds no notification is refused by name, with the ones that do.
        assertThatThrownBy(() -> receiver.deliverSample(new NotificationSampleRequest("pas-01-approved", null, null)))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).message())
                        .contains("pas-01-approved holds no notification").contains("pas-06-submit-then-notified").contains("pas-20-heartbeat"));
        // So is the scenario that answered the run, when that scenario holds none.
        assertSampleInvalid(new NotificationSampleRequest(null, submitted.runId(), null), "scenarioId");
        assertThatThrownBy(() -> receiver.deliverSample(new NotificationSampleRequest("pas-11-notification-known-run",
                "run-00000000-0000-4000-8000-00000000dead", null))).isInstanceOf(NotFoundException.class)
                .hasMessage("There is no run with that id in the ledger.");

        // Nothing that was refused was delivered or recorded.
        assertThat(ledger.records()).hasSize(lines);
        assertThat(receiver.list(null).notifications()).isEmpty();
    }

    private void assertSampleInvalid(NotificationSampleRequest request, String field) {
        assertThatThrownBy(() -> receiver.deliverSample(request)).as("expected a validation error on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly(field));
    }
}
