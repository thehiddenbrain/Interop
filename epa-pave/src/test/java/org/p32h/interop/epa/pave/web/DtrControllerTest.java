package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The DTR endpoints as a browser sees them: the status of each kind of result and the shape of its
 * body. The real service, mock client, scenario files and mapper are behind the controller, with a
 * fake gateway, an in-memory ledger and an in-memory code list.
 */
class DtrControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String PACKAGE = "{\"member\":{\"memberId\":\"%s\",\"lineOfBusiness\":\"Medicare\"},\"provider\":{\"npi\":\"1234567893\"},"
            + "\"procedureCodes\":[{\"code\":\"95810\"}],\"dateOfService\":\"2026-03-20\"%s}";

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    /**
     * The controller carries no stereotype until the integrator wires it, so that the application
     * context does not try to build it before its dependencies exist. A standalone MockMvc only
     * registers handlers of an annotated controller, so the test registers it through this.
     */
    @RestController
    static class Wired extends DtrController {
        Wired(DtrService service, ScenarioRepository scenarios, EpaProperties epa) {
            super(service, scenarios, epa);
        }
    }

    private MockMvc mvc(String mode, OnyxDtrClient client) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        RunSupport support = new RunSupport(epa, TrackingProperties.defaults(), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
        DtrService service = new DtrService(epa, gateway, client, new FakeCodeList(), support);
        return TestMvc.standalone(new Wired(service, scenarios, epa));
    }

    private MockMvc mvc(String mode) {
        return mvc(mode, new MockOnyxDtrClient(scenarios, new MockExchanges(APP)));
    }

    private static String packageBody(String memberId, String extra) {
        return PACKAGE.formatted(memberId, extra);
    }

    private static JsonNode send(MockMvc mvc, String path, String body, int expectedStatus) throws Exception {
        String response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return JsonSafe.parse(response).node();
    }

    /** A request body from a map, so a questionnaire that is itself JSON text is escaped the way a browser escapes it. */
    private static String json(Map<String, Object> fields) {
        return JsonSafe.mapper().writeValueAsString(fields);
    }

    private static Map<String, Object> answer(String linkId, Object... values) {
        return Map.of("linkId", linkId, "values", List.of(values));
    }

    private static Map<String, Object> caseFields(String memberId) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("member", Map.of("memberId", memberId, "lineOfBusiness", "Medicare"));
        fields.put("provider", Map.of("npi", "1234567893"));
        fields.put("procedureCodes", List.of(Map.of("code", "95810")));
        return fields;
    }

    /** One step of the loop, posted the way the screen posts it after the given result. */
    private static String stepAfter(JsonNode previous, String memberId, Map<String, Object> extra) {
        Map<String, Object> fields = caseFields(memberId);
        fields.put("carry", JsonSafe.mapper().convertValue(previous.at("/nextStep/carry"), Map.class));
        JsonNode responses = previous.at("/detail/questionnaireResponses");
        if (responses.isArray() && !responses.isEmpty()) {
            fields.put("questionnaireResponse", responses.get(0).get("json").asString());
        } else {
            fields.put("questionnaire", previous.at("/detail/questionnaires/0/json").asString());
        }
        fields.putAll(extra);
        return json(fields);
    }

    // ---- POST /api/dtr/package ----

    @Test
    void aPackageFetchAnswers200WithTheFourAnswersTheKindAndTheLedgerReceipt() throws Exception {
        JsonNode result = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DOC-DTR", ",\"expected\":\"PACKAGE_LOADED\""), 200);

        assertThat(result.get("protocol").asString()).isEqualTo("DTR");
        assertThat(result.get("operation").asString()).isEqualTo("DTR_QUESTIONNAIRE_PACKAGE");
        assertThat(result.get("profile").asString()).isEqualTo("mock");
        // What happened, as an outcome apart from the test result.
        assertThat(result.at("/verdict/headline").asString()).isEqualTo("The questionnaire package was loaded with one questionnaire.");
        assertThat(result.at("/verdict/outcomeCode").asString()).isEqualTo("PACKAGE_LOADED");
        assertThat(result.at("/verdict/tone").asString()).isEqualTo("ACTION");
        // What was expected, and whether it matched.
        assertThat(result.at("/expectation/expected").asString()).isEqualTo("PACKAGE_LOADED");
        assertThat(result.at("/expectation/result").asString()).isEqualTo("pass");
        // What to do next, and what the next step starts from.
        assertThat(result.at("/nextStep/action").asString()).isEqualTo("SUBMIT_RESPONSE");
        assertThat(result.at("/nextStep/carry/runId/0").asString()).isEqualTo(result.get("runId").asString());
        // And that it is recorded, under the run id on screen.
        assertThat(result.at("/ledger/recorded").asBoolean()).isTrue();
        assertThat(result.at("/ledger/record/runId").asString()).isEqualTo(result.get("runId").asString());
        assertThat(ledger.only().runId()).isEqualTo(result.get("runId").asString());

        // The designed view: the questionnaire, its kind and the rule behind it, every item, the resources that came with it.
        assertThat(result.at("/detail/responseKind").asString()).isEqualTo("PARAMETERS");
        assertThat(result.at("/detail/kindRule").asString()).startsWith("A questionnaire is adaptive when");
        assertThat(result.at("/detail/questionnaires/0/kind").asString()).isEqualTo("STATIC");
        assertThat(result.at("/detail/questionnaires/0/title/value").asString()).isEqualTo("Sleep study criteria (placeholder)");
        assertThat(result.at("/detail/questionnaires/0/status/code").asString()).isEqualTo("active");
        assertThat(result.at("/detail/questionnaires/0/version/value").asString()).isEqualTo("1.0.0");
        assertThat(result.at("/detail/questionnaires/0/items")).hasSize(4);
        assertThat(result.at("/detail/questionnaires/0/items/2/answerOptions")).hasSize(3);
        assertThat(result.at("/detail/questionnaires/0/items/2/answerOptions/0/answerAs").asString()).isEqualTo("snoring");
        assertThat(result.at("/detail/questionnaires/0/items/0/enabled").asString()).isEqualTo("ENABLED");
        assertThat(result.at("/detail/resources")).extracting(resource -> resource.at("/resourceType/value").asString())
                .containsExactly("Library", "ValueSet");
        assertThat(result.at("/detail/sent").asBoolean()).isTrue();
        // The determination grid, the wire view and the environment flag are there to read.
        assertThat(result.at("/determination/0/system").asString()).isEqualTo("questionnaire-kind");
        assertThat(result.at("/wire/request/sent").asBoolean()).isFalse();
        assertThat(result.at("/wire/request/body").asString()).contains("\"Parameters\"");
        assertThat(result.at("/wire/response/status").asInt()).isEqualTo(200);
        assertThat(result.at("/environment/active").asBoolean()).isFalse();
        // A null is left out, never sent as null.
        assertThat(result.has("error")).isFalse();
        assertThat(result.has("chainId")).isFalse();
        assertThat(result.at("/detail").has("form")).isFalse();
        assertThat(result.at("/detail/questionnaires/0/items/0").has("enabledReason")).isFalse();
    }

    @Test
    void aVendorFaultOnAPackageFetchIsStillA200WithTheFaultDescribedInTheBody() throws Exception {
        JsonNode result = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DTR-ERR500", ""), 200);
        assertThat(result.at("/verdict/tone").asString()).isEqualTo("FAULT");
        assertThat(result.at("/error/kind").asString()).isEqualTo("VENDOR_ERROR");
        assertThat(result.at("/error/httpStatus").asInt()).isEqualTo(500);
        assertThat(result.at("/error/issues/0/code/value").asString()).isEqualTo("exception");
        assertThat(result.at("/wire/request/body").asString()).contains("\"Parameters\"");
        assertThat(send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DTR-TIMEOUT", ""), 200).at("/error/kind").asString())
                .isEqualTo("TIMEOUT");
        // A package that answered and cannot be used is a 200 too, and says what is wrong with it.
        JsonNode malformed = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DTR-MALFORMED", ""), 200);
        assertThat(malformed.at("/verdict/outcomeCode").asString()).isEqualTo("MALFORMED_QUESTIONNAIRE");
        assertThat(malformed.at("/error/kind").asString()).isEqualTo("MALFORMED_RESPONSE");
        // Not found is an answer about test data, and no questionnaire is an answer that says nothing: neither has an error panel.
        assertThat(send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DTR-NOMEM", ""), 200).has("error")).isFalse();
        JsonNode empty = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DTR-EMPTY", ""), 200);
        assertThat(empty.has("error")).isFalse();
        assertThat(empty.at("/verdict/outcomeCode").asString()).isEqualTo("NO_QUESTIONNAIRE");
        assertThat(empty.at("/verdict/tone").asString()).isEqualTo("NO_ANSWER");
    }

    @Test
    void aPackageFormThatFailsValidationIs422NamingEachFieldAndNothingIsRecorded() throws Exception {
        MockMvc mvc = mvc("mock");
        mvc.perform(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":{\"npi\":\"1234567890\"},\"procedureCodes\":[]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "member.memberId", "provider.npi", "procedureCodes")));
        // A field this tool does not know is refused by name, where it would otherwise be dropped.
        mvc.perform(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON)
                        .content(packageBody("P32H-DOC-DTR", ",\"questionnaire\":[\"https://example.org/p32h-placeholder/Questionnaire/x\"]")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("questionnaire"));
        // So is an expectation the tool cannot read, and a body that is not JSON.
        mvc.perform(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON).content(packageBody("P32H-DOC-DTR", ",\"expected\":\"APPROVED\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("expected"));
        mvc.perform(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON).content("{\"member\": "))
                .andExpect(status().isUnprocessableContent());
        assertThat(ledger.records()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void aTargetThatIsNotConfiguredIs409FromTheSharedErrorShape() throws Exception {
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "There is no url for the DTR questionnaire package."));
        mvc("live").perform(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON).content(packageBody("S123456789", "")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.kind").value("CONFIGURATION"));
        mvc("live").perform(post("/api/dtr/next-question").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questionnaire", "{\"resourceType\":\"Questionnaire\"}"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.kind").value("CONFIGURATION"));
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void aWriteTheGuardrailRefusesIs403WithTheWholeResultAsItsBody() throws Exception {
        gateway.failing(new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                "Writes to the production environment Onyx Production are blocked.", "Unlock writes on the Config tab, or switch environment.",
                new ResolvedUrl("{{baseUrl}}/Claim/$submit", "https://onyx.example.invalid/Claim/$submit",
                        "https://onyx.example.invalid/Claim/$submit", List.of(), List.of(), "PAS submit sample"), "POST", "PAS submit sample"));
        JsonNode result = send(mvc("live"), "/api/dtr/package", packageBody("S123456789", ""), 403);
        // A run id and a receipt tell the browser this is a result and not the bare error shape.
        assertThat(result.get("runId").asString()).startsWith("run-");
        assertThat(result.get("operation").asString()).isEqualTo("DTR_QUESTIONNAIRE_PACKAGE");
        assertThat(result.at("/verdict/outcomeCode").asString()).isEqualTo("BLOCKED");
        assertThat(result.at("/expectation/result").asString()).isEqualTo("unverified");
        assertThat(result.at("/nextStep/action").asString()).isEqualTo("UNLOCK_OR_SWITCH");
        assertThat(result.at("/error/kind").asString()).isEqualTo("BLOCKED");
        assertThat(result.at("/wire/request/sent").asBoolean()).isFalse();
        assertThat(result.at("/ledger/recorded").asBoolean()).isTrue();
        assertThat(result.has("detail")).isFalse();
        assertThat(ledger.only().outcome()).isEqualTo("BLOCKED");
        // The same refusal on the loop, and on the two previews, which answer 200 and say it is blocked.
        JsonNode step = send(mvc("live"), "/api/dtr/next-question", json(Map.of("questionnaire", "{\"resourceType\":\"Questionnaire\"}")), 403);
        assertThat(step.get("operation").asString()).isEqualTo("DTR_NEXT_QUESTION");
        assertThat(send(mvc("live"), "/api/dtr/package/preview", packageBody("S123456789", ""), 200).get("blocked").asString())
                .startsWith("Writes to the production environment");
        assertThat(ledger.records()).hasSize(2);
    }

    @Test
    void aFailureOfThisToolAfterTheCallIs500WithTheRunIdAndTheWireView() throws Exception {
        // A parsed body that fails the moment it is read, standing in for a defect in this tool's own reading.
        ObjectNode exploding = new ObjectNode(JsonNodeFactory.instance) {
            @Override
            public JsonNode get(String propertyName) {
                throw new IllegalStateException("the reading exploded on Placeholder-Surname");
            }
        };
        VendorExchange exchange = TestExchanges.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, "{\"resourceType\":\"Parameters\"}", true)
                .withJson(exploding);
        OnyxDtrClient failing = new OnyxDtrClient() {
            @Override
            public VendorExchange questionnairePackage(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }

            @Override
            public VendorExchange nextQuestion(PreparedCall call, ResolvedText body, MockSelection selection) {
                return exchange;
            }
        };
        JsonNode result = send(mvc("mock", failing), "/api/dtr/package", packageBody("S123456789", ""), 500);
        assertThat(result.get("runId").asString()).isEqualTo(ledger.only().runId());
        assertThat(result.at("/verdict/outcomeCode").asString()).isEqualTo("TOOL_ERROR");
        assertThat(result.at("/error/kind").asString()).isEqualTo("INTERNAL");
        assertThat(result.at("/error/reference").asString()).isNotBlank();
        assertThat(result.at("/wire/response/body").asString()).contains("Parameters");
        assertThat(result.toString()).doesNotContain("Placeholder-Surname").doesNotContain("exploded");
    }

    @Test
    void theSessionsUnlockIsReadFromTheSessionAndNeverFromTheRequest() throws Exception {
        ProdUnlock unlock = TestPreparedCalls.unlock("Onyx Production", 0L);
        MockHttpServletRequest holder = new MockHttpServletRequest();
        SessionUnlock.store(holder, unlock);
        MockHttpSession session = (MockHttpSession) holder.getSession();
        mvc("mock").perform(post("/api/dtr/package").session(session).contentType(MediaType.APPLICATION_JSON)
                .content(packageBody("P32H-DOC-DTR", ""))).andExpect(status().isOk());
        assertThat(gateway.lastIntent().unlock()).isSameAs(unlock);
        // Without the session there is none, and nothing in a body can stand in for one.
        mvc("mock").perform(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON).content(packageBody("P32H-DOC-DTR", "")))
                .andExpect(status().isOk());
        assertThat(gateway.lastIntent().unlock()).isNull();
        mvc("mock").perform(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON)
                        .content(packageBody("P32H-DOC-DTR", ",\"unlock\":{\"environmentName\":\"Onyx Production\",\"epoch\":0}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("unlock"));
    }

    // ---- POST /api/dtr/package/preview ----

    @Test
    void aPackagePreviewShowsTheTargetAndTheBodyAndRecordsNothing() throws Exception {
        gateway.url(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, "{{baseUrl}}/Questionnaire/$questionnaire-package")
                .variable("baseUrl", "https://onyx.example.invalid/fhir");
        JsonNode preview = send(mvc("mock"), "/api/dtr/package/preview", packageBody("S12345678   01", ""), 200);
        assertThat(preview.get("operation").asString()).isEqualTo("DTR_QUESTIONNAIRE_PACKAGE");
        assertThat(preview.at("/url/template").asString()).isEqualTo("{{baseUrl}}/Questionnaire/$questionnaire-package");
        assertThat(preview.at("/url/display").asString()).isEqualTo("https://onyx.example.invalid/fhir/Questionnaire/$questionnaire-package");
        // The value to send is never serialised. Only the display form is.
        assertThat(preview.at("/url").has("value")).isFalse();
        assertThat(preview.get("method").asString()).isEqualTo("POST");
        assertThat(preview.at("/memberId/sent").asString()).isEqualTo("S1234567801");
        assertThat(preview.get("body").asString()).contains("\"Parameters\"").contains("S1234567801");
        assertThat(preview.get("notes").isArray()).isTrue();
        assertThat(preview.has("blocked")).isFalse();
        assertThat(ledger.records()).isEmpty();
        // A preview is validated like the run it previews.
        mvc("mock").perform(post("/api/dtr/package/preview").contentType(MediaType.APPLICATION_JSON).content("{\"procedureCodes\":[]}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"));
        mvc("mock").perform(post("/api/dtr/package/preview").contentType(MediaType.APPLICATION_JSON)
                        .content(packageBody("S123456789", ",\"expectd\":\"PACKAGE_LOADED\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("expectd"));
    }

    // ---- POST /api/dtr/next-question ----

    @Test
    void theLoopRunsThroughTheEndpointsWithNothingKeptOnTheServerBetweenSteps() throws Exception {
        // A new MockMvc, and so a new controller and service, for every call: whatever a step needs came back in the result before it.
        JsonNode fetched = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DTR-ADAPT", ",\"chainId\":\"new\""), 200);
        assertThat(fetched.at("/nextStep/action").asString()).isEqualTo("ANSWER_NEXT_QUESTION");
        assertThat(fetched.at("/detail/questionnaires/0/kind").asString()).isEqualTo("ADAPTIVE");
        String chain = fetched.get("chainId").asString();

        JsonNode first = send(mvc("mock"), "/api/dtr/next-question", stepAfter(fetched, "P32H-DTR-ADAPT", Map.of("chainId", chain)), 200);
        assertThat(first.get("operation").asString()).isEqualTo("DTR_NEXT_QUESTION");
        assertThat(first.at("/verdict/outcomeCode").asString()).isEqualTo("NEXT_QUESTION");
        // The question count, the answer set, the completion status, what is asked and what changed, at every step.
        assertThat(first.at("/detail/form/questionCount").asInt()).isEqualTo(1);
        assertThat(first.at("/detail/form/answeredCount").asInt()).isZero();
        assertThat(first.at("/detail/form/status/code").asString()).isEqualTo("in-progress");
        assertThat(first.at("/detail/form/complete").asBoolean()).isFalse();
        assertThat(first.at("/detail/form/asked/0/linkId").asString()).isEqualTo("mri-1-therapy");
        assertThat(first.at("/detail/form/asked/0/text").asString()).isEqualTo("Has the member completed a course of conservative therapy?");
        assertThat(first.at("/detail/form/changes/appeared/0/linkId").asString()).isEqualTo("mri-1-therapy");
        assertThat(first.at("/detail/questionnaires/0/items/0/answerOptions/1/answerAs").asString()).isEqualTo("no");

        JsonNode second = send(mvc("mock"), "/api/dtr/next-question", stepAfter(first, "P32H-DTR-ADAPT",
                Map.of("chainId", chain, "answers", List.of(answer("mri-1-therapy", "no")))), 200);
        assertThat(second.at("/detail/form/questionCount").asInt()).isEqualTo(2);
        assertThat(second.at("/detail/form/answers/0/linkId").asString()).isEqualTo("mri-1-therapy");
        assertThat(second.at("/detail/form/answers/0/values/0").asString()).isEqualTo("No (no)");
        assertThat(second.at("/detail/form/asked/0/linkId").asString()).isEqualTo("mri-2-red-flag");
        assertThat(second.at("/nextStep/carry").has("questionnaireResponse")).isFalse();

        // A boolean posted as a JSON boolean, not as text.
        JsonNode third = send(mvc("mock"), "/api/dtr/next-question", stepAfter(second, "P32H-DTR-ADAPT",
                Map.of("chainId", chain, "answers", List.of(answer("mri-2-red-flag", false)), "expected", "RESPONSE_COMPLETED")), 200);
        assertThat(third.at("/verdict/outcomeCode").asString()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(third.at("/expectation/result").asString()).isEqualTo("pass");
        assertThat(third.at("/detail/form/complete").asBoolean()).isTrue();
        assertThat(third.at("/detail/form/status/code").asString()).isEqualTo("completed");
        assertThat(third.at("/nextStep/action").asString()).isEqualTo("START_PAS");
        JsonNode completed = JsonSafe.parse(third.at("/nextStep/carry/questionnaireResponse/0").asString()).node();
        assertThat(completed.get("status").asString()).isEqualTo("completed");
        assertThat(completed.at("/item/1/answer/0/valueBoolean").asBoolean()).isFalse();
        assertThat(third.at("/nextStep/carry/runId/0").asString()).isEqualTo(fetched.get("runId").asString());

        assertThat(ledger.records()).extracting(RunRecord::chainId).containsOnly(chain);
        assertThat(ledger.records()).extracting(RunRecord::operation).containsExactly(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE,
                EpaOperation.DTR_NEXT_QUESTION, EpaOperation.DTR_NEXT_QUESTION, EpaOperation.DTR_NEXT_QUESTION);
        assertThat(ledger.records().subList(1, 4)).allSatisfy(record ->
                assertThat(record.correlation()).containsEntry(RunRecord.CORR_RUN_ID, fetched.get("runId").asString()));
    }

    @Test
    void aStepOfTheLoopThatFailsValidationIs422AndAMisspeltPropertyIsNamedAtAnyDepth() throws Exception {
        JsonNode fetched = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DTR-ADAPT", ""), 200);
        JsonNode first = send(mvc("mock"), "/api/dtr/next-question", stepAfter(fetched, "P32H-DTR-ADAPT", Map.of()), 200);
        int recorded = ledger.records().size();
        MockMvc mvc = mvc("mock");
        // Nothing to answer, and an answer to a question the questionnaire does not ask.
        mvc.perform(post("/api/dtr/next-question").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("questionnaire"));
        mvc.perform(post("/api/dtr/next-question").contentType(MediaType.APPLICATION_JSON)
                        .content(stepAfter(first, "P32H-DTR-ADAPT", Map.of("answers", List.of(answer("mri-9", "yes"))))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("answers[0].linkId"));
        // "questionaire" for "questionnaire", at the top of the request.
        mvc.perform(post("/api/dtr/next-question").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questionaire", "{\"resourceType\":\"Questionnaire\"}"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[*].field").value(org.hamcrest.Matchers.hasItem("questionaire")));
        // "value" for "values", inside an answer.
        String misspeltInside = mvc.perform(post("/api/dtr/next-question").contentType(MediaType.APPLICATION_JSON)
                        .content(stepAfter(first, "P32H-DTR-ADAPT", Map.of("answers", List.of(Map.of("linkId", "mri-1-therapy", "value", List.of("no")))))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andReturn().getResponse().getContentAsString();
        assertThat(misspeltInside).contains("\"value\"").contains("does not know the field");
        // A scenario the mock has no step for is the tester's to correct, and says which scenario and which step.
        mvc.perform(post("/api/dtr/next-question").contentType(MediaType.APPLICATION_JSON)
                        .content(stepAfter(first, "P32H-DTR-ADAPT", Map.of("scenarioId", "dtr-01-static-linear"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
        assertThat(ledger.records()).hasSize(recorded);
    }

    @Test
    void aNextQuestionPreviewShowsTheResponseThatWouldBeSentAndRecordsNothing() throws Exception {
        JsonNode fetched = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DTR-ADAPT", ""), 200);
        JsonNode preview = send(mvc("mock"), "/api/dtr/next-question/preview", stepAfter(fetched, "P32H-DTR-ADAPT", Map.of()), 200);
        assertThat(preview.get("operation").asString()).isEqualTo("DTR_NEXT_QUESTION");
        assertThat(preview.get("method").asString()).isEqualTo("POST");
        JsonNode body = JsonSafe.parse(preview.get("body").asString()).node();
        assertThat(body.get("resourceType").asString()).isEqualTo("QuestionnaireResponse");
        assertThat(body.at("/contained/0/resourceType").asString()).isEqualTo("Questionnaire");
        assertThat(ledger.records()).hasSize(1);
        mvc("mock").perform(post("/api/dtr/next-question/preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void aStepOfTheLoopCanAskForTheResponseToGoInsideAParametersResource() throws Exception {
        JsonNode fetched = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DTR-ADAPT", ""), 200);
        // The property is read from the body like any other, and the body sent and the body previewed are the same.
        String wrapped = stepAfter(fetched, "P32H-DTR-ADAPT", Map.of("wrapInParameter", "questionnaire-response"));
        JsonNode preview = send(mvc("mock"), "/api/dtr/next-question/preview", wrapped, 200);
        JsonNode step = send(mvc("mock"), "/api/dtr/next-question", wrapped, 200);
        JsonNode sent = JsonSafe.parse(step.at("/wire/request/body").asString()).node();
        assertThat(sent).isEqualTo(JsonSafe.parse(preview.get("body").asString()).node());
        assertThat(sent.get("resourceType").asString()).isEqualTo("Parameters");
        assertThat(sent.at("/parameter/0/name").asString()).isEqualTo("questionnaire-response");
        assertThat(sent.at("/parameter/0/resource/resourceType").asString()).isEqualTo("QuestionnaireResponse");
        assertThat(step.at("/verdict/outcomeCode").asString()).isEqualTo("NEXT_QUESTION");
        // A name that is not a parameter name is a 422 that names the field, and nothing more is recorded.
        int recorded = ledger.records().size();
        mvc("mock").perform(post("/api/dtr/next-question").contentType(MediaType.APPLICATION_JSON)
                        .content(stepAfter(fetched, "P32H-DTR-ADAPT", Map.of("wrapInParameter", "two words"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("wrapInParameter"));
        assertThat(ledger.records()).hasSize(recorded);
    }

    // ---- POST /api/dtr/response ----

    @Test
    void theResponseStepAnswers200WhetherTheResponseIsCompleteOrARequiredQuestionIsLeftUnanswered() throws Exception {
        JsonNode fetched = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DOC-DTR", ""), 200);
        Map<String, Object> fields = caseFields("P32H-DOC-DTR");
        fields.put("questionnaire", fetched.at("/detail/questionnaires/0/json").asString());
        fields.put("carry", JsonSafe.mapper().convertValue(fetched.at("/nextStep/carry"), Map.class));

        // One required question left out: a 200, an outcome of its own, and the question named.
        fields.put("answers", List.of(answer("sleep-1", false), answer("sleep-3", "snoring")));
        JsonNode unanswered = send(mvc("mock"), "/api/dtr/response", json(fields), 200);
        assertThat(unanswered.get("operation").asString()).isEqualTo("DTR_RESPONSE");
        assertThat(unanswered.at("/verdict/outcomeCode").asString()).isEqualTo("REQUIRED_UNANSWERED");
        assertThat(unanswered.at("/detail/form/complete").asBoolean()).isFalse();
        assertThat(unanswered.at("/detail/form/requiredUnanswered/0/linkId").asString()).isEqualTo("sleep-2");
        assertThat(unanswered.at("/detail/form/requiredUnanswered/0/text").asString()).isEqualTo("Epworth Sleepiness Scale score");
        assertThat(unanswered.at("/nextStep/action").asString()).isEqualTo("SUBMIT_RESPONSE");
        assertThat(unanswered.at("/nextStep/carry").has("questionnaireResponse")).isFalse();
        // Nothing was sent, and the body says so: no wire view, no timing, and a detail marked as not sent.
        assertThat(unanswered.has("wire")).isFalse();
        assertThat(unanswered.has("timing")).isFalse();
        assertThat(unanswered.has("error")).isFalse();
        assertThat(unanswered.at("/detail/sent").asBoolean()).isFalse();
        assertThat(unanswered.at("/detail/responseKind").asString()).isEqualTo("NOT_SENT");
        assertThat(unanswered.at("/verdict/supporting/0").asString()).startsWith("Nothing was sent to the service for this step.");
        assertThat(unanswered.at("/ledger/recorded").asBoolean()).isTrue();

        // Every required question answered: completed, and the response is what PAS is handed.
        fields.put("answers", List.of(answer("sleep-1", false), answer("sleep-2", 14), answer("sleep-3", "snoring")));
        fields.put("expected", "RESPONSE_COMPLETED");
        JsonNode complete = send(mvc("mock"), "/api/dtr/response", json(fields), 200);
        assertThat(complete.at("/verdict/outcomeCode").asString()).isEqualTo("RESPONSE_COMPLETED");
        assertThat(complete.at("/expectation/result").asString()).isEqualTo("pass");
        assertThat(complete.at("/nextStep/action").asString()).isEqualTo("START_PAS");
        JsonNode handedOn = JsonSafe.parse(complete.at("/nextStep/carry/questionnaireResponse/0").asString()).node();
        assertThat(handedOn.get("status").asString()).isEqualTo("completed");
        assertThat(handedOn.at("/item/1/answer/0/valueInteger").asInt()).isEqualTo(14);
        assertThat(handedOn.at("/item/2/answer/0/valueCoding/display").asString()).isEqualTo("Loud snoring");
        assertThat(complete.at("/detail/questionnaireResponses/0/status/code").asString()).isEqualTo("completed");

        assertThat(ledger.records()).extracting(RunRecord::operation).containsExactly(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE,
                EpaOperation.DTR_RESPONSE, EpaOperation.DTR_RESPONSE);
        assertThat(ledger.records()).extracting(RunRecord::outcome).containsExactly("PACKAGE_LOADED", "REQUIRED_UNANSWERED", "RESPONSE_COMPLETED");
    }

    @Test
    void aResponseStepThatFailsValidationIs422AndAConfigurationThatChangedIs409() throws Exception {
        JsonNode fetched = send(mvc("mock"), "/api/dtr/package", packageBody("P32H-DOC-DTR", ""), 200);
        String questionnaire = fetched.at("/detail/questionnaires/0/json").asString();
        MockMvc mvc = mvc("mock");
        mvc.perform(post("/api/dtr/response").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("questionnaire"));
        // An answer of the wrong type is refused by field, and the message does not repeat the answer.
        String wrongType = mvc.perform(post("/api/dtr/response").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questionnaire", questionnaire, "answers", List.of(answer("sleep-2", "a clinical remark"))))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("answers[0].values[0]"))
                .andReturn().getResponse().getContentAsString();
        assertThat(wrongType).doesNotContain("clinical remark");
        // "answer" for "answers": dropped silently, the response would be built with no answers at all.
        mvc.perform(post("/api/dtr/response").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questionnaire", questionnaire, "answer", List.of(answer("sleep-2", 14))))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("answer"));
        // This step sends nothing, so it has no scenario to choose, and says so.
        mvc.perform(post("/api/dtr/response").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questionnaire", questionnaire, "scenarioId", "dtr-01-static-linear"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
        assertThat(ledger.records()).hasSize(1);

        // The screen was drawn under a configuration that has since changed.
        gateway.changeConfiguration();
        mvc.perform(post("/api/dtr/response").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questionnaire", questionnaire, "configEpoch", 0))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.kind").value("CONFIGURATION"));
        mvc.perform(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON).content(packageBody("P32H-DOC-DTR", ",\"configEpoch\":0")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.kind").value("CONFIGURATION"));
        assertThat(ledger.records()).hasSize(1);
    }

    // ---- GET /api/dtr/scenarios ----

    @Test
    void theScenarioListIsOfferedOnMockAndIsEmptyOnLiveWhereTheVocabularyIsStillServed() throws Exception {
        String mock = mvc("mock").perform(get("/api/dtr/scenarios")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.scenarios.length()").value(15))
                .andExpect(jsonPath("$.scenarios[1].sentinelMemberId").value("P32H-DTR-ADAPT"))
                .andExpect(jsonPath("$.scenarios[6].expectedOutcome").value("NO_QUESTIONNAIRE"))
                .andExpect(jsonPath("$.problems.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode list = JsonSafe.parse(mock).node();
        // The outcomes and element names a tester can declare, so the browser keeps no copy of them.
        assertThat(list.get("outcomes")).anySatisfy(option -> {
            assertThat(option.get("code").asString()).isEqualTo("NO_QUESTIONNAIRE");
            assertThat(option.get("tone").asString()).isEqualTo("NO_ANSWER");
        });
        assertThat(list.get("outcomes")).anySatisfy(option -> assertThat(option.get("code").asString()).isEqualTo("NOTHING_REQUIRED"));
        assertThat(list.get("outcomes")).anySatisfy(option -> assertThat(option.get("code").asString()).isEqualTo("BLOCKED"));
        assertThat(list.get("elements").get(0).asString()).isEqualTo("questionnaire-kind");

        mvc("live").perform(get("/api/dtr/scenarios")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").isNotEmpty())
                .andExpect(jsonPath("$.scenarios.length()").value(0))
                .andExpect(jsonPath("$.outcomes.length()").value(20))
                .andExpect(jsonPath("$.elements.length()").value(7));
        // And a scenario cannot be forced on live, on either operation that makes a call.
        mvc("live").perform(post("/api/dtr/package").contentType(MediaType.APPLICATION_JSON)
                        .content(packageBody("S123456789", ",\"scenarioId\":\"dtr-07-no-questionnaire\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
        mvc("live").perform(post("/api/dtr/next-question").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("questionnaire", "{\"resourceType\":\"Questionnaire\"}", "scenarioId", "dtr-02-adaptive-branching"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
    }
}
