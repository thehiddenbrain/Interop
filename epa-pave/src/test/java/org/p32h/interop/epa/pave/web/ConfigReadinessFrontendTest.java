package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The Config tab's first view opens with "Ready to send?". Its Production line and the Production
 * fact below it are one function, so the guardrail state cannot be said two ways, and an operation
 * that goes without signing in never reads a plain "Yes.". The build has no browser, so these read
 * the script.
 */
class ConfigReadinessFrontendTest {

    private static String read() throws IOException {
        return Files.readString(Path.of("src/main/resources/static/js/config-inforce.js"), StandardCharsets.UTF_8);
    }

    @Test
    void theChecklistIsDrawnFirstAndItsProductionLineIsTheSameWordsAsTheProductionFact() throws IOException {
        String script = read();
        assertThat(script).contains("el('div', {class: 'config-view'}, checklist, pointed, start, writes.node, operations, trying.node)");
        String draw = CaseCodesFrontendTest.body(script, "function draw() {");
        assertThat(draw.indexOf("drawChecklist(")).isGreaterThan(0).isLessThan(draw.indexOf("drawPointed("));
        String checklist = CaseCodesFrontendTest.body(script, "function drawChecklist(stored, mode) {");
        String pointed = CaseCodesFrontendTest.body(script, "function drawPointed(stored, mode) {");
        assertThat(checklist).contains("line('Production', false, productionWords(access, environment))").contains("'Ready to send?'");
        assertThat(pointed).contains("['Production', productionWords(access, environment)]").doesNotContain("declared as production");
        // The words live in one place only.
        assertThat(script.split("The active environment is declared as production", -1)).hasSize(2);
        // On mock nothing is sent, so the sign-in and secret lines are not drawn there.
        assertThat(checklist).contains("if (!context.app.isMock()) {").contains("'Mock profile'");
    }

    @Test
    void anOperationThatGoesWithoutSigningInSaysSoAndThePlainYesIsOnlyWhereNothingSuggestsOtherwise() throws IOException {
        String status = CaseCodesFrontendTest.body(read(), "function statusCell(row, readiness) {");
        int problem = status.indexOf("signIn.problem");
        int without = status.indexOf("} else if (row.withoutSignIn) {");
        int plain = status.indexOf("parts.push(el('strong', null, 'Yes.'))");
        assertThat(problem).isPositive();
        assertThat(without).isGreaterThan(problem);
        assertThat(status.indexOf("'Yes, without signing in.'")).isGreaterThan(without).isLessThan(plain);
        assertThat(status.substring(without, plain)).contains("} else {");
        assertThat(status).contains("el('div', {class: 'small'}, row.withoutSignIn)");
    }

    @Test
    void theRuleForWhichRequestIsUsedStaysOnTheRowInsideAFold() throws IOException {
        String source = CaseCodesFrontendTest.body(read(), "function sourceCell(row, target) {");
        assertThat(source).contains("el('summary', null, 'Which request is used')").contains("row.defaultRule)");
    }
}
