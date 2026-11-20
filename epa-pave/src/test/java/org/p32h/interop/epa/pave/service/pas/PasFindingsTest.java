package org.p32h.interop.epa.pave.service.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.p32h.interop.epa.pave.service.pas.PasJson.bundle;
import static org.p32h.interop.epa.pave.service.pas.PasJson.claimResponse;
import static org.p32h.interop.epa.pave.service.pas.PasJson.decided;
import static org.p32h.interop.epa.pave.service.pas.PasJson.item;
import static org.p32h.interop.epa.pave.service.pas.PasJson.items;
import static org.p32h.interop.epa.pave.service.pas.PasJson.response;
import static org.p32h.interop.epa.pave.service.pas.PasJson.sent;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;

/**
 * PAS 2.0.1 makes the review action optional (ClaimResponse.item.adjudication.extension[reviewAction]
 * 0..1), its error example (ErrorResponseExample) carries no item at all, and a response to a revised
 * request that answers only some items is tagged SUBSETTED. None of these breaks the guide.
 */
class PasFindingsTest {

    private static List<Finding> findings(String body, String... codes) {
        return PasFindings.check(new PasResponseMapper().read(response(200, body), sent(codes)));
    }

    @Test
    void anItemWithNoDecisionIsInformationAndNeverAClaimThatTheGuideWasBroken() {
        List<Finding> found = findings(bundle(claimResponse("\"outcome\":\"complete\"", items(decided(1, "A1"), item(2)))), "E0601", "E0562");

        assertThat(found).extracting(Finding::kind).containsExactly(FindingKind.NOT_STATED);
        assertThat(found.get(0).title()).isEqualTo("No decision is stated for E0562");
        assertThat(found.get(0).detail()).contains("PAS 2.0.1 does not require a review action on every item");
        assertThat(found).extracting(Finding::kind).doesNotContain(FindingKind.REQUIRED_ELEMENT_MISSING);
    }

    @Test
    void aReviewActionOnTheResponseOrOnTheItemItselfBreaksTheContextPas201GivesItAndIsAFindingOfThatKind() {
        // PAS 2.0.1 ClaimResponse.fsh, Extension ReviewAction: context Claim.item, Examples.item, ClaimResponse.item.adjudication.
        List<Finding> onItem = findings(bundle(claimResponse("\"outcome\":\"complete\"",
                items("{\"itemSequence\":1,\"extension\":[" + PasJson.action("A1") + "]}"))), "95810");
        assertThat(onItem).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.GUIDE_RULE_BROKEN);
            assertThat(f.title()).isEqualTo("A review action sits where PAS 2.0.1 does not place one");
            assertThat(f.detail()).startsWith(PasResponseMapper.ON_THE_ITEM).contains("for Claim.item and ClaimResponse.item.adjudication");
            assertThat(f.subject()).isEqualTo("entry[0].resource.item[0].extension[0]");
        });
        List<Finding> onResponse = findings(bundle(claimResponse("\"outcome\":\"complete\"", "\"extension\":[" + PasJson.action("A1") + "]")),
                "95810");
        assertThat(onResponse).extracting(Finding::kind).contains(FindingKind.GUIDE_RULE_BROKEN);
        assertThat(onResponse).filteredOn(f -> f.kind() == FindingKind.GUIDE_RULE_BROKEN).singleElement()
                .satisfies(f -> assertThat(f.detail()).startsWith(PasResponseMapper.ON_THE_RESPONSE));
        // In the place the guide gives it, nothing is said.
        assertThat(findings(bundle(claimResponse("\"outcome\":\"complete\"", items(decided(1, "A1")))), "95810")).isEmpty();
    }

    @Test
    void anErrorResponseInTheGuidesOwnShapeRaisesNothingAboutMissingDecisions() {
        // The shape of ErrorResponseExample: outcome error, errors coded in X12 901, no item.
        String error = bundle(claimResponse("\"status\":\"active\"", "\"use\":\"preauthorization\"", "\"outcome\":\"error\"",
                "\"error\":[{\"code\":{\"coding\":[{\"system\":\"https://codesystem.x12.org/005010/901\",\"code\":\"04\"}]}}]"));

        assertThat(findings(error, "E0601")).isEmpty();
    }

    @Test
    void aReviewActionOnAnAddedItemIsNamedAsBallotContentAndDecidesNothing() {
        // PAS 2.2.0 ballot: addItem.adjudication.extension[reviewAction] 0..1 MS. PAS 2.0.1 ClaimResponse.fsh has no such slice.
        String body = bundle(claimResponse("\"outcome\":\"complete\"", items(decided(1, "A1")),
                "\"addItem\":[{\"itemSequence\":[1],\"adjudication\":[{\"category\":{\"coding\":[{\"code\":\"submitted\"}]},"
                        + "\"extension\":[" + PasJson.action("A3") + "]}]}]"));
        PasReading reading = new PasResponseMapper().read(response(200, body), sent("E0601"));
        assertThat(reading.outcome()).isEqualTo(org.p32h.interop.epa.pave.model.pas.PasOutcome.APPROVED);
        List<Finding> found = PasFindings.check(reading);
        assertThat(found).extracting(Finding::kind).containsExactly(FindingKind.OTHER_VERSION_CONTENT);
        assertThat(found.get(0).detail()).contains("The PAS 2.2.0 ballot defines a review action there; PAS 2.0.1, the version this tool "
                + "is held to, does not.");
        assertThat(found.get(0).subject()).isEqualTo("entry[0].resource.addItem[0].adjudication[0].extension[0]");
        // Nothing of it is dropped: the added item is listed among the fields returned.
        assertThat(reading.additionalFields()).anySatisfy(field -> assertThat(field.path()).startsWith("entry[0].resource.addItem"));
    }

    @Test
    void aResponseTaggedSubsettedRaisesNothingAboutTheItemsItLeavesOut() {
        String subset = bundle(claimResponse("\"meta\":{\"security\":[{\"system\":\"http://terminology.hl7.org/CodeSystem/v3-ObservationValue\","
                + "\"code\":\"SUBSETTED\"}]}", "\"outcome\":\"complete\"", items(decided(1, "A1"))));

        assertThat(findings(subset, "E0601", "E0562")).isEmpty();
        // Without the tag, the item left out is said to have no decision.
        assertThat(findings(bundle(claimResponse("\"outcome\":\"complete\"", items(decided(1, "A1")))), "E0601", "E0562"))
                .extracting(Finding::kind).containsExactly(FindingKind.NOT_STATED);
    }

    @Test
    void anUnknownReviewActionCodeIsJudgedAgainstTheListPas201BindsAndSaysSo() {
        List<Finding> found = findings(bundle(claimResponse("\"outcome\":\"complete\"", items(decided(1, "D")))), "E0601");

        assertThat(found).filteredOn(finding -> finding.kind() == FindingKind.CODE_OUTSIDE_VALUE_SET).singleElement().satisfies(finding ->
                assertThat(finding.detail()).startsWith("PAS 2.0.1 binds the review action code, required, to the X12 306 (HCR01) codes; "
                        + "this tool knows A1, A2, A3, A4, A6, C, CT and NA."));
    }
}
