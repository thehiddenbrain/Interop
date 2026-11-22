package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * What the release candidate's checks found in the Workbench's words and places, run with Node and
 * the stand-in for the document: the readiness line keeps the server's whole sentence and links
 * Open Configuration once; Open Configuration after a result leads to the fold that needs the
 * change; a lookup not set up says only "Type the details."; a restart is told apart from a change
 * made in another window; a refusal met while the files changed on disk says so; and a service's
 * failure is said once, inside its result card.
 */
class WorkbenchWordsFrontendTest {

    private static final String PRELUDE = """
            import './browser.js';
            import fs from 'node:fs';
            fs.writeFileSync('./js/app.js', [
                'export const app = {',
                '  mode: () => globalThis.fake.mode, isMock: () => false,',
                '  epoch: () => 7, on: () => () => true, go: hash => globalThis.fake.went.push(hash), notice() {}',
                '};'].join(String.fromCharCode(10)));
            globalThis.fake = {mode: {mode: 'live', epoch: 7, environment: {name: 'Sample Sandbox', kind: 'sandbox', active: true}}, went: []};
            globalThis.fetch = async () => ({ok: true, status: 200, text: async () => '{}'});
            const say = value => console.log(JSON.stringify(value));
            """;

    @Test
    void readinessLinksOnceAndOpenConfigurationLeadsToTheFoldThatNeedsTheChange(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the readiness line and the way to Configuration", PRELUDE + """
                const {readinessNode, configurationPlace} = await import('./js/workbench.js');
                const none = readinessNode('No environment is active. Open Configuration and make one active.');
                say([none.textContent, none.querySelectorAll('a').length, none.querySelector('a').textContent]);
                const gap = readinessNode('The Coverage check address needs a value for crdOrderSignServiceId. Add it under Values of the environment in Configuration.');
                say([gap.textContent, gap.querySelectorAll('a').length]);
                const env = fake.mode;
                say([
                  configurationPlace({verdict: {outcomeCode: 'NOT_SIGNED_IN'}}, env),
                  configurationPlace({verdict: {}, error: {kind: 'VENDOR_REJECTED', httpStatus: 401}}, env),
                  configurationPlace({verdict: {}, error: {kind: 'VENDOR_REJECTED', httpStatus: 404}}, env),
                  configurationPlace({verdict: {outcomeCode: 'UNREACHABLE'}, error: {kind: 'UNREACHABLE'}}, env),
                  configurationPlace({verdict: {}, error: {kind: 'VENDOR_ERROR', httpStatus: 500}}, env),
                  configurationPlace({verdict: {outcomeCode: 'NOT_SIGNED_IN'}}, {mode: 'live'}),
                  configurationPlace(undefined, null)]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"No environment is active. Open Configuration and make one active.\",1,\"Open Configuration\"]",
                "[\"The Coverage check address needs a value for crdOrderSignServiceId. Add it under Values of the environment in "
                        + "Configuration. Open Configuration\",1]",
                "[\"#configuration/environments/Sample%20Sandbox/sign-in\",\"#configuration/environments/Sample%20Sandbox/sign-in\","
                        + "\"#configuration/environments/Sample%20Sandbox/addresses\",\"#configuration/environments/Sample%20Sandbox/addresses\","
                        + "\"#configuration/environments\",\"#configuration/environments\",\"#configuration/environments\"]");
    }

    @Test
    void aLookupNotSetUpARestartAndChangedFilesAreSaidInPlainWords(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "lookup line, restart and changed files", PRELUDE + """
                const {lookupLine} = await import('./js/wb-lookup.js');
                const {restarted} = await import('./js/api.js');
                const {errorPanel, setFilesChanged} = await import('./js/fault.js');
                const line = lookupLine({status: 'DISABLED', statusLabel: 'No lookup is configured',
                  message: 'Member lookup is not configured, so there is no url to ask: set lookup.member.url, and until then type the member details by hand.'});
                say(line.textContent);
                say(lookupLine({status: 'NOT_FOUND', message: 'No member has this ID. Check it.'}).textContent);
                say([restarted({startedAt: '2026-11-22T12:00:00Z'}, {startedAt: '2026-11-22T12:05:00Z'}),
                  restarted({startedAt: '2026-11-22T12:00:00Z'}, {startedAt: '2026-11-22T12:00:00Z'}),
                  restarted({}, {startedAt: '2026-11-22T12:05:00Z'}), restarted(null, null)]);
                const refusal = {kind: 'VALIDATION', title: 'The form has 1 problem. Nothing was sent.', action: 'Correct the marked field and send again.'};
                say(Boolean(errorPanel(refusal).querySelector('.files-changed-note')));
                setFilesChanged(true);
                say(errorPanel(refusal).querySelector('.files-changed-note').textContent);
                setFilesChanged(false);
                say(Boolean(errorPanel(refusal).querySelector('.files-changed-note')));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "\"Type the details.\"",
                "\"No member has this ID.\"",
                "[true,false,false,false]",
                "false",
                "\"PAVE's files changed on disk since it started, which can cause this. Stop PAVE and start it again, then try once more.\"",
                "false");
    }

    @Test
    void aServiceFailureIsSaidOnceInsideItsCard(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "a service failure in one card", PRELUDE + """
                const {resultCard} = await import('./js/result.js');
                const said = {presence: 'VALUE', value: 'An unexpected error occurred while evaluating the request.'};
                const failed = {runId: 'run-5', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', answeredBy: 'SCENARIO',
                  verdict: {headline: 'The service failed (HTTP 500).', tone: 'FAULT'}, expectation: {result: 'unverified'},
                  error: {kind: 'VENDOR_ERROR', httpStatus: 500, heading: 'A failure played by a mock scenario', title: 'The service failed with HTTP 500.',
                    action: 'Nothing left this machine.', issues: [{severity: {presence: 'VALUE', value: 'error'}, code: {presence: 'VALUE', value: 'exception'},
                    diagnostics: said, detailsText: said}]},
                  ledger: {recorded: true}, timing: {elapsedMillis: 5}};
                const card = resultCard(failed, {context: 'workbench'});
                document.body.append(card);
                say([card.querySelectorAll('.verdict').length, card.querySelectorAll('.error-panel').length,
                  card.textContent.split('The service failed').length - 1, card.textContent.split(said.value).length - 1]);
                say(card.querySelector('.issue-chips').textContent);
                const unreachable = resultCard({...failed, error: {kind: 'UNREACHABLE', title: 'The service could not be reached.', action: 'Check the address.'}}, {context: 'workbench'});
                say(unreachable.querySelectorAll('.error-panel').length);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[1,0,1,1]",
                "\"error exception\"",
                "1");
    }

    @Test
    void aFileThatIsNotTextIsToldWhatItIs(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "a file that is not text", PRELUDE + """
                const {readText} = await import('./js/files.js');
                const file = (name, head) => { const bytes = new Uint8Array(head.concat([1, 2, 3, 4])); return {name, size: bytes.length, arrayBuffer: async () => bytes.buffer}; };
                const lines = [];
                lines.push((await readText(file('workspace-export.zip', [0x50, 0x4B, 0x03, 0x04]))).line);
                lines.push((await readText(file('col-gzipped.json.gz', [0x1F, 0x8B, 0x08]))).line);
                lines.push((await readText(file('picture.json', [0x89, 0x50, 0x4E, 0x47]))).line);
                lines.push((await readText(file('scan.csv', [0x25, 0x50, 0x44, 0x46]), {source: 'csv'})).line);
                lines.push((await readText(file('latin.json', [0x7B, 0xE9, 0x7D]))).line);
                const refusing = (name, type, size, error) => ({name, type, size, arrayBuffer: async () => { const e = new Error('x'); e.name = error; throw e; }});
                lines.push((await readText(refusing('a-folder.json', '', 4096, 'NotFoundError'))).line);
                lines.push((await readText(refusing('b-folder.csv', 'text/csv', 4096, 'NotFoundError'), {source: 'csv'})).line);
                lines.push((await readText(refusing('moved.json', 'application/json', 0, 'NotFoundError'))).line);
                lines.forEach(say);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "\"workspace-export.zip is a zip file. Unzip it and choose the .json file inside.\"",
                "\"col-gzipped.json.gz is a compressed file. Unpack it and choose the file inside.\"",
                "\"picture.json is a picture or a PDF, not a Postman export.\"",
                "\"scan.csv is a picture or a PDF, not a CSV file.\"",
                "\"latin.json is saved in another encoding (for example by PowerShell or Notepad). Save it as UTF-8, or export it again from Postman.\"",
                "\"a-folder.json is a folder. Choose the exported file inside it.\"",
                "\"b-folder.csv is a folder. Choose the exported file inside it.\"",
                "\"The file you chose can no longer be read: it was moved, renamed, deleted or changed after you chose it. Choose it again.\"");
    }

    @Test
    void aRefusalNamesItsFieldsByLabelAndTheCardSaysOnlyWhatAddsWords(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "labels, displays and gaps", PRELUDE + """
                const {errorPanel} = await import('./js/fault.js');
                const {failurePanel} = await import('./js/wb-run.js');
                const {displayAddsWords} = await import('./js/result.js');
                const {caseGaps} = await import('./js/wb-submit.js');
                const labels = {'provider.npi': 'NPI', 'procedureCodes[0].quantity': 'Qty'};
                const refusal = {kind: 'VALIDATION', title: 'The form has 5 problems. Nothing was sent.', action: 'Correct the marked fields and send again.',
                  fieldErrors: [{field: 'provider.npi', message: 'This NPI fails its check digit.'},
                    {field: 'procedureCodes[0].quantity', message: 'A quantity is more than 0. Leave it blank when none is stated.'},
                    {field: 'unknown.path', message: 'Something else.'}, {field: 'a', message: 'Four.'}, {field: 'b', message: 'Five.'}]};
                const panel = errorPanel(refusal, {labelOf: field => labels[field] || null});
                say(panel.querySelectorAll('.error-fields li').map(each => each.textContent));
                say(panel.textContent.includes('procedureCodes'));
                say(failurePanel({kind: 'NOT_JSON', status: 500}));
                say([displayAddsWords('in-progress', 'In progress'), displayAddsWords('complete', 'Complete'),
                  displayAddsWords('withpa', 'Send with the prior authorization request'), displayAddsWords('x', '')]);
                say(caseGaps({member: {memberId: ''}, provider: {}, procedureCodes: []}).map(gap => gap.field));
                say(caseGaps({member: {memberId: 'S123456789'}, provider: {npi: '1234567893'}, procedureCodes: [{code: 'E0470'}]}));
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"NPI This NPI fails its check digit.\",\"Qty A quantity is more than 0. Leave it blank when none is stated.\",\"Something else.\",\"and 2 more\"]",
                "false",
                "null",
                "[false,false,true,false]",
                "[\"member.memberId\",\"provider.npi\",\"procedureCodes\"]",
                "[]");
    }

    @Test
    void theStatusRowAndTheMemberCardSpeakOfThisCaseOnly(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "this case's notifications and sent forms", PRELUDE + """
                const {ofThisCase} = await import('./js/wb-status.js');
                const {sentForms} = await import('./js/wb-member.js');
                const held = [{id: 'n-3', correlatedRunId: 'run-other'}, {id: 'n-2', correlatedRunId: 'run-mine'}, {id: 'n-1'}];
                say([ofThisCase(held, 'run-mine').map(each => each.id), ofThisCase(held, null), ofThisCase(held, '')]);
                const forms = {typed: 'P32H-PA-REQ', sent: 'P32H-PA-REQ', rule: 'packed'};
                say([sentForms({status: 'DISABLED', memberId: forms}), sentForms({status: 'FOUND', memberId: forms}).rule, sentForms(null)]);
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[[\"n-2\"],[],[]]",
                "[null,\"packed\",null]");
    }
}
