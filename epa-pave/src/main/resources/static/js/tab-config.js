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
// screen. After any change made on this tab the frame is told to read /api/mode first and the
// three are read after it, so the banner, the environment strip and this tab say the same thing.

import * as calls from './config-calls.js';
import {el, fill, clear} from './dom.js';
import {errorPanel} from './result.js';
import {createInForceView} from './config-inforce.js';
import {createEnvironmentsView} from './config-environments.js';
import {createCollectionsView} from './config-collections.js';
import {createRawView} from './config-raw.js';
import {createChecksView} from './config-checks.js';

const VIEWS = [
    {id: 'inforce', label: 'In force now', create: createInForceView},
    {id: 'environments', label: 'Environments', create: createEnvironmentsView},
    {id: 'collections', label: 'Collections and mapping', create: createCollectionsView},
    {id: 'raw', label: 'Send a request as written', create: createRawView},
    {id: 'checks', label: 'Check this installation', create: createChecksView}
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
    operationLabel,
    announce
};

function mount(panel, application) {
    app = application;
    context.app = application;
    nodes.switcher = el('div', {class: 'views config-views', role: 'tablist', aria: {label: 'Views of the configuration'}},
        VIEWS.map(view => el('button', {
            type: 'button', class: 'view', role: 'tab', id: 'config-view-' + view.id, aria: {selected: 'false', controls: 'config-view'},
            on: {click: () => go(view.id)}
        }, view.label)));
    nodes.switcher.addEventListener('keydown', moveAlongViews);
    nodes.problem = el('div', {class: 'config-problem'});
    // The one live region of the tab. Sections are redrawn whole, and a live region around one would
    // be read out whole on every redraw, so each view says in one short line what changed instead.
    nodes.status = el('p', {class: 'visually-hidden', role: 'status', aria: {live: 'polite'}});
    nodes.view = el('div', {id: 'config-view', class: 'config', role: 'tabpanel', tabIndex: -1});
    panel.classList.add('tabpanel--stacked');
    fill(panel, nodes.switcher, nodes.status, nodes.problem, nodes.view);
    reload();
}

/** Arrow keys, Home and End move along the views, as they move along the tabs above them. */
function moveAlongViews(event) {
    const ids = VIEWS.map(view => view.id);
    const at = ids.indexOf(current);
    const next = {ArrowRight: ids[(at + 1) % ids.length], ArrowLeft: ids[(at + ids.length - 1) % ids.length],
        Home: ids[0], End: ids[ids.length - 1]}[event.key];
    if (next) {
        event.preventDefault();
        go(next);
        document.getElementById('config-view-' + next).focus();
    }
}

function onShow(path) {
    const [view, ...rest] = (path || current).split('/');
    showView(VIEWS.some(candidate => candidate.id === view) ? view : current, rest.length > 0 ? rest.join('/') : null);
}

function showView(id, argument) {
    current = id;
    for (const control of nodes.switcher.children) {
        const selected = control.id === 'config-view-' + id;
        control.setAttribute('aria-selected', selected ? 'true' : 'false');
        control.tabIndex = selected ? 0 : -1;
    }
    nodes.view.setAttribute('aria-labelledby', 'config-view-' + id);
    let view = built.get(id);
    if (!view) {
        view = VIEWS.find(candidate => candidate.id === id).create(context);
        built.set(id, view);
        view.draw();
    }
    fill(nodes.view, view.node);
    nodes.view.classList.toggle('config--bench', Boolean(view.bench));
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
    const before = stored.epoch;
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
    // What a view drew from the earlier configuration and keeps until it is asked again (the
    // placeholders of a chosen request, a resolved answer, a preview) is dropped or read again.
    const epochChanged = !failed && before !== null && before !== stored.epoch;
    for (const view of built.values()) {
        view.draw();
        if (epochChanged && view.configChanged) {
            view.configChanged();
        }
    }
    if (failed) {
        announce('The configuration could not be read from the tool. ' + failed.body.title);
    }
}

/** Says one short sentence to assistive technology. The sections themselves are not live regions. */
function announce(text) {
    nodes.status.textContent = '';
    // Set on the next frame, so that the same sentence twice in a row is announced twice.
    requestAnimationFrame(() => {
        nodes.status.textContent = text;
    });
}

/**
 * Call after any change made on this tab. The frame re-reads /api/mode first, so the write access
 * this tab draws from is the one the banner shows, and then what the tool holds is read again.
 */
async function changed() {
    await app.refreshMode();
    await reload();
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
