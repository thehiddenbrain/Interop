// Suite runs: a file of cases, run one after another, with a results table for sign-off.
//
// The server reads the file, says what it makes of it before anything runs, runs the cases in the
// background, and words every row: what ran, where it stands, and why a row was not sent. This
// page shows that, follows the progress count, and filters the table. A row that was blocked, not
// valid, not configured, not runnable or not run is shown as exactly that, in the server's
// sentence, and never as a failure: it has no test result at all.

import * as api from './api.js';
import {el, fill, clear, button, code, facts, table, tableRow, heading, copyButton, count, millis, when, whenText} from './dom.js';
import {textField, selectField, setOptions, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {outcome, stamp, slowFlag} from './marks.js';
import {errorPanel} from './result.js';
import {runLink, suiteRunsLink} from './history-common.js';

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

export function createSuites(app, context) {
    const health = context.health;
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
    // Why Run suite cannot be chosen now, beside it, so a greyed button is never left unexplained.
    const startWhy = el('span', {class: 'small quiet suites__why'});
    let previewRunnable = false;
    const problem = el('div', {aria: {live: 'polite'}});
    // The preview and the list are long and redrawn whole, so they are not live regions. What changed is announced in a sentence.
    const previewBox = el('div', {class: 'suite-preview'});
    const earlier = el('div', {class: 'suite-earlier'});
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
        el('div', {class: 'button-row'}, start, startWhy));
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
        previewRunnable = false;
        drawStart();
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
        const problems = seen.problems || [];
        const rowProblems = seen.rowProblems || [];
        const kinds = seen.kinds || [];
        const columns = seen.columns || [];
        previewRunnable = Boolean(seen.runnable);
        drawStart();
        context.announce('File read. ' + seen.headline);
        fill(previewBox,
            el('p', {class: 'suite-preview__headline'}, seen.headline),
            problems.length > 0 ? el('div', {class: 'form-problem'},
                el('p', {class: 'form-problem__title'}, 'The file cannot be run as it is.'),
                el('ul', {class: 'note-list'}, problems.map(text => el('li', null, text)))) : null,
            facts([
                ['File', [seen.name, seen.format ? ' (' + seen.format + ')' : null]],
                ['Would run against', [environment.active ? environment.name + ' (' + environment.kind + ')' : 'no environment',
                    ', ', seen.mode, ' profile, target ', seen.target]],
                ['Cases', count(seen.total) + ', of which ' + count(seen.valid) + ' can be run'],
                ['What they run', kinds.length > 0
                    ? el('ul', {class: 'plain-list'}, kinds.map(kind => el('li', null, count(kind.count) + ' x ' + kind.what))) : null],
                ['Declare an expected outcome', count(seen.withExpectation) + ' of ' + count(seen.total)],
                // The server says in a sentence what happens to writes when there are any. With none it sends the count alone.
                ['Writes', seen.writeNote ? seen.writeNote : count(seen.writes) + (seen.writes === 1 ? ' case' : ' cases')],
                ['Columns read', columns.length > 0 ? columns.map((name, index) => [index > 0 ? ', ' : null, code(name)]) : null]
            ], 'facts--ruled'),
            rowProblems.length > 0 ? el('div', null,
                el('p', {class: 'sub-heading'}, 'Rows that will be reported as not valid ('
                    + count(seen.rowProblemCount) + (seen.rowProblemCount > rowProblems.length ? ', the first ' + rowProblems.length + ' listed' : '') + ')'),
                table([{text: 'Row', class: 'num col-8'}, {text: 'Case', class: 'col-18'}, 'Why'], rowProblems.map(row => [
                    {class: 'num', node: String(row.row)}, code(row.caseId),
                    el('ul', {class: 'plain-list'}, (row.problems || []).map(text => el('li', null, text)))]))) : null);
    }

    /* ---------- starting, following and stopping a run ---------- */

    /** Run suite is offered when a file that can run has been read and no suite run is under way. */
    function drawStart() {
        const busy = Boolean(run) && run.state === 'RUNNING';
        start.disabled = busy || !previewRunnable;
        startWhy.textContent = busy ? 'A suite is running. Stop it, or wait for it to finish, before starting another.'
            : (!previewRunnable && file.files.length > 0 ? 'This file cannot be run as it is: see above.' : '');
    }

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
        drawStart();
        if (answer.kind === 'ok') {
            context.announce('Suite started. ' + (answer.body.headline || ''));
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
            if (brief.body.counts.done !== lastDone || brief.body.state !== 'RUNNING' || brief.body.headline !== run.headline) {
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
        const wasRunning = Boolean(run) && run.suiteRunId === answer.body.suiteRunId && run.state === 'RUNNING';
        run = answer.body;
        filter = kept;
        lastDone = run.counts.done;
        drawRun();
        if (wasRunning && run.state !== 'RUNNING') {
            context.announce(run.counts.notRecorded > 0
                ? run.headline + ' ' + notRecordedSentence(run.counts.notRecorded) : run.headline);
        }
        if (run.counts.notRecorded > 0) {
            app.ledgerProblem('A suite run has runs the ledger did not keep.');
        } else if (run.counts.done > 0 && health) {
            // The suite view carries no count of today's runs. The ledger's own health answer does, and it updates the indicator.
            health.refresh();
        }
        // The list of runs the tool holds is read with the run, so its line for this run and the heading say the same.
        loadEarlier();
        if (run.state === 'RUNNING') {
            follow();
        }
    }

    function notRecordedSentence(number) {
        return count(number) + (number === 1 ? ' run of this suite was NOT recorded' : ' runs of this suite were NOT recorded')
            + ' in the ledger, so History does not hold ' + (number === 1 ? 'it' : 'them') + '.';
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
        drawStart();
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

        const unrecorded = counts.notRecorded > 0;
        const head = el('div', {class: 'suite-head'},
            el('h2', {class: 'suite-head__headline'}, run.headline),
            // Said as loudly as the headline: a suite whose runs were not kept cannot be traced afterwards from History.
            unrecorded ? el('div', {class: 'ledger ledger--missing suite-head__unrecorded'},
                el('p', {class: 'ledger__state'}, notRecordedSentence(counts.notRecorded)),
                el('p', null, 'The results below are on this page only, and only until the tool restarts. '
                    + 'Export them as CSV now if you need them. A run id below that was not recorded opens no run. '
                    + 'Each row quotes what the ledger said.')) : null,
            el('div', {class: 'suite-progress'},
                el('progress', {max: counts.total, value: counts.done, aria: {label: 'Cases done'}}),
                el('span', {class: 'suite-progress__count', role: 'status', aria: {live: 'polite'}},
                    count(counts.done) + ' of ' + count(counts.total) + ' cases done'),
                running ? button('Stop suite', stop, {class: 'button--quiet'}) : null,
                // The export is the server's own file: every case as it stands when the link is used.
                el('a', {class: 'button button--quiet', href: '/api/suites/' + encodeURIComponent(run.suiteRunId) + '/results.csv', download: ''},
                    'Export results as CSV')),
            run.stopReason ? el('p', {class: 'suite-head__stop'}, run.stopReason) : null);

        const about = el('div', {class: 'facts-pair'},
            facts([
                ['Suite run', [code(run.suiteRunId), ' ', copyButton('Copy suite run id', () => run.suiteRunId),
                    el('div', {class: 'small'}, unrecorded
                        ? [suiteRunsLink(run.suiteRunId, 'List the runs of it the ledger kept'), ', if any: '
                            + count(counts.notRecorded) + ' were not kept and are not there']
                        : [suiteRunsLink(run.suiteRunId, 'List its runs in Runs'), ', where the ledger keeps them after a restart'])]],
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
            resultsSection(visible, rows.length), timingSection(run.timing));
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
        const headings = [{text: 'Row', class: 'num'}, 'Case', 'Runs', 'Expected', 'Outcome', {text: 'Test result', class: 'col-stamp'},
            {text: 'Elapsed', class: 'num col-time'}, 'Findings'];
        if (compared) {
            headings.push('Last time');
        }
        headings.push('Where the case stands, and its run');
        const body = rows.map(row => resultRow(row, compared));
        return el('section', {class: 'result__section'},
            heading(3, filter ? 'Results: ' + filter.label : 'Results', filter ? rows.length + ' of ' + total : total),
            rows.length === 0 ? el('p', {class: 'quiet'}, 'No case matches this count.')
                // The frame takes the focus so that, where the table is wider than the window, the arrow keys scroll it.
                : el('div', {class: 'table-scroll', role: 'region', tabIndex: 0, aria: {label: 'The results table'}},
                    el('table', {class: 'grid suite-results' + (compared ? ' suite-results--compared' : '')},
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
            {class: 'num', node: typeof row.elapsedMillis === 'number'
                ? [millis(row.elapsedMillis), row.slow ? el('div', null, slowFlag()) : null] : none('no call')},
            row.findings && row.findings.length > 0
                ? el('ul', {class: 'plain-list'}, row.findings.map(kind => el('li', null, code(kind)))) : el('span', {class: 'quiet'}, 'none')
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
        cells.push([
            el('div', {class: row.status && row.status !== 'COMPLETED' ? 'suite-status suite-status--unsent' : 'suite-status'}, row.statusLine),
            row.reason ? el('div', null, row.reason) : null,
            (row.notes || []).map(text => el('div', {class: 'small quiet'}, text)),
            (row.notRecorded || []).map(text => el('div', {class: 'ledger--missing small'}, el('strong', null, 'NOT recorded. '), text)),
            runIdsOf(row)
        ]);
        return tableRow({cells, class: row.changed === true ? 'suite-row suite-row--changed' : 'suite-row'});
    }

    /**
     * The run ids of a row. When the ledger kept none of them they are not links, because each
     * would open "There is no run with that id". When it kept some, the row says that some links
     * lead nowhere: the answer does not say which runs those were.
     */
    function runIdsOf(row) {
        const ids = row.runIds || [];
        const missing = (row.notRecorded || []).length;
        if (missing === 0) {
            return ids.map(id => el('div', {class: 'suite-run-id'}, runLink(id), ' ', copyButton('Copy', () => id)));
        }
        if (missing >= ids.length) {
            return ids.map(id => el('div', {class: 'suite-run-id'}, code(id, 'literal--id'), ' ',
                el('span', {class: 'small quiet'}, 'not in History'), ' ', copyButton('Copy', () => id)));
        }
        return [ids.map(id => el('div', {class: 'suite-run-id'}, runLink(id), ' ', copyButton('Copy', () => id))),
            el('div', {class: 'small quiet'}, count(missing) + ' of these ' + count(ids.length) + ' runs were not recorded, and a link to one of those opens no run.')];
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
            el('p', {class: 'quiet'}, count(timing.answered) + (timing.answered === 1 ? ' call answered, ' : ' calls answered, ')
                + count(timing.timedOut) + ' timed out, '
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
            value: item.suiteRunId, label: item.name + ', ' + whenText(item.startedAt) + ': ' + item.headline
        }))));
        if (runs.length === 0) {
            fill(earlier, el('p', {class: 'quiet'}, 'None since the tool started. The tool holds its latest ten suite runs only until it restarts. '
                + 'Every case of an earlier suite run is still in the ledger: on Runs, give its suite run id in the Suite run id filter.'));
            return;
        }
        fill(earlier, el('ul', {class: 'suite-list'}, runs.map(item => el('li', null,
            button(item.name, () => open(item.suiteRunId, false), {class: 'button--text'}),
            el('div', {class: 'small quiet'}, when(item.startedAt), ' ', suiteRunsLink(item.suiteRunId, 'its runs')),
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
