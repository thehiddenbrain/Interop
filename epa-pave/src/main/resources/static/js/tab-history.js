// The History tab: what has been run. Three views: single runs, chains and suite runs.
//
// Runs is the default view: every run in the ledger with its filters, one run in full, its note,
// replay and the export. A run id shown anywhere in the tool opens that run here through the
// address #history/runs/<runId> (history-common.js, runAddress), so a link to a run is a plain
// anchor and works from any tab. The views and the filters of the list are kept in the address,
// so the back button and a reload return to what was on screen.

import * as api from './api.js';
import {el, fill, clear, button, code, when} from './dom.js';
import {textField, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {chainView} from './chain.js';
import {createSuites} from './suites.js';
import {errorPanel} from './result.js';
import {outcome} from './marks.js';
import {loadVocabulary} from './history-vocabulary.js';
import {createHealth} from './history-health.js';
import {createRunsList} from './history-runs.js';
import {createRunView} from './history-run.js';
import {utcDayNote} from './history-common.js';

const VIEWS = [
    {id: 'runs', label: 'Runs'},
    {id: 'chains', label: 'Chains'},
    {id: 'suites', label: 'Suite runs'}
];

let app = null;
let current = 'runs';
let openChainId = null;
let openRunId = null;     // the run open in the Runs view, or null for the list
let suites = null;
let runs = null;
let runView = null;
let vocabulary = null;
let vocabularyRead = Promise.resolve(null);
const nodes = {};

// The one region that speaks for the History tab. Lists, reports and the health panel are redrawn
// often and are long, so none of them is live: each says what changed here in one sentence.
const announcer = el('p', {class: 'visually-hidden', role: 'status', aria: {live: 'polite', atomic: 'true'}});

function announce(sentence) {
    // Emptied first, so the same sentence twice in a row is still read out.
    announcer.textContent = '';
    window.setTimeout(() => { announcer.textContent = sentence; }, 50);
}

/** What the runs views share: the words for codes, the health panel, and a way to change the view. */
const context = {
    announce,
    vocabulary: () => vocabulary,
    /** Resolves when the words for codes have been read, so a list is never drawn with codes it could have worded. */
    ready: () => vocabularyRead,
    health: null,
    listQuery: () => (runs ? runs.query() : null),
    go
};

/**
 * Changes the view inside this tab, with an entry in the browser's history so the back button
 * returns. It does not go through app.navigate, which sends the focus to the top of the working
 * area: a keyboard user who chose "List runs" or a filter keeps their place.
 */
function go(path) {
    const address = '#history/' + path;
    if (window.location.hash !== address) {
        window.history.pushState(null, '', address);
    }
    onShow(path);
}

function mount(panel, application) {
    app = application;
    context.health = createHealth(app, announce);
    suites = createSuites(app, context);
    runs = createRunsList(app, context);
    runView = createRunView(app, context);
    readVocabulary();
    nodes.switcher = el('div', {class: 'views', role: 'tablist', aria: {label: 'Views of the history'}},
        VIEWS.map(view => el('button', {
            type: 'button', class: 'view', role: 'tab', id: 'view-' + view.id, aria: {selected: 'false', controls: 'history-view'},
            on: {click: () => { showView(view.id, null); app.addressChanged(); }}
        }, view.label)));
    nodes.view = el('div', {id: 'history-view', role: 'tabpanel'});
    nodes.chains = chainsView();
    panel.classList.add('tabpanel--stacked');
    fill(panel, nodes.switcher, announcer, nodes.view);
}

function onShow(path) {
    const [view, ...rest] = (path || current).split('/');
    // A view can carry a query, as the list of runs carries its filters: runs?protocol=crd.
    const [name, query] = view.split('?');
    const known = VIEWS.some(candidate => candidate.id === name);
    showView(known ? name : current, rest.length > 0 ? rest.join('/') : null, known && query !== undefined ? query : null);
}

function readVocabulary() {
    vocabularyRead = loadVocabulary().then(read => {
        vocabulary = read;
        runs.redrawChoices();
        return read;
    });
}

function showView(view, argument, query) {
    current = view;
    if (view !== 'runs') {
        // The Runs view reads the health with its list. Any other view brings the masthead indicator up to date here.
        context.health.refresh();
    }
    for (const control of nodes.switcher.children) {
        const selected = control.id === 'view-' + view;
        control.setAttribute('aria-selected', selected ? 'true' : 'false');
    }
    nodes.view.setAttribute('aria-labelledby', 'view-' + view);
    if (view === 'suites') {
        place(context.health.brief, suites.node);
        suites.show();
    } else if (view === 'chains') {
        place(context.health.brief, nodes.chains.node);
        nodes.chains.show(argument);
    } else if (argument) {
        openRunId = argument;
        // When the tester was working in the view that is replaced, the new one takes the focus. On arriving at the tab it does not.
        const hadFocus = nodes.view.contains(document.activeElement);
        const replaced = place(runView.node);
        runView.open(argument, hadFocus && replaced);
    } else {
        openRunId = null;
        const hadFocus = nodes.view.contains(document.activeElement);
        const replaced = place(runs.node);
        runs.show(query !== null && query !== undefined ? new URLSearchParams(query) : runs.query(), hadFocus && replaced);
    }
}

/**
 * Puts the nodes of a view on screen. Nodes already there in that order are left in place: taking
 * one out and putting it back would drop the keyboard focus inside it to the top of the page.
 * Answers true when the view was replaced, so the view drawn can take the focus the old one had.
 */
function place(...wanted) {
    const children = Array.from(nodes.view.children);
    if (children.length === wanted.length && children.every((child, index) => child === wanted[index])) {
        return false;
    }
    fill(nodes.view, ...wanted);
    return true;
}

/* ---------- chains ---------- */

function chainsView() {
    const from = textField({label: 'From, a UTC day', field: 'from', placeholder: 'YYYY-MM-DD'});
    const to = textField({label: 'To, a UTC day', field: 'to', placeholder: 'YYYY-MM-DD'});
    const utcNote = el('p', {class: 'small history-utc-note'});
    const problem = el('div');
    const list = el('div', {class: 'chain-list'});
    const detail = el('div', {class: 'readout', role: 'region', tabIndex: 0, aria: {label: 'The chain on screen'}});
    const form = el('form', {class: 'case-pane', noValidate: true, attrs: {'aria-label': 'Which chains to list'}},
        el('div', {class: 'case-pane__scroll'},
            el('div', {class: 'case-group'},
                el('h2', {class: 'case-legend'}, 'Chains'),
                el('p', {class: 'quiet small'}, 'One row for each case followed through CRD, DTR and PAS, most recent first. Leave the dates empty for the latest.'),
                el('div', {class: 'field-grid'}, from.node, to.node),
                utcNote,
                problem,
                el('div', {class: 'button-row'}, el('button', {type: 'submit', class: 'button button--quiet'}, 'List chains'))),
            list));
    form.addEventListener('submit', event => {
        event.preventDefault();
        load();
    });

    async function load() {
        utcNote.textContent = utcDayNote();
        clearFieldErrors(form);
        clear(problem);
        const query = new URLSearchParams();
        if (from.value() !== '') {
            query.set('from', from.value());
        }
        if (to.value() !== '') {
            query.set('to', to.value());
        }
        const answer = await api.get('/api/chains' + (query.toString() ? '?' + query.toString() : ''));
        if (answer.kind === 'form') {
            fill(problem, formProblem(answer.body, markFieldErrors([form], answer.body.fieldErrors)));
            return;
        }
        if (answer.kind !== 'ok') {
            fill(list, errorPanel(answer.body));
            return;
        }
        const chains = answer.body.chains || [];
        if (chains.length === 0) {
            const troubled = context.health.troubled();
            fill(list, el('p', {class: 'quiet'},
                'No chain has a run in this range. A chain starts when a run is sent with "Run as part of a chain" ticked on the CRD tab.',
                troubled ? ' The ledger reports a problem, above: runs it did not record, and lines it could not read, are in no chain.' : null));
            context.announce('No chain in this range.');
            return;
        }
        // Each button leads with what tells the chains apart, the member and the headline, so a
        // list of the links on the page does not read as many entries that start alike.
        fill(list, el('ul', {class: 'chain-rows'}, chains.map(chain => {
            const latest = (chain.stages || []).map(stage => stage.latest).filter(Boolean).pop();
            return el('li', {class: 'chain-row', dataset: {chain: chain.chainId}},
                button([chain.member ? code(chain.member) : code(chain.chainId), ' ', el('span', {class: 'chain-row__headline'}, chain.headline)],
                    () => { open(chain.chainId); app.addressChanged(); }, {class: 'button--text chain-row__open'}),
                el('div', {class: 'small quiet'}, chain.member ? 'Member, as recorded. ' : 'Chain id; no member recorded. ',
                    when(chain.lastRunAt), ', ', String(chain.runCount), chain.runCount === 1 ? ' run' : ' runs',
                    chain.procedureCodes && chain.procedureCodes.length > 0
                        ? [', codes ', chain.procedureCodes.map((value, index) => [index > 0 ? ', ' : null, code(value)])] : null),
                latest ? el('div', {class: 'small'}, outcome(latest.outcomeLabel || latest.outcome || '', latest.tone)) : null);
        })));
        context.announce(chains.length === 1 ? 'One chain listed.' : chains.length + ' chains listed.');
        markOpen();
    }

    /** Marks the row of the chain whose journey is on the right, so the list and the readout are seen to agree. */
    function markOpen() {
        for (const row of list.querySelectorAll('.chain-row')) {
            const isOpen = row.dataset.chain === openChainId;
            row.classList.toggle('chain-row--open', isOpen);
            if (isOpen) {
                row.setAttribute('aria-current', 'true');
            } else {
                row.removeAttribute('aria-current');
            }
        }
    }

    async function open(chainId) {
        openChainId = chainId;
        markOpen();
        const status = el('p', {class: 'readout__status', role: 'status', aria: {live: 'polite'}}, 'Reading the chain from the ledger.');
        fill(detail, status);
        const answer = await api.get('/api/chains/' + encodeURIComponent(chainId));
        if (openChainId !== chainId) {
            // Another chain was opened while this answer was on its way. The later choice stands.
            return;
        }
        status.textContent = answer.kind === 'ok' ? 'Chain read from the ledger. ' + answer.body.headline : answer.body.title;
        // The headline is drawn beneath. The line is for a screen reader, so it is not shown twice.
        status.classList.toggle('visually-hidden', answer.kind === 'ok');
        fill(detail, status, answer.kind === 'ok' ? chainView(answer.body) : errorPanel(answer.body));
    }

    function empty() {
        fill(detail, el('div', {class: 'empty-state'},
            el('p', {class: 'empty-state__lead'}, 'No chain is open.'),
            el('p', null, 'Choose a chain on the left to see its whole journey on one screen: each stage with its runs, '
                + 'and for a stage that did not run, the reason.')));
    }

    return {
        node: el('div', {class: 'workbench'}, form, detail),
        show(chainId) {
            load();
            if (chainId) {
                open(chainId);
            } else if (!openChainId) {
                empty();
            }
        }
    };
}

function address() {
    if (current === 'chains' && openChainId) {
        return 'chains/' + openChainId;
    }
    if (current === 'runs') {
        if (openRunId) {
            return 'runs/' + openRunId;
        }
        const query = runs ? runs.query().toString() : '';
        return query ? 'runs?' + query : 'runs';
    }
    return current;
}

function onConfigChange() {
    if (suites) {
        suites.onConfigChange();
    }
    // The operations the configuration maps can change with it, and their words with them.
    if (runs) {
        readVocabulary();
    }
}

function onWriteAccessChange() {
    if (runs) {
        runs.writeAccessChanged();
    }
}

export default {id: 'history', label: 'History', mount, onShow, onConfigChange, onWriteAccessChange, address};
