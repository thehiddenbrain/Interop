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
    void anErrorResponseInTheGuidesOwnShapeRaisesNothingAboutMissingDecisions() {
        // The shape of ErrorResponseExample: outcome error, errors coded in X12 901, no item.
        String error = bundle(claimResponse("\"status\":\"active\"", "\"use\":\"preauthorization\"", "\"outcome\":\"error\"",
                "\"error\":[{\"code\":{\"coding\":[{\"system\":\"https://codesystem.x12.org/005010/901\",\"code\":\"04\"}]}}]"));

        assertThat(findings(error, "E0601")).isEmpty();
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
}
