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
    void theFindingSaysWhichOfTheTwoItWas() {
        assertThat(about(findings("{\"url\":\"covered\"}"), "covered")).contains("it carries no value[x] at all");
        assertThat(about(findings("{\"url\":\"covered\",\"valueCode\":\"\"}"), "covered")).contains("its value is empty")
                .doesNotContain("no value[x]");
        assertThat(about(findings("{\"url\":\"covered\",\"valueCode\":null}"), "covered")).contains("its value is null");
    }
}
