package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
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
        return CrdVerdicts.nextStep(mapper.read(scenario(sentinel)));
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
        assertThat(verdict("P32H-BAD400").headline()).isEqualTo("The service rejected the request (HTTP 400).");
        assertThat(verdict("P32H-ERR500").headline()).isEqualTo("The service failed (HTTP 500).");
        assertThat(verdict("P32H-TIMEOUT").headline()).isEqualTo("The service did not answer in time.");
        assertThat(verdict("P32H-EMPTY").headline()).isEqualTo("The service answered but returned no determination.");
    }

    @Test
    void theDelegatedVendorIsCalledOutInTheVerdict() {
        assertThat(verdict("P32H-DELEG").supporting()).contains("It routes to the delegated vendor Evolent.");
        assertThat(next("P32H-DELEG").reason()).isEqualTo("Prior authorization is required and routes to Evolent.");
        assertThat(next("P32H-DELEG").carry()).containsEntry("delegatedVendor", "Evolent");
    }

    @Test
    void theSupportingLinesComeFromTheCodesAndNeverFromCardWording() {
        VerdictView documentation = verdict("P32H-DOC-DTR");
        assertThat(documentation.supporting()).containsExactly("Prior authorization: prior authorization is needed.",
                "Clinical documentation is needed: send with the prior authorization request.",
                "A payer questionnaire is named for this service.");
        assertThat(verdict("P32H-PA-SAT").supporting()).containsExactly("Existing authorization: PA-PLACEHOLDER-0001.");
        assertThat(verdict("P32H-INFO").supporting())
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
                    .doesNotContain("medical necessity").doesNotContain("not medically");
        }
    }

    @Test
    void theNextStepFollowsFromWhatTheResponseSaid() {
        // Documentation with a DTR link: DTR is next, already pointed at the questionnaire.
        NextStepView dtr = next("P32H-DOC-DTR");
        assertThat(dtr.action()).isEqualTo(NextAction.START_DTR);
        assertThat(dtr.headline()).isEqualTo("Next: answer the payer's questionnaire in DTR.");
        assertThat(dtr.carry().get("questionnaire")).endsWith("/Questionnaire/sleep-study-criteria");
        assertThat(dtr.carry().get("dtrLinkUrl")).endsWith("/dtr/launch");
        assertThat(dtr.carry()).containsKey("dtrAppContext");

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
        NextStepView next = CrdVerdicts.nextStep(mapper.read(exchange));
        assertThat(mapper.read(exchange).outcome()).isEqualTo(CrdOutcome.DOCUMENTATION_REQUIRED);
        assertThat(next.action()).isEqualTo(NextAction.START_PAS);
        assertThat(next.reason()).contains("names no questionnaire");
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
            assertThat(CrdVerdicts.nextStep(reading).headline()).as(outcome.name()).isNotBlank();
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
