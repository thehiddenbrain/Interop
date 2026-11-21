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
        assertThat(read("suites.js")).contains("faultReport({reference: row.faultReference, cause: row.faultCause})");
        String replay = read("history-replay.js");
        assertThat(CaseCodesFrontendTest.body(replay, "function whyCell(entry, vocabulary) {"))
                .contains("faultReport({reference: entry.faultReference, cause: entry.faultCause})");
        assertThat(CaseCodesFrontendTest.body(replay, "function entryDetail(entry, vocabulary) {"))
                .contains("faultReport({reference: entry.faultReference, cause: entry.faultCause})");
        // The error panel of every call made from a screen, and the code list.
        assertThat(read("result.js")).contains("panel.append(faultReport(error));");
        assertThat(read("code-picker.js")).contains("faultReport(answer.body)");
    }
}
