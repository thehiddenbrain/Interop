package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The CRD endpoints as a browser sees them: the status of each kind of result and the shape of
 * its body. The real service, mock client, scenario files and mapper are behind the controller,
 * with a fake gateway, an in-memory ledger and an in-memory code list.
 */
class CrdControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String REQUEST = "{\"member\":{\"memberId\":\"%s\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
            + "\"procedureCodes\":[{\"code\":\"95810\"}],\"dateOfService\":\"2026-03-20\"%s}";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final FakeCodeList codeList = new FakeCodeList();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private MockMvc mvc(String mode) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        RunSupport support = new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
        CrdService service = new CrdService(epa, gateway, new MockOnyxCrdClient(scenarios, new MockExchanges(APP)), codeList, support);
        return TestMvc.standalone(new CrdController(service, scenarios, epa));
    }

    private static String body(String memberId, String extra) {
        return REQUEST.formatted(memberId, extra);
    }

    private JsonNode run(MockMvc mvc, String body, int expectedStatus) throws Exception {
        String response = mvc.perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return JsonSafe.parse(response).node();
    }

    @Test
    void aRunAnswers200WithTheFourAnswersAndTheLedgerReceipt() throws Exception {
        JsonNode result = run(mvc("mock"), body("P32H-NO-AUTH", ",\"expected\":\"NO_AUTH_REQUIRED\""), 200);

        assertThat(result.get("protocol").asString()).isEqualTo("CRD");
        assertThat(result.get("operation").asString()).isEqualTo("CRD_ORDER_SIGN");
        assertThat(result.get("profile").asString()).isEqualTo("mock");
        // What happened, as an outcome apart from the test result.
        assertThat(result.at("/verdict/headline").asString()).isEqualTo("Prior authorization is not required.");
        assertThat(result.at("/verdict/outcomeCode").asString()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.at("/verdict/tone").asString()).isEqualTo("CLEAR");
        // What was expected, and whether it matched.
        assertThat(result.at("/expectation/expected").asString()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(result.at("/expectation/result").asString()).isEqualTo("pass");
        // What to do next.
        assertThat(result.at("/nextStep/action").asString()).isEqualTo("FLOW_ENDS");
        // And that it is recorded, under the run id on screen.
        assertThat(result.at("/ledger/recorded").asBoolean()).isTrue();
        assertThat(result.at("/ledger/record/runId").asString()).isEqualTo(result.get("runId").asString());
        assertThat(ledger.only().runId()).isEqualTo(result.get("runId").asString());
        // The determination grid, the wire view and the environment flag are there to read.
        assertThat(result.get("determination").isArray()).isTrue();
        assertThat(result.at("/wire/request/sent").asBoolean()).isFalse();
        assertThat(result.at("/wire/response/status").asInt()).isEqualTo(200);
        assertThat(result.at("/environment/active").asBoolean()).isFalse();
        // A null is left out, never sent as null.
        assertThat(result.has("error")).isFalse();
        assertThat(result.has("chainId")).isFalse();
    }

    @Test
    void aVendorFaultIsStillA200WithTheFaultDescribedInTheBody() throws Exception {
        JsonNode result = run(mvc("mock"), body("P32H-ERR500", ""), 200);
        assertThat(result.at("/verdict/tone").asString()).isEqualTo("FAULT");
        assertThat(result.at("/error/kind").asString()).isEqualTo("VENDOR_ERROR");
        assertThat(result.at("/error/httpStatus").asInt()).isEqualTo(500);
        assertThat(result.at("/wire/request/body").asString()).contains("\"hook\"");
        JsonNode timeout = run(mvc("mock"), body("P32H-TIMEOUT", ""), 200);
        assertThat(timeout.at("/error/kind").asString()).isEqualTo("TIMEOUT");
        // Not found is an answer about test data, so it has no error panel at all.
        assertThat(run(mvc("mock"), body("P32H-NOMEM", ""), 200).has("error")).isFalse();
    }

    @Test
    void aFormThatFailsValidationIs422NamingEachFieldAndNothingIsRecorded() throws Exception {
        MockMvc mvc = mvc("mock");
        mvc.perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":{\"npi\":\"1234567890\"},\"procedureCodes\":[]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "member.memberId", "provider.npi", "procedureCodes")));
        // A field this tool does not know is refused by name, where it would otherwise be dropped.
        mvc.perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content(body("P32H-NO-AUTH", ",\"expectd\":\"NO_AUTH_REQUIRED\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("expectd"));
        // So is an expectation the tool cannot read, and a body that is not JSON.
        mvc.perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content(body("P32H-NO-AUTH", ",\"expected\":\"APPROVED\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("expected"));
        mvc.perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content("{\"member\": "))
                .andExpect(status().isUnprocessableContent());
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void aTargetThatIsNotConfiguredIs409FromTheSharedErrorShape() throws Exception {
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.CRD_ORDER_SIGN, "There is no url for CRD order-sign."));
        mvc("live").perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON).content(body("S123456789", "")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.kind").value("CONFIGURATION"));
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void aWriteTheGuardrailRefusesIs403WithTheWholeResultAsItsBody() throws Exception {
        gateway.failing(new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment.",
                new ResolvedUrl("{{baseUrl}}/Claim/$submit", "https://onyx.example.invalid/Claim/$submit",
                        "https://onyx.example.invalid/Claim/$submit", List.of(), List.of(), "PAS submit sample"), "POST", "PAS submit sample"));
        JsonNode result = run(mvc("live"), body("S123456789", ""), 403);
        // A run id and a receipt tell the browser this is a result and not the bare error shape.
        assertThat(result.get("runId").asString()).startsWith("run-");
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
    }

    @Test
    void aFailureOfThisToolAfterTheCallIs500WithTheRunIdAndTheWireView() throws Exception {
        // The code list fails while the response is being checked, after the service was called.
        codeList.failing(new IllegalStateException("code list exploded for Placeholder-Surname"));
        String request = "{\"member\":{\"memberId\":\"P32H-NO-AUTH\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
                + "\"procedureCodes\":[{\"code\":\"95810\",\"system\":\"http://www.ama-assn.org/go/cpt\"}]}";
        JsonNode result = run(mvc("mock"), request, 500);
        assertThat(result.get("runId").asString()).isEqualTo(ledger.only().runId());
        assertThat(result.at("/verdict/outcomeCode").asString()).isEqualTo("TOOL_ERROR");
        assertThat(result.at("/error/kind").asString()).isEqualTo("INTERNAL");
        assertThat(result.at("/error/reference").asString()).isNotBlank();
        assertThat(result.at("/wire/response/body").asString()).contains("no-auth");
        assertThat(result.toString()).doesNotContain("Placeholder-Surname").doesNotContain("code list exploded");
    }

    @Test
    void aPreviewShowsTheTargetAndTheBodyAndRecordsNothing() throws Exception {
        gateway.url(EpaOperation.CRD_ORDER_SIGN, "{{baseUrl}}/cds-services/x").variable("baseUrl", "https://onyx.example.invalid");
        String response = mvc("mock").perform(post("/api/crd/preview").contentType(MediaType.APPLICATION_JSON).content(body("S12345678   01", "")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode preview = JsonSafe.parse(response).node();
        assertThat(preview.at("/url/template").asString()).isEqualTo("{{baseUrl}}/cds-services/x");
        assertThat(preview.at("/url/display").asString()).isEqualTo("https://onyx.example.invalid/cds-services/x");
        // The value to send is never serialised. Only the display form is.
        assertThat(preview.at("/url").has("value")).isFalse();
        assertThat(preview.get("method").asString()).isEqualTo("POST");
        assertThat(preview.at("/memberId/sent").asString()).isEqualTo("S1234567801");
        assertThat(preview.get("body").asString()).contains("\"hook\"");
        assertThat(preview.has("blocked")).isFalse();
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void discoveryListsWhatTheEnvironmentOffers() throws Exception {
        mvc("mock").perform(get("/api/crd/discover")).andExpect(status().isOk())
                .andExpect(jsonPath("$.services.length()").value(2))
                .andExpect(jsonPath("$.services[0].hook.value").value("order-sign"))
                .andExpect(jsonPath("$.note").isNotEmpty());
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void theScenarioListIsOfferedOnMockAndIsEmptyOnLiveWhereTheVocabularyIsStillServed() throws Exception {
        String mock = mvc("mock").perform(get("/api/crd/scenarios")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.scenarios.length()").value(14))
                .andExpect(jsonPath("$.scenarios[1].sentinelMemberId").value("P32H-NO-AUTH"))
                .andReturn().getResponse().getContentAsString();
        JsonNode list = JsonSafe.parse(mock).node();
        // The outcomes and element names a tester can declare, so the browser keeps no copy of them.
        assertThat(list.get("outcomes")).anySatisfy(option -> {
            assertThat(option.get("code").asString()).isEqualTo("NO_DETERMINATION");
            assertThat(option.get("tone").asString()).isEqualTo("NO_ANSWER");
        });
        assertThat(list.get("elements").get(1).asString()).isEqualTo("pa-needed");

        mvc("live").perform(get("/api/crd/scenarios")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").isNotEmpty())
                .andExpect(jsonPath("$.scenarios.length()").value(0))
                .andExpect(jsonPath("$.outcomes.length()").value(22))
                .andExpect(jsonPath("$.elements.length()").value(7));
        // And a scenario cannot be forced on live.
        mvc("live").perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON)
                        .content(body("S123456789", ",\"scenarioId\":\"crd-02-no-auth\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
    }
}
