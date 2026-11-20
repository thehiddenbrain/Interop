package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.PasJson.action;
import static org.p32h.interop.epa.pave.service.pas.PasJson.actionOf;
import static org.p32h.interop.epa.pave.service.pas.PasJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.PasJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.PasJson.claimResponseWithId;
import static org.p32h.interop.epa.pave.service.pas.PasJson.decided;
import static org.p32h.interop.epa.pave.service.pas.PasJson.item;
import static org.p32h.interop.epa.pave.service.pas.PasJson.items;
import static org.p32h.interop.epa.pave.service.pas.PasJson.response;
import static org.p32h.interop.epa.pave.service.pas.PasJson.sent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.client.pas.SentClaim;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.pas.AttachmentRefView;
import org.p32h.interop.epa.pave.model.pas.ClaimResponseView;
import org.p32h.interop.epa.pave.model.pas.DecisionSource;
import org.p32h.interop.epa.pave.model.pas.ExtensionView;
import org.p32h.interop.epa.pave.model.pas.PasDecision;
import org.p32h.interop.epa.pave.model.pas.PasErrorView;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.PasResponseKind;
import org.p32h.interop.epa.pave.model.pas.ReviewActionView;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

class PasResponseMapperTest {

    private final PasResponseMapper mapper = new PasResponseMapper();

    private PasReading read(String body, SentClaim sent) {
        return mapper.read(response(200, body), sent);
    }

    private static ClaimResponseView only(PasReading reading) {
        assertThat(reading.detail().claimResponses()).hasSize(1);
        return reading.detail().claimResponses().get(0);
    }

    private static List<String> paths(PasReading reading) {
        return reading.additionalFields().stream().map(AdditionalField::path).toList();
    }

    private static TestResult assess(String expected, PasReading reading) {
        return Expectations.assess(expected, reading.outcome(), reading.asked(), reading.answers(), PasOutcome.values(),
                PasCodes.EXPECTATION_ELEMENTS).result();
    }

    // ---- the distinction this tool exists for ----

    @Test
    void anAbsentNullOrEmptyReviewActionIsNeverReadAsADecision() {
        PasReading none = read(bundle(claimResponse("\"outcome\":\"complete\"", items(item(1)))), sent("95810"));
        PasReading noCode = read(bundle(claimResponse("\"outcome\":\"complete\"",
                items(item(1, actionOf("{\"url\":\"number\",\"valueString\":\"PA-PLACEHOLDER-0001\"}"))))), sent("95810"));
        PasReading nullCode = read(bundle(claimResponse("\"outcome\":\"complete\"",
                items(item(1, actionOf("{\"url\":\"code\",\"valueCode\":null}"))))), sent("95810"));
        PasReading emptyCode = read(bundle(claimResponse("\"outcome\":\"complete\"",
                items(item(1, actionOf("{\"url\":\"code\",\"valueCode\":\"\"}"))))), sent("95810"));
        PasReading noValue = read(bundle(claimResponse("\"outcome\":\"complete\"", items(item(1, actionOf("{\"url\":\"code\"}"))))), sent("95810"));

        for (PasReading reading : List.of(none, noCode, nullCode, emptyCode, noValue)) {
            assertThat(reading.outcome()).isEqualTo(PasOutcome.NO_DECISION);
            assertThat(reading.outcome()).isNotIn(PasOutcome.APPROVED, PasOutcome.PENDED, PasOutcome.DENIED);
            assertThat(reading.decisions()).singleElement().satisfies(decision -> {
                assertThat(decision.outcome()).isEqualTo(PasOutcome.NO_DECISION);
                assertThat(decision.source()).isEqualTo(DecisionSource.NONE);
                assertThat(decision.codes()).isEmpty();
            });
            assertThat(reading.answers().get(0).codes()).doesNotContainKey(PasCodes.REVIEW_ACTION);
            // A tester who expects an approval must not see a pass when nothing was decided.
            assertThat(assess("APPROVED", reading)).isEqualTo(TestResult.FAIL);
            assertThat(assess("review-action=A1", reading)).isEqualTo(TestResult.FAIL);
            assertThat(assess("NO_DECISION", reading)).isEqualTo(TestResult.PASS);
        }
        // And the screen can still tell them apart: no review action at all, one sent with no code,
        // one whose code is null, one whose code is empty.
        assertThat(none.decisions().get(0).reviewActions()).isEmpty();
        assertThat(noCode.decisions().get(0).reviewActions()).singleElement().satisfies(code -> assertThat(code.presence()).isEqualTo(Presence.EMPTY));
        assertThat(nullCode.decisions().get(0).reviewActions()).singleElement().satisfies(code -> assertThat(code.presence()).isEqualTo(Presence.NULL));
        assertThat(emptyCode.decisions().get(0).reviewActions()).singleElement().satisfies(code -> assertThat(code.presence()).isEqualTo(Presence.EMPTY));
        assertThat(noValue.decisions().get(0).reviewActions()).singleElement().satisfies(code -> assertThat(code.presence()).isEqualTo(Presence.EMPTY));
        // The number the code-less review action did carry is still shown.
        assertThat(only(noCode).items().get(0).adjudications().get(0).reviewActions().get(0).number().value()).isEqualTo("PA-PLACEHOLDER-0001");
    }

    @Test
    void theWordingOfADispositionIsNeverReadAsADecision() {
        PasReading reading = read(bundle(claimResponse("\"outcome\":\"complete\"", "\"disposition\":\"Approved. Certified in total.\"",
                "\"preAuthRef\":\"PA-PLACEHOLDER-0001\"", items(item(1)))), sent("95810"));
        // The sentence says approved, the outcome code says complete and there is even an
        // authorization number. None of that is a review action.
        assertThat(reading.outcome()).isEqualTo(PasOutcome.NO_DECISION);
        assertThat(only(reading).disposition().value()).isEqualTo("Approved. Certified in total.");
        assertThat(only(reading).outcome().code()).isEqualTo("complete");
        assertThat(assess("APPROVED", reading)).isEqualTo(TestResult.FAIL);
        // And the reverse: wording that says denied on a certified response does not make it a denial.
        PasReading certified = read(bundle(claimResponse("\"disposition\":\"Denied.\"", items(decided(1, "A1")))), sent("95810"));
        assertThat(certified.outcome()).isEqualTo(PasOutcome.APPROVED);
    }

    @Test
    void aClaimResponseOutcomeCodeSaysHowFarProcessingGotAndDecidesNothing() {
        for (String code : List.of("queued", "complete", "partial")) {
            PasReading reading = read(bundle(claimResponse("\"outcome\":\"" + code + "\"")), sent("95810"));
            assertThat(reading.outcome()).as(code).isEqualTo(PasOutcome.NO_DECISION);
            assertThat(only(reading).outcome().recognised()).isTrue();
        }
        // Queued is not pended: only a review action says pended.
        assertThat(read(bundle(claimResponse("\"outcome\":\"queued\"")), sent("95810")).outcome()).isNotEqualTo(PasOutcome.PENDED);
        PasReading unknown = read(bundle(claimResponse("\"outcome\":\"finished\"")), sent("95810"));
        assertThat(only(unknown).outcome().display()).isEqualTo("Unrecognised code: finished");
        assertThat(only(unknown).outcome().recognised()).isFalse();
    }

    @Test
    void eachReviewActionCodeGivesItsOwnOutcome() {
        Map<String, PasOutcome> table = new LinkedHashMap<>();
        table.put("A1", PasOutcome.APPROVED);
        table.put("A2", PasOutcome.PARTIALLY_APPROVED);
        table.put("A3", PasOutcome.DENIED);
        table.put("A4", PasOutcome.PENDED);
        table.put("A6", PasOutcome.APPROVED_WITH_CHANGES);
        table.put("C", PasOutcome.CANCELLED);
        table.put("CT", PasOutcome.CONTACT_PAYER);
        table.put("NA", PasOutcome.NO_ACTION_REQUIRED);
        table.forEach((code, outcome) -> {
            PasReading reading = read(bundle(claimResponse(items(decided(1, code)))), sent("95810"));
            assertThat(reading.outcome()).as(code).isEqualTo(outcome);
            assertThat(reading.decisions().get(0).reviewActions().get(0).recognised()).as(code).isTrue();
            assertThat(reading.answers().get(0).codes()).containsEntry(PasCodes.REVIEW_ACTION, List.of(code));
        });
        assertThat(table.values()).doesNotHaveDuplicates();
    }

    @Test
    void anUnrecognisedCodeIsShownAsItCameAndIsNeverMappedToAKnownOne() {
        for (String code : List.of("ZZ", "a1", "A11", "Approved")) {
            PasReading reading = read(bundle(claimResponse(items(decided(1, code)))), sent("95810"));
            assertThat(reading.outcome()).as(code).isEqualTo(PasOutcome.UNRECOGNISED_CODE);
            assertThat(reading.decisions().get(0).reviewActions()).singleElement().satisfies(value -> {
                assertThat(value.code()).isEqualTo(code);
                assertThat(value.display()).isEqualTo("Unrecognised code: " + code);
                assertThat(value.recognised()).isFalse();
            });
            // The code is still what an expectation is compared with, and what the ledger keeps.
            assertThat(assess("review-action=" + code, reading)).isEqualTo(TestResult.PASS);
            assertThat(reading.ledgerCodes()).contains("review-action=" + code);
        }
    }

    // ---- where a review action may sit, and how its code part may be named ----

    @Test
    void aReviewActionIsReadFromAnAdjudicationFromTheItemAndFromTheResponseAsAWhole() {
        PasReading onAdjudication = read(bundle(claimResponse(items(decided(1, "A1")))), sent("95810"));
        assertThat(only(onAdjudication).items().get(0).adjudications().get(0).reviewActions().get(0).foundAt())
                .isEqualTo("entry[0].resource.item[0].adjudication[0].extension[0]");
        assertThat(onAdjudication.decisions().get(0).source()).isEqualTo(DecisionSource.ITEM);

        PasReading onItem = read(bundle(claimResponse(items("{\"itemSequence\":1,\"extension\":[" + action("A3") + "]}"))), sent("95810"));
        assertThat(onItem.outcome()).isEqualTo(PasOutcome.DENIED);
        assertThat(only(onItem).items().get(0).reviewActions().get(0).foundAt()).isEqualTo("entry[0].resource.item[0].extension[0]");
        assertThat(onItem.decisions().get(0).source()).isEqualTo(DecisionSource.ITEM);

        PasReading onResponse = read(bundle(claimResponse("\"extension\":[" + action("A4") + "]")), sent("95810"));
        assertThat(onResponse.outcome()).isEqualTo(PasOutcome.PENDED);
        assertThat(only(onResponse).reviewActions().get(0).foundAt()).isEqualTo("entry[0].resource.extension[0]");
        assertThat(onResponse.decisions().get(0).source()).isEqualTo(DecisionSource.RESPONSE);
        for (PasReading reading : List.of(onAdjudication, onItem, onResponse)) {
            assertThat(reading.additionalFields()).isEmpty();
        }
    }

    @Test
    void theCodePartIsReadUnderBothNamesAndFromAConceptACodingOrAPlainCode() {
        assertThat(read(bundle(claimResponse(items(item(1, actionOf("{\"url\":\"code\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"A3\"}]}}"))))),
                sent("95810")).outcome()).isEqualTo(PasOutcome.DENIED);
        assertThat(read(bundle(claimResponse(items(item(1, actionOf("{\"url\":\"code\",\"valueCoding\":{\"code\":\"A4\"}}"))))),
                sent("95810")).outcome()).isEqualTo(PasOutcome.PENDED);
        assertThat(read(bundle(claimResponse(items(item(1, actionOf("{\"url\":\"" + OnyxPasConventions.REVIEW_ACTION_CODE + "\",\"valueCode\":\"A1\"}"))))),
                sent("95810")).outcome()).isEqualTo(PasOutcome.APPROVED);
        // A concept with a text and no coding carries no code. Its text is shown and decides nothing.
        PasReading textOnly = read(bundle(claimResponse(items(item(1, actionOf("{\"url\":\"code\",\"valueCodeableConcept\":{\"text\":\"Certified\"}}"))))),
                sent("95810"));
        assertThat(textOnly.outcome()).isEqualTo(PasOutcome.NO_DECISION);
        assertThat(only(textOnly).items().get(0).adjudications().get(0).reviewActions().get(0).returned().value()).isEqualTo("Certified");
    }

    @Test
    void theCodeElementIsShownAsThePayerWroteItBesideWhatTheToolKnowsOfIt() {
        PasReading reading = read(bundle(claimResponse(items(item(1, actionOf("{\"url\":\"" + OnyxPasConventions.REVIEW_ACTION_CODE
                + "\",\"valueCodeableConcept\":{\"coding\":[{\"system\":\"" + PasJson.X12_306 + "\",\"code\":\"A1\",\"display\":\"Certified in total\"}]}},"
                + "{\"url\":\"number\",\"valueString\":\"PA-PLACEHOLDER-0001\"},"
                + "{\"url\":\"reasonCode\",\"valueCodeableConcept\":{\"coding\":[{\"system\":\"https://example.org/p32h-placeholder/reason\",\"code\":\"R1\"}]}},"
                + "{\"url\":\"secondSurgicalOpinionFlag\",\"valueBoolean\":false},"
                + "{\"url\":\"vendorPart\",\"valueString\":\"extra\"}"))))), sent("95810"));
        ReviewActionView view = only(reading).items().get(0).adjudications().get(0).reviewActions().get(0);
        assertThat(view.code().code()).isEqualTo("A1");
        assertThat(view.code().display()).isEqualTo("Certified in total");
        assertThat(view.returned().value()).isEqualTo("Certified in total (" + PasJson.X12_306 + "|A1)");
        assertThat(view.number().value()).isEqualTo("PA-PLACEHOLDER-0001");
        assertThat(view.reason().value()).isEqualTo("https://example.org/p32h-placeholder/reason|R1");
        assertThat(view.reasonCodes()).containsExactly("R1");
        assertThat(view.secondSurgicalOpinionNeeded().value()).isEqualTo("false");
        // A part this tool has no slot for is kept with the review action it came in.
        assertThat(view.otherParts()).containsExactly(new ExtensionView("vendorPart", Shown.of("extra"), List.of()));
        assertThat(reading.decisions().get(0).reasonCodes()).containsExactly("R1");
        assertThat(reading.answers().get(0).codes()).containsEntry(PasCodes.REVIEW_REASON, List.of("R1"));
        assertThat(reading.additionalFields()).isEmpty();
    }

    @Test
    void aReviewActionWithAValueOfItsOwnAndNoPartsDecidesNothingAndTheValueIsStillShown() {
        PasReading reading = read(bundle(claimResponse(items("{\"itemSequence\":1,\"extension\":[{\"url\":\"" + OnyxPasConventions.REVIEW_ACTION
                + "\",\"valueCode\":\"A1\"}]}"))), sent("95810"));
        assertThat(reading.outcome()).isEqualTo(PasOutcome.NO_DECISION);
        assertThat(only(reading).items().get(0).reviewActions().get(0).returned().value()).isEqualTo("A1");
        assertThat(reading.additionalFields()).isEmpty();
    }

    // ---- which item a decision is for ----

    @Test
    void aDecisionIsTiedToTheRequestedItemWithItsSequenceAndAPartialApprovalNamesEachCode() {
        PasReading reading = read(bundle(claimResponse(items(decided(1, "A1"), decided(2, "A3")))), sent("95810", "95811"));
        assertThat(reading.outcome()).isEqualTo(PasOutcome.PARTIALLY_APPROVED);
        assertThat(reading.several()).isTrue();
        assertThat(reading.decisions()).extracting(PasDecision::subject).containsExactly("95810", "95811");
        assertThat(reading.decisions()).extracting(PasDecision::procedureCode).containsExactly("95810", "95811");
        assertThat(reading.decisions()).extracting(PasDecision::outcome).containsExactly(PasOutcome.APPROVED, PasOutcome.DENIED);
        assertThat(reading.decisions()).extracting(PasDecision::foundAt).containsExactly("entry[0].resource.item[0]", "entry[0].resource.item[1]");
        assertThat(only(reading).items()).extracting(v -> v.procedureCode()).containsExactly("95810", "95811");
        assertThat(only(reading).items()).extracting(v -> v.subject()).containsExactly("95810", "95811");
        assertThat(reading.asked()).containsExactly("95810", "95811");

        // The same expectation passes for one item and fails for the other.
        assertThat(assess("95810: review-action=A1", reading)).isEqualTo(TestResult.PASS);
        assertThat(assess("95811: review-action=A1", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("95811: review-action=A3", reading)).isEqualTo(TestResult.PASS);
        // With no subject the assertion is about every item, and they differ.
        assertThat(assess("review-action=A1", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("PARTIALLY_APPROVED; 95810: review-action=A1; 95811: review-action=A3", reading)).isEqualTo(TestResult.PASS);
        assertThat(assess("APPROVED", reading)).isEqualTo(TestResult.FAIL);
    }

    @Test
    void anItemTheResponseDoesNotListIsNeverGivenTheDecisionOfAnother() {
        PasReading reading = read(bundle(claimResponse(items(decided(1, "A1")))), sent("95810", "95811"));
        assertThat(reading.decisions()).extracting(PasDecision::subject).containsExactly("95810", "95811");
        assertThat(reading.decisions()).extracting(PasDecision::outcome).containsExactly(PasOutcome.APPROVED, PasOutcome.NO_DECISION);
        assertThat(reading.decisions().get(1).foundAt()).isNull();
        assertThat(reading.decisions().get(1).source()).isEqualTo(DecisionSource.NONE);
        // One item approved and one undecided is not an approval, and not a partial approval either.
        assertThat(reading.outcome()).isEqualTo(PasOutcome.MIXED);
        assertThat(assess("APPROVED", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("review-action=A1", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("95811: review-action=A1", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("95810: review-action=A1", reading)).isEqualTo(TestResult.PASS);
        assertThat(reading.ledgerCodes()).containsExactly("95810: review-action=A1", "95811: (no answer)");
    }

    @Test
    void aDecisionForTheWholeResponseIsTheDecisionForEachItemThatStatesNoneOfItsOwn() {
        // Certified in total, stated once for the response, with no items listed.
        PasReading total = read(bundle(claimResponse("\"extension\":[" + action("A1") + "]")), sent("95810", "95811"));
        assertThat(total.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(total.decisions()).extracting(PasDecision::subject).containsExactly("95810", "95811");
        assertThat(total.decisions()).extracting(PasDecision::source).containsOnly(DecisionSource.RESPONSE);
        assertThat(assess("review-action=A1", total)).isEqualTo(TestResult.PASS);

        // An item that states its own decision keeps it. The other takes the response's.
        PasReading split = read(bundle(claimResponse("\"extension\":[" + action("A1") + "]", items(decided(2, "A3")))), sent("95810", "95811"));
        assertThat(split.decisions()).extracting(PasDecision::outcome).containsExactly(PasOutcome.APPROVED, PasOutcome.DENIED);
        assertThat(split.decisions()).extracting(PasDecision::source).containsExactly(DecisionSource.RESPONSE, DecisionSource.ITEM);
        assertThat(split.outcome()).isEqualTo(PasOutcome.PARTIALLY_APPROVED);
    }

    @Test
    void anItemWithNoSequenceIsTiedOnlyWhenThereIsNothingElseItCouldBeAbout() {
        String unnumbered = "{\"adjudication\":[{\"extension\":[" + action("A1") + "]}]}";
        PasReading one = read(bundle(claimResponse(items(unnumbered))), sent("95810"));
        assertThat(one.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(one.decisions()).singleElement().satisfies(decision -> assertThat(decision.procedureCode()).isEqualTo("95810"));

        // With two codes asked about, saying which one it answers would be a guess.
        PasReading two = read(bundle(claimResponse(items(unnumbered))), sent("95810", "95811"));
        assertThat(two.decisions()).extracting(PasDecision::subject).containsExactly("95810", "95811", "entry[0].resource.item[0]");
        assertThat(two.decisions()).extracting(PasDecision::outcome)
                .containsExactly(PasOutcome.NO_DECISION, PasOutcome.NO_DECISION, PasOutcome.APPROVED);
        assertThat(two.decisions().get(2).procedureCode()).isNull();
        assertThat(two.outcome()).isEqualTo(PasOutcome.MIXED);
    }

    @Test
    void anItemThatAnswersNoRequestedItemIsListedUnderItsSequenceAndIsForNoneOfTheCodes() {
        PasReading reading = read(bundle(claimResponse(items(decided(7, "A1")))), sent("95810"));
        assertThat(reading.decisions()).extracting(PasDecision::subject).containsExactly("95810", "item 7");
        assertThat(reading.decisions()).extracting(PasDecision::outcome).containsExactly(PasOutcome.NO_DECISION, PasOutcome.APPROVED);
        assertThat(reading.decisions().get(1).procedureCode()).isNull();
        assertThat(only(reading).items().get(0).subject()).isEqualTo("item 7");
        assertThat(reading.outcome()).isEqualTo(PasOutcome.MIXED);
        assertThat(assess("95810: review-action=A1", reading)).isEqualTo(TestResult.FAIL);
        // The ledger names the untied item by where it sat, never by text the response supplied.
        assertThat(reading.ledgerCodes()).containsExactly("95810: (no answer)", "entry[0].resource.item[0]: review-action=A1");
    }

    @Test
    void whenTheRequestIsNotKnownEachItemIsItsOwnDecisionAndNothingIsSaidToBeUndecided() {
        PasReading items = mapper.read(response(200, bundle(claimResponse(items(decided(1, "A1"), decided(2, "A3"))))));
        assertThat(items.decisions()).extracting(PasDecision::subject).containsExactly("item 1", "item 2");
        assertThat(items.outcome()).isEqualTo(PasOutcome.PARTIALLY_APPROVED);
        assertThat(items.asked()).isEmpty();

        PasReading whole = mapper.read(response(200, bundle(claimResponse("\"extension\":[" + action("A4") + "]"))), null);
        assertThat(whole.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.subject()).isNull();
            assertThat(decision.outcome()).isEqualTo(PasOutcome.PENDED);
        });
        assertThat(whole.several()).isFalse();
        assertThat(whole.ledgerCodes()).containsExactly("review-action=A4");
        assertThat(assess("review-action=A4", whole)).isEqualTo(TestResult.PASS);
    }

    @Test
    void withSeveralClaimResponsesNothingIsTiedAndEachDecisionIsNamedByItsClaimResponse() {
        PasReading differing = read(bundle(claimResponseWithId("cr-1", "\"outcome\":\"complete\"", items(decided(1, "A1"))),
                claimResponseWithId("cr-2", "\"outcome\":\"complete\"", items(decided(1, "A3")))), sent("95810"));
        assertThat(differing.detail().claimResponses()).extracting(ClaimResponseView::decision).containsExactly(PasOutcome.APPROVED, PasOutcome.DENIED);
        assertThat(differing.decisions()).extracting(PasDecision::subject).containsExactly("ClaimResponse/cr-1 item 1", "ClaimResponse/cr-2 item 1");
        assertThat(differing.decisions()).extracting(PasDecision::procedureCode).containsOnlyNulls();
        assertThat(differing.outcome()).isEqualTo(PasOutcome.MIXED);
        assertThat(differing.ledgerCodes()).containsExactly("entry[0].resource: claim-outcome=complete",
                "entry[0].resource.item[0]: review-action=A1", "entry[1].resource: claim-outcome=complete",
                "entry[1].resource.item[0]: review-action=A3");

        // Two that agree give that outcome, and still no code can be asserted for the code that was
        // asked about: which of the two answers it cannot be told.
        PasReading agreeing = read(bundle(claimResponseWithId("cr-1", items(decided(1, "A1"))), claimResponseWithId("cr-2", items(decided(1, "A1")))),
                sent("95810"));
        assertThat(agreeing.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(assess("APPROVED", agreeing)).isEqualTo(TestResult.PASS);
        assertThat(assess("review-action=A1", agreeing)).isEqualTo(TestResult.FAIL);
        // One with no items is named by itself.
        PasReading whole = read(bundle(claimResponseWithId("cr-1", "\"extension\":[" + action("A4") + "]"),
                "{\"resourceType\":\"ClaimResponse\",\"extension\":[" + action("A4") + "]}"), sent("95810"));
        assertThat(whole.decisions()).extracting(PasDecision::subject).containsExactly("ClaimResponse/cr-1", "entry[1].resource");
        assertThat(whole.outcome()).isEqualTo(PasOutcome.PENDED);
    }

    @Test
    void reviewActionsThatDisagreeOnOneItemAreNotSettledByPickingOne() {
        PasReading reading = read(bundle(claimResponse(items(item(1, action("A1"), action("A3"))))), sent("95810"));
        assertThat(reading.decisions()).singleElement().satisfies(decision -> {
            assertThat(decision.codes()).containsExactly("A1", "A3");
            assertThat(decision.outcome()).isEqualTo(PasOutcome.MIXED);
        });
        assertThat(reading.outcome()).isEqualTo(PasOutcome.MIXED);
        assertThat(reading.ledgerCodes()).containsExactly("review-action=A1", "review-action=A3");
        // Two that agree are one decision.
        assertThat(read(bundle(claimResponse(items(item(1, action("A1"), action("A1"))))), sent("95810")).outcome()).isEqualTo(PasOutcome.APPROVED);
        // A second code inside one review action counts too, and is shown where it sat.
        PasReading twoCodes = read(bundle(claimResponse(items(item(1, action("A1", ",{\"url\":\"code\",\"valueCode\":\"A3\"}"))))), sent("95810"));
        assertThat(twoCodes.outcome()).isEqualTo(PasOutcome.MIXED);
        assertThat(only(twoCodes).items().get(0).adjudications().get(0).reviewActions().get(0).otherParts())
                .containsExactly(new ExtensionView("code (repeated)", Shown.of("A3"), List.of()));
    }

    @Test
    void decisionsThatDifferAreCombinedByWhatTheyAreAndNeverByChoosingOne() {
        assertThat(read(bundle(claimResponse(items(decided(1, "A1"), decided(2, "A6")))), sent("95810", "95811")).outcome())
                .isEqualTo(PasOutcome.APPROVED_WITH_CHANGES);
        assertThat(read(bundle(claimResponse(items(decided(1, "A2"), decided(2, "A3")))), sent("95810", "95811")).outcome())
                .isEqualTo(PasOutcome.PARTIALLY_APPROVED);
        assertThat(read(bundle(claimResponse(items(decided(1, "A1"), decided(2, "A4")))), sent("95810", "95811")).outcome())
                .isEqualTo(PasOutcome.MIXED);
        assertThat(read(bundle(claimResponse(items(decided(1, "A3"), decided(2, "ZZ")))), sent("95810", "95811")).outcome())
                .isEqualTo(PasOutcome.MIXED);
        assertThat(read(bundle(claimResponse(items(decided(1, "A3"), decided(2, "A3")))), sent("95810", "95811")).outcome())
                .isEqualTo(PasOutcome.DENIED);
        assertThat(read(bundle(claimResponse(items(decided(1, "A4"), decided(2, "A4")))), sent("95810", "95811")).outcome())
                .isEqualTo(PasOutcome.PENDED);
    }

    // ---- errors and requests for documentation ----

    @Test
    void everyErrorIsRenderedWithItsCodeAndTheElementItPointsAt() {
        String error = "\"error\":[{\"itemSequence\":2,\"code\":{\"coding\":[{\"system\":\"https://codesystem.x12.org/005010/901\",\"code\":\"33\","
                + "\"display\":\"Input Errors\"}]},\"extension\":["
                + "{\"url\":\"" + OnyxPasConventions.ERROR_ELEMENT + "\",\"valueString\":\"Claim.item[1].productOrService\"},"
                + "{\"url\":\"" + OnyxPasConventions.ERROR_PATH + "\",\"valueString\":\"Bundle.entry[0].resource.item[1]\"},"
                + "{\"url\":\"" + OnyxPasConventions.ERROR_FOLLOWUP_ACTION + "\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"C\"}]}},"
                + "{\"url\":\"https://example.org/p32h-placeholder/trace\",\"valueString\":\"t-1\"}]},"
                + "{\"code\":{\"coding\":[{\"code\":\"42\"}]}}]";
        PasReading reading = read(bundle(claimResponse("\"outcome\":\"error\"", error)), sent("95810", "95811"));

        assertThat(only(reading).errors()).hasSize(2);
        PasErrorView first = only(reading).errors().get(0);
        assertThat(first.foundAt()).isEqualTo("entry[0].resource.error[0]");
        assertThat(first.itemSequence().value()).isEqualTo("2");
        assertThat(first.code().value()).isEqualTo("Input Errors (https://codesystem.x12.org/005010/901|33)");
        assertThat(first.codes()).containsExactly("33");
        assertThat(first.element().value()).isEqualTo("Claim.item[1].productOrService");
        assertThat(first.path().value()).isEqualTo("Bundle.entry[0].resource.item[1]");
        assertThat(first.followupAction().value()).isEqualTo("C");
        assertThat(first.extensions()).containsExactly(new ExtensionView("https://example.org/p32h-placeholder/trace", Shown.of("t-1"), List.of()));
        // The second error names no element. That is absent, and shown as absent.
        assertThat(only(reading).errors().get(1).element().presence()).isEqualTo(Presence.ABSENT);
        assertThat(only(reading).errors().get(1).itemSequence().presence()).isEqualTo(Presence.ABSENT);

        // Errors and no review action: the payer could not process the request. That is not "no decision".
        assertThat(reading.outcome()).isEqualTo(PasOutcome.REQUEST_ERRORS);
        // An error against one item bears on that item. One against no item bears on all of them.
        assertThat(reading.decisions().get(0).errorCodes()).containsExactly("42");
        assertThat(reading.decisions().get(1).errorCodes()).containsExactly("33", "42");
        assertThat(assess("95811: error-code=33", reading)).isEqualTo(TestResult.PASS);
        assertThat(assess("95810: error-code=33", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("error-code=42; claim-outcome=error", reading)).isEqualTo(TestResult.PASS);
        assertThat(reading.ledgerCodes()).containsExactly("claim-outcome=error", "95810: error-code=42", "95811: error-code=33", "95811: error-code=42");
        assertThat(reading.additionalFields()).isEmpty();
    }

    @Test
    void theOutcomeCodeErrorAloneIsAReportOfErrorsAndADecisionBesideErrorsStaysTheDecision() {
        assertThat(read(bundle(claimResponse("\"outcome\":\"error\"")), sent("95810")).outcome()).isEqualTo(PasOutcome.REQUEST_ERRORS);
        PasReading decided = read(bundle(claimResponse("\"outcome\":\"error\"", items(decided(1, "A3")),
                "\"error\":[{\"code\":{\"coding\":[{\"code\":\"33\"}]}}]")), sent("95810"));
        assertThat(decided.outcome()).isEqualTo(PasOutcome.DENIED);
        assertThat(decided.ledgerCodes()).containsExactly("claim-outcome=error", "review-action=A3", "error-code=33");
    }

    private static final String ASKS_FOR_DOCUMENT = "\"communicationRequest\":[{\"reference\":\"CommunicationRequest/doc-request-1\"}]";

    private static String documentRequest(String status) {
        return "{\"resourceType\":\"CommunicationRequest\",\"id\":\"doc-request-1\",\"status\":\"" + status + "\",\"category\":[{\"text\":\"Documentation\"}],"
                + "\"payload\":[{\"contentString\":\"Send the report.\"}],\"priority\":\"routine\"}";
    }

    @Test
    void aRequestForDocumentationIsReadWithItsStatusAndMakesAPendedResponseAnAttachmentRequiredOne() {
        PasReading reading = read(bundle(claimResponse(items(decided(1, "A4")), ASKS_FOR_DOCUMENT), documentRequest("active")), sent("95810"));
        assertThat(reading.outcome()).isEqualTo(PasOutcome.ATTACHMENT_REQUIRED);
        AttachmentRefView attachment = only(reading).attachments().get(0);
        assertThat(attachment.foundAt()).isEqualTo("entry[0].resource.communicationRequest[0]");
        assertThat(attachment.reference().value()).isEqualTo("CommunicationRequest/doc-request-1");
        assertThat(attachment.carried()).isTrue();
        assertThat(attachment.status().value()).isEqualTo("active");
        assertThat(attachment.open()).isTrue();
        assertThat(attachment.category().value()).isEqualTo("Documentation");
        assertThat(attachment.wanted()).extracting(Shown::value).containsExactly("Send the report.");
        // Everything else the request holds is listed with it, and with nothing else.
        assertThat(attachment.rest()).extracting(AdditionalField::path)
                .containsExactly("entry[1].resource.resourceType", "entry[1].resource.id", "entry[1].resource.priority");
        assertThat(reading.detail().otherResources()).isEmpty();
        assertThat(reading.additionalFields()).isEmpty();
        // The review action is still pended: the decision code is not rewritten by the request.
        assertThat(reading.decisions().get(0).outcome()).isEqualTo(PasOutcome.PENDED);
        assertThat(assess("ATTACHMENT_REQUIRED; review-action=A4", reading)).isEqualTo(TestResult.PASS);
    }

    @Test
    void aRequestForDocumentationIsClosedOnlyWhenItsStatusIsSeenAndSaysSo() {
        PasReading closed = read(bundle(claimResponse(items(decided(1, "A4")), ASKS_FOR_DOCUMENT), documentRequest("completed")), sent("95810"));
        assertThat(closed.outcome()).isEqualTo(PasOutcome.PENDED);
        assertThat(only(closed).attachments().get(0).open()).isFalse();

        // The request is referred to and not carried. Its status cannot be seen, so it is not taken as closed.
        PasReading unseen = read(bundle(claimResponse(items(decided(1, "A4")), ASKS_FOR_DOCUMENT)), sent("95810"));
        assertThat(unseen.outcome()).isEqualTo(PasOutcome.ATTACHMENT_REQUIRED);
        assertThat(only(unseen).attachments().get(0).carried()).isFalse();
        assertThat(only(unseen).attachments().get(0).status().presence()).isEqualTo(Presence.ABSENT);
        assertThat(only(unseen).attachments().get(0).open()).isTrue();

        // A request contained in the ClaimResponse is found by its hash reference.
        PasReading contained = read(bundle(claimResponse("\"contained\":[{\"resourceType\":\"CommunicationRequest\",\"id\":\"doc-1\",\"status\":\"completed\"}]",
                items(decided(1, "A4")), "\"communicationRequest\":[{\"reference\":\"#doc-1\"}]")), sent("95810"));
        assertThat(only(contained).attachments().get(0).carried()).isTrue();
        assertThat(only(contained).attachments().get(0).open()).isFalse();
        assertThat(contained.outcome()).isEqualTo(PasOutcome.PENDED);
        assertThat(contained.additionalFields()).isEmpty();
    }

    @Test
    void aFinalDecisionThatAlsoRefersToARequestForDocumentationStaysWhatItIs() {
        assertThat(read(bundle(claimResponse(items(decided(1, "A1")), ASKS_FOR_DOCUMENT), documentRequest("active")), sent("95810")).outcome())
                .isEqualTo(PasOutcome.APPROVED);
        // A denial is represented as a denial whatever else the response carries.
        assertThat(read(bundle(claimResponse(items(decided(1, "A3")), ASKS_FOR_DOCUMENT), documentRequest("active")), sent("95810")).outcome())
                .isEqualTo(PasOutcome.DENIED);
        // With nothing decided at all, the request for documentation is the answer.
        assertThat(read(bundle(claimResponse(ASKS_FOR_DOCUMENT), documentRequest("active")), sent("95810")).outcome())
                .isEqualTo(PasOutcome.ATTACHMENT_REQUIRED);
    }

    // ---- what the ledger and a later step are given ----

    @Test
    void theAuthorizationNumberAndTheClaimResponseIdAreReadForCorrelationAndKeptOutOfTheCodes() {
        PasReading reading = read(bundle(claimResponse("\"outcome\":\"complete\"", "\"preAuthRef\":\"PA-PLACEHOLDER-0001\"",
                "\"preAuthPeriod\":{\"start\":\"2026-01-15\",\"end\":\"2026-04-15\"}", items(decided(1, "A1")))), sent("95810"));
        assertThat(reading.authorizationRef()).isEqualTo("PA-PLACEHOLDER-0001");
        assertThat(reading.claimResponseId()).isEqualTo("cr-1");
        assertThat(only(reading).preAuthPeriodStart().value()).isEqualTo("2026-01-15");
        assertThat(only(reading).preAuthPeriodEnd().value()).isEqualTo("2026-04-15");
        assertThat(reading.ledgerCodes()).containsExactly("claim-outcome=complete", "review-action=A1");
        assertThat(reading.ledgerCodes()).noneMatch(code -> code.contains("PA-PLACEHOLDER"));

        // With no pre-authorization reference, the number a review action carries is the authorization number.
        PasReading numbered = read(bundle(claimResponse(items(item(1, action("A1", ",{\"url\":\"number\",\"valueString\":\"PA-PLACEHOLDER-0002\"}"))))),
                sent("95810"));
        assertThat(numbered.authorizationRef()).isEqualTo("PA-PLACEHOLDER-0002");
        assertThat(read(bundle(claimResponse(items(decided(1, "A4")))), sent("95810")).authorizationRef()).isNull();
        assertThat(mapper.read(TestExchanges.timeout(EpaOperation.PAS_SUBMIT), sent("95810")).claimResponseId()).isNull();
    }

    @Test
    void theClaimTheResponseAnswersIsShownByItsReferenceAndByTheIdentifierItEchoes() {
        PasReading both = read(bundle(claimResponse("\"request\":{\"reference\":\"Claim/claim-1\",\"identifier\":{\"system\":\"urn:placeholder\","
                + "\"value\":\"claim-test-1\"}}", "\"identifier\":[{\"value\":\"CR-PLACEHOLDER-1\"}]")), sent("95810"));
        assertThat(only(both).request().value()).isEqualTo("Claim/claim-1");
        assertThat(only(both).requestIdentifier().value()).isEqualTo("urn:placeholder|claim-test-1");
        assertThat(only(both).identifiers()).extracting(Shown::value).containsExactly("CR-PLACEHOLDER-1");
        PasReading identifierOnly = read(bundle(claimResponse("\"request\":{\"identifier\":{\"value\":\"claim-test-1\"}}")), sent("95810"));
        assertThat(only(identifierOnly).request().presence()).isEqualTo(Presence.ABSENT);
        assertThat(only(identifierOnly).requestIdentifier().value()).isEqualTo("claim-test-1");
        assertThat(both.additionalFields()).isEmpty();
        assertThat(identifierOnly.additionalFields()).isEmpty();
    }

    // ---- nothing silently dropped ----

    @Test
    void aFieldTheMapperWasNeverToldAboutReachesTheAdditionalFieldsAtEveryDepth() {
        String body = "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"vendorTraceId\":\"trace-1\",\"entry\":[{\"resource\":{"
                + "\"resourceType\":\"ClaimResponse\",\"id\":\"cr-1\",\"outcome\":\"complete\",\"vendorStatus\":{\"code\":\"x\",\"detail\":{\"depth\":3}},"
                + "\"item\":[{\"itemSequence\":1,\"vendorItemFlag\":true,\"adjudication\":[{\"vendorScore\":0.50,"
                + "\"category\":{\"coding\":[{\"code\":\"submitted\",\"version\":\"1\"}]},"
                + "\"extension\":[{\"url\":\"" + OnyxPasConventions.REVIEW_ACTION + "\",\"extension\":[{\"id\":\"part-1\",\"url\":\"code\",\"valueCode\":\"A1\"}]}]}]}],"
                + "\"error\":[{\"code\":{\"coding\":[{\"code\":\"33\"}]},\"vendorErrorHint\":\"look here\"}]}}]}";
        PasReading reading = read(body, sent("95810"));

        assertThat(reading.additionalFields()).containsExactly(
                new AdditionalField("vendorTraceId", Presence.VALUE, "\"trace-1\""),
                new AdditionalField("entry[0].resource.vendorStatus.code", Presence.VALUE, "\"x\""),
                new AdditionalField("entry[0].resource.vendorStatus.detail.depth", Presence.VALUE, "3"),
                new AdditionalField("entry[0].resource.item[0].vendorItemFlag", Presence.VALUE, "true"),
                new AdditionalField("entry[0].resource.item[0].adjudication[0].vendorScore", Presence.VALUE, "0.50"),
                new AdditionalField("entry[0].resource.item[0].adjudication[0].category.coding[0].version", Presence.VALUE, "\"1\""),
                new AdditionalField("entry[0].resource.item[0].adjudication[0].extension[0].extension[0].id", Presence.VALUE, "\"part-1\""),
                new AdditionalField("entry[0].resource.error[0].vendorErrorHint", Presence.VALUE, "\"look here\""));
        // And what the mapper does understand is still read around them.
        assertThat(reading.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(only(reading).errors().get(0).codes()).containsExactly("33");
    }

    @Test
    void anExtensionTheToolHasNeverSeenIsShownWhereItSatWithEveryPartOfIt() {
        String unknown = "{\"url\":\"https://example.org/p32h-placeholder/flag\",\"valueBoolean\":true},"
                + "{\"url\":\"https://example.org/p32h-placeholder/complex\",\"extension\":[{\"url\":\"part-a\",\"valueString\":\"x\"},"
                + "{\"url\":\"part-b\",\"extension\":[{\"url\":\"deep\",\"valueInteger\":3}]}]},{\"valueString\":\"no url\"}";
        ExtensionView flag = new ExtensionView("https://example.org/p32h-placeholder/flag", Shown.of("true"), List.of());
        ExtensionView complex = new ExtensionView("https://example.org/p32h-placeholder/complex", Shown.absent(), List.of(
                new ExtensionView("part-a", Shown.of("x"), List.of()),
                new ExtensionView("part-b", Shown.absent(), List.of(new ExtensionView("deep", Shown.of("3"), List.of())))));
        ExtensionView unnamed = new ExtensionView("(no url)", Shown.of("no url"), List.of());

        PasReading reading = read(bundle(claimResponse("\"extension\":[" + unknown + "]",
                items("{\"itemSequence\":1,\"extension\":[" + unknown + "],\"adjudication\":[{\"extension\":[" + unknown + "," + action("A1") + "]}]}"),
                "\"error\":[{\"code\":{\"coding\":[{\"code\":\"33\"}]},\"extension\":[" + unknown + "]}]")), sent("95810"));
        assertThat(only(reading).extensions()).containsExactly(flag, complex, unnamed);
        assertThat(only(reading).items().get(0).extensions()).containsExactly(flag, complex, unnamed);
        assertThat(only(reading).items().get(0).adjudications().get(0).extensions()).containsExactly(flag, complex, unnamed);
        assertThat(only(reading).errors().get(0).extensions()).containsExactly(flag, complex, unnamed);
        assertThat(reading.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(reading.additionalFields()).isEmpty();
    }

    @Test
    void everyOtherResourceInTheBundleIsListedWithAllItHoldsAndAnOperationOutcomeInsideItIsReadIssueByIssue() {
        PasReading reading = read(bundle(claimResponse(items(decided(1, "A1"))),
                "{\"resourceType\":\"Patient\",\"id\":\"patient-1\",\"name\":[{\"family\":\"Placeholder-Surname\"}],\"birthDate\":\"1970-01-31\"}",
                "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"warning\",\"code\":\"informational\",\"diagnostics\":\"Processed.\"}]}"),
                sent("95810"));
        assertThat(reading.detail().otherResources()).singleElement().satisfies(resource -> {
            assertThat(resource.foundAt()).isEqualTo("entry[1].resource");
            assertThat(resource.resourceType().value()).isEqualTo("Patient");
            assertThat(resource.id().value()).isEqualTo("patient-1");
            assertThat(resource.fields()).containsExactly(
                    new AdditionalField("entry[1].resource.name[0].family", Presence.VALUE, "\"Placeholder-Surname\""),
                    new AdditionalField("entry[1].resource.birthDate", Presence.VALUE, "\"1970-01-31\""));
        });
        assertThat(reading.detail().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.severity().value()).isEqualTo("warning");
            assertThat(issue.diagnostics().value()).isEqualTo("Processed.");
        });
        assertThat(reading.detail().bundleType().value()).isEqualTo("collection");
        assertThat(reading.additionalFields()).isEmpty();
    }

    @Test
    void absentNullAndEmptyAreThreeDifferentThings() {
        PasReading reading = read(bundle(claimResponse("\"disposition\":null", "\"preAuthRef\":\"\"", "\"item\":[]", "\"error\":null")), sent("95810"));
        ClaimResponseView view = only(reading);
        assertThat(view.created().presence()).isEqualTo(Presence.ABSENT);
        assertThat(view.disposition().presence()).isEqualTo(Presence.NULL);
        assertThat(view.preAuthRef().presence()).isEqualTo(Presence.EMPTY);
        assertThat(view.outcome().presence()).isEqualTo(Presence.ABSENT);
        // An empty item list and a null error list are stated, not passed over: each is listed.
        assertThat(reading.additionalFields()).containsExactly(new AdditionalField("entry[0].resource.item", Presence.EMPTY, "[]"),
                new AdditionalField("entry[0].resource.error", Presence.NULL, null));
        assertThat(reading.outcome()).isEqualTo(PasOutcome.NO_DECISION);
    }

    // ---- every kind of response, a fault included, is rendered in full ----

    @Test
    void aRejectionIsRenderedIssueByIssueAndNotReducedToItsStatus() {
        PasReading reading = mapper.read(response(400, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"invalid\","
                + "\"details\":{\"text\":\"Not a valid request.\"},\"diagnostics\":\"Claim.insurance is required.\","
                + "\"expression\":[\"Bundle.entry[0].resource.insurance\"]}],\"vendorRequestId\":\"req-7\"}"), sent("95810"));
        assertThat(reading.outcome()).isEqualTo(PasOutcome.REJECTED);
        assertThat(reading.detail().responseKind()).isEqualTo(PasResponseKind.OPERATION_OUTCOME);
        assertThat(reading.detail().issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code().value()).isEqualTo("invalid");
            assertThat(issue.diagnostics().value()).isEqualTo("Claim.insurance is required.");
            assertThat(issue.expression()).extracting(Shown::value).containsExactly("Bundle.entry[0].resource.insurance");
        });
        // A fault response is read for fields nobody anticipated exactly as a success is.
        assertThat(reading.additionalFields()).containsExactly(new AdditionalField("vendorRequestId", Presence.VALUE, "\"req-7\""));
        assertThat(reading.answers()).isEmpty();
        assertThat(reading.ledgerCodes()).isEmpty();
        assertThat(assess("review-action=A1", reading)).isEqualTo(TestResult.FAIL);
        assertThat(assess("REJECTED", reading)).isEqualTo(TestResult.PASS);
    }

    @Test
    void aServerErrorATimeoutAnUnreachableServiceAMalformedBodyAndAnEmptyBodyAreEachShownForWhatTheyAre() {
        PasReading serverError = mapper.read(response(500, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"fatal\",\"code\":\"exception\"}]}"));
        assertThat(serverError.outcome()).isEqualTo(PasOutcome.SERVICE_ERROR);
        assertThat(serverError.detail().issues()).hasSize(1);

        PasReading gateway = mapper.read(response(502, "<html>Bad Gateway</html>"));
        assertThat(gateway.outcome()).isEqualTo(PasOutcome.SERVICE_ERROR);
        assertThat(gateway.detail().responseKind()).isEqualTo(PasResponseKind.NOT_JSON);
        assertThat(gateway.additionalFields()).containsExactly(AdditionalField.unparsedBody("<html>Bad Gateway</html>"));

        PasReading timeout = mapper.read(TestExchanges.timeout(EpaOperation.PAS_SUBMIT), sent("95810"));
        assertThat(timeout.outcome()).isEqualTo(PasOutcome.TIMEOUT);
        assertThat(timeout.detail().responseKind()).isEqualTo(PasResponseKind.NO_RESPONSE);
        assertThat(timeout.detail().responseNote()).isEqualTo("No response arrived.");
        assertThat(timeout.additionalFields()).isEmpty();
        assertThat(timeout.decisions()).isEmpty();

        PasReading unreachable = mapper.read(TestExchanges.unavailable(EpaOperation.PAS_SUBMIT));
        assertThat(unreachable.outcome()).isEqualTo(PasOutcome.UNREACHABLE);
        assertThat(unreachable.detail().responseKind()).isEqualTo(PasResponseKind.NO_RESPONSE);

        PasReading malformed = mapper.read(response(200, "{\"resourceType\": \"Bundle\", \"entry\": ["));
        assertThat(malformed.outcome()).isEqualTo(PasOutcome.MALFORMED_RESPONSE);
        assertThat(malformed.detail().responseKind()).isEqualTo(PasResponseKind.NOT_JSON);
        assertThat(malformed.detail().responseNote()).contains("could not be read as JSON").contains("exactly as received");
        // The raw bytes are on screen outside the wire view, first in the list and marked for what they are.
        assertThat(malformed.additionalFields()).containsExactly(AdditionalField.unparsedBody("{\"resourceType\": \"Bundle\", \"entry\": ["));
        assertThat(malformed.additionalFields().get(0).kind()).isEqualTo(AdditionalField.Kind.RAW_BODY);

        PasReading empty = mapper.read(response(200, null));
        assertThat(empty.outcome()).isEqualTo(PasOutcome.MALFORMED_RESPONSE);
        assertThat(empty.detail().responseKind()).isEqualTo(PasResponseKind.NO_BODY);
        assertThat(empty.detail().responseNote()).isEqualTo("The response has no body.");
    }

    @Test
    void aSuccessThatCarriesNoClaimResponseIsNoDecisionWhateverElseItCarries() {
        PasReading emptyBundle = read("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[]}", sent("95810"));
        assertThat(emptyBundle.outcome()).isEqualTo(PasOutcome.NO_DECISION);
        assertThat(emptyBundle.detail().responseKind()).isEqualTo(PasResponseKind.BUNDLE);
        assertThat(emptyBundle.detail().responseNote()).isEqualTo("The bundle carries no ClaimResponse, so it holds no decision.");
        assertThat(emptyBundle.additionalFields()).containsExactly(new AdditionalField("entry", Presence.EMPTY, "[]"));
        assertThat(emptyBundle.answers()).isEmpty();
        assertThat(assess("review-action=A1", emptyBundle)).isEqualTo(TestResult.FAIL);

        PasReading outcomeOnly = read("{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"information\",\"code\":\"informational\"}]}",
                sent("95810"));
        assertThat(outcomeOnly.outcome()).isEqualTo(PasOutcome.NO_DECISION);
        assertThat(outcomeOnly.detail().responseKind()).isEqualTo(PasResponseKind.OPERATION_OUTCOME);

        PasReading array = read("[1,{\"a\":2}]", sent("95810"));
        assertThat(array.outcome()).isEqualTo(PasOutcome.NO_DECISION);
        assertThat(array.detail().responseKind()).isEqualTo(PasResponseKind.OTHER_JSON);
        assertThat(paths(array)).containsExactly("[0]", "[1].a");

        PasReading other = read("{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"return\"}]}", sent("95810"));
        assertThat(other.detail().responseKind()).isEqualTo(PasResponseKind.OTHER_JSON);
        assertThat(other.detail().responseNote()).contains("not a bundle, a ClaimResponse or an OperationOutcome");
        assertThat(paths(other)).containsExactly("resourceType", "parameter[0].name");
    }

    @Test
    void aClaimResponseThatIsNotWrappedInABundleIsReadAllTheSame() {
        PasReading reading = read(claimResponse("\"outcome\":\"complete\"", items(decided(1, "A1"))), sent("95810"));
        assertThat(reading.detail().responseKind()).isEqualTo(PasResponseKind.CLAIM_RESPONSE);
        assertThat(reading.outcome()).isEqualTo(PasOutcome.APPROVED);
        assertThat(only(reading).foundAt()).isEqualTo("(root)");
        assertThat(only(reading).items().get(0).foundAt()).isEqualTo("item[0]");
        assertThat(reading.decisions().get(0).procedureCode()).isEqualTo("95810");
        assertThat(reading.additionalFields()).isEmpty();
    }

    @Test
    void somethingWorthKnowingAboutTheBodyAsAWholeComesFirst() {
        PasReading reading = read("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"type\":\"batch\",\"vendorField\":1,\"entry\":[{\"resource\":"
                + claimResponse(items(decided(1, "A1"))) + "}]}", sent("95810"));
        assertThat(reading.additionalFields()).hasSize(2);
        assertThat(reading.additionalFields().get(0).kind()).isEqualTo(AdditionalField.Kind.NOTE);
        assertThat(reading.additionalFields().get(0).value()).contains("repeats a key");
        assertThat(reading.additionalFields().get(1).path()).isEqualTo("vendorField");
    }

    // ---- not found is not a fault ----

    @Test
    void anUnknownMemberIsAFindingAboutTestDataHoweverItArrives() {
        PasReading byErrorCode = read(bundle(claimResponse("\"outcome\":\"error\"", "\"error\":[{\"code\":{\"coding\":[{\"system\":"
                + "\"https://codesystem.x12.org/005010/901\",\"code\":\"75\"}]}}]")), sent("95810"));
        assertThat(byErrorCode.outcome()).isEqualTo(PasOutcome.MEMBER_NOT_FOUND);
        assertThat(byErrorCode.outcome().fault()).isFalse();
        // The ClaimResponse itself reports errors. The outcome of the call says what they mean.
        assertThat(only(byErrorCode).decision()).isEqualTo(PasOutcome.REQUEST_ERRORS);
        assertThat(only(byErrorCode).errors()).hasSize(1);

        PasReading byOperationOutcome = mapper.read(response(404, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                + "\"code\":\"not-found\",\"expression\":[\"Claim.patient\"]}]}"), sent("95810"));
        assertThat(byOperationOutcome.outcome()).isEqualTo(PasOutcome.MEMBER_NOT_FOUND);
        assertThat(byOperationOutcome.detail().issues()).hasSize(1);

        PasReading provider = mapper.read(response(404, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                + "\"code\":\"not-found\",\"expression\":[\"Practitioner.identifier\"]}]}"), sent("95810"));
        assertThat(provider.outcome()).isEqualTo(PasOutcome.PROVIDER_NOT_FOUND);

        // The same status with nothing that says what was not found is a rejection, which is a fault.
        PasReading bare = mapper.read(response(404, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\"}]}"),
                sent("95810"));
        assertThat(bare.outcome()).isEqualTo(PasOutcome.REJECTED);
        assertThat(bare.outcome().fault()).isTrue();
    }

    @Test
    void anInquiryAboutARequestThePayerDoesNotKnowIsAFindingAboutTheClaimId() {
        PasReading reading = mapper.read(response(404, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\","
                + "\"code\":\"not-found\",\"expression\":[\"Claim.identifier\"]}]}"), sent("95810"));
        assertThat(reading.outcome()).isEqualTo(PasOutcome.AUTHORIZATION_NOT_FOUND);
        assertThat(reading.outcome().fault()).isFalse();
    }
}
