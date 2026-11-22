package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;
import org.p32h.interop.epa.pave.web.StaticSources.Source;

/**
 * Every place on the screens that shows the reference of a failure of this tool shows the cause
 * with it, in the same words, with one button that copies both: the server log that holds the
 * rest can be on a machine the tester never looks at. So the report is drawn in one place,
 * fault.js, and every script that reads a reference draws it through there. The report and the
 * error panel are run with Node where it is installed, against a stand-in for the document,
 * because reading their source cannot tell a report that copies the cause from one that does not.
 */
class FaultReportFrontendTest {

    /** A read of a reference: error.reference, row.faultReference, run.stopReference. */
    private static final Pattern READS_A_REFERENCE = Pattern.compile("\\.\\w*(?:reference|Reference)\\b");
    private static final Pattern IMPORTS_FAULT = Pattern.compile("import\\s*\\{[^}]*}\\s*from\\s*['\"]\\./fault\\.js['\"]");

    @Test
    void everyScriptThatReadsAReferenceDrawsItThroughTheFaultReport() throws IOException {
        List<String> alone = new ArrayList<>();
        for (Source script : StaticSources.sources(".js")) {
            if (script.name().equals("js/fault.js")) {
                continue;
            }
            String code = StaticSources.withoutComments(script.text());
            if (READS_A_REFERENCE.matcher(code).find() && !IMPORTS_FAULT.matcher(code).find()) {
                alone.add(script.name());
            }
        }
        assertThat(alone).as("scripts that read a reference without fault.js").isEmpty();
    }

    @Test
    void theReportIsWordedOnceInFaultJs() throws IOException {
        String fault = StaticSources.script("fault.js");
        assertThat(fault).contains("'Cause, as the server log names it:'").contains("'Copy reference and cause'")
                .contains("'Reference ' + reference + (cause ? '\\nCause, as the server log names it: ' + cause : '')");
        for (Source script : StaticSources.sources(".js")) {
            if (!script.name().equals("js/fault.js")) {
                assertThat(script.text()).as(script.name()).doesNotContain("as the server log names it");
            }
        }
    }

    @Test
    void theRuleSeesAReferenceReadWithoutTheReport() {
        assertThat(READS_A_REFERENCE.matcher("const fault = {reference: row.faultReference, cause: row.faultCause};").find()).isTrue();
        assertThat(READS_A_REFERENCE.matcher("el('p', null, 'Reference ', error.reference)").find()).isTrue();
        assertThat(READS_A_REFERENCE.matcher("const referenceLine = 'Reference';").find()).isFalse();
        assertThat(IMPORTS_FAULT.matcher("import {errorPanel, faultReport} from './fault.js';").find()).isTrue();
        assertThat(IMPORTS_FAULT.matcher("import {el} from './dom.js';").find()).isFalse();
    }

    /** The report and the panel, run: what each draws, what the button copies, and what it says when the browser refuses. */
    @Test
    void theReportCopiesTheReferenceAndTheCauseTogether(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the fault report", """
                import './browser.js';
                import {faultReport, faultText, errorPanel} from './js/fault.js';
                const say = value => console.log(JSON.stringify(value));
                const id = '0f8b1c2a-3d4e-4f50-8a6b-7c8d9e0f1a2b';
                const cause = 'jakarta.servlet.ServletException <- java.lang.NoClassDefFoundError';

                say(faultReport(null, cause));
                say(faultReport('', cause));
                const alone = faultReport(id);
                say(alone.textContent);
                const both = faultReport(id, cause);
                say(both.querySelectorAll('.fault-report__row').map(row => row.textContent));
                const control = both.querySelector('button');
                say(control.textContent);
                await control.click();
                say(standIn.clipboard);
                say(both.querySelector('.copy-status').textContent);
                standIn.clipboardRefuses = true;
                await control.click();
                say(both.querySelector('.copy-status').textContent);
                say(faultText(id));

                const internal = errorPanel({kind: 'INTERNAL', heading: 'A failure of this tool, not of the service',
                    title: 'This tool hit an internal error and could not complete the request.',
                    action: 'Copy the reference and the cause below with the button and report them. The server log holds the rest.',
                    reference: id, cause, fieldErrors: [], issues: []});
                say(internal.querySelector('.verdict__label').textContent);
                say(internal.querySelector('.verdict__headline').textContent);
                say(internal.querySelector('.muted').textContent);
                // One sentence after the first is a second, quieter line, read without a click (I-34).
                say([internal.querySelectorAll('details').filter(each => each.textContent.startsWith('Why')).length,
                  internal.querySelector('.action-rest').textContent]);
                say(Boolean(internal.querySelector('.fault-report')) && internal.querySelector('.fault-report').closest('details') === null);
                say(internal.className);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "null",
                "null",
                "\"Reference0f8b1c2a-3d4e-4f50-8a6b-7c8d9e0f1a2bCopy reference\"",
                "[\"Reference0f8b1c2a-3d4e-4f50-8a6b-7c8d9e0f1a2b\",\"Cause, as the server log names it:jakarta.servlet.ServletException <- "
                        + "java.lang.NoClassDefFoundError\"]",
                "\"Copy reference and cause\"",
                "\"Reference 0f8b1c2a-3d4e-4f50-8a6b-7c8d9e0f1a2b\\nCause, as the server log names it: jakarta.servlet.ServletException <- "
                        + "java.lang.NoClassDefFoundError\"",
                "\"Copied.\"",
                "\"Not copied: select the text and copy it.\"",
                "\"Reference 0f8b1c2a-3d4e-4f50-8a6b-7c8d9e0f1a2b\"",
                "\"A failure of this tool, not of the service\"",
                "\"This tool hit an internal error and could not complete the request.\"",
                "\"Copy the reference and the cause below with the button and report them.\"",
                "[0,\"The server log holds the rest.\"]",
                "true",
                "\"verdict verdict--fault error-panel\"");
    }

    /** What the panel says when there was no answer to read, and that a refused form and the service's issues are never dropped. */
    @Test
    void thePanelSaysWhatCameBackOrThatNothingDid(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the error panel", """
                import './browser.js';
                import {errorPanel, UNREACHABLE_LINE} from './js/fault.js';
                const say = value => console.log(JSON.stringify(value));

                say(errorPanel(null));
                say(errorPanel({kind: 'NOT_JSON', status: 500}).querySelector('.verdict__headline').textContent);
                const lost = errorPanel({kind: 'UNREACHABLE'});
                say([lost.querySelector('.verdict__headline').textContent === UNREACHABLE_LINE, lost.querySelector('.muted').textContent]);
                say(errorPanel({kind: 'PAGE_FAULT'}).textContent);

                const form = {kind: 'VALIDATION', heading: 'A problem with what was submitted', title: 'Two fields cannot be used.',
                    action: 'Correct the marked fields and send again.',
                    fieldErrors: [{field: 'memberId', message: 'Enter a member ID.'}, {field: 'provider.npi', message: 'This NPI fails its check digit.'}],
                    issues: []};
                say(Array.from(errorPanel(form).querySelectorAll('.error-fields li')).map(item => item.textContent));
                say(Array.from(errorPanel(form, {fieldsPlaced: ['memberId']}).querySelectorAll('.error-fields li')).map(item => item.textContent));
                say(errorPanel(form).className);

                const refused = {kind: 'VENDOR_REJECTED', heading: 'The service rejected the request', title: 'The service answered HTTP 400.',
                    httpStatus: 400, fieldErrors: [], issues: [{
                        severity: {presence: 'VALUE', value: 'error'}, code: {presence: 'VALUE', value: 'invalid'},
                        diagnostics: {presence: 'NULL'}, detailsText: {presence: 'ABSENT'}, detailsCodes: [], detailsPresence: 'EMPTY',
                        expression: [{presence: 'VALUE', value: 'Bundle.entry[0]'}], expressionPresence: 'VALUE',
                        location: [], locationPresence: 'ABSENT'}]};
                const panel = errorPanel(refused);
                say(panel.querySelector('.verdict__label').textContent);
                say(Array.from(panel.querySelectorAll('tbody td')).map(cell => cell.textContent));
                say(panel.className);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "null",
                "\"PAVE did not answer as expected (HTTP 500). If PAVE runs from STS, stop it and start it again.\"",
                "[true,\"If a run was on its way, it may or may not have been made: look for it in Audit before sending it again.\"]",
                "\"This page met a fault of its own. Reload the page.\"",
                "[\"memberId Enter a member ID.\",\"provider.npi This NPI fails its check digit.\"]",
                "[\"provider.npi This NPI fails its check digit.\"]",
                "\"verdict verdict--neutral error-panel\"",
                "\"The service rejected the request, HTTP 400\"",
                "[\"error\",\"invalid\",\"returned as null\",\"returned empty\",\"Bundle.entry[0]\",\"not returned\"]",
                "\"verdict verdict--stop error-panel\"");
    }

    /**
     * Runs withoutReference itself, as the browser does, on every shape of sentence the server
     * writes about a failure of this tool. Reading its source cannot tell a function that strips
     * the reference from one that returns the sentence unchanged.
     */
    @Test
    void withoutReferenceGivesTheReferenceOnceAndSendsTheReaderToTheReport(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "withoutReference", """
                import {withoutReference} from './js/fault.js';
                const id = '0f8b1c2a-3d4e-4f50-8a6b-7c8d9e0f1a2b';
                const both = {reference: id, cause: 'java.lang.NoClassDefFoundError'};
                const alone = {reference: id};
                const shown = [
                  withoutReference('This tool failed while running the suite. Reference ' + id + '.', both),
                  withoutReference('This tool failed while running the case. Whether a request left for it is not known, and nothing '
                    + 'can be said about the service from this row. Look for it in Audit before running it again. Reference ' + id + '.', both),
                  withoutReference('This tool failed while reading the answer. A request may have been sent. Report the reference ' + id + '.', both),
                  withoutReference('This tool failed while reading the answer. A request may have been sent. Report the reference ' + id + '.', alone),
                  withoutReference('This is a defect in this tool, not a finding about what was sent. Report the reference ' + id
                    + '. The server log holds the detail under the same reference.', both),
                  withoutReference('Tool failure: this tool failed while running the case, so whether a request left is not known. '
                    + 'The reason gives the reference.', both),
                  withoutReference('Tool failure: this tool failed while running the case, so whether a request left is not known. '
                    + 'The reason gives the reference.', alone),
                  withoutReference('This tool failed while running the suite. Reference ' + id + '.', {}),
                  withoutReference('This tool failed while running the suite. Reference ' + id + '.', {reference: 'another', cause: 'x'}),
                  withoutReference(undefined, both)
                ];
                for (const line of shown) {
                  console.log(JSON.stringify(line === undefined ? null : line));
                }
                """);
        String id = "0f8b1c2a-3d4e-4f50-8a6b-7c8d9e0f1a2b";
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "\"This tool failed while running the suite.\"",
                "\"This tool failed while running the case. Whether a request left for it is not known, and nothing can be said about "
                        + "the service from this row. Look for it in Audit before running it again.\"",
                "\"This tool failed while reading the answer. A request may have been sent. Report the reference and the cause below.\"",
                "\"This tool failed while reading the answer. A request may have been sent. Report the reference below.\"",
                "\"This is a defect in this tool, not a finding about what was sent. Report the reference and the cause below. The "
                        + "server log holds the detail under the same reference.\"",
                "\"Tool failure: this tool failed while running the case, so whether a request left is not known. The reference and "
                        + "the cause are below the reason.\"",
                "\"Tool failure: this tool failed while running the case, so whether a request left is not known. The reference is "
                        + "below the reason.\"",
                // With no reference to show below, or another one, the sentence is left as the server wrote it.
                "\"This tool failed while running the suite. Reference " + id + ".\"",
                "\"This tool failed while running the suite. Reference " + id + ".\"",
                "null");
    }
}
