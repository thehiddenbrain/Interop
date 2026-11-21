package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The right half of a protocol tab says what is true: that a step was handed on, that a call is
 * out and for how long, and, after a reload, that nothing was sent since the page was loaded, with
 * the newest run of the protocol in the ledger. The build has no browser, so these read the
 * scripts; each state was driven in a real browser on mock and on live against a stand-in.
 */
class EmptyStateFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    private static String body(String file, String opening) throws IOException {
        return CaseCodesFrontendTest.body(read(file), opening);
    }

    @Test
    void noTabSaysNothingWasSentYetAndEachSaysSinceThePageWasLoaded() throws IOException {
        for (Map.Entry<String, String> tab : Map.of("tab-crd.js", "CRD", "tab-dtr.js", "DTR", "tab-pas.js", "PAS").entrySet()) {
            String source = read(tab.getKey());
            assertThat(source).as(tab.getKey()).doesNotContain("from this tab yet.'").contains("sinceLoaded('" + tab.getValue() + "'");
        }
    }

    @Test
    void inOneColumnEveryNewResultIsBroughtToTheTopOfTheWindow() throws IOException {
        String show = body("workbench.js", "showResult(envelope, options) {");
        int scroll = show.indexOf("shown.scrollIntoView({block: 'start'});");
        assertThat(scroll).isPositive();
        // Only where the page scrolls: a readout that scrolls on its own is put at its top instead.
        assertThat(show.lastIndexOf("if (getComputedStyle(readout).overflowY === 'visible') {", scroll)).isPositive();
        assertThat(show.indexOf("readout.scrollTop = 0;")).isLessThan(scroll);
    }

    /**
     * A result replaces the empty state and its case table, so on mock the cases stay under every result, folded, and
     * "Start a new case" opens them. On live the fold is never built: the table fills in a sample.
     */
    @Test
    void onMockTheCasesStayUnderEveryResultAndStartANewCaseOpensThem() throws IOException, InterruptedException {
        String show = body("workbench.js", "showResult(envelope, options) {");
        assertThat(show).contains("const another = typeof settings.anotherCase === 'function' ? settings.anotherCase() : null;")
                .contains("result.append(another);");
        assertThat(read("tab-crd.js")).contains("anotherCase: () => anotherCaseFold(app, vocabulary.scenarios, bench.ownRequestActive(), letTheMemberIdDecide)");
        assertThat(read("tab-dtr.js")).contains("anotherCase: () => anotherCaseFold(app, vocabulary.scenarios, bench.ownRequestActive(), letTheMemberIdDecide)");
        assertThat(read("tab-pas.js")).contains("anotherCaseFold(app, vocabulary.byUse.get(useOf(mode)), bench.ownRequestActive(), letTheMemberIdDecide)");
        String start = CaseCodesFrontendTest.body(read("chain.js"), "function startNewCase() {");
        assertThat(start).contains("details.another-case").contains("cases.open = true;");
        String fold = body("workbench.js", "export function anotherCaseFold(app, list, own, onUse) {");
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here; the text checks above still apply.");
        String script = "const el = (tag, props, ...children) => tag + (props ? '.' + props.class : '');\n"
                + "const scenarioListNotes = () => null; const scenarioTable = () => null;\n"
                + fold.replaceFirst("^export ", "") + "\n}\n"
                + "const list = {available: true, scenarios: [{}, {}]};\n"
                + "console.log(JSON.stringify([anotherCaseFold({isMock: () => true}, list, false), anotherCaseFold({isMock: () => false}, list, false),"
                + " anotherCaseFold({isMock: () => true}, {available: false, scenarios: []}, false), anotherCaseFold({isMock: () => true}, null, false)]));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[\"details.fold another-case\",null,null,null]");
    }

    @Test
    void theNewestRunIsNamedByItsIdItsTimeAndItsOutcomeAndNothingOfItsCase() throws IOException {
        String since = body("workbench.js", "export function sinceLoaded(protocol, words, labelOf) {");
        assertThat(since).contains("run.runId").contains("run.timestamp").contains("labelOf(")
                .doesNotContain("memberId").doesNotContain("npi").doesNotContain("procedureCodes")
                .contains("'&limit=1'");
    }

    @Test
    void aHandedOnStepLeadsWithWhereItCameFromAndFoldsTheCaseTable() throws IOException {
        String dtr = body("tab-dtr.js", "function drawEmptyState() {");
        assertThat(dtr).contains("usableHandover()").contains("handedOnLead(");
        assertThat(dtr.indexOf("'Start another case on mock ('")).isGreaterThan(dtr.indexOf("el('details', {class: 'fold'}"));
        assertThat(dtr.indexOf("el('details', {class: 'fold'}")).isPositive();

        String pas = body("tab-pas.js", "function drawEmptyState() {");
        assertThat(pas).contains("controls.handover.current()").contains("handedOnLead(");
        assertThat(pas.indexOf("'Start another case on mock ('")).isGreaterThan(pas.indexOf("el('details', {class: 'fold'}"));
        assertThat(pas.indexOf("el('details', {class: 'fold'}")).isPositive();

        String lead = body("workbench.js", "export function handedOnLead(handed, heading, label, send) {");
        assertThat(lead).contains("button--quiet").doesNotContain("button--primary");
    }

    @Test
    void theSendingPanelIsAskedForAndIsUndoneOnEveryWayOutOfTheCall() throws IOException {
        String call = body("workbench.js", "async call(send, options) {");
        assertThat(call).contains("options.result").contains("showSending(");
        String afterFinally = call.substring(call.indexOf("} finally {"));
        String inFinally = afterFinally.substring(0, afterFinally.indexOf("if (answer.kind"));
        assertThat(inFinally).contains("clearInterval(").contains("replaceChildren(");
        String sending = body("workbench.js", "function showSending(saying) {");
        assertThat(sending).contains("setInterval(").contains("class: 'sending__elapsed', aria: {hidden: 'true'}");
        assertThat(read("workbench.js")).contains("The read timeout is ").doesNotContain("at most");
    }

    @Test
    void onlyTheCallsThatGoToTheServiceAndDrawAResultAskForIt() throws IOException {
        assertThat(body("tab-crd.js", "async function send() {")).contains("result: true");
        assertThat(body("tab-dtr.js", "async function fetchPackage() {")).contains("result: true");
        assertThat(body("tab-pas.js", "async function sendSubmit() {")).contains("result: true");
        assertThat(body("tab-crd.js", "async function showPreview() {")).doesNotContain("result:");
        assertThat(body("tab-crd.js", "async function discover() {")).doesNotContain("result:");
        assertThat(body("tab-dtr.js", "async function showPackagePreview() {")).doesNotContain("result:");
        assertThat(body("tab-dtr.js", "async function showLoopPreview(entry) {")).doesNotContain("result:");
        assertThat(body("tab-pas.js", "async function showPreview(kind) {")).doesNotContain("result:");
        assertThat(read("config-raw.js")).doesNotContain("result: true");
        // The response step sends nothing to the payer; only the adaptive step asks for the panel.
        assertThat(body("tab-dtr.js", "async function sendStep(entry, kind) {")).contains("result: kind === 'loop'");
    }

    @Test
    void discoveryIsNotHeldUpByTextLeftInTheCodeBox() throws IOException {
        assertThat(body("tab-crd.js", "async function discover() {")).contains("sendsCase: false");
    }
}
