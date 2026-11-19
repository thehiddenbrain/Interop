package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.model.crd.CrdResponseKind;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Drives every bundled CRD scenario through the real scenario files, the real mock client and the
 * real mapper, and compares the determination with two independent statements of what it should
 * be: the table in the specification, written out here, and the claim each file makes about
 * itself. A scenario file that stops producing the determination it claims fails here.
 */
class CrdScenarioDeterminationTest {

    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final MockOnyxCrdClient client = new MockOnyxCrdClient(scenarios, new MockExchanges(APP));
    private final CrdResponseMapper mapper = new CrdResponseMapper();

    private VendorExchange call(String scenarioId, String memberId, String npi) {
        return client.hook(TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN), ResolvedText.plain("{\"hook\":\"order-sign\"}"),
                MockSelection.of(scenarioId, memberId, npi));
    }

    private CrdReading read(String sentinelMemberId) {
        return mapper.read(call(null, sentinelMemberId, "1234567893"));
    }

    /** The codes of the one answer a single-order scenario gives, or none when it gives no answer. */
    private static Map<String, List<String>> codes(CrdReading reading) {
        List<CoverageInformationView> coverage = reading.detail().coverageInformation();
        assertThat(coverage.size()).isLessThanOrEqualTo(1);
        return coverage.isEmpty() ? Map.of() : CrdReading.codes(coverage.get(0));
    }

    private static TestResult assess(String expected, CrdReading reading) {
        return Expectations.assess(expected, reading.outcome(), reading.asked(), reading.answers(), CrdOutcome.values(),
                CrdCodes.EXPECTATION_ELEMENTS).result();
    }

    /** A request body carrying one order for each code, as the request builder writes them. */
    private static String orders(String... procedureCodes) {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < procedureCodes.length; i++) {
            entries.append(i == 0 ? "" : ",").append("{\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-")
                    .append(i + 1).append("\",\"code\":{\"coding\":[{\"code\":\"").append(procedureCodes[i]).append("\"}]}}}");
        }
        return "{\"hook\":\"order-sign\",\"context\":{\"draftOrders\":{\"resourceType\":\"Bundle\",\"entry\":[" + entries + "]}}}";
    }

    @Test
    void eachSentinelMemberProducesTheDeterminationTheSpecificationTableStates() {
        Map<String, CrdOutcome> table = new LinkedHashMap<>();
        table.put("P32H-PA-REQ", CrdOutcome.AUTH_REQUIRED);
        table.put("P32H-NO-AUTH", CrdOutcome.NO_AUTH_REQUIRED);
        table.put("P32H-PA-SAT", CrdOutcome.AUTH_SATISFIED);
        table.put("P32H-DOC-DTR", CrdOutcome.DOCUMENTATION_REQUIRED);
        table.put("P32H-INFO", CrdOutcome.INFORMATION_NEEDED);
        table.put("P32H-NOCOV", CrdOutcome.NOT_COVERED);
        table.put("P32H-NOMEM", CrdOutcome.MEMBER_NOT_FOUND);
        table.put("P32H-INACTIVE", CrdOutcome.COVERAGE_INACTIVE);
        table.put("P32H-NOPROV", CrdOutcome.PROVIDER_NOT_FOUND);
        table.put("P32H-DELEG", CrdOutcome.AUTH_REQUIRED);
        table.put("P32H-BAD400", CrdOutcome.REJECTED);
        table.put("P32H-ERR500", CrdOutcome.SERVICE_ERROR);
        table.put("P32H-TIMEOUT", CrdOutcome.TIMEOUT);
        table.put("P32H-EMPTY", CrdOutcome.NO_DETERMINATION);
        assertThat(table).hasSize(14);
        table.forEach((sentinel, outcome) -> assertThat(read(sentinel).outcome()).as(sentinel).isEqualTo(outcome));
    }

    @Test
    void everyScenarioFileProducesExactlyWhatItClaims() {
        List<Scenario> all = scenarios.list("crd");
        assertThat(all).hasSize(14);
        for (Scenario scenario : all) {
            VendorExchange exchange = call(scenario.id(), null, null);
            CrdReading reading = mapper.read(exchange);
            String what = scenario.id();

            assertThat(reading.outcome().name()).as(what + " outcome").isEqualTo(scenario.expect().outcome());
            assertThat(exchange.failureType().name()).as(what + " error type").isEqualTo(scenario.expect().errorType());
            assertThat(exchange.httpStatus()).as(what + " status").isEqualTo(scenario.expect().httpStatus());
            scenario.expect().codes().forEach((element, code) ->
                    assertThat(codes(reading).get(element)).as(what + " " + element).isNotNull().contains(code));
            assertThat(reading.delegatedVendor()).as(what + " vendor").isEqualTo(scenario.expect().delegatedVendor());
            // The claim, declared as an expectation, must pass through the same comparison a tester's would:
            // the outcome, and every code the file claims, as one declared expectation.
            StringBuilder declared = new StringBuilder(scenario.expect().outcome());
            scenario.expect().codes().forEach((element, code) -> declared.append("; ").append(element).append("=").append(code));
            assertThat(assess(declared.toString(), reading)).as(what + " as the expectation " + declared).isEqualTo(TestResult.PASS);
            // Which scenario answered, and how it was chosen, travels on the exchange for the result to state.
            assertThat(exchange.scenarioId()).as(what).isEqualTo(scenario.id());
            assertThat(exchange.scenarioNote()).as(what).isEqualTo("chosen in the scenario selector");
        }
    }

    @Test
    void theMockAnswersEveryOrderItIsSentAndSaysThatItRepeatedTheAnswer() {
        // The scenario file answers one order. A real service answers each order, so with two
        // codes the mock gives two answers, each tied to its own code, and the result says how.
        VendorExchange exchange = client.hook(TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN),
                ResolvedText.plain(orders("95810", "95811")), MockSelection.of("crd-01-pa-required", null, null));
        CrdReading reading = mapper.read(exchange, SentOrder.fromBody(orders("95810", "95811")));

        assertThat(reading.outcome()).isEqualTo(CrdOutcome.AUTH_REQUIRED);
        assertThat(reading.unanswered()).isEmpty();
        assertThat(reading.detail().coverageInformation()).extracting(CoverageInformationView::subject).containsExactly("95810", "95811");
        assertThat(reading.detail().coverageInformation()).extracting(v -> v.appliesTo().value())
                .containsExactly("ServiceRequest/service-request-1", "ServiceRequest/service-request-2");
        assertThat(exchange.scenarioNote()).contains("repeated here for each of the 2 orders sent");
        assertThat(exchange.responseBody()).contains("service-request-2");

        // One order is answered from the file exactly as written.
        VendorExchange single = client.hook(TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN),
                ResolvedText.plain(orders("95810")), MockSelection.of("crd-01-pa-required", null, null));
        assertThat(single.scenarioNote()).isEqualTo("chosen in the scenario selector");
        assertThat(single.responseBody()).doesNotContain("service-request-2");
    }

    @Test
    void anAnswerOnTheCardItselfCannotBeTiedToOneOfSeveralCodesAndIsNotAssumedToCoverThem() {
        // Scenario 3 puts its coverage information on the card, attached to no order. With one
        // code there is nothing else it could be about. With two, saying which would be a guess.
        CrdReading one = mapper.read(client.hook(TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN),
                ResolvedText.plain(orders("95810")), MockSelection.of("crd-03-pa-satisfied", null, null)), SentOrder.fromBody(orders("95810")));
        assertThat(one.outcome()).isEqualTo(CrdOutcome.AUTH_SATISFIED);
        assertThat(one.detail().coverageInformation().get(0).procedureCodes()).containsExactly("95810");

        CrdReading two = mapper.read(client.hook(TestPreparedCalls.unconfigured(EpaOperation.CRD_ORDER_SIGN),
                ResolvedText.plain(orders("95810", "95811")), MockSelection.of("crd-03-pa-satisfied", null, null)),
                SentOrder.fromBody(orders("95810", "95811")));
        assertThat(two.outcome()).isEqualTo(CrdOutcome.MIXED);
        assertThat(two.detail().coverageInformation().get(0).procedureCodes()).isEmpty();
        assertThat(two.unanswered()).containsExactly("95810", "95811");
    }

    @Test
    void noAuthAndNoDeterminationAreNeverTheSameAnswer() {
        VendorExchange noAuthExchange = call(null, "P32H-NO-AUTH", "1234567893");
        VendorExchange emptyExchange = call(null, "P32H-EMPTY", "1234567893");
        CrdReading noAuth = mapper.read(noAuthExchange);
        CrdReading empty = mapper.read(emptyExchange);

        // Both are HTTP 200 with no transport failure. Only the content tells them apart.
        assertThat(noAuthExchange.httpStatus()).isEqualTo(200);
        assertThat(emptyExchange.httpStatus()).isEqualTo(200);
        assertThat(noAuthExchange.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(emptyExchange.failureType()).isEqualTo(OnyxFailureType.NONE);

        assertThat(noAuth.outcome()).isEqualTo(CrdOutcome.NO_AUTH_REQUIRED);
        assertThat(empty.outcome()).isEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(noAuth.outcome().label()).isNotEqualTo(empty.outcome().label());

        assertThat(codes(noAuth)).containsEntry("pa-needed", List.of("no-auth"));
        assertThat(codes(empty)).isEmpty();
        assertThat(empty.answers()).isEmpty();
        assertThat(noAuth.ledgerCodes()).contains("pa-needed=no-auth");
        assertThat(empty.ledgerCodes()).isEmpty();

        assertThat(noAuth.detail().coverageInformation()).hasSize(1);
        assertThat(empty.detail().coverageInformation()).isEmpty();
        // The empty response did carry a cards array, and it was empty: that is shown, not hidden.
        assertThat(empty.detail().responseKind()).isEqualTo(CrdResponseKind.CDS_HOOKS);
        assertThat(empty.detail().cardsPresence()).isEqualTo(Presence.EMPTY);
        assertThat(empty.detail().systemActionsPresence()).isEqualTo(Presence.ABSENT);

        // A tester who expects "no authorization" must not see a pass when the service said nothing.
        assertThat(assess("NO_AUTH_REQUIRED", noAuth)).isEqualTo(TestResult.PASS);
        assertThat(assess("pa-needed=no-auth", noAuth)).isEqualTo(TestResult.PASS);
        assertThat(assess("NO_AUTH_REQUIRED", empty)).isEqualTo(TestResult.FAIL);
        assertThat(assess("pa-needed=no-auth", empty)).isEqualTo(TestResult.FAIL);
        assertThat(assess("NO_DETERMINATION", noAuth)).isEqualTo(TestResult.FAIL);
    }

    @Test
    void theSentinelNpiGivesProviderNotFoundForAnOrdinaryMember() {
        CrdReading reading = mapper.read(call(null, "S123456789", "9999999995"));
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.PROVIDER_NOT_FOUND);
    }

    @Test
    void notFoundIsToldApartFromAFaultThatCarriesTheSameKindOfStatus() {
        VendorExchange notFound = call(null, "P32H-NOMEM", "1234567893");
        VendorExchange rejected = call(null, "P32H-BAD400", "1234567893");
        // Both are HTTP 4xx. One is a finding about the test data, the other about the request.
        assertThat(notFound.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(rejected.failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(mapper.read(notFound).outcome()).isEqualTo(CrdOutcome.MEMBER_NOT_FOUND);
        assertThat(mapper.read(rejected).outcome()).isEqualTo(CrdOutcome.REJECTED);
        assertThat(mapper.read(notFound).outcome().fault()).isFalse();
        assertThat(mapper.read(rejected).outcome().fault()).isTrue();
    }

    @Test
    void aFaultResponseIsRenderedIssueByIssueAndNotReducedToItsStatus() {
        CrdReading rejected = read("P32H-BAD400");
        assertThat(rejected.detail().responseKind()).isEqualTo(CrdResponseKind.OPERATION_OUTCOME);
        assertThat(rejected.detail().issues()).hasSize(1);
        assertThat(rejected.detail().issues().get(0).code().value()).isEqualTo("invalid");
        assertThat(rejected.detail().issues().get(0).severity().value()).isEqualTo("error");
        assertThat(rejected.detail().issues().get(0).diagnostics().value()).contains("draft orders");
        assertThat(rejected.detail().issues().get(0).expression()).extracting(s -> s.value()).containsExactly("context.draftOrders");
        // Everything in the OperationOutcome was placed in the designed view, so nothing is left over.
        assertThat(rejected.additionalFields()).isEmpty();
    }

    @Test
    void aTimeoutHasNoResponseToShowAndSaysSo() {
        VendorExchange exchange = call(null, "P32H-TIMEOUT", "1234567893");
        assertThat(exchange.httpStatus()).isNull();
        assertThat(exchange.request().sent()).isFalse();
        assertThat(exchange.request().note()).contains("crd-13-timeout").contains("sentinel member ID P32H-TIMEOUT");
        CrdReading reading = mapper.read(exchange);
        assertThat(reading.detail().responseKind()).isEqualTo(CrdResponseKind.NO_RESPONSE);
        assertThat(reading.answers()).isEmpty();
        assertThat(reading.ledgerCodes()).isEmpty();
    }

    @Test
    void theThreePlacementsOfCoverageInformationAreAllFoundInTheBundledScenarios() {
        assertThat(read("P32H-PA-REQ").detail().coverageInformation().get(0).foundAt())
                .isEqualTo("systemActions[0].resource.extension[0]");
        assertThat(read("P32H-PA-SAT").detail().coverageInformation().get(0).foundAt())
                .isEqualTo("cards[0].extension[0]");
        assertThat(read("P32H-INFO").detail().coverageInformation().get(0).foundAt())
                .isEqualTo("cards[0].suggestions[0].actions[0].resource.extension[0]");
    }

    @Test
    void theDelegatedVendorIsNamedWithTheRuleThatRecognisedIt() {
        CrdReading reading = read("P32H-DELEG");
        assertThat(reading.delegatedVendor()).isEqualTo("Evolent");
        assertThat(reading.detail().coverageInformation().get(0).delegatedVendorBasis()).isEqualTo("the detail code \"delegated-vendor\"");
        assertThat(reading.detail().coverageInformation().get(0).details()).hasSize(2);
        assertThat(codes(reading)).containsEntry("delegated-vendor", List.of("Evolent"));
        assertThat(reading.ledgerCodes()).doesNotContain("delegated-vendor=Evolent");
    }

    @Test
    void theDtrLinkAndTheQuestionnaireAreCarriedOnTheDocumentationScenario() {
        CrdReading reading = read("P32H-DOC-DTR");
        assertThat(reading.detail().cards().get(0).links()).hasSize(1);
        assertThat(reading.detail().cards().get(0).links().get(0).type().value()).isEqualTo("smart");
        assertThat(reading.detail().coverageInformation().get(0).questionnaires()).hasSize(1);
        assertThat(reading.detail().coverageInformation().get(0).questionnaires().get(0).value()).endsWith("/Questionnaire/sleep-study-criteria");
        assertThat(codes(reading)).containsEntry("doc-purpose", List.of("withpa"));
    }

    @Test
    void theSatisfiedAuthorizationIdentifierIsReadButKeptOutOfTheLedgerCodes() {
        CrdReading reading = read("P32H-PA-SAT");
        assertThat(reading.detail().coverageInformation().get(0).satisfiedPaId().value()).isEqualTo("PA-PLACEHOLDER-0001");
        assertThat(codes(reading)).containsEntry("satisfied-pa-id", List.of("PA-PLACEHOLDER-0001"));
        assertThat(reading.ledgerCodes()).noneMatch(code -> code.contains("PA-PLACEHOLDER-0001"));
    }
}
