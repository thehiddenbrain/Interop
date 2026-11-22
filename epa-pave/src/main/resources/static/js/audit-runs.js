// Audit > Runs: every recorded run, newest first, with the filters GET /api/runs offers, a run opened
// by its id, the export for sign-off and the replay of the runs listed.
//
// The filters live in the address (#audit/?from=...), so the back button, a reload and a link
// return to the same list. They hold codes, days and identifiers of runs, never member data: that
// is also why a run is opened by its id and never looked up by a member ID, since whatever a filter
// holds lands in the browser's history.

import {el, button, field, fold, fill, clear, chip, modal, setFieldError} from './dom.js';
import {call} from './api.js';
import {errorPanel} from './fault.js';
import {runLink, none, outcomeChip, testChip, memberShort, replayIds, timedTotal, utcText, utcToday} from './audit-common.js';
import {replayReport} from './audit-replay.js';

// Every filter kept in the address, by the name it has there.
export const FILTER_NAMES = ['from', 'to', 'step', 'result', 'outcome', 'testerRef', 'chainId', 'suiteRunId', 'replays', 'limit', 'samples'];

const DEFAULT_LIMIT = '200';
const LIMITS = [['50', 'Newest 50'], ['200', 'Newest 200'], ['1000', 'Newest 1,000'], ['0', 'Every match']];

// The most runs one replay sends again, as the server's RunReplay.MOST_RUNS says.
const MOST_REPLAYED = 1000;

/** The filters of an address part such as '?from=2026-11-22&step=STATUS', as URLSearchParams with only the names Audit keeps. */
export function filtersOf(part) {
    const text = typeof part === 'string' && part.startsWith('?') ? part.slice(1) : '';
    const read = new URLSearchParams(text);
    const kept = new URLSearchParams();
    for (const name of FILTER_NAMES) {
        const value = read.get(name);
        if (value !== null && value !== '') {
            kept.set(name, value);
        }
    }
    return kept;
}

/**
 * The questions GET /api/runs is asked for these filters, one per query the chosen step names in
 * the vocabulary (the Status step is the inquiry and every subscription call, which no single
 * filter of the endpoint covers). Runs made by a replay are left out unless asked for. A step the
 * vocabulary does not name asks nothing, rather than listing every run under it.
 */
export function runQueries(filters, words) {
    const base = {};
    for (const name of ['from', 'to', 'result', 'outcome', 'testerRef', 'chainId', 'suiteRunId']) {
        if (filters.get(name)) {
            base[name] = filters.get(name);
        }
    }
    base.limit = filters.get('limit') || DEFAULT_LIMIT;
    base.replays = filters.get('replays') === 'include' ? 'include' : 'leave-out';
    const stepCode = filters.get('step');
    if (!stepCode) {
        return [base];
    }
    const step = words.step(stepCode);
    const queries = step && Array.isArray(step.filters) ? step.filters : [];
    return queries.map(each => Object.assign({}, base, each.protocol ? {protocol: each.protocol} : {operation: each.operation}));
}

/**
 * One list from the answers of the questions above: newest first, cut at the limit, and said to be
 * cut when any answer was or the runs together pass it. matched is the count of every run that
 * matches, or null when an answer did not count it.
 */
export function mergeLists(answers, limitText) {
    const limit = Number(limitText || DEFAULT_LIMIT);
    let runs = [];
    let truncated = false;
    let matched = 0;
    for (const answer of answers) {
        runs = runs.concat(Array.isArray(answer.runs) ? answer.runs : []);
        truncated = truncated || answer.truncated === true;
        if (matched !== null) {
            matched = answer.truncated === true ? (typeof answer.matched === 'number' ? matched + answer.matched : null) : matched + answer.count;
        }
    }
    runs.sort((a, b) => String(b.timestamp || '').localeCompare(String(a.timestamp || '')));
    if (limit > 0 && runs.length > limit) {
        runs = runs.slice(0, limit);
        truncated = true;
    }
    return {runs, truncated, matched: truncated ? matched : runs.length};
}

/** A notification delivered as a sample on the mock profile: the only notification run that names a scenario. */
export function isSample(run) {
    return run.operation === 'SUBSCRIPTION_NOTIFICATION' && Boolean(run.scenarioId);
}

/** The runs the Samples filter keeps. On live there is no such filter and every run is kept. */
export function withSamples(runs, choice, mock) {
    if (!mock || !choice) {
        return runs;
    }
    return runs.filter(run => (choice === 'only') === isSample(run));
}

/**
 * The Runs view. ctx: {app, words() (a promise of the vocabulary's lookups), go(part), ledger(node)}.
 * Returns {node, show(filters)}.
 */
export function runsView(ctx) {
    const node = el('div', {class: 'audit-runs'});
    let asked = 0;
    let listed = [];
    let current = new URLSearchParams();
    let confirmDialog = null;

    async function show(filters) {
        current = filters;
        asked += 1;
        const mine = asked;
        const words = await ctx.words();
        const queries = runQueries(filters, words);
        const [answers, health] = await Promise.all([Promise.all(queries.map(query => call('GET', '/api/runs', {query}))),
            call('GET', '/api/runs/health')]);
        if (mine !== asked) {
            // A later choice of filters was made while this answer was on its way; the later one stands.
            return;
        }
        const failed = answers.find(answer => !answer.ok);
        draw(filters, words, failed, failed ? null : mergeLists(answers.map(answer => answer.data || {}), filters.get('limit')),
            health.ok ? health.data : null);
    }

    function draw(filters, words, failed, list, health) {
        const mock = ctx.app.isMock();
        listed = list ? withSamples(list.runs, filters.get('samples'), mock) : [];
        const replaySlot = el('div', {class: 'audit-replay-slot'});
        const today = health && typeof health.runsToday === 'number' ? health.runsToday.toLocaleString('en-US') + ' today, UTC' : null;
        const exportLink = el('a', {class: 'btn', href: exportAddress(filters, words), download: '',
            title: 'Every run of the days, step and test result chosen, as CSV, runs made by a replay included.'}, 'Export CSV');
        const replayButton = button('Replay the runs listed', () => confirmReplay(words, replaySlot, replayButton), {
            disabled: replayIds(listed).length === 0 || replayIds(listed).length > MOST_REPLAYED,
            title: replayIds(listed).length > MOST_REPLAYED ? 'More than 1,000 runs are listed; narrow the list to replay them.' : null
        });
        const opened = openForm();
        fill(node,
            el('div', {class: 'page-head'}, el('h1', {class: 'page-head__title'}, 'Runs'),
                today ? el('span', {class: 'page-head__note'}, today) : null,
                el('div', {class: 'page-head__end'}, opened.node, exportLink, replayButton)),
            opened.said,
            ctx.ledger(health),
            filterForm(filters, words, mock),
            replaySlot,
            failed ? errorPanel(failed.error) : table(list, words));
    }

    function exportAddress(filters, words) {
        // The export takes the days, one protocol and the test result; a step that is one protocol is passed on as it.
        const query = new URLSearchParams();
        for (const name of ['from', 'to', 'result']) {
            if (current.get(name)) {
                query.set(name, current.get(name));
            }
        }
        const step = filters.get('step') ? words.step(filters.get('step')) : null;
        if (step && Array.isArray(step.filters) && step.filters.length === 1 && step.filters[0].protocol) {
            query.set('protocol', step.filters[0].protocol);
        }
        const text = query.toString();
        return '/api/runs/export.csv' + (text ? '?' + text : '');
    }

    /** A run opened by the id on a ticket: sent as typed, encoded, with no pattern applied to it. */
    function openForm() {
        const input = el('input', {class: 'input mono', type: 'text', name: 'runId', placeholder: 'run-...', spellcheck: false, autocomplete: 'off'});
        const wrapper = field({label: 'Run id', input});
        const said = el('div', {class: 'audit-open__said'});
        const open = button('Open the run', () => openById(input, wrapper, said), {small: true});
        input.addEventListener('keydown', event => {
            if (event.key === 'Enter') {
                event.preventDefault();
                openById(input, wrapper, said);
            }
        });
        return {node: el('div', {class: 'audit-open'}, wrapper, open), said};
    }

    async function openById(input, wrapper, said) {
        setFieldError(wrapper, null);
        clear(said);
        const typed = input.value.trim();
        if (typed === '') {
            setFieldError(wrapper, 'Type the id of a run, as a result or a ticket gives it.');
            return;
        }
        if (typed.startsWith('chain-')) {
            setFieldError(wrapper, 'This is a chain id, not a run id. Type the id of a run.');
            return;
        }
        const answer = await call('GET', '/api/runs/' + encodeURIComponent(typed));
        if (answer.ok) {
            ctx.go(['runs', typed]);
            return;
        }
        fill(said, errorPanel(answer.error));
    }

    function filterForm(filters, words, mock) {
        const apply = () => ctx.go(['?' + formQuery().toString()]);
        const text = (name, label, placeholder, mono) => {
            const input = el('input', {class: ['input', mono ? 'mono' : null], type: 'text', name, value: filters.get(name) || '',
                placeholder: placeholder || null, autocomplete: 'off'});
            input.addEventListener('change', apply);
            return {name, input, node: field({label, input})};
        };
        const select = (name, label, options) => {
            // How many has no empty choice: left out of the address, it is the default the list was asked with.
            const value = filters.get(name) || (name === 'limit' ? DEFAULT_LIMIT : '');
            const list = options.slice();
            // A value in the address the server does not list is kept as a choice, so the filter in force is always shown.
            if (value && !list.some(([code]) => code === value)) {
                list.push([value, value]);
            }
            const input = el('select', {class: 'select', name}, list.map(([code, words]) => el('option', {value: code, selected: code === value}, words)));
            input.addEventListener('change', apply);
            return {name, input, node: field({label, input})};
        };
        const step = filters.get('step') ? words.step(filters.get('step')) : null;
        const replays = el('input', {type: 'checkbox', name: 'replays', checked: filters.get('replays') === 'include'});
        replays.addEventListener('change', apply);
        const controls = [
            text('from', 'From', 'YYYY-MM-DD', true),
            text('to', 'To', 'YYYY-MM-DD', true),
            select('step', 'Step', [['', 'Any']].concat(words.steps.map(each => [each.step, each.name]))),
            select('result', 'Test', [['', 'Any']].concat(['pass', 'fail', 'unverified'].map(code => {
                const found = words.testResult(code);
                return [code, found ? found.label : code];
            }))),
            select('outcome', 'Outcome', [['', 'Any']].concat(words.outcomesOf(step).map(option => [option.code, option.short || option.label || option.code]))),
            text('testerRef', 'Tester reference', 'any')
        ];
        const more = [
            text('chainId', 'Chain id', null, true),
            text('suiteRunId', 'Suite run id', null, true),
            select('limit', 'How many', LIMITS)
        ];
        // Samples are delivered on mock only, so on live this filter is not built at all.
        const samples = mock ? select('samples', 'Samples', [['', 'Shown'], ['leave-out', 'Left out'], ['only', 'Only samples']]) : null;
        if (samples) {
            more.push(samples);
        }
        const all = controls.concat(more);

        function formQuery() {
            const query = new URLSearchParams();
            for (const control of all) {
                const value = control.input.value.trim();
                if (value !== '' && !(control.name === 'limit' && value === DEFAULT_LIMIT)) {
                    query.set(control.name, value);
                }
            }
            if (replays.checked) {
                query.set('replays', 'include');
            }
            if (query.get('step') !== filters.get('step')) {
                // An outcome of one step is no outcome of another.
                query.delete('outcome');
            }
            return query;
        }

        const anyMore = ['chainId', 'suiteRunId', 'replays', 'limit', 'samples'].some(name => filters.get(name));
        return el('div', {class: 'audit-filters'},
            el('form', {class: 'filters', 'aria-label': 'Filters', on: {submit: event => { event.preventDefault(); apply(); }}},
                controls.map(control => control.node),
                el('div', {class: 'audit-filters__clear'}, button('Clear the filters', () => ctx.go([]), {small: true, quiet: true}))),
            fold({summary: 'More filters', open: anyMore, body: el('div', {class: 'filters audit-filters__more'},
                more.map(control => control.node),
                el('label', {class: 'audit-check'}, replays, ' Include replays'))}));
    }

    function table(list, words) {
        if (listed.length === 0) {
            return el('p', {class: 'audit-empty'}, 'No runs match these filters.');
        }
        const rows = listed.map(run => {
            const step = words.stepOf(run.operation);
            const operation = words.operation(run.operation);
            const when = utcText(run.timestamp);
            const day = when ? when.slice(0, 10) : null;
            return el('tr', null,
                el('td', {class: 'audit-when'}, run.answeredBy === 'SCENARIO' ? el('span', {class: 'hatch-mark', title: 'Mock: a scenario answered'}) : null,
                    runLink(run.runId, when ? (day === utcToday() ? when.slice(11, 19) : when.slice(5, 19)) : run.runId)),
                el('td', null, step ? step.name : null, operation ? el('span', {class: 'table__sub'}, (step ? ' · ' : '') + operation.label) : null,
                    run.replayOfRunId ? [' ', chip('replay', {title: 'Made by a replay of ' + run.replayOfRunId})] : null),
                el('td', null, outcomeChip(words, run)),
                el('td', null, testChip(words, run.result)),
                el('td', {class: 'num'}, typeof run.httpStatus === 'number' ? el('span', {class: 'mono'}, String(run.httpStatus)) : null),
                el('td', {class: 'num', title: typeof run.elapsedMillis === 'number' ? null : 'Not measured'},
                    typeof run.elapsedMillis === 'number' ? String(run.elapsedMillis) : null),
                el('td', null, memberShort(run)),
                el('td', null, Array.isArray(run.procedureCodes) && run.procedureCodes.length > 0
                    ? el('span', {class: 'mono'}, run.procedureCodes.join(', ')) : null),
                el('td', {class: 'col-env'}, run.environment || null));
        });
        const heads = [['Time', null], ['Step', null], ['Outcome', null], ['Test', null], ['HTTP', 'num'], ['ms', 'num'],
            ['Member', null], ['Code', null], ['Environment', 'col-env']];
        return el('div', {class: 'audit-list'},
            el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
                el('thead', null, el('tr', null, heads.map(([head, cls]) => el('th', {scope: 'col', class: cls, title: head === 'Time' ? 'UTC' : null}, head)))),
                el('tbody', null, rows))),
            el('p', {class: 'muted small audit-list__foot'}, timedTotal(listed), '.',
                list.truncated ? ' ' + countLine(list) : null,
                current.get('replays') === 'include' ? null : ' Runs made by a replay are left out.'));
    }

    function countLine(list) {
        return typeof list.matched === 'number'
            ? 'These are the newest ' + listed.length.toLocaleString('en-US') + ' of ' + list.matched.toLocaleString('en-US')
                + ' runs that match; choose Every match under How many, or narrow the filters, to see the rest.'
            : 'More runs match; choose Every match under How many, or narrow the filters, to see them.';
    }

    /**
     * Says what a replay of the runs listed would send before anything is sent: how many, how many
     * a replay made and are not sent, how many of the rest are writes, and where they go. Counted
     * from the runs on screen, which are exactly what is sent.
     */
    function confirmReplay(words, slot, opener) {
        const ids = replayIds(listed);
        const body = el('div', {class: 'audit-confirm'});
        const fillBody = () => fill(body, confirmWords(words, ids));
        fillBody();
        const off = ctx.app.on('writes', fillBody);
        confirmDialog = modal({
            title: 'Replay the runs listed',
            body,
            footer: dialog => [
                button('Replay these runs', () => { dialog.close(); runReplay(ids, words, slot, opener); }, {primary: true}),
                button('Do not replay them', () => dialog.close())
            ],
            onClose: () => { off(); confirmDialog = null; }
        });
    }

    function confirmWords(words, ids) {
        const sent = new Set(ids);
        const originals = listed.filter(run => sent.has(run.runId));
        const made = listed.length - originals.length;
        const writes = new Map();
        for (const run of originals) {
            const operation = words.operation(run.operation);
            if (operation && operation.write === true) {
                writes.set(operation.label, (writes.get(operation.label) || 0) + 1);
            }
        }
        const writeCount = Array.from(writes.values()).reduce((sum, value) => sum + value, 0);
        const mode = ctx.app.mode() || {};
        const environment = mode.environment || {};
        const access = mode.writeAccess || {};
        const mock = ctx.app.isMock();
        const plural = (number, one, many) => number.toLocaleString('en-US') + ' ' + (number === 1 ? one : many);
        const where = environment.active ? environment.name + ' (' + environment.kind + ')' : 'the service the configuration in force names';
        return [
            el('p', null, plural(listed.length, 'run is listed. ', 'runs are listed. '),
                made > 0 ? plural(made, 'of them was made by a replay and is not sent again. ', 'of them were made by a replay and are not sent again. ') : null,
                el('strong', null, plural(ids.length, 'run is sent again', 'runs are sent again')),
                ids.length === 1 ? '.' : ', oldest first, one after another.'),
            el('p', null, writeCount === 0 ? 'None of them is a write.'
                : [el('strong', null, plural(writeCount, 'of them is a write', 'of them are writes')), ' (',
                    Array.from(writes.entries()).map(([label, number], index) => [index > 0 ? ', ' : null, number + ' x ' + label]), '). ',
                    mock ? null : access.writesAllowed === true
                        ? 'Each write is sent again as a new request to ' + where + ', where it is acted on like the first one.'
                        : ['Writes are not sent: ', access.reason || null, ' Each is reported as blocked.']]),
            el('p', null, mock ? 'This is the mock profile: the scenarios answer again and nothing leaves this machine. Each run sent again is recorded as a new run.'
                : 'The requests go to ' + where + ', against the configuration in force now. Each run sent again is recorded as a new run.')
        ];
    }

    async function runReplay(ids, words, slot, opener) {
        opener.disabled = true;
        const started = Date.now();
        const busy = el('p', {class: 'muted', role: 'status'}, 'Sending the runs again... 0 s');
        const timer = window.setInterval(() => {
            busy.textContent = 'Sending the runs again... ' + Math.round((Date.now() - started) / 1000) + ' s';
        }, 1000);
        fill(slot, busy);
        // The runs listed and nothing else: their ids, oldest first, and no range of days.
        const answer = await call('POST', '/api/runs/replay', {body: {runIds: ids}});
        window.clearInterval(timer);
        opener.disabled = false;
        if (!answer.ok) {
            fill(slot, errorPanel(answer.error));
            return;
        }
        const report = replayReport(answer.data || {}, words);
        fill(slot, report);
        const heading = report.querySelector('h2');
        if (heading) {
            heading.focus();
        }
    }

    return {
        node,
        show,
        /** The page is leaving: a confirmation still open is closed, so nothing is sent from a page no longer shown. */
        close() {
            if (confirmDialog) {
                confirmDialog.close();
            }
        }
    };
}
