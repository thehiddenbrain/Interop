package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.PasJson.action;
import static org.p32h.interop.epa.pave.service.pas.PasJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.PasJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.PasJson.decided;
import static org.p32h.interop.epa.pave.service.pas.PasJson.item;
import static org.p32h.interop.epa.pave.service.pas.PasJson.items;
import static org.p32h.interop.epa.pave.service.pas.PasJson.response;
import static org.p32h.interop.epa.pave.service.pas.PasJson.sent;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.DeterminationRow;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

/** The plain language answer, the next step and the determination grid for each kind of PAS result. */
class PasVerdictsTest {

    private static final String RUN = "run-00000000-0000-4000-8000-000000000001";
    private final PasResponseMapper mapper = new PasResponseMapper();

    private PasReading read(String body, SentClaim sent) {
        return mapper.read(response(200, body), sent);
    }

    private static VerdictView verdict(PasReading reading) {
        return PasVerdicts.verdict(reading, response(200, "{}"), EpaOperation.PAS_SUBMIT);
    }

    private static NextStepView next(PasReading reading) {
        return PasVerdicts.nextStep(reading, response(200, "{}"), EpaOperation.PAS_SUBMIT, RUN);
    }

    private PasReading decidedAs(String code) {
        return read(bundle(claimResponse("\"outcome\":\"complete\"", items(decided(1, code)))), sent("95810"));
    }

    @Test
    void eachDecisionIsWordedAsWhatItIsAndADenialIsCalledADenial() {
        assertThat(verdict(decidedAs("A1")).headline()).isEqualTo("The prior authorization is approved.");
        assertThat(verdict(decidedAs("A6")).headline()).isEqualTo("The prior authorization is approved with changes.");
        assertThat(verdict(decidedAs("A2")).headline()).isEqualTo("The prior authorization is partially approved.");
        assertThat(verdict(decidedAs("A3")).headline()).isEqualTo("The prior authorization is denied.");
        assertThat(verdict(decidedAs("A3")).tone()).isEqualTo(Tone.STOP);
        assertThat(verdict(decidedAs("A3")).outcomeCode()).isEqualTo("DENIED");
        assertThat(verdict(decidedAs("A4")).headline()).isEqualTo("The prior authorization is pended.");
        assertThat(verdict(decidedAs("A4")).supporting()).contains("There is no final decision yet. This is not an approval and not a denial.");
        assertThat(verdict(decidedAs("C")).headline()).isEqualTo("The authorization request is cancelled.");
        assertThat(verdict(decidedAs("CT")).headline()).isEqualTo("The payer asks to be contacted about this request.");
        assertThat(verdict(decidedAs("NA")).headline()).isEqualTo("The payer answered that no action is required for this request.");
    }

    @Test
    void noDecisionSaysInSoManyWordsThatItIsNotAnApprovalAndNeverQuotesThePayersWording() {
        PasReading reading = read(bundle(claimResponse("\"outcome\":\"complete\"", "\"disposition\":\"Approved. Certified in total.\"",
                items(item(1)))), sent("95810"));
        VerdictView verdict = verdict(reading);
        assertThat(verdict.headline()).isEqualTo("The service answered but returned no decision.");
        assertThat(verdict.tone()).isEqualTo(Tone.NO_ANSWER);
        assertThat(verdict.supporting()).containsExactly(
                "This is not an approval, and it is not a denial or a pended request either. The response carries no review action, so "
                        + "the payer has stated no decision.",
                "The response does carry wording from the payer. This tool reads codes and never wording: it is shown below as it came.");
        assertThat(verdict.headline() + verdict.supporting()).doesNotContain("Certified in total");
        assertThat(next(reading).action()).isEqualTo(NextAction.REVIEW);
        assertThat(next(reading).reason()).contains("does not say whether the request is approved, pended or denied");

        // No ClaimResponse at all is worded as that.
        PasReading none = read("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[]}", sent("95810"));
        assertThat(verdict(none).headline()).isEqualTo("The service answered without a ClaimResponse, so there is no decision.");
        assertThat(verdict(none).supporting().get(0)).endsWith("The response carries no ClaimResponse at all.");
    }

    @Test
    void theHeadlinesOfTheOutcomesThatSayWhatWasDecidedAreAllDifferent() {
        Set<String> headlines = new HashSet<>();
        for (String code : List.of("A1", "A2", "A3", "A4", "A6", "C", "CT", "NA", "ZZ")) {
            headlines.add(verdict(decidedAs(code)).headline());
        }
        headlines.add(verdict(read(bundle(claimResponse(items(item(1)))), sent("95810"))).headline());
        assertThat(headlines).hasSize(10);
    }

    @Test
    void aPartialApprovalAndAMixOfDecisionsNameEachItemSoNoneIsReadAsAnother() {
        PasReading partial = read(bundle(claimResponse(items(decided(1, "A1"), decided(2, "A3")))), sent("95810", "95811"));
        assertThat(verdict(partial).headline()).isEqualTo("The prior authorization is partially approved.");
        assertThat(verdict(partial).supporting()).containsExactly("95810: approved (review action A1).", "95811: denied (review action A3).");
        assertThat(next(partial).action()).isEqualTo(NextAction.FLOW_ENDS);

        PasReading mixed = read(bundle(claimResponse(items(decided(1, "A1")))), sent("95810", "95811"));
        assertThat(verdict(mixed).headline()).isEqualTo("The items in this request did not all receive the same decision.");
        assertThat(verdict(mixed).supporting()).containsExactly("95810: approved (review action A1).",
                "95811: no decision was returned for this item. That is not an approval.");
        assertThat(next(mixed).action()).isEqualTo(NextAction.REVIEW);

        // A mix with a pended item is not finished, so the next step is to ask again.
        PasReading open = read(bundle(claimResponse(items(decided(1, "A1"), decided(2, "A4")))), sent("95810", "95811"));
        assertThat(verdict(open).supporting()).contains("95811: pended, with no final decision yet (review action A4).");
        assertThat(next(open).action()).isEqualTo(NextAction.CHECK_STATUS);
    }

    @Test
    void anUnrecognisedCodeIsQuotedAsItCame() {
        VerdictView verdict = verdict(decidedAs("ZZ"));
        assertThat(verdict.headline()).isEqualTo("The service returned a decision code this tool does not recognise.");
        assertThat(verdict.supporting()).containsExactly("Unrecognised code: ZZ.");
        assertThat(next(decidedAs("ZZ")).action()).isEqualTo(NextAction.REVIEW);
    }

    @Test
    void theAuthorizationNumberAndItsPeriodAreStatedUnderAnApproval() {
        PasReading reading = read(bundle(claimResponse("\"preAuthRef\":\"PA-PLACEHOLDER-0001\"",
                "\"preAuthPeriod\":{\"start\":\"2026-01-15\",\"end\":\"2026-04-15\"}", items(decided(1, "A1"), decided(2, "A1")))), sent("95810", "95811"));
        VerdictView verdict = verdict(reading);
        assertThat(verdict.supporting()).containsExactly("This is the decision for each of: 95810, 95811.",
                "Authorization number: PA-PLACEHOLDER-0001.", "Authorized from 2026-01-15 to 2026-04-15.");
        // A printed verdict never holds the number.
        assertThat(verdict.toString()).doesNotContain("PA-PLACEHOLDER");
        assertThat(verdict(read(bundle(claimResponse("\"preAuthPeriod\":{\"end\":\"2026-04-15\"}", items(decided(1, "A1")))), sent("95810")))
                .supporting()).containsExactly("Authorized until 2026-04-15.");
    }

    @Test
    void aDecisionStatedOnceForTheWholeResponseIsSaidToBeSo() {
        PasReading reading = read(bundle(claimResponse("\"extension\":[" + action("A4") + "]")), sent("95810", "95811"));
        assertThat(verdict(reading).supporting()).contains("This is the decision for each of: 95810, 95811.",
                "The decision is stated once for the response as a whole, not item by item.");
        // The grid says it too, on each item's row.
        assertThat(PasVerdicts.determination(reading)).filteredOn(row -> row.label().equals("Decision")).extracting(DeterminationRow::display)
                .containsExactly("Pended (stated for the whole response)", "Pended (stated for the whole response)");
    }

    @Test
    void aMockAnswerSaysFirstWhichScenarioItCameFrom() {
        VendorExchange exchange = response(200, bundle(claimResponse(items(decided(1, "A1"))))).withScenario("pas-01-approved",
                "selected by the sentinel member ID P32H-PAS-APPROVED");
        VerdictView verdict = PasVerdicts.verdict(mapper.read(exchange, sent("95810")), exchange, EpaOperation.PAS_SUBMIT);
        assertThat(verdict.supporting().get(0)).isEqualTo("Mock scenario pas-01-approved answered: selected by the sentinel member ID "
                + "P32H-PAS-APPROVED.");
    }

    @Test
    void theNextStepFollowsTheDecisionAndCarriesWhatAStatusCheckStartsFrom() {
        NextStepView pended = next(decidedAs("A4"));
        assertThat(pended.action()).isEqualTo(NextAction.CHECK_STATUS);
        assertThat(pended.headline()).isEqualTo("Next: ask for the status with an inquiry, or wait for a notification.");
        assertThat(pended.carry()).containsEntry(NextStepView.CARRY_CLAIM_ID, List.of("claim-test-1"))
                .containsEntry(NextStepView.CARRY_RUN_ID, List.of(RUN)).containsEntry(NextStepView.CARRY_PROCEDURE_CODES, List.of("95810"));
        // Only the keys the next step reads, and nothing invented.
        assertThat(pended.carry()).containsOnlyKeys(NextStepView.CARRY_CLAIM_ID, NextStepView.CARRY_RUN_ID, NextStepView.CARRY_PROCEDURE_CODES);

        for (String code : List.of("A1", "A2", "A3", "A6", "C", "NA")) {
            assertThat(next(decidedAs(code)).action()).as(code).isEqualTo(NextAction.FLOW_ENDS);
            assertThat(next(decidedAs(code)).reason()).as(code).isNotBlank();
        }
        assertThat(next(decidedAs("A3")).reason()).contains("denied").contains("final decision");
        assertThat(next(decidedAs("CT")).action()).isEqualTo(NextAction.REVIEW);
        assertThat(next(decidedAs("CT")).headline()).startsWith("Next: contact the payer");

        // With no claim id and no run to follow, nothing is carried under those keys.
        NextStepView unknown = PasVerdicts.nextStep(mapper.read(response(200, bundle(claimResponse(items(decided(1, "A4")))))),
                response(200, "{}"), EpaOperation.PAS_INQUIRE, null);
        assertThat(unknown.carry()).isEmpty();
    }

    @Test
    void anAttachmentThatIsRequiredSendsTheTesterBackToSubmitWithIt() {
        PasReading reading = read(bundle(claimResponse(items(decided(1, "A4")), "\"communicationRequest\":[{\"reference\":\"CommunicationRequest/d\"}]")),
                sent("95810"));
        assertThat(verdict(reading).headline()).isEqualTo("The payer needs more documentation before it will decide.");
        assertThat(verdict(reading).supporting()).containsExactly("The response refers to 1 request for documentation. What is asked for is "
                + "shown below, in the payer's own words.");
        assertThat(next(reading).action()).isEqualTo(NextAction.START_PAS);
        assertThat(next(reading).carry()).containsEntry(NextStepView.CARRY_CLAIM_ID, List.of("claim-test-1"));
        // An approval that still refers to an open request says so beneath it.
        PasReading approved = read(bundle(claimResponse(items(decided(1, "A1")), "\"communicationRequest\":[{\"reference\":\"CommunicationRequest/d\"}]")),
                sent("95810"));
        assertThat(verdict(approved).supporting()).containsExactly("The response also refers to a request for documentation that is not shown as closed.");
    }

    @Test
    void errorsAndNotFoundEachPointAtWhatTheTesterCanFix() {
        PasReading errors = read(bundle(claimResponse("\"outcome\":\"error\"", "\"error\":[{\"code\":{\"coding\":[{\"code\":\"33\"}]}}]")), sent("95810"));
        assertThat(verdict(errors).headline()).isEqualTo("The payer returned errors and no decision.");
        assertThat(verdict(errors).supporting()).containsExactly("The response reports 1 error. Each is listed below with its code and the element "
                + "it points at.");
        assertThat(next(errors).action()).isEqualTo(NextAction.FIX_REQUEST);

        PasReading member = mapper.read(response(404, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"not-found\","
                + "\"expression\":[\"Claim.patient\"]}]}"), sent("95810"));
        assertThat(verdict(member).tone()).isEqualTo(Tone.DATA);
        assertThat(verdict(member).supporting()).containsExactly("This is a finding about the test data, not about the environment.");
        assertThat(next(member).action()).isEqualTo(NextAction.FIX_TEST_DATA);
        assertThat(next(member).headline()).isEqualTo("Next: check the member ID against the test data.");

        PasReading claim = mapper.read(response(404, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"not-found\","
                + "\"expression\":[\"Claim.identifier\"]}]}"), sent("95810"));
        assertThat(verdict(claim).headline()).isEqualTo("The service does not recognise this authorization request.");
        assertThat(next(claim).headline()).isEqualTo("Next: check the claim id against the submission it should belong to.");
    }

    @Test
    void aRejectionPointsAtTheCredentialsTheUrlOrTheRequestAccordingToItsStatus() {
        VendorExchange unauthorised = response(401, null);
        VendorExchange missing = response(404, null);
        VendorExchange invalid = response(422, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"invalid\"}]}");
        assertThat(PasVerdicts.verdict(mapper.read(unauthorised), unauthorised, EpaOperation.PAS_SUBMIT).headline())
                .isEqualTo("The service did not accept the request as sent (HTTP 401).");
        assertThat(PasVerdicts.nextStep(mapper.read(unauthorised), unauthorised, EpaOperation.PAS_SUBMIT, RUN).headline())
                .isEqualTo("Next: check the credentials on the Config tab.");
        assertThat(PasVerdicts.nextStep(mapper.read(missing), missing, EpaOperation.PAS_SUBMIT, RUN).headline())
                .isEqualTo("Next: check the PAS url on the Config tab.");
        assertThat(PasVerdicts.nextStep(mapper.read(invalid), invalid, EpaOperation.PAS_SUBMIT, RUN).action()).isEqualTo(NextAction.FIX_REQUEST);
    }

    /** The same exchange as it would be had the request really left the machine. */
    private static VendorExchange asSent(VendorExchange exchange) {
        WireRequest request = exchange.request();
        WireRequest sent = new WireRequest(request.method(), request.url(), request.urlTemplate(), request.headers(), request.body(), true, null);
        return exchange.httpStatus() == null ? Exchanges.timeout(sent, exchange.elapsedMillis())
                : Exchanges.fromResponse(sent, exchange.httpStatus(), exchange.responseHeaders(), exchange.responseBody(), exchange.elapsedMillis(), true);
    }

    @Test
    void aSubmissionThatGotNoUsableAnswerMayStillHaveBeenReceivedAndTheTesterIsToldBeforeSendingAgain() {
        VendorExchange timeout = asSent(TestExchanges.timeout(EpaOperation.PAS_SUBMIT));
        PasReading reading = mapper.read(timeout, sent("95810"));
        assertThat(PasVerdicts.verdict(reading, timeout, EpaOperation.PAS_SUBMIT).supporting())
                .contains("The submission was sent. Whether the payer received and kept it is not known.");
        NextStepView next = PasVerdicts.nextStep(reading, timeout, EpaOperation.PAS_SUBMIT, RUN);
        assertThat(next.action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(next.headline()).isEqualTo("Next: check the environment, then ask for the status before sending again.");
        assertThat(next.reason()).contains("could create a second request");
        // The claim id is carried so the status can be asked for.
        assertThat(next.carry()).containsEntry(NextStepView.CARRY_CLAIM_ID, List.of("claim-test-1"));

        VendorExchange serverError = asSent(response(500, null));
        assertThat(PasVerdicts.nextStep(mapper.read(serverError), serverError, EpaOperation.PAS_SUBMIT, RUN).headline())
                .contains("ask for the status before sending again");

        // An inquiry changes nothing, so it is simply sent again.
        assertThat(PasVerdicts.nextStep(reading, timeout, EpaOperation.PAS_INQUIRE, RUN).headline())
                .isEqualTo("Next: check the environment, then send again.");
        assertThat(PasVerdicts.verdict(reading, timeout, EpaOperation.PAS_INQUIRE).supporting()).noneMatch(line -> line.contains("was sent"));
        // On the mock profile nothing was sent, and the result does not say that it was.
        VendorExchange mockTimeout = Exchanges.timeout(new WireRequest("POST", null, "", List.of(), "{}", false, "Mock profile."), 5L);
        assertThat(PasVerdicts.verdict(mapper.read(mockTimeout), mockTimeout, EpaOperation.PAS_SUBMIT).supporting())
                .noneMatch(line -> line.contains("was sent"));
        assertThat(PasVerdicts.nextStep(mapper.read(mockTimeout), mockTimeout, EpaOperation.PAS_SUBMIT, RUN).headline())
                .isEqualTo("Next: check the environment, then send again.");
        // A service that was never reached received nothing.
        VendorExchange unreachable = TestExchanges.unavailable(EpaOperation.PAS_SUBMIT);
        NextStepView down = PasVerdicts.nextStep(mapper.read(unreachable), unreachable, EpaOperation.PAS_SUBMIT, RUN);
        assertThat(down.reason()).startsWith("The service was not reached, so it received nothing.");
        assertThat(PasVerdicts.verdict(mapper.read(unreachable), unreachable, EpaOperation.PAS_SUBMIT).supporting())
                .containsExactly("The connection was refused or could not be opened.");
    }

    @Test
    void aBodyThatCouldNotBeReadAndNoBodyAtAllAreWordedApart() {
        VendorExchange malformed = response(200, "{\"resourceType\": ");
        VendorExchange empty = response(200, null);
        assertThat(PasVerdicts.verdict(mapper.read(malformed), malformed, EpaOperation.PAS_SUBMIT).headline())
                .isEqualTo("The service answered with a body that could not be read (HTTP 200).");
        assertThat(PasVerdicts.verdict(mapper.read(empty), empty, EpaOperation.PAS_SUBMIT).headline())
                .isEqualTo("The service answered with no body (HTTP 200).");
    }

    @Test
    void everyOutcomeHasANextStepWithAReason() {
        // Outcomes that depend on the transport are driven by an exchange of that kind. The rest by a body.
        Set<NextAction> seen = EnumSet.noneOf(NextAction.class);
        for (String code : List.of("A1", "A2", "A3", "A4", "A6", "C", "CT", "NA", "ZZ")) {
            NextStepView step = next(decidedAs(code));
            assertThat(step.reason()).as(code).isNotBlank();
            seen.add(step.action());
        }
        assertThat(seen).contains(NextAction.FLOW_ENDS, NextAction.CHECK_STATUS, NextAction.REVIEW);
    }

    // ---- the determination grid ----

    @Test
    void theGridOfASingleDecisionListsItsCodeItsMeaningAndTheProcessingStatus() {
        List<DeterminationRow> rows = PasVerdicts.determination(read(bundle(claimResponse("\"outcome\":\"complete\"",
                "\"preAuthRef\":\"PA-PLACEHOLDER-0001\"", items(decided(1, "A1")))), sent("95810")));
        assertThat(rows).containsExactly(
                new DeterminationRow(null, "Decision", "A1", "Certified in total", "review-action", true, Presence.VALUE),
                new DeterminationRow(null, "Processing status", "complete", "Complete: processing has finished", "claim-outcome", true,
                        Presence.VALUE),
                new DeterminationRow(null, "Authorization number", "PA-PLACEHOLDER-0001", null, "preAuthRef", true, Presence.VALUE));
    }

    @Test
    void theGridCarriesOneGroupOfRowsForEachItemAndAnUndecidedItemIsShownAsNotReturned() {
        List<DeterminationRow> rows = PasVerdicts.determination(read(bundle(claimResponse("\"outcome\":\"complete\"",
                items(item(1, action("A1")), item(2, action("A3", ",{\"url\":\"reasonCode\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"R1\"}]}}"))))),
                sent("95810", "95811", "E0601")));
        assertThat(rows).containsExactly(
                new DeterminationRow("95810", "Decision", "A1", "Certified in total", "review-action", true, Presence.VALUE),
                new DeterminationRow("95811", "Decision", "A3", "Not certified", "review-action", true, Presence.VALUE),
                new DeterminationRow("95811", "Decision reason", "R1", null, "review-reason", true, Presence.VALUE),
                // The third code was asked about and nothing was decided for it. The row is there, and it is empty.
                DeterminationRow.notReturned("E0601", "Decision", "review-action"),
                new DeterminationRow(null, "Processing status", "complete", "Complete: processing has finished", "claim-outcome", true,
                        Presence.VALUE));
    }

    @Test
    void anEmptyAnswerStillGetsItsRowsSoItDoesNotLookLikeADecision() {
        assertThat(PasVerdicts.determination(mapper.read(TestExchanges.timeout(EpaOperation.PAS_SUBMIT)))).containsExactly(
                DeterminationRow.notReturned(null, "Decision", "review-action"),
                DeterminationRow.notReturned(null, "Processing status", "claim-outcome"));
        // A ClaimResponse with no review action and no outcome code: both rows, both not returned.
        assertThat(PasVerdicts.determination(read(bundle(claimResponse()), sent("95810")))).containsExactly(
                DeterminationRow.notReturned(null, "Decision", "review-action"),
                new DeterminationRow(null, "Processing status", null, null, "claim-outcome", true, Presence.ABSENT));
        // A review action that was sent with no code in it is shown as present and empty, which is not the same as absent.
        assertThat(PasVerdicts.determination(read(bundle(claimResponse(items(item(1, PasJson.actionOf("{\"url\":\"number\",\"valueString\":\"n\"}"))))),
                sent("95810"))).get(0)).isEqualTo(new DeterminationRow(null, "Decision", null, null, "review-action", true, Presence.EMPTY));
    }

    @Test
    void anUnrecognisedCodeAnErrorAndARequestForDocumentationEachHaveTheirRow() {
        List<DeterminationRow> unknown = PasVerdicts.determination(decidedAs("ZZ"));
        assertThat(unknown.get(0)).isEqualTo(new DeterminationRow(null, "Decision", "ZZ", "Unrecognised code: ZZ", "review-action", false, Presence.VALUE));

        List<DeterminationRow> rows = PasVerdicts.determination(read(bundle(claimResponse("\"outcome\":\"error\"",
                "\"error\":[{\"code\":{\"coding\":[{\"code\":\"33\"}]}}]", "\"communicationRequest\":[{\"reference\":\"CommunicationRequest/d\"}]")),
                sent("95810")));
        assertThat(rows).containsExactly(
                DeterminationRow.notReturned(null, "Decision", "review-action"),
                new DeterminationRow(null, "Error", "33", null, "error-code", true, Presence.VALUE),
                new DeterminationRow(null, "Processing status", "error", "Error: the request could not be processed", "claim-outcome", true,
                        Presence.VALUE),
                new DeterminationRow(null, "Documentation request", null, "Not shown as closed", "communicationRequest", true, Presence.ABSENT));
    }
}
