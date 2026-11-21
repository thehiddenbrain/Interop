package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The form of a protocol tab: the three things a send needs first, the call's own fields never
 * folded, the optional ones in one closed "More options", and a refusal that names each field and
 * opens the fold it sits in. The build has no browser, so these read the scripts; the behaviour was
 * driven in a real browser.
 */
class FormLayoutFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void theCaseOpensWithTheThreeThingsASendNeedsInThatOrder() throws IOException {
        String caseContext = read("case-context.js");
        int required = caseContext.indexOf("{class: 'case-group case-required'}");
        assertThat(required).isGreaterThanOrEqualTo(0);
        String group = caseContext.substring(required, caseContext.indexOf("el('fieldset'", required));
        int member = group.indexOf("member.identifierNode");
        int provider = group.indexOf("provider.identifierNode");
        int codes = group.indexOf("codes.node");
        assertThat(member).isGreaterThan(0);
        assertThat(provider).isGreaterThan(member);
        assertThat(codes).isGreaterThan(provider);
        assertThat(caseContext).contains("'Needed to send'").contains("member.detailNodes").contains("provider.detailNodes");
    }

    @Test
    void aRefusedFieldInAClosedFoldHasTheFoldOpenedAndEveryRefusalNamesItsField() throws IOException {
        String forms = read("forms.js");
        String mark = CaseCodesFrontendTest.body(forms, "export function markFieldErrors(roots, fieldErrors, options) {");
        assertThat(mark).contains("openFoldsAround(field);").contains("placed.push({field, label: fieldLabel(field, problem.field)");
        assertThat(CaseCodesFrontendTest.body(forms, "function openFoldsAround(node) {")).contains("details:not([open])").contains("fold.open = true");
        assertThat(CaseCodesFrontendTest.body(forms, "export function formProblem(error, unplaced, placed) {"))
                .contains("append(node, linesList(lines));");
        assertThat(CaseCodesFrontendTest.body(forms, "function linesList(lines) {")).contains("'form-problem__fields'");
        // Every refusal the workbench draws passes the fields it placed to the summary.
        Matcher calls = Pattern.compile("formProblem\\(([^;]*?)\\);", Pattern.DOTALL).matcher(read("workbench.js"));
        int count = 0;
        while (calls.find()) {
            count++;
            assertThat(calls.group(1)).as(calls.group()).matches("(?s).*, placed\\)*").contains("{placed}");
        }
        assertThat(count).isEqualTo(3);
    }

    @Test
    void theCallsOwnFieldsAreNeverFoldedAndTheOptionalOnesAre() throws IOException {
        String pas = read("tab-pas.js");
        assertThat(pas).contains("bench.beforeOptions.append(controls.sectionSlot);").contains("bench.own.append(controls.common);")
                .doesNotContain("bench.own.append(controls.sectionSlot");
        assertThat(read("tab-dtr.js")).contains("bench.beforeOptions.append(controls.carried);");
        for (String tab : List.of("tab-crd.js", "tab-dtr.js", "tab-pas.js")) {
            assertThat(read(tab)).as(tab).contains("moreOptions: true,");
        }
        String workbench = read("workbench.js");
        assertThat(workbench).contains("el('details', {class: 'fold more-options'}, el('summary', null, 'More options', optionsSaid))")
                .contains("caseSlot, beforeOptions,\n        folded ? [valuesSlot, moreOptions] : [own, valuesSlot]");
        // The Config tab's request sent as written does not ask for the fold, so its fields stay open.
        assertThat(read("config-raw.js")).doesNotContain("moreOptions");
    }

    @Test
    void theSummaryMirrorsTheMarksAndNeverSitsInThePinnedBar() throws IOException {
        String forms = read("forms.js");
        // A field loses its mark in more ways than typing (a lookup, a chosen case, the check on
        // leaving it), and gains one in more ways than a send (the NPI check on leaving the field),
        // so unmark and markFieldErrors both tell the page, and the summary listens for both.
        assertThat(CaseCodesFrontendTest.body(forms, "export function unmark(field) {")).contains("new CustomEvent(UNMARKED, {bubbles: true})");
        assertThat(CaseCodesFrontendTest.body(forms, "export function markFieldErrors(roots, fieldErrors, options) {"))
                .contains("field.dispatchEvent(new CustomEvent(MARKED, {bubbles: true}));");
        // The lines are read from the marks on screen now, with the sentence each field shows now,
        // not dropped one by one from the list the send built: a mark replaced by another sentence
        // used to take its line with it while the field stayed wrong.
        String mirror = CaseCodesFrontendTest.body(forms, "export function mirrorMarks(slot, scope) {");
        assertThat(mirror).contains("scope.querySelectorAll('.field--invalid')").contains("field.querySelectorAll('.field__error')")
                .contains("summary.remove()").contains("'Nothing was sent. Still to correct:'").contains("summary.insertBefore(linesList(lines), notes)");
        // One line a field, in the order of the form, the same at the send and afterwards.
        assertThat(CaseCodesFrontendTest.body(forms, "function linesFor(fields, messageOf) {")).contains("Node.DOCUMENT_POSITION_FOLLOWING");
        assertThat(CaseCodesFrontendTest.body(forms, "export function formProblem(error, unplaced, placed) {"))
                .contains("linesFor(entries.map(entry => entry.field)").contains("listed.set(node, signature(lines));");
        assertThat(CaseCodesFrontendTest.body(forms, "function linesList(lines) {")).contains("button(line.label, () => focusField(line.field)");
        String workbench = read("workbench.js");
        assertThat(workbench).contains("panel.addEventListener(UNMARKED, () => mirrorMarks(problem, panel));")
                .contains("panel.addEventListener(MARKED, () => mirrorMarks(problem, panel));");
        // The PAS sample notification has a summary of its own; its marks are not the form's.
        assertThat(read("pas-notifications.js")).contains("el('div', {class: 'pas-sample', dataset: {summary: 'own'}})");
        // A long summary in the bar that stays in view squeezed the form to half the pane.
        for (String tab : List.of("tab-crd.js", "tab-pas.js")) {
            String text = read(tab);
            assertThat(text).as(tab).contains("bench.scroll.prepend(bench.problem);");
            String actions = text.substring(text.indexOf("bench.actions.append("), text.indexOf(");", text.indexOf("bench.actions.append(")));
            assertThat(actions).as(tab).doesNotContain("bench.problem");
        }
        String dtr = read("tab-dtr.js");
        assertThat(dtr).contains("bench.scroll.prepend(controls.problemHome);");
        String dtrActions = dtr.substring(dtr.indexOf("bench.actions.append("), dtr.indexOf(");", dtr.indexOf("bench.actions.append(")));
        assertThat(dtrActions).doesNotContain("problemHome");
    }

    @Test
    void afterARefusedSendTheSummaryIsScrolledToTheTopOfTheFormAndHasTheFocus() throws IOException {
        String forms = read("forms.js");
        // A marked field scrolled into view pushed the summary above the visible part of the form,
        // so a caller that shows a summary leaves the field alone and the summary takes the focus.
        assertThat(CaseCodesFrontendTest.body(forms, "export function markFieldErrors(roots, fieldErrors, options) {"))
                .contains("if (first && !placed && !(options && options.focus === false)) {");
        assertThat(CaseCodesFrontendTest.body(forms, "export function showFormProblem(slot, node) {"))
                .contains("fill(slot, node);").contains("node.scrollIntoView({block: 'start'});").contains("node.focus({preventScroll: true});");
        assertThat(CaseCodesFrontendTest.body(forms, "export function formProblem(error, unplaced, placed) {"))
                .contains("{class: 'form-problem', role: 'alert', tabIndex: -1}");
        // Every form that shows the summary: CRD, DTR and PAS through the workbench, the PAS
        // subscription id, the CRD comparison, a request of the tester's own.
        for (String file : List.of("workbench.js", "tab-pas.js", "crd-compare.js")) {
            String text = read(file);
            Matcher calls = Pattern.compile("(\\w+)\\(\\s*(?:bench\\.)?problem, formProblem\\(").matcher(text);
            int count = 0;
            while (calls.find()) {
                count++;
                assertThat(calls.group(1)).as(file + ": " + calls.group()).isEqualTo("showFormProblem");
            }
            assertThat(count).as(file).isGreaterThan(0);
            Matcher marks = Pattern.compile("formProblem\\(([^;]*?)\\);", Pattern.DOTALL).matcher(text);
            while (marks.find()) {
                assertThat(marks.group(1)).as(file + ": " + marks.group()).contains("{placed}").matches("(?s).*, placed\\)*");
            }
        }
        assertThat(read("workbench.js")).contains("showFormProblem(problem, said);");
    }

    @Test
    void aRefusalMadeOutsideTheWorkbenchCallClearsTheMarksOfTheLastOneFirst() throws IOException {
        // The summary mirrors every mark in the panel. A refusal made without bench.call, which
        // clears the panel first, kept the marks of an earlier refused send: hidden PAS submit
        // fields, or the procedure codes marked on CRD, came back into the subscription summary
        // as soon as the subscription id was typed.
        String subscription = CaseCodesFrontendTest.body(read("tab-pas.js"), "async function subscriptionCall(kind) {");
        int cleared = subscription.indexOf("clearFieldErrors(panel);");
        assertThat(cleared).isGreaterThan(0).isLessThan(subscription.indexOf("markFieldErrors("));
        assertThat(subscription.indexOf("bench.clearRefusal();")).isGreaterThan(0).isLessThan(subscription.indexOf("showFormProblem("));
        String compare = CaseCodesFrontendTest.body(read("crd-compare.js"), "async function compare(what) {");
        assertThat(compare.indexOf("clearFieldErrors(bench.form);")).isGreaterThan(0).isLessThan(compare.indexOf("markFieldErrors("));
        assertThat(CaseCodesFrontendTest.body(read("workbench.js"), "async call(send, options) {").indexOf("clearFieldErrors(panel);"))
                .isGreaterThan(0);
    }

    @Test
    void theStatusLineOfARefusedSendSpeaksOfThatSendInThePastTense() throws IOException {
        String forms = read("forms.js");
        String saying = CaseCodesFrontendTest.body(forms, "export function refusedSaying(count, what) {");
        assertThat(saying).contains("'The send was refused for ' + problems + ' in the form. Nothing was sent.'")
                .contains("'The comparison was refused for ' + problems + ' in the form. Nothing was compared.'");
        String workbench = read("workbench.js");
        String call = CaseCodesFrontendTest.body(workbench, "async call(send, options) {");
        // "The form has 3 problems" stayed on the status line while the tester corrected them.
        assertThat(call).contains("status.textContent = refusedSaying(counted);").contains("status.textContent = refusedSaying(1);")
                .doesNotContain("status.textContent = nothing.title;");
        assertThat(read("tab-pas.js")).contains("bench.say(refusedSaying(said.fieldErrors.length));").doesNotContain("bench.say(said.title);");
        String compare = read("crd-compare.js");
        assertThat(compare).contains("bench.say(refusedSaying(1, 'comparison'));")
                .contains("bench.say(refusedSaying(answer.body.fieldErrors.length, 'comparison'));").doesNotContain("bench.say(nothing.title);");
    }

    @Test
    void noScreenSentenceSendsTheTesterToTheLeftOrTheRight() throws IOException {
        Pattern quoted = Pattern.compile("'([^'\\n]*)'");
        for (String file : List.of("tab-crd.js", "tab-dtr.js", "tab-pas.js", "pas-notifications.js", "tab-history.js")) {
            for (String line : read(file).split("\n")) {
                if (line.strip().startsWith("//") || line.strip().startsWith("*") || line.strip().startsWith("/*")) {
                    continue;
                }
                Matcher text = quoted.matcher(line);
                while (text.find()) {
                    assertThat(text.group(1).toLowerCase()).as(file + ": " + line.strip()).doesNotContain("on the left").doesNotContain("on the right");
                }
            }
        }
    }
}
