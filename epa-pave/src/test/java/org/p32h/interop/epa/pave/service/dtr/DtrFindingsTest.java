package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;

/**
 * A package bundle that does not start with its questionnaire breaks the DTR 2.0.1 invariant
 * dtrb-1, and that is raised as a broken rule of the guide, so that history can be searched for it
 * by its kind, as the misplaced review action of PAS is.
 */
class DtrFindingsTest {

    private static final String QUESTIONNAIRE = "{\"resourceType\":\"Questionnaire\",\"url\":\"https://example.org/p32h-placeholder/"
            + "Questionnaire/a\",\"status\":\"active\",\"item\":[{\"linkId\":\"q1\",\"type\":\"string\"}]}";
    private static final String LIBRARY = "{\"resourceType\":\"Library\",\"url\":\"https://example.org/p32h-placeholder/Library/a\"}";

    private static List<Finding> findings(String body) {
        return DtrFindings.check(new DtrResponseMapper().readPackage(DtrFixtures.answered(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, 200, body),
                List.of()));
    }

    private static String parameters(String... bundles) {
        StringBuilder out = new StringBuilder("{\"resourceType\":\"Parameters\",\"parameter\":[");
        for (int i = 0; i < bundles.length; i++) {
            out.append(i == 0 ? "" : ",").append("{\"name\":\"PackageBundle\",\"resource\":{\"resourceType\":\"Bundle\",\"type\":\"collection\","
                    + "\"entry\":[").append(bundles[i]).append("]}}");
        }
        return out.append("]}").toString();
    }

    private static List<Finding> broken(List<Finding> findings) {
        return findings.stream().filter(finding -> finding.kind() == FindingKind.GUIDE_RULE_BROKEN).toList();
    }

    @Test
    void aPackageBundleThatDoesNotStartWithItsQuestionnaireBreaksDtrb1AndIsRaisedAsABrokenRule() {
        List<Finding> found = broken(findings(parameters("{\"resource\":" + QUESTIONNAIRE + "}",
                "{\"resource\":" + LIBRARY + "},{\"resource\":" + QUESTIONNAIRE.replace("/a\"", "/b\"") + "}")));
        assertThat(found).singleElement().satisfies(finding -> {
            assertThat(finding.title()).isEqualTo("A package bundle does not start with its questionnaire (dtrb-1)");
            assertThat(finding.detail()).isEqualTo("DTR 2.0.1 requires the first resource of a package bundle to be a Questionnaire (invariant "
                    + "dtrb-1, on DTR-QPackageBundle and on the PackageBundle of dtr-qpackage-output-parameters). The first entry of this "
                    + "bundle is not a Questionnaire. What the bundle holds is shown as it came.");
            // The second bundle, not the first.
            assertThat(finding.subject()).isEqualTo("parameter[1].resource");
        });
    }

    @Test
    void aPackageBundleWithNoQuestionnaireOrNoEntryBreaksDtrb1() {
        assertThat(broken(findings(parameters("{\"resource\":" + LIBRARY + "}")))).singleElement()
                .satisfies(finding -> assertThat(finding.subject()).isEqualTo("parameter[0].resource"));
        assertThat(broken(findings("{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"PackageBundle\",\"resource\":"
                + "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[]}}]}"))).singleElement()
                .satisfies(finding -> assertThat(finding.detail()).contains("This bundle holds no entry."));
    }

    @Test
    void aPackageWhoseBundlesEachStartWithTheirQuestionnaireBreaksNothingAndABundleInsideOneIsNotJudged() {
        assertThat(broken(findings(parameters("{\"resource\":" + QUESTIONNAIRE + "},{\"resource\":" + LIBRARY + "}")))).isEmpty();
        // A bundle carried inside a package bundle is not a package bundle, so it need not start with a questionnaire.
        String inner = "{\"resource\":{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + LIBRARY + "}]}}";
        assertThat(broken(findings(parameters("{\"resource\":" + QUESTIONNAIRE + "}," + inner)))).isEmpty();
        // A bare Bundle that starts with its questionnaire is not flagged either.
        assertThat(broken(findings("{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":" + QUESTIONNAIRE + "}]}")))
                .isEmpty();
    }
}
