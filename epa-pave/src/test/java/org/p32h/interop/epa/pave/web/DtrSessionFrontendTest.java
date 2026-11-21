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
 * On the DTR tab the questions come right under the verdict, and a step lands on its verdict, or
 * on the question an adaptive step just asked. The build has no browser, so these read the
 * scripts, and the one sentence that counts rules is run by Node where Node is installed; the
 * offsets themselves were measured in a real browser at 1440 by 900 and 1024 by 768.
 */
class DtrSessionFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void theQuestionnaireHeadKeepsItsTitleAndSaysTheRulesNotAppliedInOneLine() throws IOException {
        String view = CaseCodesFrontendTest.body(read("dtr-questionnaire.js"), "function questionnaireView(entry) {");
        assertThat(view).contains("notAppliedLine(entry.view)").doesNotContain("notApplied(entry.view)")
                .doesNotContain("entry.view.canonical").doesNotContain("kindBasis");
    }

    @Test
    void nothingStandsBetweenTheVerdictAndTheQuestionsButTheTitle() throws IOException {
        String render = CaseCodesFrontendTest.body(read("dtr-questionnaire.js"), "function render() {");
        int questions = render.indexOf("questionnaireView(entry)");
        assertThat(questions).isPositive();
        String before = render.substring(0, questions);
        assertThat(before).doesNotContain("'Answering the questionnaire").doesNotContain("class: 'dtr-honest'");
        assertThat(render.indexOf("aboutStep(")).isGreaterThan(questions);
        assertThat(read("dtr-questionnaire.js")).doesNotContain("'Answering the questionnaire'");
    }

    @Test
    void theLineOfWhatIsStillToDoIsLeftOutWhenTheButtonIsRightUnderTheQuestions() throws IOException {
        String source = read("dtr-questionnaire.js");
        String line = CaseCodesFrontendTest.body(source, "function nextStepLine() {");
        int branch = line.indexOf("if (step.action === 'SUBMIT_RESPONSE' || step.action === 'ANSWER_NEXT_QUESTION') {");
        assertThat(branch).isPositive();
        int returnsNull = line.indexOf("return null;", branch);
        assertThat(returnsNull).isGreaterThan(branch).isLessThan(line.indexOf("'Still to do, as the latest result says: '"));
        assertThat(source).doesNotContain("The button is below the questions.");
    }

    /**
     * The refusal of a step for a mask alone is told apart by the step the server gives it, and the
     * screen then offers that step neither as "choose it again" nor in the result before it. Run
     * with Node on the refusal as the server words it, and on the usual refusal.
     */
    @Test
    void aRefusalForAMaskAloneIsToldApartFromTheServersOwnWordsWhenNodeRunsIt() throws IOException, InterruptedException {
        java.nio.file.Path node = CaseCodesFrontendTest.node();
        org.junit.jupiter.api.Assumptions.assumeTrue(node != null, "Node is not installed here.");
        String function = CaseCodesFrontendTest.body(read("tab-dtr.js"), "export function refusedForAMask(text) {").replaceFirst("^export ", "") + "\n}";
        String masked = org.p32h.interop.epa.pave.service.run.RunInputs.MASKED_TITLE + " "
                + org.p32h.interop.epa.pave.service.run.RunInputs.MASKED_ACTION + " questionnaire: The questionnaire was given masked.";
        String usual = "The form has 1 problem. Nothing was sent. Correct the marked field and send again. provider.npi: Enter the NPI.";
        String script = function + "\nconsole.log(JSON.stringify([" + org.p32h.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(masked)
                + ", " + org.p32h.interop.epa.pave.model.JsonSafe.mapper().writeValueAsString(usual) + ", '', null].map(refusedForAMask)));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[true,false,false,false]");
    }

    @Test
    void aStepRefusedForAMaskIsNotToldToSendTheSameStepAgainAndTheJudgementIsThisTools() throws IOException {
        String again = CaseCodesFrontendTest.body(read("tab-dtr.js"), "function sayAgain(slot, label) {");
        assertThat(again).contains("const forAMask = refusedForAMask(bench.problem.textContent);")
                .contains("document.querySelector('#panel-dtr .readout__result .nextstep')").contains("earlier.replaceChildren(el('p', {class: 'nextstep__reason'}, 'Not offered now: the step it named was refused because a value '");
        String source = read("dtr-questionnaire.js");
        assertThat(source).contains("'This tool judged the response complete.'").contains("'This tool did not judge the response complete.'")
                .doesNotContain("The server judged").doesNotContain("The server did not judge");
    }

    @Test
    void afterAPlayedLoopFaultSendingAgainIsWhatARealServiceWouldCallFor() throws IOException {
        String source = read("dtr-questionnaire.js");
        assertThat(source).contains("played: envelope.answeredBy === 'SCENARIO'};");
        assertThat(CaseCodesFrontendTest.body(source, "function stepState(entry) {"))
                .contains("entry.lastFailed.played ? 'Had this been a real service, \"' + buttonName.loop() + '\" would send the same step again.'");
    }

    @Test
    void aStepThisToolCouldNotSendIsNotCountedAsSentToTheService() throws IOException {
        String source = read("dtr-questionnaire.js");
        assertThat(CaseCodesFrontendTest.body(source, "function applyResult(entry, kind, envelope) {"))
                .contains("notSent: envelope.answeredBy === 'NOT_SENT',");
        String state = CaseCodesFrontendTest.body(source, "function stepState(entry) {");
        assertThat(state).contains("entry.steps.filter(step => step.kind === 'loop' && !step.notSent).length")
                .contains("'No step has reached the service yet.'").contains("' was not sent'");
        assertThat(CaseCodesFrontendTest.body(source, "function stepList(entry) {")).contains("'The steps of this questionnaire'")
                .contains("step.notSent ? el('strong', null, ', not sent') : null").doesNotContain("The steps sent for this questionnaire");
    }

    @Test
    void theSessionsCopyOfTheNextStepButtonIsQuietBecauseTheResultHoldsThePrimaryOne() throws IOException {
        String source = read("dtr-questionnaire.js");
        assertThat(CaseCodesFrontendTest.body(source, "function nextStepLine() {"))
                .contains("button(control.label, control.run, {class: 'button--quiet'})").doesNotContain("button--primary");
        assertThat(CaseCodesFrontendTest.body(source, "function withdrawFromResult() {"))
                .contains("button(control.label, control.run, {class: 'button--primary'})");
    }

    @Test
    void aStepScrollsToItsVerdictAndAnAdaptiveStepFocusesTheQuestionItAsked() throws IOException {
        String step = CaseCodesFrontendTest.body(read("tab-dtr.js"), "async function sendStep(entry, kind) {");
        assertThat(step).doesNotContain("session.node.scrollIntoView").contains("closest('.result')")
                .contains("'NEXT_QUESTION'").contains("session.firstUnanswered()")
                .contains("preventScroll: false").contains("preventScroll: true");
        assertThat(read("dtr-questionnaire.js")).contains("        firstUnanswered,\n");
    }

    @Test
    void theOneLineReadsTheRulesTheServerListedAndWordsOneRuleApartFromSeveral() throws IOException, InterruptedException {
        String detail = read("dtr-detail.js");
        String body = CaseCodesFrontendTest.body(detail, "export function notAppliedLine(q) {");
        assertThat(body).contains("q.notApplied");
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here, so the sentence cannot be run; the text checks above still apply.");
        String script = "const el = (tag, props, ...children) => children.flat(Infinity).filter(c => c !== null && c !== undefined).join('');\n"
                + body.replaceFirst("^export ", "") + "\n}\n"
                + "console.log(JSON.stringify([notAppliedLine({notApplied: ['a']}), notAppliedLine({notApplied: ['a', 'b']}),"
                + " notAppliedLine({notApplied: []}), notAppliedLine({})]));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        String[] parts = out.substring(2, out.length() - 2).split("\",\"");
        assertThat(parts[0]).contains("A blank where it would put a value").doesNotContain("one of them").contains("Not applied by this tool: ");
        assertThat(parts[1]).contains("2 rules").contains("one of them");
        assertThat(out).endsWith(",null,null]");
    }
}
