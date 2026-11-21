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
 * History opens a run by its id, leaves the runs a replay made out of the list unless asked,
 * replays exactly the runs listed, oldest first, and puts what changed at the top of the report. A
 * note of the tool's own is grey words, not the dashed frame of a value a response left out.
 */
class HistoryFrontendTest {

    private static final Path STATIC = Path.of("src/main/resources/static");

    private static String read(String file) throws IOException {
        return Files.readString(STATIC.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    void aReplaySendsTheRunsListedByIdOldestFirstAndNoDates() throws IOException {
        String runs = read("js/history-runs.js");
        assertThat(runs).contains("'Replay the runs listed'").doesNotContain("Replay the runs in these dates")
                .doesNotContain("A replay takes the dates only").contains("'What the replay of the runs listed found'");
        String replay = CaseCodesFrontendTest.body(runs, "async function runReplay(ids, control) {");
        assertThat(replay).contains("api.postJson('/api/runs/replay', {runIds: ids})").doesNotContain("from:").doesNotContain("to:").doesNotContain("dates");
        String ids = CaseCodesFrontendTest.body(runs, "export function replayIds(listed) {");
        assertThat(ids).contains(".slice().reverse()").contains("!run.replayOfRunId");
        String confirm = CaseCodesFrontendTest.body(runs, "function confirmReplay(listed, opener, redraw) {");
        assertThat(confirm).contains("replayIds(listed)").contains("oldest first")
                .contains("'More than 1,000 runs are listed. One replay sends at most 1,000: narrow the list first.'")
                .contains("tooMany ? null : button('Replay these runs'");
        assertThat(CaseCodesFrontendTest.body(runs, "function drawActions(query, body) {"))
                .contains("' Only the runs listed are sent, not the others that match.'")
                .contains("' again, against the configuration in force now, and says which answers are the same and what changed.'");
    }

    @Test
    void theListLeavesReplaysOutUnlessAskedAndSaysSo() throws IOException {
        String runs = read("js/history-runs.js");
        assertThat(runs).contains("'Show runs made by a replay'").contains("sent.set('replays', 'leave-out')")
                .contains("query.set('replays', 'include')").contains("' Runs made by a replay are left out.'");
    }

    @Test
    void aRunIsOpenedByItsIdAsTypedEncodedAndWithNoPattern() throws IOException {
        String runs = read("js/history-runs.js");
        assertThat(runs).contains("label: 'Open a run by its id'").contains("'Open the run'");
        String open = CaseCodesFrontendTest.body(runs, "async function openById() {");
        assertThat(open).contains("api.get('/api/runs/' + encodeURIComponent(typed))").contains("errorPanel(answer.body)")
                .contains("runAddress(typed)").doesNotContain("RegExp").doesNotContain(".match(").doesNotContain(".test(");
    }

    @Test
    void theReplayReportPutsWhatChangedFirstAndFoldsTheSame() throws IOException {
        String report = read("js/history-replay.js");
        String order = report.substring(report.indexOf("const ORDER = ["), report.indexOf("];", report.indexOf("const ORDER = [")));
        assertThat(order).startsWith("const ORDER = ['changed'").endsWith("'same'");
        assertThat(report).contains("const FOLDED = new Set(['same', 'not-replayable']);")
                .contains("el('details', {class: 'fold history-replay__group'}, el('summary', null, label), body)")
                .contains("const label = statusWords(status) + ' (' + entries.length + ')';");
    }

    @Test
    void aNoteOfTheToolsOwnIsGreyWordsAndAValueNotThereKeepsItsDashedFrame() throws IOException {
        String css = read("css/pave.css");
        String note = css.substring(css.indexOf(".note--none {"), css.indexOf("}", css.indexOf(".note--none {")));
        assertThat(note).doesNotContain("border").contains("var(--ink-soft)");
        String presence = css.substring(css.indexOf(".presence--absent, .presence--none {"), css.indexOf("}", css.indexOf(".presence--absent, .presence--none {")));
        assertThat(presence).contains("dashed");
        assertThat(CaseCodesFrontendTest.body(read("js/history-common.js"), "export function none(text) {"))
                .contains("note--none").doesNotContain("presence--none");
        String result = read("js/result.js");
        for (String line : result.split("\n")) {
            if (line.contains("'nothing declared'") || line.contains("'not measured'")) {
                assertThat(line).as(line).doesNotContain("presence--none").contains("note--none");
            }
        }
        String chain = read("js/chain.js");
        for (String line : chain.split("\n")) {
            if (line.contains("const none = text =>") || line.contains("'not stated'")) {
                assertThat(line).as(line).doesNotContain("presence--none").contains("note--none");
            }
        }
        // The wire view's "none" for a url is what the request carried, so it keeps the frame.
        assertThat(result).contains("['Url', request.url ? code(request.url) : el('span', {class: 'presence presence--none'}, 'none')]");
        String legend = read("js/legend.js");
        assertThat(legend).contains("note--none")
                .contains("'A dashed outline: nothing is there. No answer, nothing returned. The words inside say which.'")
                .doesNotContain("No answer, nothing declared, nothing returned.");
        assertThat(read("css/history.css")).contains(".history-table td.history-when-cell {\n    white-space: nowrap;");
    }

    @Test
    void theReplayIdsAreTheOriginalsListedOldestFirstWhenNodeRunsIt() throws IOException, InterruptedException {
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here; the text checks still apply.");
        String function = CaseCodesFrontendTest.body(read("js/history-runs.js"), "export function replayIds(listed) {")
                .replaceFirst("^export ", "") + "\n}";
        String script = function + "\nconst listed = [{runId: 'c'}, {runId: 'b', replayOfRunId: 'a'}, {runId: 'a'}];"
                + "\nconst ids = replayIds(listed);"
                + "\nconsole.log(JSON.stringify([ids, listed.map(run => run.runId)]));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        // The originals oldest first, and the list on screen left in its own order.
        assertThat(out).isEqualTo("[[\"a\",\"c\"],[\"c\",\"b\",\"a\"]]");
    }
}
