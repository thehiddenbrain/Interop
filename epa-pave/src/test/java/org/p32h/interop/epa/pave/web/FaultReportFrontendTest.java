package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Every place on the screens that shows the reference of a failure of this tool shows the cause
 * with it, in the same words and with one button that copies both. The server log that holds the
 * rest can be on a machine the tester never looks at. The build has no browser, so these read the
 * scripts; the screens were driven in a real browser.
 */
class FaultReportFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void theReportNamesTheCauseInPlainWordsAndCopiesItWithTheReference() throws IOException {
        String report = CaseCodesFrontendTest.body(read("dom.js"), "export function faultReport(error) {");
        assertThat(report).contains("'Cause, as the server log names it: '").contains("'Copy reference and cause'")
                .contains("'Reference ' + error.reference + (error.cause ? '\\nCause, as the server log names it: ' + error.cause : '')");
    }

    @Test
    void aSuiteRowAndAReplayEntryShowTheCauseWithTheReference() throws IOException {
        // These carried the reference only inside a server sentence, with no cause and no copy button.
        assertThat(read("suites.js")).contains("const rowFault = {reference: row.faultReference, cause: row.faultCause};")
                .contains("faultReport(rowFault),");
        String replay = read("history-replay.js");
        assertThat(CaseCodesFrontendTest.body(replay, "function faultOf(entry) {"))
                .contains("return {reference: entry.faultReference, cause: entry.faultCause};");
        for (String view : new String[] {"function whyCell(entry, vocabulary) {", "function entryDetail(entry, vocabulary) {"}) {
            assertThat(CaseCodesFrontendTest.body(replay, view)).contains("faultReport(faultOf(entry)),")
                    .contains("withoutReference(entry.reason, faultOf(entry))");
        }
        // The error panel of every call made from a screen, and the code list.
        assertThat(read("result.js")).contains("panel.append(faultReport(error));");
        assertThat(read("code-picker.js")).contains("faultReport(answer.body)");
    }

    @Test
    void aSuiteThatThisToolEndedShowsTheCauseUnderTheStopSentence() throws IOException {
        // A missing class ends a whole suite, and before this the stop sentence carried the reference alone.
        String suites = read("suites.js");
        assertThat(suites).contains("const stopFault = {reference: run.stopReference, cause: run.stopCause};")
                .contains("el('p', {class: 'suite-head__stop'}, withoutReference(run.stopReason, stopFault))")
                .contains("run.stopReason ? faultReport(stopFault) : null");
    }

    @Test
    void theSentenceAboveTheReportDoesNotRepeatTheReference() throws IOException {
        String strip = CaseCodesFrontendTest.body(read("dom.js"), "export function withoutReference(sentence, fault) {");
        assertThat(strip).contains("sentence.split(' Reference ' + fault.reference + '.').join('')")
                .contains(".split('Report the reference ' + fault.reference + '.')")
                .contains("'Report the reference and the cause below.'").contains("'Report the reference below.'")
                // A sentence with no reference is left exactly as the server wrote it.
                .contains("if (!sentence || !fault || !fault.reference) {");
        assertThat(read("suites.js")).contains("withoutReference(row.reason, rowFault)").contains("faultReport(rowFault)");
    }

    @Test
    void aNotificationThisToolFailedToReadShowsTheCauseWithTheReference() throws IOException {
        // The verdict gave "Report the reference <id>." alone, with no cause and no copy button.
        String notifications = read("pas-notifications.js");
        assertThat(notifications).contains("const fault = {reference: item.faultReference, cause: item.faultCause};")
                .contains("supporting.map(text => el('li', null, withoutReference(text, fault)))")
                .contains("faultReport(fault),");
    }
}
