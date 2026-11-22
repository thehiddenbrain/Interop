package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.service.suite.SuiteColumns;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Audit > Suites (plan 3.3, 10.7 G4), run with Node against answers of the shapes SuiteController
 * sends, with a stand-in for the frame (app.js) written in place of the real one. What each test holds:
 * <ul>
 *   <li>the file is read in the page through files.js when "Preview the suite" is pressed and posted
 *       as JSON; a file the page cannot read is never posted and its line stands at the field</li>
 *   <li>"Run suite" sends the text that was previewed, with the epoch the preview was drawn from,
 *       and is withdrawn when another file is chosen or the configuration moves</li>
 *   <li>a row this tool failed on shows its reference and cause in the fault report (T07), the
 *       sentence without the reference; the row that failed with the stop points to the stop's
 *       report, which stands once; "Stop suite" posts the stop and the run is read again</li>
 *   <li>the run is followed while it runs and no longer once the page is left</li>
 *   <li>the CSV template names the mock profile's test member IDs and the test NPI only</li>
 *   <li>every property the page sends is one the server's record reads</li>
 * </ul>
 */
class SuitesFrontendTest {

    private static final String ID = "0f8b1c2a-3d4e-4f50-8a6b-7c8d9e0f1a2b";

    private static final String PRELUDE = """
            import './browser.js';
            import fs from 'node:fs';
            fs.writeFileSync('./js/app.js', [
                'export const app = {',
                '  mode: () => globalThis.fake.mode, isMock: () => globalThis.fake.mode.mode === "mock",',
                '  epoch: () => globalThis.fake.epoch, notice() {}, go: hash => globalThis.fake.went.push(hash),',
                '  on: (name, fn) => { globalThis.fake.listeners.push([name, fn]); return () => { globalThis.fake.off.push(name); }; }',
                '};'].join(String.fromCharCode(10)));
            globalThis.fake = {mode: {mode: 'mock'}, epoch: 7, went: [], listeners: [], off: [], sent: [], answers: {}};
            globalThis.fetch = async (address, init) => {
                const key = init.method + ' ' + address;
                fake.sent.push({key, body: init.body === undefined ? null : JSON.parse(init.body)});
                const answer = fake.answers[key] || {status: 404, body: {kind: 'NOT_FOUND', title: 'Not found.'}};
                return {ok: answer.status < 300, status: answer.status, text: async () => JSON.stringify(answer.body)};
            };
            const settle = () => new Promise(resolve => setTimeout(resolve, 20));
            const say = value => console.log(JSON.stringify(value));
            const text = node => node.textContent;
            const press = (root, label) => root.querySelectorAll('button').find(each => text(each) === label).click();
            const sentTo = key => fake.sent.filter(each => each.key === key).map(each => each.body);
            const choose = (input, name, bytes) => {
                input.files = [new File([bytes], name)];
                input.value = 'C:/fakepath/' + name;
                return input.dispatchEvent({type: 'change'});
            };
            fake.answers['GET /api/vocabulary'] = {status: 200, body: {testResults: [{code: 'PASS', label: 'Passed'}, {code: 'FAIL', label: 'Failed'},
                {code: 'UNVERIFIED', label: 'Unverified'}]}};
            fake.answers['GET /api/suites'] = {status: 200, body: {runs: [{suiteRunId: 'suite-1', name: 'earlier.csv', state: 'FINISHED',
                startedAt: '2026-11-22T10:00:00Z', headline: 'Finished: 2 cases. 2 passed.'}]}};
            """;

    private static final String RUNNING = """
            const counts = (done, total, extra) => Object.assign({total, done, passed: 0, failed: 0, unverified: 0, blocked: 0, invalid: 0,
                notConfigured: 0, notRunnable: 0, notSignedIn: 0, toolFailed: 0, partly: 0, notRun: 0, waiting: 0, notRecorded: 0,
                withFindings: 0, slow: 0, changed: 0, failedByFault: 0}, extra || {});
            const passedRow = {row: 1, caseId: 'TC-001', what: 'CRD order-sign', status: 'COMPLETED', statusLine: 'Sent and answered.',
                expected: 'NO_AUTH_REQUIRED', outcome: 'NO_AUTH_REQUIRED', outcomeLabel: 'PA not required', tone: 'CLEAR', result: 'pass',
                elapsedMillis: 150, slow: false, runIds: ['run-a'], findings: [], notes: [], notRecorded: [], countedIn: 'passed'};
            const failedRow = {row: 2, caseId: 'TC-002', what: 'CRD order-sign', status: 'TOOL_FAILED',
                statusLine: 'This tool failed while running the case. Look for it in Audit before running it again. Reference ' + ID + '.',
                faultReference: ID, faultCause: 'java.lang.NoClassDefFoundError', runIds: [], findings: [], notes: [], notRecorded: [],
                countedIn: 'toolFailed'};
            const waitingRow = {row: 3, caseId: 'TC-003', what: 'CRD order-select', status: 'WAITING', statusLine: 'Waiting to be run.',
                runIds: [], findings: [], notes: [], notRecorded: [], countedIn: 'waiting'};
            const view = (state, done, rows, extra) => Object.assign({suiteRunId: 'suite-2', name: 'cases.csv', format: 'CSV', state,
                headline: state === 'RUNNING' ? 'Running: ' + done + ' of 3 cases done.' : 'Stopped: 2 of 3 cases done.',
                mode: 'mock', environment: {name: 'PAVE Sample Sandbox', kind: 'SANDBOX'}, startedAt: '2026-11-22T11:00:00Z',
                counts: counts(done, 3, {passed: 1, toolFailed: 1, waiting: 3 - done}), notes: [], results: rows,
                countGroups: [{group: 'passed', label: 'Passed', count: 1}, {group: 'toolFailed', label: 'This tool failed: whether a request left is not known', count: 1},
                    {group: 'waiting', label: 'Waiting to be run', count: 3 - done}]}, extra || {});
            """;

    @Test
    void theFileIsReadAtThePressAndTheRunSendsTheTextThatWasPreviewed(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "audit-suites.js", PRELUDE + """
            const {mount} = await import('./js/audit-suites.js');
            const root = document.createElement('div');
            document.body.append(root);
            const page = mount(root, ['suites']);
            await settle();
            const runButton = () => root.querySelectorAll('button').find(each => text(each) === 'Run suite');
            say(['mounted', text(root.querySelector('h1')), root.querySelectorAll('button').map(text), runButton().disabled,
                root.querySelectorAll('select option').map(text)]);

            // A file the page cannot read is never posted: its line stands at the field and the input is let go.
            const file = root.querySelector('input');
            await choose(file, 'cases.csv', new Uint8Array([0x63, 0x61, 0xff, 0xfe, 0x41, 0x0a]));
            await press(root, 'Preview the suite');
            await settle();
            say(['unreadable', sentTo('POST /api/suites/preview').length, root.querySelector('.field--error .field__error').textContent, file.value]);

            const csv = 'caseId,protocol,memberId\\nTC-001,crd,P32H-NO-AUTH\\n';
            await choose(file, 'cases.csv', new TextEncoder().encode(csv));
            fake.answers['POST /api/suites/preview'] = {status: 200, body: {name: 'cases.csv', format: 'CSV', runnable: true,
                headline: '1 case can be run.', total: 1, valid: 1, problems: [], rowProblems: [], rowProblemCount: 0,
                kinds: [{what: 'CRD order-sign', count: 1}], writes: 0, withExpectation: 0, columns: ['caseId', 'protocol', 'memberId'],
                mode: 'mock', environment: {name: 'PAVE Sample Sandbox', kind: 'SANDBOX'}}};
            await press(root, 'Preview the suite');
            await settle();
            say(['previewed', sentTo('POST /api/suites/preview'), root.querySelector('.field--error') === null, runButton().disabled,
                text(root.querySelector('h2'))]);

            // Another file chosen after the preview withdraws the run until it is previewed.
            await choose(file, 'other.csv', new TextEncoder().encode('caseId\\nX\\n'));
            say(['other file', runButton().disabled, text(root).includes('1 case can be run.')]);
            await choose(file, 'cases.csv', new TextEncoder().encode(csv));
            await press(root, 'Preview the suite');
            await settle();
            // A file changed after the preview is not what runs: the text previewed is.
            file.files = [new File([new TextEncoder().encode('caseId\\nCHANGED\\n')], 'cases.csv')];
            root.querySelectorAll('input').find(each => each.name === 'testerRef').value = ' QA-7 ';
            fake.answers['POST /api/suites'] = {status: 202, body: {suiteRunId: 'suite-2', state: 'FINISHED', headline: 'Finished: 1 case. 1 passed.',
                counts: {total: 1, done: 1, notRecorded: 0}, results: []}};
            await press(root, 'Run suite');
            await settle();
            say(['started', sentTo('POST /api/suites'), text(root).includes('Finished: 1 case. 1 passed.')]);

            // A move of the configuration withdraws a held preview: it was drawn from the earlier one.
            await choose(file, 'cases.csv', new TextEncoder().encode(csv));
            await press(root, 'Preview the suite');
            await settle();
            fake.listeners.filter(([name]) => name === 'config').forEach(([, fn]) => fn());
            await settle();
            say(['config moved', runButton().disabled, text(root).includes('Preview the suite again before running it.')]);
            page.unmount();
            say(['unmounted', fake.off, root.childNodes.length]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"mounted\",\"Suites\",[\"Preview the suite\",\"Run suite\",\"Open this suite run\"],true,"
                        + "[\"No comparison\",\"earlier.csv, 2026-11-22 10:00:00 UTC: Finished: 2 cases. 2 passed.\"]]",
                "[\"unreadable\",0,\"cases.csv is saved in another encoding (for example by Excel or Notepad). Save it as CSV UTF-8, then choose it "
                        + "again.\",\"\"]",
                "[\"previewed\",[{\"fileName\":\"cases.csv\",\"text\":\"caseId,protocol,memberId\\nTC-001,crd,P32H-NO-AUTH\\n\",\"testerRef\":null,"
                        + "\"compareWith\":null,\"configEpoch\":7}],true,false,"
                        + "\"1 case can be run.\"]",
                "[\"other file\",true,false]",
                "[\"started\",[{\"fileName\":\"cases.csv\",\"text\":\"caseId,protocol,memberId\\nTC-001,crd,P32H-NO-AUTH\\n\",\"testerRef\":\"QA-7\","
                        + "\"compareWith\":null,\"configEpoch\":7}],true]",
                "[\"config moved\",true,true]",
                "[\"unmounted\",[\"config\"],0]");
    }

    /** T07 for suite rows and the stop: the reference with its cause and one copy of both, given once. */
    @Test
    void aRowThisToolFailedOnShowsItsFaultReportAndTheStopIsReportedOnce(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "audit-suites.js", PRELUDE + "const ID = '" + ID + "';\n" + RUNNING + """
            const {mount} = await import('./js/audit-suites.js');
            const root = document.createElement('div');
            document.body.append(root);
            fake.answers['GET /api/suites/suite-2'] = {status: 200, body: view('RUNNING', 2, [passedRow, failedRow, waitingRow])};
            const page = mount(root, ['suites', 'suite-2']);
            await settle();
            const rows = () => root.querySelectorAll('.audit-suites__run .table tbody tr');
            say(['running', text(root.querySelector('.audit-suites__run h2')), root.querySelector('progress').value,
                root.querySelector('.audit-suites__run [role=status]').textContent,
                root.querySelectorAll('.audit-suites__run button').map(text).filter(label => label.startsWith('Stop') || label.startsWith('Every'))]);
            say(['passed row', rows()[0].querySelectorAll('td').map(text)]);
            const failed = rows()[1];
            const cells = failed.querySelectorAll('td');
            say(['failed row', cells[cells.length - 2].querySelector('.config-wrap div').textContent,
                failed.querySelectorAll('.fault-report__row').map(text), failed.querySelector('.fault-report button').textContent]);
            await failed.querySelector('.fault-report button').click();
            say(['copied', standIn.clipboard]);

            // Filtered by a count: only its rows.
            await press(root, 'Passed 1');
            say(['filtered', rows().length, rows()[0].querySelectorAll('td')[1].querySelector('.mono').textContent]);
            await press(root, 'Passed 1');
            say(['every case', rows().length]);

            // Followed while it runs: the brief answer without rows, then the whole run once the count moved.
            const before = fake.sent.length;
            fake.answers['GET /api/suites/suite-2?rows=false'] = {status: 200, body: view('RUNNING', 2, [])};
            await new Promise(resolve => setTimeout(resolve, 1150));
            say(['polled', fake.sent.slice(before).map(each => each.key)]);

            // Stop: posted, then the run is read again; the stop's report stands once and the row points to it.
            const stopped = Object.assign({}, failedRow, {statusLine: 'This tool failed while running the suite. Reference ' + ID + '.'});
            fake.answers['POST /api/suites/suite-2/stop'] = {status: 200, body: view('RUNNING', 2, [], {stopping: true})};
            fake.answers['GET /api/suites/suite-2'] = {status: 200, body: view('STOPPED', 2, [passedRow, stopped,
                Object.assign({}, waitingRow, {status: 'NOT_RUN', statusLine: 'Not run: the suite stopped before it.', countedIn: 'notRun'})],
                {stopReason: 'This tool failed while running the suite. Reference ' + ID + '.', stopReference: ID,
                 stopCause: 'java.lang.NoClassDefFoundError', finishedAt: '2026-11-22T11:00:09Z'})};
            await press(root, 'Stop suite');
            await settle();
            const runBox = root.querySelector('.audit-suites__run');
            say(['stopped', sentTo('POST /api/suites/suite-2/stop').length, text(runBox.querySelector('h2')),
                runBox.querySelectorAll('button').map(text).includes('Stop suite'),
                runBox.querySelectorAll('.fault-report').length, text(runBox).split(ID).length - 1,
                text(runBox).includes('This tool failed while running the suite.'), text(runBox).includes('The same failure as the stop above.'),
                runBox.querySelector('a.btn').getAttribute('href') === null ? runBox.querySelector('a.btn').href : runBox.querySelector('a.btn').getAttribute('href')]);
            const after = fake.sent.length;
            await new Promise(resolve => setTimeout(resolve, 1150));
            page.unmount();
            say(['not followed once ended or left', fake.sent.length - after]);
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"running\",\"Running: 2 of 3 cases done.\",2,\"2 of 3 cases done\",[\"Stop suite\",\"Every case 3\"]]",
                "[\"passed row\",[\"1\",\"TC-001CRD order-sign\",\"PA not requiredexpected NO_AUTH_REQUIRED\",\"Passed\",\"150\","
                        + "\"Sent and answered.run-a\",\"none\"]]",
                "[\"failed row\",\"This tool failed while running the case. Look for it in Audit before running it again.\","
                        + "[\"Reference" + ID + "\",\"Cause, as the server log names it:java.lang.NoClassDefFoundError\"],\"Copy reference and cause\"]",
                "[\"copied\",\"Reference " + ID + "\\nCause, as the server log names it: java.lang.NoClassDefFoundError\"]",
                "[\"filtered\",1,\"TC-001\"]",
                "[\"every case\",3]",
                "[\"polled\",[\"GET /api/suites/suite-2?rows=false\"]]",
                "[\"stopped\",1,\"Stopped: 2 of 3 cases done.\",false,1,1,true,true,\"/api/suites/suite-2/results.csv\"]",
                "[\"not followed once ended or left\",0]");
    }

    @Test
    void theTemplateNamesTheMockTestMemberIdsOnly(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "the suite template", PRELUDE + """
            const {TEMPLATE_ROWS, templateText} = await import('./js/audit-suites.js');
            const head = TEMPLATE_ROWS[0];
            const rows = TEMPLATE_ROWS.slice(1);
            say(head);
            say(rows.map(row => row[head.indexOf('memberId')]));
            say(Array.from(new Set(rows.map(row => row[head.indexOf('npi')]))));
            say(templateText().split('\\r\\n').length);
            say(/[0-9]{6,}/.test(rows.map(row => row[head.indexOf('memberId')]).join(',')));
            """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                "[\"caseId\",\"protocol\",\"hook\",\"memberId\",\"npi\",\"procedureCodes\",\"dateOfService\",\"expected\",\"scenarioId\","
                        + "\"testerRef\",\"note\"]",
                "[\"P32H-NO-AUTH\",\"P32H-PA-REQ\",\"P32H-DOC-DTR\"]",
                "[\"1234567893\"]",
                "5",
                "false");
    }

    /** The columns the template names are columns the server reads, and the request properties are the record's. */
    @Test
    void whatThePageSendsIsWhatTheServerReads() throws IOException {
        String page = StaticSources.script("audit-suites.js");
        List<String> components = Arrays.stream(SuiteController.SuiteFileRequest.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(components).containsExactly("fileName", "text", "testerRef", "compareWith", "configEpoch");
        // One body builder for the preview and the run, naming every component of the record.
        assertThat(page).contains("'/api/suites/preview', {body: suiteBody(got.fileName, got.text, epoch)}")
                .contains("'/api/suites', {body: suiteBody(held.fileName, held.text, held.epoch)}")
                .contains("return {fileName, text, testerRef: ref === '' ? null : ref, compareWith: compare.value ? compare.value : null, "
                        + "configEpoch: epoch};");
        // Read in the page through files.js, never sent as a file.
        assertThat(page).contains("readText(file, {maxBytes: MOST_BYTES, source: 'csv'})").doesNotContain("FormData").doesNotContain("multipart");
        for (String column : List.of("caseId", "protocol", "hook", "memberId", "npi", "procedureCodes", "dateOfService", "expected",
                "scenarioId", "testerRef", "note")) {
            assertThat(SuiteColumns.find(column)).as(column).isNotNull();
            assertThat(SuiteColumns.find(column).canonical()).as(column).isEqualTo(column);
        }
    }
}
