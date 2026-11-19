package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrdConformanceCheckerTest {

    private static final String EXT = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
    private static final EpaProperties MOCK = EpaProperties.of(Map.of("mode", "mock"));
    private static final EpaProperties LIVE = EpaProperties.of(Map.of("mode", "live"));
    private static final String PLACEHOLDER_NOTE = " The code list here is the synthetic placeholder that ships with the tool and not a P32H list.";
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

    /** Checks a response against the code list on one profile. */
    private List<Finding> checkOn(EpaProperties profile, int status, String body, String lineOfBusiness, String... codes) {
        VendorExchange exchange = TestExchanges.crd(status, body);
        return new CrdConformanceChecker(codeList, profile).check(mapper.read(exchange, orders(codes)), exchange, lineOfBusiness, null);
    }

    /**
     * Checks a response against the code list. The list in these tests stands for a real export
     * unless a test marks it as the placeholder, and a real export is compared the same way
     * wherever the requests go. So every check is made on both profiles and must come out the same.
     */
    private List<Finding> check(int status, String body, String lineOfBusiness, String... codes) {
        List<Finding> onMock = checkOn(MOCK, status, body, lineOfBusiness, codes);
        assertThat(checkOn(LIVE, status, body, lineOfBusiness, codes)).as("the same check on the live profile").isEqualTo(onMock);
        return onMock;
    }

    /** Checks the response on its own, with no code list, for the tests that are not about the list. */
    private List<Finding> checkResponse(int status, String body, String... codes) {
        VendorExchange exchange = TestExchanges.crd(status, body);
        return new CrdConformanceChecker(null, LIVE).check(mapper.read(exchange, orders(codes)), exchange, null, null);
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
        assertThat(new CrdConformanceChecker(codeList, MOCK).check(mapper.read(timeout, orders("95810")), timeout, null, null)).isEmpty();
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

    @Test
    void aVendorCellWithNoLetterOrDigitInItIsNoRoutingAndIsNeverPrintedAsNull() {
        // A dash for "none", as a list may write it. The real code list service serves such a cell
        // as no vendor at all. This directory hands it over as written, as another one might.
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", "-");
        // The service names no vendor either. The two agree on routing, so nothing is said.
        assertThat(check(200, response(action(1, code("pa-needed", "auth-needed"))), "Medicare", "95810")).isEmpty();
        // The service names one. The list has taken no position on routing, and the finding says that.
        assertThat(notCompared(check(200, response(action(1, code("pa-needed", "auth-needed") + "," + vendor("eviCore"))), "Medicare", "95810"))
                .detail()).isEqualTo("The service routes 95810 to eviCore. The code list names no vendor for the code (rows for Medicare), "
                        + "so the routing could not be compared.");

        // Beside a row that does name a vendor, the dash is not a second vendor for the list to disagree with itself about.
        codeList.row("70553", AuthRequirement.REQUIRED, "Medicare", "Evolent").row("70553", AuthRequirement.REQUIRED, "Medicare", " -- ");
        assertThat(check(200, response(action(1, code("pa-needed", "auth-needed") + "," + vendor("Evolent"))), "Medicare", "70553")).isEmpty();
        assertThat(check(200, response(action(1, code("pa-needed", "auth-needed") + "," + vendor("Carelon"))), "Medicare", "70553")).singleElement()
                .satisfies(f -> {
                    assertThat(f.kind()).isEqualTo(FindingKind.CODE_LIST_DISAGREES_ON_VENDOR);
                    assertThat(f.codeListSays()).isEqualTo("The code list routes this code to Evolent (rows for Medicare).");
                });
    }

    // ---- the placeholder list, which must never read as the P32H list ----

    @Test
    void onLiveThePlaceholderIsNotComparedAndThatIsSaidOnceForTheRun() {
        codeList.asPlaceholder().row("95810", AuthRequirement.REQUIRED, "Medicare", "eviCore").row("70553", AuthRequirement.REQUIRED, "Medicare", "Evolent");
        // Compared, the first code would disagree on authorization and the second on the vendor.
        String body = response(action(1, code("pa-needed", "no-auth")), action(2, code("pa-needed", "auth-needed") + "," + vendor("Carelon")));

        Finding finding = notCompared(checkOn(LIVE, 200, body, "Medicare", "95810", "70553"));
        assertThat(finding.title()).isEqualTo("The code list was not compared with the service: no P32H code list is loaded");
        assertThat(finding.detail()).isEqualTo("No P32H code list is loaded. The tool is using the synthetic placeholder list that ships "
                + "with it, which says nothing about real codes, so nothing was compared with the service's answer. Set codelist.file to "
                + "the P32H export and restart the tool.");
        // About the run, not about one of its codes, and with no position stated for either side.
        assertThat(finding.subject()).isNull();
        assertThat(finding.codeListSays()).isNull();
        assertThat(finding.serviceSays()).isNull();
        // Which list is loaded was asked once for the check, not once for each code.
        assertThat(codeList.selfChecks()).isEqualTo(1);
    }

    @Test
    void onLiveNoRowOfThePlaceholderIsEvenLookedUp() {
        // Looking a code up in this list now throws. Nothing is compared, so nothing is looked up.
        codeList.asPlaceholder().row("95810", AuthRequirement.REQUIRED, "Medicare", null).failing(new IllegalStateException("a row was looked up"));
        assertThat(kinds(checkOn(LIVE, 200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "95810")))
                .containsExactly(FindingKind.CODE_LIST_NOT_COMPARED);
    }

    @Test
    void onLiveThePlaceholderFindingIsTheSameWhateverTheListHoldsForTheCode() {
        codeList.asPlaceholder().row("95810", AuthRequirement.REQUIRED, "Commercial", null).row("70553", AuthRequirement.UNKNOWN, null, null);
        String oneAnswer = response(action(1, code("pa-needed", "no-auth")));
        // A code the list does not have, one it has under another line, one it says nothing about,
        // a member whose line is not known, and an order no answer could be tied to: each has its
        // own reason with a real list. Here the reason is the same for all, and it is not one of those.
        for (String code : new String[] {"99999", "95810", "70553"}) {
            for (String line : new String[] {"Medicare", null}) {
                Finding finding = notCompared(checkOn(LIVE, 200, oneAnswer, line, code));
                assertThat(finding.title()).as(code + " for " + line).endsWith("no P32H code list is loaded");
                assertThat(finding.subject()).isNull();
            }
        }
        assertThat(notCompared(checkOn(LIVE, 200, response(action(7, code("pa-needed", "no-auth"))), "Medicare", "95810")).title())
                .endsWith("no P32H code list is loaded");
        // Still said when the request named no code this tool could read. A determination came
        // back, and a result that says nothing about the code list reads as one that agrees with it.
        assertThat(notCompared(checkOn(LIVE, 200, oneAnswer, "Medicare")).title()).endsWith("no P32H code list is loaded");
    }

    @Test
    void onLiveThePlaceholderIsNotMentionedWhenTheServiceGaveNoDeterminationToCompare() {
        codeList.asPlaceholder().row("95810", AuthRequirement.REQUIRED, null, null);
        // A fault is the headline, and a response with no coverage information has its own finding.
        // That is the rule for a real list too: the code list is spoken of only beside a determination.
        assertThat(checkOn(LIVE, 500, "{\"resourceType\":\"OperationOutcome\",\"issue\":[]}", null, "95810")).isEmpty();
        assertThat(kinds(checkOn(LIVE, 200, "{\"cards\":[]}", null, "95810"))).containsExactly(FindingKind.REQUIRED_ELEMENT_MISSING);
        VendorExchange timeout = TestExchanges.timeout(EpaOperation.CRD_ORDER_SIGN);
        assertThat(new CrdConformanceChecker(codeList, LIVE).check(mapper.read(timeout, orders("95810")), timeout, null, null)).isEmpty();
    }

    @Test
    void onLiveWhatTheResponseItselfGetsWrongIsStillReportedBesideThePlaceholderFinding() {
        codeList.asPlaceholder().row("95810", AuthRequirement.REQUIRED, null, null);
        List<Finding> findings = checkOn(LIVE, 200, response(action(1, code("covered", "maybe") + "," + code("pa-needed", "no-auth"))), null, "95810");
        assertThat(kinds(findings)).containsExactly(FindingKind.CODE_OUTSIDE_VALUE_SET, FindingKind.CODE_LIST_NOT_COMPARED);
        // The check of the response against the implementation guide does not rest on the list, and says nothing about it.
        assertThat(findings.get(0).detail()).doesNotContain("placeholder");
    }

    @Test
    void aModeThatIsNotMockIsTreatedAsLiveBecauseThatIsTheSaferMistake() {
        codeList.asPlaceholder().row("95810", AuthRequirement.REQUIRED, null, null);
        String body = response(action(1, code("pa-needed", "no-auth")));
        // The application refuses to start on any mode but mock or live. If this class is ever
        // built with something else, invented rows are still not compared with an answer.
        for (String mode : new String[] {"live", "LIVE", "uat", "production"}) {
            assertThat(notCompared(checkOn(EpaProperties.of(Map.of("mode", mode)), 200, body, null, "95810")).title()).as(mode)
                    .endsWith("no P32H code list is loaded");
        }
        assertThat(kinds(checkOn(EpaProperties.of(Map.of("mode", "MOCK")), 200, body, null, "95810")))
                .containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
    }

    @Test
    void onMockThePlaceholderIsComparedAndADisagreementSaysWhichListItRestsOn() {
        codeList.asPlaceholder().row("95810", AuthRequirement.REQUIRED, "Medicare", null).row("70553", AuthRequirement.REQUIRED, "Medicare", "Evolent");
        String body = response(action(1, code("pa-needed", "no-auth")), action(2, code("pa-needed", "auth-needed") + "," + vendor("Carelon")));

        List<Finding> findings = checkOn(MOCK, 200, body, "Medicare", "95810", "70553");
        assertThat(kinds(findings)).containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH, FindingKind.CODE_LIST_DISAGREES_ON_VENDOR);
        assertThat(findings.get(0).detail()).isEqualTo("One of them is wrong, and this tool cannot tell which." + PLACEHOLDER_NOTE);
        assertThat(findings.get(1).detail()).isEqualTo("One of them is wrong, and this tool cannot tell which." + PLACEHOLDER_NOTE);
        // Everything else about the finding is as it is with a real list.
        assertThat(findings.get(0).title()).isEqualTo("The code list and the service disagree on prior authorization for 95810");
        assertThat(findings.get(0).subject()).isEqualTo("95810");
        assertThat(findings.get(0).codeListSays()).isEqualTo("The code list says prior authorization is required (rows for Medicare).");
        assertThat(findings.get(0).serviceSays()).isEqualTo("The service answered pa-needed=no-auth.");
        assertThat(findings.get(1).codeListSays()).isEqualTo("The code list routes this code to Evolent (rows for Medicare).");
        // Asked once for the check, here too.
        assertThat(codeList.selfChecks()).isEqualTo(1);

        // The vendor the service does not name at all.
        assertThat(checkOn(MOCK, 200, response(action(1, code("pa-needed", "auth-needed"))), "Medicare", "70553")).singleElement()
                .satisfies(f -> {
                    assertThat(f.title()).isEqualTo("The code list routes 70553 to a vendor and the service names none");
                    assertThat(f.detail()).isEqualTo("One of them is wrong, and this tool cannot tell which." + PLACEHOLDER_NOTE);
                });
    }

    @Test
    void onMockEveryReasonTheTwoWereNotComparedSaysWhichListItRestsOn() {
        codeList.asPlaceholder()
                .row("95810", AuthRequirement.REQUIRED, "Commercial", null)
                .row("70553", AuthRequirement.UNKNOWN, "Medicare", null)
                .row("E0601", AuthRequirement.REQUIRED, "Medicare", null).row("E0601", AuthRequirement.NOT_REQUIRED, "Medicare", null)
                .row("G0299", AuthRequirement.REQUIRED, "Medicare", "Evolent").row("G0299", AuthRequirement.REQUIRED, "Medicare", "Carelon")
                .row("E0470", AuthRequirement.REQUIRED, "Medicare", null);
        String noAuth = response(action(1, code("pa-needed", "no-auth")));
        String needed = response(action(1, code("pa-needed", "auth-needed")));
        String neededWithVendor = response(action(1, code("pa-needed", "auth-needed") + "," + vendor("eviCore")));

        // No row at all, no row under the member's line, and a member whose line is not known.
        assertThat(notCompared(checkOn(MOCK, 200, noAuth, "Medicare", "99999")).detail())
                .isEqualTo("The code list has no row for 99999." + PLACEHOLDER_NOTE);
        assertThat(notCompared(checkOn(MOCK, 200, noAuth, null, "99999")).detail())
                .isEqualTo("The code list has no row for 99999." + PLACEHOLDER_NOTE);
        assertThat(notCompared(checkOn(MOCK, 200, noAuth, "Medicare", "95810")).detail())
                .startsWith("The code list has no row for 95810 under Medicare.").endsWith("is not this member's answer." + PLACEHOLDER_NOTE);
        assertThat(notCompared(checkOn(MOCK, 200, noAuth, null, "95810")).detail())
                .startsWith("The member's line of business is not known").endsWith("would be a guess about the member." + PLACEHOLDER_NOTE);
        // A side that takes no position, and rows that contradict each other.
        assertThat(notCompared(checkOn(MOCK, 200, needed, "Medicare", "70553")).detail())
                .isEqualTo("The code list does not say whether 70553 needs prior authorization (rows for Medicare)." + PLACEHOLDER_NOTE);
        assertThat(notCompared(checkOn(MOCK, 200, response(action(1, code("pa-needed", "conditional"))), "Medicare", "E0470")).detail())
                .startsWith("The service's answer for E0470 does not say").endsWith("nothing to compare with the code list." + PLACEHOLDER_NOTE);
        assertThat(notCompared(checkOn(MOCK, 200, noAuth, "Medicare", "E0601")).detail())
                .contains("disagree with each other on prior authorization").endsWith("no single position to compare." + PLACEHOLDER_NOTE);
        // Routing that could not be compared, both ways round.
        assertThat(notCompared(checkOn(MOCK, 200, neededWithVendor, "Medicare", "G0299")).detail())
                .contains("name different vendors").endsWith("The list has no single routing to compare." + PLACEHOLDER_NOTE);
        assertThat(notCompared(checkOn(MOCK, 200, neededWithVendor, "Medicare", "E0470")).detail())
                .startsWith("The service routes E0470 to eviCore.").endsWith("so the routing could not be compared." + PLACEHOLDER_NOTE);
        // An order no answer could be tied to.
        assertThat(notCompared(checkOn(MOCK, 200, response(action(7, code("pa-needed", "auth-needed"))), "Medicare", "E0470")).detail())
                .isEqualTo("The response carries no answer this tool could tie to E0470." + PLACEHOLDER_NOTE);
    }

    @Test
    void onMockAgreementWithThePlaceholderIsStillNoFindingAndOtherFindingsDoNotMentionIt() {
        codeList.asPlaceholder().row("95810", AuthRequirement.REQUIRED, "Medicare", "eviCore");
        assertThat(checkOn(MOCK, 200, response(action(1, code("covered", "covered") + "," + code("pa-needed", "auth-needed") + ","
                + vendor("eviCore"))), "Medicare", "95810")).isEmpty();
        // A finding about the response alone does not rest on the list, so it carries no note about one.
        List<Finding> findings = checkOn(MOCK, 200, response(action(1, code("covered", "maybe") + "," + code("pa-needed", "no-auth"))),
                "Medicare", "95810");
        assertThat(kinds(findings)).containsExactly(FindingKind.CODE_OUTSIDE_VALUE_SET, FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
        assertThat(findings.get(0).detail()).doesNotContain("placeholder");
        assertThat(findings.get(1).detail()).endsWith(PLACEHOLDER_NOTE);
    }

    @Test
    void aRealListSaysNothingAboutAPlaceholderOnEitherProfile() {
        // The list here is not marked, so it stands for a real export.
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", null);
        for (EpaProperties profile : List.of(MOCK, LIVE)) {
            List<Finding> findings = checkOn(profile, 200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "95810", "99999");
            assertThat(kinds(findings)).containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH, FindingKind.CODE_LIST_NOT_COMPARED);
            assertThat(findings.get(0).detail()).isEqualTo("One of them is wrong, and this tool cannot tell which.");
            assertThat(findings.get(1).detail()).isEqualTo("The code list has no row for 99999.");
        }
        // Still asked only once for each check, however many codes there were.
        assertThat(codeList.selfChecks()).isEqualTo(2);
    }
}
