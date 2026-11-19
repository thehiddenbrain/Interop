// Start-up, the frame, the tab switcher, and the poll of /api/mode with the configuration epoch.
//
// The frame says what this tool is and what it is pointed at on every tab: name and version on
// the left, the environment strip on the right, the same facts in the browser tab title, and a
// banner that cannot be scrolled past for production, for UAT and for the mock profile.

import * as api from './api.js';
import {el, fill, clear, button, token} from './dom.js';
import {createCaseContext} from './case-context.js';
import {createChain} from './chain.js';
import {openLegend} from './legend.js';
import crdTab from './tab-crd.js';
import dtrTab from './tab-dtr.js';
import pasTab from './tab-pas.js';
import historyTab from './tab-history.js';
import configTab from './tab-config.js';

const TABS = [crdTab, dtrTab, pasTab, historyTab, configTab];

// The one thing this tool keeps in browser storage: which tab was open. It holds no member data,
// and StaticFrontendTest fails the build if any script stores under another key.
const TAB_KEY = 'pave.tab';

const POLL_MILLIS = 4000;

const state = {
    mode: null,           // the last answer of /api/mode that was drawn
    active: null,         // the id of the tab on screen
    tabs: new Map(),      // tab id -> {tab, panel, control, built}
    carried: new Map(),   // tab id -> what the previous step handed on
    pollFailed: false,
    ledger: null          // what the ledger did with the latest run this page knows of
};

const app = {
    /** The last /api/mode answer that the screen was drawn from. */
    mode: () => state.mode,
    /** The configuration epoch the screen was drawn from. Sent with every run as configEpoch. */
    epoch: () => (state.mode ? state.mode.epoch : null),
    isMock: () => Boolean(state.mode) && state.mode.mode === 'mock',
    navigate,
    /** Leaves something for a tab to pick up when it is next shown: the hand-over between steps. */
    carryTo(tabId, payload) {
        state.carried.set(tabId, payload);
    },
    /** What was carried to a tab, or null. It stays until the next hand-over replaces it or a new case starts. */
    carriedTo(tabId) {
        return state.carried.get(tabId) || null;
    },
    /** Forgets every hand-over. What one case carried to the next step is not for the case after it. */
    forgetCarried() {
        state.carried.clear();
    },
    notice,
    clearNotices: () => clear(document.getElementById('notices')),
    /** Re-reads /api/mode now. Used after the server says the configuration changed. */
    refreshMode: () => poll(true),
    /** Tells the frame what the ledger did with the latest run, for the indicator in the masthead. */
    ledgerReceipt(receipt) {
        state.ledger = {recorded: Boolean(receipt && receipt.recorded), runsToday: receipt ? receipt.runsToday : undefined,
            message: receipt ? receipt.message : undefined};
        drawStrip();
    },
    /** Tells the frame that runs made elsewhere, such as by a suite, were not recorded. */
    ledgerProblem(message) {
        state.ledger = {recorded: false, message};
        drawStrip();
    },
    /** Lets a tab update the address when it changes view, so the back button and a reload return to it. */
    addressChanged() {
        const address = '#' + addressOf(state.active);
        if (window.location.hash !== address) {
            window.history.replaceState(null, '', address);
        }
    },
    /** The case shared by the protocol tabs, and the chain it is followed under. Set at start-up. */
    caseContext: null,
    chain: null
};

async function start() {
    // A defect in this page must not leave a tester looking at a screen that silently stopped
    // half way. The detail stays in the browser console; it is not put on screen, because an
    // error message can quote what it was handling.
    const ownFault = () => notice('This page hit an error of its own and may be showing an incomplete answer. '
        + 'Reload the page. A run that was sent was made whether or not its result was drawn here: look for it in the ledger.');
    window.addEventListener('error', ownFault);
    window.addEventListener('unhandledrejection', ownFault);

    document.getElementById('legend-open').addEventListener('click', () => openLegend(document.getElementById('legend')));
    app.caseContext = createCaseContext();
    app.chain = createChain(app, document.getElementById('chain-bar'));

    const first = await api.get('/api/mode');
    if (first.kind === 'ok') {
        state.mode = first.body;
    } else {
        state.pollFailed = true;
    }
    drawFrame();
    buildTabs();
    const wanted = tabFromAddress() || rememberedTab() || TABS[0].id;
    showTab(state.tabs.has(wanted.split('/')[0]) ? wanted : TABS[0].id, {replace: true});
    if (state.pollFailed) {
        serverSilent(first.body);
    }
    window.addEventListener('hashchange', () => {
        const target = tabFromAddress();
        if (target && target !== addressOf(state.active)) {
            showTab(target, {fromAddress: true});
        }
    });
    window.setInterval(() => poll(false), POLL_MILLIS);
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            poll(false);
        }
    });
}

/* ---------- /api/mode and the epoch ---------- */

async function poll(announceSame) {
    const answer = await api.get('/api/mode');
    if (answer.kind !== 'ok') {
        if (!state.pollFailed) {
            state.pollFailed = true;
            serverSilent(answer.body);
        }
        return;
    }
    const before = state.mode;
    const recovered = state.pollFailed;
    state.pollFailed = false;
    state.mode = answer.body;
    const changed = !before || before.epoch !== answer.body.epoch || before.mode !== answer.body.mode;
    // The write access can change without the epoch moving, when writes are unlocked or locked again.
    const frameChanged = changed || JSON.stringify(before) !== JSON.stringify(answer.body);
    if (frameChanged || recovered) {
        drawFrame();
    }
    if (recovered) {
        app.clearNotices();
    }
    if (changed) {
        if (before) {
            notice('The configuration changed. ' + environmentSentence(answer.body) + ' Every tab now shows it.');
        }
        // Every tab that has been drawn is redrawn now, whichever one is on screen. A tab that has
        // not been opened yet draws from the new configuration when it is.
        for (const entry of state.tabs.values()) {
            if (entry.built && entry.tab.onConfigChange) {
                entry.tab.onConfigChange(answer.body, before);
            }
        }
        app.chain.refresh();
    } else if (announceSame && before) {
        // Asked for after a refusal. Saying nothing would leave the tester wondering whether the screen is current.
        notice('The screen shows the configuration now in force. ' + environmentSentence(answer.body), {plain: true});
    }
}

function serverSilent(error) {
    notice(error.title + ' ' + error.action + ' This page keeps asking, and says so when the tool answers again.');
    drawStrip();
}

function environmentSentence(mode) {
    const environment = mode.environment || {};
    return environment.active
        ? 'The active environment is ' + environment.name + ' (' + environment.kind + '), on the ' + mode.mode + ' profile.'
        : 'No environment is active, on the ' + mode.mode + ' profile.';
}

/* ---------- the frame ---------- */

function drawFrame() {
    const mode = state.mode;
    if (mode) {
        document.getElementById('tool-name').textContent = mode.name;
        document.getElementById('tool-version').textContent = mode.version;
        const environment = mode.environment || {};
        // The browser tab carries the same facts as the masthead, so it can be told apart from the
        // other tools on the programme when it is narrowed to a few words.
        document.title = mode.name + ' ' + mode.version + ' | '
            + (environment.active ? environment.name + ' (' + environment.kind + ')' : 'no environment') + ' | ' + mode.mode;
    }
    drawStrip();
    drawBanners();
}

function stripItem(label, value, extraClass) {
    return el('span', {class: 'strip-item' + (extraClass ? ' ' + extraClass : '')},
        el('span', {class: 'strip-item__label'}, label), el('span', {class: 'strip-item__value'}, value));
}

function drawStrip() {
    const strip = document.getElementById('environment-strip');
    const mode = state.mode;
    if (!mode) {
        fill(strip, stripItem('Server', 'not answering', 'strip-item--alarm'));
        return;
    }
    const environment = mode.environment || {};
    const items = [];
    if (state.pollFailed) {
        items.push(stripItem('Server', 'not answering', 'strip-item--alarm'));
    }
    items.push(stripItem('Profile', [mode.mode === 'mock' ? el('span', {class: 'hatch', aria: {hidden: 'true'}}) : null, mode.mode]));
    items.push(stripItem('Target', mode.target));
    items.push(stripItem('Environment', environment.active
        ? [environment.name, el('span', {class: 'kind kind--' + token(environment.kind)}, environment.kind)]
        : 'none active'));
    items.push(ledgerItem());
    fill(strip, items);
}

/**
 * The ledger indicator.
 *
 * Seam: GET /api/runs/health arrives with the ledger. When it does, poll it beside /api/mode and
 * draw from it here: whether tracking is on, the count of runs today, and the loud form when it
 * is off, not writable or has failed a write. Until then the indicator repeats what the receipt
 * of the latest run on this page said, which is the only thing this page knows about the ledger.
 */
function ledgerItem() {
    const ledger = state.ledger;
    if (!ledger) {
        return stripItem('Ledger', 'no run from this page yet');
    }
    if (!ledger.recorded) {
        return stripItem('Ledger', 'the latest run was NOT recorded', 'strip-item--alarm');
    }
    return stripItem('Ledger', typeof ledger.runsToday === 'number'
        ? ledger.runsToday.toLocaleString() + (ledger.runsToday === 1 ? ' run recorded today' : ' runs recorded today')
        : 'recorded, count not stated');
}

function drawBanners() {
    const host = document.getElementById('banners');
    const mode = state.mode;
    clear(host);
    if (!mode) {
        return;
    }
    const environment = mode.environment || {};
    const access = mode.writeAccess || {};
    if (environment.active && environment.kind === 'prod') {
        host.append(banner('prod', 'Production', environment.name, access.reason));
    } else if (environment.active && environment.kind === 'uat') {
        host.append(banner('uat', 'UAT', environment.name, access.reason));
    }
    if (mode.mode === 'mock') {
        host.append(el('div', {class: 'banner banner--mock'}, el('div', {class: 'banner__inner'},
            el('span', {class: 'hatch', aria: {hidden: 'true'}}),
            el('span', null, el('strong', null, 'Mock profile.'),
                ' Scenario files answer in place of the service. No answer on this profile is about a real member, and nothing leaves this machine.'))));
    }
}

function banner(kind, label, name, reason) {
    return el('div', {class: 'banner banner--' + kind}, el('div', {class: 'banner__inner'},
        el('span', {class: 'banner__kind'}, label),
        el('span', {class: 'banner__name'}, name),
        reason ? el('span', {class: 'banner__reason'}, reason) : null));
}

function notice(message, options) {
    const host = document.getElementById('notices');
    const row = el('div', {class: 'notice' + (options && options.plain ? ' notice--plain' : '')}, el('span', null, message));
    row.append(button('Dismiss', () => row.remove(), {class: 'button--quiet button--small'}));
    // One notice at a time: a newer statement about the tool replaces the older one.
    fill(host, row);
}

/* ---------- tabs ---------- */

function buildTabs() {
    const list = document.getElementById('tabs');
    const main = document.getElementById('main');
    for (const tab of TABS) {
        const control = el('button', {
            type: 'button', class: 'tab', id: 'tab-' + tab.id, role: 'tab', tabIndex: -1,
            aria: {selected: 'false', controls: 'panel-' + tab.id},
            on: {click: () => showTab(tab.id)}
        }, tab.label);
        const panel = el('section', {id: 'panel-' + tab.id, role: 'tabpanel', hidden: true, aria: {labelledby: 'tab-' + tab.id}});
        list.append(control);
        main.append(panel);
        state.tabs.set(tab.id, {tab, panel, control, built: false});
    }
    list.addEventListener('keydown', event => {
        const ids = TABS.map(tab => tab.id);
        const at = ids.indexOf(state.active);
        let next = null;
        if (event.key === 'ArrowRight') {
            next = ids[(at + 1) % ids.length];
        } else if (event.key === 'ArrowLeft') {
            next = ids[(at + ids.length - 1) % ids.length];
        } else if (event.key === 'Home') {
            next = ids[0];
        } else if (event.key === 'End') {
            next = ids[ids.length - 1];
        }
        if (next) {
            event.preventDefault();
            showTab(next);
            state.tabs.get(next).control.focus();
        }
    });
}

function tabFromAddress() {
    const hash = window.location.hash.replace(/^#/, '');
    try {
        return hash.length > 0 ? decodeURIComponent(hash) : null;
    } catch (malformed) {
        // An address typed or pasted wrongly is not an error to report. The first tab is shown.
        return null;
    }
}

function addressOf(tabId) {
    const entry = state.tabs.get(tabId);
    const view = entry && entry.tab.address ? entry.tab.address() : null;
    return view ? tabId + '/' + view : tabId;
}

function rememberedTab() {
    try {
        return window.localStorage.getItem(TAB_KEY);
    } catch (storageOff) {
        return null;
    }
}

function rememberTab(tabId) {
    try {
        window.localStorage.setItem(TAB_KEY, tabId);
    } catch (storageOff) {
        // Storage can be switched off in the browser. Remembering the tab is a convenience only.
    }
}

/** Shows a tab. target is a tab id, optionally followed by a slash and a view inside the tab. */
function showTab(target, options) {
    const [tabId, ...rest] = String(target).split('/');
    const entry = state.tabs.get(tabId);
    if (!entry) {
        return;
    }
    for (const other of state.tabs.values()) {
        const selected = other === entry;
        other.control.setAttribute('aria-selected', selected ? 'true' : 'false');
        other.control.tabIndex = selected ? 0 : -1;
        other.panel.hidden = !selected;
    }
    state.active = tabId;
    if (!entry.built) {
        entry.tab.mount(entry.panel, app);
        entry.built = true;
    }
    if (entry.tab.onShow) {
        entry.tab.onShow(rest.join('/') || null);
    }
    app.chain.showFor(tabId);
    rememberTab(tabId);
    const address = '#' + addressOf(tabId);
    if (window.location.hash !== address) {
        if (options && (options.replace || options.fromAddress)) {
            window.history.replaceState(null, '', address);
        } else {
            window.history.pushState(null, '', address);
        }
    }
}

function navigate(target) {
    showTab(target);
    document.getElementById('main').focus({preventScroll: true});
    window.scrollTo(0, 0);
}

start();
