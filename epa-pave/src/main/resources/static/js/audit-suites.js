// Audit > Suites: a file of cases, previewed, run one after another in the background, followed
// while it runs, and read back as a results table for sign-off.
//
// Every sentence about a row is the server's (statusLine, reason, notes, headline): this page draws
// it and keeps no copy. A row that was blocked, not valid, not configured or not run says exactly
// that and has no test result, so it is never drawn as a failure. The file is read when "Preview the
// suite" is pressed (files.js, R-33) and its text is posted as JSON; "Run suite" sends the text that
// was previewed, so what runs is what the preview described.

import {el, fill, clear, chip, kv, fold, button, field, copyButton, setFieldError} from './dom.js';
import {call} from './api.js';
import {app} from './app.js';
import {errorPanel, faultReport, withoutReference} from './fault.js';
import {readText} from './files.js';
import {vocabulary} from './words.js';
import {wordsOf, runLink, none, testChip, utcText} from './audit-common.js';
import {LEDGER_TEXT_HINT} from './wb-case.js';

const POLL_MILLIS = 1000;

// The server reads a suite file of at most 5 MB (SuiteFileReader); the page refuses a larger one
// before sending it, in the words of files.js.
const MOST_BYTES = 5 * 1024 * 1024;

// The CSV template: the columns the server reads, and rows that name only the mock profile's test
// member IDs (P32H-...) and the test NPI, so nothing in it can be taken for a real member.
export const TEMPLATE_ROWS = [
    ['caseId', 'protocol', 'hook', 'memberId', 'npi', 'procedureCodes', 'dateOfService', 'expected', 'scenarioId', 'testerRef', 'note'],
    ['TC-001', 'crd', 'order-sign', 'P32H-NO-AUTH', '1234567893', '95811', '2026-01-15', 'NO_AUTH_REQUIRED', '', 'QA-1', 'Expects no prior authorization'],
    ['TC-002', 'crd', 'order-sign', 'P32H-PA-REQ', '1234567893', '95810', '2026-01-15', 'AUTH_REQUIRED', '', 'QA-1', 'Expects prior authorization'],
    ['TC-003', 'crd', 'order-select', 'P32H-DOC-DTR', '1234567893', '95810', '2026-01-15', '', '', 'QA-1', 'Declares nothing so it is unverified']
];

/** The template as the text of a CSV file, CRLF line ends, as a spreadsheet writes one. */
export function templateText() {
    return TEMPLATE_ROWS.map(row => row.join(',')).join('\r\n') + '\r\n';
}

// A table cell keeps to one line (pave.css); its content wraps inside a block of its own, so a long sentence
// of the server's takes the width that is left instead of pushing the table past the window.
const wrapping = (...children) => el('td', null, el('div', {class: 'config-wrap'}, children));

const shortId = id => String(id).length > 16 ? String(id).slice(0, 12) + '...' : String(id);

const plural = (number, one, many) => Number(number || 0).toLocaleString('en-US') + ' ' + (number === 1 ? one : many);

/** Where a preview or a run goes: "<environment> (<kind>), <mode>". */
function placeOf(view) {
    const environment = view.environment || null;
    const where = environment && environment.name ? environment.name + (environment.kind ? ' (' + String(environment.kind).toLowerCase() + ')' : '')
        : 'no environment active';
    return where + (view.mode ? ', ' + view.mode : '');
}

/** The view of Audit > Suites. route: the parts after '#audit' ('suites', and optionally a suite run id). */
export function mount(root, route) {
    const page = el('div', {class: 'audit-suites'});
    root.append(page);

    let mounted = true;
    let words = wordsOf(null);
    let held = null;          // {text, fileName, epoch} of the preview a run would send, or null
    let run = null;           // the suite run on screen
    let filter = null;        // {key, label, test} or null for every row
    let timer = null;
    let lastDone = -1;
    let sending = false;

    const file = el('input', {type: 'file', class: 'input', accept: '.csv,.json,text/csv,application/json'});
    const fileField = field({label: 'Suite file', input: file});
    const testerRef = el('input', {class: 'input', type: 'text', name: 'testerRef', autocomplete: 'off', placeholder: 'ticket or initials'});
    const compare = el('select', {class: 'select', name: 'compareWith'}, el('option', {value: ''}, 'No comparison'));
    const previewButton = button('Preview the suite', preview);
    const runButton = button('Run suite', startRun, {primary: true, disabled: true});
    const why = el('span', {class: 'muted small', role: 'status'});
    const templateLink = el('a', {class: 'btn btn--quiet', href: '#', download: 'pave-suite-template.csv',
        title: 'Columns and three rows with the mock profile\'s test member IDs only.'}, 'CSV template');
    const previewBox = el('div', {'aria-live': 'polite'});
    const problem = el('div');
    const runBox = el('div');
    const earlier = el('div');

    fill(page,
        el('div', {class: 'page-head'}, el('h1', {class: 'page-head__title'}, 'Suites'),
            el('span', {class: 'page-head__note'}, 'A file of cases, run one after another'),
            el('div', {class: 'page-head__end'}, templateLink)),
        el('div', {class: 'filters audit-suites__start'},
            fileField,
            field({label: 'Tester reference', input: testerRef, hint: LEDGER_TEXT_HINT}),
            field({label: 'Compare with an earlier suite run', input: compare}),
            el('div', {class: 'inline-controls'}, previewButton, runButton)),
        why, problem, previewBox, runBox,
        el('div', {class: 'section audit-suites__held'},
            el('div', {class: 'section__head'}, el('h2', {class: 'section__title'}, 'Suite runs PAVE holds')),
            earlier));

    templateLink.addEventListener('click', () => {
        // Made when asked for, from the rows held above: nothing is fetched and nothing leaves the machine.
        const address = URL.createObjectURL(new Blob([templateText()], {type: 'text/csv'}));
        templateLink.href = address;
        setTimeout(() => URL.revokeObjectURL(address), 60000);
    });
    file.addEventListener('change', () => {
        // Another file was chosen: what a run would send is no longer what was previewed.
        dropPreview();
    });
    const offConfig = app.on('config', () => {
        if (held) {
            dropPreview();
            fill(why, 'The configuration changed. Preview the suite again before running it.');
        }
        loadEarlier();
    });

    vocabulary().then(found => {
        words = wordsOf(found);
        if (run && mounted) {
            drawRun();
        }
    }, () => {
        // Without the short words a code is shown as the code it is.
    });
    drawRun();
    loadEarlier();
    openFromRoute(route);

    function dropPreview() {
        held = null;
        clear(previewBox);
        clear(problem);
        clear(why);
        setFieldError(fileField, null);
        drawStart();
    }

    function openFromRoute(parts) {
        const rest = Array.isArray(parts) ? parts : [];
        const id = rest[0] === 'suites' && rest.length > 1 ? rest[1] : null;
        if (id && (!run || run.suiteRunId !== id)) {
            open(id, false);
        }
    }

    /* ---------- what PAVE makes of the file ---------- */

    async function preview() {
        dropPreview();
        previewButton.disabled = true;
        fill(why, 'Reading the file...');
        const got = await readText(file, {maxBytes: MOST_BYTES, source: 'csv'});
        if (!got.ok) {
            previewButton.disabled = false;
            clear(why);
            setFieldError(fileField, got.line);
            return;
        }
        const epoch = app.epoch();
        const answer = await call('POST', '/api/suites/preview', {body: suiteBody(got.fileName, got.text, epoch)});
        previewButton.disabled = false;
        clear(why);
        if (!mounted) {
            return;
        }
        if (!answer.ok) {
            fill(previewBox, errorPanel(answer.error));
            return;
        }
        const seen = answer.data || {};
        if (seen.runnable === true) {
            held = {text: got.text, fileName: got.fileName, epoch};
        }
        drawStart();
        fill(previewBox, previewCard(seen));
    }

    function previewCard(seen) {
        const problems = Array.isArray(seen.problems) ? seen.problems : [];
        const rowProblems = Array.isArray(seen.rowProblems) ? seen.rowProblems : [];
        const kinds = Array.isArray(seen.kinds) ? seen.kinds : [];
        const columns = Array.isArray(seen.columns) ? seen.columns : [];
        return el('div', {class: 'section audit-suites__preview'},
            el('div', {class: 'section__head'}, el('h2', {class: 'section__title'}, seen.headline || 'The file was read.'),
                chip(seen.runnable === true ? 'Can run' : 'Cannot run', {tone: seen.runnable === true ? 'clear' : 'stop'})),
            problems.length > 0 ? el('ul', {class: 'notes'}, problems.map(text => el('li', null, text))) : null,
            kv([
                {key: 'File', value: (seen.name || '') + (seen.format ? ' (' + seen.format + ')' : '')},
                {key: 'Would run against', value: placeOf(seen)},
                {key: 'Cases', value: plural(seen.total, 'case', 'cases') + ', ' + Number(seen.valid || 0).toLocaleString('en-US') + ' can run'},
                kinds.length > 0 ? {key: 'What they run', value: kinds.map(kind => kind.count + ' x ' + kind.what).join(', ')} : null,
                {key: 'Expected outcome declared', value: (seen.withExpectation || 0) + ' of ' + (seen.total || 0)},
                {key: 'Writes', value: seen.writeNote ? seen.writeNote : plural(seen.writes, 'case', 'cases')},
                columns.length > 0 ? {key: 'Columns read', value: el('span', {class: 'mono'}, columns.join(', '))} : null
            ]),
            rowProblems.length > 0 ? fold({
                summary: 'Rows reported as not valid',
                hint: String(seen.rowProblemCount || rowProblems.length) + (seen.rowProblemCount > rowProblems.length ? ', the first ' + rowProblems.length + ' listed' : ''),
                open: true,
                body: el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
                    el('thead', null, el('tr', null, el('th', {scope: 'col', class: 'num'}, 'Row'), el('th', {scope: 'col'}, 'Case'),
                        el('th', {scope: 'col'}, 'Why'))),
                    el('tbody', null, rowProblems.map(row => el('tr', null,
                        el('td', {class: 'num'}, String(row.row)),
                        el('td', null, el('span', {class: 'mono'}, row.caseId || '')),
                        wrapping(el('ul', {class: 'notes'}, (row.problems || []).map(text => el('li', null, text)))))))))
            }) : null);
    }

    /* ---------- starting, following and stopping a run ---------- */

    // The server reads the preview and the run as one record (SuiteFileRequest), so both posts are
    // built here and cannot drift apart; the preview uses only the file name and the text today.
    function suiteBody(fileName, text, epoch) {
        const ref = String(testerRef.value || '').trim();
        return {fileName, text, testerRef: ref === '' ? null : ref, compareWith: compare.value ? compare.value : null, configEpoch: epoch};
    }

    function drawStart() {
        const busy = Boolean(run) && run.state === 'RUNNING';
        runButton.disabled = sending || busy || !held;
        runButton.title = busy ? 'A suite is running. Stop it, or wait for it to end, before starting another.'
            : (!held ? 'Preview a suite file that can run first.' : '');
    }

    async function startRun() {
        if (!held || sending) {
            return;
        }
        sending = true;
        drawStart();
        clear(problem);
        const answer = await call('POST', '/api/suites', {body: suiteBody(held.fileName, held.text, held.epoch)});
        sending = false;
        if (!mounted) {
            return;
        }
        if (!answer.ok) {
            drawStart();
            fill(problem, errorPanel(answer.error));
            return;
        }
        show(answer.data || {});
        loadEarlier();
    }

    function show(view) {
        run = view;
        filter = null;
        lastDone = view.counts ? view.counts.done : -1;
        drawRun();
        follow();
    }

    function follow() {
        clearTimeout(timer);
        timer = null;
        if (!mounted || !run || run.state !== 'RUNNING') {
            return;
        }
        timer = setTimeout(async () => {
            if (!mounted || !run) {
                return;
            }
            // Without the rows while waiting: the count is all that changes between two cases.
            const brief = await call('GET', '/api/suites/' + encodeURIComponent(run.suiteRunId), {query: {rows: 'false'}});
            if (!mounted || !run) {
                return;
            }
            if (!brief.ok) {
                fill(problem, errorPanel(brief.error));
                return;
            }
            const now = brief.data || {};
            if (!now.counts || now.counts.done !== lastDone || now.state !== 'RUNNING' || now.headline !== run.headline) {
                await open(run.suiteRunId, true);
            } else {
                follow();
            }
        }, POLL_MILLIS);
    }

    async function open(suiteRunId, keepFilter) {
        const answer = await call('GET', '/api/suites/' + encodeURIComponent(suiteRunId));
        if (!mounted) {
            return;
        }
        if (!answer.ok) {
            run = null;
            drawStart();
            fill(runBox, errorPanel(answer.error));
            return;
        }
        const kept = keepFilter ? filter : null;
        run = answer.data || {};
        filter = kept;
        lastDone = run.counts ? run.counts.done : -1;
        drawRun();
        if (run.state !== 'RUNNING') {
            loadEarlier();
        }
        follow();
    }

    async function stop(control) {
        control.disabled = true;
        const answer = await call('POST', '/api/suites/' + encodeURIComponent(run.suiteRunId) + '/stop');
        if (!mounted) {
            return;
        }
        if (!answer.ok) {
            control.disabled = false;
            fill(problem, errorPanel(answer.error));
            return;
        }
        await open(run.suiteRunId, true);
    }

    /* ---------- the run on screen ---------- */

    function drawRun() {
        drawStart();
        if (!run) {
            clear(runBox);
            return;
        }
        const counts = run.counts || {total: 0, done: 0};
        const rows = Array.isArray(run.results) ? run.results : [];
        const running = run.state === 'RUNNING';
        const stopFault = {reference: run.stopReference, cause: run.stopCause};
        const stopControl = running ? button(run.stopping === true ? 'Stopping the suite...' : 'Stop suite', () => stop(stopControl),
            {disabled: run.stopping === true}) : null;
        const unrecorded = counts.notRecorded > 0;
        fill(runBox,
            el('div', {class: 'section audit-suites__run'},
                el('div', {class: 'section__head'},
                    el('h2', {class: 'section__title'}, run.headline || ''),
                    el('div', {class: 'section__end inline-controls'},
                        el('progress', {max: Math.max(counts.total, 1), value: counts.done, 'aria-label': 'Cases done'}),
                        el('span', {class: 'small', role: 'status'}, counts.done + ' of ' + counts.total + ' cases done'),
                        stopControl,
                        el('a', {class: 'btn', href: '/api/suites/' + encodeURIComponent(run.suiteRunId) + '/results.csv', download: ''}, 'Results CSV'))),
                unrecorded ? el('div', {class: 'line line--alarm'}, el('p', {class: 'line__text'},
                    plural(counts.notRecorded, 'run of this suite was NOT recorded in the ledger.', 'runs of this suite were NOT recorded in the ledger.')
                    + ' Download the results CSV now: this page and PAVE hold them only until PAVE stops.')) : null,
                run.stopReason ? el('p', null, withoutReference(run.stopReason, stopFault)) : null,
                run.stopReason ? faultReport(run.stopReference, run.stopCause) : null,
                kv([
                    {key: 'Suite run', value: [el('span', {class: 'mono'}, run.suiteRunId || ''), ' ',
                        copyButton('Copy suite run id', () => run.suiteRunId, {small: true}), ' ',
                        el('a', {class: 'table__link', href: '#audit/?suiteRunId=' + encodeURIComponent(run.suiteRunId || '')}, 'Its runs in Runs')]},
                    {key: 'File', value: (run.name || '') + (run.format ? ' (' + run.format + ')' : '')},
                    {key: 'Ran against', value: placeOf(run)},
                    {key: 'Started', value: utcText(run.startedAt) || none('not known')},
                    {key: 'Ended', value: run.finishedAt ? utcText(run.finishedAt) || run.finishedAt : none(running ? 'still running' : 'not known')},
                    run.testerRef ? {key: 'Tester reference', value: run.testerRef} : null,
                    run.comparedWith ? {key: 'Compared with', value: el('span', {class: 'mono'}, run.comparedWith)} : null
                ]),
                Array.isArray(run.notes) && run.notes.length > 0 ? el('ul', {class: 'notes'}, run.notes.map(text => el('li', null, text))) : null,
                chips(rows),
                resultsTable(rows),
                timingFold(run.timing)));
    }

    /** The counts, as buttons that filter the table. A count of zero is not offered. */
    function chips(rows) {
        const counts = run.counts || {};
        const choose = candidate => () => {
            filter = filter && candidate && filter.key === candidate.key ? null : candidate;
            drawRun();
        };
        const pressed = candidate => String(candidate ? Boolean(filter) && filter.key === candidate.key : !filter);
        const one = (candidate, label, value) => {
            const control = button([label, ' ', el('strong', null, String(value))], choose(candidate), {small: true, quiet: true});
            control.setAttribute('aria-pressed', pressed(candidate));
            return control;
        };
        const list = [one(null, 'Every case', rows.length)];
        for (const group of Array.isArray(run.countGroups) ? run.countGroups : []) {
            if (group && group.count > 0) {
                list.push(one({key: 'group:' + group.group, label: group.label, test: row => row.countedIn === group.group}, group.label, group.count));
            }
        }
        const extra = [
            ['withFindings', 'With findings', row => Array.isArray(row.findings) && row.findings.length > 0],
            ['slow', 'Slow', row => row.slow === true],
            ['changed', 'Changed since the earlier run', row => row.changed === true],
            ['notRecorded', 'Runs NOT recorded', row => Array.isArray(row.notRecorded) && row.notRecorded.length > 0]
        ];
        for (const [key, label, test] of extra) {
            const value = typeof counts[key] === 'number' ? counts[key] : rows.filter(test).length;
            if (value > 0) {
                list.push(one({key, label, test}, label, value));
            }
        }
        return el('div', {class: 'inline-controls audit-suites__counts', role: 'group', 'aria-label': 'Show only'}, list);
    }

    function resultsTable(rows) {
        const visible = filter ? rows.filter(filter.test) : rows;
        if (rows.length === 0) {
            return null;
        }
        if (visible.length === 0) {
            return el('p', {class: 'muted'}, 'No case matches this count.');
        }
        const compared = Boolean(run.comparedWith);
        const heads = [['Row', 'num'], ['Case', null], ['Outcome', null], ['Test', null], ['ms', 'num']];
        if (compared) {
            heads.push(['Last time', null]);
        }
        heads.push(['Where the case stands', null], ['Findings', null]);
        return el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
            el('thead', null, el('tr', null, heads.map(([head, cls]) => el('th', {scope: 'col', class: cls}, head)))),
            el('tbody', null, visible.map(row => resultRow(row, compared)))));
    }

    function resultRow(row, compared) {
        const cells = [
            el('td', {class: 'num'}, String(row.row)),
            // What the row runs and what it declared sit under the case and the outcome, so the row's sentence keeps its width.
            el('td', null, el('div', {class: 'mono'}, row.caseId || ''), el('div', {class: 'table__sub'}, row.what || 'not stated')),
            el('td', null, row.outcome ? chip(row.outcomeLabel || row.outcome, {tone: row.tone, title: row.outcome}) : none('no outcome'),
                el('div', {class: 'table__sub'}, 'expected ', row.expected ? expectedWords(row.expected) : 'none declared')),
            el('td', null, row.result ? testChip(words, row.result) : none('not compared')),
            el('td', {class: 'num'}, typeof row.elapsedMillis === 'number'
                ? [String(row.elapsedMillis), row.slow === true ? [' ', chip('slow', {tone: 'action'})] : null]
                // After a failure of this tool a call may have left unmeasured, so it is not called no call.
                : none(row.faultReference ? 'not known' : 'no call')),
        ];
        if (compared) {
            cells.push(el('td', null, row.previousOutcome === undefined && row.previousResult === undefined
                ? none('not in the earlier run')
                : [row.previousOutcomeLabel || row.previousOutcome || none('no outcome'),
                    row.previousResult ? [' ', testChip(words, row.previousResult)] : null,
                    row.changed === true ? [' ', chip('changed', {tone: 'action'})] : null]));
        }
        // The case that was running when this tool ended the suite failed with the stop itself: its
        // reference and cause stand once, under the stop, and the row points there.
        const rowFault = {reference: row.faultReference, cause: row.faultCause};
        const sameAsStop = Boolean(rowFault.reference) && rowFault.reference === run.stopReference;
        const shownFault = sameAsStop ? {reference: rowFault.reference} : rowFault;
        cells.push(wrapping(
            el('div', null, withoutReference(row.statusLine, shownFault)),
            row.reason ? el('div', {class: 'small'}, withoutReference(row.reason, shownFault)) : null,
            sameAsStop ? el('div', {class: 'small muted'}, 'The same failure as the stop above.') : faultReport(row.faultReference, row.faultCause),
            (Array.isArray(row.notes) ? row.notes : []).map(text => el('div', {class: 'small muted'}, text)),
            (Array.isArray(row.notRecorded) ? row.notRecorded : []).map(text => el('div', {class: 'small'}, el('strong', null, 'NOT recorded. '), text)),
            runIdsOf(row)));
        // Findings last and wrapping: their codes are long, and the row's own sentence matters more.
        cells.push(wrapping(Array.isArray(row.findings) && row.findings.length > 0
            ? row.findings.map(kind => el('div', {class: 'mono small'}, kind)) : none('none')));
        return el('tr', null, cells);
    }

    /** What a row declared, as written: a row names no single protocol, and two protocols can word one code apart. */
    function expectedWords(expected) {
        return el('span', {class: 'mono'}, expected);
    }

    /** The run ids of a row: links when the ledger kept them, plain ids when it did not (a link would open no run). */
    function runIdsOf(row) {
        const ids = Array.isArray(row.runIds) ? row.runIds : [];
        const missing = Array.isArray(row.notRecorded) ? row.notRecorded.length : 0;
        if (ids.length === 0) {
            return null;
        }
        return el('div', {class: 'small'}, ids.map((id, index) => [index > 0 ? ' ' : null,
            // The start of the id is enough to tell the runs of a row apart; the whole id is in the title, the link and the CSV.
            missing >= ids.length ? el('span', {class: 'mono', title: id + ' (not in the ledger)'}, shortId(id))
                : runLink(id, el('span', {class: 'mono', title: id}, shortId(id)))]),
            missing > 0 && missing < ids.length ? el('div', {class: 'muted'}, missing + ' of these ' + ids.length + ' runs were not recorded; a link to one of them opens no run.') : null);
    }

    /** The response times of the calls the service answered, folded: a sign-off reads it, a tester rarely does. */
    function timingFold(timing) {
        if (!timing) {
            return null;
        }
        const figure = (key, value) => ({key, value: typeof value === 'number' ? value.toLocaleString('en-US') + ' ms' : none('no call answered')});
        const buckets = Array.isArray(timing.buckets) ? timing.buckets : [];
        return fold({
            summary: 'Response time',
            hint: plural(timing.answered, 'call answered', 'calls answered') + ', ' + (timing.timedOut || 0) + ' timed out, '
                + (timing.slow || 0) + ' slower than ' + Number(timing.thresholdMillis || 0).toLocaleString('en-US') + ' ms',
            body: () => [
                kv([figure('Fastest', timing.minMillis), figure('Median', timing.medianMillis), figure('90th percentile', timing.p90Millis),
                    figure('95th percentile', timing.p95Millis), figure('Slowest', timing.maxMillis), figure('Mean', timing.meanMillis)]),
                buckets.length > 0 ? kv(buckets.map(bucket => ({key: bucket.label, value: plural(bucket.count, 'call', 'calls')}))) : null
            ]
        });
    }

    /* ---------- the suite runs PAVE still holds ---------- */

    async function loadEarlier() {
        const answer = await call('GET', '/api/suites');
        if (!mounted) {
            return;
        }
        if (!answer.ok) {
            fill(earlier, errorPanel(answer.error));
            return;
        }
        const runs = answer.data && Array.isArray(answer.data.runs) ? answer.data.runs : [];
        const chosen = compare.value;
        fill(compare, el('option', {value: ''}, 'No comparison'), runs.filter(item => item.state !== 'RUNNING').map(item =>
            el('option', {value: item.suiteRunId, selected: item.suiteRunId === chosen},
                (item.name || item.suiteRunId) + ', ' + (utcText(item.startedAt) || '') + ': ' + (item.headline || ''))));
        if (runs.length === 0) {
            fill(earlier, el('p', {class: 'muted small'}, 'None since PAVE started. PAVE holds its latest ten suite runs until it stops; '
                + 'the ledger keeps every case: filter Runs by the suite run id.'));
            return;
        }
        fill(earlier, el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
            el('thead', null, el('tr', null, el('th', {scope: 'col'}, 'Started'), el('th', {scope: 'col'}, 'File'),
                el('th', {scope: 'col'}, 'Where it stands'), el('th', {scope: 'col'}, 'Open'))),
            el('tbody', null, runs.map(item => el('tr', null,
                el('td', {class: 'mono'}, utcText(item.startedAt) || ''),
                el('td', null, item.name || ''),
                wrapping(item.headline || ''),
                el('td', null, button('Open this suite run', () => open(item.suiteRunId, false), {small: true}))))))));
    }

    return {
        update(parts) {
            openFromRoute(parts);
        },
        unmount() {
            mounted = false;
            clearTimeout(timer);
            offConfig();
            page.remove();
        }
    };
}
