// The Runs view: every recorded run, newest first, with every filter GET /api/runs offers, the
// export for sign-off, the replay of a date range, and whether the ledger is recording.
//
// The filters live in the address (#history/runs?protocol=crd&result=fail), so the back button,
// a reload and a link from elsewhere in the tool all return to the same list. They hold codes,
// dates and identifiers of runs, never member data.

import * as api from './api.js';
import {el, fill, clear, button, code, count, millis} from './dom.js';
import {textField, selectField, setOptions, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {stamp, slowFlag, resultWord} from './marks.js';
import {errorPanel} from './result.js';
import {runLink, chainLink, suiteRunsLink, none, memberShort, outcomeOf, expectedOf, whatRan, whereRan, whenFiled, utcDayNote} from './history-common.js';
import {replayReport, summaryText} from './history-replay.js';

// The filters of the list that the export takes as well, by their query names. The export takes no others.
const EXPORT_FILTERS = ['protocol', 'result'];

// Every filter of GET /api/runs, by the name the endpoint gives it, with the words for it on screen.
const FILTERS = [
    ['from', 'From'], ['to', 'To'], ['protocol', 'Protocol'], ['operation', 'Operation'], ['outcome', 'Outcome'],
    ['result', 'Test result'], ['chainId', 'Chain'], ['suiteRunId', 'Suite run']
];

const LIMITS = [{value: '200', label: 'Newest 200'}, {value: '50', label: 'Newest 50'},
    {value: '1000', label: 'Newest 1,000'}, {value: '0', label: 'Every match'}];
const DEFAULT_LIMIT = '200';

export function createRunsList(app, context) {
    let lastQuery = new URLSearchParams();
    let asked = 0;

    const fields = {
        from: textField({label: 'From, a UTC day', field: 'from', placeholder: 'YYYY-MM-DD'}),
        to: textField({label: 'To, a UTC day', field: 'to', placeholder: 'YYYY-MM-DD'}),
        protocol: selectField({label: 'Protocol', field: 'protocol', options: [{value: '', label: 'Any protocol'}]}),
        operation: selectField({label: 'Operation', field: 'operation', options: [{value: '', label: 'Any operation'}]}),
        outcome: selectField({label: 'Outcome', field: 'outcome', options: [{value: '', label: 'Any outcome'}]}),
        result: selectField({label: 'Test result', field: 'result', options: [{value: '', label: 'Any result'}]
            .concat(['pass', 'fail', 'unverified'].map(value => ({value, label: resultWord(value)})))}),
        chainId: textField({label: 'Chain id', field: 'chainId', literal: true}),
        suiteRunId: textField({label: 'Suite run id', field: 'suiteRunId', literal: true}),
        limit: selectField({label: 'How many', field: 'limit', options: LIMITS})
    };
    const problem = el('div', {class: 'form-problem-slot'});
    // The ledger's days are UTC days and the times on screen are the viewer's own: said beside the dates, every time the list is drawn.
    const utcNote = el('p', {class: 'small history-utc-note'});
    const form = el('form', {class: 'history-filters', noValidate: true, attrs: {'aria-label': 'Which runs to list'}},
        el('div', {class: 'history-filters__grid'}, Object.values(fields).map(field => field.node),
            el('div', {class: 'button-row history-filters__buttons'},
                el('button', {type: 'submit', class: 'button button--primary'}, 'List runs'),
                button('Clear the filters', () => { setFields(new URLSearchParams()); apply(); }))),
        utcNote, problem);
    // Not a live region: it holds the filter buttons and is redrawn with every list. The count is announced in a sentence.
    const status = el('div', {class: 'history-status'});
    const actions = el('div', {class: 'history-actions'});
    // Not a live region either: a report of a date range can run to hundreds of rows. Its counts are announced instead.
    const replayBox = el('div', {class: 'history-replay-slot'});
    const list = el('div', {class: 'history-list', aria: {busy: 'false'}});

    form.addEventListener('submit', event => {
        event.preventDefault();
        apply();
    });
    fields.protocol.select.addEventListener('change', drawChoices);

    const node = el('div', {class: 'history-runs'},
        context.health.node,
        form, status, actions, replayBox, list,
        el('p', {class: 'history-about small'},
            'A ledger line holds the codes, times and identifiers of a run: what ran, where, what came back as codes, what was expected and the test result. '
            + 'By design it holds no member name, no date of birth and no request or response. The member appears as the ledger keeps it: '
            + 'the member ID when recording it is switched on, otherwise a token, which is not the ID. Open a run for everything its line holds.'));

    /** The query the fields describe, with nothing for a field left at "any". */
    function queryFromFields() {
        const query = new URLSearchParams();
        for (const [name] of FILTERS) {
            const value = fields[name].value();
            if (value !== '') {
                query.set(name, value);
            }
        }
        if (fields.limit.value() !== DEFAULT_LIMIT) {
            query.set('limit', fields.limit.value());
        }
        return query;
    }

    function setFields(query) {
        for (const [name] of FILTERS) {
            fields[name].set(query.get(name) || '');
        }
        fields.limit.set(query.get('limit') || DEFAULT_LIMIT);
    }

    /** The choices of the selects, from the server's own lists. A value in the address the server does not list is kept as a choice. */
    function drawChoices() {
        const vocabulary = context.vocabulary();
        if (!vocabulary) {
            return;
        }
        const operations = vocabulary.operations();
        const protocols = Array.from(new Set(operations.map(entry => entry.protocol)));
        keepChoice(fields.protocol, [{value: '', label: 'Any protocol'}].concat(protocols.map(name => ({value: name.toLowerCase(), label: name}))),
            lastQuery.get('protocol'));
        const chosen = fields.protocol.value().toUpperCase();
        keepChoice(fields.operation, [{value: '', label: 'Any operation'}].concat(operations
            .filter(entry => chosen === '' || entry.protocol === chosen)
            .map(entry => ({value: entry.operation, label: entry.label}))), lastQuery.get('operation'));
        keepChoice(fields.outcome, [{value: '', label: 'Any outcome'}].concat(vocabulary.outcomeChoices(chosen ? [chosen] : null)
            .map(option => ({value: option.code, label: option.label ? option.label + ' (' + option.code + ')' : option.code}))),
            lastQuery.get('outcome'));
    }

    function keepChoice(field, options, wanted) {
        const value = wanted || field.value();
        if (value && !options.some(option => option.value === value)) {
            options = options.concat([{value, label: value}]);
        }
        setOptions(field.select, options);
        if (value) {
            field.set(value);
        }
    }

    function apply() {
        const query = queryFromFields();
        context.go('runs' + (query.toString() ? '?' + query.toString() : ''));
    }

    async function load(query, focusAfter) {
        lastQuery = query;
        utcNote.textContent = utcDayNote();
        setFields(query);
        drawChoices();
        clearFieldErrors(form);
        clear(problem);
        clear(replayBox);
        const sent = new URLSearchParams(query);
        if (!sent.has('limit')) {
            sent.set('limit', DEFAULT_LIMIT);
        }
        asked += 1;
        const mine = asked;
        list.setAttribute('aria-busy', 'true');
        status.textContent = 'Reading the ledger.';
        // The health is read with the list, so an empty list can say whether the ledger itself is in trouble.
        const [answer] = await Promise.all([api.get('/api/runs?' + sent.toString()), context.ready(), context.health.refresh()]);
        if (mine !== asked) {
            // A later choice of filters was made while this answer was on its way. The later one stands.
            return;
        }
        list.setAttribute('aria-busy', 'false');
        if (answer.kind === 'form') {
            fill(status, 'The ledger was not asked: a filter cannot be read.');
            fill(problem, formProblem(answer.body, markFieldErrors([form], answer.body.fieldErrors)));
            clear(list);
            clear(actions);
            context.announce('The ledger was not asked: a filter cannot be read.');
            return;
        }
        if (answer.kind !== 'ok') {
            fill(status, answer.body.title);
            fill(list, errorPanel(answer.body));
            clear(actions);
            context.announce(answer.body.title);
            return;
        }
        // A filter chip that had the focus is about to be redrawn away.
        const chipHadFocus = status.contains(document.activeElement);
        drawStatus(answer.body, query);
        context.announce(status.querySelector('.history-status__count').textContent);
        if (focusAfter || chipHadFocus) {
            // The control that had the focus was redrawn or taken away, so the focus goes to the count, where the new list starts.
            status.querySelector('.history-status__count').focus({preventScroll: true});
        }
        if (answer.body.count > 0) {
            drawActions(query);
        } else {
            clear(actions);
        }
        drawList(answer.body.runs || [], query);
    }

    function drawStatus(body, query) {
        const active = FILTERS.filter(([name]) => query.get(name));
        const matched = body.count === 0 ? [el('strong', null, '0'), ' runs match.'] : body.truncated
            ? (typeof body.matched === 'number'
                ? ['Showing the newest ', el('strong', null, count(body.count)), ' of ', el('strong', null, count(body.matched)), ' runs that match. '
                    + 'Choose "Every match" under How many, or narrow the filters, to see the rest.']
                : ['Showing the newest ', el('strong', null, count(body.count)), ' runs. More runs match: the ledger stopped at the limit and did not count the rest. '
                    + 'Choose "Every match" under How many, or narrow the filters, to see them.'])
            : [el('strong', null, count(body.count)), body.count === 1 ? ' run matches' : ' runs match',
                active.length > 0 ? ' these filters, and every one is shown.' : ', and every one is shown.'];
        fill(status,
            el('p', {class: 'history-status__count', tabIndex: -1}, matched),
            active.length === 0 ? el('p', {class: 'small quiet'}, 'No filter is on: this is the whole ledger, newest first.')
                : el('div', {class: 'filters', role: 'group', aria: {label: 'Filters on'}},
                    el('span', {class: 'small quiet'}, 'Filters on:'),
                    active.map(([name, label]) => button([label, ' ', filterValue(name, query.get(name)), el('span', {class: 'filter__remove'}, 'Remove')], () => {
                        const without = new URLSearchParams(query);
                        without.delete(name);
                        context.go('runs' + (without.toString() ? '?' + without.toString() : ''));
                    }, {class: 'button--quiet button--small filter', aria: {label: 'Remove the filter ' + label + ' ' + query.get(name)}, title: 'Remove this filter'}))));
    }

    /** A filter's value as its control shows it: the words of a choice in a select, the value itself for a date or an id. */
    function filterValue(name, value) {
        const field = fields[name];
        if (field && field.select) {
            const option = Array.from(field.select.options).find(candidate => candidate.value === value);
            if (option && option.textContent !== value) {
                return el('strong', null, option.textContent);
            }
        }
        if (name === 'result') {
            return el('strong', null, resultWord(value));
        }
        return name === 'from' || name === 'to' ? el('strong', null, value) : code(value);
    }

    /**
     * The export and the replay of a date range. The export takes the dates, the protocol and the
     * test result as the list does; a replay takes the dates only. Each says what it leaves out.
     */
    function drawActions(query) {
        const dates = new URLSearchParams();
        for (const name of ['from', 'to']) {
            if (query.get(name)) {
                dates.set(name, query.get(name));
            }
        }
        const exported = new URLSearchParams(dates);
        for (const name of EXPORT_FILTERS) {
            if (query.get(name)) {
                exported.set(name, query.get(name));
            }
        }
        const otherFilters = FILTERS.some(([name]) => name !== 'from' && name !== 'to' && query.get(name));
        const notExported = FILTERS.some(([name]) => name !== 'from' && name !== 'to' && !EXPORT_FILTERS.includes(name) && query.get(name));
        const range = dates.get('from') || dates.get('to')
            ? [dates.get('from') ? ['from ', dates.get('from')] : 'from the first run', ' ', dates.get('to') ? ['to ', dates.get('to')] : 'to the latest',
                ', counted in UTC days']
            : 'of every day in the ledger';
        fill(actions,
            el('div', {class: 'history-actions__item'},
                el('a', {class: 'button button--quiet', href: '/api/runs/export.csv' + (exported.toString() ? '?' + exported.toString() : ''), download: ''},
                    'Export runs as CSV'),
                el('p', {class: 'small'}, 'A CSV file of the runs ', range, ', one row per run with every column of the ledger line. '
                    + 'A spreadsheet opens it as it is.',
                    EXPORT_FILTERS.some(name => query.get(name)) ? ' It keeps the protocol and the test result chosen above.' : null,
                    notExported ? el('strong', null, ' It does not take the other filters above: it holds every run that matches the dates, '
                        + 'the protocol and the test result.') : null)),
            el('div', {class: 'history-actions__item'},
                dates.toString() ? replayOpener(dates) : null,
                el('p', {class: 'small'}, dates.toString()
                    ? ['Sends every run ', range, ', again against the configuration in force now, and says which answers are the same and what changed.',
                        otherFilters ? el('strong', null, ' A replay takes the dates only, like the export.') : null]
                    : 'Give a From or a To date above to replay the runs of those days and see what changed since.')));
    }

    function replayOpener(dates) {
        const opener = button('Replay the runs in these dates', () => confirmReplay(dates, opener), {class: 'button--quiet'});
        return opener;
    }

    /**
     * Says what a replay of these dates would send before anything is sent: how many runs the
     * ledger holds in those days whatever the other filters, how many of them are writes, and
     * where they go. The counts are read from the ledger with the dates alone, because the replay
     * takes the dates alone. Which operations write is the server's own flag on each operation.
     */
    async function confirmReplay(dates, opener) {
        fill(replayBox, el('p', {class: 'readout__status'}, 'Counting the runs in these dates.'));
        const counted = new URLSearchParams(dates);
        counted.set('limit', '0');
        const answer = await api.get('/api/runs?' + counted.toString());
        if (answer.kind !== 'ok') {
            fill(replayBox, errorPanel(answer.body));
            return;
        }
        const inDates = answer.body.runs || [];
        const replays = inDates.filter(run => run.replayOfRunId).length;
        const originals = inDates.filter(run => !run.replayOfRunId);
        const vocabulary = context.vocabulary();
        const writesByOperation = new Map();
        for (const run of originals) {
            const operation = vocabulary ? vocabulary.operation(run.operation) : null;
            if (operation && operation.write === true) {
                writesByOperation.set(operation.label, (writesByOperation.get(operation.label) || 0) + 1);
            }
        }
        const writes = Array.from(writesByOperation.values()).reduce((sum, value) => sum + value, 0);
        const mode = app.mode() || {};
        const environment = mode.environment || {};
        const access = mode.writeAccess || {};
        const mock = mode.mode === 'mock';
        const plural = (number, one, many) => count(number) + ' ' + (number === 1 ? one : many);
        const send = button('Send these runs again', () => runReplay(dates, send), {class: 'button--primary'});
        const cancel = button('Cancel', () => { clear(replayBox); opener.focus(); });
        fill(replayBox, el('div', {class: 'history-confirm', role: 'group', aria: {label: 'Replay these dates'}},
            el('p', null, el('strong', null, 'The ledger holds ' + plural(inDates.length, 'run', 'runs') + ' in these UTC days'),
                FILTERS.some(([name]) => name !== 'from' && name !== 'to' && lastQuery.get(name))
                    ? ', whatever the other filters above, which a replay does not take' : null, '. ',
                replays > 0 ? plural(replays, 'is a replay', 'are replays') + ' of an earlier run and ' + (replays === 1 ? 'is' : 'are') + ' left out. ' : null,
                el('strong', null, 'Up to ' + plural(originals.length, 'run is', 'runs are') + ' sent again'),
                ', one after another; a run that cannot be sent again is reported with the reason and not sent.'),
            el('p', null, writes === 0 ? 'None of them is a write.'
                : [el('strong', null, plural(writes, 'of them is a write', 'of them are writes')), ' (',
                    Array.from(writesByOperation.entries()).map(([label, number], index) => [index > 0 ? ', ' : null, count(number) + ' x ' + label]), '). ',
                    mock ? null : access.writesAllowed === true
                        ? ['Each write is sent again as a new request to ', environment.active ? [environment.name, ' (', environment.kind, ')'] : 'the service the configuration names',
                            ', where it is acted on like the first one. ']
                        : ['Writes are not sent: ', access.reason || 'the guardrail refuses them', ' Each is reported as blocked. ']]),
            el('p', null, mock
                ? 'This is the mock profile: the scenario files answer again, and nothing leaves this machine. Each run sent again is recorded as a new run.'
                : ['This is the ', mode.mode || 'current', ' profile: the requests go to ',
                    environment.active ? [environment.name, ' (', environment.kind, ')'] : 'the service the configuration in force names',
                    ', against the configuration in force now. Each run sent again is recorded as a new run.']),
            el('div', {class: 'button-row'}, send, cancel)));
        send.focus();
    }

    async function runReplay(dates, control) {
        control.disabled = true;
        control.setAttribute('aria-busy', 'true');
        const status = el('p', {class: 'readout__status'}, 'Sending the runs again. This takes as long as the runs themselves took.');
        fill(replayBox, status);
        context.announce('Sending the runs again.');
        const body = {};
        dates.forEach((value, name) => { body[name] = value; });
        const answer = await api.postJson('/api/runs/replay', body);
        if (answer.kind === 'ok') {
            const report = replayReport(answer.body, context.vocabulary(), {title: 'What the replay of these dates found'});
            fill(replayBox, report);
            // The button that had the focus is gone with the confirmation, so the focus goes to the report it led to.
            report.querySelector('h3').focus();
            context.announce('Replay done: ' + summaryText(answer.body));
            context.health.refresh();
            return;
        }
        if (answer.kind === 'form') {
            fill(replayBox, formProblem(answer.body, markFieldErrors([form], answer.body.fieldErrors)));
        } else {
            fill(replayBox, errorPanel(answer.body));
        }
        context.announce('Nothing was replayed: ' + answer.body.title);
        replayBox.setAttribute('tabindex', '-1');
        replayBox.focus();
        if (answer.kind === 'conflict') {
            app.refreshMode();
        }
    }

    function drawList(runs, query) {
        if (runs.length === 0) {
            const filtered = FILTERS.some(([name]) => query.get(name));
            fill(list, el('div', {class: 'empty-state'},
                el('p', {class: 'empty-state__lead'}, filtered ? 'No run matches these filters.'
                    : context.health.troubled() ? 'The ledger lists no run.' : 'The ledger holds no run yet.'),
                !filtered && context.health.troubled()
                    ? el('p', null, 'The ledger reports a problem, above. Runs it could not write, and lines it could not read, are not in this list.') : null,
                el('p', null, filtered
                    ? 'Remove a filter above, or widen the dates. A run the ledger did not record is not here at all: see the state of the ledger above.'
                    : 'Send a request from the CRD, DTR or PAS tab, or run a suite. Every run is recorded here as it is made.')));
            return;
        }
        const vocabulary = context.vocabulary();
        const head = ['When', 'What ran', 'Where', 'Outcome', {text: 'Expected', cls: 'col-expected'}, {text: 'Test result', cls: 'col-stamp'},
            {text: 'Elapsed', cls: 'num col-time'}, 'Member, as recorded', 'Tester', 'Part of'];
        fill(list, el('div', {class: 'table-scroll', role: 'region', tabIndex: 0, aria: {label: 'The runs'}},
            el('table', {class: 'grid history-table'},
                el('thead', null, el('tr', null, head.map(item => typeof item === 'string'
                    ? el('th', {scope: 'col'}, item) : el('th', {scope: 'col', class: item.cls}, item.text)))),
                el('tbody', null, runs.map(run => el('tr', {class: run.result === 'fail' ? 'history-row history-row--fail' : 'history-row'},
                    el('td', {class: 'history-when-cell'}, runLink(run.runId, whenFiled(run.timestamp))),
                    el('td', null, whatRan(vocabulary, run), run.replayOfRunId ? el('div', {class: 'small'}, el('span', {class: 'flag'}, 'replay')) : null),
                    el('td', {class: 'small'}, whereRan(run), el('div', {class: 'quiet'}, run.profile, ' profile')),
                    el('td', null, outcomeOf(vocabulary, run)),
                    el('td', null, run.expected ? expectedOf(vocabulary, run) : none('nothing declared')),
                    el('td', null, stamp(run.result)),
                    el('td', {class: 'num'}, millis(run.elapsedMillis), run.slow ? el('div', null, slowFlag()) : null),
                    el('td', null, memberShort(run)),
                    el('td', null, run.testerRef ? run.testerRef : none('none')),
                    el('td', null, partOf(run))))))));
    }

    function partOf(run) {
        const parts = [];
        if (run.chainId) {
            parts.push(el('div', null, el('span', {class: 'small quiet'}, 'chain '), chainLink(run.chainId, code(run.chainId.slice(0, 14), 'literal--id'))));
        }
        if (run.suiteRunId) {
            parts.push(el('div', null, suiteRunsLink(run.suiteRunId, 'suite run'), el('span', {class: 'small quiet'}, ', case '),
                run.caseId ? code(run.caseId) : none('no case id')));
        }
        return parts.length > 0 ? parts : el('span', {class: 'quiet small'}, 'a single run');
    }

    return {
        node,
        /** Shows the list for the filters in the address. */
        show(query, focusAfter) {
            load(query || new URLSearchParams(), focusAfter);
        },
        /** The filters of the list on screen, for the address. */
        query: () => lastQuery,
        redrawChoices: drawChoices
    };
}
