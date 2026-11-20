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
        // The issue's empty expression is in the issues table as returned empty, and not listed again as not interpreted.
        assertThat(result.at("/error/issues/0/expressionPresent").asBoolean()).isTrue();
        assertThat(result.at("/error/issues/0/expression")).isEmpty();
        assertThat(result.get("additionalFields").toString()).doesNotContain("expression");
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
                .andExpect(jsonPath("$.elements.length()").value(7))
                // The words for every next action, so the History tab can say what a run's next step was.
                .andExpect(jsonPath("$.nextActions.length()").value(org.p32h.interop.epa.pave.model.NextAction.values().length))
                .andExpect(jsonPath("$.nextActions[0].code").value("START_DTR"))
                .andExpect(jsonPath("$.nextActions[0].label").value("Answer the payer's questionnaire (DTR)."));
        // And a scenario cannot be forced on live.
        mvc("live").perform(post("/api/crd").contentType(MediaType.APPLICATION_JSON)
                        .content(body("S123456789", ",\"scenarioId\":\"crd-02-no-auth\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("scenarioId"));
    }

    // ---- POST /api/crd/compare ----

    /** A request shaped the way a vendor might shape it, with a placeholder NPI that passes the check digit. */
    private static String vendorSample() {
        try (java.io.InputStream in = CrdControllerTest.class.getResourceAsStream("/compare/vendor-shaped-order-sign.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** The posted form as JSON: the pasted request in rawBody, and whatever else the form holds. */
    private static String comparing(String pasted, String otherFields) {
        return "{\"rawBody\":" + JsonSafe.mapper().writeValueAsString(pasted) + otherFields + "}";
    }

    private JsonNode compare(MockMvc mvc, String body, int expectedStatus) throws Exception {
        String response = mvc.perform(post("/api/crd/compare").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return JsonSafe.parse(response).node();
    }

    private void assertNothingWasSentOrRecorded() {
        assertThat(ledger.records()).as("ledger lines").isEmpty();
        assertThat(gateway.intents()).as("calls the gateway was asked to prepare").isEmpty();
    }

    @Test
    void aComparisonAnswers200WithBothRequestsAndEveryDifferenceExplained() throws Exception {
        JsonNode answer = compare(mvc("mock"), comparing(vendorSample(), ""), 200);

        assertThat(answer.get("comparable").asBoolean()).isTrue();
        assertThat(answer.has("whyNot")).isFalse();
        // authoredOn, which this tool now writes as the moment it builds the request (CRD 2.0.1), differs on every call.
        assertThat(answer.get("headline").asString()).isEqualTo("The two requests differ in 34 places: 25 in how the request is built, 2 in the "
                + "case they describe, 4 in labels, 2 that differ on every call, and 1 that this tool does not classify.");
        assertThat(answer.get("hook").asString()).isEqualTo("order-sign");
        // Both texts, each as indented JSON.
        assertThat(answer.get("toolBody").asString()).startsWith("{\n  \"hook\" : \"order-sign\",");
        assertThat(answer.get("yourBody").asString()).startsWith("{\n  \"hookInstance\" : ");
        // Every group is counted, the one that answers the tester's question first.
        assertThat(answer.get("counts")).hasSize(5);
        assertThat(answer.at("/counts/0/group").asString()).isEqualTo("BUILD");
        assertThat(answer.at("/counts/0/label").asString()).isEqualTo("How the request is built");
        assertThat(answer.at("/counts/0/count").asInt()).isEqualTo(25);
        assertThat(answer.at("/counts/0/description").asString()).isNotBlank();
        // The caveat is said once, as its own component, and not again among the notes.
        assertThat(answer.at("/caveat").asString()).startsWith("This comparison shows where the two requests differ. It cannot show which one "
                + "the service expects");
        assertThat(answer.get("notes").toString()).doesNotContain("This comparison shows where the two requests differ");
        // One difference, in full.
        assertThat(answer.get("differences")).hasSize(34);
        JsonNode first = answer.at("/differences/0");
        assertThat(first.get("path").asString()).isEqualTo("hookInstance");
        assertThat(first.get("kind").asString()).isEqualTo("DIFFERENT_VALUE");
        assertThat(first.get("kindLabel").asString()).isEqualTo("A different value");
        assertThat(first.get("group").asString()).isEqualTo("EVERY_CALL");
        assertThat(first.at("/toolValue/presence").asString()).isEqualTo("VALUE");
        assertThat(first.at("/toolValue/value").asString()).isEqualTo("(a new id is generated when the request is sent)");
        assertThat(first.at("/yourValue/value").asString()).isEqualTo("5f0e8a57-2d1c-4c1e-9d0e-3a8f6b2c7d41");
        assertThat(first.get("toolType").asString()).isEqualTo("string");
        assertThat(first.get("toolPointer").asString()).isEqualTo("/hookInstance");
        assertThat(first.get("yourPointer").asString()).isEqualTo("/hookInstance");
        assertThat(first.get("what").asString()).isNotBlank();
        assertThat(first.get("explanation").asString()).isNotBlank();
        // Where the case came from: nothing was on the form, so all of it was read from the pasted request.
        assertThat(answer.at("/caseSource/1/field").asString()).isEqualTo("member.memberId");
        assertThat(answer.at("/caseSource/1/source").asString()).isEqualTo("YOUR_REQUEST");
        assertThat(answer.at("/caseSource/1/path").asString()).isEqualTo("prefetch.coverageRequest.subscriberId");
        assertThat(answer.at("/caseSource/1/value/value").asString()).isEqualTo("S123456789");
        assertThat(answer.at("/notFound/0/field").asString()).isEqualTo("member.lineOfBusiness");
        // No run id and no ledger receipt: a comparison is not a run.
        assertThat(answer.has("runId")).isFalse();
        assertThat(answer.has("ledger")).isFalse();
        assertNothingWasSentOrRecorded();
    }

    @Test
    void aComparisonWorksOnLiveWithNothingConfiguredAndIsNeverACall() throws Exception {
        gateway.failing(new EndpointNotConfiguredException(EpaOperation.CRD_ORDER_SIGN, "There is no url for CRD order-sign."));
        JsonNode answer = compare(mvc("live"), comparing(vendorSample(), ",\"member\":{\"memberId\":\"S123456789\",\"lastName\":\"Form-Surname\"}"), 200);
        assertThat(answer.get("comparable").asBoolean()).isTrue();
        // The form's value was used, and is reported as the form's.
        assertThat(answer.at("/caseSource/3/field").asString()).isEqualTo("member.lastName");
        assertThat(answer.at("/caseSource/3/source").asString()).isEqualTo("FORM");
        assertThat(answer.at("/caseSource/3").has("path")).isFalse();
        assertNothingWasSentOrRecorded();
    }

    @Test
    void aPairThatCannotBeComparedFieldByFieldIsAnAnswerWith200ThatSaysWhy() throws Exception {
        MockMvc mvc = mvc("mock");
        // Not JSON: where parsing stopped, and the text as it was written.
        String notJson = "{\"hook\": \"order-sign\", \"note\": Sample-Member}";
        JsonNode unreadable = compare(mvc, comparing(notJson, ""), 200);
        assertThat(unreadable.get("comparable").asBoolean()).isFalse();
        assertThat(unreadable.get("whyNot").asString()).isEqualTo("The two cannot be compared field by field, because your request could not be "
                + "read as JSON (line 1, column 32).");
        assertThat(unreadable.get("headline").asString()).isEqualTo("The two requests were not compared field by field.");
        assertThat(unreadable.get("yourBody").asString()).isEqualTo(notJson);
        assertThat(unreadable.has("toolBody")).isFalse();
        assertThat(unreadable.has("hook")).isFalse();
        assertThat(unreadable.get("differences")).isEmpty();
        assertThat(unreadable.get("counts")).isEmpty();
        // Nothing of the text around the place parsing stopped is quoted in any sentence.
        assertThat(unreadable.get("whyNot").asString() + unreadable.get("notes")).doesNotContain("Sample-Member");

        // JSON that is not an object, and an object with nothing in it.
        assertThat(compare(mvc, comparing("[1, 2]", ""), 200).get("whyNot").asString()).isEqualTo("The two cannot be compared field by field, "
                + "because your request is JSON but not a JSON object: it is a list.");
        assertThat(compare(mvc, comparing("\"order-sign\"", ""), 200).get("whyNot").asString()).endsWith("it is a text.");
        assertThat(compare(mvc, comparing("{}", ""), 200).get("whyNot").asString()).isEqualTo("The two cannot be compared field by field, because "
                + "your request is an empty JSON object.");

        // A form and a request that together give no member ID, no NPI and no code.
        JsonNode nothingToBuild = compare(mvc, comparing("{\"hook\":\"order-sign\",\"context\":{\"patientId\":\"S123456789\"}}", ""), 200);
        assertThat(nothingToBuild.get("comparable").asBoolean()).isFalse();
        assertThat(nothingToBuild.get("whyNot").asString()).isEqualTo("This tool could not build its own request to compare with yours: the form "
                + "has no member ID, no NPI, and no procedure code, and they could not be read from your request.");
        assertThat(nothingToBuild.get("yourBody").asString()).startsWith("{\n  \"hook\" : \"order-sign\"");
        assertThat(nothingToBuild.at("/notFound/0/field").asString()).isEqualTo("member.memberId");

        // The same request with the case on the form is compared.
        JsonNode withForm = compare(mvc, comparing("{\"hook\":\"order-sign\",\"context\":{\"patientId\":\"S123456789\"}}",
                ",\"member\":{\"memberId\":\"S123456789\"},\"provider\":{\"npi\":\"1234567893\"},\"procedureCodes\":[{\"code\":\"95810\"}]"), 200);
        assertThat(withForm.get("comparable").asBoolean()).isTrue();
        assertNothingWasSentOrRecorded();
    }

    @Test
    void aComparisonThatCannotBeAskedIs422NamingTheField() throws Exception {
        MockMvc mvc = mvc("mock");
        // Nothing pasted.
        for (String blank : List.of("{}", "{\"rawBody\":\"\"}", "{\"rawBody\":\"  \",\"member\":{\"memberId\":\"S123456789\"}}")) {
            mvc.perform(post("/api/crd/compare").contentType(MediaType.APPLICATION_JSON).content(blank))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors.length()").value(1)).andExpect(jsonPath("$.fieldErrors[0].field").value("rawBody"));
        }
        // An NPI on the form with a wrong check digit, as for a run.
        mvc.perform(post("/api/crd/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(comparing(vendorSample(), ",\"provider\":{\"npi\":\"1234567890\"}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("provider.npi"));
        // A misspelt property is refused by name, where it would otherwise be dropped and the comparison made without it.
        mvc.perform(post("/api/crd/compare").contentType(MediaType.APPLICATION_JSON).content(comparing(vendorSample(), ",\"rawbody\":\"{}\"")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("rawbody"));
        mvc.perform(post("/api/crd/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(comparing(vendorSample(), ",\"member\":{\"memberID\":\"S123456789\"}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("memberID"));
        // A pasted text larger than 2 MB.
        mvc.perform(post("/api/crd/compare").contentType(MediaType.APPLICATION_JSON)
                        .content(comparing("{\"padding\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}", "")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("rawBody"));
        // A body that is not JSON at all, and a method this endpoint does not take.
        mvc.perform(post("/api/crd/compare").contentType(MediaType.APPLICATION_JSON).content("{\"rawBody\": "))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(get("/api/crd/compare")).andExpect(status().isMethodNotAllowed());
        assertNothingWasSentOrRecorded();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void nothingFromEitherRequestIsLoggedByAComparisonOrByAnyWayItCanFail(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        MockMvc mvc = mvc("mock");
        compare(mvc, comparing(vendorSample(), ",\"member\":{\"memberId\":\"S123456789\",\"lastName\":\"Form-Surname\",\"dateOfBirth\":\"1970-01-31\"}"), 200);
        compare(mvc, comparing("{\"name\": Sample-Member born 1968-04-12}", ""), 200);
        compare(mvc, comparing("{\"hook\":\"order-sign\",\"note\":\"Sample-Member\"}", ""), 200);
        compare(mvc, comparing(vendorSample(), ",\"provider\":{\"npi\":\"1234567890\"}"), 422);
        compare(mvc, comparing("", ",\"member\":{\"memberId\":\"S123456789\",\"lastName\":\"Form-Surname\"}"), 422);
        compare(mvc, "{\"rawBody\": \"Sample-Member", 422);
        // A failure inside this tool while it builds its own request: the code list is asked for a system and fails.
        codeList.failing(new IllegalStateException("code list exploded for Sample-Member"));
        JsonNode failure = compare(mvc, comparing(vendorSample().replace("\"system\": \"http://www.ama-assn.org/go/cpt\",", ""), ""), 500);
        assertThat(failure.get("kind").asString()).isEqualTo("INTERNAL");
        assertThat(failure.get("reference").asString()).isNotBlank();
        assertThat(failure.toString()).doesNotContain("Sample-Member").doesNotContain("code list exploded").doesNotContain("S123456789");

        // The failure is logged under its reference, as exception types and frames, and nothing else is logged at all.
        assertThat(output.getAll()).contains(failure.get("reference").asString()).contains("IllegalStateException");
        assertThat(output.getAll()).doesNotContain("Sample-Member").doesNotContain("Form-Surname").doesNotContain("S123456789")
                .doesNotContain("1968-04-12").doesNotContain("1970-01-31").doesNotContain("1000000087").doesNotContain("Robin")
                .doesNotContain("pat-8842").doesNotContain("hookInstance").doesNotContain("resourceType").doesNotContain("Placeholder Street")
                .doesNotContain("code list exploded");
        assertNothingWasSentOrRecorded();
    }
}
