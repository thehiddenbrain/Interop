package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.pas.MockSubscriptionClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.pas.NotificationJson;
import org.p32h.interop.epa.pave.service.pas.NotificationReceiver;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.pas.SubscriptionService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The PAS endpoints as a browser, and as the service that calls the receiver, see them: the status
 * of each kind of result and the shape of its body. The real services, mock clients, scenario
 * files and mappers are behind the controller, with a fake gateway and an in-memory ledger.
 */
class PasControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final OnyxProperties ONYX = OnyxProperties.of(Map.of("subscription.callback-url", "https://pave.example.invalid/api/pas/notifications"));
    private static final String ID = "subscription-placeholder-0001";
    private static final String SUBMISSION = "{\"member\":{\"memberId\":\"%s\",\"firstName\":\"Given\",\"lastName\":\"Placeholder-Surname\","
            + "\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}],"
            + "\"dateOfService\":\"2026-03-20\"%s}";
    private static final String INQUIRY = "{\"member\":{\"memberId\":\"%s\"},\"provider\":{\"npi\":\"1234567893\"}%s}";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private ObjectProvider<RunLedger> ledgerProvider() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return beans.getBeanProvider(RunLedger.class);
    }

    private MockMvc mvc(String mode) {
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        return mvc(epa, new RunSupport(epa, TrackingProperties.defaults(), ONYX, ledgerProvider(), CLOCK));
    }

    private MockMvc mvc(EpaProperties epa, RunSupport support) {
        PasService pas = new PasService(ONYX, gateway, new MockOnyxPasClient(scenarios, new MockExchanges(APP)), new FakeCodeList(), support,
                ledgerProvider());
        SubscriptionService subscriptions = new SubscriptionService(ONYX, gateway, new MockSubscriptionClient(scenarios, new MockExchanges(APP)), support);
        NotificationReceiver receiver = new NotificationReceiver(gateway, support, ledgerProvider(), scenarios);
        // The controller itself, as the application registers it, so its own annotations are what is tested.
        return TestMvc.standalone(new PasController(pas, subscriptions, receiver, scenarios, epa));
    }

    /** A controller whose services fail while reading an answer, after the client has been called. */
    private MockMvc failingAfterTheCall() {
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        return mvc(epa, new RunSupport(epa, TrackingProperties.defaults(), ONYX, ledgerProvider(), CLOCK) {
            @Override
            public Finding slowFinding(Timing timing) {
                throw new IllegalStateException("the reader exploded for Placeholder-Surname");
            }
        });
    }

    private static String submission(String memberId, String extra) {
        return SUBMISSION.formatted(memberId, extra);
    }

    private static String inquiry(String memberId, String extra) {
        return INQUIRY.formatted(memberId, extra);
    }

    private static JsonNode send(MockMvc mvc, MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String response = mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return JsonSafe.parse(response).node();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static ProdWriteBlockedException blocked(EpaOperation operation) {
        return new ProdWriteBlockedException(operation, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment.",
                new ResolvedUrl("{{baseUrl}}/x", "https://onyx.example.invalid/x", "https://onyx.example.invalid/x", List.of(), List.of(), "sample"),
                operation.defaultMethod(), "sample");
    }

    // ---- submit ----

    @Test
    void aSubmissionAnswers200WithTheFourAnswersAndTheLedgerReceipt() throws Exception {
        JsonNode result = send(mvc("mock"), json(post("/api/pas/submit"), submission("P32H-PAS-APPROVED", ",\"expected\":\"APPROVED\"")), 200);

        assertThat(result.get("protocol").asString()).isEqualTo("PAS");
        assertThat(result.get("operation").asString()).isEqualTo("PAS_SUBMIT");
        assertThat(result.get("profile").asString()).isEqualTo("mock");
        // What happened, as an outcome apart from the test result.
        assertThat(result.at("/verdict/headline").asString()).isEqualTo("The prior authorization is approved.");
        assertThat(result.at("/verdict/outcomeCode").asString()).isEqualTo("APPROVED");
        assertThat(result.at("/verdict/tone").asString()).isEqualTo("CLEAR");
        // What was expected, and whether it matched.
        assertThat(result.at("/expectation/expected").asString()).isEqualTo("APPROVED");
        assertThat(result.at("/expectation/result").asString()).isEqualTo("pass");
        // What to do next.
        assertThat(result.at("/nextStep/action").asString()).isEqualTo("FLOW_ENDS");
        assertThat(result.at("/nextStep/carry/claimId/0").asString()).isEqualTo(result.at("/detail/claimId").asString()).startsWith("claim-");
        // And that it is recorded, under the run id on screen.
        assertThat(result.at("/ledger/recorded").asBoolean()).isTrue();
        assertThat(result.at("/ledger/record/runId").asString()).isEqualTo(result.get("runId").asString());
        assertThat(ledger.only().runId()).isEqualTo(result.get("runId").asString());
        // Tier 1, the grid and the wire view are there to read.
        assertThat(result.at("/detail/responseKind").asString()).isEqualTo("BUNDLE");
        assertThat(result.at("/detail/claimResponses/0/decision").asString()).isEqualTo("APPROVED");
        // The authorization number travels where PAS 2.0.1 puts it: on the decision of the item, not in preAuthRef.
        assertThat(result.at("/detail/claimResponses/0/decisions/0/authorizationNumbers/0").asString()).isEqualTo("PA-PLACEHOLDER-0001");
        assertThat(result.at("/detail/claimResponses/0/items/0/adjudications/0/reviewActions/0/code/code").asString()).isEqualTo("A1");
        assertThat(result.at("/determination/0/code").asString()).isEqualTo("A1");
        assertThat(result.at("/wire/request/sent").asBoolean()).isFalse();
        assertThat(result.at("/wire/request/body").asString()).contains("\"Claim\"").contains("Placeholder-Surname");
        assertThat(result.at("/wire/response/status").asInt()).isEqualTo(200);
        // A null is left out, never sent as null.
        assertThat(result.has("error")).isFalse();
        assertThat(result.has("chainId")).isFalse();
        assertThat(result.at("/detail").has("attachmentMode")).isFalse();
    }

    @Test
    void aDenialTheTesterExpectedIsShownAsAnOutcomeOfDeniedBesideATestResultOfPassed() throws Exception {
        JsonNode result = send(mvc("mock"), json(post("/api/pas/submit"), submission("P32H-PAS-DENIED", ",\"expected\":\"DENIED\"")), 200);
        // Two facts, one result, and neither stands in for the other.
        assertThat(result.at("/verdict/outcomeLabel").asString()).isEqualTo("The authorization is denied");
        assertThat(result.at("/verdict/tone").asString()).isEqualTo("STOP");
        assertThat(result.at("/expectation/result").asString()).isEqualTo("pass");
        // And a response that decided nothing never passes for an approval.
        JsonNode noDecision = send(mvc("mock"), json(post("/api/pas/submit"), submission("P32H-PAS-NO-DECISION", ",\"expected\":\"APPROVED\"")), 200);
        assertThat(noDecision.at("/verdict/outcomeCode").asString()).isEqualTo("NO_DECISION");
        assertThat(noDecision.at("/expectation/result").asString()).isEqualTo("fail");
        assertThat(noDecision.at("/determination/0/presence").asString()).isEqualTo("ABSENT");
    }

    @Test
    void aVendorFaultIsStillA200WithTheFaultDescribedInTheBody() throws Exception {
        MockMvc mvc = mvc("mock");
        JsonNode rejected = send(mvc, json(post("/api/pas/submit"), submission("P32H-PAS-BAD400", "")), 200);
        assertThat(rejected.at("/verdict/tone").asString()).isEqualTo("FAULT");
        assertThat(rejected.at("/error/kind").asString()).isEqualTo("VENDOR_REJECTED");
        assertThat(rejected.at("/error/httpStatus").asInt()).isEqualTo(400);
        assertThat(rejected.at("/error/issues/0/code/value").asString()).isEqualTo("invalid");
        assertThat(rejected.at("/wire/request/body").asString()).contains("\"Claim\"");
        assertThat(send(mvc, json(post("/api/pas/submit"), submission("P32H-PAS-ERR500", "")), 200).at("/error/kind").asString()).isEqualTo("VENDOR_ERROR");
        assertThat(send(mvc, json(post("/api/pas/submit"), submission("P32H-PAS-TIMEOUT", "")), 200).at("/error/kind").asString()).isEqualTo("TIMEOUT");
        assertThat(send(mvc, json(post("/api/pas/submit"), submission("P32H-PAS-DOWN", "")), 200).at("/error/kind").asString()).isEqualTo("UNREACHABLE");
        // Not found is an answer about test data, so it has no error panel at all.
        assertThat(send(mvc, json(post("/api/pas/submit"), submission("P32H-PAS-NOMEM", "")), 200).has("error")).isFalse();
    }

    @Test
    void aSubmissionThatFailsValidationIs422NamingEachFieldAndNothingIsRecorded() throws Exception {
        MockMvc mvc = mvc("mock");
        mvc.perform(json(post("/api/pas/submit"), "{\"provider\":{\"npi\":\"1234567890\"},\"procedureCodes\":[]}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "member.memberId", "provider.npi", "procedureCodes")));
        // A field this tool does not know is refused by name, where it would otherwise be dropped.
        mvc.perform(json(post("/api/pas/submit"), submission("P32H-PAS-APPROVED", ",\"expectd\":\"APPROVED\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("expectd"));
        // So is one inside an attachment, an expectation the tool cannot read, and a body that is not JSON.
        mvc.perform(json(post("/api/pas/submit"), submission("P32H-PAS-APPROVED", ",\"attachments\":[{\"titel\":\"Report\"}]")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("titel"));
        mvc.perform(json(post("/api/pas/submit"), submission("P32H-PAS-APPROVED", ",\"expected\":\"NO_AUTH_REQUIRED\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("expected"));
        mvc.perform(json(post("/api/pas/submit"), "{\"member\": ")).andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/pas/submit")).andExpect(status().isUnprocessableContent());
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void anAttachmentMayCarryItsDocumentTypeAndCategoryAndAMisspeltPartOfEitherIsRefusedByName() throws Exception {
        MockMvc mvc = mvc("mock");
        // The document type and category an attachment may carry, for US Core 3.1.1 DocumentReference, are read by those names.
        JsonNode typed = send(mvc, json(post("/api/pas/submit/preview"), submission("P32H-PAS-APPROVED", ",\"attachments\":[{\"title\":"
                + "\"Report\",\"contentType\":\"text/plain\",\"text\":\"Placeholder report.\",\"type\":{\"code\":\"11506-3\",\"system\":"
                + "\"http://loinc.org\"},\"category\":{\"code\":\"clinical-note\",\"system\":"
                + "\"http://hl7.org/fhir/us/core/CodeSystem/us-core-documentreference-category\"}}]")), 200);
        assertThat(typed.get("body").asString()).contains("\"11506-3\"").contains("\"clinical-note\"");
        mvc.perform(json(post("/api/pas/submit"), submission("P32H-PAS-APPROVED", ",\"attachments\":[{\"title\":\"Report\","
                + "\"contentType\":\"text/plain\",\"text\":\"x\",\"type\":{\"kode\":\"11506-3\"}}]")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("kode"));
    }

    @Test
    void aSubmissionTheGuardrailRefusesIs403WithTheWholeResultAsItsBodyAndALedgerLine() throws Exception {
        gateway.failing(blocked(EpaOperation.PAS_SUBMIT));
        JsonNode result = send(mvc("live"), json(post("/api/pas/submit"), submission("S123456789", ",\"expected\":\"APPROVED\"")), 403);
        // A run id and a receipt tell the browser this is a result and not the bare error shape.
        assertThat(result.get("runId").asString()).startsWith("run-");
        assertThat(result.get("operation").asString()).isEqualTo("PAS_SUBMIT");
        assertThat(result.at("/verdict/outcomeCode").asString()).isEqualTo("BLOCKED");
        assertThat(result.at("/verdict/tone").asString()).isEqualTo("BLOCKED");
        assertThat(result.at("/expectation/result").asString()).isEqualTo("unverified");
        assertThat(result.at("/nextStep/action").asString()).isEqualTo("UNLOCK_OR_SWITCH");
        assertThat(result.at("/error/kind").asString()).isEqualTo("BLOCKED");
        assertThat(result.at("/wire/request/sent").asBoolean()).isFalse();
        assertThat(result.at("/wire").has("response")).isFalse();
        assertThat(result.at("/ledger/recorded").asBoolean()).isTrue();
        assertThat(result.has("detail")).isFalse();
        assertThat(ledger.only().outcome()).isEqualTo("BLOCKED");
        assertThat(ledger.only().scenarioId()).isNull();
        // An inquiry is a read. The same environment lets it through.
        FakeGateway production = gateway.failing(null).environment("Onyx Production", EnvironmentKind.PROD);
        assertThat(production.activeEnvironment().production()).isTrue();
        JsonNode inquired = send(mvc("mock"), json(post("/api/pas/inquire"), inquiry("P32H-PAS-APPROVED", ",\"claimId\":\"claim-test-0001\"")), 200);
        assertThat(inquired.at("/verdict/outcomeCode").asString()).isEqualTo("APPROVED");
        assertThat(inquired.at("/environment/kind").asString()).isEqualTo("prod");
    }

    @Test
    void aFailureOfThisToolAfterASubmissionIs500WithTheRunIdAndTheWireView() throws Exception {
        JsonNode result = send(failingAfterTheCall(), json(post("/api/pas/submit"), submission("P32H-PAS-APPROVED", "")), 500);
        assertThat(result.get("runId").asString()).isEqualTo(ledger.only().runId());
        assertThat(result.at("/verdict/outcomeCode").asString()).isEqualTo("TOOL_ERROR");
        assertThat(result.at("/error/kind").asString()).isEqualTo("INTERNAL");
        assertThat(result.at("/error/reference").asString()).isNotBlank();
        assertThat(result.at("/wire/response/body").asString()).contains("\"A1\"");
        assertThat(result.at("/ledger/record/correlation/claimId").asString()).startsWith("claim-");
        assertThat(result.toString()).doesNotContain("the reader exploded");
    }

    @Test
    void aTargetThatIsNotConfiguredIs409FromTheSharedErrorShapeAtEveryEndpointThatMakesACall() throws Exception {
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.PAS_SUBMIT, "There is no url for this call."));
        MockMvc mvc = mvc("live");
        String submit = submission("S123456789", "");
        String inquire = inquiry("S123456789", ",\"claimId\":\"claim-test-0001\"");
        List<MockHttpServletRequestBuilder> calls = List.of(json(post("/api/pas/submit"), submit), json(post("/api/pas/submit/preview"), submit),
                json(post("/api/pas/inquire"), inquire), json(post("/api/pas/inquire/preview"), inquire),
                json(post("/api/pas/subscriptions"), "{}"), json(post("/api/pas/subscriptions/preview"), "{}"),
                get("/api/pas/subscriptions"), get("/api/pas/subscriptions/{id}", ID), json(put("/api/pas/subscriptions/{id}", ID), "{}"),
                json(post("/api/pas/subscriptions/{id}/preview", ID), "{}"), delete("/api/pas/subscriptions/{id}", ID));
        for (MockHttpServletRequestBuilder call : calls) {
            mvc.perform(call).andExpect(status().isConflict()).andExpect(jsonPath("$.kind").value("CONFIGURATION"));
        }
        // Each reached the gateway and stopped there: nothing was sent, so nothing is recorded.
        assertThat(gateway.intents()).hasSize(calls.size());
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void aMisspeltPropertyIsRefusedByNameAtEveryEndpointThatReadsABody() throws Exception {
        MockMvc mvc = mvc("mock");
        String submit = submission("P32H-PAS-APPROVED", ",\"expectd\":\"APPROVED\"");
        String inquire = inquiry("P32H-PAS-APPROVED", ",\"claimId\":\"claim-test-0001\",\"expectd\":\"APPROVED\"");
        String subscription = "{\"expectd\":\"CREATED\"}";
        List<MockHttpServletRequestBuilder> calls = List.of(json(post("/api/pas/submit"), submit), json(post("/api/pas/submit/preview"), submit),
                json(post("/api/pas/inquire"), inquire), json(post("/api/pas/inquire/preview"), inquire),
                json(post("/api/pas/subscriptions"), subscription), json(post("/api/pas/subscriptions/preview"), subscription),
                json(put("/api/pas/subscriptions/{id}", ID), subscription), json(post("/api/pas/subscriptions/{id}/preview", ID), subscription),
                json(post("/api/pas/notifications/sample"), "{\"scenarioId\":\"pas-20-heartbeat\",\"expectd\":\"HEARTBEAT_RECEIVED\"}"));
        for (MockHttpServletRequestBuilder call : calls) {
            mvc.perform(call).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("expectd")));
        }
        // Refused before anything was prepared, sent or recorded.
        assertThat(gateway.intents()).isEmpty();
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void aPreviewOfASubmissionShowsTheTargetAndTheBodyAndRecordsNothing() throws Exception {
        gateway.url(EpaOperation.PAS_SUBMIT, "{{baseUrl}}/Claim/$submit").variable("baseUrl", "https://onyx.example.invalid/fhir");
        MockMvc mvc = mvc("mock");
        JsonNode preview = send(mvc, json(post("/api/pas/submit/preview"), submission("S12345678   01", "")), 200);
        assertThat(preview.get("operation").asString()).isEqualTo("PAS_SUBMIT");
        assertThat(preview.at("/url/template").asString()).isEqualTo("{{baseUrl}}/Claim/$submit");
        assertThat(preview.at("/url/display").asString()).isEqualTo("https://onyx.example.invalid/fhir/Claim/$submit");
        assertThat(preview.at("/url").has("value")).isFalse();
        assertThat(preview.get("method").asString()).isEqualTo("POST");
        assertThat(preview.at("/memberId/sent").asString()).isEqualTo("S1234567801");
        assertThat(preview.get("body").asString()).contains("\"preauthorization\"");
        assertThat(preview.get("notes").isArray()).isTrue();
        assertThat(preview.has("blocked")).isFalse();
        mvc.perform(json(post("/api/pas/submit/preview"), "{}")).andExpect(status().isUnprocessableContent());

        gateway.failing(blocked(EpaOperation.PAS_SUBMIT));
        JsonNode blockedPreview = send(mvc, json(post("/api/pas/submit/preview"), submission("S123456789", "")), 200);
        assertThat(blockedPreview.get("blocked").asString()).startsWith("Writes to the production environment Onyx Production are blocked.");
        assertThat(blockedPreview.has("body")).isFalse();
        assertThat(ledger.records()).isEmpty();
    }

    // ---- inquire ----

    @Test
    void anInquiryStartedFromWhatASubmissionHandedOnAnswers200AndIsLinkedToThatSubmission() throws Exception {
        MockMvc mvc = mvc("mock");
        JsonNode submitted = send(mvc, json(post("/api/pas/submit"), submission("P32H-PAS-INQUIRE", "")), 200);
        assertThat(submitted.at("/verdict/outcomeCode").asString()).isEqualTo("PENDED");
        assertThat(submitted.at("/nextStep/action").asString()).isEqualTo("CHECK_STATUS");

        // The carry of the submission's next step is posted back unchanged.
        JsonNode inquired = send(mvc, json(post("/api/pas/inquire"), inquiry("P32H-PAS-INQUIRE", ",\"expected\":\"APPROVED\",\"carry\":"
                + submitted.at("/nextStep/carry"))), 200);
        assertThat(inquired.get("operation").asString()).isEqualTo("PAS_INQUIRE");
        assertThat(inquired.at("/verdict/outcomeCode").asString()).isEqualTo("APPROVED");
        assertThat(inquired.at("/expectation/result").asString()).isEqualTo("pass");
        assertThat(inquired.at("/detail/claimId").asString()).isEqualTo(submitted.at("/detail/claimId").asString());
        assertThat(inquired.at("/ledger/record/correlation/correlatedRunId").asString()).isEqualTo(submitted.get("runId").asString());
        assertThat(ledger.records()).extracting(RunRecord::operation).containsExactly(EpaOperation.PAS_SUBMIT, EpaOperation.PAS_INQUIRE);

        JsonNode preview = send(mvc, json(post("/api/pas/inquire/preview"), inquiry("S123456789", ",\"claimId\":\"claim-test-0001\"")), 200);
        assertThat(preview.get("operation").asString()).isEqualTo("PAS_INQUIRE");
        assertThat(preview.get("body").asString()).contains("claim-test-0001");
        assertThat(ledger.records()).hasSize(2);
    }

    @Test
    void anInquiryThatFailsValidationIs422AndAVendorFaultIsStillA200() throws Exception {
        MockMvc mvc = mvc("mock");
        mvc.perform(json(post("/api/pas/inquire"), inquiry("S123456789", ""))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("claimId"));
        mvc.perform(json(post("/api/pas/inquire"), inquiry("S123456789", ",\"claimID\":\"claim-test-0001\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("claimID")));
        mvc.perform(json(post("/api/pas/inquire/preview"), inquiry("S123456789", ""))).andExpect(status().isUnprocessableContent());
        assertThat(ledger.records()).isEmpty();
        JsonNode down = send(mvc, json(post("/api/pas/inquire"), inquiry("P32H-PAS-DOWN", ",\"claimId\":\"claim-test-0001\"")), 200);
        assertThat(down.at("/error/kind").asString()).isEqualTo("UNREACHABLE");
        assertThat(down.at("/verdict/outcomeCode").asString()).isEqualTo("UNREACHABLE");
    }

    // ---- subscriptions ----

    @Test
    void theFiveSubscriptionCallsEachAnswer200WithTheirOwnOutcomeAndTheirOwnLedgerLine() throws Exception {
        MockMvc mvc = mvc("mock");
        JsonNode created = send(mvc, json(post("/api/pas/subscriptions"), "{\"expected\":\"CREATED\",\"testerRef\":\"QA-17\"}"), 200);
        assertThat(created.get("protocol").asString()).isEqualTo("SUBSCRIPTION");
        assertThat(created.get("operation").asString()).isEqualTo("SUBSCRIPTION_CREATE");
        assertThat(created.at("/verdict/outcomeCode").asString()).isEqualTo("CREATED");
        assertThat(created.at("/expectation/result").asString()).isEqualTo("pass");
        assertThat(created.at("/nextStep/carry/subscriptionId/0").asString()).isEqualTo(ID);
        // Tier 1: id, status, criteria, channel, endpoint and heartbeat.
        assertThat(created.at("/detail/subscriptionId").asString()).isEqualTo(ID);
        JsonNode view = created.at("/detail/subscriptions/0");
        assertThat(view.at("/id/value").asString()).isEqualTo(ID);
        assertThat(view.at("/status/code").asString()).isEqualTo("requested");
        assertThat(view.at("/criteria/value").asString()).isEqualTo("http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic");
        assertThat(view.at("/channelType/value").asString()).isEqualTo("rest-hook");
        assertThat(view.at("/endpoint/value").asString()).isEqualTo("https://pave.example.invalid/api/pas/notifications");
        assertThat(view.at("/heartbeat/value").asString()).isEqualTo("86400");
        assertThat(created.at("/wire/request/body").asString()).contains("\"Subscription\"");
        assertThat(created.has("error")).isFalse();

        JsonNode list = send(mvc, get("/api/pas/subscriptions").param("expected", "LISTED"), 200);
        assertThat(list.get("operation").asString()).isEqualTo("SUBSCRIPTION_GET");
        assertThat(list.at("/verdict/outcomeCode").asString()).isEqualTo("LISTED");
        assertThat(list.at("/expectation/result").asString()).isEqualTo("pass");
        assertThat(list.at("/detail/total/value").asString()).isEqualTo("1");

        JsonNode read = send(mvc, get("/api/pas/subscriptions/{id}", ID), 200);
        assertThat(read.at("/verdict/outcomeCode").asString()).isEqualTo("READ");
        assertThat(read.at("/detail/subscriptions/0/status/code").asString()).isEqualTo("active");
        assertThat(read.at("/wire/request/method").asString()).isEqualTo("GET");

        JsonNode updated = send(mvc, json(put("/api/pas/subscriptions/{id}", ID), "{\"variables\":{\"subscriptionStatus\":\"off\"}}"), 200);
        assertThat(updated.get("operation").asString()).isEqualTo("SUBSCRIPTION_UPDATE");
        assertThat(updated.at("/verdict/outcomeCode").asString()).isEqualTo("UPDATED");
        assertThat(updated.at("/wire/request/method").asString()).isEqualTo("PUT");
        assertThat(JsonSafe.parse(updated.at("/wire/request/body").asString()).node().get("id").asString()).isEqualTo(ID);

        JsonNode deleted = send(mvc, delete("/api/pas/subscriptions/{id}", ID).param("note", "cleaning up"), 200);
        assertThat(deleted.get("operation").asString()).isEqualTo("SUBSCRIPTION_DELETE");
        assertThat(deleted.at("/verdict/outcomeCode").asString()).isEqualTo("DELETED");
        assertThat(deleted.at("/wire/request/method").asString()).isEqualTo("DELETE");
        assertThat(deleted.at("/wire/response/status").asInt()).isEqualTo(204);

        assertThat(ledger.records()).extracting(RunRecord::outcome).containsExactly("CREATED", "LISTED", "READ", "UPDATED", "DELETED");
        assertThat(ledger.records().get(0).testerRef()).isEqualTo("QA-17");
        assertThat(ledger.records().get(4).note()).isEqualTo("cleaning up");
        assertThat(ledger.records().get(4).correlation()).containsEntry(RunRecord.CORR_SUBSCRIPTION_ID, ID);
        // The id reached the gateway as a url value, taken from the address.
        assertThat(gateway.lastIntent().urlValues()).containsExactly(Map.entry("id", ID));
    }

    @Test
    void aCreateAnUpdateAndADeleteTheGuardrailRefusesAreEach403WithALedgerLineOfBlocked() throws Exception {
        MockMvc mvc = mvc("live");
        gateway.failing(blocked(EpaOperation.SUBSCRIPTION_CREATE));
        JsonNode create = send(mvc, json(post("/api/pas/subscriptions"), "{}"), 403);
        gateway.failing(blocked(EpaOperation.SUBSCRIPTION_UPDATE));
        JsonNode update = send(mvc, json(put("/api/pas/subscriptions/{id}", ID), "{}"), 403);
        gateway.failing(blocked(EpaOperation.SUBSCRIPTION_DELETE));
        JsonNode remove = send(mvc, delete("/api/pas/subscriptions/{id}", ID), 403);
        for (JsonNode result : List.of(create, update, remove)) {
            assertThat(result.get("runId").asString()).startsWith("run-");
            assertThat(result.at("/verdict/outcomeCode").asString()).isEqualTo("BLOCKED");
            assertThat(result.at("/error/kind").asString()).isEqualTo("BLOCKED");
            assertThat(result.at("/wire/request/sent").asBoolean()).isFalse();
            assertThat(result.at("/ledger/recorded").asBoolean()).isTrue();
            assertThat(result.has("detail")).isFalse();
        }
        assertThat(ledger.records()).extracting(RunRecord::outcome).containsExactly("BLOCKED", "BLOCKED", "BLOCKED");
        assertThat(ledger.records()).extracting(RunRecord::operation).containsExactly(EpaOperation.SUBSCRIPTION_CREATE,
                EpaOperation.SUBSCRIPTION_UPDATE, EpaOperation.SUBSCRIPTION_DELETE);
        // No scenario answered, because nothing reached a client.
        assertThat(ledger.records()).extracting(RunRecord::scenarioId).containsOnlyNulls();

        // A read and a list are reads: on a production environment they go through.
        gateway.failing(null).environment("Onyx Production", EnvironmentKind.PROD);
        MockMvc mock = mvc("mock");
        assertThat(send(mock, get("/api/pas/subscriptions/{id}", ID), 200).at("/verdict/outcomeCode").asString()).isEqualTo("READ");
        assertThat(send(mock, get("/api/pas/subscriptions"), 200).at("/verdict/outcomeCode").asString()).isEqualTo("LISTED");
        assertThat(ledger.last().environmentKind()).isEqualTo(EnvironmentKind.PROD);
    }

    @Test
    void aSubscriptionCallThatFailsValidationIs422AndAnIdTheServiceDoesNotKnowIsA200ThatSaysSo() throws Exception {
        MockMvc mvc = mvc("mock");
        mvc.perform(json(post("/api/pas/subscriptions"), "{\"subscriptionId\":\"" + ID + "\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("subscriptionId"));
        mvc.perform(json(post("/api/pas/subscriptions"), "{\"expectd\":\"CREATED\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("expectd"));
        mvc.perform(json(post("/api/pas/subscriptions"), "{\"member\":{\"memberId\":\"S123456789\"}}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("member"));
        mvc.perform(post("/api/pas/subscriptions")).andExpect(status().isUnprocessableContent());
        mvc.perform(json(put("/api/pas/subscriptions/{id}", ID), "{\"subscriptionId\":\"subscription-other\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("subscriptionId"));
        mvc.perform(json(put("/api/pas/subscriptions/{id}", "two words"), "{}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("subscriptionId"));
        // A parameter nobody knows is refused by name, exactly as an unknown field of a body is.
        mvc.perform(get("/api/pas/subscriptions/{id}", ID).param("expectd", "READ")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("expectd"));
        mvc.perform(delete("/api/pas/subscriptions/{id}", ID).param("force", "true")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("force"));
        mvc.perform(get("/api/pas/subscriptions").param("expected", "APPROVED")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("expected"));
        mvc.perform(get("/api/pas/subscriptions").param("configEpoch", "soon")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("configEpoch"));
        mvc.perform(get("/api/pas/subscriptions").param("collectionId", "col-1")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("requestId"));
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();

        // The screen was drawn from a configuration that is no longer current: refused, and not recorded.
        mvc.perform(get("/api/pas/subscriptions").param("configEpoch", "7")).andExpect(status().isConflict());
        assertThat(ledger.records()).isEmpty();

        // Not found is an answer, and it is answered 200 with no error panel.
        JsonNode notFound = send(mvc, get("/api/pas/subscriptions/{id}", ID).param("scenarioId", "pas-19-subscription-not-found"), 200);
        assertThat(notFound.at("/verdict/outcomeCode").asString()).isEqualTo("NOT_FOUND");
        assertThat(notFound.at("/verdict/tone").asString()).isEqualTo("DATA");
        assertThat(notFound.has("error")).isFalse();
        assertThat(notFound.at("/detail/issues/0/code/value").asString()).isEqualTo("not-found");
    }

    @Test
    void whatABodyWouldCarryReachesAReadAndADeleteAsRequestParameters() throws Exception {
        gateway.url(EpaOperation.SUBSCRIPTION_GET, "{{baseUrl}}/Subscription/{id}");
        String followed = "run-00000000-0000-4000-8000-0000000000c1";
        JsonNode read = send(mvc("mock"), get("/api/pas/subscriptions/{id}", ID).param("var.baseUrl", "https://onyx.example.invalid/fhir")
                .param("carry.runId", followed).param("expected", "subscription-status=active").param("testerRef", "QA-17")
                .param("collectionId", "col-1").param("requestId", "req-2").param("configEpoch", "0"), 200);
        assertThat(read.at("/wire/request/url").asString()).isEqualTo("https://onyx.example.invalid/fhir/Subscription/" + ID);
        assertThat(read.at("/expectation/result").asString()).isEqualTo("pass");
        RunRecord record = ledger.only();
        assertThat(record.testerRef()).isEqualTo("QA-17");
        assertThat(record.correlation()).containsEntry(RunRecord.CORR_RUN_ID, followed);
        assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_RUN_VALUES, RunRecord.INPUT_CHOSEN_REQUEST, RunRecord.INPUT_PRIOR_STEP);
        assertThat(gateway.lastIntent().request()).isEqualTo(new RequestRef("col-1", "req-2"));
        assertThat(gateway.lastIntent().expectedEpoch()).isZero();
        assertThat(gateway.lastIntent().runValues()).containsExactly(Map.entry("baseUrl", "https://onyx.example.invalid/fhir"));
    }

    @Test
    void aSubscriptionPreviewShowsWhatACreateOrAnUpdateWouldSendAndRecordsNothing() throws Exception {
        MockMvc mvc = mvc("mock");
        JsonNode create = send(mvc, json(post("/api/pas/subscriptions/preview"), "{}"), 200);
        assertThat(create.get("operation").asString()).isEqualTo("SUBSCRIPTION_CREATE");
        assertThat(create.get("method").asString()).isEqualTo("POST");
        assertThat(JsonSafe.parse(create.get("body").asString()).node().at("/channel/endpoint").asString())
                .isEqualTo("https://pave.example.invalid/api/pas/notifications");
        assertThat(create.has("memberId")).isFalse();
        JsonNode update = send(mvc, json(post("/api/pas/subscriptions/{id}/preview", ID), "{}"), 200);
        assertThat(update.get("operation").asString()).isEqualTo("SUBSCRIPTION_UPDATE");
        assertThat(update.get("method").asString()).isEqualTo("PUT");
        assertThat(JsonSafe.parse(update.get("body").asString()).node().get("id").asString()).isEqualTo(ID);
        mvc.perform(json(post("/api/pas/subscriptions/preview"), "{\"subscriptionId\":\"" + ID + "\"}")).andExpect(status().isUnprocessableContent());
        gateway.failing(blocked(EpaOperation.SUBSCRIPTION_CREATE));
        assertThat(send(mvc, json(post("/api/pas/subscriptions/preview"), "{}"), 200).get("blocked").asString()).contains("are blocked");
        assertThat(ledger.records()).isEmpty();
    }

    // ---- the session's unlock ----

    @Test
    void theSessionsUnlockReachesTheGatewayForEveryWriteAndNeverComesFromTheRequestBody() throws Exception {
        MockMvc mvc = mvc("mock");
        ProdUnlock unlock = TestPreparedCalls.unlock("Onyx Production", 0L);
        MockHttpSession session = new MockHttpSession();
        MockHttpServletRequest holder = new MockHttpServletRequest();
        holder.setSession(session);
        SessionUnlock.store(holder, unlock);

        List<MockHttpServletRequestBuilder> writes = List.of(json(post("/api/pas/submit"), submission("P32H-PAS-APPROVED", "")),
                json(post("/api/pas/subscriptions"), "{}"), json(put("/api/pas/subscriptions/{id}", ID), "{}"),
                delete("/api/pas/subscriptions/{id}", ID));
        for (MockHttpServletRequestBuilder write : writes) {
            mvc.perform(write.session(session)).andExpect(status().isOk());
            assertThat(gateway.lastIntent().unlock()).isSameAs(unlock);
        }
        // The previews are judged with the same unlock, so what a preview says would be sent is what is sent.
        mvc.perform(json(post("/api/pas/submit/preview"), submission("P32H-PAS-APPROVED", "")).session(session)).andExpect(status().isOk());
        assertThat(gateway.lastIntent().unlock()).isSameAs(unlock);

        // With no session there is no unlock, whatever the body or the parameters say.
        mvc.perform(json(post("/api/pas/submit"), submission("P32H-PAS-APPROVED", ""))).andExpect(status().isOk());
        assertThat(gateway.lastIntent().unlock()).isNull();
        int before = gateway.intents().size();
        mvc.perform(json(post("/api/pas/submit"), submission("P32H-PAS-APPROVED", ",\"unlock\":{\"environmentName\":\"Onyx Production\",\"epoch\":0}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("unlock"));
        mvc.perform(json(post("/api/pas/subscriptions"), "{\"unlock\":true}")).andExpect(status().isUnprocessableContent());
        mvc.perform(delete("/api/pas/subscriptions/{id}", ID).param("unlock", "true")).andExpect(status().isUnprocessableContent());
        mvc.perform(delete("/api/pas/subscriptions/{id}", ID).param("environmentName", "Onyx Production").param("epoch", "0"))
                .andExpect(status().isUnprocessableContent());
        assertThat(gateway.intents()).hasSize(before);
    }

    // ---- the notification receiver ----

    @Test
    void whateverIsPostedToTheReceiverIsAnswered200WithNoBodyAndIsRecordedAndShown() throws Exception {
        MockMvc mvc = mvc("mock");
        String notification = NotificationJson.idOnly("subscription-of-another-client", "claimresponse-nobody-submitted");
        mvc.perform(post("/api/pas/notifications").contentType("application/fhir+json").content(notification))
                .andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(post("/api/pas/notifications").contentType(MediaType.TEXT_PLAIN).content("not json for Placeholder-Surname"))
                .andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(post("/api/pas/notifications").contentType(MediaType.APPLICATION_JSON).content("{\"resourceType\": "))
                .andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(post("/api/pas/notifications").contentType(MediaType.APPLICATION_XML).content("<Bundle/>")).andExpect(status().isOk());
        mvc.perform(post("/api/pas/notifications")).andExpect(status().isOk()).andExpect(content().string(""));

        // Five arrivals, five ledger lines, none of them a run that could be sent again.
        assertThat(ledger.records()).hasSize(5);
        assertThat(ledger.records()).extracting(RunRecord::operation).containsOnly(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(ledger.records()).extracting(RunRecord::outcome).containsExactly("UNMATCHED_NOTIFICATION", "UNREADABLE_NOTIFICATION",
                "UNREADABLE_NOTIFICATION", "UNREADABLE_NOTIFICATION", "UNREADABLE_NOTIFICATION");
        assertThat(ledger.records()).allSatisfy(record -> assertThat(record.unrecordedInputs()).containsExactly(RunRecord.INPUT_INBOUND));
        assertThat(JsonSafe.mapper().writeValueAsString(ledger.records())).doesNotContain("Placeholder-Surname").doesNotContain("resourceType");

        JsonNode list = send(mvc, get("/api/pas/notifications"), 200);
        assertThat(list.get("held").asInt()).isEqualTo(5);
        assertThat(list.get("received").asInt()).isEqualTo(5);
        assertThat(list.get("capacity").asInt()).isEqualTo(NotificationReceiver.CAPACITY);
        assertThat(list.get("note").asString()).contains("held in memory only");
        assertThat(list.get("notifications")).hasSize(5);
        // Newest first: the one with no body is first and the notification is last.
        JsonNode empty = list.at("/notifications/0");
        assertThat(empty.get("kind").asString()).isEqualTo("NO_BODY");
        assertThat(empty.has("payload")).isFalse();
        JsonNode first = list.at("/notifications/4");
        assertThat(first.get("id").asString()).isEqualTo(ledger.records().get(0).runId());
        assertThat(first.get("receivedAt").asString()).isEqualTo("2026-03-10T15:00:00Z");
        assertThat(first.get("payload").asString()).isEqualTo(notification);
        assertThat(first.at("/verdict/outcomeCode").asString()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(first.at("/nextStep/action").asString()).isEqualTo("REVIEW");
        assertThat(first.at("/type/code").asString()).isEqualTo("event-notification");
        assertThat(first.get("subscriptionId").asString()).isEqualTo("subscription-of-another-client");
        assertThat(first.get("payloadContent").asString()).isEqualTo("id-only");
        assertThat(first.at("/findings/0/kind").asString()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(first.at("/ledger/recorded").asBoolean()).isTrue();
        assertThat(first.get("sample").asBoolean()).isFalse();
        assertThat(first.get("truncated").asBoolean()).isFalse();
        // A null is left out: this one was matched to no run and is no sample.
        assertThat(first.has("correlatedRunId")).isFalse();
        assertThat(first.has("subscriptionRunId")).isFalse();
        assertThat(first.has("scenarioId")).isFalse();
        // Tier 2: what the reader did not interpret is listed, and so nothing is in the payload alone.
        assertThat(first.get("additionalFields").isArray()).isTrue();
        assertThat(list.at("/notifications/3/payload").asString()).isEqualTo("not json for Placeholder-Surname");

        // A poll with the newest stamp gets nothing new, and is still told what is held.
        JsonNode since = send(mvc, get("/api/pas/notifications").param("since", list.get("latest").asString()), 200);
        assertThat(since.get("notifications")).isEmpty();
        assertThat(since.get("held").asInt()).isEqualTo(5);
        JsonNode sinceFirst = send(mvc, get("/api/pas/notifications").param("since", first.get("receivedAt").asString()), 200);
        assertThat(sinceFirst.get("notifications")).hasSize(4);
        mvc.perform(get("/api/pas/notifications").param("since", "yesterday")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("since"));
    }

    @Test
    void aBodyLongerThanTheReceiverHoldsIsStillAnswered200AndTheListSaysItWasCut() throws Exception {
        MockMvc mvc = mvc("mock");
        String oversized = "{\"padding\":\"" + "a".repeat(NotificationReceiver.MAX_KEPT_BYTES + 10) + "\"}";
        mvc.perform(post("/api/pas/notifications").contentType(MediaType.APPLICATION_JSON).content(oversized)).andExpect(status().isOk());
        JsonNode held = send(mvc, get("/api/pas/notifications"), 200).at("/notifications/0");
        assertThat(held.get("truncated").asBoolean()).isTrue();
        assertThat(held.get("bytesReceived").asLong()).isEqualTo(oversized.length());
        assertThat(held.get("bytesKept").asLong()).isEqualTo(NotificationReceiver.MAX_KEPT_BYTES);
        assertThat(held.get("payload").asString()).hasSize(NotificationReceiver.MAX_KEPT_BYTES);
        assertThat(held.at("/payloadNotes/0").asString()).contains("Only its first " + NotificationReceiver.MAX_KEPT_BYTES + " bytes are held");
        assertThat(ledger.only().coverageCodes()).contains("body=truncated");
    }

    @Test
    void onMockASubmissionCanBeFollowedByASampleNotificationThatIsMatchedToItAndToTheSubscription() throws Exception {
        MockMvc mvc = mvc("mock");
        JsonNode created = send(mvc, json(post("/api/pas/subscriptions"), "{}"), 200);
        JsonNode submitted = send(mvc, json(post("/api/pas/submit"), submission("P32H-PAS-NOTIFY", "")), 200);
        assertThat(submitted.at("/verdict/outcomeCode").asString()).isEqualTo("PENDED");

        JsonNode sample = send(mvc, json(post("/api/pas/notifications/sample"), "{\"runId\":\"" + submitted.get("runId").asString() + "\"}"), 200);
        assertThat(sample.get("sample").asBoolean()).isTrue();
        assertThat(sample.get("scenarioId").asString()).isEqualTo("pas-06-submit-then-notified");
        assertThat(sample.at("/verdict/outcomeCode").asString()).isEqualTo("NOTIFICATION_RECEIVED");
        assertThat(sample.get("correlatedRunId").asString()).isEqualTo(submitted.get("runId").asString());
        assertThat(sample.get("subscriptionRunId").asString()).isEqualTo(created.get("runId").asString());
        assertThat(sample.get("claimId").asString()).isEqualTo(submitted.at("/detail/claimId").asString());
        assertThat(sample.at("/claimResponses/0/decision").asString()).isEqualTo("APPROVED");
        assertThat(sample.get("payloadContent").asString()).isEqualTo("full-resource");
        assertThat(sample.at("/nextStep/action").asString()).isEqualTo("FLOW_ENDS");

        // It arrived through the same code a real one reaches, so it is in the list and in the ledger.
        JsonNode list = send(mvc, get("/api/pas/notifications"), 200);
        assertThat(list.at("/notifications/0/id").asString()).isEqualTo(sample.get("id").asString());
        assertThat(list.at("/notifications/0/correlatedRunId").asString()).isEqualTo(submitted.get("runId").asString());
        assertThat(ledger.last().operation()).isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(ledger.last().correlation()).containsEntry(RunRecord.CORR_RUN_ID, submitted.get("runId").asString())
                .containsEntry(RunRecord.CORR_SUBSCRIPTION_RUN_ID, created.get("runId").asString());
    }

    @Test
    void aSampleIsRefusedOnLiveAndWhenItNamesSomethingThatIsNotThere() throws Exception {
        mvc("live").perform(json(post("/api/pas/notifications/sample"), "{\"scenarioId\":\"pas-20-heartbeat\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(org.hamcrest.Matchers.containsString("mock profile only")));
        MockMvc mvc = mvc("mock");
        mvc.perform(json(post("/api/pas/notifications/sample"), "{}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
        mvc.perform(json(post("/api/pas/notifications/sample"), "{\"scenarioID\":\"pas-20-heartbeat\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioID"));
        mvc.perform(json(post("/api/pas/notifications/sample"), "{\"scenarioId\":\"pas-11-notification-known-run\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("runId"));
        mvc.perform(json(post("/api/pas/notifications/sample"), "{\"scenarioId\":\"pas-11-notification-known-run\","
                        + "\"runId\":\"run-00000000-0000-4000-8000-00000000dead\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.kind").value("NOT_FOUND"));
        mvc.perform(post("/api/pas/notifications/sample")).andExpect(status().isUnprocessableContent());
        assertThat(ledger.records()).isEmpty();
        // The one that needs nothing named is delivered, and with no subscription created it is unmatched.
        JsonNode heartbeat = send(mvc, json(post("/api/pas/notifications/sample"), "{\"scenarioId\":\"pas-20-heartbeat\"}"), 200);
        assertThat(heartbeat.at("/verdict/outcomeCode").asString()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(heartbeat.at("/type/code").asString()).isEqualTo("heartbeat");
    }

    // ---- scenarios and vocabulary ----

    @Test
    void theScenarioListIsOfferedOnMockForEachKindOfCallWithOnlyTheScenariosThatAnswerIt() throws Exception {
        MockMvc mvc = mvc("mock");
        JsonNode all = send(mvc, get("/api/pas/scenarios"), 200);
        assertThat(all.get("available").asBoolean()).isTrue();
        assertThat(all.get("scenarios")).hasSize(21);
        assertThat(all.at("/scenarios/0/sentinelMemberId").asString()).isEqualTo("P32H-PAS-APPROVED");
        assertThat(all.at("/scenarios/0/expectedOutcome").asString()).isEqualTo("APPROVED");
        assertThat(all.get("problems")).isEmpty();
        // Every outcome a tester can meet on this tab, each once, with its label and tone: 22 of a
        // submission or an inquiry, 12 of a subscription call of which 5 faults carry the same code
        // as PAS, 8 of a notification, and the 2 about this tool itself.
        Set<String> codes = new HashSet<>();
        all.get("outcomes").forEach(option -> assertThat(codes.add(option.get("code").asString())).as(option.get("code").asString() + " is listed once").isTrue());
        assertThat(codes).contains("APPROVED", "DENIED", "NO_DECISION", "CREATED", "NOT_FOUND", "UNMATCHED_NOTIFICATION",
                "UNCHECKED_NOTIFICATION", "BLOCKED", "TOOL_ERROR", "NO_MATCHING_AUTHORIZATION").hasSize(39);
        assertThat(all.get("outcomes")).anySatisfy(option -> {
            assertThat(option.get("code").asString()).isEqualTo("DENIED");
            assertThat(option.get("label").asString()).isEqualTo("The authorization is denied");
            assertThat(option.get("tone").asString()).isEqualTo("STOP");
        });
        assertThat(all.get("elements")).extracting(JsonNode::asString).containsExactly("review-action", "review-reason", "claim-outcome", "error-code",
                "subscription-status", "channel-type", "payload-content");

        JsonNode submit = send(mvc, get("/api/pas/scenarios").param("for", "submit"), 200);
        assertThat(submit.get("scenarios")).hasSize(16);
        assertThat(submit.get("outcomes")).hasSize(24);
        assertThat(submit.get("elements")).extracting(JsonNode::asString).containsExactly("review-action", "review-reason", "claim-outcome", "error-code");

        JsonNode inquire = send(mvc, get("/api/pas/scenarios").param("for", "Inquire"), 200);
        assertThat(inquire.get("scenarios")).hasSize(9);
        // The outcome a scenario claims for an inquiry is its inquiry's, not its submission's.
        assertThat(inquire.get("scenarios")).anySatisfy(scenario -> {
            assertThat(scenario.get("id").asString()).isEqualTo("pas-05-submit-then-inquire");
            assertThat(scenario.get("expectedOutcome").asString()).isEqualTo("APPROVED");
        });

        JsonNode subscription = send(mvc, get("/api/pas/scenarios").param("for", "subscription"), 200);
        assertThat(subscription.get("scenarios")).extracting(s -> s.get("id").asString())
                .containsExactly("pas-10-subscription-lifecycle", "pas-19-subscription-not-found");
        assertThat(subscription.get("outcomes")).hasSize(14);
        assertThat(subscription.get("elements")).extracting(JsonNode::asString).containsExactly("subscription-status", "channel-type", "payload-content");
        assertThat(subscription.at("/scenarios/0").has("expectedOutcome")).isFalse();

        JsonNode notification = send(mvc, get("/api/pas/scenarios").param("for", "notification"), 200);
        assertThat(notification.get("scenarios")).extracting(s -> s.get("id").asString()).containsExactly("pas-06-submit-then-notified",
                "pas-10-subscription-lifecycle", "pas-11-notification-known-run", "pas-12-notification-unmatched", "pas-20-heartbeat");
        assertThat(notification.at("/scenarios/3/expectedOutcome").asString()).isEqualTo("UNMATCHED_NOTIFICATION");
        assertThat(notification.get("outcomes")).hasSize(10);
        assertThat(notification.get("elements")).isEmpty();

        mvc.perform(get("/api/pas/scenarios").param("for", "everything")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("for"));
    }

    @Test
    void onLiveThereIsNoScenarioToChooseAndTheVocabularyIsStillServed() throws Exception {
        MockMvc mvc = mvc("live");
        mvc.perform(get("/api/pas/scenarios")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").isNotEmpty())
                .andExpect(jsonPath("$.scenarios.length()").value(0))
                .andExpect(jsonPath("$.outcomes.length()").value(39))
                .andExpect(jsonPath("$.elements.length()").value(7));
        mvc.perform(get("/api/pas/scenarios").param("for", "subscription")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.outcomes.length()").value(14));
        // And a scenario cannot be forced on live, on any of the calls.
        mvc.perform(json(post("/api/pas/submit"), submission("S123456789", ",\"scenarioId\":\"pas-03-denied\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
        mvc.perform(json(post("/api/pas/inquire"), inquiry("S123456789", ",\"claimId\":\"claim-test-0001\",\"scenarioId\":\"pas-03-denied\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
        mvc.perform(get("/api/pas/subscriptions/{id}", ID).param("scenarioId", "pas-19-subscription-not-found"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
        assertThat(ledger.records()).isEmpty();
    }
}
