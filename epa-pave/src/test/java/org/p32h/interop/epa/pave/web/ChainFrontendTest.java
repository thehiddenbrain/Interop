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
 * A case followed through the tabs is one journey. The chain box is on when the page opens; the
 * chain on screen changes only when a result arrives, never on a preview or a refused send; a run
 * for another case starts another chain and the page says so; and Chains leaves out the chains of
 * one step unless asked. The decisions are run by Node where Node is installed.
 */
class ChainFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    private static String run(String script) throws IOException, InterruptedException {
        Path node = CaseCodesFrontendTest.node();
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        return out;
    }

    private static String caseKey() throws IOException {
        return CaseCodesFrontendTest.body(read("chain.js"), "export function caseKey(summary) {").replaceFirst("^export ", "") + "\n}";
    }

    @Test
    void theBarNeverCutsTheMemberTheCodesOrTheWordForWhereAStageStands() throws IOException {
        String chain = Files.readString(Path.of("src/main/resources/static/js/chain.js"), StandardCharsets.UTF_8);
        String parts = CaseCodesFrontendTest.body(chain, "function stateParts(line) {");
        assertThat(parts).contains("el('span', {class: 'chain-stage__word'}, text.slice(0, colon))")
                .contains("el('span', {class: 'visually-hidden'}, ': ' + text.slice(colon + 2))");
        String css = Files.readString(Path.of("src/main/resources/static/css/pave.css"), StandardCharsets.UTF_8);
        assertThat(CaseCodesFrontendTest.body(css, ".chain-bar--line {")).contains("flex-wrap: wrap;");
        assertThat(CaseCodesFrontendTest.body(css, ".chain-bar--line .chain-stage__word {")).contains("flex: 0 0 auto;");
        String caseRule = CaseCodesFrontendTest.body(css, ".chain-bar__case {");
        assertThat(caseRule).doesNotContain("text-overflow").doesNotContain("max-width");
        assertThat(css).doesNotContain(".chain-bar__case, .chain-bar__case code");
    }

    @Test
    void theBoxIsOnTheBarWaitsForAChainAndAskingForTheValueChangesNothingOnScreen() throws IOException {
        String chain = read("chain.js");
        String create = CaseCodesFrontendTest.body(chain, "export function createChain(app, bar) {");
        assertThat(create).contains("el('input', {type: 'checkbox', id: 'chain-follow', checked: true})")
                .contains("'On unless you untick it: a case followed through CRD, DTR and PAS is one journey in History. '");
        String draw = CaseCodesFrontendTest.body(create, "function draw() {");
        assertThat(draw).contains("const wanted = showing && Boolean(chainId);").doesNotContain("chain-bar__next")
                .doesNotContain("Chain: not started.").contains("chain-bar--line").contains("'Paused: runs sent now join no chain.'");
        String value = CaseCodesFrontendTest.body(create, "requestValue() {");
        assertThat(value).contains("caseKey(").contains("chainKey")
                .doesNotContain("app.notice(").doesNotContain("draw()").doesNotContain("chainId = null").doesNotContain("view = null");
        String result = CaseCodesFrontendTest.body(create, "onResult(envelope) {");
        assertThat(result).contains("app.notice(").contains("chainKey = sentKey").contains("'This run started a new chain, because its case is not the case of chain '");
        assertThat(CaseCodesFrontendTest.body(create, "function startNewCase() {")).contains("chainKey = null;");
    }

    @Test
    void theSameCaseTypedAnotherWayHasTheSameKeyWhenNodeRunsIt() throws IOException, InterruptedException {
        Assumptions.assumeTrue(CaseCodesFrontendTest.node() != null, "Node is not installed here; the text checks still apply.");
        String script = caseKey() + "\nconst base = caseKey({memberId: 'S123 456 789', npi: '1234567893', codes: ['E0470', '95810']});\n"
                + "console.log(JSON.stringify(["
                + "caseKey({memberId: 'S123456789', npi: ' 1234567893 ', codes: ['95810', 'E0470']}) === base,"
                + "caseKey({memberId: 'S987654321', npi: '1234567893', codes: ['E0470', '95810']}) === base,"
                + "caseKey({memberId: 'S123456789', npi: '1234567890', codes: ['E0470', '95810']}) === base,"
                + "caseKey({memberId: 'S123456789', npi: '1234567893', codes: ['E0470', '95811']}) === base,"
                + "caseKey({memberId: 'S123456789', npi: '1234567893', codes: ['E0470']}) === base]));";
        assertThat(run(script)).isEqualTo("[true,false,false,false,false]");
    }

    @Test
    void theChainOnScreenChangesOnlyWithAResultAndTheNoticeNamesTheChainClosedWhenNodeRunsIt() throws IOException, InterruptedException {
        Assumptions.assumeTrue(CaseCodesFrontendTest.node() != null, "Node is not installed here; the text checks still apply.");
        String create = CaseCodesFrontendTest.body(read("chain.js"), "export function createChain(app, bar) {")
                .replaceFirst("^export ", "") + "\n}";
        String stubs = String.join("\n",
                "const PROTOCOL_TABS = ['crd', 'dtr', 'pas'];",
                "const node = () => ({append() {}, addEventListener() {}, children: []});",
                "const el = (tag, props) => Object.assign(node(), props && 'checked' in props ? {checked: props.checked} : {});",
                "const fill = () => {}; const clear = () => {}; const button = () => node(); const code = value => value;",
                "const token = value => value; const toneMark = () => null; const errorPanel = () => node();",
                "const api = {get: async () => ({kind: 'ok', body: {}})};",
                "let current = {memberId: 'S123456789', npi: '1234567893', codes: ['E0470']};",
                "const notices = [];",
                "const app = {caseContext: {summary: () => current}, notice: text => notices.push(text), navigate() {}, forgetCarried() {}};");
        String steps = String.join("\n",
                "const chain = createChain(app, {hidden: true});",
                "const seen = [];",
                "seen.push(chain.requestValue());",
                "chain.onResult({chainId: 'chain-1'});",
                "seen.push(chain.requestValue());",
                "current = {memberId: 'S987654321', npi: '1234567893', codes: ['E0470']};",
                "seen.push(chain.requestValue());",
                "seen.push(notices.length);",
                "seen.push(chain.id());",
                "chain.onResult({chainId: 'chain-2'});",
                "seen.push(notices.length);",
                "seen.push(notices[0].includes('chain-1'));",
                "current = {memberId: 'S123456789', npi: '1234567893', codes: ['E0470']};",
                "seen.push(chain.requestValue());",
                "current = {memberId: 'S987654321', npi: '1234567893', codes: ['E0470']};",
                "seen.push(chain.requestValue());",
                "console.log(JSON.stringify(seen));");
        assertThat(run(stubs + "\n" + caseKey() + "\n" + create + "\n" + steps))
                .isEqualTo("[\"new\",\"chain-1\",\"new\",0,\"chain-1\",1,true,\"new\",\"chain-2\"]");
    }

    @Test
    void chainsListsJourneysOfTwoOrMoreStepsUnlessAskedAndRowsLeadWithTheTime() throws IOException {
        String history = read("tab-history.js");
        String view = CaseCodesFrontendTest.body(history, "function chainsView() {");
        assertThat(view).contains("'Show chains of one step'").contains("stage.latest").contains(">= 2")
                .contains("'1 chain of one step is not listed.'").contains("' chains of one step are not listed.'")
                .doesNotContain("'Member, as recorded. '").doesNotContain("Chain id; no member recorded.")
                .doesNotContain("ticked on the CRD tab")
                .contains("'No chain of two or more steps has a run in this range. A chain starts with the first run of a case while '");
    }
}
