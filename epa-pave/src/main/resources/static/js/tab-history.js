// The History tab. Three views of what has been run: single runs, chains and suite runs.
//
// This pass builds Chains and Suite runs. The Runs view needs the ledger endpoints under
// /api/runs, which arrive in a later pass; until then it says so in one sentence.
//
// Seam for the Runs view: add its content where runsView() is, and make a run id a link to
// "history/runs/<runId>" wherever one is shown as text to copy (suites.js, chain.js, result.js).

import * as api from './api.js';
import {el, fill, clear, button, code, when} from './dom.js';
import {textField, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {chainView} from './chain.js';
import {createSuites} from './suites.js';
import {errorPanel} from './result.js';
import {outcome} from './marks.js';

const VIEWS = [
    {id: 'runs', label: 'Runs'},
    {id: 'chains', label: 'Chains'},
    {id: 'suites', label: 'Suite runs'}
];

let app = null;
let current = 'suites';
let openChainId = null;
let suites = null;
const nodes = {};

function mount(panel, application) {
    app = application;
    suites = createSuites(app);
    nodes.switcher = el('div', {class: 'views', role: 'tablist', aria: {label: 'Views of the history'}},
        VIEWS.map(view => el('button', {
            type: 'button', class: 'view', role: 'tab', id: 'view-' + view.id, aria: {selected: 'false', controls: 'history-view'},
            on: {click: () => { showView(view.id, null); app.addressChanged(); }}
        }, view.label)));
    nodes.view = el('div', {id: 'history-view', role: 'tabpanel'});
    nodes.runs = runsView();
    nodes.chains = chainsView();
    panel.classList.add('tabpanel--stacked');
    fill(panel, nodes.switcher, nodes.view);
}

function onShow(path) {
    const [view, ...rest] = (path || current).split('/');
    showView(VIEWS.some(candidate => candidate.id === view) ? view : current, rest.length > 0 ? rest.join('/') : null);
}

function showView(view, argument) {
    current = view;
    for (const control of nodes.switcher.children) {
        const selected = control.id === 'view-' + view;
        control.setAttribute('aria-selected', selected ? 'true' : 'false');
    }
    nodes.view.setAttribute('aria-labelledby', 'view-' + view);
    if (view === 'suites') {
        fill(nodes.view, suites.node);
        suites.show();
    } else if (view === 'chains') {
        fill(nodes.view, nodes.chains.node);
        nodes.chains.show(argument);
    } else {
        fill(nodes.view, nodes.runs);
    }
}

function runsView() {
    return el('p', {class: 'plain-statement'},
        'The Runs view arrives in a later pass of the build: every recorded run, with filters, the full record, replay and the export for sign-off. '
        + 'Chains and suite runs are on the two views beside this one.');
}

/* ---------- chains ---------- */

function chainsView() {
    const from = textField({label: 'From', field: 'from', placeholder: 'YYYY-MM-DD'});
    const to = textField({label: 'To', field: 'to', placeholder: 'YYYY-MM-DD'});
    const problem = el('div');
    const list = el('div', {class: 'chain-list', aria: {live: 'polite'}});
    const detail = el('div', {class: 'readout', role: 'region', tabIndex: 0, aria: {label: 'The chain on screen'}});
    const form = el('form', {class: 'case-pane', noValidate: true, attrs: {'aria-label': 'Which chains to list'}},
        el('div', {class: 'case-pane__scroll'},
            el('div', {class: 'case-group'},
                el('h2', {class: 'case-legend'}, 'Chains'),
                el('p', {class: 'quiet small'}, 'One row for each case followed through CRD, DTR and PAS, most recent first. Leave the dates empty for the latest.'),
                el('div', {class: 'field-grid'}, from.node, to.node),
                problem,
                el('div', {class: 'button-row'}, el('button', {type: 'submit', class: 'button button--quiet'}, 'List chains'))),
            list));
    form.addEventListener('submit', event => {
        event.preventDefault();
        load();
    });

    async function load() {
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
            fill(list, el('p', {class: 'quiet'},
                'No chain has a run in this range. A chain starts when a run is sent with "Run as part of a chain" ticked on the CRD tab.'));
            return;
        }
        fill(list, el('ul', {class: 'chain-rows'}, chains.map(chain => {
            const latest = (chain.stages || []).map(stage => stage.latest).filter(Boolean).pop();
            return el('li', {class: 'chain-row' + (chain.chainId === openChainId ? ' chain-row--open' : '')},
                button(chain.member ? ['Member ', code(chain.member)] : ['Chain ', code(chain.chainId)],
                    () => { open(chain.chainId); app.addressChanged(); }, {class: 'button--text'}),
                el('div', {class: 'small quiet'}, when(chain.lastRunAt), ', ', String(chain.runCount), chain.runCount === 1 ? ' run' : ' runs',
                    chain.procedureCodes && chain.procedureCodes.length > 0
                        ? [', codes ', chain.procedureCodes.map((value, index) => [index > 0 ? ', ' : null, code(value)])] : null),
                el('div', {class: 'small'}, latest ? outcome(chain.headline, latest.tone) : chain.headline));
        })));
    }

    async function open(chainId) {
        openChainId = chainId;
        const status = el('p', {class: 'readout__status', role: 'status', aria: {live: 'polite'}}, 'Reading the chain from the ledger.');
        fill(detail, status);
        const answer = await api.get('/api/chains/' + encodeURIComponent(chainId));
        status.textContent = answer.kind === 'ok' ? 'Chain read from the ledger. ' + answer.body.headline : answer.body.title;
        fill(detail, status, answer.kind === 'ok' ? chainView(answer.body) : errorPanel(answer.body));
        for (const row of list.querySelectorAll('.chain-row')) {
            row.classList.remove('chain-row--open');
        }
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
    return current === 'chains' && openChainId ? 'chains/' + openChainId : current;
}

function onConfigChange() {
    if (suites) {
        suites.onConfigChange();
    }
}

export default {id: 'history', label: 'History', mount, onShow, onConfigChange, address};
