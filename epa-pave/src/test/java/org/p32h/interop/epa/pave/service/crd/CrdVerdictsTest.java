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
        assertThat(verdict("P32H-PA-SAT").headline()).isEqualTo("Prior authorization requirements are already met.");
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
                "Clinical documentation is needed, to go with the prior authorization request.",
                "A payer questionnaire is named for this service.");
        // CRD201-20: satisfied says the requirements are met, and names the payer's identifier as no more than that.
        assertThat(beyondTheScenarioLine(verdict("P32H-PA-SAT")))
                .containsExactly("Identifier the payer gives for the requirements already met: PA-PLACEHOLDER-0001.");
        // CRD201-19: what is conditional is said element by element.
        assertThat(beyondTheScenarioLine(verdict("P32H-INFO"))).containsExactly(
                "Conditional on information not yet supplied: coverage, prior authorization.",
                "The payer needs to know: who will perform the service; where the service will be performed.");
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
        assertThat(dtr.headline()).isEqualTo("Next: complete the payer's documentation in DTR.");
        assertThat(dtr.reason()).startsWith("The response asks for documentation, to go with the prior authorization request. In CRD 2.0.1 "
                + "a doc-needed answer is itself what says DTR is to be used, whatever pa-needed says.");
        assertThat(dtr.carry()).containsEntry(NextStepView.CARRY_DOC_PURPOSE, List.of("withpa"));
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
        assertThat(next("P32H-EMPTY").action()).isEqualTo(NextAction.REPORT_NO_DETERMINATION);
    }

    @Test
    void documentationWithNoQuestionnaireAndNoLinkStillGoesToDtrBecauseDocNeededSaysSo() {
        // CRD201-03: in CRD 2.0.1 doc-needed itself says DTR is to be used. No questionnaire or link is needed.
        VendorExchange exchange = TestExchanges.crd(200, one("{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},{\"url\":\"doc-needed\",\"valueCode\":\"admin\"}"));
        NextStepView next = CrdVerdicts.nextStep(mapper.read(exchange), exchange);
        assertThat(mapper.read(exchange).outcome()).isEqualTo(CrdOutcome.DOCUMENTATION_REQUIRED);
        assertThat(next.action()).isEqualTo(NextAction.START_DTR);
        assertThat(next.reason()).isEqualTo("The response asks for documentation, and the response does not say what it is for (no "
                + "doc-purpose). In CRD 2.0.1 a doc-needed answer is itself what says DTR is to be used, whatever pa-needed says. No "
                + "questionnaire is named, so DTR asks the payer for its package by the order and the coverage.");
        assertThat(next.carry()).doesNotContainKey(NextStepView.CARRY_DTR_LINK_URL);
    }

    @Test
    void documentationLeadsToDtrWhateverPaNeededSaysAndTheReasonSaysWhatItIsFor() {
        // CRD201-13: the 2.0.1 annotated example answers satisfied, admin, withclaim, info-needed performer, a questionnaire and a response.
        VendorExchange example = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"conditional\"},"
                + "{\"url\":\"pa-needed\",\"valueCode\":\"satisfied\"},{\"url\":\"satisfied-pa-id\",\"valueString\":\"PA-PLACEHOLDER-0003\"},"
                + "{\"url\":\"doc-needed\",\"valueCode\":\"admin\"},{\"url\":\"doc-purpose\",\"valueCode\":\"withclaim\"},"
                + "{\"url\":\"info-needed\",\"valueCode\":\"performer\"},"
                + "{\"url\":\"questionnaire\",\"valueCanonical\":\"https://example.org/p32h-placeholder/Questionnaire/q1\"},"
                + "{\"url\":\"response\",\"valueReference\":{\"reference\":\"QuestionnaireResponse/qr-1\"}},"
                + "{\"url\":\"coverage-assertion-id\",\"valueString\":\"a-9\"}"));
        CrdReading reading = mapper.read(example);
        // The definite prior authorization answer is the outcome; info-needed does not hide it (CRD201-19).
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.AUTH_SATISFIED);
        NextStepView next = CrdVerdicts.nextStep(reading, example);
        assertThat(next.action()).isEqualTo(NextAction.START_DTR);
        assertThat(next.reason()).startsWith("The response asks for documentation, to go with the claim.")
                .contains("A partly completed questionnaire response is named for DTR to start from.")
                .endsWith("The payer also asks for: who will perform the service. " + CrdVerdicts.OWN_REQUEST);
        assertThat(next.carry()).containsEntry(NextStepView.CARRY_DOC_PURPOSE, List.of("withclaim"))
                .containsEntry(NextStepView.CARRY_COVERAGE_ASSERTION_ID, List.of("a-9"))
                .containsEntry(NextStepView.CARRY_ORDER, List.of("ServiceRequest/service-request-1"))
                .containsEntry(NextStepView.CARRY_QUESTIONNAIRE, List.of("https://example.org/p32h-placeholder/Questionnaire/q1"));
        assertThat(CrdVerdicts.verdict(reading, example).supporting()).contains(
                "Definite: prior authorization: prior authorization requirements are already met; documentation: administrative "
                        + "documentation is needed.",
                "Conditional on information not yet supplied: coverage.",
                "A partly completed questionnaire response is named for DTR to start from: QuestionnaireResponse/qr-1.");

        // No authorization needed and documentation for the claim: still DTR, not the end of the flow.
        VendorExchange noAuth = TestExchanges.crd(200, one("{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\"},"
                + "{\"url\":\"doc-needed\",\"valueCode\":\"clinical\"},{\"url\":\"doc-purpose\",\"valueCode\":\"retain-doc\"}"));
        NextStepView afterNoAuth = CrdVerdicts.nextStep(mapper.read(noAuth), noAuth);
        assertThat(afterNoAuth.action()).isEqualTo(NextAction.START_DTR);
        assertThat(afterNoAuth.reason()).startsWith("The response asks for documentation, to keep in the record as evidence of medical necessity.");

        // A later version's doc-needed=patient asks for documentation too.
        VendorExchange patient = TestExchanges.crd(200, one("{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},"
                + "{\"url\":\"doc-needed\",\"valueCode\":\"patient\"}"));
        assertThat(CrdVerdicts.nextStep(mapper.read(patient), patient).action()).isEqualTo(NextAction.START_DTR);
    }

    @Test
    void informationWantedAndNoDocumentationAsksForItAndSaysWherePerformerAndLocationGo() {
        VendorExchange info = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"conditional\"},"
                + "{\"url\":\"info-needed\",\"valueCode\":\"location\"}"));
        NextStepView next = CrdVerdicts.nextStep(mapper.read(info), info);
        assertThat(next.action()).isEqualTo(NextAction.SUPPLY_INFORMATION);
        assertThat(next.reason()).isEqualTo("The payer cannot give a firm answer from what it was sent. The payer also asks for: where the "
                + "service will be performed. " + CrdVerdicts.OWN_REQUEST);
        // A definite answer beside the request for information is not hidden behind it.
        VendorExchange required = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"conditional\"},"
                + "{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},{\"url\":\"info-needed\",\"valueCode\":\"timeframe\"}"));
        assertThat(mapper.read(required).outcome()).isEqualTo(CrdOutcome.AUTH_REQUIRED);
        assertThat(CrdVerdicts.nextStep(mapper.read(required), required).action()).isEqualTo(NextAction.SUPPLY_INFORMATION);
    }

    @Test
    void twoCoveragesAnsweringDifferentlyAreSaidToDifferByCoverageNotByCode() {
        // CRD201-14: one order, one answer per coverage.
        String body = "{\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\","
                + "\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[{\"url\":\"coverage\",\"valueReference\":{\"reference\":\"Coverage/coverage-1\"}},"
                + "{\"url\":\"covered\",\"valueCode\":\"covered\"},{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"}]},"
                + "{\"url\":\"" + EXT + "\",\"extension\":[{\"url\":\"coverage\",\"valueReference\":{\"reference\":\"Coverage/coverage-2\"}},"
                + "{\"url\":\"covered\",\"valueCode\":\"covered\"},{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\"}]}]}}]}";
        VendorExchange exchange = TestExchanges.crd(200, body);
        CrdReading reading = mapper.read(exchange, List.of(new SentOrder("ServiceRequest/service-request-1", "95810")));
        assertThat(reading.detail().coverageInformation()).extracting(v -> v.subject())
                .containsExactly("95810 under Coverage/coverage-1", "95810 under Coverage/coverage-2");
        VerdictView verdict = CrdVerdicts.verdict(reading, exchange);
        assertThat(verdict.headline()).isEqualTo("The member's coverages did not all give the same answer.");
        assertThat(verdict.supporting()).first().isEqualTo("The response answers under more than one coverage, and the coverages do not "
                + "give the same answer. That is a difference between the member's coverages, not between the codes.");
        assertThat(verdict.supporting()).doesNotContain("The codes in this order did not all receive the same answer.");
    }

    @Test
    void aReasonIsShownInTheVerdictAndALaterVersionsReasonIsRead() {
        // CRD201-24.
        VendorExchange inactive = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"not-covered\"},{\"url\":\"reason\","
                + "\"valueCodeableConcept\":{\"coding\":[{\"system\":\"http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp\",\"code\":"
                + "\"no-active-coverage\"}]}}"));
        assertThat(mapper.read(inactive).outcome()).isEqualTo(CrdOutcome.COVERAGE_INACTIVE);
        assertThat(CrdVerdicts.verdict(mapper.read(inactive), inactive).supporting())
                .contains("Reason the payer gives: http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp|no-active-coverage (a CRD 2.2.0 ballot code, "
                        + "not in CRD 2.0.1: the coverage named is not active, so no coverage information can be given).");
        VendorExchange noMember = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"not-covered\"},{\"url\":\"reason\","
                + "\"valueCodeableConcept\":{\"coding\":[{\"system\":\"http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp\",\"code\":"
                + "\"no-member-found\"}]}}"));
        assertThat(mapper.read(noMember).outcome()).isEqualTo(CrdOutcome.MEMBER_NOT_FOUND);
        assertThat(CrdVerdicts.nextStep(mapper.read(noMember), noMember).action()).isEqualTo(NextAction.FIX_TEST_DATA);
        VendorExchange text = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"not-covered\"},{\"url\":\"reason\","
                + "\"valueCodeableConcept\":{\"text\":\"Yearly limit reached\"}}"));
        assertThat(CrdVerdicts.verdict(mapper.read(text), text).supporting()).contains("Reason the payer gives: Yearly limit reached.");
    }

    @Test
    void aCrd201ReasonCodeIsShownWithItsCrd201Meaning() {
        // V-08: gold-card and detail-code are the only reason codes CRD 2.0.1 defines.
        VendorExchange goldCard = TestExchanges.crd(200, one("{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\"},{\"url\":\"reason\","
                + "\"valueCodeableConcept\":{\"coding\":[{\"system\":\"http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp\",\"code\":"
                + "\"gold-card\"}]}}"));
        assertThat(CrdVerdicts.verdict(mapper.read(goldCard), goldCard).supporting()).contains("Reason the payer gives: "
                + "http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp|gold-card (CRD 2.0.1 gold-card: the ordering practitioner has been "
                + "granted gold card status with this payer or coverage type).");
        VendorExchange detailCode = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"conditional\"},{\"url\":\"reason\","
                + "\"valueCodeableConcept\":{\"coding\":[{\"system\":\"http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp\",\"code\":"
                + "\"detail-code\"}]}}"));
        assertThat(CrdVerdicts.verdict(mapper.read(detailCode), detailCode).supporting()).contains("Reason the payer gives: "
                + "http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp|detail-code (CRD 2.0.1 detail-code: the ordered code is too general "
                + "for the payer to decide coverage or prior authorization, and a more detailed code is needed).");
    }

    @Test
    void requirementsAlreadyMetEndTheFlowInTheWordsOfCrd201() {
        // V-04 and CRD201-20: satisfied says the conditions were evaluated, not that an authorization is on file.
        NextStepView satisfied = next("P32H-PA-SAT");
        assertThat(satisfied.action()).isEqualTo(NextAction.FLOW_ENDS);
        assertThat(satisfied.reason()).isEqualTo("The payer answered that the requirements prior authorization would check are already "
                + "met, so prior authorization can be bypassed and there is nothing to submit.");
    }

    @Test
    void documentationThatIsConditionalIsNotCalledNotAskedFor() {
        // V-05: CRD 2.0.1 conditional means the decision cannot be made without more information.
        VendorExchange exchange = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"covered\"},"
                + "{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},{\"url\":\"doc-needed\",\"valueCode\":\"conditional\"}"));
        assertThat(mapper.read(exchange).outcome()).isEqualTo(CrdOutcome.AUTH_REQUIRED);
        NextStepView next = CrdVerdicts.nextStep(mapper.read(exchange), exchange);
        assertThat(next.action()).isEqualTo(NextAction.START_PAS);
        assertThat(next.reason()).isEqualTo("Prior authorization is required. Documentation may be needed once the payer has more "
                + "information: doc-needed is conditional.");
        // With a vendor, the vendor is still named first.
        VendorExchange routed = TestExchanges.crd(200, one("{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},"
                + "{\"url\":\"doc-needed\",\"valueCode\":\"conditional\"},{\"url\":\"detail\",\"extension\":[{\"url\":\"code\","
                + "\"valueCodeableConcept\":{\"coding\":[{\"code\":\"delegated-vendor\"}]}},{\"url\":\"value\",\"valueString\":\"eviCore\"}]}"));
        assertThat(CrdVerdicts.nextStep(mapper.read(routed), routed).reason()).isEqualTo("Prior authorization is required and routes to "
                + "eviCore. Documentation may be needed once the payer has more information: doc-needed is conditional.");
        // Without doc-needed, the reason is as it was.
        VendorExchange plain = TestExchanges.crd(200, one("{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"}"));
        assertThat(CrdVerdicts.nextStep(mapper.read(plain), plain).reason())
                .isEqualTo("Prior authorization is required and no documentation is asked for first.");
    }

    @Test
    void aDefinitePriorAuthorizationAnswerIsNotSaidTwiceWhenTheAnswerIsPartlyConditional() {
        // V-06.
        VendorExchange exchange = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"conditional\"},"
                + "{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},{\"url\":\"doc-needed\",\"valueCode\":\"clinical\"},"
                + "{\"url\":\"info-needed\",\"valueCode\":\"location\"}"));
        CrdReading reading = mapper.read(exchange);
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.DOCUMENTATION_REQUIRED);
        List<String> supporting = CrdVerdicts.verdict(reading, exchange).supporting();
        assertThat(supporting).filteredOn(line -> line.contains("prior authorization is needed")).containsExactly(
                "Definite: prior authorization: prior authorization is needed; documentation: clinical documentation is needed.");
        assertThat(supporting).contains("Conditional on information not yet supplied: coverage.");
        // With nothing conditional there is no Definite line, so the prior authorization line stays.
        VendorExchange firm = TestExchanges.crd(200, one("{\"url\":\"covered\",\"valueCode\":\"covered\"},"
                + "{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},{\"url\":\"doc-needed\",\"valueCode\":\"clinical\"}"));
        assertThat(CrdVerdicts.verdict(mapper.read(firm), firm).supporting()).contains("Prior authorization: prior authorization is needed.");
    }

    @Test
    void aNamedQuestionnaireResponseIsHandedOnToDtrAsAReferenceAndNeverAsACompletedResponse() {
        // V-01: the reference CRD 2.0.1 names in the response sub-extension goes to DTR under its own key.
        VendorExchange exchange = TestExchanges.crd(200, one("{\"url\":\"pa-needed\",\"valueCode\":\"auth-needed\"},"
                + "{\"url\":\"doc-needed\",\"valueCode\":\"both\"},{\"url\":\"doc-purpose\",\"valueCode\":\"withpa\"},"
                + "{\"url\":\"response\",\"valueReference\":{\"reference\":\"QuestionnaireResponse/qr-1\"}},"
                + "{\"url\":\"coverage-assertion-id\",\"valueString\":\"a-1\"}"));
        NextStepView next = CrdVerdicts.nextStep(mapper.read(exchange), exchange);
        assertThat(next.action()).isEqualTo(NextAction.START_DTR);
        assertThat(next.reason()).endsWith("A partly completed questionnaire response is named for DTR to start from.");
        assertThat(next.reason()).doesNotContain("does not yet hand");
        assertThat(next.carry()).containsEntry(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE_REF, List.of("QuestionnaireResponse/qr-1"));
        // A reference is not a completed response, which PAS would submit.
        assertThat(next.carry()).doesNotContainKey(NextStepView.CARRY_QUESTIONNAIRE_RESPONSE);
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

    @Test
    void anAnswerThatNeedsNothingFurtherIsColouredForSomethingToDoWhenItsNextStepIsTheTesters() {
        VerdictView clear = VerdictView.of(CrdOutcome.NO_AUTH_REQUIRED, "Prior authorization is not required.", List.of());
        VerdictView toDtr = CrdVerdicts.toned(clear, NextStepView.of(NextAction.START_DTR, "Next: complete the payer's documentation in DTR.", "x"));
        assertThat(toDtr.tone()).isEqualTo(org.p32h.interop.epa.pave.model.Tone.ACTION);
        assertThat(toDtr.outcomeCode()).isEqualTo("NO_AUTH_REQUIRED");
        assertThat(toDtr.headline()).isEqualTo("Prior authorization is not required.");
        assertThat(CrdVerdicts.toned(clear, NextStepView.of(NextAction.FLOW_ENDS, "The flow ends here.", "x"))).isSameAs(clear);
        VerdictView action = VerdictView.of(CrdOutcome.AUTH_REQUIRED, "Prior authorization is required.", List.of());
        assertThat(CrdVerdicts.toned(action, NextStepView.of(NextAction.START_PAS, "Next.", "x"))).isSameAs(action);
    }
}
