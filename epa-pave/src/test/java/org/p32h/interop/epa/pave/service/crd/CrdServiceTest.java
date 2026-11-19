package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.crd.OnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * The CRD service driven through the real scenario files, the real mock client and the real
 * mapper, with a fake gateway, an in-memory ledger and an in-memory code list around them.
 */
class CrdServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String SURNAME = "Placeholder-Surname";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final FakeCodeList codeList = new FakeCodeList();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final OnyxCrdClient mockClient = new MockOnyxCrdClient(scenarios, new MockExchanges(APP));

    private CrdService service(OnyxCrdClient client, String mode, Map<String, String> tracking) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        RunSupport support = new RunSupport(epa, TrackingProperties.of(tracking), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
        return new CrdService(epa, gateway, client, codeList, support);
    }

    private CrdService service() {
        return service(mockClient, "mock", Map.of());
    }

    private static CrdRunRequest.Builder request(String memberId) {
        return CrdRunRequest.builder()
                .member(MemberDetail.builder().memberId(memberId).firstName("Given").lastName(SURNAME).dateOfBirth("1970-01-31")
                        .gender("female").lineOfBusiness("Medicare").subscriberId("SUB-PLACEHOLDER-1").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended")))
                .dateOfService("2026-03-20");
    }

    private RunEnvelope<CrdDetail> run(String memberId) {
        return service().run(request(memberId).build(), RunOrigin.manual());
    }

    // ---- the result answers the four questions ----

    @Test
    void aRunReturnsTheVerdictTheTestResultTheNextStepAndTheLedgerReceipt() {
        RunEnvelope<CrdDetail> result = service().run(request("P32H-PA-REQ").expected("AUTH_REQUIRED").testerRef("QA-17")
                .note("first run").chainId("chain-1").build(), RunOrigin.manual());

        // What happened.
        assertThat(result.verdict().headline()).isEqualTo("Prior authorization is required.");
        assertThat(result.verdict().outcomeCode()).isEqualTo("AUTH_REQUIRED");
        assertThat(result.verdict().tone()).isEqualTo(Tone.ACTION);
        // What did I expect, and did it match.
        assertThat(result.expectation().expected()).isEqualTo("AUTH_REQUIRED");
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        // What do I do next.
        assertThat(result.nextStep().action()).isEqualTo(NextAction.START_PAS);
        assertThat(result.nextStep().headline()).isEqualTo("Next: submit the prior authorization request in PAS.");
        // And it is recorded, under the same run id the screen shows.
        assertThat(result.ledger().recorded()).isTrue();
        assertThat(ledger.only().runId()).isEqualTo(result.runId());
        assertThat(result.protocol()).isEqualTo(Protocol.CRD);
        assertThat(result.operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(result.chainId()).isEqualTo("chain-1");
        assertThat(result.profile()).isEqualTo("mock");
        assertThat(result.error()).isNull();
        assertThat(result.timing().slow()).isFalse();
    }

    @Test
    void theLedgerRecordCarriesEveryFieldTheSpecificationListsAsAlways() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX);
        service().run(request("P32H-DELEG").expected("AUTH_REQUIRED").testerRef("QA-17").note("routing check").build(), RunOrigin.manual());
        RunRecord record = ledger.only();

        assertThat(record.runId()).startsWith("run-");
        assertThat(record.timestamp()).isEqualTo("2026-03-10T11:00:00-04:00");
        assertThat(record.protocol()).isEqualTo(Protocol.CRD);
        assertThat(record.operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(record.target()).isEqualTo("onyx");
        assertThat(record.profile()).isEqualTo("mock");
        assertThat(record.environment()).isEqualTo("Onyx Sandbox");
        assertThat(record.environmentKind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(record.scenarioId()).isEqualTo("crd-10-delegated-vendor");
        assertThat(record.procedureCodes()).containsExactly("95810");
        assertThat(record.lineOfBusiness()).isEqualTo("Medicare");
        assertThat(record.outcome()).isEqualTo("AUTH_REQUIRED");
        assertThat(record.coverageCodes()).containsExactly("covered=covered", "pa-needed=auth-needed", "doc-needed=no-doc");
        assertThat(record.delegatedVendor()).isEqualTo("Evolent");
        assertThat(record.httpStatus()).isEqualTo(200);
        assertThat(record.elapsedMillis()).isGreaterThanOrEqualTo(0);
        assertThat(record.errorType()).isEqualTo("NONE");
        assertThat(record.expected()).isEqualTo("AUTH_REQUIRED");
        assertThat(record.result()).isEqualTo(TestResult.PASS);
        assertThat(record.note()).isEqualTo("routing check");
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.hook()).isEqualTo("order-sign");
        assertThat(record.memberId()).isEqualTo("P32H-DELEG");
        assertThat(record.npi()).isEqualTo("1234567893");
        assertThat(record.dateOfService()).isEqualTo("2026-03-20");
        assertThat(record.unrecordedInputs()).isEmpty();
    }

    // ---- the distinction this tool exists for, on screen and in the ledger ----

    @Test
    void noAuthAndAnEmptyResponseGiveTwoDifferentScreensAndTwoDifferentLedgerRows() {
        RunEnvelope<CrdDetail> noAuth = service().run(request("P32H-NO-AUTH").expected("NO_AUTH_REQUIRED").build(), RunOrigin.manual());
        RunEnvelope<CrdDetail> empty = service().run(request("P32H-EMPTY").expected("NO_AUTH_REQUIRED").build(), RunOrigin.manual());

        // Two screens.
        assertThat(noAuth.verdict().headline()).isEqualTo("Prior authorization is not required.");
        assertThat(empty.verdict().headline()).isEqualTo("The service answered but returned no determination.");
        assertThat(empty.verdict().supporting()).anySatisfy(line -> assertThat(line).contains("not the same as an answer that prior authorization is not required"));
        assertThat(noAuth.verdict().tone()).isEqualTo(Tone.CLEAR);
        assertThat(empty.verdict().tone()).isEqualTo(Tone.NO_ANSWER);

        DeterminationRow noAuthRow = noAuth.determination().stream().filter(r -> r.label().equals("Prior authorization")).findFirst().orElseThrow();
        DeterminationRow emptyRow = empty.determination().stream().filter(r -> r.label().equals("Prior authorization")).findFirst().orElseThrow();
        assertThat(noAuthRow.code()).isEqualTo("no-auth");
        assertThat(noAuthRow.presence()).isEqualTo(Presence.VALUE);
        assertThat(noAuthRow.display()).isEqualTo("No prior authorization is needed");
        // The empty response still gets the row, marked as not returned, so the grid is not simply blank.
        assertThat(emptyRow.code()).isNull();
        assertThat(emptyRow.presence()).isEqualTo(Presence.ABSENT);

        assertThat(noAuth.nextStep().action()).isEqualTo(NextAction.FLOW_ENDS);
        assertThat(empty.nextStep().action()).isEqualTo(NextAction.REVIEW);

        // The same expectation passes for one and fails for the other.
        assertThat(noAuth.expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(empty.expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(empty.expectation().explanation()).isEqualTo(
                "Expected \"Prior authorization is not required\" but the outcome was \"The service returned no determination\".");

        // Two ledger rows.
        RunRecord noAuthRecord = ledger.records().get(0);
        RunRecord emptyRecord = ledger.records().get(1);
        assertThat(noAuthRecord.outcome()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(emptyRecord.outcome()).isEqualTo("NO_DETERMINATION");
        assertThat(noAuthRecord.coverageCodes()).containsExactly("covered=covered", "pa-needed=no-auth", "doc-needed=no-doc");
        assertThat(emptyRecord.coverageCodes()).isEmpty();
        assertThat(noAuthRecord.result()).isEqualTo(TestResult.PASS);
        assertThat(emptyRecord.result()).isEqualTo(TestResult.FAIL);
        // Neither is a transport failure: the difference is in what the service said, not in whether it answered.
        assertThat(noAuthRecord.errorType()).isEqualTo("NONE");
        assertThat(emptyRecord.errorType()).isEqualTo("NONE");
        assertThat(noAuthRecord.httpStatus()).isEqualTo(200);
        assertThat(emptyRecord.httpStatus()).isEqualTo(200);
        assertThat(emptyRecord.findings()).contains("REQUIRED_ELEMENT_MISSING");
        assertThat(noAuthRecord.findings()).isEmpty();
    }

    @Test
    void theAuthorizationOutcomeAndTheTestResultAreReportedSeparately() {
        // The tester expects "not covered", and that is what comes back: a stop for the provider, a pass for the test.
        RunEnvelope<CrdDetail> result = service().run(request("P32H-NOCOV").expected("NOT_COVERED").build(), RunOrigin.manual());
        assertThat(result.verdict().tone()).isEqualTo(Tone.STOP);
        assertThat(result.verdict().headline()).isEqualTo("This service is not a covered benefit.");
        assertThat(result.expectation().result()).isEqualTo(TestResult.PASS);
        // And the reverse: a clean "not required" that the tester did not expect is a failed test.
        RunEnvelope<CrdDetail> surprise = service().run(request("P32H-NO-AUTH").expected("AUTH_REQUIRED").build(), RunOrigin.manual());
        assertThat(surprise.verdict().tone()).isEqualTo(Tone.CLEAR);
        assertThat(surprise.expectation().result()).isEqualTo(TestResult.FAIL);
        // With no expectation the run is unverified, never passed.
        assertThat(run("P32H-NO-AUTH").expectation().result()).isEqualTo(TestResult.UNVERIFIED);
    }

    @Test
    void aCodeAssertionIsCheckedAgainstTheStandardCodes() {
        assertThat(service().run(request("P32H-DOC-DTR").expected("pa-needed=auth-needed; doc-needed=clinical").build(), RunOrigin.manual())
                .expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(service().run(request("P32H-DELEG").expected("delegated-vendor=Evolent").build(), RunOrigin.manual())
                .expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(service().run(request("P32H-DELEG").expected("delegated-vendor=eviCore").build(), RunOrigin.manual())
                .expectation().result()).isEqualTo(TestResult.FAIL);
    }

    // ---- not found, faults ----

    @Test
    void notFoundIsPresentedAsAFindingAboutTestDataAndAFaultAsAFindingAboutTheEnvironment() {
        RunEnvelope<CrdDetail> notFound = run("P32H-NOMEM");
        assertThat(notFound.verdict().tone()).isEqualTo(Tone.DATA);
        assertThat(notFound.verdict().supporting()).contains("This is a finding about the test data, not about the environment.");
        assertThat(notFound.nextStep().action()).isEqualTo(NextAction.FIX_TEST_DATA);
        // No error panel: the service answered. Its issues are still shown in the detail.
        assertThat(notFound.error()).isNull();
        assertThat(notFound.detail().issues()).hasSize(1);

        RunEnvelope<CrdDetail> serverError = run("P32H-ERR500");
        assertThat(serverError.verdict().tone()).isEqualTo(Tone.FAULT);
        assertThat(serverError.verdict().headline()).isEqualTo("The service failed (HTTP 500).");
        assertThat(serverError.nextStep().action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(serverError.error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        assertThat(serverError.error().httpStatus()).isEqualTo(500);
        assertThat(serverError.error().issues()).hasSize(1);

        assertThat(ledger.records().get(0).outcome()).isEqualTo("MEMBER_NOT_FOUND");
        assertThat(ledger.records().get(1).outcome()).isEqualTo("SERVICE_ERROR");
        assertThat(ledger.records().get(1).errorType()).isEqualTo("SERVER_ERROR");
    }

    @Test
    void theFourKindsOfFaultAreDistinguishedInTheErrorPanel() {
        assertThat(run("P32H-BAD400").error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(run("P32H-ERR500").error().kind()).isEqualTo(ErrorKind.VENDOR_ERROR);
        RunEnvelope<CrdDetail> timeout = run("P32H-TIMEOUT");
        assertThat(timeout.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(timeout.error().httpStatus()).isNull();
        assertThat(timeout.verdict().headline()).isEqualTo("The service did not answer in time.");
        OnyxCrdClient unreachable = stub(TestExchanges.unavailable(EpaOperation.CRD_ORDER_SIGN));
        RunEnvelope<CrdDetail> down = service(unreachable, "mock", Map.of()).run(request("S123456789").build(), RunOrigin.manual());
        assertThat(down.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);
        OnyxCrdClient garbled = stub(TestExchanges.crd(200, "{\"cards\": ["));
        RunEnvelope<CrdDetail> malformed = service(garbled, "mock", Map.of()).run(request("S123456789").build(), RunOrigin.manual());
        assertThat(malformed.error().kind()).isEqualTo(ErrorKind.MALFORMED_RESPONSE);
        // The raw bytes are kept for the screen even though they could not be parsed.
        assertThat(malformed.wire().response().body()).isEqualTo("{\"cards\": [");
        assertThat(malformed.wire().response().parsed()).isFalse();
        assertThat(ledger.records()).extracting(RunRecord::errorType)
                .containsExactly("BAD_REQUEST", "SERVER_ERROR", "TIMEOUT", "UNAVAILABLE", "MALFORMED_RESPONSE");
    }

    // ---- validation: nothing sent, nothing recorded ----

    @Test
    void aFormThatIsNotValidIsRefusedBeforeAnythingIsSentOrRecorded() {
        AtomicInteger calls = new AtomicInteger();
        OnyxCrdClient counting = new OnyxCrdClient() {
            @Override
            public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
                calls.incrementAndGet();
                return TestExchanges.crd(200, "{\"cards\":[]}");
            }

            @Override
            public VendorExchange discover(PreparedCall call) {
                return TestExchanges.crd(200, "{}");
            }
        };
        CrdService service = service(counting, "mock", Map.of());

        assertInvalid(service, request(" ").build(), "member.memberId");
        assertInvalid(service, CrdRunRequest.builder().provider(ProviderDetail.builder().npi("1234567893").build())
                .procedureCodes(List.of(CodeInput.of("95810"))).build(), "member.memberId");
        assertInvalid(service, request("S123456789").provider(ProviderDetail.builder().npi("1234567890").build()).build(), "provider.npi");
        assertInvalid(service, request("S123456789").provider(null).build(), "provider.npi");
        assertInvalid(service, request("S123456789").procedureCodes(List.of()).build(), "procedureCodes");
        assertInvalid(service, request("S123456789").procedureCodes(List.of(CodeInput.of(" "))).build(), "procedureCodes[0].code");
        assertInvalid(service, request("S123456789").dateOfService("03/20/2026").build(), "dateOfService");
        assertInvalid(service, request("S123456789").dateOfService("2026-02-30").build(), "dateOfService");
        assertInvalid(service, request("S123456789").member(MemberDetail.builder().memberId("S123456789").dateOfBirth("2026-03-11").build()).build(),
                "member.dateOfBirth");
        assertInvalid(service, request("S123456789").member(MemberDetail.builder().memberId("S123456789").dateOfBirth("31-01-1970").build()).build(),
                "member.dateOfBirth");
        assertInvalid(service, request("S123456789").expected("APPROVED").build(), "expected");
        assertInvalid(service, request("S123456789").hook("order-dispatch").build(), "hook");
        assertInvalid(service, request("S123456789").rawBody("{not json").build(), "rawBody");

        assertThat(calls.get()).isZero();
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void everyProblemWithTheFormIsReportedAtOnce() {
        assertThatThrownBy(() -> service().run(CrdRunRequest.builder().dateOfService("tomorrow").build(), RunOrigin.manual()))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field)
                        .containsExactly("member.memberId", "provider.npi", "procedureCodes", "dateOfService"));
    }

    @Test
    void aScenarioCannotBeForcedOnLive() {
        assertInvalid(service(mockClient, "live", Map.of()), request("S123456789").scenarioId("crd-02-no-auth").build(), "scenarioId");
        // On mock the same request runs and answers from the chosen scenario, whatever the member.
        RunEnvelope<CrdDetail> forced = service().run(request("P32H-PA-REQ").scenarioId("crd-02-no-auth").build(), RunOrigin.manual());
        assertThat(forced.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(ledger.only().scenarioId()).isEqualTo("crd-02-no-auth");
    }

    private void assertInvalid(CrdService service, CrdRunRequest request, String field) {
        assertThatThrownBy(() -> service.run(request, RunOrigin.manual()))
                .as("expected a validation error on " + field)
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors()).extracting(FieldError::field).contains(field));
    }

    @Test
    void aTargetTheGatewayRefusesIsNotSentAndNotRecorded() {
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.CRD_ORDER_SIGN, "There is no url for CRD order-sign."));
        assertThatThrownBy(() -> run("P32H-PA-REQ")).isInstanceOf(EndpointNotConfiguredException.class);
        assertThat(ledger.records()).isEmpty();
    }

    // ---- the ledger ----

    @Test
    void aLedgerThatCannotWriteIsReportedOnTheResultAndTheResultIsStillReturned() {
        ledger.refusing("This run was NOT recorded: the ledger file runs-2026-03-10.jsonl could not be written.");
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.ledger().recorded()).isFalse();
        assertThat(result.ledger().message()).contains("NOT recorded");
    }

    @Test
    void aLedgerThatThrowsDoesNotCostTheTesterTheirResult() {
        ledger.throwing(new IllegalStateException("disk full for Placeholder-Surname"));
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.ledger().recorded()).isFalse();
        assertThat(result.ledger().message()).contains("NOT recorded").doesNotContain(SURNAME);
    }

    @Test
    void theRecordHandedToTheLedgerHoldsNoMemberDataAndNoWireJson() {
        service().run(request("P32H-DOC-DTR").build(), RunOrigin.manual());
        String line = JsonSafe.mapper().writeValueAsString(ledger.only());
        assertThat(line).doesNotContain(SURNAME).doesNotContain("Given").doesNotContain("1970-01-31").doesNotContain("female")
                .doesNotContain("SUB-PLACEHOLDER-1").doesNotContain("resourceType").doesNotContain("hookInstance")
                .doesNotContain("systemActions").doesNotContain("Clinical documentation is required");
    }

    @Test
    void aRunFromASuiteOrAReplayCarriesItsOrigin() {
        service().run(request("P32H-NO-AUTH").build(), new RunOrigin("suite-1", "case-7", "run-earlier"));
        assertThat(ledger.only().suiteRunId()).isEqualTo("suite-1");
        assertThat(ledger.only().caseId()).isEqualTo("case-7");
        assertThat(ledger.only().replayOfRunId()).isEqualTo("run-earlier");
        service().run(request("P32H-NO-AUTH").build(), null);
        assertThat(ledger.last().suiteRunId()).isNull();
    }

    @Test
    void aFailureOfThisToolAfterTheCallStillLeavesALedgerLine() {
        // The code is given its system, so the request is built without the code list. The code
        // list is then consulted only after the call, where this failure hits.
        codeList.failing(new IllegalStateException("code list exploded"));
        CrdRunRequest request = request("P32H-NO-AUTH")
                .procedureCodes(List.of(new CodeInput("95810", "http://www.ama-assn.org/go/cpt", null))).build();
        assertThatThrownBy(() -> service().run(request, RunOrigin.manual())).isInstanceOf(IllegalStateException.class);
        // The service was called, so the run happened and is recorded as a failure of the tool.
        assertThat(ledger.only().outcome()).isEqualTo("TOOL_ERROR");
        assertThat(ledger.only().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(ledger.only().httpStatus()).isEqualTo(200);
        assertThat(ledger.only().scenarioId()).isEqualTo("crd-02-no-auth");
    }

    @Test
    void aFailureBeforeTheCallLeavesNoLedgerLineBecauseNoRunHappened() {
        // Here the code list fails while the request is being built, so nothing was sent.
        codeList.failing(new IllegalStateException("code list exploded"));
        assertThatThrownBy(() -> run("P32H-NO-AUTH")).isInstanceOf(IllegalStateException.class);
        assertThat(ledger.records()).isEmpty();
    }

    // ---- what was sent ----

    @Test
    void aSpacedMemberIdIsSentWithoutSpacesAndBothFormsAreShown() {
        RunEnvelope<CrdDetail> result = service().run(request("S12345678   01").build(), RunOrigin.manual());
        assertThat(result.wire().memberId().typed()).isEqualTo("S12345678   01");
        assertThat(result.wire().memberId().sent()).isEqualTo("S1234567801");
        assertThat(result.wire().memberId().changed()).isTrue();
        JsonNode sent = JsonSafe.parse(result.wire().request().body()).node();
        assertThat(sent.at("/prefetch/patient/identifier/0/value").asString()).isEqualTo("S1234567801");
        assertThat(result.wire().request().body()).doesNotContain("S12345678   01");
        assertThat(ledger.only().memberId()).isEqualTo("S1234567801");
        // An unspaced id goes through the same path unchanged.
        RunEnvelope<CrdDetail> unspaced = service().run(request("S123456789").build(), RunOrigin.manual());
        assertThat(unspaced.wire().memberId().changed()).isFalse();
        assertThat(unspaced.wire().memberId().sent()).isEqualTo("S123456789");
    }

    @Test
    void onMockTheWireViewSaysNothingWasSentAndWhichScenarioAnswered() {
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.wire().request().note()).isEqualTo("Mock profile. Answered from scenario crd-02-no-auth, selected by the sentinel "
                + "member ID P32H-NO-AUTH. Nothing left this machine.");
        assertThat(result.wire().request().method()).isEqualTo("POST");
        assertThat(result.wire().response().status()).isEqualTo(200);
        assertThat(result.wire().response().body()).contains("\"no-auth\"");
        // An ordinary member gets the default scenario, and the note says it is the default.
        assertThat(run("S123456789").wire().request().note()).contains("crd-01-pa-required").contains("the default");
    }

    @Test
    void orderSelectIsSentAsItsOwnOperationWithItsSelections() {
        RunEnvelope<CrdDetail> result = service().run(request("P32H-NO-AUTH").hook("order-select").build(), RunOrigin.manual());
        assertThat(result.operation()).isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        assertThat(gateway.lastIntent().operation()).isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        JsonNode sent = JsonSafe.parse(result.wire().request().body()).node();
        assertThat(sent.get("hook").asString()).isEqualTo("order-select");
        assertThat(sent.at("/context/selections/0").asString()).isEqualTo("ServiceRequest/service-request-1");
        assertThat(ledger.only().hook()).isEqualTo("order-select");
    }

    @Test
    void theTestersOwnBodyIsSentAsWrittenAndTheLedgerSaysTheBodyWasNotKept() {
        String body = "{\"hook\":\"order-sign\",\"context\":{\"patientId\":\"P32H-NO-AUTH\"},\"vendorSampleField\":true}";
        RunEnvelope<CrdDetail> result = service().run(CrdRunRequest.builder().rawBody(body).build(), RunOrigin.manual());

        assertThat(result.wire().request().body()).isEqualTo(body);
        // The form held no member, so the sentinel typed into the body selected the scenario.
        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.wire().request().note()).contains("found in the request body");
        assertThat(result.detail().bodyBuiltByTool()).isFalse();
        assertThat(result.detail().requestNotes()).isEmpty();
        assertThat(result.wire().memberId()).isNull();
        RunRecord record = ledger.only();
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY);
        assertThat(record.memberId()).isNull();
        assertThat(record.procedureCodes()).isEmpty();
    }

    @Test
    void aBodyThatStillHoldsAPlaceholderIsRefusedAndNotRecorded() {
        assertThatThrownBy(() -> service().run(CrdRunRequest.builder().rawBody("{\"hook\":\"order-sign\",\"fhirServer\":\"{{fhirServer}}\"}").build(),
                RunOrigin.manual())).isInstanceOfSatisfying(UnresolvedVariablesException.class,
                e -> assertThat(e.variables()).containsExactly("fhirServer"));
        assertThat(ledger.records()).isEmpty();
        // Typed for this run, it resolves, and the ledger notes that a run value was used.
        service().run(CrdRunRequest.builder().rawBody("{\"hook\":\"order-sign\",\"fhirServer\":\"{{fhirServer}}\"}")
                .variables(Map.of("fhirServer", "https://ehr.example.invalid/fhir")).build(), RunOrigin.manual());
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY, RunRecord.INPUT_RUN_VALUES);
    }

    @Test
    void theLedgerNamesEveryInputItCouldNotKeep() {
        service().run(request("P32H-NO-AUTH").memberOverrides(List.of("lastName")).providerOverrides(List.of("firstName"))
                .variables(Map.of("fhirServer", "https://ehr.example.invalid/fhir")).request(new RequestRef("col-1", "req-2")).build(),
                RunOrigin.manual());
        assertThat(ledger.only().unrecordedInputs()).containsExactly(RunRecord.INPUT_MEMBER_DETAILS, RunRecord.INPUT_PROVIDER_DETAILS,
                RunRecord.INPUT_RUN_VALUES, RunRecord.INPUT_CHOSEN_REQUEST);
        assertThat(gateway.lastIntent().request()).isEqualTo(new RequestRef("col-1", "req-2"));
        assertThat(gateway.lastIntent().runValues()).containsEntry("fhirServer", "https://ehr.example.invalid/fhir");
    }

    @Test
    void theResolvedUrlAndTheCollectionRequestAreShownAndRecorded() {
        gateway.environment("Onyx Sandbox", EnvironmentKind.SANDBOX).variable("baseUrl", "https://onyx.example.invalid")
                .variable("crdOrderSignServiceId", "placeholder-service")
                .url(EpaOperation.CRD_ORDER_SIGN, "{{baseUrl}}/cds-services/{{crdOrderSignServiceId}}")
                .fromRequest("order-sign Happy Path", false);
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        assertThat(result.wire().request().url()).isEqualTo("https://onyx.example.invalid/cds-services/placeholder-service");
        assertThat(result.wire().request().urlTemplate()).isEqualTo("{{baseUrl}}/cds-services/{{crdOrderSignServiceId}}");
        assertThat(result.environment().name()).isEqualTo("Onyx Sandbox");
        assertThat(ledger.only().targetSource()).isEqualTo("order-sign Happy Path");
    }

    // ---- findings ----

    @Test
    void aRequestWithAPreRequestScriptIsFlaggedOnTheResultAndInTheLedger() {
        gateway.url(EpaOperation.CRD_ORDER_SIGN, "https://onyx.example.invalid/cds-services/x").fromRequest("order-sign Happy Path", true);
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        assertThat(result.findings()).extracting(Finding::kind).containsExactly(FindingKind.PRE_REQUEST_SCRIPT_NOT_RUN);
        assertThat(result.findings().get(0).subject()).isEqualTo("order-sign Happy Path");
        assertThat(ledger.only().findings()).containsExactly("PRE_REQUEST_SCRIPT_NOT_RUN");
    }

    @Test
    void aCallThatCompletesButIsSlowIsFlaggedAndATimeoutIsNot() {
        VendorExchange slow = new VendorExchange(TestExchanges.crd(200, "{\"cards\":[]}").request(), 200, List.of(), "{\"cards\":[]}",
                JsonSafe.parse("{\"cards\":[]}").node(), null, 16000L, org.p32h.interop.epa.pave.model.OnyxFailureType.NONE, null,
                java.util.Set.of(), null);
        RunEnvelope<CrdDetail> result = service(stub(slow), "mock", Map.of()).run(request("S123456789").build(), RunOrigin.manual());
        assertThat(result.timing().elapsedMillis()).isEqualTo(16000L);
        assertThat(result.timing().thresholdMillis()).isEqualTo(15000L);
        assertThat(result.timing().slow()).isTrue();
        assertThat(result.findings()).extracting(Finding::kind).contains(FindingKind.SLOW_RESPONSE);
        assertThat(ledger.only().slow()).isTrue();

        RunEnvelope<CrdDetail> configured = service(stub(slow), "mock", Map.of("slow-threshold-ms", "20000")).run(request("S123456789").build(), RunOrigin.manual());
        assertThat(configured.timing().slow()).isFalse();

        RunEnvelope<CrdDetail> timedOut = service(stub(TestExchanges.timeout(EpaOperation.CRD_ORDER_SIGN)), "mock", Map.of())
                .run(request("S123456789").build(), RunOrigin.manual());
        assertThat(timedOut.timing().slow()).isFalse();
        assertThat(timedOut.findings()).extracting(Finding::kind).doesNotContain(FindingKind.SLOW_RESPONSE);
    }

    @Test
    void aDisagreementWithTheCodeListIsAFindingThatStatesBothSides() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", "eviCore");
        RunEnvelope<CrdDetail> result = run("P32H-NO-AUTH");
        Finding finding = result.findings().stream().filter(f -> f.kind() == FindingKind.CODE_LIST_DISAGREES_ON_AUTH).findFirst().orElseThrow();
        assertThat(finding.subject()).isEqualTo("95810");
        assertThat(finding.codeListSays()).isEqualTo("The code list says prior authorization is required for Medicare.");
        assertThat(finding.serviceSays()).isEqualTo("The service answered pa-needed=no-auth.");
        assertThat(finding.detail()).isEqualTo("One of them is wrong, and this tool cannot tell which.");
        // A finding is not a failure of the call, and it does not change the verdict or the test result.
        assertThat(result.verdict().outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.error()).isNull();
        assertThat(ledger.only().findings()).contains("CODE_LIST_DISAGREES_ON_AUTH");
    }

    // ---- preview and discovery ----

    @Test
    void aPreviewShowsTheRequestWithoutSendingOrRecordingIt() {
        CrdPreview preview = service().preview(request("S12345678   01").build());
        assertThat(preview.operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(preview.method()).isEqualTo("POST");
        assertThat(preview.url().configured()).isFalse();
        assertThat(preview.memberId().sent()).isEqualTo("S1234567801");
        assertThat(JsonSafe.parse(preview.body()).node().get("hook").asString()).isEqualTo("order-sign");
        assertThat(preview.notes()).isNotEmpty();
        assertThat(ledger.records()).isEmpty();
        assertThatThrownBy(() -> service().preview(request(" ").build())).isInstanceOf(ValidationException.class);
    }

    @Test
    void discoveryListsTheServicesAndIsNotRecordedAsARun() {
        CrdDiscovery discovery = service().discover();
        assertThat(discovery.services()).hasSize(2);
        assertThat(discovery.services()).extracting(s -> s.hook().value()).containsExactly("order-sign", "order-select");
        assertThat(discovery.services().get(0).id().value()).startsWith("placeholder-");
        assertThat(discovery.error()).isNull();
        assertThat(discovery.wire().request().sent()).isFalse();
        assertThat(gateway.lastIntent().operation()).isEqualTo(EpaOperation.CRD_DISCOVERY);
        assertThat(ledger.records()).isEmpty();

        String real = "{\"services\":[{\"hook\":\"order-sign\",\"id\":\"crd-order-sign\",\"title\":\"T\",\"description\":\"D\","
                + "\"prefetch\":{\"patient\":\"Patient/{{context.patientId}}\"},\"usageRequirements\":\"none\"}],\"vendorField\":1}";
        CrdDiscovery fromService = service(stub(TestExchanges.crd(200, real)), "mock", Map.of()).discover();
        assertThat(fromService.services().get(0).prefetch()).singleElement().satisfies(p -> {
            assertThat(p.key()).isEqualTo("patient");
            assertThat(p.template().value()).isEqualTo("Patient/{{context.patientId}}");
        });
        assertThat(fromService.additionalFields()).extracting(f -> f.path()).containsExactly("services[0].usageRequirements", "vendorField");
    }

    private static OnyxCrdClient stub(VendorExchange exchange) {
        return new OnyxCrdClient() {
            @Override
            public VendorExchange hook(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange discover(PreparedCall call) {
                return exchange;
            }
        };
    }
}
