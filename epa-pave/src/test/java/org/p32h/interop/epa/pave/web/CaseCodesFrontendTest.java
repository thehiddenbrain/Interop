package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * What the page sends as the procedure codes of the case is what the chips show, and nothing else.
 * The build has no script engine of its own, so most of these read the scripts that decide it, and
 * the one decision small enough to stand alone is run by Node where Node is installed. The
 * behaviour itself was driven in the real browser. Each check fails on the code as it was before
 * the fix.
 */
class CaseCodesFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    /** The body of the first function or method named, up to its closing brace at the same indent. */
    static String body(String source, String opening) {
        int start = source.indexOf(opening);
        assertThat(start).as(opening).isGreaterThanOrEqualTo(0);
        int lineStart = source.lastIndexOf('\n', start) + 1;
        String indent = source.substring(lineStart, start);
        int end = source.indexOf("\n" + indent + "}", start);
        assertThat(end).as("end of " + opening).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    void textLeftInTheCodeBoxIsNeverMovedIntoTheCaseWhenTheRequestIsRead() throws IOException {
        String values = body(read("code-picker.js"), "values() {");
        assertThat(values).doesNotContain("commitTyped").doesNotContain("input.value");
    }

    @Test
    void theSameCodeIsRefusedByWhatEachEntryHoldsOnEveryWayIn() throws IOException {
        String picker = read("code-picker.js");
        String addEntry = body(picker, "function addEntry(");
        // An entry keeps its code under input. Comparing entry.code compared undefined and let every repeat in.
        assertThat(addEntry).contains("existing.input.code === String(candidate.code)").doesNotContain("existing.code ===");
        // Every way a code gets into the case goes through addEntry: no other place appends to the entries.
        Matcher appends = Pattern.compile("entries\\s*=\\s*entries\\.concat").matcher(picker);
        int count = 0;
        while (appends.find()) {
            count++;
        }
        assertThat(count).isEqualTo(1);
        assertThat(addEntry).contains("entries = entries.concat");
    }

    @Test
    void aSendWithTextLeftInTheCodeBoxIsRefusedBeforeAnythingIsAsked() throws IOException {
        String call = body(read("workbench.js"), "async call(send, options) {");
        int refusal = call.indexOf("const typed = textLeftToRefuse(codeBoxFacts(options));\n            if (typed !== '') {");
        int sending = call.indexOf("send()");
        assertThat(refusal).isGreaterThanOrEqualTo(0);
        assertThat(sending).isGreaterThan(refusal);
        assertThat(call.substring(refusal, sending)).contains("return null;");
        assertThat(call).contains("was typed in the procedure code box and not added to the case.")
                .contains("Choose Add code to add it, or clear the box, then send again.")
                .contains("field: 'procedureCodes'");
    }

    /**
     * The decision itself, run. The build has no script engine of its own, so the one function that
     * decides whether a send is refused for text left in the code box is taken out of workbench.js as
     * it stands and run by Node, where Node is installed, against every case the screen can be in.
     * It fails when the refusal is removed, and when it is applied to a call that carries nothing of
     * the case. What it cannot see: how the workbench reads those facts from the page (whether the
     * box is found and whether the case is hidden), and that nothing then leaves the browser. Those
     * were driven in the real browser.
     */
    @Test
    void theRefusalHoldsExactlyWhenTheCallSendsTheCase() throws IOException, InterruptedException {
        Path node = node();
        Assumptions.assumeTrue(node != null, "Node is not installed here, so the decision cannot be run; the text checks above still apply.");
        String decide = body(read("workbench.js"), "export function textLeftToRefuse(facts) {").replaceFirst("^export ", "") + "\n}";
        String cases = String.join(",\n",
                // A protocol tab with its case shown, and a call that sends it: refused, and the text is named trimmed.
                "{typed: 'MRI', caseShown: true, ownRequest: false}",
                "{typed: '  MRI  ', caseShown: true, ownRequest: false, sendsCase: true}",
                // Nothing typed, or only spaces: nothing to refuse for.
                "{typed: '', caseShown: true, ownRequest: false}",
                "{typed: '   ', caseShown: true, ownRequest: false}",
                // A subscription call: the tab says it carries nothing of the case.
                "{typed: 'MRI', caseShown: true, ownRequest: false, sendsCase: false}",
                // The case is put out of the way (Subscriptions or Notifications chosen), or is on another tab.
                "{typed: 'MRI', caseShown: false, ownRequest: false}",
                "{typed: 'MRI', caseShown: false, ownRequest: false, sendsCase: true}",
                // A request of the tester's own carries nothing of the case.
                "{typed: 'MRI', caseShown: true, ownRequest: true}",
                // No box found.
                "{typed: undefined, caseShown: true, ownRequest: false}");
        String script = decide + "\nconsole.log(JSON.stringify([" + cases + "].map(textLeftToRefuse)));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[\"MRI\",\"MRI\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"]");
    }

    @Test
    void theCallsOfThePasTabThatCarryNothingOfTheCaseSaySo() throws IOException {
        String pas = read("tab-pas.js");
        assertThat(body(pas, "async function subscriptionCall(kind) {")).contains("sendsCase: false");
        assertThat(body(pas, "async function showPreview(kind) {")).contains("sendsCase: kind === 'submit' || kind === 'inquire'");
    }

    /** The Node executable on the path, or null when there is none. */
    static Path node() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            Path candidate = Path.of(dir, "node");
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
