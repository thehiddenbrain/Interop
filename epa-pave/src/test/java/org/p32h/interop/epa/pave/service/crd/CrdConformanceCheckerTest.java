package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CrdConformanceCheckerTest {

    private static final String EXT = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
    private final CrdResponseMapper mapper = new CrdResponseMapper();
    private final FakeCodeList codeList = new FakeCodeList();

    private static String action(int serviceRequest, String subExtensions) {
        return "{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-" + serviceRequest
                + "\",\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[" + subExtensions + "]}]}}";
    }

    private static String response(String... actions) {
        return "{\"cards\":[],\"systemActions\":[" + String.join(",", actions) + "]}";
    }

    private static String code(String url, String value) {
        return "{\"url\":\"" + url + "\",\"valueCode\":\"" + value + "\"}";
    }

    private static String vendor(String name) {
        return "{\"url\":\"detail\",\"extension\":[{\"url\":\"code\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"delegated-vendor\"}]}},"
                + "{\"url\":\"value\",\"valueString\":\"" + name + "\"}]}";
    }

    /** The orders a request for these codes carries, as the request builder writes them. */
    private static List<SentOrder> orders(String... codes) {
        List<SentOrder> orders = new ArrayList<>();
        for (int i = 0; i < codes.length; i++) {
            orders.add(new SentOrder("ServiceRequest/service-request-" + (i + 1), codes[i]));
        }
        return orders;
    }

    /** Checks a response against the code list. */
    private List<Finding> check(int status, String body, String lineOfBusiness, String... codes) {
        VendorExchange exchange = TestExchanges.crd(status, body);
        return new CrdConformanceChecker(codeList).check(mapper.read(exchange, orders(codes)), exchange, lineOfBusiness, null);
    }

    /** Checks the response on its own, with no code list, for the tests that are not about the list. */
    private List<Finding> checkResponse(int status, String body, String... codes) {
        VendorExchange exchange = TestExchanges.crd(status, body);
        return new CrdConformanceChecker(null).check(mapper.read(exchange, orders(codes)), exchange, null, null);
    }

    private static List<FindingKind> kinds(List<Finding> findings) {
        return findings.stream().map(Finding::kind).toList();
    }

    private static Finding notCompared(List<Finding> findings) {
        assertThat(kinds(findings)).containsExactly(FindingKind.CODE_LIST_NOT_COMPARED);
        return findings.get(0);
    }

    // ---- the response on its own ----

    @Test
    void aCodeOutsideItsValueSetIsReportedAsNonConformantWithTheValueSet() {
        List<Finding> findings = checkResponse(200, response(action(1, code("covered", "maybe") + "," + code("pa-needed", "auth-needed") + ","
                + code("doc-purpose", "withfax") + "," + code("info-needed", "mood"))), "95810");
        assertThat(kinds(findings)).containsExactly(FindingKind.CODE_OUTSIDE_VALUE_SET, FindingKind.CODE_OUTSIDE_VALUE_SET,
                FindingKind.CODE_OUTSIDE_VALUE_SET);
        assertThat(findings.get(0).title()).isEqualTo("Non-conformant code in covered");
        assertThat(findings.get(0).detail()).contains("\"maybe\"").contains("covered, not-covered, conditional");
        assertThat(findings.get(0).serviceSays()).isEqualTo("The service returned covered=maybe.");
        // The finding is about the answer for the code that was asked, and is named by that code.
        assertThat(findings.get(0).subject()).isEqualTo("95810");
    }

    @Test
    void coverageInformationWithNoAnswerSaysSo() {
        List<Finding> findings = checkResponse(200, response(action(1, "{\"url\":\"date\",\"valueDate\":\"2026-01-15\"}")), "95810");
        assertThat(kinds(findings)).containsExactly(FindingKind.REQUIRED_ELEMENT_MISSING);
        assertThat(findings.get(0).detail()).contains("carries neither covered nor pa-needed");
    }

    @Test
    void anElementSentWithNoValueIsAFindingAndNotAnAnswer() {
        List<Finding> findings = checkResponse(200, response(action(1, code("covered", "covered") + ",{\"url\":\"pa-needed\",\"valueCode\":null}")),
                "95810");
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.REQUIRED_ELEMENT_MISSING);
            assertThat(f.title()).isEqualTo("pa-needed was sent with no value");
            assertThat(f.detail()).contains("its value is null");
        });
    }

    @Test
    void aSuccessfulResponseWithNoCoverageInformationIsAFindingAndAFaultIsNot() {
        codeList.row("95810", AuthRequirement.REQUIRED, null, null);
        assertThat(check(200, "{\"cards\":[]}", null, "95810")).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.REQUIRED_ELEMENT_MISSING);
            assertThat(f.title()).isEqualTo("No coverage information in the response");
        });
        // A fault is already the headline. It is not also reported as a missing element, and the
        // code list is not said to be uncompared when there was never an answer to compare.
        assertThat(check(500, "{\"resourceType\":\"OperationOutcome\",\"issue\":[]}", null, "95810")).isEmpty();
        VendorExchange timeout = TestExchanges.timeout(EpaOperation.CRD_ORDER_SIGN);
        assertThat(new CrdConformanceChecker(codeList).check(mapper.read(timeout, orders("95810")), timeout, null, null)).isEmpty();
    }

    @Test
    void withoutACodeListOnlyTheResponseItselfIsChecked() {
        assertThat(checkResponse(200, response(action(1, code("pa-needed", "no-auth"))), "95810")).isEmpty();
    }

    // ---- agreement and disagreement ----

    @Test
    void noFindingMeansTheTwoWereComparedAndAgree() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", "eviCore");
        assertThat(check(200, response(action(1, code("covered", "covered") + "," + code("pa-needed", "auth-needed") + "," + vendor("eviCore"))),
                "Medicare", "95810")).isEmpty();
    }

    @Test
    void theCodeListAndTheServiceDisagreeingOnAuthorizationIsReportedInBothDirections() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", null).row("70553", AuthRequirement.NOT_REQUIRED, "Medicare", null);
        List<Finding> listSaysRequired = check(200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "95810");
        assertThat(listSaysRequired).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
            // The line the row is for is always named, so nobody has to wonder which row was used.
            assertThat(f.codeListSays()).isEqualTo("The code list says prior authorization is required (rows for Medicare).");
            assertThat(f.serviceSays()).isEqualTo("The service answered pa-needed=no-auth.");
            assertThat(f.detail()).isEqualTo("One of them is wrong, and this tool cannot tell which.");
        });
        List<Finding> listSaysNotRequired = check(200, response(action(1, code("pa-needed", "auth-needed"))), "Medicare", "70553");
        assertThat(listSaysNotRequired).singleElement().satisfies(f -> {
            assertThat(f.codeListSays()).isEqualTo("The code list says prior authorization is not required (rows for Medicare).");
            assertThat(f.serviceSays()).isEqualTo("The service answered pa-needed=auth-needed.");
        });
        // "Satisfied" means one was needed and exists, which agrees with "required".
        assertThat(check(200, response(action(1, code("pa-needed", "satisfied"))), "Medicare", "95810")).isEmpty();
        assertThat(kinds(check(200, response(action(1, code("pa-needed", "satisfied"))), "Medicare", "70553")))
                .containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
    }

    @Test
    void theVendorDisagreeingIsReportedWhenTheServiceNamesAnotherOrNamesNone() {
        codeList.row("70553", AuthRequirement.REQUIRED, "Medicare", "Evolent");
        List<Finding> another = check(200, response(action(1, code("pa-needed", "auth-needed") + "," + vendor("Carelon"))), "Medicare", "70553");
        assertThat(another).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.CODE_LIST_DISAGREES_ON_VENDOR);
            assertThat(f.codeListSays()).isEqualTo("The code list routes this code to Evolent (rows for Medicare).");
            assertThat(f.serviceSays()).isEqualTo("The service routes it to Carelon.");
        });
        List<Finding> none = check(200, response(action(1, code("pa-needed", "auth-needed"))), "Medicare", "70553");
        assertThat(none).singleElement().satisfies(f -> assertThat(f.serviceSays()).isEqualTo("The service named no delegated vendor."));
        // The same vendor spelled differently is the same vendor.
        assertThat(check(200, response(action(1, code("pa-needed", "auth-needed") + "," + vendor("EVOLENT"))), "Medicare", "70553")).isEmpty();
    }

    @Test
    void eachCodeIsComparedWithTheAnswerForItsOwnOrder() {
        codeList.row("95810", AuthRequirement.REQUIRED, null, null).row("70553", AuthRequirement.REQUIRED, null, null);
        // The first code is answered auth-needed (agrees), the second no-auth (disagrees).
        List<Finding> findings = check(200, response(action(1, code("pa-needed", "auth-needed")), action(2, code("pa-needed", "no-auth"))),
                null, "95810", "70553");
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
            assertThat(f.subject()).isEqualTo("70553");
            assertThat(f.codeListSays()).isEqualTo("The code list says prior authorization is required (rows that state no line of business).");
        });
    }

    // ---- when the two could not be compared, that is said, with the reason ----

    @Test
    void aCodeTheListDoesNotHaveIsReportedAsNotCompared() {
        Finding finding = notCompared(check(200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "99999"));
        assertThat(finding.title()).isEqualTo("The code list was not compared with the service for 99999");
        assertThat(finding.detail()).isEqualTo("The code list has no row for 99999.");
        assertThat(finding.subject()).isEqualTo("99999");
    }

    @Test
    void linesOfBusinessAreNeverConflated() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Commercial", null);
        // The list has this code for Commercial only. A Medicare member's answer is not compared with
        // a Commercial row, and the finding says which lines the list does have.
        Finding medicare = notCompared(check(200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "95810"));
        assertThat(medicare.detail()).contains("no row for 95810 under Medicare").contains("under: Commercial")
                .contains("A row for another line of business is not this member's answer.");
        // The same answer for a Commercial member is a disagreement.
        assertThat(kinds(check(200, response(action(1, code("pa-needed", "no-auth"))), "Commercial", "95810")))
                .containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
        // A row that states no line applies to every line.
        codeList.row("70553", AuthRequirement.REQUIRED, null, null);
        assertThat(kinds(check(200, response(action(1, code("pa-needed", "no-auth"))), "Commercial", "70553")))
                .containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
    }

    @Test
    void whenTheMembersLineIsNotKnownARowForANamedLineIsNeverChosenForThem() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", null).row("95810", AuthRequirement.NOT_REQUIRED, "Commercial", null);
        // Typed-by-hand member details carry no line of business. Picking the Medicare row would
        // report a disagreement for a member who may be Commercial.
        Finding finding = notCompared(check(200, response(action(1, code("pa-needed", "no-auth"))), null, "95810"));
        assertThat(finding.detail()).contains("line of business is not known").contains("Medicare, Commercial")
                .contains("would be a guess about the member");
        assertThat(notCompared(check(200, response(action(1, code("pa-needed", "no-auth"))), "  ", "95810")).detail())
                .contains("line of business is not known");

        // A row that states no line can be used, and only that row is.
        codeList.row("95810", AuthRequirement.REQUIRED, null, null);
        assertThat(check(200, response(action(1, code("pa-needed", "no-auth"))), null, "95810")).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
            assertThat(f.codeListSays()).contains("rows that state no line of business");
        });
    }

    @Test
    void rowsThatContradictEachOtherGiveTheListNoSinglePosition() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", null).row("95810", AuthRequirement.NOT_REQUIRED, "Medicare", null);
        assertThat(notCompared(check(200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "95810")).detail())
                .contains("disagree with each other on prior authorization").contains("no single position");
    }

    @Test
    void aSideThatTakesNoPositionIsNotADisagreementAndIsNotSilentEither() {
        codeList.row("95810", AuthRequirement.UNKNOWN, "Medicare", null);
        // The list does not say. That is never read as "no".
        assertThat(notCompared(check(200, response(action(1, code("pa-needed", "auth-needed"))), "Medicare", "95810")).detail())
                .contains("The code list does not say whether 95810 needs prior authorization");
        // The service says "conditional", which is not a position either.
        codeList.row("70553", AuthRequirement.REQUIRED, "Medicare", null);
        assertThat(notCompared(check(200, response(action(1, code("pa-needed", "conditional"))), "Medicare", "70553")).detail())
                .contains("does not say whether prior authorization is needed (pa-needed=conditional)");
        assertThat(notCompared(check(200, response(action(1, code("covered", "covered"))), "Medicare", "70553")).detail())
                .contains("pa-needed was not returned");
    }

    @Test
    void aCodeNoAnswerCouldBeTiedToIsNotComparedWithSomeOtherAnswer() {
        codeList.row("95810", AuthRequirement.REQUIRED, null, null);
        // The only answer is for an order that was not sent, so it is not the answer for 95810.
        assertThat(notCompared(check(200, response(action(7, code("pa-needed", "no-auth"))), null, "95810")).detail())
                .isEqualTo("The response carries no answer this tool could tie to 95810.");
        // The second code got no answer at all. The first is compared and agrees.
        codeList.row("70553", AuthRequirement.REQUIRED, null, null);
        List<Finding> findings = check(200, response(action(1, code("pa-needed", "auth-needed"))), null, "95810", "70553");
        assertThat(notCompared(findings).subject()).isEqualTo("70553");
    }

    @Test
    void routingThatCouldNotBeComparedIsSaid() {
        // The service names a vendor and the list names none: the list has taken no position on routing.
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", null);
        Finding noVendorInList = notCompared(check(200, response(action(1, code("pa-needed", "auth-needed") + "," + vendor("eviCore"))),
                "Medicare", "95810"));
        assertThat(noVendorInList.detail()).contains("The service routes 95810 to eviCore").contains("names no vendor");
        // The list's own rows name two vendors.
        codeList.row("70553", AuthRequirement.REQUIRED, "Medicare", "Evolent").row("70553", AuthRequirement.REQUIRED, "Medicare", "Carelon");
        assertThat(notCompared(check(200, response(action(1, code("pa-needed", "auth-needed") + "," + vendor("Evolent"))), "Medicare", "70553"))
                .detail()).contains("name different vendors").contains("Evolent, Carelon");
    }
}
