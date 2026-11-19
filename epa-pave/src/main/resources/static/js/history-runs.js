// The Runs view: every recorded run, newest first, with every filter GET /api/runs offers, the
// export for sign-off, the replay of a date range, and whether the ledger is recording.
//
// The filters live in the address (#history/runs?protocol=crd&result=fail), so the back button,
// a reload and a link from elsewhere in the tool all return to the same list. They hold codes,
// dates and identifiers of runs, never member data.

import * as api from './api.js';
import {el, fill, clear, button, code, count, millis, when} from './dom.js';
import {textField, selectField, setOptions, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {stamp, slowFlag, resultWord} from './marks.js';
import {errorPanel} from './result.js';
import {runLink, chainLink, none, memberShort, outcomeOf, whatRan, whereRan} from './history-common.js';
import {replayReport} from './history-replay.js';

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
        from: textField({label: 'From', field: 'from', placeholder: 'YYYY-MM-DD'}),
        to: textField({label: 'To', field: 'to', placeholder: 'YYYY-MM-DD'}),
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
    const form = el('form', {class: 'history-filters', noValidate: true, attrs: {'aria-label': 'Which runs to list'}},
        el('div', {class: 'history-filters__grid'}, Object.values(fields).map(field => field.node),
            el('div', {class: 'button-row history-filters__buttons'},
                el('button', {type: 'submit', class: 'button button--primary'}, 'List runs'),
                button('Clear the filters', () => { setFields(new URLSearchParams()); apply(); }))),
        problem);
    const status = el('div', {class: 'history-status', role: 'status', aria: {live: 'polite'}});
    const actions = el('div', {class: 'history-actions'});
    const replayBox = el('div', {class: 'history-replay-slot', aria: {live: 'polite'}});
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

    async function load(query) {
        lastQuery = query;
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
        const [answer] = await Promise.all([api.get('/api/runs?' + sent.toString()), context.ready()]);
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
            return;
        }
        if (answer.kind !== 'ok') {
            fill(status, answer.body.title);
            fill(list, errorPanel(answer.body));
            clear(actions);
            return;
        }
        drawStatus(answer.body, query);
        drawActions(query);
        drawList(answer.body.runs || [], query);
    }

    function drawStatus(body, query) {
        const active = FILTERS.filter(([name]) => query.get(name));
        const matched = body.truncated
            ? ['Showing the newest ', el('strong', null, count(body.count)), ' runs. More runs match: the ledger stopped at the limit and did not count the rest. '
                + 'Choose "Every match" under How many, or narrow the filters, to see them.']
            : [el('strong', null, count(body.count)), body.count === 1 ? ' run matches' : ' runs match',
                active.length > 0 ? ' these filters, and every one is shown.' : ', and every one is shown.'];
        fill(status,
            el('p', {class: 'history-status__count'}, matched),
            active.length === 0 ? el('p', {class: 'small quiet'}, 'No filter is on: this is the whole ledger, newest first.')
                : el('div', {class: 'filters', role: 'group', aria: {label: 'Filters on'}},
                    el('span', {class: 'small quiet'}, 'Filters on:'),
                    active.map(([name, label]) => button([label, ' ', filterValue(name, query.get(name))], () => {
                        const without = new URLSearchParams(query);
                        without.delete(name);
                        context.go('runs' + (without.toString() ? '?' + without.toString() : ''));
                    }, {class: 'button--quiet button--small filter', aria: {label: 'Remove the filter ' + label + ' ' + query.get(name)}, title: 'Remove this filter'}))));
    }

    function filterValue(name, value) {
        const vocabulary = context.vocabulary();
        if (name === 'operation' && vocabulary && vocabulary.operation(value)) {
            return el('strong', null, vocabulary.operation(value).label);
        }
        if (name === 'result') {
            return el('strong', null, resultWord(value));
        }
        return code(value);
    }

    /** The export and the replay of a date range. Both take the dates only, and say so. */
    function drawActions(query) {
        const dates = new URLSearchParams();
        for (const name of ['from', 'to']) {
            if (query.get(name)) {
                dates.set(name, query.get(name));
            }
        }
        const otherFilters = FILTERS.some(([name]) => name !== 'from' && name !== 'to' && query.get(name));
        const range = dates.get('from') || dates.get('to')
            ? [dates.get('from') ? ['from ', dates.get('from')] : 'from the first run', ' ', dates.get('to') ? ['to ', dates.get('to')] : 'to the latest']
            : 'of every day in the ledger';
        fill(actions,
            el('div', {class: 'history-actions__item'},
                el('a', {class: 'button button--quiet', href: '/api/runs/export.csv' + (dates.toString() ? '?' + dates.toString() : ''), download: ''},
                    'Export runs as CSV'),
                el('p', {class: 'small'}, 'A CSV file of the runs ', range, ', one row per run with every column of the ledger line. '
                    + 'A spreadsheet opens it as it is.',
                    otherFilters ? el('strong', null, ' The export takes the dates only: it holds every run in those dates, whatever the other filters above.') : null)),
            el('div', {class: 'history-actions__item'},
                dates.toString() ? button('Replay the runs in these dates', () => confirmReplay(dates), {class: 'button--quiet'}) : null,
                el('p', {class: 'small'}, dates.toString()
                    ? ['Sends every run ', range, ' again against the configuration in force now, and says which answers are the same and what changed.',
                        otherFilters ? el('strong', null, ' A replay takes the dates only, like the export.') : null]
                    : 'Give a From or a To date above to replay the runs of those days and see what changed since.')));
    }

    function confirmReplay(dates) {
        const mode = app.mode() || {};
        const environment = mode.environment || {};
        const send = button('Send these runs again', () => runReplay(dates, send), {class: 'button--primary'});
        fill(replayBox, el('div', {class: 'history-confirm', role: 'group', aria: {label: 'Replay these dates'}},
            el('p', null, el('strong', null, 'This sends real requests.'), ' Every run in these dates that can be sent again goes to ',
                environment.active ? [environment.name, ' (', environment.kind, ')'] : 'no environment', ' on the ', mode.mode || 'current',
                ' profile, one after another, and the answer comes back when the last one is done. A run that cannot be sent again is reported with the reason and not sent. '
                + 'Replays of earlier replays are left out. Writes to production are refused by the guardrail.'),
            el('div', {class: 'button-row'}, send, button('Cancel', () => clear(replayBox)))));
        send.focus();
    }

    async function runReplay(dates, control) {
        control.disabled = true;
        control.setAttribute('aria-busy', 'true');
        const status = el('p', {class: 'readout__status', role: 'status'}, 'Sending the runs again. This takes as long as the runs themselves took.');
        fill(replayBox, status);
        const body = {};
        dates.forEach((value, name) => { body[name] = value; });
        const answer = await api.postJson('/api/runs/replay', body);
        if (answer.kind === 'ok') {
            fill(replayBox, replayReport(answer.body, context.vocabulary(), {title: 'What the replay of these dates found'}));
            context.health.refresh();
            return;
        }
        if (answer.kind === 'form') {
            fill(replayBox, formProblem(answer.body, markFieldErrors([form], answer.body.fieldErrors)));
            return;
        }
        fill(replayBox, errorPanel(answer.body));
        if (answer.kind === 'conflict') {
            app.refreshMode();
        }
    }

    function drawList(runs, query) {
        if (runs.length === 0) {
            const filtered = FILTERS.some(([name]) => query.get(name));
            fill(list, el('div', {class: 'empty-state'},
                el('p', {class: 'empty-state__lead'}, filtered ? 'No run matches these filters.' : 'The ledger holds no run yet.'),
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
                    el('td', null, runLink(run.runId, when(run.timestamp))),
                    el('td', null, whatRan(vocabulary, run), run.replayOfRunId ? el('div', {class: 'small'}, el('span', {class: 'flag'}, 'replay')) : null),
                    el('td', {class: 'small'}, whereRan(run), el('div', {class: 'quiet'}, run.profile, ' profile')),
                    el('td', null, outcomeOf(vocabulary, run)),
                    el('td', null, run.expected ? code(run.expected) : none('nothing declared')),
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
            parts.push(el('div', null, el('span', {class: 'small quiet'}, 'suite, case '), run.caseId ? code(run.caseId) : none('no case id')));
        }
        return parts.length > 0 ? parts : el('span', {class: 'quiet small'}, 'a single run');
    }

    return {
        node,
        /** Shows the list for the filters in the address. */
        show(query) {
            context.health.refresh();
            load(query || new URLSearchParams());
        },
        /** The filters of the list on screen, for the address. */
        query: () => lastQuery,
        redrawChoices: drawChoices
    };
}
