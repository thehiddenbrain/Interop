// Suite runs: a file of cases, run one after another, with a results table for sign-off.
//
// The server reads the file, says what it makes of it before anything runs, runs the cases in the
// background, and words every row: what ran, where it stands, and why a row was not sent. This
// page shows that, follows the progress count, and filters the table. A row that was blocked, not
// valid, not configured, not runnable or not run is shown as exactly that, in the server's
// sentence, and never as a failure: it has no test result at all.

import * as api from './api.js';
import {el, fill, clear, button, code, facts, table, tableRow, heading, copyButton, count, millis, when} from './dom.js';
import {textField, selectField, setOptions, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {outcome, stamp, slowFlag} from './marks.js';
import {errorPanel} from './result.js';

const POLL_MILLIS = 1000;

// The columns of a suite file, as the server's own documentation names them, with rows a tester
// can run as they are. Only the sentinel member IDs of the mock scenarios appear here: a template
// must never carry anything that looks like a real member.
const TEMPLATE = [
    'caseId,protocol,hook,memberId,npi,procedureCodes,dateOfService,expected,scenarioId,testerRef,note',
    'TC-001,crd,order-sign,P32H-NO-AUTH,1234567893,95811,2026-01-15,NO_AUTH_REQUIRED,,QA-1,Expects that prior authorization is not required',
    'TC-002,crd,order-sign,P32H-PA-REQ,1234567893,95810,2026-01-15,AUTH_REQUIRED; pa-needed=auth-needed,,QA-1,Expects an outcome and a standard code',
    'TC-003,crd,order-select,P32H-DOC-DTR,1234567893,95810,2026-01-15,,,QA-1,Declares nothing so the case is unverified',
    ''
].join('\r\n');

// The counts of a suite run, in the order they are offered as filters, with the rows each one
// selects. The server counts; this only picks out the rows a count is about.
const COUNT_FILTERS = [
    ['passed', 'Passed', row => row.status === 'COMPLETED' && row.result === 'pass'],
    ['failed', 'Failed', row => row.status === 'COMPLETED' && row.result === 'fail'],
    ['unverified', 'Unverified', row => row.status === 'COMPLETED' && row.result !== 'pass' && row.result !== 'fail'],
    ['blocked', 'Blocked', row => row.status === 'BLOCKED'],
    ['invalid', 'Not valid', row => row.status === 'INVALID'],
    ['notConfigured', 'Not configured', row => row.status === 'NOT_CONFIGURED'],
    ['notRunnable', 'Not runnable', row => row.status === 'NOT_RUNNABLE'],
    ['notRun', 'Not run', row => row.state === 'NOT_RUN'],
    ['waiting', 'Waiting', row => row.state === 'PENDING' || row.state === 'RUNNING'],
    ['withFindings', 'With findings', row => (row.findings || []).length > 0],
    ['slow', 'Slow', row => row.slow === true],
    ['changed', 'Changed since the earlier run', row => row.changed === true],
    ['notRecorded', 'Runs NOT recorded', row => (row.notRecorded || []).length > 0]
];

export function createSuites(app) {
    let run = null;            // the suite run on screen, with its rows
    let filter = null;         // {key, label, test} or null for every row
    let timer = null;
    let lastDone = -1;
    let previewEpoch = null;

    const file = el('input', {type: 'file', id: 'suite-file', accept: '.csv,.json,text/csv,application/json'});
    const templateLink = el('a', {download: 'pave-suite-template.csv', href: '#'}, 'Download a suite file template (CSV)');
    const testerRef = textField({label: 'Tester reference', field: 'testerRef', placeholder: 'ticket or initials'});
    const compare = selectField({label: 'Compare with an earlier run', field: 'compareWith', options: [{value: '', label: 'No comparison'}],
        hint: 'Each case then shows what it produced last time.'});
    const start = button('Run suite', startRun, {class: 'button--primary', disabled: true});
    const problem = el('div', {aria: {live: 'polite'}});
    const previewBox = el('div', {class: 'suite-preview', aria: {live: 'polite'}});
    const earlier = el('div', {class: 'suite-earlier', aria: {live: 'polite'}});
    const runBox = el('div', {class: 'suite-run'});

    // The results table needs the whole width of the window, so this view is one column: what to
    // run and the runs the tool holds side by side at the top, the open run beneath them.
    const form = el('div', {class: 'suites__start'},
        el('h2', {class: 'case-legend'}, 'Run a suite'),
        el('div', {class: 'suites__controls'},
            el('div', {class: 'field', dataset: {field: 'file'}},
                el('label', {htmlFor: 'suite-file'}, 'Suite file, CSV or JSON'), file,
                el('span', {class: 'small'}, templateLink)),
            testerRef.node,
            compare.node),
        previewBox,
        problem,
        el('div', {class: 'button-row'}, start));
    const node = el('div', {class: 'suites'},
        el('div', {class: 'suites__top'}, form,
            el('div', {class: 'suites__held'}, el('h2', {class: 'case-legend'}, 'Suite runs the tool holds'), earlier)),
        runBox);

    templateLink.addEventListener('click', () => {
        // Made when asked for, from text held in this file. Nothing is fetched and nothing leaves the machine.
        const address = URL.createObjectURL(new Blob([TEMPLATE], {type: 'text/csv'}));
        templateLink.href = address;
        window.setTimeout(() => URL.revokeObjectURL(address), 60000);
    });
    file.addEventListener('change', preview);
    drawRun();

    /* ---------- what the tool makes of the file ---------- */

    async function preview() {
        clearFieldErrors(form);
        clear(problem);
        clear(previewBox);
        start.disabled = true;
        if (file.files.length === 0) {
            return;
        }
        const data = new FormData();
        data.append('file', file.files[0]);
        const answer = await api.postForm('/api/suites/preview', data);
        previewEpoch = app.epoch();
        if (answer.kind === 'form') {
            fill(problem, formProblem(answer.body, markFieldErrors([form], answer.body.fieldErrors)));
            return;
        }
        if (answer.kind !== 'ok') {
            fill(previewBox, errorPanel(answer.body));
            return;
        }
        const seen = answer.body;
        const environment = seen.environment || {};
        start.disabled = !seen.runnable;
        fill(previewBox,
            el('p', {class: 'suite-preview__headline'}, seen.headline),
            seen.problems.length > 0 ? el('div', {class: 'form-problem'},
                el('p', {class: 'form-problem__title'}, 'The file cannot be run as it is.'),
                el('ul', {class: 'note-list'}, seen.problems.map(text => el('li', null, text)))) : null,
            facts([
                ['File', [seen.name, seen.format ? ' (' + seen.format + ')' : null]],
                ['Would run against', [environment.active ? environment.name + ' (' + environment.kind + ')' : 'no environment',
                    ', ', seen.mode, ' profile, target ', seen.target]],
                ['Cases', count(seen.total) + ', of which ' + count(seen.valid) + ' can be run'],
                ['What they run', seen.kinds.length > 0
                    ? el('ul', {class: 'plain-list'}, seen.kinds.map(kind => el('li', null, count(kind.count) + ' x ' + kind.what))) : null],
                ['Declare an expected outcome', count(seen.withExpectation) + ' of ' + count(seen.total)],
                ['Writes', seen.writeNote ? seen.writeNote : count(seen.writes)],
                ['Columns read', seen.columns.length > 0 ? seen.columns.map((name, index) => [index > 0 ? ', ' : null, code(name)]) : null]
            ], 'facts--ruled'),
            seen.rowProblems.length > 0 ? el('div', null,
                el('p', {class: 'sub-heading'}, 'Rows that will be reported as not valid ('
                    + count(seen.rowProblemCount) + (seen.rowProblemCount > seen.rowProblems.length ? ', the first ' + seen.rowProblems.length + ' listed' : '') + ')'),
                table([{text: 'Row', class: 'num col-8'}, {text: 'Case', class: 'col-18'}, 'Why'], seen.rowProblems.map(row => [
                    {class: 'num', node: String(row.row)}, code(row.caseId),
                    el('ul', {class: 'plain-list'}, row.problems.map(text => el('li', null, text)))]))) : null);
    }

    /* ---------- starting, following and stopping a run ---------- */

    async function startRun() {
        clearFieldErrors(form);
        clear(problem);
        if (file.files.length === 0) {
            return;
        }
        const data = new FormData();
        data.append('file', file.files[0]);
        if (testerRef.value() !== '') {
            data.append('testerRef', testerRef.value());
        }
        if (compare.value() !== '') {
            data.append('compareWith', compare.value());
        }
        // The configuration the preview was drawn from. The server refuses the run if it has changed since.
        data.append('configEpoch', String(app.epoch()));
        start.disabled = true;
        const answer = await api.postForm('/api/suites', data);
        start.disabled = false;
        if (answer.kind === 'ok') {
            show(answer.body);
            runBox.scrollIntoView({block: 'start'});
            loadEarlier();
            return;
        }
        if (answer.kind === 'form') {
            fill(problem, formProblem(answer.body, markFieldErrors([form], answer.body.fieldErrors)));
            return;
        }
        fill(problem, errorPanel(answer.body));
        if (answer.kind === 'conflict') {
            app.refreshMode();
        }
    }

    function show(view) {
        run = view;
        filter = null;
        lastDone = view.counts ? view.counts.done : -1;
        drawRun();
        follow();
    }

    function follow() {
        window.clearTimeout(timer);
        if (!run || run.state !== 'RUNNING') {
            return;
        }
        timer = window.setTimeout(async () => {
            if (!run) {
                return;
            }
            // Without the rows while waiting: the count is all that changes between two cases.
            const brief = await api.get('/api/suites/' + encodeURIComponent(run.suiteRunId) + '?rows=false');
            if (brief.kind !== 'ok') {
                fill(runBox, errorPanel(brief.body));
                return;
            }
            if (brief.body.counts.done !== lastDone || brief.body.state !== 'RUNNING') {
                await open(run.suiteRunId, true);
            } else {
                follow();
            }
        }, POLL_MILLIS);
    }

    async function open(suiteRunId, keepFilter) {
        const answer = await api.get('/api/suites/' + encodeURIComponent(suiteRunId));
        if (answer.kind !== 'ok') {
            run = null;
            fill(runBox, errorPanel(answer.body));
            return;
        }
        const kept = keepFilter ? filter : null;
        run = answer.body;
        filter = kept;
        lastDone = run.counts.done;
        drawRun();
        if (run.counts.notRecorded > 0) {
            app.ledgerProblem('A suite run has runs the ledger did not keep.');
        } else if (run.counts.done > 0) {
            // The suite view carries no count of today's runs, so the indicator says recorded and no more.
            app.ledgerReceipt({recorded: true});
        }
        if (run.state === 'RUNNING') {
            follow();
        } else {
            loadEarlier();
        }
    }

    async function stop() {
        const answer = await api.post('/api/suites/' + encodeURIComponent(run.suiteRunId) + '/stop');
        if (answer.kind !== 'ok') {
            fill(problem, errorPanel(answer.body));
            return;
        }
        await open(run.suiteRunId, true);
    }

    /* ---------- the run on screen ---------- */

    function drawRun() {
        if (!run) {
            fill(runBox, el('div', {class: 'empty-state'},
                el('p', {class: 'empty-state__lead'}, 'No suite run is open.'),
                el('p', null, 'Choose a suite file above. The tool says what it would run and what is wrong with the file before anything is sent. '
                    + 'Then choose "Run suite", or open one of the runs the tool still holds.')));
            return;
        }
        const counts = run.counts;
        const environment = run.environment || {};
        const rows = run.results || [];
        const running = run.state === 'RUNNING';
        const visible = filter ? rows.filter(filter.test) : rows;

        const head = el('div', {class: 'suite-head'},
            el('h2', {class: 'suite-head__headline', aria: {live: 'polite'}}, run.headline),
            el('div', {class: 'suite-progress'},
                el('progress', {max: counts.total, value: counts.done, aria: {label: 'Cases done'}}),
                el('span', {class: 'suite-progress__count', role: 'status', aria: {live: 'polite'}},
                    count(counts.done) + ' of ' + count(counts.total) + ' cases done'),
                running ? button('Stop suite', stop, {class: 'button--quiet'}) : null),
            run.stopReason ? el('p', {class: 'suite-head__stop'}, run.stopReason) : null);

        const about = el('div', {class: 'facts-pair'},
            facts([
                ['Suite run', [code(run.suiteRunId), ' ', copyButton('Copy suite run id', () => run.suiteRunId)]],
                ['File', [run.name, run.format ? ' (' + run.format + ')' : null]],
                ['Ran against', [environment.active ? environment.name + ' (' + environment.kind + ')' : 'no environment',
                    ', ', run.mode, ' profile, target ', run.target]]
            ], 'facts--ruled'),
            facts([
                ['Started', when(run.startedAt)],
                ['Finished', run.finishedAt ? when(run.finishedAt) : (running ? 'still running' : null)],
                ['Tester reference', run.testerRef || el('span', {class: 'presence presence--none'}, 'none given')],
                ['Compared with', run.comparedWith ? code(run.comparedWith) : null]
            ], 'facts--ruled'));

        const notes = run.notes && run.notes.length > 0 ? el('ul', {class: 'note-list'}, run.notes.map(text => el('li', null, text))) : null;

        fill(runBox, head, about, notes, summary(counts, run.findingCounts || {}, rows.length),
            resultsSection(visible, rows.length), timingSection(run.timing),
            el('p', null, el('a', {class: 'button button--quiet', href: '/api/suites/' + encodeURIComponent(run.suiteRunId) + '/results.csv',
                download: ''}, 'Export results as CSV')));
    }

    /** The counts, as buttons that filter the table. A count of zero is not offered. */
    function summary(counts, findingCounts, total) {
        const choose = (candidate) => () => {
            filter = filter && candidate && filter.key === candidate.key ? null : candidate;
            drawRun();
        };
        const chip = (candidate, label, value) => button([label, ' ', el('strong', null, count(value))], choose(candidate), {
            class: 'button--quiet button--small filter', aria: {pressed: String(Boolean(candidate ? filter && filter.key === candidate.key : !filter))}
        });
        const chips = [chip(null, 'Every case', total)];
        for (const [key, label, test] of COUNT_FILTERS) {
            if (counts[key] > 0) {
                chips.push(chip({key, label, test}, label, counts[key]));
            }
        }
        const kinds = Object.entries(findingCounts).map(([kind, value]) =>
            chip({key: 'finding:' + kind, label: kind, test: row => (row.findings || []).includes(kind)}, code(kind), value));
        return el('section', {class: 'result__section'},
            heading(3, 'Summary'),
            el('p', {class: 'quiet'}, 'Choose a count to show only its cases. Choose it again to show every case.'),
            el('div', {class: 'filters', role: 'group', aria: {label: 'Show only'}}, chips),
            kinds.length > 0 ? el('div', {class: 'filters', role: 'group', aria: {label: 'Show only the cases with this finding'}},
                el('span', {class: 'quiet small'}, 'Findings, by kind:'), kinds) : null);
    }

    function resultsSection(rows, total) {
        const compared = Boolean(run.comparedWith);
        const headings = [{text: 'Row', class: 'num'}, 'Case', 'Runs', 'Expected', 'Outcome', 'Test result', {text: 'Elapsed', class: 'num'}, 'Findings'];
        if (compared) {
            headings.push('Last time');
        }
        headings.push('Where the case stands, and its run');
        const body = rows.map(row => resultRow(row, compared));
        return el('section', {class: 'result__section'},
            heading(3, filter ? 'Results: ' + filter.label : 'Results', filter ? rows.length + ' of ' + total : total),
            rows.length === 0 ? el('p', {class: 'quiet'}, 'No case matches this count.')
                : el('div', {class: 'table-scroll'}, el('table', {class: 'grid suite-results' + (compared ? ' suite-results--compared' : '')},
                    el('thead', null, el('tr', null, headings.map(item => typeof item === 'string'
                        ? el('th', {scope: 'col'}, item) : el('th', {scope: 'col', class: item.class}, item.text)))),
                    el('tbody', null, body))));
    }

    function resultRow(row, compared) {
        const none = text => el('span', {class: 'presence presence--none'}, text);
        const sent = Boolean(row.outcome);
        const cells = [
            {class: 'num', node: String(row.row)},
            code(row.caseId),
            row.what || none('not stated'),
            row.expected ? code(row.expected) : none('none declared'),
            sent ? outcome(row.outcomeLabel || row.outcome, row.tone) : none('no outcome'),
            row.result ? stamp(row.result) : none('not compared'),
            {class: 'num', node: typeof row.elapsedMillis === 'number' ? [millis(row.elapsedMillis), row.slow ? el('div', null, slowFlag()) : null] : none('no call')},
            row.findings && row.findings.length > 0 ? el('ul', {class: 'plain-list'}, row.findings.map(kind => el('li', null, code(kind)))) : el('span', {class: 'quiet'}, 'none')
        ];
        if (compared) {
            cells.push(row.previousOutcome === undefined && row.previousResult === undefined
                ? none('not in the earlier run')
                : [row.previousOutcomeLabel || (row.previousOutcome ? code(row.previousOutcome) : none('no outcome')),
                    row.previousResult ? el('div', null, stamp(row.previousResult)) : null,
                    row.changed === true ? el('div', null, el('span', {class: 'flag'}, 'changed')) : null]);
        }
        // A row that was not sent says so here in the server's sentence, with the reason. It has no
        // outcome and no test result, so it cannot be read as a failure.
        // Until the Runs view exists the run id is text to copy. Seam: make it a link to History, Runs, by run id.
        cells.push([
            el('div', {class: row.status && row.status !== 'COMPLETED' ? 'suite-status suite-status--unsent' : 'suite-status'}, row.statusLine),
            row.reason ? el('div', null, row.reason) : null,
            (row.notes || []).map(text => el('div', {class: 'small quiet'}, text)),
            (row.notRecorded || []).map(text => el('div', {class: 'ledger--missing small'}, el('strong', null, 'NOT recorded. '), text)),
            (row.runIds || []).map(id => el('div', {class: 'suite-run-id'}, code(id, 'literal--id'), ' ', copyButton('Copy', () => id)))
        ]);
        return tableRow({cells, class: row.changed === true ? 'suite-row suite-row--changed' : 'suite-row'});
    }

    /** The response times of the calls the service answered: the figures, and the ranges as plain bars. */
    function timingSection(timing) {
        if (!timing) {
            return null;
        }
        const buckets = timing.buckets || [];
        const most = buckets.reduce((largest, bucket) => Math.max(largest, bucket.count), 0);
        const figure = (label, value) => [label, typeof value === 'number' ? millis(value) : el('span', {class: 'presence presence--none'}, 'no call answered')];
        return el('section', {class: 'result__section'},
            heading(3, 'Response time'),
            el('p', {class: 'quiet'}, count(timing.answered) + ' calls answered, ' + count(timing.timedOut) + ' timed out, '
                + count(timing.slow) + ' slower than the threshold of ' + millis(timing.thresholdMillis) + '.'),
            el('div', {class: 'timing'},
                facts([figure('Fastest', timing.minMillis), figure('Median', timing.medianMillis), figure('90th percentile', timing.p90Millis),
                    figure('95th percentile', timing.p95Millis), figure('Slowest', timing.maxMillis), figure('Mean', timing.meanMillis)], 'facts--ruled'),
                buckets.length === 0 ? null : el('table', {class: 'grid timing__buckets'},
                    el('thead', null, el('tr', null, el('th', {scope: 'col'}, 'Range'), el('th', {scope: 'col', class: 'num'}, 'Calls'), el('th', {scope: 'col'}, ''))),
                    el('tbody', null, buckets.map(bucket => el('tr', null,
                        el('td', null, bucket.label),
                        el('td', {class: 'num'}, count(bucket.count)),
                        el('td', {class: 'timing__bar-cell'}, el('meter', {class: 'timing__bar', min: 0, max: Math.max(most, 1), value: bucket.count,
                            aria: {label: bucket.label + ': ' + bucket.count + ' calls'}}))))))));
    }

    /* ---------- the runs the tool still holds ---------- */

    async function loadEarlier() {
        const answer = await api.get('/api/suites');
        if (answer.kind !== 'ok') {
            fill(earlier, errorPanel(answer.body));
            return;
        }
        const runs = answer.body.runs || [];
        setOptions(compare.select, [{value: '', label: 'No comparison'}].concat(runs.filter(item => item.state !== 'RUNNING').map(item => ({
            value: item.suiteRunId, label: item.name + ', ' + new Date(item.startedAt).toLocaleString() + ': ' + item.headline
        }))));
        if (runs.length === 0) {
            fill(earlier, el('p', {class: 'quiet'}, 'None yet. The tool holds its latest suite runs until it restarts; every case stays in the ledger.'));
            return;
        }
        fill(earlier, el('ul', {class: 'suite-list'}, runs.map(item => el('li', null,
            button(item.name, () => open(item.suiteRunId, false), {class: 'button--text'}),
            el('div', {class: 'small quiet'}, when(item.startedAt)),
            el('div', {class: 'small'}, item.headline)))));
    }

    return {
        node,
        /** Called when the view is shown. */
        show() {
            loadEarlier();
            follow();
        },
        /** Called when the configuration changes. What the file would run against may have changed with it. */
        onConfigChange() {
            if (file.files.length > 0 && previewEpoch !== app.epoch()) {
                preview();
            }
            loadEarlier();
        }
    };
}
