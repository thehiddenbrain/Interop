package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                .contains("supporting.map(text => el('li', null, withoutReference(text, fault)))");
        // A child of the opened notification on a line of its own, drawn whatever else is true:
        // faultReport itself draws nothing when there is no reference.
        assertThat(Pattern.compile("(?m)^ +faultReport\\(fault\\),$").matcher(notifications).results().count()).isEqualTo(1);
    }

    @Test
    void aSuiteRowDrawsItsReportUnconditionallyAndItsStatusLineSendsTheReaderToIt() throws IOException {
        String row = CaseCodesFrontendTest.body(read("suites.js"), "function resultRow(row, compared) {");
        assertThat(Pattern.compile("(?m)^ +faultReport\\(rowFault\\),$").matcher(row).results().count()).isEqualTo(1);
        assertThat(row).contains("withoutReference(row.statusLine, rowFault)").contains("withoutReference(row.reason, rowFault)");
        // Whether a call went out is not known after a failure of this tool, so its time is not "no call".
        assertThat(row).contains(": none(row.faultReference ? 'not known' : 'no call')},");
    }

    /**
     * Runs withoutReference itself, as the browser does, on every shape of sentence the server
     * writes about a failure of this tool. Reading its source cannot tell a function that strips
     * the reference from one that returns the sentence unchanged. The build has no script engine,
     * so this uses Node where it is installed, and says it was skipped where it is not.
     */
    @Test
    void withoutReferenceGivesTheReferenceOnceAndSendsTheReaderToTheReport(@TempDir Path folder) throws Exception {
        String function = CaseCodesFrontendTest.body(read("dom.js"), "export function withoutReference(sentence, fault) {")
                .replaceFirst("^export ", "") + "\n}\n";
        String id = "0f8b1c2a-3d4e-4f50-8a6b-7c8d9e0f1a2b";
        String cases = """
                const id = '%1$s';
                const both = {reference: id, cause: 'java.lang.NoClassDefFoundError'};
                const alone = {reference: id};
                const shown = [
                  withoutReference('This tool failed while running the suite. Reference ' + id + '.', both),
                  withoutReference('This tool failed while running the case. Whether a request left for it is not known, and nothing '
                    + 'can be said about the service from this row. Look for it in History before running it again. Reference ' + id + '.', both),
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
                for (const line of shown) { console.log(JSON.stringify(line === undefined ? null : line)); }
                """.formatted(id);
        Path script = folder.resolve("without-reference.js");
        Files.writeString(script, function + cases, StandardCharsets.UTF_8);
        Process node;
        try {
            node = new ProcessBuilder("node", script.toString()).redirectErrorStream(true).start();
        } catch (IOException e) {
            Assumptions.abort("Node is not installed here, so withoutReference was not run.");
            return;
        }
        assertThat(node.waitFor(60, TimeUnit.SECONDS)).isTrue();
        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(node.exitValue()).as(output).isZero();
        assertThat(output.lines().toList()).containsExactly(
                "\"This tool failed while running the suite.\"",
                "\"This tool failed while running the case. Whether a request left for it is not known, and nothing can be said about "
                        + "the service from this row. Look for it in History before running it again.\"",
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
