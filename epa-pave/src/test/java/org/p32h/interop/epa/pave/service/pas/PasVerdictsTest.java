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
import org.p32h.interop.epa.pave.model.Reached;
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

    // ---- PAS 2.0.1: where the authorization number and its period sit ----

    /** An approved item in the shape of PAS 2.0.1 ReferralAuthorizationResponseExample: number on the review action, period on the item, no preAuthRef. */
    private static String approvedAsIn201(int sequence, String number, String start, String end) {
        return "{\"extension\":[{\"url\":\"http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemPreAuthPeriod\","
                + "\"valuePeriod\":{\"start\":\"" + start + "\",\"end\":\"" + end + "\"}}],\"itemSequence\":" + sequence
                + ",\"adjudication\":[{\"category\":{\"coding\":[{\"code\":\"submitted\"}]},\"extension\":["
                + action("A1", ",{\"url\":\"number\",\"valueString\":\"" + number + "\"}") + "]}]}";
    }

    @Test
    void theAuthorizationNumberAndPeriodOfAnItemAreReadFromWherePas201PutsThem() {
        PasReading reading = read(bundle(claimResponse("\"outcome\":\"complete\"", items(approvedAsIn201(1, "AUTH0001", "2005-05-02",
                "2005-06-02")))), sent("95810"));
        VerdictView verdict = verdict(reading);
        assertThat(verdict.headline()).isEqualTo("The prior authorization is approved.");
        assertThat(verdict.supporting()).contains("Authorization number: AUTH0001.", "Authorized from 2005-05-02 to 2005-06-02.");
        assertThat(reading.decisions().get(0).authorizationNumbers()).containsExactly("AUTH0001");
        assertThat(reading.decisions().get(0).authorizedFrom()).isEqualTo("2005-05-02");
        assertThat(PasVerdicts.determination(reading)).extracting(DeterminationRow::label, DeterminationRow::code).contains(
                org.assertj.core.groups.Tuple.tuple("Authorization number", "AUTH0001"),
                org.assertj.core.groups.Tuple.tuple("Authorized period", "2005-05-02/2005-06-02"));
        // Nothing of it was dropped: the period extension is still listed with the item.
        assertThat(reading.detail().claimResponses().get(0).items().get(0).extensions()).hasSize(1);
        assertThat(reading.additionalFields()).isEmpty();
    }

    @Test
    void withTwoItemsEachNumberIsNamedForItsItemAndTheTopLevelReferenceIsKeptAsAFurtherSource() {
        PasReading reading = read(bundle(claimResponse("\"outcome\":\"complete\"", "\"preAuthRef\":\"PA-PLACEHOLDER-0009\"",
                items(approvedAsIn201(1, "AUTH0001", "2026-03-20", "2026-06-20"), approvedAsIn201(2, "AUTH0002", "2026-03-21", "2026-04-21")))),
                sent("95810", "E0601"));
        assertThat(verdict(reading).supporting()).contains("95810: Authorization number: AUTH0001.", "E0601: Authorization number: AUTH0002.",
                "E0601: Authorized from 2026-03-21 to 2026-04-21.", "For the response as a whole: Authorization number: PA-PLACEHOLDER-0009.");
    }

    @Test
    void theResponsesOwnPeriodIsLeftOutWhenEveryItemStatesItAndOtherwiseSaysItIsTheResponses() {
        // Every item states the period ClaimResponse.preAuthPeriod states: the line with no subject adds nothing.
        PasReading same = read(bundle(claimResponse("\"outcome\":\"complete\"", "\"preAuthPeriod\":{\"start\":\"2026-01-15\",\"end\":\"2026-04-15\"}",
                items(approvedAsIn201(1, "AUTH0001", "2026-01-15", "2026-04-15"), approvedAsIn201(2, "AUTH0001", "2026-01-15", "2026-04-15")))),
                sent("E0601", "95810"));
        assertThat(verdict(same).supporting()).containsExactly("This is the decision for each of: E0601, 95810.",
                "E0601: Authorization number: AUTH0001.", "E0601: Authorized from 2026-01-15 to 2026-04-15.",
                "95810: Authorization number: AUTH0001.", "95810: Authorized from 2026-01-15 to 2026-04-15.");
        // The items differ from it: the response's own period is named as the response's, not read as one more item's.
        PasReading differ = read(bundle(claimResponse("\"outcome\":\"complete\"", "\"preAuthPeriod\":{\"start\":\"2026-01-15\",\"end\":\"2026-06-15\"}",
                items(approvedAsIn201(1, "AUTH0001", "2026-01-15", "2026-04-15"), approvedAsIn201(2, "AUTH0002", "2026-02-15", "2026-06-15")))),
                sent("E0601", "95810"));
        assertThat(verdict(differ).supporting()).endsWith("For the response as a whole: Authorized from 2026-01-15 to 2026-06-15.")
                .doesNotContain("Authorized from 2026-01-15 to 2026-06-15.");
        // One item with its own period equal to the response's: stated once.
        PasReading one = read(bundle(claimResponse("\"outcome\":\"complete\"", "\"preAuthPeriod\":{\"start\":\"2026-01-15\",\"end\":\"2026-04-15\"}",
                items(approvedAsIn201(1, "AUTH0001", "2026-01-15", "2026-04-15")))), sent("E0601"));
        assertThat(verdict(one).supporting()).containsOnlyOnce("Authorized from 2026-01-15 to 2026-04-15.");
    }

    @Test
    void anInquiryAnsweredWithNoClaimResponseIsNoMatchingAuthorizationAndASubmissionSoAnsweredIsNoDecision() {
        // PAS 2.0.1 ClaimInquiryOperation: "the only output is a single Bundle with zero-to-many PASClaimInquiryResponses".
        String empty = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[]}";
        PasReading inquiry = mapper.read(response(200, empty), sent("95810"), true);
        assertThat(inquiry.outcome()).isEqualTo(PasOutcome.NO_MATCHING_AUTHORIZATION);
        VerdictView verdict = PasVerdicts.verdict(inquiry, response(200, empty), EpaOperation.PAS_INQUIRE);
        assertThat(verdict.headline()).isEqualTo("No prior authorization matches this inquiry.");
        assertThat(verdict.supporting()).contains("PAS 2.0.1 answers an inquiry with one ClaimResponse for each prior authorization that "
                + "matches it. This answer holds none, so the payer holds no authorization matching what was asked about.");
        assertThat(PasVerdicts.nextStep(inquiry, response(200, empty), EpaOperation.PAS_INQUIRE, RUN).action()).isEqualTo(NextAction.FIX_TEST_DATA);
        assertThat(inquiry.detail().responseNote()).contains("none means that none matched");

        // The same body after a submission is no decision, as before: the guide gives an empty answer no meaning there.
        assertThat(mapper.read(response(200, empty), sent("95810"), false).outcome()).isEqualTo(PasOutcome.NO_DECISION);
        // And an inquiry answered with an OperationOutcome alone is not read as "nothing matched".
        String outcomeOnly = bundle("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"information\",\"code\":\"informational\"}]}");
        assertThat(mapper.read(response(200, outcomeOnly), sent("95810"), true).outcome()).isNotEqualTo(PasOutcome.NO_MATCHING_AUTHORIZATION);
    }

    @Test
    void anAttachmentRequiredResultSaysWhatPas201DoesNextAndThatThisToolDoesNotBuildIt() {
        // PAS 2.0.1 specification.md (a new Claim that replaces the earlier one) and additionalinfo.md ($submit-attachment).
        PasReading reading = read(bundle(claimResponse("\"outcome\":\"complete\"", "\"communicationRequest\":[{\"reference\":"
                + "\"CommunicationRequest/cr-docs-1\"}]", items(decided(1, "A4")))), sent("95810"));
        assertThat(reading.outcome()).isEqualTo(PasOutcome.ATTACHMENT_REQUIRED);
        assertThat(verdict(reading).supporting()).contains("PAS 2.0.1 sends documentation after the payer asks for it in one of two ways: "
                + "a claim update, which is a new claim with its own identifier that replaces this one (Claim.related, relationship "
                + "replaces), or the CDex operation $submit-attachment. This tool builds neither.");
        NextStepView next = next(reading);
        assertThat(next.action()).isEqualTo(NextAction.START_PAS);
        assertThat(next.headline()).isEqualTo("Next: send the documentation the payer asked for. Submitting again from this tool sends a new "
                + "request with the same claim identifier, which is not the PAS 2.0.1 way.");
        assertThat(next.reason()).endsWith("This tool builds neither.");
    }

    @Test
    void aReviewActionOutsideThePlacePas201AllowsIsReadAndSaidToBeOutsideIt() {
        // PAS 2.0.1 ReviewAction context: Claim.item, ClaimResponse.item.adjudication. Not ClaimResponse, not ClaimResponse.item.
        PasReading onItem = read(bundle(claimResponse("\"outcome\":\"complete\"", items("{\"itemSequence\":1,\"extension\":[" + action("A1")
                + "]}"))), sent("95810"));
        assertThat(onItem.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(verdict(onItem).supporting()).contains("A review action sat on an item itself, not on its adjudication, where PAS 2.0.1 "
                + "places it. It was read all the same.");
        assertThat(onItem.detail().claimResponses().get(0).items().get(0).reviewActions().get(0).placement())
                .isEqualTo(PasResponseMapper.ON_THE_ITEM);

        PasReading onResponse = read(bundle(claimResponse("\"outcome\":\"complete\"", "\"extension\":[" + action("A1") + "]")),
                sent("95810", "E0601"));
        assertThat(onResponse.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(verdict(onResponse).supporting()).contains("The decision is stated once for the response as a whole, not item by item. "
                + "PAS 2.0.1 places a review action on each item's adjudication, not on the ClaimResponse itself; it was read all the same.");
        // In the place the guide gives it, nothing is said about placement.
        assertThat(verdict(decidedAs("A1")).supporting()).noneMatch(line -> line.contains("PAS 2.0.1 places"));
        assertThat(decidedAs("A1").detail().claimResponses().get(0).items().get(0).adjudications().get(0).reviewActions().get(0).placement())
                .isNull();
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
                "The decision is stated once for the response as a whole, not item by item. PAS 2.0.1 places a review action on each "
                        + "item's adjudication, not on the ClaimResponse itself; it was read all the same.");
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
                + "shown below, in the payer's own words.", PasVerdicts.GUIDE_ROUTE);
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

    private static final String MAY_HAVE_ACTED = "The payer may have received the submission and acted on it.";
    private static final String ASK_FIRST = "Next: check the environment, then ask for the status before sending again.";
    private static final String ASK_FIRST_BECAUSE = "The payer may have received the submission and acted on it. Ask for its status with an "
            + "inquiry first: sending it again without checking could create a second request.";
    private static final String SEND_AGAIN = "Next: check the environment, then send again.";

    @Test
    void aSubmissionThatMayHaveReachedThePayerIsToBeAskedAboutBeforeItIsSentAgain() {
        // No answer inside the read timeout: the request had gone out, and whether it arrived cannot be said.
        VendorExchange timeout = asSent(TestExchanges.timeout(EpaOperation.PAS_SUBMIT));
        assertThat(timeout.reached()).isEqualTo(Reached.CANNOT_SAY);
        PasReading reading = mapper.read(timeout, sent("95810"));
        assertThat(PasVerdicts.verdict(reading, timeout, EpaOperation.PAS_SUBMIT).supporting())
                .containsExactly("No response arrived inside the read timeout.", MAY_HAVE_ACTED);
        NextStepView next = PasVerdicts.nextStep(reading, timeout, EpaOperation.PAS_SUBMIT, RUN);
        assertThat(next.action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(next.headline()).isEqualTo(ASK_FIRST);
        assertThat(next.reason()).isEqualTo(ASK_FIRST_BECAUSE);
        // The claim id is carried so the status can be asked for.
        assertThat(next.carry()).containsEntry(NextStepView.CARRY_CLAIM_ID, List.of("claim-test-1"));

        // An answer that could not be used is no proof that nothing was done with the submission.
        for (VendorExchange answered : List.of(asSent(response(500, null)), asSent(response(200, "{\"resourceType\": ")))) {
            assertThat(answered.reached()).isEqualTo(Reached.YES);
            assertThat(PasVerdicts.verdict(mapper.read(answered), answered, EpaOperation.PAS_SUBMIT).supporting()).contains(MAY_HAVE_ACTED);
            assertThat(PasVerdicts.nextStep(mapper.read(answered), answered, EpaOperation.PAS_SUBMIT, RUN).headline()).isEqualTo(ASK_FIRST);
        }
        // A request the service refused is none of this: it said no, and the next step is about the request.
        VendorExchange refused = asSent(response(422, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"invalid\"}]}"));
        assertThat(PasVerdicts.verdict(mapper.read(refused), refused, EpaOperation.PAS_SUBMIT).supporting()).doesNotContain(MAY_HAVE_ACTED);
        assertThat(PasVerdicts.nextStep(mapper.read(refused), refused, EpaOperation.PAS_SUBMIT, RUN).action()).isEqualTo(NextAction.FIX_REQUEST);

        // An inquiry changes nothing, so it is simply sent again.
        NextStepView inquiry = PasVerdicts.nextStep(reading, timeout, EpaOperation.PAS_INQUIRE, RUN);
        assertThat(inquiry.headline()).isEqualTo(SEND_AGAIN);
        assertThat(inquiry.reason()).isEqualTo("No usable answer came back. This is a finding about the environment, not about the member or "
                + "the code.");
        assertThat(PasVerdicts.verdict(reading, timeout, EpaOperation.PAS_INQUIRE).supporting())
                .containsExactly("No response arrived inside the read timeout.");
    }

    @Test
    void aSubmissionThatCannotHaveReachedThePayerWasNotSubmittedAndCanBeSentAgain() {
        WireRequest attempted = TestExchanges.unavailable(EpaOperation.PAS_SUBMIT).request();
        // The connection was never opened, so nothing was delivered.
        String refusedHow = "The connection was refused or could not be opened.";
        VendorExchange refused = Exchanges.unavailable(attempted, 12L, refusedHow);
        assertThat(refused.reached()).isEqualTo(Reached.NO);
        PasReading reading = mapper.read(refused, sent("95810"));
        assertThat(reading.outcome()).isEqualTo(PasOutcome.UNREACHABLE);
        VerdictView verdict = PasVerdicts.verdict(reading, refused, EpaOperation.PAS_SUBMIT);
        assertThat(verdict.headline()).isEqualTo("The service could not be reached.");
        assertThat(verdict.supporting()).containsExactly(refusedHow, "Nothing was submitted: the request did not reach the payer.");
        NextStepView again = PasVerdicts.nextStep(reading, refused, EpaOperation.PAS_SUBMIT, RUN);
        assertThat(again.action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(again.headline()).isEqualTo(SEND_AGAIN);
        assertThat(again.reason()).isEqualTo("Nothing was submitted, because the request did not reach the payer. It is safe to send the same "
                + "request again.");

        // The connection failed after it was opened. The same outcome, and the opposite advice.
        String brokenHow = "The request could not be completed (IOException).";
        VendorExchange broken = Exchanges.cutOff(attempted, 12L, brokenHow);
        assertThat(broken.reached()).isEqualTo(Reached.CANNOT_SAY);
        PasReading afterBroken = mapper.read(broken, sent("95810"));
        assertThat(afterBroken.outcome()).isEqualTo(PasOutcome.UNREACHABLE);
        VerdictView unsure = PasVerdicts.verdict(afterBroken, broken, EpaOperation.PAS_SUBMIT);
        // "Could not be reached" would be a claim this tool cannot back when the request may have arrived.
        assertThat(unsure.headline()).isEqualTo("The connection to the service failed before an answer arrived.");
        assertThat(unsure.supporting()).containsExactly(brokenHow, MAY_HAVE_ACTED);
        NextStepView careful = PasVerdicts.nextStep(afterBroken, broken, EpaOperation.PAS_SUBMIT, RUN);
        assertThat(careful.headline()).isEqualTo(ASK_FIRST);
        assertThat(careful.reason()).isEqualTo(ASK_FIRST_BECAUSE);
        // The claim id is carried so the status can be asked for.
        assertThat(careful.carry()).containsEntry(NextStepView.CARRY_CLAIM_ID, List.of("claim-test-1"));

        // An inquiry changes nothing, so it is sent again however the connection failed, and nothing is said about a submission.
        for (VendorExchange failed : List.of(refused, broken)) {
            NextStepView inquiry = PasVerdicts.nextStep(mapper.read(failed, sent("95810")), failed, EpaOperation.PAS_INQUIRE, RUN);
            assertThat(inquiry.headline()).isEqualTo(SEND_AGAIN);
            assertThat(inquiry.reason()).isEqualTo("No answer came back. This is a finding about the environment, not about the member or the "
                    + "code.");
            assertThat(PasVerdicts.verdict(mapper.read(failed, sent("95810")), failed, EpaOperation.PAS_INQUIRE).supporting()).hasSize(1);
        }
    }

    @Test
    void onMockTheAdviceFollowsWhatTheScenarioModelsAndNeverSaysThatAnythingWasSent() {
        WireRequest notSent = new WireRequest("POST", null, "", List.of(), "{}", false, "Mock profile.");
        // A scenario that models a read timeout: on live the request would have gone out.
        VendorExchange mockTimeout = Exchanges.timeout(notSent, 5L).withScenario("pas-16-timeout", "chosen in the scenario selector");
        // The verdict names the scenario and nothing more: the error panel says once that the timeout was played and that
        // nothing was sent, and what a real timeout would mean for the submission is the next step's, worded as that.
        assertThat(PasVerdicts.verdict(mapper.read(mockTimeout), mockTimeout, EpaOperation.PAS_SUBMIT).supporting()).containsExactly(
                "Mock scenario pas-16-timeout answered: chosen in the scenario selector.");
        NextStepView rehearsed = PasVerdicts.nextStep(mapper.read(mockTimeout), mockTimeout, EpaOperation.PAS_SUBMIT, RUN);
        assertThat(rehearsed.headline()).isEqualTo("Next, had this been a real service: check the environment, then ask for the status "
                + "before sending again.");
        assertThat(rehearsed.reason()).isEqualTo("Had this been a real service, the payer might have received the submission: ask for its "
                + "status before sending it again. Sending it again without checking could create a second request.");
        assertThat(rehearsed.action()).isEqualTo(NextAction.CHECK_ENVIRONMENT);
        assertThat(rehearsed.carry()).containsKey(NextStepView.CARRY_RUN_ID);
        // A scenario that models a refused connection: nothing would have been delivered.
        VendorExchange mockRefused = Exchanges.unavailable(notSent, 5L, "The connection was refused or could not be opened.");
        NextStepView down = PasVerdicts.nextStep(mapper.read(mockRefused), mockRefused, EpaOperation.PAS_SUBMIT, RUN);
        assertThat(down.headline()).isEqualTo(SEND_AGAIN);
        assertThat(down.reason()).startsWith("Nothing was submitted");
        // The wording is about the payer and never about this machine, so it is true on both profiles.
        for (VendorExchange mock : List.of(mockTimeout, mockRefused)) {
            VerdictView verdict = PasVerdicts.verdict(mapper.read(mock), mock, EpaOperation.PAS_SUBMIT);
            NextStepView next = PasVerdicts.nextStep(mapper.read(mock), mock, EpaOperation.PAS_SUBMIT, RUN);
            assertThat(verdict.supporting() + next.headline() + next.reason()).doesNotContain("was sent").doesNotContain("left this machine");
        }
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
