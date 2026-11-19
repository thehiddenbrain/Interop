// The Config tab: where the vendor's Postman files become the configuration of this tool.
//
// A person who has never seen the tool should be able to answer five questions from here: where
// is the tool pointed, is that production, which request of the vendor's collection does each
// operation use, what is still missing before a run can be sent, and can the tool change
// anything in that environment right now. Every one of those answers is the server's. This tab
// reads them and shows them; it does not work out what is production, what is a write or what is
// missing.
//
// The tab is a row of views, built the way the History tab builds its own. What the tool holds
// and what is in force is read here once, in three answers, and handed to whichever view is on
// screen. After any change made on this tab the three are read again and the frame is told to
// read /api/mode, so the banner, the environment strip and this tab say the same thing.

import * as calls from './config-calls.js';
import {el, fill, clear} from './dom.js';
import {errorPanel} from './result.js';
import {createInForceView} from './config-inforce.js';
import {createEnvironmentsView} from './config-environments.js';

const VIEWS = [
    {id: 'inforce', label: 'In force now', create: createInForceView},
    {id: 'environments', label: 'Environments', create: createEnvironmentsView}
];

let app = null;
let current = VIEWS[0].id;
let reads = 0;
const nodes = {};
const built = new Map();   // view id -> the view, once it has been shown

// What the tool holds and what is in force, as last read, and when. Each is null until its first answer.
const stored = {environments: null, collections: null, effective: null, epoch: null, readAt: null};

const context = {
    /** The frame: the mode, the epoch, notices. Set when the tab is mounted. */
    app: null,
    /** What was last read: {environments, collections, effective, epoch, readAt}. */
    stored: () => stored,
    reload,
    changed,
    go,
    operationLabel
};

function mount(panel, application) {
    app = application;
    context.app = application;
    nodes.switcher = el('div', {class: 'views', role: 'tablist', aria: {label: 'Views of the configuration'}},
        VIEWS.map(view => el('button', {
            type: 'button', class: 'view', role: 'tab', id: 'config-view-' + view.id, aria: {selected: 'false', controls: 'config-view'},
            on: {click: () => go(view.id)}
        }, view.label)));
    nodes.problem = el('div', {class: 'config-problem', aria: {live: 'polite'}});
    nodes.view = el('div', {id: 'config-view', class: 'config', role: 'tabpanel'});
    panel.classList.add('tabpanel--stacked');
    fill(panel, nodes.switcher, nodes.problem, nodes.view);
    reload();
}

function onShow(path) {
    const [view, ...rest] = (path || current).split('/');
    showView(VIEWS.some(candidate => candidate.id === view) ? view : current, rest.length > 0 ? rest.join('/') : null);
}

function showView(id, argument) {
    current = id;
    for (const control of nodes.switcher.children) {
        control.setAttribute('aria-selected', control.id === 'config-view-' + id ? 'true' : 'false');
    }
    nodes.view.setAttribute('aria-labelledby', 'config-view-' + id);
    let view = built.get(id);
    if (!view) {
        view = VIEWS.find(candidate => candidate.id === id).create(context);
        built.set(id, view);
        view.draw();
    }
    fill(nodes.view, view.node);
    nodes.view.scrollTop = 0;
    view.show(argument);
}

/** Shows another view of this tab, optionally at something inside it, and keeps the address in step. */
function go(id, argument) {
    showView(id, argument || null);
    app.addressChanged();
}

/**
 * Reads what the tool holds and what is in force, then redraws every view that has been built.
 * Views that are not on screen are redrawn too: they are shown again without being read again.
 */
async function reload() {
    const turn = ++reads;
    const [environments, collections, effective] = await Promise.all([calls.environments(), calls.collections(), calls.effective()]);
    if (turn !== reads) {
        // A later read was started while this one was on its way. Its answer is the one to draw.
        return;
    }
    const failed = [environments, collections, effective].find(answer => answer.kind !== 'ok');
    if (failed) {
        // What was read earlier stays on screen, since the tester may be reading it, and is marked
        // as possibly out of date. Nothing is cleared to an empty table that would read as "none".
        fill(nodes.problem, errorPanel(failed.body), stored.effective
            ? el('p', {class: 'config-stale'}, 'What is shown below was read earlier and may no longer be what the tool holds.') : null);
    } else {
        clear(nodes.problem);
        stored.environments = environments.body;
        stored.collections = collections.body;
        stored.effective = effective.body;
        stored.epoch = effective.body.epoch;
        stored.readAt = new Date();
    }
    for (const view of built.values()) {
        view.draw();
    }
}

/**
 * Call after any change made on this tab. The stored configuration is read again, and the frame
 * re-reads /api/mode so its banner and its strip agree with what this tab now shows.
 */
async function changed() {
    await reload();
    await app.refreshMode();
}

/** The label of an operation, from the list the server sent. One it did not list is shown by its name. */
function operationLabel(name) {
    const rows = stored.effective ? stored.effective.operations || [] : [];
    const row = rows.find(candidate => candidate.operation === name);
    return row ? row.label : String(name);
}

function onConfigChange(mode) {
    // A change made on this tab has been read already, and its epoch is the one the frame now reports.
    if (stored.epoch !== mode.epoch) {
        reload();
    }
}

function address() {
    return current === VIEWS[0].id ? null : current;
}

export default {id: 'config', label: 'Config', mount, onShow, onConfigChange, address};
