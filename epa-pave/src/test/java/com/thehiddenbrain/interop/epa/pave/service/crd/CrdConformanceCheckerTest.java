package com.thehiddenbrain.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import com.thehiddenbrain.interop.epa.pave.model.AuthRequirement;
import com.thehiddenbrain.interop.epa.pave.model.CodeInput;
import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;
import com.thehiddenbrain.interop.epa.pave.model.Finding;
import com.thehiddenbrain.interop.epa.pave.model.FindingKind;
import com.thehiddenbrain.interop.epa.pave.model.VendorExchange;
import com.thehiddenbrain.interop.epa.pave.testsupport.FakeCodeList;
import com.thehiddenbrain.interop.epa.pave.testsupport.TestExchanges;
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

    private List<Finding> check(int status, String body, String lineOfBusiness, String... codes) {
        VendorExchange exchange = TestExchanges.crd(status, body);
        return new CrdConformanceChecker(codeList).check(mapper.read(exchange), exchange,
                java.util.Arrays.stream(codes).map(CodeInput::of).toList(), lineOfBusiness, null);
    }

    private static List<FindingKind> kinds(List<Finding> findings) {
        return findings.stream().map(Finding::kind).toList();
    }

    @Test
    void aCleanResponseThatAgreesWithTheCodeListHasNoFindings() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", "eviCore");
        assertThat(check(200, response(action(1, code("covered", "covered") + "," + code("pa-needed", "auth-needed") + "," + vendor("eviCore"))),
                "Medicare", "95810")).isEmpty();
    }

    @Test
    void aCodeOutsideItsValueSetIsReportedAsNonConformantWithTheValueSet() {
        List<Finding> findings = check(200, response(action(1, code("covered", "maybe") + "," + code("pa-needed", "auth-needed") + ","
                + code("doc-purpose", "withfax") + "," + code("info-needed", "mood"))), null, "95810");
        assertThat(kinds(findings)).containsExactly(FindingKind.CODE_OUTSIDE_VALUE_SET, FindingKind.CODE_OUTSIDE_VALUE_SET,
                FindingKind.CODE_OUTSIDE_VALUE_SET);
        assertThat(findings.get(0).title()).isEqualTo("Non-conformant code in covered");
        assertThat(findings.get(0).detail()).contains("\"maybe\"").contains("covered, not-covered, conditional");
        assertThat(findings.get(0).serviceSays()).isEqualTo("The service returned covered=maybe.");
        assertThat(findings.get(0).subject()).isEqualTo("ServiceRequest/service-request-1");
    }

    @Test
    void coverageInformationWithNoAnswerSaysSo() {
        List<Finding> findings = check(200, response(action(1, "{\"url\":\"date\",\"valueDate\":\"2026-01-15\"}")), null, "95810");
        assertThat(kinds(findings)).containsExactly(FindingKind.REQUIRED_ELEMENT_MISSING);
        assertThat(findings.get(0).detail()).contains("carries neither covered nor pa-needed");
    }

    @Test
    void anElementSentWithNoValueIsAFindingAndNotAnAnswer() {
        List<Finding> findings = check(200, response(action(1, code("covered", "covered") + ",{\"url\":\"pa-needed\",\"valueCode\":null}")),
                null, "95810");
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.REQUIRED_ELEMENT_MISSING);
            assertThat(f.title()).isEqualTo("pa-needed was sent with no value");
            assertThat(f.detail()).contains("its value is null");
        });
    }

    @Test
    void aSuccessfulResponseWithNoCoverageInformationIsAFindingAndAFaultIsNot() {
        assertThat(check(200, "{\"cards\":[]}", null, "95810")).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.REQUIRED_ELEMENT_MISSING);
            assertThat(f.title()).isEqualTo("No coverage information in the response");
        });
        // A fault is already the headline. It is not also reported as a missing element.
        assertThat(check(500, "{\"resourceType\":\"OperationOutcome\",\"issue\":[]}", null, "95810")).isEmpty();
        VendorExchange timeout = TestExchanges.timeout(EpaOperation.CRD_ORDER_SIGN);
        assertThat(new CrdConformanceChecker(codeList).check(mapper.read(timeout), timeout, List.of(CodeInput.of("95810")), null, null)).isEmpty();
    }

    @Test
    void theCodeListAndTheServiceDisagreeingOnAuthorizationIsReportedInBothDirections() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", null).row("70553", AuthRequirement.NOT_REQUIRED, "Medicare", null);
        List<Finding> listSaysRequired = check(200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "95810");
        assertThat(listSaysRequired).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
            assertThat(f.codeListSays()).isEqualTo("The code list says prior authorization is required for Medicare.");
            assertThat(f.serviceSays()).isEqualTo("The service answered pa-needed=no-auth.");
        });
        List<Finding> listSaysNotRequired = check(200, response(action(1, code("pa-needed", "auth-needed"))), "Medicare", "70553");
        assertThat(listSaysNotRequired).singleElement().satisfies(f -> {
            assertThat(f.codeListSays()).isEqualTo("The code list says prior authorization is not required for Medicare.");
            assertThat(f.serviceSays()).isEqualTo("The service answered pa-needed=auth-needed.");
        });
        // "Satisfied" means one was needed and exists, which agrees with "required".
        assertThat(check(200, response(action(1, code("pa-needed", "satisfied"))), "Medicare", "95810")).isEmpty();
        assertThat(kinds(check(200, response(action(1, code("pa-needed", "satisfied"))), "Medicare", "70553")))
                .containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
    }

    @Test
    void aSideThatTakesNoPositionIsNotADisagreement() {
        codeList.row("95810", AuthRequirement.UNKNOWN, "Medicare", null);
        // The list does not say. That is never read as "no".
        assertThat(check(200, response(action(1, code("pa-needed", "auth-needed"))), "Medicare", "95810")).isEmpty();
        // The service says "conditional", which is not a position either.
        codeList.row("70553", AuthRequirement.REQUIRED, "Medicare", null);
        assertThat(check(200, response(action(1, code("pa-needed", "conditional"))), "Medicare", "70553")).isEmpty();
        // A code the list does not have at all.
        assertThat(check(200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "99999")).isEmpty();
    }

    @Test
    void linesOfBusinessAreNeverConflated() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Commercial", null);
        // The list knows Commercial only. A Medicare member's answer is not compared with a Commercial row.
        assertThat(check(200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "95810")).isEmpty();
        // The same answer for a Commercial member is a disagreement.
        assertThat(kinds(check(200, response(action(1, code("pa-needed", "no-auth"))), "Commercial", "95810")))
                .containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
        // A row that states no line applies to every line the list knows.
        codeList.row("70553", AuthRequirement.REQUIRED, null, null);
        assertThat(kinds(check(200, response(action(1, code("pa-needed", "no-auth"))), "Commercial", "70553")))
                .containsExactly(FindingKind.CODE_LIST_DISAGREES_ON_AUTH);
    }

    @Test
    void rowsThatContradictEachOtherGiveTheListNoSinglePosition() {
        codeList.row("95810", AuthRequirement.REQUIRED, "Medicare", null).row("95810", AuthRequirement.NOT_REQUIRED, "Medicare", null);
        assertThat(check(200, response(action(1, code("pa-needed", "no-auth"))), "Medicare", "95810")).isEmpty();
    }

    @Test
    void theVendorDisagreeingIsReportedWhenTheServiceNamesAnotherOrNamesNone() {
        codeList.row("70553", AuthRequirement.REQUIRED, "Medicare", "Evolent");
        List<Finding> another = check(200, response(action(1, code("pa-needed", "auth-needed") + "," + vendor("Carelon"))), "Medicare", "70553");
        assertThat(another).singleElement().satisfies(f -> {
            assertThat(f.kind()).isEqualTo(FindingKind.CODE_LIST_DISAGREES_ON_VENDOR);
            assertThat(f.codeListSays()).isEqualTo("The code list routes this code to Evolent for Medicare.");
            assertThat(f.serviceSays()).isEqualTo("The service routes it to Carelon.");
        });
        List<Finding> none = check(200, response(action(1, code("pa-needed", "auth-needed"))), "Medicare", "70553");
        assertThat(none).singleElement().satisfies(f -> assertThat(f.serviceSays()).isEqualTo("The service named no delegated vendor."));
        // The same vendor spelled differently is the same vendor.
        assertThat(check(200, response(action(1, code("pa-needed", "auth-needed") + "," + vendor("EVOLENT"))), "Medicare", "70553")).isEmpty();
    }

    @Test
    void eachCodeIsComparedWithTheAnswerForItsOwnServiceRequest() {
        codeList.row("95810", AuthRequirement.REQUIRED, null, null).row("70553", AuthRequirement.REQUIRED, null, null);
        // The first code is answered auth-needed (agrees), the second no-auth (disagrees).
        List<Finding> findings = check(200, response(action(1, code("pa-needed", "auth-needed")), action(2, code("pa-needed", "no-auth"))),
                null, "95810", "70553");
        assertThat(findings).singleElement().satisfies(f -> assertThat(f.subject()).isEqualTo("70553"));
        // An answer for a service request that pairs with no code that was sent is not compared with anything.
        assertThat(check(200, response(action(7, code("pa-needed", "no-auth"))), null, "95810")).isEmpty();
    }

    @Test
    void withoutACodeListOnlyTheResponseItselfIsChecked() {
        VendorExchange exchange = TestExchanges.crd(200, response(action(1, code("pa-needed", "no-auth"))));
        assertThat(new CrdConformanceChecker(null).check(mapper.read(exchange), exchange, List.of(CodeInput.of("95810")), "Medicare", null)).isEmpty();
    }
}
