package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;

/**
 * What a response this tool builds states about itself: the intended use DTR 2.0.1 requires, from
 * the request or from CRD and never assumed, and the form the coverage is named in.
 */
class DtrResponseShapeTest {

    private static final String TEMP = "http://hl7.org/fhir/us/davinci-crd/CodeSystem/temp";
    private static final String NULL_FLAVOR = "http://terminology.hl7.org/CodeSystem/v3-NullFlavor";

    private static Function<String, ResolvedText> variables(String... pairs) {
        FakeGateway gateway = FakeGateway.unconfigured();
        for (int i = 0; i < pairs.length; i += 2) {
            gateway.variable(pairs[i], pairs[i + 1]);
        }
        return template -> gateway.resolve(template, Map.of());
    }

    private static Map<String, List<String>> crdSaid(String... codes) {
        return Map.of(NextStepView.CARRY_DOC_PURPOSE, List.of(codes));
    }

    @Test
    void theIntendedUseTheRequestStatesIsSentWithTheCodeSystemCrd201PutsItIn() {
        DtrResponseShape shape = DtrResponseShape.read(variables("dtrIntendedUse", "withpa"), Map.of());
        assertThat(shape.statesIntendedUse()).isTrue();
        assertThat(shape.intendedUses()).singleElement().satisfies(use -> {
            assertThat(use.at("/coding/0/system").asString()).isEqualTo(TEMP);
            assertThat(use.at("/coding/0/code").asString()).isEqualTo("withpa");
        });
        assertThat(shape.intendedUseSource()).isEqualTo("as the variable dtrIntendedUse states");
        assertThat(shape.notes()).isEmpty();

        // Several are separated by commas. OTH is a NullFlavor code in the 2.0.1 value set, not a code of the CRD code system.
        DtrResponseShape two = DtrResponseShape.read(variables("dtrIntendedUse", "OTH, withorder"), Map.of());
        assertThat(two.intendedUses()).extracting(use -> use.at("/coding/0/system").asString()).containsExactly(NULL_FLAVOR, TEMP);
        assertThat(two.codes()).containsExactly("OTH", "withorder");

        // A system|code pair is the tester's own statement, and goes as written.
        DtrResponseShape pair = DtrResponseShape.read(variables("dtrIntendedUse",
                "http://hl7.org/fhir/us/davinci-crd/CodeSystem/coverage-information-codes|withclaim"), Map.of());
        assertThat(pair.intendedUses().get(0).at("/coding/0/system").asString())
                .isEqualTo("http://hl7.org/fhir/us/davinci-crd/CodeSystem/coverage-information-codes");
        assertThat(pair.notes()).isEmpty();

        // A bare code outside the 2.0.1 value set is sent as stated, with no system, and the note quotes the 2.0.1 list as the 2.0.1 list.
        DtrResponseShape outside = DtrResponseShape.read(variables("dtrIntendedUse", "retain"), Map.of());
        assertThat(outside.intendedUses().get(0).at("/coding/0").has("system")).isFalse();
        assertThat(outside.notes()).containsExactly("The variable dtrIntendedUse states the intended use retain, which is not a code of the "
                + "CRD 2.0.1 value set DocReason that DTR 2.0.1 binds the intended use to (withpa, withclaim, withorder, retain-doc, OTH). "
                + "It was sent as stated, with no code system.");
    }

    @Test
    void withoutAStatementTheIntendedUseIsWhatCrdSaidTheDocumentationIsForAndOtherwiseNone() {
        DtrResponseShape fromCrd = DtrResponseShape.read(variables(), crdSaid("withclaim", "oth"));
        assertThat(fromCrd.codes()).containsExactly("withclaim");
        assertThat(fromCrd.intendedUseSource()).isEqualTo("as CRD said the documentation is for (doc-purpose)");
        // A code of CRD's that 2.0.1 cannot bind is not passed on, and is not dropped in silence either.
        assertThat(fromCrd.notes()).containsExactly("CRD said the documentation is for oth, which is not a code of the CRD 2.0.1 value set "
                + "DocReason that DTR 2.0.1 binds the intended use to (withpa, withclaim, withorder, retain-doc, OTH), so it was not stated "
                + "as an intended use.");

        // What the request states comes first.
        assertThat(DtrResponseShape.read(variables("dtrIntendedUse", "withorder"), crdSaid("withpa")).codes()).containsExactly("withorder");

        // Nothing stated and nothing handed on: none is assumed, not even "with the prior authorization".
        DtrResponseShape none = DtrResponseShape.read(variables(), Map.of());
        assertThat(none.statesIntendedUse()).isFalse();
        assertThat(none.intendedUseSource()).isNull();
        assertThat(DtrResponseShape.read(null, null).statesIntendedUse()).isFalse();
    }

    @Test
    void theCoverageIsNamedAsDtr201HasItUnlessTheRequestAsksForTheLaterFormByName() {
        assertThat(DtrResponseShape.read(variables(), Map.of()).laterCoverageForm()).isFalse();
        assertThat(DtrResponseShape.read(variables("dtrCoverageExtension", "qr-context"), Map.of()).laterCoverageForm()).isFalse();
        assertThat(DtrResponseShape.read(variables("dtrCoverageExtension", "qr-coverage"), Map.of()).laterCoverageForm()).isTrue();
        assertThat(DtrResponseShape.read(variables("dtrCoverageExtension", " QR-Coverage "), Map.of()).laterCoverageForm()).isTrue();
        DtrResponseShape unknown = DtrResponseShape.read(variables("dtrCoverageExtension", "2.2.0"), Map.of());
        assertThat(unknown.laterCoverageForm()).isFalse();
        assertThat(unknown.notes()).containsExactly("The variable dtrCoverageExtension holds a value that names no form this tool knows: "
                + "qr-context (DTR 2.0.1) or qr-coverage (DTR 2.2.0-ballot). The coverage is named in qr-context, as DTR 2.0.1 has it.");
    }
}
