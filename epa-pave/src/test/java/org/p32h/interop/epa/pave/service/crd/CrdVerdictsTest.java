package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CrdVerdictsTest {

    private static final String EXT = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private final MockOnyxCrdClient client = new MockOnyxCrdClient(new ScenarioRepository(APP), new MockExchanges(APP));
    private final CrdResponseMapper mapper = new CrdResponseMapper();

    private VendorExchange scenario(String sentinel) {
        return client.hook(TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN), ResolvedText.plain("{}"),
                MockSelection.of(null, sentinel, "1234567893"));
    }

    private VerdictView verdict(String sentinel) {
        VendorExchange exchange = scenario(sentinel);
        return CrdVerdicts.verdict(mapper.read(exchange), exchange);
    }

    private NextStepView next(String sentinel) {
        VendorExchange exchange = scenario(sentinel);
        return CrdVerdicts.nextStep(mapper.read(exchange), exchange);
    }

    private NextStepView next(int status, String body) {
        VendorExchange exchange = TestExchanges.crd(status, body);
        return CrdVerdicts.nextStep(mapper.read(exchange), exchange);
    }

    /** The supporting lines without the line that names the mock scenario, which every mock result starts with. */
    private static List<String> beyondTheScenarioLine(VerdictView verdict) {
        assertThat(verdict.supporting().get(0)).startsWith("Mock scenario ");
        return verdict.supporting().subList(1, verdict.supporting().size());
    }

    private static String one(String subExtensions) {
        return "{\"cards\":[],\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\","
                + "\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[" + subExtensions + "]}]}}]}";
    }

    @Test
    void theHeadlineForEachScenarioIsOnePlainSentence() {
        assertThat(verdict("P32H-PA-REQ").headline()).isEqualTo("Prior authorization is required.");
        assertThat(verdict("P32H-NO-AUTH").headline()).isEqualTo("Prior authorization is not required.");
        assertThat(verdict("P32H-PA-SAT").headline()).isEqualTo("Prior authorization is already satisfied.");
        assertThat(verdict("P32H-DOC-DTR").headline()).isEqualTo("Documentation is required.");
        assertThat(verdict("P32H-INFO").headline()).isEqualTo("The payer needs more information before it can answer.");
        assertThat(verdict("P32H-NOCOV").headline()).isEqualTo("This service is not a covered benefit.");
        assertThat(verdict("P32H-NOMEM").headline()).isEqualTo("The service does not recognise this member.");
        assertThat(verdict("P32H-INACTIVE").headline()).isEqualTo("Coverage is not active on the date of service.");
        assertThat(verdict("P32H-NOPROV").headline()).isEqualTo("The service does not recognise this provider.");
        assertThat(verdict("P32H-DELEG").headline()).isEqualTo("Prior authorization is required.");
        assertThat(verdict("P32H-BAD400").headline()).isEqualTo("The service did not accept the request as sent (HTTP 400).");
        assertThat(verdict("P32H-ERR500").headline()).isEqualTo("The service failed (HTTP 500).");
        assertThat(verdict("P32H-TIMEOUT").headline()).isEqualTo("The service did not answer in time.");
        assertThat(verdict("P32H-EMPTY").headline()).isEqualTo("The service answered but returned no determination.");
    }

    @Test
    void theDelegatedVendorIsCalledOutInTheVerdict() {
        assertThat(verdict("P32H-DELEG").supporting()).contains("It routes to the delegated vendor Evolent.");
        assertThat(next("P32H-DELEG").reason()).isEqualTo("Prior authorization is required and routes to Evolent.");
        assertThat(next("P32H-DELEG").carry()).containsEntry(NextStepView.CARRY_DELEGATED_VENDOR, List.of("Evolent"));
    }

    @Test
    void aMockResultSaysFirstWhichScenarioAnsweredAndHowItCameToBeTheOne() {
        // Without this line a default scenario's answer reads as an answer about the member on the form.
        VendorExchange byDefault = client.hook(TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN), ResolvedText.plain("{}"),
                MockSelection.of(null, "S123456789", "1234567893"));
        assertThat(CrdVerdicts.verdict(mapper.read(byDefault), byDefault).supporting().get(0)).isEqualTo(
                "Mock scenario crd-01-pa-required answered: the default, because no scenario was chosen and no sentinel matched.");
        assertThat(verdict("P32H-NO-AUTH").supporting().get(0))
                .isEqualTo("Mock scenario crd-02-no-auth answered: selected by the sentinel member ID P32H-NO-AUTH.");
        // An answer from a real service carries no such line.
        VendorExchange live = TestExchanges.crd(200, one("{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\"}"));
        assertThat(CrdVerdicts.verdict(mapper.read(live), live).supporting()).noneMatch(line -> line.startsWith("Mock scenario"));
    }

    @Test
    void theSupportingLinesComeFromTheCodesAndNeverFromCardWording() {
        VerdictView documentation = verdict("P32H-DOC-DTR");
        assertThat(beyondTheScenarioLine(documentation)).containsExactly("Prior authorization: prior authorization is needed.",
                "Clinical documentation is needed: send with the prior authorization request.",
                "A payer questionnaire is named for this service.");
        assertThat(beyondTheScenarioLine(verdict("P32H-PA-SAT"))).containsExactly("Existing authorization: PA-PLACEHOLDER-0001.");
        assertThat(beyondTheScenarioLine(verdict("P32H-INFO")))
                .containsExactly("The payer needs to know: who will perform the service; where the service will be performed.");
        // No sentence of the scenario's card text appears in the verdict.
        for (String line : documentation.supporting()) {
            assertThat(line).doesNotContain("Complete the payer questionnaire, then submit");
        }
    }

    @Test
    void nothingInAnyCrdVerdictReadsAsAClinicalDenial() {
        for (String sentinel : new String[] {"P32H-PA-REQ", "P32H-NO-AUTH", "P32H-PA-SAT", "P32H-DOC-DTR", "P32H-INFO", "P32H-NOCOV",
                "P32H-NOMEM", "P32H-INACTIVE", "P32H-NOPROV", "P32H-DELEG", "P32H-BAD400", "P32H-ERR500", "P32H-TIMEOUT", "P32H-EMPTY"}) {
            VerdictView verdict = verdict(sentinel);
            NextStepView next = next(sentinel);
            String all = (verdict.headline() + " " + String.join(" ", verdict.supporting()) + " " + next.headline() + " " + next.reason())
                    .toLowerCase(Locale.ROOT);
            assertThat(all).as(sentinel).doesNotContain("denied").doesNotContain("denial").doesNotContain("deny")
                    .doesNotContain("refus").doesNotContain("reject").doesNotContain("medical necessity").doesNotContain("not medically");
        }
    }

    @Test
    void theNextStepFollowsFromWhatTheResponseSaid() {
        // Documentation with a DTR link: DTR is next, already pointed at the questionnaire.
        NextStepView dtr = next("P32H-DOC-DTR");
        assertThat(dtr.action()).isEqualTo(NextAction.START_DTR);
        assertThat(dtr.headline()).isEqualTo("Next: answer the payer's questionnaire in DTR.");
        assertThat(dtr.first(NextStepView.CARRY_QUESTIONNAIRE)).endsWith("/Questionnaire/sleep-study-criteria");
        assertThat(dtr.first(NextStepView.CARRY_DTR_LINK_URL)).endsWith("/dtr/launch");
        assertThat(dtr.carry()).containsKey(NextStepView.CARRY_DTR_APP_CONTEXT);
        // The order the answer applies to goes with it, so DTR asks about the same order CRD did.
        assertThat(dtr.carry()).containsEntry(NextStepView.CARRY_ORDER, List.of("ServiceRequest/service-request-1"));

        assertThat(next("P32H-PA-REQ").action()).isEqualTo(NextAction.START_PAS);
        // No authorization needed: the flow ends, and that is itself a finding worth recording.
        NextStepView ends = next("P32H-NO-AUTH");
        assertThat(ends.action()).isEqualTo(NextAction.FLOW_ENDS);
        assertThat(ends.headline()).isEqualTo("The flow ends here. Record this result.");
        assertThat(ends.reason()).contains("prior authorization is not required");
        assertThat(next("P32H-PA-SAT").action()).isEqualTo(NextAction.FLOW_ENDS);
        assertThat(next("P32H-NOCOV").action()).isEqualTo(NextAction.FLOW_ENDS);
        assertThat(next("P32H-INACTIVE").action()).isEqualTo(NextAction.FLOW_ENDS);
        assertThat(next("P32H-INFO").action()).isEqualTo(NextAction.SUPPLY_INFORMATION);
        assertThat(next("P32H-NOMEM").action()).isEqualTo(NextAction.FIX_TEST_DATA);
        assertThat(next("P32H-NOPROV").action()).isEqualTo(NextAction.FIX_TEST_DATA);
        assertThat(next("P32H-BAD400").action()).isEqualTo(NextAction.FIX_REQUEST);
        assertThat(next("P32H-ERR500").action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(next("P32H-TIMEOUT").action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        // The service said nothing, so the tool does not pretend to know what comes next.
        assertThat(next("P32H-EMPTY").action()).isEqualTo(NextAction.REVIEW);
    }

    @Test
    void documentationWithNoQuestionnaireAndNoLinkGoesToPasNotToDtr() {
        VendorExchange exchange = TestExchanges.crd(200, one("{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},{\"url\":\"doc-needed\",\"valueCode\":\"clinical\"}"));
        NextStepView next = CrdVerdicts.nextStep(mapper.read(exchange), exchange);
        assertThat(mapper.read(exchange).outcome()).isEqualTo(CrdOutcome.DOCUMENTATION_REQUIRED);
        assertThat(next.action()).isEqualTo(NextAction.START_PAS);
        assertThat(next.reason()).contains("names no questionnaire");
    }

    @Test
    void everythingDtrNeedsIsCarriedAndNothingIsReducedToItsFirstValue() {
        String two = "{\"systemActions\":["
                + "{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\",\"extension\":[{\"url\":\"" + EXT
                + "\",\"extension\":[{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\"},{\"url\":\"coverage-assertion-id\",\"valueString\":\"a-1\"}]}]}},"
                + "{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-2\",\"extension\":[{\"url\":\"" + EXT
                + "\",\"extension\":[{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},{\"url\":\"doc-needed\",\"valueCode\":\"clinical\"},"
                + "{\"url\":\"coverage-assertion-id\",\"valueString\":\"a-2\"},"
                + "{\"url\":\"questionnaire\",\"valueCanonical\":\"https://example.org/Questionnaire/q1\"},"
                + "{\"url\":\"questionnaire\",\"valueCanonical\":\"https://example.org/Questionnaire/q2\"}]}]}}]}";
        VendorExchange exchange = TestExchanges.crd(200, two);
        CrdReading reading = mapper.read(exchange, List.of(new SentOrder("ServiceRequest/service-request-1", "95810"),
                new SentOrder("ServiceRequest/service-request-2", "95811")));
        NextStepView next = CrdVerdicts.nextStep(reading, exchange);
        assertThat(next.carry()).containsEntry(NextStepView.CARRY_QUESTIONNAIRE,
                List.of("https://example.org/Questionnaire/q1", "https://example.org/Questionnaire/q2"));
        // From the answer that asks for documentation only, so the first value is the one DTR needs.
        assertThat(next.carry()).containsEntry(NextStepView.CARRY_COVERAGE_ASSERTION_ID, List.of("a-2"));
        assertThat(next.carry()).containsEntry(NextStepView.CARRY_ORDER, List.of("ServiceRequest/service-request-2"));
        assertThat(next.carry()).containsEntry(NextStepView.CARRY_PROCEDURE_CODES, List.of("95810", "95811"));
        // A key with nothing to carry is left out, never carried as an empty value.
        assertThat(next.carry()).doesNotContainKeys(NextStepView.CARRY_DTR_LINK_URL, NextStepView.CARRY_DELEGATED_VENDOR);
    }

    @Test
    void anOrderOfSeveralCodesNamesEachCodeInTheVerdict() {
        List<SentOrder> orders = List.of(new SentOrder("ServiceRequest/service-request-1", "95810"),
                new SentOrder("ServiceRequest/service-request-2", "95811"));
        String answerFor = "{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-%s\",\"extension\":[{\"url\":\""
                + EXT + "\",\"extension\":[{\"url\":\"pa-needed\",\"valueCode\":\"%s\"}]}]}}";

        // The second code got no answer. The verdict must not read "not required" for the order.
        VendorExchange partial = TestExchanges.crd(200, "{\"systemActions\":[" + answerFor.formatted("1", "no-auth") + "]}");
        VerdictView unanswered = CrdVerdicts.verdict(mapper.read(partial, orders), partial);
        assertThat(unanswered.outcomeCode()).isEqualTo("MIXED");
        assertThat(unanswered.headline()).isEqualTo("The codes in this order did not all receive the same answer.");
        assertThat(unanswered.supporting()).containsExactly("95810: prior authorization is not required.",
                "95811: no answer was returned for this code. That is not an answer that prior authorization is not required.");
        assertThat(CrdVerdicts.nextStep(mapper.read(partial, orders), partial).action()).isEqualTo(NextAction.REVIEW);

        // Both answered alike: one headline, and a line saying which codes it covers.
        VendorExchange alike = TestExchanges.crd(200, "{\"systemActions\":[" + answerFor.formatted("1", "auth-needed") + ","
                + answerFor.formatted("2", "auth-needed") + "]}");
        VerdictView both = CrdVerdicts.verdict(mapper.read(alike, orders), alike);
        assertThat(both.headline()).isEqualTo("Prior authorization is required.");
        assertThat(both.supporting()).containsExactly("This is the answer for each of: 95810, 95811.");

        // An answer the tool could not tie to a code is said to be exactly that.
        VendorExchange untied = TestExchanges.crd(200, "{\"systemActions\":[" + answerFor.formatted("9", "no-auth") + "]}");
        assertThat(CrdVerdicts.verdict(mapper.read(untied, orders), untied).supporting().get(0))
                .isEqualTo("ServiceRequest/service-request-9: prior authorization is not required. This tool could not tie this "
                        + "answer to one of the codes that were asked about.");
    }

    @Test
    void aRequestThatWasNotAcceptedPointsAtTheLikelyCauseForItsStatus() {
        String outcome = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\"}]}";
        // The body is not at fault for credentials that were not accepted or an address that does not exist.
        for (int status : new int[] {401, 403}) {
            NextStepView next = next(status, outcome);
            assertThat(next.action()).as("HTTP " + status).isEqualTo(NextAction.OPEN_CONFIG);
            assertThat(next.headline()).isEqualTo("Next: check the credentials on the Config tab.");
            assertThat(next.reason()).contains("HTTP " + status);
        }
        NextStepView notFound = next(404, outcome);
        assertThat(notFound.action()).isEqualTo(NextAction.OPEN_CONFIG);
        assertThat(notFound.headline()).contains("service id").contains("Discovery");
        for (int status : new int[] {400, 422}) {
            assertThat(next(status, outcome).action()).as("HTTP " + status).isEqualTo(NextAction.FIX_REQUEST);
        }
        // A 404 that names the member is a finding about test data, not about the address.
        String memberNotFound = "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\","
                + "\"expression\":[\"Patient.identifier\"]}]}";
        assertThat(next(404, memberNotFound).action()).isEqualTo(NextAction.FIX_TEST_DATA);
    }

    @Test
    void aResponseWithNoBodyIsNotDescribedAsOneThatCouldNotBeRead() {
        VendorExchange empty = TestExchanges.crd(200, null);
        assertThat(CrdVerdicts.verdict(mapper.read(empty), empty).headline()).isEqualTo("The service answered with no body (HTTP 200).");
    }

    @Test
    void everyOutcomeHasAVerdictAndANextStep() {
        // Outcomes the scenario files do not produce, built directly.
        Map<CrdOutcome, String> bodies = Map.of(
                CrdOutcome.PERFORMER_AUTH, one("{\"url\":\"pa-needed\",\"valueCode\":\"performpa\"}"),
                CrdOutcome.CONDITIONAL, one("{\"url\":\"pa-needed\",\"valueCode\":\"conditional\"}"),
                CrdOutcome.AUTH_NOT_STATED, one("{\"url\":\"covered\",\"valueCode\":\"covered\"}"),
                CrdOutcome.UNRECOGNISED_CODE, one("{\"url\":\"pa-needed\",\"valueCode\":\"auth-maybe\"}"),
                CrdOutcome.MIXED, "{\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"a\",\"extension\":[{\"url\":\""
                        + EXT + "\",\"extension\":[{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\"}]}]}},{\"type\":\"update\",\"resource\":{\"resourceType\":"
                        + "\"ServiceRequest\",\"id\":\"b\",\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"}]}]}}]}");
        Set<CrdOutcome> seen = EnumSet.noneOf(CrdOutcome.class);
        bodies.forEach((outcome, body) -> {
            VendorExchange exchange = TestExchanges.crd(200, body);
            CrdReading reading = mapper.read(exchange);
            assertThat(reading.outcome()).isEqualTo(outcome);
            assertThat(CrdVerdicts.verdict(reading, exchange).headline()).as(outcome.name()).isNotBlank().endsWith(".");
            assertThat(CrdVerdicts.nextStep(reading, exchange).headline()).as(outcome.name()).isNotBlank();
            seen.add(outcome);
        });
        VendorExchange unavailable = TestExchanges.unavailable(EpaOperation.CRD_ORDER_SIGN);
        assertThat(CrdVerdicts.verdict(mapper.read(unavailable), unavailable).headline()).isEqualTo("The service could not be reached.");
        VendorExchange malformed = TestExchanges.crd(200, "not json");
        assertThat(CrdVerdicts.verdict(mapper.read(malformed), malformed).headline())
                .isEqualTo("The service answered with a body that could not be read (HTTP 200).");
        assertThat(CrdVerdicts.verdict(mapper.read(TestExchanges.crd(200, bodies.get(CrdOutcome.MIXED))), TestExchanges.crd(200, bodies.get(CrdOutcome.MIXED)))
                .supporting()).containsExactly("ServiceRequest/a: prior authorization is not required.", "ServiceRequest/b: prior authorization is required.");
        assertThat(CrdVerdicts.verdict(mapper.read(TestExchanges.crd(200, bodies.get(CrdOutcome.UNRECOGNISED_CODE))),
                TestExchanges.crd(200, bodies.get(CrdOutcome.UNRECOGNISED_CODE))).supporting()).containsExactly("Unrecognised code: auth-maybe.");
        assertThat(seen).hasSize(5);
    }
}
