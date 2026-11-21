package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The first screen of a tab keeps the words needed to act; what only explains is one click away
 * under "About this step". The server marks the one scenario note that only explains, so no script
 * tells notes apart by their words, and the notes that report something wrong stay beside their row.
 */
class AboutThisStepFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void theWarningsStayBesideTheRowAndOnlyTheDefaultNoteGoesInAbout() throws IOException {
        String workbench = read("workbench.js");
        String label = CaseCodesFrontendTest.body(workbench, "export function scenarioLabel(scenario) {");
        assertThat(label).contains("scenario.notes").doesNotContain("defaultNote");
        String about = CaseCodesFrontendTest.body(workbench, "export function scenarioDefaultNotes(list) {");
        assertThat(about).contains("scenario.defaultNote").doesNotContain("scenario.notes").doesNotContain("problems");
        assertThat(CaseCodesFrontendTest.body(workbench, "export function scenarioListNotes(list) {")).contains("scenario.defaultNote");
        // The words above the table say what to do, and "sentinel" is not one of them.
        for (String tab : List.of("tab-crd.js", "tab-dtr.js", "tab-pas.js")) {
            String empty = CaseCodesFrontendTest.body(read(tab), "function drawEmptyState() {");
            assertThat(empty).as(tab).contains("'On mock, each member ID picks the answer. Pick a case:'").doesNotContain("sentinel")
                    .contains("aboutStep(").contains("scenarioDefaultNotes(list)").contains("scenarioListNotes(list)");
        }
    }

    @Test
    void theStatementThatWithholdsTheFormLeadsWithAPlainSentenceAndKeepsTheServersReason() throws IOException {
        String readiness = CaseCodesFrontendTest.body(read("workbench.js"), "function drawReadiness() {");
        assertThat(readiness).contains("first.unavailable").contains("'Open the Config tab'")
                .contains("Upload the vendor\\'s Postman environment and collection on the Config tab if you have not.")
                .contains("'What the tool says is missing'")
                .doesNotContain("No environment is active and no collection").doesNotContain("The active environment has no request or address");
    }

    @Test
    void theExplanationsMovedAreKeptWordForWordBehindAboutThisStep() throws IOException {
        assertThat(read("tab-crd.js")).contains("'Sent unless unticked.'")
                .contains("'The answer appears here: what the service said, whether it matched what you expected, and what to do next.'")
                .contains("the Patient, the Coverage and the provider go in the request as prefetch. ");
        assertThat(read("code-picker.js")).contains("'Type a code or words of its description; Enter adds.'");
        assertThat(read("history-run.js")).contains("aboutStep(el('p', {class: 'small quiet'}, 'The step this run named");
        assertThat(read("history-runs.js")).contains("aboutStep(utcNote)").contains("aboutStep(el('p', {class: 'history-about small'}");
        assertThat(read("tab-history.js")).contains("aboutStep(utcNote)");
        assertThat(CaseCodesFrontendTest.body(read("dtr-detail.js"), "export function notApplied(q) {"))
                .contains("list.map(line => el('li', null, line))").contains("aboutStep(");
        assertThat(CaseCodesFrontendTest.body(read("dom.js"), "export function aboutStep(...content) {"))
                .contains("'fold about-step'").contains("'About this step'");
    }
}
