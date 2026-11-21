package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.crd.CoverageInformationView;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

/**
 * A sub-extension sent with no value[x] at all and one whose value is an empty string are both
 * shown as empty, and are two different answers. The view names the first, and the finding says
 * which of the two it was.
 */
class CrdNoValueTest {

    private static final String EXT = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";

    private final CrdResponseMapper mapper = new CrdResponseMapper();

    private static String body(String subExtensions) {
        return "{\"cards\":[],\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"sr-1\","
                + "\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[" + subExtensions + "]}]}}]}";
    }

    private CoverageInformationView view(String subExtensions) {
        return mapper.read(TestExchanges.crd(200, body(subExtensions))).detail().coverageInformation().get(0);
    }

    private List<Finding> findings(String subExtensions) {
        var exchange = TestExchanges.crd(200, body(subExtensions));
        return new CrdConformanceChecker(new org.p32h.interop.epa.pave.testsupport.FakeCodeList(),
                org.p32h.interop.epa.pave.config.EpaProperties.of(java.util.Map.of("mode", "mock")))
                .check(mapper.read(exchange), exchange, null, null);
    }

    private static String about(List<Finding> findings, String element) {
        return findings.stream().filter(f -> f.kind() == FindingKind.REQUIRED_ELEMENT_MISSING && f.title().startsWith(element + " "))
                .findFirst().orElseThrow().detail();
    }

    @Test
    void aSubExtensionWithNoValueIsNamedAndOneWithAnEmptyValueIsNot() {
        CoverageInformationView bare = view("{\"url\":\"covered\"},{\"url\":\"date\"},{\"url\":\"doc-purpose\",\"valueCode\":\"withpa\"},"
                + "{\"url\":\"doc-purpose\"}");
        assertThat(bare.covered().presence()).isEqualTo(Presence.EMPTY);
        assertThat(bare.sentWithNoValue()).containsExactly("covered", "date", "doc-purpose[1]");
        CoverageInformationView empty = view("{\"url\":\"covered\",\"valueCode\":\"\"},{\"url\":\"date\",\"valueDate\":\"\"}");
        assertThat(empty.covered().presence()).isEqualTo(Presence.EMPTY);
        assertThat(empty.sentWithNoValue()).isEmpty();
    }

    @Test
    void theLedgerLineTellsNotReturnedNullEmptyAndNoValueApart() {
        String covered = "{\"url\":\"covered\",\"valueCode\":\"covered\"}";
        assertThat(mapper.read(TestExchanges.crd(200, body(covered))).ledgerCodes()).containsExactly("covered=covered");
        assertThat(mapper.read(TestExchanges.crd(200, body(covered + ",{\"url\":\"pa-needed\",\"valueCode\":null}"))).ledgerCodes())
                .containsExactly("covered=covered", "pa-needed=(null)");
        assertThat(mapper.read(TestExchanges.crd(200, body(covered + ",{\"url\":\"pa-needed\",\"valueCode\":\"\"}"))).ledgerCodes())
                .containsExactly("covered=covered", "pa-needed=(empty)");
        assertThat(mapper.read(TestExchanges.crd(200, body(covered + ",{\"url\":\"pa-needed\"}"))).ledgerCodes())
                .containsExactly("covered=covered", "pa-needed=(no value)");
    }

    @Test
    void theRecordKeepsTheReadingsOwnStatementsOfACodeSentWithNothingInIt() {
        // The ledger checks every coverage code it is given. A null pa-needed written as "(null)"
        // must reach History as that, not as a six-character value that is not a code.
        String covered = "{\"url\":\"covered\",\"valueCode\":\"covered\"}";
        List<String> written = new java.util.ArrayList<>();
        for (String paNeeded : List.of("{\"url\":\"pa-needed\",\"valueCode\":null}", "{\"url\":\"pa-needed\",\"valueCode\":\"\"}",
                "{\"url\":\"pa-needed\"}")) {
            org.p32h.interop.epa.pave.model.RunRecord record = record(mapper.read(TestExchanges.crd(200, body(covered + "," + paNeeded)))
                    .ledgerCodes());
            org.p32h.interop.epa.pave.model.RunRecord readBack = org.p32h.interop.epa.pave.model.JsonSafe.mapper().readValue(
                    org.p32h.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(record), org.p32h.interop.epa.pave.model.RunRecord.class);
            assertThat(readBack.coverageCodes()).isEqualTo(record.coverageCodes());
            written.add(record.coverageCodes().get(1));
        }
        assertThat(written).containsExactly("pa-needed=(null)", "pa-needed=(empty)", "pa-needed=(no value)");
        // Before a code that was asked about, too; and a real value that is not a code still gives only its length.
        assertThat(record(List.of("95810: pa-needed=(null)", "pa-needed=(nulls)", "pa-needed=(null) and more")).coverageCodes())
                .containsExactly("95810: pa-needed=(null)", "pa-needed=(not a code, 7 characters)", "pa-needed=(not a code, 15 characters)");
    }

    @Test
    void aReturnedCodeThatReadsLikeOneOfTheStatementsIsNotWrittenAsOne() {
        String body = body("{\"url\":\"covered\",\"valueCode\":\"covered\"},{\"url\":\"pa-needed\",\"valueCode\":\"(null)\"}");
        assertThat(record(mapper.read(TestExchanges.crd(200, body)).ledgerCodes()).coverageCodes())
                .containsExactly("covered=covered", "pa-needed=(not a code, 6 characters)");
    }

    private static org.p32h.interop.epa.pave.model.RunRecord record(List<String> coverageCodes) {
        return org.p32h.interop.epa.pave.model.RunRecord.builder().runId("run-1").timestamp("2026-03-10T10:00:00.000-05:00")
                .protocol(org.p32h.interop.epa.pave.model.Protocol.CRD).operation(org.p32h.interop.epa.pave.model.EpaOperation.CRD_ORDER_SIGN)
                .outcome("NO_DETERMINATION").result(org.p32h.interop.epa.pave.model.TestResult.UNVERIFIED).coverageCodes(coverageCodes)
                .build();
    }

    @Test
    void theFindingSaysWhichOfTheTwoItWas() {
        assertThat(about(findings("{\"url\":\"covered\"}"), "covered")).contains("it carries no value[x] at all");
        assertThat(about(findings("{\"url\":\"covered\",\"valueCode\":\"\"}"), "covered")).contains("its value is empty")
                .doesNotContain("no value[x]");
        assertThat(about(findings("{\"url\":\"covered\",\"valueCode\":null}"), "covered")).contains("its value is null");
    }

    @Test
    void theDeterminationGridSaysASubExtensionWasSentWithNoValueAndAnEmptyOneIsJustEmpty() {
        List<org.p32h.interop.epa.pave.model.DeterminationRow> bare = CrdService.determination(mapper.read(TestExchanges.crd(200,
                body("{\"url\":\"covered\"},{\"url\":\"pa-needed\",\"valueCode\":\"\"},{\"url\":\"doc-purpose\",\"valueCode\":\"withpa\"},"
                        + "{\"url\":\"doc-purpose\"}"))));
        assertThat(bare).extracting(org.p32h.interop.epa.pave.model.DeterminationRow::label, org.p32h.interop.epa.pave.model.DeterminationRow::presence,
                org.p32h.interop.epa.pave.model.DeterminationRow::sentWithNoValue).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Covered", Presence.EMPTY, true),
                org.assertj.core.groups.Tuple.tuple("Prior authorization", Presence.EMPTY, null),
                org.assertj.core.groups.Tuple.tuple("Documentation", Presence.ABSENT, null),
                org.assertj.core.groups.Tuple.tuple("Documentation purpose", Presence.VALUE, null),
                org.assertj.core.groups.Tuple.tuple("Documentation purpose", Presence.EMPTY, true));
    }
}
