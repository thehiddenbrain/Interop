package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The order and the folds of a result. What the service answered reads first and stays open; what
 * was not returned and the tool's own internals are one click away, each fold naming what it holds
 * and how many. Nothing is dropped and no failure ever sits inside a fold. The build has no browser,
 * so these read the scripts, and the one decision small enough to stand alone is run by Node.
 */
class ResultLayoutFrontendTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    private static String render() throws IOException {
        return CaseCodesFrontendTest.body(read("result.js"), "export function renderResult(envelope, options) {");
    }

    @Test
    void theResultIsInTheOrderTheSpecificationGivesAndTheErrorIsLastOutsideTheFold() throws IOException {
        String render = render();
        List<String> order = List.of("settings.lead", "runFacts(", "findingsSection(", "determinationSection(", "settings.detail(",
                "additionalFieldsSection(", "result__details", "wireView(", "error.heading");
        int previous = -1;
        for (String part : order) {
            int at = render.indexOf(part);
            assertThat(at).as(part).isGreaterThan(previous);
            previous = at;
        }
        int fold = render.indexOf("result__details");
        assertThat(render.indexOf("typedWithRun(")).isGreaterThan(fold);
        // The fold is closed by its own element, and the error section is appended after it, to the article itself.
        String foldPart = render.substring(fold, render.indexOf("if (envelope.error)"));
        assertThat(foldPart).doesNotContain("errorPanel(").doesNotContain("ledgerLine(").doesNotContain("runFacts(");
        assertThat(render).contains("'Details: the wire view and what was typed with the run'")
                .contains("additionalFieldsSection(envelope.additionalFields || [], null, envelope.wire, {folded: true})");
        assertThat(render).doesNotContain("What went wrong");
    }

    @Test
    void theRunLineCarriesTheTimeAndAFailedRecordStaysAnAlertOfItsOwn() throws IOException {
        String facts = CaseCodesFrontendTest.body(read("result.js"), "function runFacts(envelope, settings) {");
        assertThat(facts).contains("responseTime(envelope.timing)").contains("recorded ? null : ['Ledger', ledgerLine(")
                .doesNotContain("typedWithRun(");
        assertThat(CaseCodesFrontendTest.body(read("result.js"), "function ledgerLine(ledger, runId) {"))
                .contains("'ledger ledger--missing', role: 'alert'");
    }

    @Test
    void theAdditionalFieldsKeepTheirHeadingAndCountOutsideTheFoldTheyCreate() throws IOException {
        String section = CaseCodesFrontendTest.body(read("result.js"), "export function additionalFieldsSection(givenFields, title, wire, options) {");
        int headingAt = section.indexOf("section(heading(3, label, fields.length)");
        int foldAt = section.indexOf("fieldsFold(plain.length + bodies.length)");
        assertThat(headingAt).isGreaterThanOrEqualTo(0);
        assertThat(foldAt).isGreaterThan(headingAt);
        assertThat(section).contains("options && options.folded");
        // The notes about the body and the line that says a body could not be read are caveats on
        // the whole result: they go on the open section, before the fold is made.
        int notesAt = section.indexOf("node.append(el('ul', {class: 'note-list'}, notes.map(");
        int unreadableAt = section.indexOf("It could not be read as JSON");
        assertThat(notesAt).isGreaterThan(headingAt).isLessThan(foldAt);
        assertThat(unreadableAt).isGreaterThan(headingAt).isLessThan(foldAt);
        assertThat(section).doesNotContain("into.append(el('ul', {class: 'note-list'}");
    }

    @Test
    void onlyAnElementNotReturnedIsFoldedAndBothTablesAreBuiltTheSameWay() throws IOException {
        String determination = CaseCodesFrontendTest.body(read("result.js"), "function determinationSection(rows) {");
        assertThat(determination).contains("(row.presence || 'ABSENT') === 'ABSENT' && row.sentWithNoValue !== true")
                .doesNotContain("'NULL'").doesNotContain("'EMPTY'")
                .contains("const grouped = rows.some(row => row.subject);")
                .contains("determinationBody(list, grouped)")
                .contains("'Not returned'")
                .contains("'The response returned none of these elements.'");
        assertThat(read("result.js")).contains("function determinationBody(rows, grouped) {");
    }

    @Test
    void theCrdAnswerItselfIsNeverFolded() throws IOException {
        String crd = read("crd-detail.js");
        // The folds the file creates: elements not returned, the rest of a resource, and the two it
        // had before (how the request was built, what the check judged by). Cards, links,
        // suggestions and system actions are never put inside one.
        Matcher summaries = Pattern.compile("el\\('summary', null, '([^']*)'").matcher(crd);
        List<String> found = new ArrayList<>();
        while (summaries.find()) {
            found.add(summaries.group(1));
        }
        assertThat(found).containsExactlyInAnyOrder("Not returned", "How this tool built the request", "What the conformance check judged by");
        assertThat(crd).contains("fieldsFold(rest.length, additionalFieldsTable(rest, shownWire))");
        for (String part : List.of("cards.map(card => cardBlock(", "actions.map(action => actionBlock(")) {
            int at = crd.indexOf(part);
            assertThat(at).as(part).isGreaterThanOrEqualTo(0);
            assertThat(crd.substring(crd.lastIndexOf('\n', crd.lastIndexOf("node.append(", at)), at)).as(part).doesNotContain("details");
        }
    }

    @Test
    void anUndeclaredRunSaysItInOneLineAndADeclaredOneKeepsTheBox() throws IOException {
        String result = read("result.js");
        assertThat(CaseCodesFrontendTest.body(result, "function undeclared(expectation) {"))
                .contains("expectation.result === 'unverified' && !expectation.expected && !expectation.expectedLabel");
        assertThat(render()).contains("quiet ? null : testResult(envelope.expectation)")
                .contains("el('p', {class: 'result__unverified'}, envelope.expectation.explanation)");
    }

    @Test
    void findingsAreAllCountedAndAllDrawnWhetherOrNotTheyAreExpectedOnMock() throws IOException {
        String findings = CaseCodesFrontendTest.body(read("result.js"), "function findingsSection(envelope) {");
        assertThat(findings).contains("heading(3, 'Findings', findings.length)").doesNotContain("shown.length)")
                .contains("shown.map(finding)").contains("onMock.map(finding)");
        int none = findings.indexOf("'None were raised on this run.'");
        int apart = findings.indexOf("'None apart from the ones expected on the mock profile, below.'");
        assertThat(findings.substring(findings.lastIndexOf("if", none), none)).contains("onMock.length === 0");
        assertThat(findings.substring(findings.lastIndexOf("else", apart), apart)).doesNotContain("if");
        String result = read("result.js");
        assertThat(result).doesNotContain("Both sides here are").doesNotContain("Upload and activate an environment");
    }

    @Test
    void theAddressCalledIsShownOnlyForACallThatLeftAndOnlyWithoutItsQuery() throws IOException {
        String result = read("result.js");
        String strip = CaseCodesFrontendTest.body(result, "function verdictStrip(envelope) {");
        assertThat(strip).contains("calledAddress(envelope, answeredBy)").doesNotContain("request.url");
        String called = CaseCodesFrontendTest.body(result, "function calledAddress(envelope, answeredBy) {");
        assertThat(called).contains("answeredBy === 'SERVICE' || (!envelope.answeredBy && envelope.profile === 'live')")
                .contains("kind !== 'UNREACHABLE' && kind !== 'TIMEOUT'").contains("code(shown)")
                .contains("const shown = addressOnly(url);");
        // The url reaches the strip only through addressOnly: it is read once, and printed only as cut.
        assertThat(called.split("envelope.wire.request.url", -1)).hasSize(3);
        assertThat(called).doesNotContain("code(url)");
        // Before the foot and after the supporting lines.
        assertThat(strip.indexOf("calledAddress(")).isGreaterThan(strip.indexOf("verdict__supporting"))
                .isLessThan(strip.indexOf("verdict__foot"));
    }

    @Test
    void addressOnlyKeepsSchemeHostPortAndPathWhenNodeRunsIt() throws IOException, InterruptedException {
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here, so the function cannot be run; the text checks still apply.");
        String function = CaseCodesFrontendTest.body(read("result.js"), "export function addressOnly(url) {")
                .replaceFirst("^export ", "") + "\n}";
        String script = function + "\nconsole.log(JSON.stringify(['https://h.example.invalid/fhir/Claim?patient=X1#a', "
                + "'https://h.example.invalid:8443/cds-services/order-sign', 'http://127.0.0.1:9/cds#x=1'].map(addressOnly)));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[\"https://h.example.invalid/fhir/Claim\",\"https://h.example.invalid:8443/cds-services/order-sign\","
                + "\"http://127.0.0.1:9/cds\"]");
    }

    @Test
    void theMockGroupingHoldsExactlyForAnIncompleteRequestOnMockWithNoEnvironmentWhenNodeRunsIt() throws IOException, InterruptedException {
        Path node = CaseCodesFrontendTest.node();
        Assumptions.assumeTrue(node != null, "Node is not installed here, so the predicate cannot be run; the text checks still apply.");
        String function = CaseCodesFrontendTest.body(read("result.js"), "export function expectedOnMock(envelope, item) {")
                .replaceFirst("^export ", "") + "\n}";
        String cases = String.join(",\n",
                "[{profile: 'mock'}, {kind: 'REQUEST_INCOMPLETE'}]",
                "[{profile: 'mock', environment: {active: false}}, {kind: 'REQUEST_INCOMPLETE'}]",
                "[{profile: 'live'}, {kind: 'REQUEST_INCOMPLETE'}]",
                "[{profile: 'live'}, {kind: 'CODE_LIST_DISAGREES_ON_VENDOR'}]",
                "[{profile: 'mock', environment: {active: true}}, {kind: 'REQUEST_INCOMPLETE'}]",
                "[{profile: 'mock'}, {kind: 'CODE_LIST_DISAGREES_ON_VENDOR'}]",
                "[{profile: 'mock'}, {kind: 'CODE_LIST_DISAGREES_ON_AUTH'}]",
                "[null, {kind: 'REQUEST_INCOMPLETE'}]");
        String script = function + "\nconsole.log(JSON.stringify([" + cases + "].map(pair => expectedOnMock(pair[0], pair[1]))));";
        Process process = new ProcessBuilder(node.toString(), "-e", script).redirectErrorStream(true).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("node finished").isTrue();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(process.exitValue()).as(out).isZero();
        assertThat(out).isEqualTo("[true,true,false,false,false,false,false,false]");
    }

    @Test
    void onDtrTheQuestionsLeadAndTheFetchButtonStepsBackWhileTheyAreOnScreen() throws IOException {
        String dtr = read("tab-dtr.js");
        assertThat(CaseCodesFrontendTest.body(dtr, "function show(envelope) {")).contains("lead: session ? session.node : null")
                .contains("detailTitle: 'DTR response detail'");
        assertThat(CaseCodesFrontendTest.body(dtr, "async function fetchPackage() {"))
                .contains("controls.send.classList.toggle('button--primary', !session)")
                .contains("controls.send.classList.toggle('button--quiet', Boolean(session))");
        assertThat(read("dtr-questionnaire.js")).doesNotContain("The whole result is above.");
        // The words of a button of the tester's own are changed by text only, so the class set here stays.
        assertThat(read("own-request.js")).doesNotContain("className");
    }
}
