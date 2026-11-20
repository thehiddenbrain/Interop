package org.p32h.interop.epa.pave.service.raw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.RawRequestClient;
import org.p32h.interop.epa.pave.client.raw.MockRawRequestClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.raw.RawDetail;
import org.p32h.interop.epa.pave.model.raw.RawOutcome;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.MemberInText;
import org.p32h.interop.epa.pave.service.run.MembersInBody;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.RawController;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Sending a collection request as written, through the service and the controller, with fakes around them. */
class RawServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final RequestRef REF = new RequestRef("col-1", "req-9");

    private final FakeGateway gateway = FakeGateway.unconfigured().fromRequest("eligibility check (vendor sample)", false);
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final RawRequestClient mockClient = new MockRawRequestClient(new ScenarioRepository(APP), new MockExchanges(APP));

    private RawService service(RawRequestClient client) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        return new RawService(epa, gateway, client, new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK));
    }

    private static RawRequestClient stub(VendorExchange exchange) {
        return (call, body) -> exchange;
    }

    private RunEnvelope<RawDetail> run(RawRequestClient client, String body, String expected) {
        return service(client).run(RawRunRequest.of(REF, body, Map.of(), expected), RunOrigin.manual(), null);
    }

    @Test
    void anUnmappedRequestIsSentAsWrittenAndEveryFieldOfTheAnswerIsListed() {
        RunEnvelope<RawDetail> result = run(mockClient, "{\"memberId\":\"S123456789\"}", null);

        assertThat(result.protocol()).isEqualTo(Protocol.RAW);
        assertThat(result.operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(result.verdict().outcomeCode()).isEqualTo("ANSWERED");
        // On mock no service was asked, so the headline says who answered.
        assertThat(result.verdict().headline()).isEqualTo("A mock scenario, standing in for the service, answered (HTTP 200).");
        assertThat(result.verdict().tone()).isEqualTo(Tone.NEUTRAL);
        // The mock says which canned answer this was, and the tool says it does not interpret it.
        assertThat(result.verdict().supporting().get(0)).startsWith("Mock scenario raw-01-not-modelled answered");
        assertThat(result.verdict().supporting()).anySatisfy(line -> assertThat(line).contains("does not interpret the answer"));
        // Nothing left this machine, and the result does not say otherwise.
        assertThat(result.verdict().supporting()).noneSatisfy(line -> assertThat(line).contains("was sent as written"));
        // Nothing is read into a designed view, so the whole body is in the returned fields.
        assertThat(result.additionalFields()).extracting(AdditionalField::path).containsExactly("mock", "note");
        assertThat(result.detail().responseKind()).isEqualTo(RawDetail.KIND_JSON);
        assertThat(result.detail().requestName()).isEqualTo("eligibility check (vendor sample)");
        assertThat(result.wire().request().body()).isEqualTo("{\"memberId\":\"S123456789\"}");
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(result.expectation().result()).isEqualTo(TestResult.UNVERIFIED);
        assertThat(result.nextStep().action()).isEqualTo(NextAction.REVIEW);
        assertThat(gateway.lastIntent().operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(gateway.lastIntent().request()).isEqualTo(REF);

        RunRecord record = ledger.only();
        assertThat(record.runId()).isEqualTo(result.runId());
        assertThat(record.protocol()).isEqualTo(Protocol.RAW);
        assertThat(record.operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(record.outcome()).isEqualTo("ANSWERED");
        assertThat(record.targetSource()).isEqualTo("eligibility check (vendor sample)");
        assertThat(record.nextAction()).isEqualTo("REVIEW");
        // The body and the choice of request are not on the line, so the line says a replay cannot rebuild them.
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_BODY, RunRecord.INPUT_CHOSEN_REQUEST);
        assertThat(record.memberId()).isNull();
    }

    @Test
    void theOutcomeSaysOnlyWhatCameBack() {
        assertThat(RawService.outcome(TestExchanges.of(EpaOperation.UNMAPPED, 200, "{\"a\":1}", false))).isEqualTo(RawOutcome.ANSWERED);
        // For a request the tool does not know, text that is not JSON and no body at all are answers, not faults.
        assertThat(RawService.outcome(TestExchanges.of(EpaOperation.UNMAPPED, 200, "plain text", false))).isEqualTo(RawOutcome.ANSWERED_NOT_JSON);
        assertThat(RawService.outcome(TestExchanges.of(EpaOperation.UNMAPPED, 204, null, false))).isEqualTo(RawOutcome.ANSWERED_NO_BODY);
        assertThat(RawService.outcome(TestExchanges.of(EpaOperation.UNMAPPED, 404, "{}", false))).isEqualTo(RawOutcome.REJECTED);
        assertThat(RawService.outcome(TestExchanges.of(EpaOperation.UNMAPPED, 503, null, false))).isEqualTo(RawOutcome.SERVICE_ERROR);
        assertThat(RawService.outcome(TestExchanges.timeout(EpaOperation.UNMAPPED))).isEqualTo(RawOutcome.TIMEOUT);
        assertThat(RawService.outcome(TestExchanges.unavailable(EpaOperation.UNMAPPED))).isEqualTo(RawOutcome.UNREACHABLE);
        for (RawOutcome outcome : RawOutcome.values()) {
            assertThat(outcome.label()).as(outcome.name()).isNotBlank();
        }
    }

    @Test
    void aBodyThatIsNotJsonIsShownAsReceivedAndAnErrorIsReadIssueByIssue() {
        RunEnvelope<RawDetail> text = run(stub(TestExchanges.of(EpaOperation.UNMAPPED, 200, "<html>hello</html>", false)), null, null);
        assertThat(text.verdict().outcomeCode()).isEqualTo("ANSWERED_NOT_JSON");
        assertThat(text.error()).isNull();
        assertThat(text.detail().responseKind()).isEqualTo(RawDetail.KIND_NOT_JSON);
        assertThat(text.additionalFields()).extracting(AdditionalField::value).containsExactly("<html>hello</html>");

        String outcome = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\",\"diagnostics\":\"bad\"}],\"extra\":1}";
        RunEnvelope<RawDetail> rejected = run(stub(TestExchanges.of(EpaOperation.UNMAPPED, 400, outcome, false)), "{}", null);
        assertThat(rejected.verdict().outcomeCode()).isEqualTo("REJECTED");
        assertThat(rejected.error().kind()).isEqualTo(ErrorKind.VENDOR_REJECTED);
        assertThat(rejected.detail().issues()).singleElement().satisfies(issue -> assertThat(issue.code().value()).isEqualTo("invalid"));
        assertThat(rejected.additionalFields()).extracting(AdditionalField::path).containsExactly("extra");
        assertThat(rejected.nextStep().action()).isEqualTo(NextAction.FIX_REQUEST);

        RunEnvelope<RawDetail> down = run(stub(TestExchanges.unavailable(EpaOperation.UNMAPPED)), null, null);
        assertThat(down.error().kind()).isEqualTo(ErrorKind.UNREACHABLE);
        assertThat(down.detail().responseKind()).isEqualTo(RawDetail.KIND_NO_RESPONSE);
        assertThat(down.nextStep().action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
    }

    @Test
    void aTimeoutAScenarioPlaysSaysItWasPlayedAfterHowLongAndWhatLiveWouldWait() {
        VendorExchange played = TestExchanges.timeout(EpaOperation.UNMAPPED).withElapsedMillis(400).withScenario("crd-13-timeout", "chosen");
        RunEnvelope<RawDetail> result = run(stub(played), null, null);
        assertThat(result.error().kind()).isEqualTo(ErrorKind.TIMEOUT);
        assertThat(result.error().title()).isEqualTo("A mock scenario played a timeout. Nothing was sent and no environment failed: the "
                + "response time shown, 400 ms, is the scenario's own delay.");
        assertThat(result.error().action()).isEqualTo("Had this been a real service, this tool would have waited up to 15000 ms for an answer "
                + "(onyx.read-timeout-ms) before giving up. The next step says what a tester would do then.");
        // Without a scenario, the timeout is a real one and is said as such.
        assertThat(run(stub(TestExchanges.timeout(EpaOperation.UNMAPPED)), null, null).error().title())
                .isEqualTo("No response arrived within the read timeout.");
    }

    @Test
    void aTesterCanDeclareTheOutcomeOrTheHttpStatusTheyExpect() {
        assertThat(run(mockClient, null, "ANSWERED").expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(run(mockClient, null, "http-status=200").expectation().result()).isEqualTo(TestResult.PASS);
        assertThat(run(mockClient, null, "http-status=201").expectation().result()).isEqualTo(TestResult.FAIL);
        assertThat(run(stub(TestExchanges.of(EpaOperation.UNMAPPED, 404, "{}", false)), null, "REJECTED; http-status=404").expectation().result())
                .isEqualTo(TestResult.PASS);
        // With no response there is no status to compare, so a status expectation fails and never passes.
        assertThat(run(stub(TestExchanges.timeout(EpaOperation.UNMAPPED)), null, "http-status=200").expectation().result())
                .isEqualTo(TestResult.FAIL);
        assertThatThrownBy(() -> run(mockClient, null, "pa-needed=no-auth")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> run(mockClient, null, "APPROVED")).isInstanceOf(ValidationException.class);
    }

    @Test
    void placeholdersInTheBodyAreResolvedAndOneWithNoValueIsRefusedBeforeSending() {
        RunEnvelope<RawDetail> result = service(mockClient).run(RawRunRequest.of(REF, "{\"id\":\"{{caseId}}\"}", Map.of("caseId", "C-1"), null),
                RunOrigin.manual(), null);
        assertThat(result.wire().request().body()).isEqualTo("{\"id\":\"C-1\"}");
        assertThat(ledger.only().unrecordedInputs()).contains(RunRecord.INPUT_RUN_VALUES);
        assertThatThrownBy(() -> run(mockClient, "{\"id\":\"{{caseId}}\"}", null)).isInstanceOf(UnresolvedVariablesException.class);
        assertThat(ledger.records()).hasSize(1);
    }

    @Test
    void theRequestToSendIsRequiredAndNothingIsSentWithoutIt() {
        assertThatThrownBy(() -> service(mockClient).run(RawRunRequest.of(null, "{}", Map.of(), null), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).field()).isEqualTo("request"));
        assertThatThrownBy(() -> service(mockClient).run(RawRunRequest.of(new RequestRef("col-1", " "), "{}", Map.of(), null),
                RunOrigin.manual(), null)).isInstanceOf(ValidationException.class);
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void aRawWriteToProductionIsRefusedRecordedAndReturnedLikeAnyOtherWrite() {
        // The gateway judges an unmapped request by its method and by what its url looks like.
        gateway.failing(new ProdWriteBlockedException(EpaOperation.UNMAPPED, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "This request could change something on the production environment Onyx Production, so it is blocked.",
                "Unlock writes on the Config tab, or switch environment.",
                new ResolvedUrl("{{baseUrl}}/Claim/$submit", "https://onyx.example.invalid/Claim/$submit",
                        "https://onyx.example.invalid/Claim/$submit", List.of(), List.of(), "submit Happy Path"), "POST", "submit Happy Path"));
        RunEnvelope<RawDetail> result = run((call, body) -> {
            throw new AssertionError("a blocked request must not reach the client");
        }, "{}", null);
        assertThat(RunSupport.isBlocked(result)).isTrue();
        assertThat(result.wire().request().sent()).isFalse();
        assertThat(ledger.only().outcome()).isEqualTo("BLOCKED");
        assertThat(ledger.only().targetSource()).isEqualTo("submit Happy Path");
        // The verdict gives the refusal; the next step says what unlocking or switching achieves for a
        // request mapped to nothing, and never repeats the refusal or names a protocol tab.
        assertThat(result.verdict().headline()).contains("so it is blocked");
        assertThat(result.nextStep().action()).isEqualTo(NextAction.UNLOCK_OR_SWITCH);
        assertThat(result.nextStep().reason()).isEqualTo("With production writes unlocked in this browser session, where the installation "
                + "allows them, the request, exactly as written, goes to the production environment; with an environment that is not "
                + "production active, it goes there instead.");
        assertThat(result.nextStep().reason()).doesNotContain("so it is blocked").doesNotContain("PAS tab");

        RequestPreview preview = service(mockClient).preview(RawRunRequest.of(REF, "{}", Map.of(), null), null);
        assertThat(preview.blocked()).contains("blocked");
        assertThat(preview.url().template()).isEqualTo("{{baseUrl}}/Claim/$submit");
    }

    @Test
    void aRefusedWriteWhoseNoteHoldsAnIdentifierOfTheTypedBodyIsRefusedAndNotRecorded() {
        gateway.failing(new ProdWriteBlockedException(EpaOperation.UNMAPPED, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Blocked on production.", "Unlock writes on the Config tab, or switch environment."));
        String body = "{\"resourceType\": \"Coverage\", \"identifier\": [{\"value\": \"S123456789\"}]}";
        RawRunRequest request = new RawRunRequest(REF, body, Map.of(), Map.of(), null, null, "for s123456789", null, null, Map.of());

        assertThatThrownBy(() -> service(mockClient).run(request, RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, refused -> assertThat(refused.fieldErrors())
                        .extracting(FieldError::field).containsExactly("note"));
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void theIdentifiersOfABodyAreReadWhereverItsPatientsAndCoveragesSit() {
        String body = "{\"context\": {\"patientId\": \"pat-1\"}, \"prefetch\": {\"coverage\": {\"resourceType\": \"Bundle\", \"entry\": ["
                + "{\"resource\": {\"resourceType\": \"Coverage\", \"subscriberId\": \"S111222333\","
                + " \"contained\": [{\"resourceType\": \"Patient\", \"identifier\": [{\"value\": \"S444555666\"}]}]}}]}},"
                + " \"other\": {\"resourceType\": \"Practitioner\", \"identifier\": [{\"value\": \"1234567893\"}]}}";
        assertThat(MembersInBody.read(body)).extracting(MemberDetail::memberId).containsExactly("S444555666", null);
        assertThat(MembersInBody.read(body)).extracting(MemberDetail::subscriberId).containsExactly(null, "S111222333");
        assertThat(MembersInBody.read("member S123456789")).isEmpty();
        assertThat(MembersInBody.read(null)).isEmpty();
        // A practitioner's identifier is not a member's, so a note naming it is not refused.
        assertThat(MemberInText.problemsInBody("NPI 1234567893", null, null, MembersInBody.read(body), "Nothing was sent.")).isEmpty();
    }

    @Test
    void aPreviewShowsTheTargetAndTheResolvedBodyAndRecordsNothing() {
        gateway.url(EpaOperation.UNMAPPED, "https://another-service.example.invalid/api/thing");
        RequestPreview preview = service(mockClient).preview(RawRunRequest.of(REF, "{\"id\":\"{{caseId}}\"}", Map.of("caseId", "C-1"), null), null);
        assertThat(preview.url().display()).isEqualTo("https://another-service.example.invalid/api/thing");
        assertThat(preview.body()).isEqualTo("{\"id\":\"C-1\"}");
        assertThat(preview.requestName()).isEqualTo("eligibility check (vendor sample)");
        RequestPreview noBody = service(mockClient).preview(RawRunRequest.of(REF, " ", Map.of(), null), null);
        assertThat(noBody.body()).isNull();
        assertThat(noBody.notes()).containsExactly("There is no body: none was supplied for this run, and the collection request has none saved.");
        assertThat(noBody.bodySource()).isEqualTo(RawDetail.BODY_NONE);
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void theEndpointAnswersLikeEveryOtherRunEndpoint() throws Exception {
        MockMvc mvc = TestMvc.standalone(new RawController(service(mockClient)));
        mvc.perform(post("/api/raw").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":{\"collectionId\":\"col-1\",\"requestId\":\"req-9\"},\"rawBody\":\"{}\",\"expected\":\"ANSWERED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol").value("RAW"))
                .andExpect(jsonPath("$.verdict.outcomeCode").value("ANSWERED"))
                .andExpect(jsonPath("$.expectation.result").value("pass"))
                .andExpect(jsonPath("$.ledger.recorded").value(true));
        mvc.perform(post("/api/raw").contentType(MediaType.APPLICATION_JSON).content("{\"rawBody\":\"{}\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("request"));
        // A property this endpoint does not have is refused by name.
        mvc.perform(post("/api/raw").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":{\"collectionId\":\"col-1\",\"requestId\":\"req-9\"},\"body\":\"{}\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("body"));
        mvc.perform(post("/api/raw/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":{\"collectionId\":\"col-1\",\"requestId\":\"req-9\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation").value("UNMAPPED"));
        gateway.failing(new ProdWriteBlockedException(EpaOperation.UNMAPPED, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Blocked on production.", "Unlock writes on the Config tab, or switch environment."));
        mvc.perform(post("/api/raw").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"request\":{\"collectionId\":\"col-1\",\"requestId\":\"req-9\"}}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.verdict.outcomeCode").value("BLOCKED"));
    }
}
