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
    void aHandOverWithAMaskedValueSaysSoInItsLeadWhenNodeRunsIt() throws IOException, InterruptedException {
        String lead = body("workbench.js", "export function handedOnLead(handed, heading, label, send) {");
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here.");
        String script = "const flat = parts => parts.flat(Infinity).filter(p => p !== null && p !== undefined).join('');\n"
                + "const el = (tag, props, ...children) => flat(children) + (tag === 'p' ? '|' : '');\n"
                + "const code = value => value; const button = () => ''; const document = {};\n"
                + lead.replaceFirst("^export ", "") + "\n}\n"
                + "console.log(JSON.stringify([handedOnLead({fromProtocol: 'CRD', fromRunId: 'run-1', masked: [{key: 'questionnaire'}]}, 'H', 'Go', null),"
                + " handedOnLead({fromProtocol: 'CRD', fromRunId: 'run-1', masked: []}, 'H', 'Go', null),"
                + " handedOnLead({fromProtocol: 'DTR', fromRunId: 'run-2', masked: [{key: 'a'}, {key: 'b'}]}, 'H', 'Go', null)]));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[\"Handed on from the CRD run run-1.|What it handed on is in the form, under \\\"H\\\", apart from any value it names "
                + "as not handed on. One value was not handed on because it was masked; the form names it. Choose \\\"Go\\\".|\","
                + "\"Handed on from the CRD run run-1.|What it handed on is in the form, under \\\"H\\\". Choose \\\"Go\\\".|\","
                + "\"Handed on from the DTR run run-2.|What it handed on is in the form, under \\\"H\\\", apart from any value it names "
                + "as not handed on. 2 values were not handed on because they were masked; the form names them. Choose \\\"Go\\\".|\"]");
    }

    @Test
    void afterAHandOverTheFocusIsOnTheLeadThatSaysWhereTheStepCameFrom() throws IOException {
        String lead = body("workbench.js", "export function handedOnLead(handed, heading, label, send) {");
        assertThat(lead).contains("el('p', {class: 'empty-state__lead', tabIndex: -1}")
                .contains("if (lead.isConnected && (!at || at === document.body || at.id === 'main')) {")
                .contains("lead.focus({preventScroll: true});");
    }

    /**
     * When the lead takes the focus it is brought into view by its nearest edge, whatever the size of
     * the window; when the tester is already elsewhere, neither the focus nor the scroll moves. Run
     * with Node, with the element and the page stood in for.
     */
    @Test
    void theLeadThatTakesTheFocusIsBroughtIntoViewWhenNodeRunsIt() throws IOException, InterruptedException {
        String lead = body("workbench.js", "export function handedOnLead(handed, heading, label, send) {");
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here.");
        String script = "const calls = []; let made = null;\n"
                + "const el = (tag, props, ...children) => { const node = {tag, isConnected: true, focus: o => calls.push('focus ' + JSON.stringify(o)),"
                + " scrollIntoView: o => calls.push('scroll ' + JSON.stringify(o))}; if (tag === 'p' && !made) { made = node; } return node; };\n"
                + "const code = value => value; const button = () => ({});\n"
                + "const document = {body: {}, activeElement: null};\n"
                + "const timers = []; const setTimeout = f => timers.push(f);\n"
                + lead.replaceFirst("^export ", "") + "\n}\n"
                + "document.activeElement = {id: 'main'}; handedOnLead({fromProtocol: 'CRD', fromRunId: 'run-1'}, 'H', 'Go', null); timers.shift()();\n"
                + "const arrived = calls.splice(0);\n"
                + "made = null; document.activeElement = {id: 'field-3'}; handedOnLead({fromProtocol: 'CRD', fromRunId: 'run-1'}, 'H', 'Go', null);"
                + " timers.shift()();\n"
                + "console.log(JSON.stringify([arrived, calls]));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[[\"focus {\\\"preventScroll\\\":true}\",\"scroll {\\\"block\\\":\\\"nearest\\\"}\"],[]]");
    }

    @Test
    void whileTheSendingPanelShowsItsSentenceTheStatusLineIsForScreenReadersOnly() throws IOException {
        String call = read("workbench.js");
        int hide = call.indexOf("status.classList.toggle('visually-hidden', waiting !== null);");
        int show = call.indexOf("status.classList.remove('visually-hidden');");
        assertThat(hide).isPositive();
        assertThat(show).isGreaterThan(hide).isLessThan(call.indexOf("clearInterval(waiting.timer);"));
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

    /** The line is drawn, not only written: run under node with a stub ledger answering one run. */
    @Test
    void theNewestRunLineIsDrawnWithItsIdTimeAndOutcomeInAClauseOfItsOwnWhenNodeRunsIt() throws IOException, InterruptedException {
        String since = body("workbench.js", "export function sinceLoaded(protocol, words, labelOf, asked) {");
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here; the text checks still apply.");
        String script = "const asked = [];\n"
                + "const api = {get: url => { asked.push(url); return Promise.resolve({kind: 'ok', body: {runs: [{runId: 'run-1', timestamp: 'T',"
                + " outcome: 'SERVICE_ERROR', memberId: 'S123456789', npi: '1234567893'}]}}); }};\n"
                + "const flat = parts => parts.flat(Infinity).filter(p => p !== null && p !== undefined).map(p => typeof p === 'object' ? p.text : p).join('');\n"
                + "const el = (tag, props, ...children) => ({children, hidden: props && props.hidden, get text() { return flat(this.children); }});\n"
                + "const fill = (node, ...parts) => { node.children = parts; };\n"
                + "const runLink = id => '<' + id + '>'; const when = t => 'at ' + t;\n"
                + since.replaceFirst("^export ", "") + "\n}\n"
                + "const shown = sinceLoaded('subscription', 'Nothing yet.', code => code === 'SERVICE_ERROR' ? 'The service failed' : code, 'subscription');\n"
                + "setTimeout(() => console.log(JSON.stringify({asked, text: shown.text})), 0);";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("{\"asked\":[\"/api/runs?protocol=subscription&limit=1\"],\"text\":\"Nothing yet.The newest subscription run in the "
                + "ledger when this tab was drawn: <run-1>, at T. Outcome: The service failed. It may be from another tab or another tester. Open "
                + "it to read its result.A call sent before a reload still finishes and is recorded; History lists it once it has.\"}");
        assertThat(out).doesNotContain("S123456789").doesNotContain("1234567893");
    }

    @Test
    void theNewestRunIsNamedByItsIdItsTimeAndItsOutcomeAndNothingOfItsCase() throws IOException {
        String since = body("workbench.js", "export function sinceLoaded(protocol, words, labelOf, asked) {");
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
