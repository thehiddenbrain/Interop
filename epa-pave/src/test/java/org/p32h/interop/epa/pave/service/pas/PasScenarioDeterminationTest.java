package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.pas.MockOnyxPasClient;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.DecisionSource;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.PasResponseKind;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioExpect;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;

/**
 * Drives every submission and inquiry step of every bundled PAS scenario through the real scenario
 * files, the real mock client and the real mapper, and compares the determination with two
 * independent statements of what it should be: a table written out here, and the claim each file
 * makes about itself. A scenario file that stops producing the determination it claims fails here.
 *
 * <p>The subscription steps and the notification steps of the same files are driven the same way
 * by the tests of the subscription service and of the notification receiver.
 */
class PasScenarioDeterminationTest {

    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final MockOnyxPasClient client = new MockOnyxPasClient(scenarios, new MockExchanges(APP));
    private final PasResponseMapper mapper = new PasResponseMapper();

    private VendorExchange submit(String scenarioId, String memberId, String... codes) {
        return client.submit(TestPreparedCalls.unconfigured(EpaOperation.PAS_SUBMIT), ResolvedText.plain(PasJson.request("claim-test-1", codes)),
                MockSelection.of(scenarioId, memberId, "1234567893"));
    }

    private VendorExchange inquire(String scenarioId, String memberId, String... codes) {
        return client.inquire(TestPreparedCalls.unconfigured(EpaOperation.PAS_INQUIRE), ResolvedText.plain(PasJson.request("claim-test-1", codes)),
                MockSelection.of(scenarioId, memberId, "1234567893"));
    }

    private PasReading read(VendorExchange exchange, String... codes) {
        return mapper.read(exchange, SentClaim.fromBody(PasJson.request("claim-test-1", codes)));
    }

    private PasReading submitted(String sentinelMemberId) {
        return read(submit(null, sentinelMemberId, "95810"), "95810");
    }

    private static TestResult assess(String expected, PasReading reading) {
        return Expectations.assess(expected, reading.outcome(), reading.asked(), reading.answers(), PasOutcome.values(),
                PasCodes.EXPECTATION_ELEMENTS).result();
    }

    private static ClaimResponseView only(PasReading reading) {
        assertThat(reading.detail().claimResponses()).hasSize(1);
        return reading.detail().claimResponses().get(0);
    }

    @Test
    void eachSentinelMemberProducesTheDeterminationThisTableStates() {
        Map<String, PasOutcome> table = new LinkedHashMap<>();
        table.put("P32H-PAS-APPROVED", PasOutcome.APPROVED);
        table.put("P32H-PAS-PENDED", PasOutcome.PENDED);
        table.put("P32H-PAS-DENIED", PasOutcome.DENIED);
        table.put("P32H-PAS-PARTIAL", PasOutcome.PARTIALLY_APPROVED);
        table.put("P32H-PAS-INQUIRE", PasOutcome.PENDED);
        table.put("P32H-PAS-NOTIFY", PasOutcome.PENDED);
        table.put("P32H-PAS-ATTACH-REQ", PasOutcome.ATTACHMENT_REQUIRED);
        table.put("P32H-PAS-ATTACH-OK", PasOutcome.APPROVED);
        table.put("P32H-PAS-BAD400", PasOutcome.REJECTED);
        table.put("P32H-PAS-DOWN", PasOutcome.UNREACHABLE);
        table.put("P32H-PAS-NO-DECISION", PasOutcome.NO_DECISION);
        table.put("P32H-PAS-NOMEM", PasOutcome.MEMBER_NOT_FOUND);
        table.put("P32H-PAS-TIMEOUT", PasOutcome.TIMEOUT);
        table.put("P32H-PAS-ERR500", PasOutcome.SERVICE_ERROR);
        table.put("P32H-PAS-UNKNOWN-CODE", PasOutcome.UNRECOGNISED_CODE);
        table.forEach((sentinel, outcome) -> assertThat(submitted(sentinel).outcome()).as(sentinel).isEqualTo(outcome));
        // Every scenario a member can select is in the table.
        assertThat(scenarios.list(MockOnyxPasClient.SCENARIOS).stream().filter(s -> s.sentinelMemberId() != null)).hasSize(table.size());
    }

    @Test
    void everySubmissionAndEveryInquiryProducesExactlyWhatItsFileClaims() {
        int submissions = 0;
        int inquiries = 0;
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            if (scenario.main() != null) {
                VendorExchange exchange = submit(scenario.id(), null, "95810");
                assertClaim(scenario.id() + " submission", scenario.expect(), exchange, read(exchange, "95810"));
                assertThat(exchange.scenarioId()).isEqualTo(scenario.id());
                assertThat(exchange.scenarioNote()).as(scenario.id()).isEqualTo("chosen in the scenario selector");
                submissions++;
            }
            if (scenario.step(MockOnyxPasClient.STEP_INQUIRE) != null) {
                VendorExchange exchange = inquire(scenario.id(), null, "95810");
                assertClaim(scenario.id() + " inquiry", scenario.step(MockOnyxPasClient.STEP_INQUIRE).expect(), exchange, read(exchange, "95810"));
                inquiries++;
            }
        }
        assertThat(submissions).isEqualTo(15);
        assertThat(inquiries).isEqualTo(9);
    }

    /** Compares one answer with the claim its file makes, and puts the claim through the comparison a tester's expectation would go through. */
    private static void assertClaim(String what, ScenarioExpect claim, VendorExchange exchange, PasReading reading) {
        assertThat(claim).as(what + " makes a claim").isNotNull();
        assertThat(reading.outcome().name()).as(what + " outcome").isEqualTo(claim.outcome());
        assertThat(exchange.failureType().name()).as(what + " error type").isEqualTo(claim.errorType());
        assertThat(exchange.httpStatus()).as(what + " status").isEqualTo(claim.httpStatus());
        if (!claim.codes().isEmpty()) {
            assertThat(reading.answers()).as(what + " answers").isNotEmpty();
        }
        claim.codes().forEach((element, code) -> reading.answers().forEach(answer ->
                assertThat(answer.codes().get(element)).as(what + " " + element + " for " + answer.subject()).isNotNull().contains(code)));
        StringBuilder declared = new StringBuilder(claim.outcome());
        claim.codes().forEach((element, code) -> declared.append("; ").append(element).append("=").append(code));
        assertThat(assess(declared.toString(), reading)).as(what + " as the expectation " + declared).isEqualTo(TestResult.PASS);
    }

    @Test
    void theClaimsOfTheFilesCoverEveryOutcomeThatSaysWhatWasDecided() {
        Set<String> claimed = new HashSet<>();
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            if (scenario.expect() != null) {
                claimed.add(scenario.expect().outcome());
            }
            if (scenario.step(MockOnyxPasClient.STEP_INQUIRE) != null) {
                claimed.add(scenario.step(MockOnyxPasClient.STEP_INQUIRE).expect().outcome());
            }
        }
        assertThat(claimed).contains("APPROVED", "PENDED", "DENIED", "PARTIALLY_APPROVED", "ATTACHMENT_REQUIRED", "NO_DECISION",
                "UNRECOGNISED_CODE", "MEMBER_NOT_FOUND", "REJECTED", "SERVICE_ERROR", "TIMEOUT", "UNREACHABLE");
    }

    @Test
    void approvedAndNoDecisionAreNeverTheSameAnswer() {
        VendorExchange approvedExchange = submit(null, "P32H-PAS-APPROVED", "95810");
        VendorExchange emptyExchange = submit(null, "P32H-PAS-NO-DECISION", "95810");
        PasReading approved = read(approvedExchange, "95810");
        PasReading noDecision = read(emptyExchange, "95810");

        // Both are HTTP 200 with no transport failure, and both ClaimResponses have the outcome
        // code complete. Only the review action tells them apart.
        assertThat(approvedExchange.httpStatus()).isEqualTo(200);
        assertThat(emptyExchange.httpStatus()).isEqualTo(200);
        assertThat(approvedExchange.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(emptyExchange.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(only(approved).outcome().code()).isEqualTo("complete");
        assertThat(only(noDecision).outcome().code()).isEqualTo("complete");

        assertThat(approved.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(noDecision.outcome()).isEqualTo(PasOutcome.NO_DECISION);
        assertThat(approved.outcome().label()).isNotEqualTo(noDecision.outcome().label());

        assertThat(approved.answers().get(0).codes()).containsEntry("review-action", List.of("A1"));
        assertThat(noDecision.answers().get(0).codes()).doesNotContainKey("review-action");
        assertThat(approved.ledgerCodes()).containsExactly("claim-outcome=complete", "review-action=A1");
        assertThat(noDecision.ledgerCodes()).containsExactly("claim-outcome=complete");
        assertThat(approved.decisions().get(0).source()).isEqualTo(DecisionSource.ITEM);
        assertThat(noDecision.decisions().get(0).source()).isEqualTo(DecisionSource.NONE);

        // The file's own disposition says "Approved." That wording is shown and decides nothing.
        assertThat(only(noDecision).disposition().value()).isEqualTo("Approved.");
        assertThat(only(noDecision).items()).hasSize(1);
        assertThat(only(noDecision).items().get(0).adjudications().get(0).reviewActions()).isEmpty();

        // A tester who expects an approval must not see a pass when nothing was decided.
        assertThat(assess("APPROVED", approved)).isEqualTo(TestResult.PASS);
        assertThat(assess("review-action=A1", approved)).isEqualTo(TestResult.PASS);
        assertThat(assess("APPROVED", noDecision)).isEqualTo(TestResult.FAIL);
        assertThat(assess("review-action=A1", noDecision)).isEqualTo(TestResult.FAIL);
        assertThat(assess("NO_DECISION", approved)).isEqualTo(TestResult.FAIL);
        // And nothing that was decided: not pended and not denied either.
        assertThat(assess("PENDED", noDecision)).isEqualTo(TestResult.FAIL);
        assertThat(assess("DENIED", noDecision)).isEqualTo(TestResult.FAIL);
        assertThat(assess("NO_DECISION", noDecision)).isEqualTo(TestResult.PASS);
    }

    @Test
    void deniedApprovedPendedPartialAndNoDecisionEachGiveADifferentOutcomeVerdictAndLedgerCodes() {
        Map<String, PasReading> readings = new LinkedHashMap<>();
        Map<String, VendorExchange> exchanges = new LinkedHashMap<>();
        for (String sentinel : List.of("P32H-PAS-DENIED", "P32H-PAS-APPROVED", "P32H-PAS-PENDED", "P32H-PAS-PARTIAL", "P32H-PAS-NO-DECISION")) {
            VendorExchange exchange = submit(null, sentinel, "95810", "95811");
            exchanges.put(sentinel, exchange);
            readings.put(sentinel, read(exchange, "95810", "95811"));
        }
        Set<PasOutcome> outcomes = new HashSet<>();
        Set<String> headlines = new HashSet<>();
        Set<Tone> tones = new HashSet<>();
        Set<List<String>> ledgerCodes = new HashSet<>();
        readings.forEach((sentinel, reading) -> {
            VerdictView verdict = PasVerdicts.verdict(reading, exchanges.get(sentinel), EpaOperation.PAS_SUBMIT);
            outcomes.add(reading.outcome());
            headlines.add(verdict.headline());
            tones.add(verdict.tone());
            ledgerCodes.add(reading.ledgerCodes());
        });
        assertThat(outcomes).containsExactlyInAnyOrder(PasOutcome.DENIED, PasOutcome.APPROVED, PasOutcome.PENDED, PasOutcome.PARTIALLY_APPROVED,
                PasOutcome.NO_DECISION);
        assertThat(headlines).hasSize(5);
        assertThat(tones).containsExactlyInAnyOrder(Tone.STOP, Tone.CLEAR, Tone.PENDING, Tone.ACTION, Tone.NO_ANSWER);
        assertThat(ledgerCodes).hasSize(5);

        // A denial reads as a denial, and a test that expected one passes on it.
        PasReading denied = readings.get("P32H-PAS-DENIED");
        assertThat(PasVerdicts.verdict(denied, exchanges.get("P32H-PAS-DENIED"), EpaOperation.PAS_SUBMIT).headline())
                .isEqualTo("The prior authorization is denied.");
        assertThat(assess("DENIED", denied)).isEqualTo(TestResult.PASS);
        assertThat(assess("APPROVED", denied)).isEqualTo(TestResult.FAIL);
        assertThat(denied.ledgerCodes()).containsExactly("claim-outcome=complete", "95810: review-action=A3",
                "95810: review-reason=placeholder-criteria-not-met", "95811: review-action=A3", "95811: review-reason=placeholder-criteria-not-met");
    }

    @Test
    void thePartialApprovalTiesEachDecisionToItsCodeSoOneExpectationPassesForOneItemAndFailsForTheOther() {
        PasReading reading = read(submit(null, "P32H-PAS-PARTIAL", "95810", "95811"), "95810", "95811");
        assertThat(reading.outcome()).isEqualTo(PasOutcome.PARTIALLY_APPROVED);
        assertThat(reading.decisions()).extracting(PasDecision::subject).containsExactly("95810", "95811");
        assertThat(reading.decisions()).extracting(PasDecision::outcome).containsExactly(PasOutcome.APPROVED, PasOutcome.DENIED);
        assertThat(assess("95810: review-action=A1", reading)).isEqualTo(TestResult.PASS);
        assertThat(assess("95811: review-action=A1", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("95811: review-action=A3", reading)).isEqualTo(TestResult.PASS);
        assertThat(reading.ledgerCodes()).containsExactly("claim-outcome=complete", "95810: review-action=A1", "95811: review-action=A3",
                "95811: review-reason=placeholder-criteria-not-met");
        // With one code sent, the second item of the file answers nothing that was asked about.
        PasReading oneCode = read(submit(null, "P32H-PAS-PARTIAL", "95810"), "95810");
        assertThat(oneCode.decisions()).extracting(PasDecision::subject).containsExactly("95810", "item 2");
        assertThat(oneCode.outcome()).isEqualTo(PasOutcome.PARTIALLY_APPROVED);
    }

    @Test
    void aSubmissionThatIsPendedIsApprovedWhenItIsInquiredAbout() {
        PasReading submitted = read(submit(null, "P32H-PAS-INQUIRE", "95810"), "95810");
        PasReading inquired = read(inquire(null, "P32H-PAS-INQUIRE", "95810"), "95810");
        assertThat(submitted.outcome()).isEqualTo(PasOutcome.PENDED);
        assertThat(inquired.outcome()).isEqualTo(PasOutcome.APPROVED);
        // The two answers are about the same claim, so the same ClaimResponse.
        assertThat(inquired.claimResponseId()).isEqualTo(submitted.claimResponseId()).isEqualTo("claimresponse-claim-test-1");
        assertThat(only(inquired).requestIdentifier().value()).isEqualTo("claim-test-1");
        assertThat(inquired.authorizationRef()).isEqualTo("PA-PLACEHOLDER-0001");
        assertThat(submitted.authorizationRef()).isNull();
    }

    @Test
    void anAttachmentThatIsRequiredAndOneThatWasAcceptedAreToldApartByTheStatusOfTheRequestForIt() {
        PasReading required = submitted("P32H-PAS-ATTACH-REQ");
        PasReading accepted = submitted("P32H-PAS-ATTACH-OK");
        assertThat(required.outcome()).isEqualTo(PasOutcome.ATTACHMENT_REQUIRED);
        assertThat(only(required).attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.carried()).isTrue();
            assertThat(attachment.status().value()).isEqualTo("active");
            assertThat(attachment.open()).isTrue();
            assertThat(attachment.wanted().get(0).value()).isEqualTo("Send the report of the attended sleep study.");
        });
        assertThat(accepted.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(only(accepted).attachments()).singleElement().satisfies(attachment -> {
            assertThat(attachment.status().value()).isEqualTo("completed");
            assertThat(attachment.open()).isFalse();
        });
        // The review action of the first is still pended: the request for documentation is read from structure, not from the code.
        assertThat(required.decisions().get(0).outcome()).isEqualTo(PasOutcome.PENDED);
    }

    @Test
    void theThreePlacementsOfAReviewActionAreAllFoundInTheBundledScenarios() {
        assertThat(only(submitted("P32H-PAS-APPROVED")).items().get(0).adjudications().get(0).reviewActions().get(0).foundAt())
                .isEqualTo("entry[0].resource.item[0].adjudication[0].extension[0]");
        assertThat(only(submitted("P32H-PAS-PENDED")).reviewActions().get(0).foundAt()).isEqualTo("entry[0].resource.extension[0]");
        assertThat(only(submitted("P32H-PAS-UNKNOWN-CODE")).items().get(0).reviewActions().get(0).foundAt())
                .isEqualTo("entry[0].resource.item[0].extension[0]");
        assertThat(submitted("P32H-PAS-PENDED").decisions().get(0).source()).isEqualTo(DecisionSource.RESPONSE);
    }

    @Test
    void theDesignedViewTakesEverythingTheBundledAnswersCarrySoNothingIsLeftOver() {
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            if (scenario.main() != null) {
                assertThat(read(submit(scenario.id(), null, "95810"), "95810").additionalFields()).as(scenario.id()).isEmpty();
            }
            if (scenario.step(MockOnyxPasClient.STEP_INQUIRE) != null) {
                assertThat(read(inquire(scenario.id(), null, "95810"), "95810").additionalFields()).as(scenario.id() + " inquiry").isEmpty();
            }
        }
        // The insurer the approved answer carries is listed as itself, with what it holds.
        assertThat(submitted("P32H-PAS-APPROVED").detail().otherResources()).singleElement().satisfies(resource -> {
            assertThat(resource.resourceType().value()).isEqualTo("Organization");
            assertThat(resource.fields()).extracting(f -> f.path()).containsExactly("entry[1].resource.name");
        });
    }

    @Test
    void notFoundIsToldApartFromAFaultAndFromARequestThatCarriedErrors() {
        VendorExchange notFound = submit(null, "P32H-PAS-NOMEM", "95810");
        VendorExchange rejected = submit(null, "P32H-PAS-BAD400", "95810");
        assertThat(read(notFound, "95810").outcome()).isEqualTo(PasOutcome.MEMBER_NOT_FOUND);
        assertThat(read(notFound, "95810").outcome().fault()).isFalse();
        assertThat(read(rejected, "95810").outcome()).isEqualTo(PasOutcome.REJECTED);
        assertThat(read(rejected, "95810").outcome().fault()).isTrue();
        // The member-not-found answer is an HTTP 200 whose error names the element and the follow-up.
        assertThat(only(read(notFound, "95810")).errors()).singleElement().satisfies(error -> {
            assertThat(error.codes()).containsExactly("75");
            assertThat(error.element().value()).isEqualTo("Claim.patient");
            assertThat(error.followupAction().value()).isEqualTo("Please Correct and Resubmit (https://codesystem.x12.org/005010/889|C)");
        });
    }

    @Test
    void aFaultResponseIsRenderedIssueByIssueAndATimeoutHasNoResponseToShow() {
        PasReading rejected = submitted("P32H-PAS-BAD400");
        assertThat(rejected.detail().responseKind()).isEqualTo(PasResponseKind.OPERATION_OUTCOME);
        assertThat(rejected.detail().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code().value()).isEqualTo("invalid");
            assertThat(issue.severity().value()).isEqualTo("error");
            assertThat(issue.diagnostics().value()).contains("Claim.insurance");
            assertThat(issue.expression()).extracting(s -> s.value()).containsExactly("Bundle.entry[0].resource.insurance");
        });
        VendorExchange timeout = submit(null, "P32H-PAS-TIMEOUT", "95810");
        assertThat(timeout.httpStatus()).isNull();
        assertThat(timeout.request().sent()).isFalse();
        assertThat(timeout.request().note()).contains("pas-16-timeout").contains("sentinel member ID P32H-PAS-TIMEOUT");
        assertThat(read(timeout, "95810").detail().responseKind()).isEqualTo(PasResponseKind.NO_RESPONSE);
        assertThat(read(timeout, "95810").answers()).isEmpty();
        assertThat(read(timeout, "95810").ledgerCodes()).isEmpty();
    }

    @Test
    void theMockAnswersEveryItemSoAnApprovalOfTwoCodesIsAnApprovalOfBoth() {
        VendorExchange exchange = submit(null, "P32H-PAS-APPROVED", "95810", "95811");
        PasReading reading = read(exchange, "95810", "95811");
        assertThat(reading.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(reading.decisions()).extracting(PasDecision::subject).containsExactly("95810", "95811");
        assertThat(reading.decisions()).extracting(PasDecision::source).containsOnly(DecisionSource.ITEM);
        assertThat(exchange.scenarioNote()).contains("repeated here for each of the 2 items sent");
        assertThat(assess("review-action=A1", reading)).isEqualTo(TestResult.PASS);
    }
}
