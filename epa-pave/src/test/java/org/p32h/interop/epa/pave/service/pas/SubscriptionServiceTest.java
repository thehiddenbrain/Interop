package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.client.pas.SubscriptionClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireResponse;
import org.p32h.interop.epa.pave.model.pas.SubscriptionDetail;
import org.p32h.interop.epa.pave.model.pas.SubscriptionResponseKind;
import org.p32h.interop.epa.pave.model.pas.SubscriptionView;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioExpect;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.service.scenario.ScenarioStep;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * The subscription service driven through the real scenario files, the real mock client and the
 * real mapper, with a fake gateway and an in-memory ledger around them.
 */
class SubscriptionServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String ID = "subscription-placeholder-0001";
    private static final String RECEIVER = "https://pave.example.invalid/api/pas/notifications";
    private static final Map<String, String> WITH_RECEIVER = Map.of("subscription.callback-url", RECEIVER);
    private static final EpaOperation CREATE = EpaOperation.SUBSCRIPTION_CREATE;
    private static final EpaOperation GET = EpaOperation.SUBSCRIPTION_GET;
    private static final EpaOperation UPDATE = EpaOperation.SUBSCRIPTION_UPDATE;
    private static final EpaOperation DELETE = EpaOperation.SUBSCRIPTION_DELETE;

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final SubscriptionClient mockClient = new MockSubscriptionClient(scenarios, new MockExchanges(APP));

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private SubscriptionService service(SubscriptionClient client, String mode, Map<String, String> onyx) {
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        OnyxProperties onyxProperties = OnyxProperties.of(onyx);
        return new SubscriptionService(onyxProperties, gateway, client,
                new RunSupport(epa, TrackingProperties.defaults(), onyxProperties, ledgerProvider(), CLOCK));
    }

    private SubscriptionService service() {
        return service(mockClient, "mock", WITH_RECEIVER);
    }

    private static SubscriptionRequest.Builder request() {
        return SubscriptionRequest.builder();
    }

    private static SubscriptionRequest.Builder about(String id) {
        return SubscriptionRequest.builder().subscriptionId(id);
    }

    private RunEnvelope<SubscriptionDetail> run(EpaOperation operation, SubscriptionRequest request) {
        return service().run(operation, request, RunOrigin.manual(), null);
    }

    /** A client that counts its calls and answers every one of them with a subscription. */
    private static final class CountingClient implements SubscriptionClient {
        private final AtomicInteger calls = new AtomicInteger();

        private VendorExchange answer(EpaOperation operation) {
            calls.incrementAndGet();
            return TestExchanges.of(operation, 200, SubscriptionResponseMapperTest.subscription(ID, "active"), false);
        }

        @Override
        public VendorExchange create(PreparedCall call, ResolvedText body, MockSelection selection) {
            return answer(CREATE);
        }

        @Override
        public VendorExchange get(PreparedCall call, MockSelection selection) {
            return answer(GET);
        }

        @Override
        public VendorExchange update(PreparedCall call, ResolvedText body, MockSelection selection) {
            return answer(UPDATE);
        }

        @Override
        public VendorExchange delete(PreparedCall call, MockSelection selection) {
            return answer(DELETE);
        }
    }

    private static SubscriptionClient stub(VendorExchange exchange) {
        return new SubscriptionClient() {
            @Override
            public VendorExchange create(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange get(PreparedCall call, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange update(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange delete(PreparedCall call, MockSelection selection) {
                return exchange;
            }
        };
    }

    // ---- every subscription step of every scenario file ----

    @Test
    void everySubscriptionStepOfEveryScenarioFileProducesExactlyWhatTheFileClaims() {
        Map<String, EpaOperation> steps = new LinkedHashMap<>();
        steps.put(MockSubscriptionClient.STEP_CREATE, CREATE);
        steps.put(MockSubscriptionClient.STEP_READ, GET);
        steps.put(MockSubscriptionClient.STEP_LIST, GET);
        steps.put(MockSubscriptionClient.STEP_UPDATE, UPDATE);
        steps.put(MockSubscriptionClient.STEP_DELETE, DELETE);
        int driven = 0;
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            for (Map.Entry<String, EpaOperation> step : steps.entrySet()) {
                ScenarioStep defined = scenario.step(step.getKey());
                if (defined == null) {
                    continue;
                }
                String what = scenario.id() + " " + step.getKey();
                ScenarioExpect claim = defined.expect();
                assertThat(claim).as(what + " makes a claim").isNotNull();
                boolean one = !step.getKey().equals(MockSubscriptionClient.STEP_CREATE) && !step.getKey().equals(MockSubscriptionClient.STEP_LIST);
                // The claim is put through as a tester's expectation would be, so the file is checked by the same comparison.
                StringBuilder declared = new StringBuilder(claim.outcome());
                claim.codes().forEach((element, code) -> declared.append("; ").append(element).append("=").append(code));
                RunEnvelope<SubscriptionDetail> result = run(step.getValue(), (one ? about(ID) : request()).scenarioId(scenario.id())
                        .expected(declared.toString()).build());

                RunRecord record = ledger.last();
                assertThat(result.verdict().outcomeCode()).as(what + " outcome").isEqualTo(claim.outcome());
                assertThat(record.outcome()).as(what + " ledger outcome").isEqualTo(claim.outcome());
                assertThat(record.errorType()).as(what + " error type").isEqualTo(claim.errorType());
                assertThat(record.httpStatus()).as(what + " status").isEqualTo(claim.httpStatus());
                claim.codes().forEach((element, code) -> assertThat(record.coverageCodes()).as(what + " " + element).contains(element + "=" + code));
                assertThat(result.expectation().result()).as(what + " as the expectation " + declared).isEqualTo(TestResult.PASS);
                assertThat(record.scenarioId()).isEqualTo(scenario.id());
                assertThat(result.verdict().supporting().get(0)).as(what).startsWith("Mock scenario " + scenario.id() + " answered: chosen in the scenario selector");
                // The designed view takes everything the bundled answers carry.
                assertThat(result.additionalFields()).as(what + " leaves nothing uninterpreted").isEmpty();
                driven++;
            }
        }
        assertThat(driven).isEqualTo(8);
    }

    // ---- create ----

    @Test
    void aCreateReturnsTheVerdictTheNextStepAndALedgerLineThatCarriesTheNewSubscriptionsId() {
        RunEnvelope<SubscriptionDetail> result = run(CREATE, request().expected("CREATED; subscription-status=requested").testerRef("QA-17")
                .note("first subscription").build());

        assertThat(result.protocol()).isEqualTo(Protocol.SUBSCRIPTION);
        assertThat(result.operation()).isEqualTo(CREATE);
        assertThat(result.verdict().headline()).isEqualTo("The subscription was created.");
        assertThat(result.verdict().tone()).isEqualTo(Tone.CLEAR);
        assertThat(result.verdict().supporting()).containsExactly(
                "Mock scenario pas-10-subscription-lifecycle answered: the default for a subscription call, because no scenario was chosen.",
                "Its id is " + ID + ".", "Status: Requested: the service has not yet activated it.", "It calls back " + RECEIVER + ".");
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(result.nextStep().action()).isEqualTo(NextAction.START_PAS);
        // What a later read, update or delete starts from, and what a notification is matched by.
        assertThat(result.nextStep().carry()).containsExactly(Map.entry(NextStepView.CARRY_SUBSCRIPTION_ID, List.of(ID)),
                Map.entry(NextStepView.CARRY_RUN_ID, List.of(result.runId())));
        assertThat(result.error()).isNull();
        assertThat(result.findings()).isEmpty();

        SubscriptionDetail detail = result.detail();
        assertThat(detail.responseKind()).isEqualTo(SubscriptionResponseKind.SUBSCRIPTION);
        assertThat(detail.subscriptionId()).isEqualTo(ID);
        assertThat(detail.idSource()).isEqualTo("the id of the Subscription the service returned");
        assertThat(detail.callbackUrl()).isEqualTo(RECEIVER);
        assertThat(detail.bodyBuiltByTool()).isTrue();
        assertThat(detail.requestNotes()).anySatisfy(note -> assertThat(note).contains("placeholder and names no real topic"));
        assertThat(detail.subscriptions().get(0).endpoint().value()).isEqualTo(RECEIVER);
        assertThat(result.determination()).extracting(DeterminationRow::label, DeterminationRow::code).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Subscription status", "requested"), org.assertj.core.groups.Tuple.tuple("Channel type", "rest-hook"),
                org.assertj.core.groups.Tuple.tuple("Notification content", "id-only"));

        // What was sent names this tool's receiver, as FHIR JSON.
        JsonNode sent = JsonSafe.parse(result.wire().request().body()).node();
        assertThat(sent.get("resourceType").asString()).isEqualTo("Subscription");
        assertThat(sent.at("/channel/endpoint").asString()).isEqualTo(RECEIVER);
        assertThat(result.wire().request().method()).isEqualTo("POST");
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.wire().request().headers()).extracting(h -> h.name() + ": " + h.value()).contains("Content-Type: application/fhir+json");
        assertThat(result.wire().memberId()).isNull();

        RunRecord record = ledger.only();
        assertThat(record.runId()).isEqualTo(result.runId());
        assertThat(record.protocol()).isEqualTo(Protocol.SUBSCRIPTION);
        assertThat(record.operation()).isEqualTo(CREATE);
        assertThat(record.outcome()).isEqualTo("CREATED");
        assertThat(record.httpStatus()).isEqualTo(201);
        assertThat(record.coverageCodes()).containsExactly("subscription-status=requested", "channel-type=rest-hook", "payload-content=id-only");
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, ID));
        assertThat(record.expected()).isEqualTo("CREATED; subscription-status=requested");
        assertThat(record.result()).isEqualTo(TestResult.PASS);
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.note()).isEqualTo("first subscription");
        assertThat(record.nextAction()).isEqualTo("START_PAS");
        assertThat(record.memberId()).isNull();
        assertThat(record.procedureCodes()).isEmpty();
        assertThat(record.unrecordedInputs()).isEmpty();
    }

    @Test
    void withNoCallbackUrlACreateStillSendsAndTheResultSaysInPlainWordsThatTheServiceHasNowhereToCallBack() {
        RunEnvelope<SubscriptionDetail> result = service(mockClient, "mock", Map.of()).run(CREATE, request().build(), RunOrigin.manual(), null);

        // It was sent, and recorded: the missing receiver is a finding, not a refusal.
        assertThat(result.verdict().outcomeCode()).isEqualTo("CREATED");
        assertThat(result.ledger().recorded()).isTrue();
        assertThat(JsonSafe.parse(result.wire().request().body()).node().at("/channel").has("endpoint")).isFalse();
        assertThat(result.detail().callbackUrl()).isNull();

        Finding finding = result.findings().stream().filter(f -> f.kind() == FindingKind.REQUIRED_ELEMENT_MISSING).findFirst().orElseThrow();
        assertThat(finding.title()).isEqualTo("The subscription names no url to call back");
        assertThat(finding.detail()).isEqualTo("The service has nowhere to send a notification, so none can arrive at this tool for this "
                + "subscription. The setting onyx.subscription.callback-url is blank. Set it to the address at which the service can reach "
                + "this tool's receiver, which ends in /api/pas/notifications, and send again. Until then, follow a request with an "
                + "inquiry: that path needs no callback.");
        assertThat(finding.subject()).isEqualTo("channel.endpoint");
        assertThat(ledger.only().findings()).containsExactly("REQUIRED_ELEMENT_MISSING");
        // And the next step is to set it, not to wait for a notification that cannot come.
        assertThat(result.nextStep().action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(result.nextStep().headline()).isEqualTo("Next: set onyx.subscription.callback-url to an address the service can reach, then "
                + "update this subscription or create another. Until then, follow a request with an inquiry.");
        assertThat(result.nextStep().reason()).isEqualTo("The subscription was created without a url to call back, so no notification can "
                + "reach this tool. The setting onyx.subscription.callback-url is blank. Set it to the address at which the service can reach "
                + "this tool's receiver, which ends in /api/pas/notifications, and send again.");
        assertThat(result.detail().requestNotes()).anySatisfy(note -> assertThat(note).startsWith("No endpoint was sent, so the service has "
                + "nowhere to call back."));

        // An update that names no receiver says the same, and is not reported as done with nothing left to do.
        RunEnvelope<SubscriptionDetail> updated = service(mockClient, "mock", Map.of()).run(UPDATE, about(ID).build(), RunOrigin.manual(), null);
        assertThat(updated.verdict().outcomeCode()).isEqualTo("UPDATED");
        assertThat(updated.findings()).extracting(Finding::kind).containsExactly(FindingKind.REQUIRED_ELEMENT_MISSING);
        assertThat(updated.nextStep().action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(updated.nextStep().reason()).startsWith("The subscription was updated without a url to call back, so no notification can "
                + "reach this tool.");
        // With a receiver named, a create leads on to a submission and an update is complete.
        assertThat(run(CREATE, request().build()).nextStep().action()).isEqualTo(NextAction.START_PAS);
        assertThat(run(UPDATE, about(ID).build()).nextStep().action()).isEqualTo(NextAction.FLOW_ENDS);
        // A read sends no subscription, so it has nothing to say about a receiver.
        assertThat(service(mockClient, "mock", Map.of()).run(GET, about(ID).build(), RunOrigin.manual(), null).findings()).isEmpty();
    }

    @Test
    void theIdOfANewSubscriptionIsReadFromTheLocationHeaderWhenTheServiceReturnsNoBodyAndIsSaidToBeUnknownWhenThereIsNone() {
        VendorExchange noBody = TestExchanges.of(CREATE, 201, null, false);
        VendorExchange located = new VendorExchange(noBody.request(), 201, List.of(new org.p32h.interop.epa.pave.model.WireHeader("Location",
                "https://onyx.example.invalid/fhir/Subscription/subscription-from-header/_history/1")), null, null, null, null, 12L,
                noBody.failureType(), null, java.util.Set.of(), null, null, List.of());
        RunEnvelope<SubscriptionDetail> fromHeader = service(stub(located), "live", WITH_RECEIVER).run(CREATE, request().build(), RunOrigin.manual(), null);
        assertThat(fromHeader.verdict().outcomeCode()).isEqualTo("CREATED");
        assertThat(fromHeader.detail().subscriptionId()).isEqualTo("subscription-from-header");
        assertThat(fromHeader.detail().idSource()).isEqualTo("the Location header of the response");
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, "subscription-from-header");
        assertThat(fromHeader.determination()).singleElement().satisfies(row -> assertThat(row.presence()).isEqualTo(Presence.ABSENT));

        RunEnvelope<SubscriptionDetail> unknown = service(stub(noBody), "live", WITH_RECEIVER).run(CREATE, request().build(), RunOrigin.manual(), null);
        assertThat(unknown.verdict().outcomeCode()).isEqualTo("CREATED");
        assertThat(unknown.detail().subscriptionId()).isNull();
        assertThat(unknown.verdict().supporting()).contains("The response gave no id for the new subscription, so it cannot be read, updated "
                + "or deleted from here by id.");
        assertThat(ledger.last().correlation()).doesNotContainKey(RunRecord.CORR_SUBSCRIPTION_ID);
        assertThat(unknown.nextStep().carry()).doesNotContainKey(NextStepView.CARRY_SUBSCRIPTION_ID);
    }

    // ---- read, list, update, delete ----

    @Test
    void aReadAListAnUpdateAndADeleteEachHaveTheirOwnOutcomeAndTheirOwnLedgerLine() {
        RunEnvelope<SubscriptionDetail> read = run(GET, about(ID).build());
        RunEnvelope<SubscriptionDetail> list = run(GET, request().build());
        RunEnvelope<SubscriptionDetail> updated = run(UPDATE, about(ID).variables(Map.of("subscriptionStatus", "off")).build());
        RunEnvelope<SubscriptionDetail> deleted = run(DELETE, about(ID).build());

        assertThat(read.verdict().headline()).isEqualTo("The subscription was returned.");
        assertThat(read.verdict().supporting()).contains("Status: Active.");
        assertThat(list.verdict().headline()).isEqualTo("The service listed 1 subscription.");
        assertThat(list.detail().total().value()).isEqualTo("1");
        assertThat(updated.verdict().headline()).isEqualTo("The subscription was updated.");
        assertThat(JsonSafe.parse(updated.wire().request().body()).node().get("id").asString()).isEqualTo(ID);
        assertThat(JsonSafe.parse(updated.wire().request().body()).node().get("status").asString()).isEqualTo("off");
        assertThat(updated.wire().request().method()).isEqualTo("PUT");
        assertThat(deleted.verdict().headline()).isEqualTo("The subscription was deleted.");
        assertThat(deleted.wire().request().method()).isEqualTo("DELETE");
        assertThat(deleted.wire().request().body()).isNull();
        assertThat(deleted.detail().responseKind()).isEqualTo(SubscriptionResponseKind.NO_BODY);
        // Nothing came back to hold a status, and the grid says so in place of leaving the row out.
        assertThat(deleted.determination()).singleElement().satisfies(row -> assertThat(row.presence()).isEqualTo(Presence.ABSENT));

        assertThat(ledger.records()).extracting(RunRecord::operation).containsExactly(GET, GET, UPDATE, DELETE);
        assertThat(ledger.records()).extracting(RunRecord::outcome).containsExactly("READ", "LISTED", "UPDATED", "DELETED");
        assertThat(ledger.records()).extracting(RunRecord::httpStatus).containsExactly(200, 200, 200, 204);
        assertThat(ledger.records()).extracting(RunRecord::nextAction).containsOnly("FLOW_ENDS");
        assertThat(ledger.records()).extracting(RunRecord::unrecordedInputs).containsExactly(List.of(), List.of(), List.of(RunRecord.INPUT_RUN_VALUES),
                List.of());
        // Each line about one subscription names it. A list names none.
        assertThat(ledger.records().get(0).correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, ID));
        assertThat(ledger.records().get(1).correlation()).isEmpty();
        assertThat(ledger.records().get(2).correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, ID));
        assertThat(ledger.records().get(3).correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, ID));
        assertThat(ledger.records().get(3).coverageCodes()).isEmpty();
    }

    @Test
    void theIdReachesTheGatewayAsAUrlValueAndNeverAsPartOfAUrlTheServiceBuiltItself() {
        run(GET, about(ID).build());
        assertThat(gateway.lastIntent().operation()).isEqualTo(GET);
        assertThat(gateway.lastIntent().urlValues()).containsExactly(Map.entry("id", ID));
        run(DELETE, about(" " + ID + " ").build());
        assertThat(gateway.lastIntent().urlValues()).containsExactly(Map.entry("id", ID));
        // A list and a create name no subscription, so they supply no value.
        run(GET, request().build());
        assertThat(gateway.lastIntent().urlValues()).isEmpty();
        run(CREATE, request().build());
        assertThat(gateway.lastIntent().urlValues()).isEmpty();
        assertThat(gateway.lastIntent().operation()).isEqualTo(CREATE);

        // With a url configured, the wire view shows where the call for that one subscription goes.
        gateway.variable("baseUrl", "https://onyx.example.invalid/fhir").url(GET, "{{baseUrl}}/Subscription/{id}");
        assertThat(run(GET, about(ID).build()).wire().request().url()).isEqualTo("https://onyx.example.invalid/fhir/Subscription/" + ID);
    }

    @Test
    void anIdTheServiceDoesNotKnowIsAFindingAboutTheIdAndNotAFault() {
        RunEnvelope<SubscriptionDetail> result = run(GET, about(ID).scenarioId("pas-19-subscription-not-found").expected("NOT_FOUND").build());
        assertThat(result.verdict().headline()).isEqualTo("The service does not know this subscription.");
        assertThat(result.verdict().tone()).isEqualTo(Tone.DATA);
        assertThat(result.verdict().supporting()).contains("This is a finding about the id that was asked for, not about the environment.");
        assertThat(result.nextStep().action()).isEqualTo(NextAction.FIX_TEST_DATA);
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        // No error panel: the service answered. Its issue is still shown in the detail.
        assertThat(result.error()).isNull();
        assertThat(result.detail().issues()).hasSize(1);
        assertThat(ledger.only().outcome()).isEqualTo("NOT_FOUND");
        assertThat(ledger.only().errorType()).isEqualTo("BAD_REQUEST");
        assertThat(ledger.only().correlation()).containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, ID);
    }

    // ---- the production guardrail ----

    private static ProdWriteBlockedException blocked(EpaOperation operation) {
        return new ProdWriteBlockedException(operation, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment.",
                new ResolvedUrl("{{baseUrl}}/Subscription", "https://onyx.example.invalid/fhir/Subscription",
                        "https://onyx.example.invalid/fhir/Subscription", List.of(), List.of(), "subscription sample"), operation.defaultMethod(),
                "subscription sample");
    }

    @Test
    void aCreateAnUpdateAndADeleteTheGuardrailRefusesAreEachRecordedAsBlockedAndNothingReachesTheClient() {
        CountingClient client = new CountingClient();
        SubscriptionService service = service(client, "live", WITH_RECEIVER);
        for (EpaOperation operation : List.of(CREATE, UPDATE, DELETE)) {
            gateway.failing(blocked(operation));
            RunEnvelope<SubscriptionDetail> result = service.run(operation, (operation == CREATE ? request() : about(ID)).expected(
                    operation == CREATE ? "CREATED" : operation == UPDATE ? "UPDATED" : "DELETED").build(), RunOrigin.manual(), null);

            assertThat(RunSupport.isBlocked(result)).as(operation.name()).isTrue();
            assertThat(result.verdict().outcomeCode()).isEqualTo("BLOCKED");
            assertThat(result.verdict().tone()).isEqualTo(Tone.BLOCKED);
            assertThat(result.expectation().result()).isEqualTo(TestResult.UNVERIFIED);
            assertThat(result.nextStep().action()).isEqualTo(NextAction.UNLOCK_OR_SWITCH);
            assertThat(result.error().kind()).isEqualTo(ErrorKind.BLOCKED);
            assertThat(result.operation()).isEqualTo(operation);
            assertThat(result.protocol()).isEqualTo(Protocol.SUBSCRIPTION);
            assertThat(result.wire().request().sent()).isFalse();
            assertThat(result.wire().request().method()).isEqualTo(operation.defaultMethod());
            assertThat(result.wire().response()).isNull();
            assertThat(result.detail()).isNull();
            RunRecord record = ledger.last();
            assertThat(record.runId()).isEqualTo(result.runId());
            assertThat(record.operation()).isEqualTo(operation);
            assertThat(record.outcome()).isEqualTo("BLOCKED");
            assertThat(record.errorType()).isEqualTo(RunRecord.ERROR_BLOCKED);
            assertThat(record.environmentKind()).isEqualTo(EnvironmentKind.PROD);
            assertThat(record.httpStatus()).isNull();
        }
        assertThat(client.calls.get()).isZero();
        assertThat(ledger.records()).hasSize(3);
    }

    @Test
    void aReadAndAListAreReadsAndGoThroughOnAProductionEnvironmentWithNoUnlock() {
        gateway.environment("Onyx Production", EnvironmentKind.PROD);
        assertThat(run(GET, about(ID).build()).verdict().outcomeCode()).isEqualTo("READ");
        assertThat(run(GET, request().build()).verdict().outcomeCode()).isEqualTo("LISTED");
        assertThat(EpaOperation.SUBSCRIPTION_GET.write()).isFalse();
        assertThat(gateway.intents()).extracting(i -> i.unlock()).containsOnlyNulls();
        assertThat(ledger.records()).extracting(RunRecord::environmentKind).containsOnly(EnvironmentKind.PROD);
    }

    @Test
    void theSessionsUnlockIsHandedToTheGatewayAndNothingInThePostedRequestCanStandInForIt() {
        ProdUnlock unlock = TestPreparedCalls.unlock("Onyx Production", 0L);
        for (EpaOperation operation : List.of(CREATE, UPDATE, DELETE, GET)) {
            service().run(operation, (operation == CREATE ? request() : about(ID)).build(), RunOrigin.manual(), unlock);
            assertThat(gateway.lastIntent().unlock()).as(operation.name()).isSameAs(unlock);
        }
        run(DELETE, about(ID).build());
        assertThat(gateway.lastIntent().unlock()).isNull();

        int before = gateway.intents().size();
        SubscriptionRequest posted = JsonSafe.mapper().readValue("{\"unlock\":{\"environmentName\":\"Onyx Production\",\"epoch\":0}}",
                SubscriptionRequest.class);
        assertThat(posted.unrecognised()).containsOnlyKeys("unlock");
        assertInvalid(CREATE, posted, "unlock");
        assertThat(gateway.intents()).hasSize(before);
    }

    // ---- validation: nothing sent, nothing recorded ----

    @Test
    void aCallThatIsNotValidIsRefusedBeforeAnythingIsSentOrRecorded() {
        assertInvalid(CREATE, about(ID).build(), "subscriptionId");
        assertInvalid(UPDATE, request().build(), "subscriptionId");
        assertInvalid(DELETE, about("  ").build(), "subscriptionId");
        assertInvalid(GET, about("the subscription for Placeholder-Surname").build(), "subscriptionId");
        assertInvalid(DELETE, about("a/b").build(), "subscriptionId");
        assertInvalid(GET, about(ID).rawBody("{\"resourceType\":\"Subscription\"}").build(), "rawBody");
        assertInvalid(DELETE, about(ID).rawBody("{}").build(), "rawBody");
        // An outcome only a submission has, and an element only a submission returns, cannot be checked here.
        assertInvalid(CREATE, request().expected("APPROVED").build(), "expected");
        assertInvalid(CREATE, request().expected("review-action=A1").build(), "expected");
        assertInvalid(GET, request().chainId("free text").build(), "chainId");
        assertInvalid(GET, request().carry(Map.of(NextStepView.CARRY_RUN_ID, List.of("not a run id"))).build(), "carry.runId");
        assertInvalid(GET, request().unrecognised(Map.of("expectd", "LISTED")).build(), "expectd");
        // A subscription call has no member, so a posted member is a field this tool does not know here.
        SubscriptionRequest withMember = JsonSafe.mapper().readValue("{\"member\":{\"memberId\":\"S123456789\"},\"procedureCodes\":[]}",
                SubscriptionRequest.class);
        assertThat(withMember.unrecognised()).containsOnlyKeys("member", "procedureCodes");
        assertInvalid(CREATE, withMember, "member");

        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
        // A scenario cannot be forced on live.
        assertThatThrownBy(() -> service(mockClient, "live", WITH_RECEIVER).run(GET, about(ID).scenarioId("pas-19-subscription-not-found").build(),
                RunOrigin.manual(), null)).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly("scenarioId"));
        // And an operation that is not a subscription call is a programming error, not a form to correct.
        assertThatThrownBy(() -> run(EpaOperation.PAS_SUBMIT, request().build())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(EpaOperation.SUBSCRIPTION_NOTIFICATION, request().build())).isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInvalid(EpaOperation operation, SubscriptionRequest request, String field) {
        assertThatThrownBy(() -> run(operation, request)).as("expected a validation error on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).contains(field));
    }

    @Test
    void aChosenScenarioThatDoesNotAnswerTheCallIsRefusedByNameAndNothingIsRecorded() {
        assertThatThrownBy(() -> run(CREATE, request().scenarioId("pas-19-subscription-not-found").build()))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).message())
                        .contains("pas-19-subscription-not-found").contains("\"subscription-create\""));
        assertThat(ledger.records()).isEmpty();
    }

    // ---- faults, each rendered in full ----

    @Test
    void aWriteThatCouldNotBeCompletedIsNeverSaidToHaveDeliveredNothing() {
        // As a live client reports it. A connection that never opened and one that failed after it
        // opened are both "unreachable", and this tool cannot tell which.
        RunEnvelope<SubscriptionDetail> create = service(stub(TestExchanges.unavailable(CREATE)), "live", WITH_RECEIVER)
                .run(CREATE, request().build(), RunOrigin.manual(), null);
        assertThat(create.verdict().outcomeCode()).isEqualTo("UNREACHABLE");
        assertThat(create.verdict().supporting()).containsExactly("The connection was refused or could not be opened.");
        assertThat(create.nextStep().action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(create.nextStep().headline()).isEqualTo("Next: check the environment. Send again only when the connection never opened.");
        assertThat(create.nextStep().reason()).isEqualTo("No answer came back, and the verdict says how the connection failed. A connection "
                + "that never opened delivered nothing. One that failed after it opened may have delivered the request, so list the "
                + "subscriptions before sending again.");
        // A read changes nothing, so it is sent again however the connection failed.
        RunEnvelope<SubscriptionDetail> read = service(stub(TestExchanges.unavailable(GET)), "live", WITH_RECEIVER)
                .run(GET, about(ID).build(), RunOrigin.manual(), null);
        assertThat(read.nextStep().headline()).isEqualTo("Next: check the environment, then send again.");
        assertThat(read.nextStep().reason()).isEqualTo("No answer came back. This is a finding about the environment, not about the "
                + "subscription.");
    }

    @Test
    void aRejectionAServerErrorATimeoutAMalformedBodyAndAnEmptyBodyAreEachRenderedInFull() {
        String invalid = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\",\"diagnostics\":"
                + "\"Subscription.criteria is not a known topic.\"}],\"vendorRequestId\":\"req-7\"}";
        RunEnvelope<SubscriptionDetail> rejected = service(stub(TestExchanges.of(CREATE, 400, invalid, false)), "mock", WITH_RECEIVER)
                .run(CREATE, request().expected("CREATED").build(), RunOrigin.manual(), null);
        assertThat(rejected.verdict().headline()).isEqualTo("The service did not accept the request as sent (HTTP 400).");
        assertThat(rejected.verdict().tone()).isEqualTo(Tone.FAULT);
        assertThat(rejected.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(rejected.error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(rejected.error().issues()).hasSize(1);
        assertThat(rejected.detail().issues()).hasSize(1);
        assertThat(rejected.additionalFields()).extracting(f -> f.path()).containsExactly("vendorRequestId");
        assertThat(rejected.nextStep().action()).isEqualTo(NextAction.FIX_REQUEST);
        assertThat(rejected.wire().response().status()).isEqualTo(400);

        RunEnvelope<SubscriptionDetail> unauthorised = service(stub(TestExchanges.of(GET, 401, null, true)), "mock", WITH_RECEIVER)
                .run(GET, about(ID).build(), RunOrigin.manual(), null);
        assertThat(unauthorised.nextStep().action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(unauthorised.nextStep().headline()).isEqualTo("Next: check the credentials on the Config tab.");

        RunEnvelope<SubscriptionDetail> serverError = service(stub(TestExchanges.of(UPDATE, 503, "<html>Unavailable</html>", false)), "mock",
                WITH_RECEIVER).run(UPDATE, about(ID).build(), RunOrigin.manual(), null);
        assertThat(serverError.verdict().headline()).isEqualTo("The service failed (HTTP 503).");
        assertThat(serverError.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(serverError.additionalFields()).singleElement().satisfies(field -> assertThat(field.value()).isEqualTo("<html>Unavailable</html>"));

        // As a live client reports it: the delete was sent, and no answer arrived in time.
        RunEnvelope<SubscriptionDetail> timeout = service(stub(TestExchanges.timeout(DELETE)), "live", WITH_RECEIVER)
                .run(DELETE, about(ID).build(), RunOrigin.manual(), null);
        assertThat(timeout.verdict().headline()).isEqualTo("The service did not answer in time.");
        assertThat(timeout.verdict().supporting()).contains("The request was sent. Whether the service applied it is not known.");
        assertThat(timeout.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(timeout.nextStep().headline()).isEqualTo("Next: check the environment, then list the subscriptions before sending again.");
        assertThat(timeout.detail().responseKind()).isEqualTo(SubscriptionResponseKind.NO_RESPONSE);

        RunEnvelope<SubscriptionDetail> down = service(stub(TestExchanges.unavailable(GET)), "live", WITH_RECEIVER)
                .run(GET, request().build(), RunOrigin.manual(), null);
        assertThat(down.verdict().outcomeCode()).isEqualTo("UNREACHABLE");
        assertThat(down.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);

        String garbled = "{\"resourceType\": \"Subscription\", ";
        RunEnvelope<SubscriptionDetail> malformed = service(stub(TestExchanges.of(GET, 200, garbled, true)), "mock", WITH_RECEIVER)
                .run(GET, about(ID).build(), RunOrigin.manual(), null);
        assertThat(malformed.verdict().headline()).isEqualTo("The service answered with a body that could not be read (HTTP 200).");
        assertThat(malformed.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        assertThat(malformed.additionalFields()).singleElement().satisfies(field -> assertThat(field.value()).isEqualTo(garbled));
        assertThat(malformed.wire().response().body()).isEqualTo(garbled);

        RunEnvelope<SubscriptionDetail> empty = service(stub(TestExchanges.of(GET, 200, null, true)), "mock", WITH_RECEIVER)
                .run(GET, about(ID).build(), RunOrigin.manual(), null);
        assertThat(empty.verdict().headline()).isEqualTo("The service answered with no body (HTTP 200).");
        assertThat(empty.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);

        assertThat(ledger.records()).extracting(RunRecord::outcome).containsExactly("REJECTED", "REJECTED", "SERVICE_ERROR", "TIMEOUT", "UNREACHABLE",
                "MALFORMED_RESPONSE", "MALFORMED_RESPONSE");
        assertThat(ledger.records()).extracting(RunRecord::errorType).containsExactly("BAD_REQUEST", "BAD_REQUEST", "SERVER_ERROR", "TIMEOUT",
                "UNAVAILABLE", "MALFORMED_RESPONSE", "MALFORMED_RESPONSE");
        // A fault is never a subscription state.
        assertThat(ledger.records()).allSatisfy(record -> assertThat(record.coverageCodes()).isEmpty());
    }

    @Test
    void aSuccessWhoseBodyIsNotWhatTheCallReturnsIsNotReportedAsDone() {
        RunEnvelope<SubscriptionDetail> result = service(stub(TestExchanges.of(CREATE, 200, "{\"error\":\"placeholder gateway page\"}", false)),
                "mock", WITH_RECEIVER).run(CREATE, request().expected("CREATED").build(), RunOrigin.manual(), null);
        assertThat(result.verdict().outcomeCode()).isEqualTo("UNRECOGNISED_RESPONSE");
        assertThat(result.verdict().supporting()).anySatisfy(line -> assertThat(line).contains("Whether the call did anything is not known"));
        assertThat(result.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(result.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(result.additionalFields()).extracting(f -> f.path()).containsExactly("error");
        assertThat(result.detail().subscriptionId()).isNull();
        assertThat(ledger.only().correlation()).isEmpty();
    }

    @Test
    void aStatusCodeThisToolDoesNotKnowIsShownAsItCameAndRaisedAsAFinding() {
        RunEnvelope<SubscriptionDetail> result = service(stub(TestExchanges.of(GET, 200, SubscriptionResponseMapperTest.subscription(ID, "paused"),
                true)), "mock", WITH_RECEIVER).run(GET, about(ID).build(), RunOrigin.manual(), null);
        assertThat(result.verdict().supporting()).contains("Status: Unrecognised code: paused.");
        assertThat(result.determination().get(0).display()).isEqualTo("Unrecognised code: paused");
        assertThat(result.determination().get(0).recognised()).isFalse();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.kind()).isEqualTo(FindingKind.CODE_OUTSIDE_VALUE_SET);
            assertThat(finding.serviceSays()).isEqualTo("paused");
        });
        assertThat(ledger.only().coverageCodes()).containsExactly("subscription-status=paused");
        assertThat(ledger.only().findings()).containsExactly("CODE_OUTSIDE_VALUE_SET");
    }

    // ---- a body of the tester's own ----

    @Test
    void theTestersOwnBodyIsSentAsWrittenAndOneThatNamesNoReceiverIsFlagged() {
        String body = "{\"resourceType\":\"Subscription\",\"status\":\"requested\",\"vendorSampleField\":true,\"channel\":{\"type\":\"rest-hook\","
                + "\"endpoint\":\"{{receiver}}\"}}";
        RunEnvelope<SubscriptionDetail> result = run(CREATE, request().rawBody(body).variables(Map.of("receiver", RECEIVER)).build());
        assertThat(result.wire().request().body()).isEqualTo(body.replace("{{receiver}}", RECEIVER));
        assertThat(result.detail().bodyBuiltByTool()).isFalse();
        assertThat(result.detail().callbackUrl()).isEqualTo(RECEIVER);
        assertThat(result.detail().requestNotes()).isEmpty();
        assertThat(result.findings()).isEmpty();
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY, RunRecord.INPUT_RUN_VALUES);

        RunEnvelope<SubscriptionDetail> noReceiver = run(CREATE, request().rawBody("{\"resourceType\":\"Subscription\",\"channel\":{\"type\":\"rest-hook\"}}")
                .build());
        assertThat(noReceiver.findings()).singleElement().satisfies(finding ->
                assertThat(finding.detail()).contains("The body you supplied names no channel.endpoint"));
        // The fix is in the body they wrote. The setting this tool reads its receiver from had no part in it.
        assertThat(noReceiver.nextStep().action()).isEqualTo(NextAction.FIX_REQUEST);
        assertThat(noReceiver.nextStep().headline()).isEqualTo("Next: add channel.endpoint to the body you supplied, then update this "
                + "subscription or create another. Until then, follow a request with an inquiry.");
        assertThat(noReceiver.nextStep().headline() + noReceiver.nextStep().reason()).doesNotContain("onyx.subscription.callback-url");
        assertThat(noReceiver.nextStep().reason()).isEqualTo("The subscription was created without a url to call back, so no notification can "
                + "reach this tool. The body you supplied names no channel.endpoint. Add the address at which the service can reach this "
                + "tool's receiver, which ends in /api/pas/notifications, and send again.");

        RunEnvelope<SubscriptionDetail> notJson = run(UPDATE, about(ID).rawBody("{\"resourceType\": ").build());
        assertThat(notJson.wire().request().body()).isEqualTo("{\"resourceType\": ");
        assertThat(notJson.detail().requestNotes()).singleElement().satisfies(note -> assertThat(note).contains("not valid JSON"));
        // Whether a body that cannot be read names a receiver is not known, so nothing is claimed either way.
        assertThat(notJson.findings()).isEmpty();
        assertThat(notJson.detail().callbackUrl()).isNull();
        RunEnvelope<SubscriptionDetail> createdFromUnreadable = run(CREATE, request().rawBody("{\"resourceType\": ").build());
        assertThat(createdFromUnreadable.findings()).isEmpty();
        assertThat(createdFromUnreadable.nextStep().headline()).isEqualTo("Next: submit a prior authorization request. The service notifies a "
                + "subscription's receiver when the status of a request changes.");

        assertThatThrownBy(() -> run(CREATE, request().rawBody(body).build())).isInstanceOfSatisfying(UnresolvedVariablesException.class,
                e -> assertThat(e.variables()).containsExactly("receiver"));
        assertThat(ledger.records()).hasSize(4);
    }

    // ---- the hand-off, a failure of the tool, the log ----

    @Test
    void aStepStartedFromWhatACreateHandedOnIsLinkedToTheRunThatCreatedTheSubscription() {
        RunEnvelope<SubscriptionDetail> created = run(CREATE, request().chainId("new").build());
        RunEnvelope<SubscriptionDetail> read = run(GET, about(created.nextStep().first(NextStepView.CARRY_SUBSCRIPTION_ID))
                .chainId(created.chainId()).carry(created.nextStep().carry()).build());
        RunRecord record = ledger.last();
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, ID),
                Map.entry(RunRecord.CORR_RUN_ID, created.runId()));
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_PRIOR_STEP);
        assertThat(record.chainId()).isEqualTo(created.chainId()).startsWith("chain-");
        // A further step is still linked to the create, not to this read.
        assertThat(read.nextStep().first(NextStepView.CARRY_RUN_ID)).isEqualTo(created.runId());
    }

    @Test
    void aFailureOfThisToolAfterTheCallStillLeavesALedgerLineThatNamesTheSubscription() {
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        RunSupport failing = new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(), ledgerProvider(), CLOCK) {
            @Override
            public Finding slowFinding(Timing timing) {
                throw new IllegalStateException("the reader exploded for " + RECEIVER);
            }
        };
        RunEnvelope<SubscriptionDetail> result = new SubscriptionService(OnyxProperties.of(WITH_RECEIVER), gateway, mockClient, failing)
                .run(DELETE, about(ID).expected("DELETED").build(), RunOrigin.manual(), null);
        assertThat(RunSupport.isToolError(result)).isTrue();
        assertThat(result.verdict().outcomeCode()).isEqualTo("TOOL_ERROR");
        assertThat(result.expectation().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(result.error().reference()).isNotBlank();
        assertThat(result.error().title() + result.verdict().headline()).doesNotContain("exploded");
        assertThat(result.wire().response().status()).isEqualTo(204);
        RunRecord record = ledger.only();
        assertThat(record.outcome()).isEqualTo("TOOL_ERROR");
        assertThat(record.operation()).isEqualTo(DELETE);
        assertThat(record.httpStatus()).isEqualTo(204);
        assertThat(record.correlation()).containsExactly(Map.entry(RunRecord.CORR_SUBSCRIPTION_ID, ID));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aSubscriptionCallLogsNothingOfWhatItSentOrReceived(CapturedOutput output) {
        run(CREATE, request().build());
        run(GET, about(ID).build());
        run(DELETE, about(ID).build());
        assertThat(output.getAll()).doesNotContain(ID).doesNotContain("pave.example.invalid").doesNotContain("resourceType");
    }

    // ---- a secret the service sends back ----

    private static final String CALLBACK_SECRET = "placeholder-receiver-secret-0001";

    /** A scenario directory with one scenario, whose create answers with the subscription and the header it was sent. */
    private static AppProperties echoingScenario(Path directory) throws IOException {
        Path pas = Files.createDirectories(directory.resolve(MockOnyxPasClient.SCENARIOS));
        Files.writeString(pas.resolve("pas-90-header-returned.json"), """
                {
                  "id": "pas-90-header-returned",
                  "number": 90,
                  "label": "A subscription returned with the header it was created with",
                  "basis": "Synthetic. For a test of this tool only.",
                  "steps": {
                    "subscription-create": {
                      "httpStatus": 201,
                      "response": {
                        "resourceType": "Subscription",
                        "id": "subscription-placeholder-0001",
                        "status": "requested",
                        "criteria": "https://example.org/p32h-placeholder/SubscriptionTopic/prior-authorization-status",
                        "channel": {
                          "type": "rest-hook",
                          "endpoint": "https://pave.example.invalid/api/pas/notifications",
                          "payload": "application/fhir+json",
                          "header": ["Authorization: Bearer %s"]
                        }
                      }
                    }
                  }
                }
                """.formatted(CALLBACK_SECRET), StandardCharsets.UTF_8);
        return AppProperties.of(Map.of("mock-latency-enabled", "false", "scenario-dir", directory.toString()));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aSecretTheServiceSendsBackIsMaskedInTheWireViewAndReachesNeitherTheLedgerNorTheLog(@TempDir Path directory, CapturedOutput output)
            throws IOException {
        AppProperties app = echoingScenario(directory);
        SubscriptionClient echoing = new MockSubscriptionClient(new ScenarioRepository(app), new MockExchanges(app));
        // The credential the service is to send with each notification, kept in a secret variable as a tester would keep it.
        gateway.variable("callbackToken", CALLBACK_SECRET);
        String body = "{\"resourceType\":\"Subscription\",\"status\":\"requested\",\"channel\":{\"type\":\"rest-hook\",\"endpoint\":\""
                + RECEIVER + "\",\"header\":[\"Authorization: Bearer {{callbackToken}}\"]}}";

        RunEnvelope<SubscriptionDetail> result = service(echoing, "mock", WITH_RECEIVER).run(CREATE, request().rawBody(body).build(),
                RunOrigin.manual(), null);

        assertThat(result.verdict().outcomeCode()).isEqualTo("CREATED");
        // The request side already hides it, by the name of the variable it came from.
        assertThat(result.wire().request().body()).contains("\"Authorization: Bearer [secret callbackToken]\"");
        // The service sent it back. The wire view shows the same mask in its place, and says that it did.
        WireResponse response = result.wire().response();
        assertThat(response.body()).contains("\"header\" : [ \"Authorization: Bearer [secret callbackToken]\" ]");
        assertThat(response.maskedPlaces()).isEqualTo(1);
        assertThat(response.maskNote()).isEqualTo("1 place in the body of this response is masked because it repeats a secret this request "
                + "sent. A masked place is shown as [secret ...], naming the variable, the header or the token whose value it repeats. "
                + "What is shown here is therefore not exactly what was received. The byte count is the count of what was received.");
        assertThat(response.byteLength()).isEqualTo((long) response.body().replace("[secret callbackToken]", CALLBACK_SECRET)
                .getBytes(StandardCharsets.UTF_8).length);

        // The mapper read what really arrived, and the designed view and the returned fields hide the header as they did before.
        SubscriptionView subscription = result.detail().subscriptions().get(0);
        assertThat(subscription.headers()).extracting(Shown::value).containsExactly("Authorization: (value not shown)");
        assertThat(subscription.endpoint().value()).isEqualTo(RECEIVER);
        assertThat(result.detail().subscriptionId()).isEqualTo(ID);
        assertThat(result.additionalFields()).isEmpty();

        // Nothing the browser is sent holds it, and neither does the ledger line.
        assertThat(JsonSafe.mapper().writeValueAsString(result)).doesNotContain(CALLBACK_SECRET).contains("[secret callbackToken]");
        assertThat(JsonSafe.mapper().writeValueAsString(ledger.only())).doesNotContain(CALLBACK_SECRET).doesNotContain("callbackToken");

        // A failure of this tool after the call still shows the response, and writes to the log. Neither holds it.
        RunSupport failing = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.defaults(), OnyxProperties.defaults(),
                ledgerProvider(), CLOCK) {
            @Override
            public Finding slowFinding(Timing timing) {
                throw new IllegalStateException("the reader exploded for " + CALLBACK_SECRET);
            }
        };
        RunEnvelope<SubscriptionDetail> failed = new SubscriptionService(OnyxProperties.of(WITH_RECEIVER), gateway, echoing, failing)
                .run(CREATE, request().rawBody(body).build(), RunOrigin.manual(), null);
        assertThat(RunSupport.isToolError(failed)).isTrue();
        assertThat(failed.wire().response().maskedPlaces()).isEqualTo(1);
        assertThat(JsonSafe.mapper().writeValueAsString(failed)).doesNotContain(CALLBACK_SECRET).contains("[secret callbackToken]");
        assertThat(JsonSafe.mapper().writeValueAsString(ledger.last())).doesNotContain(CALLBACK_SECRET).doesNotContain("callbackToken");
        assertThat(output.getAll()).contains("A run failed inside this tool after the service was called").doesNotContain(CALLBACK_SECRET);
    }

    // ---- preview ----

    @Test
    void aPreviewShowsWhatACreateOrAnUpdateWouldSendWithoutSendingOrRecordingIt() {
        CountingClient client = new CountingClient();
        SubscriptionService service = service(client, "mock", WITH_RECEIVER);
        gateway.variable("baseUrl", "https://onyx.example.invalid/fhir").url(UPDATE, "{{baseUrl}}/Subscription/{id}");

        RequestPreview create = service.preview(CREATE, request().build(), null);
        assertThat(create.operation()).isEqualTo(CREATE);
        assertThat(create.method()).isEqualTo("POST");
        assertThat(JsonSafe.parse(create.body()).node().at("/channel/endpoint").asString()).isEqualTo(RECEIVER);
        assertThat(create.notes()).isNotEmpty();
        assertThat(create.memberId()).isNull();
        assertThat(create.blocked()).isNull();

        RequestPreview update = service.preview(UPDATE, about(ID).build(), null);
        assertThat(update.method()).isEqualTo("PUT");
        assertThat(update.url().display()).isEqualTo("https://onyx.example.invalid/fhir/Subscription/" + ID);
        assertThat(JsonSafe.parse(update.body()).node().get("id").asString()).isEqualTo(ID);

        RequestPreview own = service.preview(CREATE, request().rawBody("{\"endpoint\":\"{{receiver}}\"}").build(), null);
        assertThat(own.body()).isEqualTo("{\"endpoint\":\"{{receiver}}\"}");
        assertThat(own.notes()).containsExactly("The body still contains placeholders with no value: receiver.");

        gateway.failing(blocked(CREATE));
        RequestPreview blocked = service.preview(CREATE, request().build(), null);
        assertThat(blocked.blocked()).startsWith("Writes to the production environment Onyx Production are blocked.");
        assertThat(blocked.body()).isNull();

        assertThatThrownBy(() -> service.preview(UPDATE, request().build(), null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.preview(GET, about(ID).build(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(client.calls.get()).isZero();
        assertThat(ledger.records()).isEmpty();
    }
}
