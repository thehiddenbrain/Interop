package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.web.StaticSources.NodeRun;

/**
 * Audit's rules that no server test can see (plan 10.2, B4a): a replay of the runs listed sends
 * exactly the runs listed, by id, oldest first, never a range of days; runs made by a replay are
 * left out of the list unless asked for; a run is opened by its id as typed, encoded, with no
 * pattern; the Samples filter is built on mock only; a total never covers a run nobody timed; a
 * replay entry shows a failure of this tool through the fault report and a masked reading under
 * its conclusion; and the note editor carries the ledger hint at both its fields. The modules are
 * run with Node against a stand-in for the document and for the server.
 */
class AuditFrontendTest {

    @Test
    void theModulesKeepEveryRuleWhenNodeRunsThem(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "Audit's modules", """
                import './browser.js';
                import {replayIds, totalWords, timedTotal, wordsOf} from './js/audit-common.js';
                import {runQueries, mergeLists, withSamples, filtersOf, runsView} from './js/audit-runs.js';
                import {entryDetail, replayLine} from './js/audit-replay.js';
                import {noteEditor} from './js/audit-note.js';
                const say = value => console.log(JSON.stringify(value));
                globalThis.window = globalThis;
                const vocabulary = {
                  steps: [{step: 'COVERAGE', name: 'Coverage check', protocol: 'CRD', filters: [{protocol: 'crd'}]},
                          {step: 'STATUS', name: 'Status', protocol: 'PAS', filters: [{operation: 'PAS_INQUIRE'}, {protocol: 'subscription'}]}],
                  operations: [{code: 'CRD_ORDER_SIGN', label: 'CRD order-sign', protocol: 'CRD', step: 'COVERAGE', write: false},
                               {code: 'PAS_SUBMIT', label: 'PAS submit', protocol: 'PAS', step: 'SUBMIT', write: true},
                               {code: 'SUBSCRIPTION_NOTIFICATION', label: 'Subscription notification', protocol: 'SUBSCRIPTION', step: 'STATUS', write: false}],
                  outcomes: {CRD: [{code: 'AUTH_REQUIRED', label: 'Prior authorization is required', short: 'PA required', tone: 'ACTION'},
                                   {code: 'NO_AUTH_REQUIRED', label: 'No prior authorization', short: 'PA not required', tone: 'CLEAR'}],
                             RUN: [{code: 'BLOCKED', label: 'Blocked', short: 'Blocked', tone: 'BLOCKED'}]},
                  testResults: [{code: 'PASS', label: 'Passed'}, {code: 'FAIL', label: 'Failed'}, {code: 'UNVERIFIED', label: 'Unverified'}],
                  answeredBy: [{code: 'SCENARIO', chip: 'MOCK'}]
                };
                const words = wordsOf(vocabulary);
                // 1 replay ids
                const listed = [{runId: 'c'}, {runId: 'b', replayOfRunId: 'a'}, {runId: 'a'}];
                say([replayIds(listed), listed.map(run => run.runId)]);
                // 2 queries
                say(runQueries(filtersOf(''), words));
                say(runQueries(filtersOf('?replays=include&limit=0&from=2026-11-22&member=S123456789'), words));
                say(runQueries(filtersOf('?step=STATUS&result=fail'), words));
                say(runQueries(filtersOf('?step=NOPE'), words));
                // 3 merge
                say(mergeLists([{count: 2, truncated: false, runs: [{runId: 'x', timestamp: '2026-11-22T10:00:00Z'}, {runId: 'y', timestamp: '2026-11-22T08:00:00Z'}]},
                  {count: 1, truncated: false, runs: [{runId: 'z', timestamp: '2026-11-22T09:00:00Z'}]}], '2'));
                say(mergeLists([{count: 1, truncated: true, matched: 5, runs: [{runId: 'x', timestamp: '2026-11-22T10:00:00Z'}]},
                  {count: 1, truncated: false, runs: [{runId: 'z', timestamp: '2026-11-22T09:00:00Z'}]}], '1'));
                // 4 samples
                const runs = [{runId: 's', operation: 'SUBSCRIPTION_NOTIFICATION', scenarioId: 'pas-11'}, {runId: 'n', operation: 'SUBSCRIPTION_NOTIFICATION'}, {runId: 'c', operation: 'CRD_ORDER_SIGN', scenarioId: 'crd-01'}];
                say([withSamples(runs, 'only', true).map(r => r.runId), withSamples(runs, 'leave-out', true).map(r => r.runId), withSamples(runs, 'only', false).map(r => r.runId)]);
                // 5 totals
                say([totalWords(4, 4, '500 ms'), totalWords(4, 3, '500 ms'), totalWords(1, 1, '150 ms'), totalWords(1, 0, '0 ms'), totalWords(2, 0, '0 ms')]);
                say(timedTotal([{elapsedMillis: 150}, {elapsedMillis: null}, {elapsedMillis: 1200}]));
                // 6 fault report and masked reading in a replay entry
                const entry = {runId: 'run-1', status: 'error', reason: 'This tool failed while sending the run again. Reference ref-9.',
                  faultReference: 'ref-9', faultCause: 'java.lang.IllegalStateException', original: {protocol: 'CRD', outcome: 'AUTH_REQUIRED', result: 'pass'}};
                const detail = entryDetail(entry, words);
                say(detail.querySelector('.fault-report').textContent);
                say(detail.textContent.includes('Reference ref-9.'));
                const masked = entryDetail({runId: 'run-2', status: 'changed', differences: [{field: 'outcome', was: 'AUTH_REQUIRED', now: 'NO_AUTH_REQUIRED'}],
                  original: {protocol: 'CRD'}, replayed: {protocol: 'CRD', outcome: 'NO_AUTH_REQUIRED', result: 'unverified'}, replayRunId: 'run-3',
                  verdict: {headline: 'No prior authorization', maskedReading: 'Read from a masked value.'}}, words);
                say([replayLine({status: 'same'}, words), masked.querySelector('.audit-replay__line').textContent, masked.querySelector('.masked-reading').textContent]);
                // 7 note editor hints
                const note = noteEditor({runId: 'run-1', note: '', testerRef: ''});
                say(note.querySelectorAll('.field__hint').map(hint => hint.textContent));
                // 8 the list, the samples filter and the replay, run against a stand-in server
                const sent = [];
                const answers = {
                  '/api/runs/health': {enabled: true, writable: true, runsToday: 3},
                  '/api/runs': {count: 3, truncated: false, runs: [
                    {runId: 'run-new', timestamp: '2026-11-22T10:00:00Z', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', outcome: 'AUTH_REQUIRED', result: 'pass', elapsedMillis: 150},
                    {runId: 'run-copy', timestamp: '2026-11-22T09:30:00Z', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', outcome: 'AUTH_REQUIRED', result: 'pass', replayOfRunId: 'run-old'},
                    {runId: 'run-old', timestamp: '2026-11-22T09:00:00Z', protocol: 'PAS', operation: 'PAS_SUBMIT', outcome: 'BLOCKED', result: 'unverified'}]},
                  '/api/runs/replay': {summary: {same: 2}, results: []}
                };
                globalThis.fetch = async (address, init) => {
                  sent.push({address: String(address), method: init.method, body: init.body || null});
                  const path = String(address).split('?')[0];
                  return {ok: true, status: 200, text: async () => JSON.stringify(answers[path] || {})};
                };
                for (const mock of [false, true]) {
                  const app = {isMock: () => mock, mode: () => ({mode: mock ? 'mock' : 'live', environment: {}, writeAccess: {writesAllowed: false, reason: 'Writes are locked.'}}),
                    on: () => () => true, notice() {}};
                  const view = runsView({app, words: async () => words, ledger: () => null, go: parts => sent.push({go: parts})});
                  document.body.append(view.node);
                  sent.length = 0;
                  await view.show(filtersOf(''));
                  say([mock, view.node.querySelectorAll('label').map(label => label.textContent).filter(text => text.includes('Samples')).length,
                    sent.filter(each => each.address).map(each => each.address)]);
                  if (mock) {
                    const opener = view.node.querySelectorAll('button').find(each => each.textContent === 'Replay the runs listed');
                    await opener.click();
                    const dialog = document.body.querySelector('dialog');
                    say(dialog.querySelector('.modal__body').textContent);
                    const go = dialog.querySelectorAll('button').find(each => each.textContent === 'Replay these runs');
                    sent.length = 0;
                    await go.click();
                    await new Promise(resolve => setTimeout(resolve, 10));
                    say(sent.filter(each => each.address).map(each => [each.method, each.address, each.body]));
                  }
                  view.node.remove ? view.node.remove() : null;
                }
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        assertThat(run.lines()).containsExactly(
                // The originals oldest first, and the list on screen left in its own order.
                "[[\"a\",\"c\"],[\"c\",\"b\",\"a\"]]",
                // Replays left out unless asked; a filter Audit does not keep (a member) is never sent.
                "[{\"limit\":\"200\",\"replays\":\"leave-out\"}]",
                "[{\"from\":\"2026-11-22\",\"limit\":\"0\",\"replays\":\"include\"}]",
                // The Status step is the inquiry and the subscription calls: one question each, as the vocabulary names them.
                "[{\"result\":\"fail\",\"limit\":\"200\",\"replays\":\"leave-out\",\"operation\":\"PAS_INQUIRE\"},"
                        + "{\"result\":\"fail\",\"limit\":\"200\",\"replays\":\"leave-out\",\"protocol\":\"subscription\"}]",
                // A step the vocabulary does not name asks nothing, rather than listing every run under it.
                "[]",
                "{\"runs\":[{\"runId\":\"x\",\"timestamp\":\"2026-11-22T10:00:00Z\"},{\"runId\":\"z\",\"timestamp\":\"2026-11-22T09:00:00Z\"}],"
                        + "\"truncated\":true,\"matched\":3}",
                "{\"runs\":[{\"runId\":\"x\",\"timestamp\":\"2026-11-22T10:00:00Z\"}],\"truncated\":true,\"matched\":6}",
                // Samples: only the notifications delivered as samples, or every run but them; on live the filter does nothing.
                "[[\"s\"],[\"n\",\"c\"],[\"s\",\"n\",\"c\"]]",
                "[\"4 runs taking 500 ms in all\",\"4 runs, 3 of them measured, taking 500 ms in all\",\"1 run taking 150 ms in all\","
                        + "\"1 run, not measured\",\"2 runs, none of them measured\"]",
                "\"3 runs, 2 of them measured, taking 1,350 ms in all\"",
                // A failure of this tool: the reference and the cause together with the one copy button, and not said twice in the reason.
                "\"Referenceref-9Cause, as the server log names it:java.lang.IllegalStateExceptionCopy reference and cause\"",
                "false",
                "[\"Same outcome.\",\"Changed: PA required to PA not required.\",\"Read from a masked value.\"]",
                "[\"Kept in the ledger as typed. Never type member data here.\",\"Kept in the ledger as typed. Never type member data here.\"]",
                // No Samples filter on live; on mock it is there. Either way the list asks with replays left out.
                "[false,0,[\"/api/runs?limit=200&replays=leave-out\",\"/api/runs/health\"]]",
                "[true,1,[\"/api/runs?limit=200&replays=leave-out\",\"/api/runs/health\"]]",
                "\"3 runs are listed. 1 of them was made by a replay and is not sent again. 2 runs are sent again, oldest first, one after another."
                        + "1 of them is a write (1 x PAS submit). This is the mock profile: the scenarios answer again and nothing leaves this machine. "
                        + "Each run sent again is recorded as a new run.\"",
                // The replay sends the ids listed, oldest first, and no range of days.
                "[[\"POST\",\"/api/runs/replay\",\"{\\\"runIds\\\":[\\\"run-old\\\",\\\"run-new\\\"]}\"]]");
    }

    @Test
    void aRunOpenedFromTheLedgerSaysWhatItCannotShowAndWhyItCannotBeRunAgain(@TempDir Path folder) throws Exception {
        NodeRun run = StaticSources.runModule(folder, "one run in Audit", """
                import './browser.js';
                import {wordsOf} from './js/audit-common.js';
                import {runView} from './js/audit-run.js';
                const say = value => console.log(JSON.stringify(value));
                globalThis.window = globalThis;
                const words = wordsOf({steps: [{step: 'COVERAGE', name: 'Coverage check', protocol: 'CRD', filters: [{protocol: 'crd'}]}],
                  operations: [{code: 'CRD_ORDER_SIGN', label: 'CRD order-sign', protocol: 'CRD', step: 'COVERAGE', write: false}],
                  outcomes: {CRD: [{code: 'AUTH_REQUIRED', label: 'Prior authorization is required', short: 'PA required', tone: 'ACTION'}]},
                  testResults: [{code: 'PASS', label: 'Passed'}], answeredBy: [{code: 'VENDOR', chip: 'LIVE'}]});
                const lines = {
                  // Live with the default policy: the member ID kept only as a token (I-20).
                  'run-token': {runId: 'run-token', timestamp: '2026-11-22T10:00:00Z', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', outcome: 'AUTH_REQUIRED',
                    result: 'pass', memberToken: 'tok-1a2b', profile: 'live'},
                  'run-kept': {runId: 'run-kept', timestamp: '2026-11-22T10:00:00Z', protocol: 'CRD', operation: 'CRD_ORDER_SIGN', outcome: 'AUTH_REQUIRED',
                    result: 'pass', memberId: 'S123456789', memberToken: 'tok-1a2b', profile: 'mock'}
                };
                globalThis.fetch = async address => {
                  const path = String(address).split('?')[0];
                  const id = decodeURIComponent(path.slice('/api/runs/'.length));
                  const body = path === '/api/runs/health' ? {enabled: true, writable: true} : lines[id];
                  return {ok: true, status: 200, text: async () => JSON.stringify(body)};
                };
                const view = runView({app: {isMock: () => false, mode: () => ({mode: 'live'})}, words: async () => words, ledger: () => null,
                  listAddress: () => '#audit/runs'});
                document.body.append(view.node);
                for (const id of ['run-token', 'run-kept']) {
                  await view.show(id);
                  const text = view.node.textContent;
                  say([id, view.node.querySelectorAll('button').map(each => each.textContent).filter(each => each === 'Run again').length,
                    text.includes('This run cannot be run again: its member ID was not kept.'), text.includes('tracking.'),
                    view.node.querySelectorAll('.audit-run__unkept').map(each => each.textContent)]);
                }
                """);
        assertThat(run.exit()).as(run.output()).isZero();
        String unkept = "[\"The request and answer are shown only by the page that made the run, while it stays open.\"]";
        assertThat(run.lines()).containsExactly(
                // No button that could only be told the line cannot be sent; no property name.
                "[\"run-token\",0,true,false," + unkept + "]",
                "[\"run-kept\",1,false,false," + unkept + "]");
    }

    @Test
    void aReplaySendsIdsAndNeverDays() throws IOException {
        for (String name : new String[] {"audit-runs.js", "audit-run.js"}) {
            String script = StaticSources.script(name);
            assertThat(script).as(name).contains("call('POST', '/api/runs/replay', {body: {runIds: ")
                    .doesNotContain("from:").doesNotContain("to:");
        }
        assertThat(StaticSources.body(StaticSources.script("audit-runs.js"), "async function runReplay(ids, words, slot, opener) {"))
                .contains("{body: {runIds: ids}}");
    }

    @Test
    void aRunIsOpenedByItsIdAsTypedEncodedAndWithNoPattern() throws IOException {
        String open = StaticSources.body(StaticSources.script("audit-runs.js"), "async function openById(input, wrapper, said) {");
        assertThat(open).contains("call('GET', '/api/runs/' + encodeURIComponent(typed))").contains("ctx.go(['runs', typed])")
                .doesNotContain("RegExp").doesNotContain(".match(").doesNotContain(".test(");
        assertThat(StaticSources.body(StaticSources.script("audit-run.js"), "async function show(runId) {"))
                .contains("call('GET', '/api/runs/' + encodeURIComponent(runId))");
        for (String name : new String[] {"audit.js", "audit-common.js", "audit-runs.js", "audit-run.js", "audit-replay.js", "audit-note.js"}) {
            assertThat(StaticSources.withoutComments(StaticSources.script(name))).as(name).doesNotContain("RegExp").doesNotContain(".test(")
                    .doesNotContain(".match(").doesNotContain(".replace(/");
        }
    }

    @Test
    void theSamplesFilterIsBuiltOnMockOnly() throws IOException {
        String form = StaticSources.body(StaticSources.script("audit-runs.js"), "function filterForm(filters, words, mock) {");
        assertThat(form).contains("const samples = mock ? select('samples', 'Samples',");
        assertThat(StaticSources.body(StaticSources.script("audit-runs.js"), "export function withSamples(runs, choice, mock) {"))
                .contains("if (!mock || !choice) {");
    }

    @Test
    void theNoteEditorTakesTheLedgerHintFromTheWorkbench() throws IOException {
        String note = StaticSources.script("audit-note.js");
        assertThat(note).contains("import {LEDGER_TEXT_HINT} from './wb-case.js';")
                .contains("field({label: 'Note', input: note, hint: LEDGER_TEXT_HINT})")
                .contains("field({label: 'Tester reference', input: ref, hint: LEDGER_TEXT_HINT})");
    }
}
