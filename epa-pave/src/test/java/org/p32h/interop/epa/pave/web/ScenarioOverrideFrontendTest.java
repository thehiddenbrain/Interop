package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A mock scenario chosen by hand is said under the Member ID field, and a typed NPI that cannot be
 * looked up is said once, at the field. The note says only what is certain: which scenario was
 * chosen. Which one the member ID or the NPI would pick is the server's rule, and no part of it is
 * copied into a script.
 */
class ScenarioOverrideFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void theChosenScenarioIsFoundByItsIdAloneWhenNodeRunsIt() throws IOException, InterruptedException {
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here, so the function cannot be run; the text checks still apply.");
        String function = CaseCodesFrontendTest.body(read("workbench.js"), "export function scenarioOverride(list, chosenId) {")
                .replaceFirst("^export ", "") + "\n}";
        String script = function + "\nconst list = {scenarios: [{id: 'crd-01-pa-required', number: 1}, {id: 'crd-02-no-auth', number: 2}]};\n"
                + "console.log(JSON.stringify([scenarioOverride(list, ''), scenarioOverride(list, 'crd-01-pa-required'), "
                + "scenarioOverride(list, 'crd-99-gone'), scenarioOverride(null, 'crd-01-pa-required'), scenarioOverride(list, undefined)]));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[null,{\"id\":\"crd-01-pa-required\",\"number\":1},null,null,null]");
    }

    @Test
    void theNoteCopiesNoPartOfTheServersRuleAndSaysWhatIsCertain() throws IOException {
        String workbench = read("workbench.js");
        String override = CaseCodesFrontendTest.body(workbench, "export function scenarioOverride(list, chosenId) {");
        assertThat(override).doesNotContain("sentinelMemberIds").doesNotContain("sentinelNpis").doesNotContain("sentinelMemberId");
        assertThat(CaseCodesFrontendTest.body(workbench, "export function scenarioOverrideNote(scenario, where, letDecide) {"))
                .contains("in place of the '\n            + 'scenario the member ID and NPI would pick")
                .contains("'Let the member ID decide'");
        // Each tab writes the note through the case, and only while the case is on that tab.
        for (String tab : new String[] {"tab-crd.js", "tab-dtr.js", "tab-pas.js"}) {
            String draw = CaseCodesFrontendTest.body(read(tab), "function drawOverrideNote() {");
            assertThat(draw).as(tab).contains("bench.form.contains(app.caseContext.node)").contains("app.caseContext.identifierNote('member'");
        }
    }

    @Test
    void aLookupRefusalThatLandsAtTheFieldIsNotSaidAgainInTheStrip() throws IOException {
        String lookup = read("lookup.js");
        int form = lookup.indexOf("if (answer.kind === 'form') {");
        String branch = lookup.substring(form, lookup.indexOf("return;", form));
        assertThat(branch).contains("const unplaced = spec.refused(fieldErrors, viaButton);")
                .contains("show(null, placedAll ? null : answer.body.title);");
        assertThat(branch.indexOf("spec.refused(")).isLessThan(branch.indexOf("show(null"));
        assertThat(CaseCodesFrontendTest.body(read("case-context.js"), "refused(fieldErrors, viaButton) {")).contains("return markFieldErrors(");
    }
}
