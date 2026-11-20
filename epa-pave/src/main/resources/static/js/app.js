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
import {useServerWords} from './result.js';
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
    ledger: null,         // what the ledger did with the latest run this page knows of
    health: null,         // the last answer of /api/runs/health, or null before the first
    healthUnread: false,  // true when the last ask of /api/runs/health got no usable answer
    stripKey: null,       // what the environment strip was last drawn from, so a poll that changes nothing is silent
    stripSaid: undefined, // what was last announced about the strip; undefined until it is first drawn
    operations: new Map(), // operation name -> its row of /api/config/effective, as the server sent it
    operationsRead: false, // true once /api/config/effective has answered at least once
    configListeners: [],   // parts of the page told after each change of the configuration, once the operations are read again
    placeholderList: null, // true when the code list in use is a placeholder, from the self-check; null before it is read
    codeListSource: null   // where the code list in use was read from, as the self-check names it; null before it is read
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
    /**
     * Re-reads /api/mode now. Used after the server says the configuration changed.
     * options.quiet: the caller has said on screen what it changed (the Config tab after one of its
     * own actions), so no notice is raised for it, except that production writes were locked again,
     * which is said wherever the tester is.
     */
    refreshMode: options => poll(true, options),
    /**
     * Tells the frame what the ledger did with the latest run, for the indicator in the masthead.
     * The ledger's own state is read again at once, so the count and any failure are current.
     */
    ledgerReceipt(receipt) {
        state.ledger = {recorded: Boolean(receipt && receipt.recorded), runsToday: receipt ? receipt.runsToday : undefined,
            message: receipt ? receipt.message : undefined};
        drawStrip();
        readHealth();
    },
    /** Tells the frame that runs made elsewhere, such as by a suite, were not recorded. */
    ledgerProblem(message) {
        state.ledger = {recorded: false, message};
        drawStrip();
        readHealth();
    },
    /**
     * Hands the frame an answer of GET /api/runs/health that a tab has read itself, so the
     * masthead says the same as the tab at the same moment. The frame reads it on its own as well.
     */
    ledgerHealth(health) {
        if (health && typeof health === 'object') {
            state.health = health;
            state.healthUnread = false;
            drawStrip();
        }
    },
    /** The label of an operation as /api/config/effective words it, or null when the server has not listed it. */
    operationLabel(name) {
        const row = state.operations.get(name);
        return row && row.label ? row.label : null;
    },
    /**
     * The row /api/config/effective gives for an operation, named as that endpoint names it
     * ("CRD_ORDER_SIGN"): where it would be sent, and in "unavailable" the server's sentence when
     * it cannot be sent as things stand. Null when the server has not answered yet or does not list it.
     */
    operationRow(name) {
        return state.operations.get(name) || null;
    },
    /** True once /api/config/effective has answered, so that a missing row means "not listed" and not "not read yet". */
    operationsRead: () => state.operationsRead,
    /**
     * Calls listener() after every change of the configuration, once the frame has read the
     * operations again, so what it reads through operationRow() is current. Returns a function that stops it.
     */
    whenConfigChanges(listener) {
        state.configListeners.push(listener);
        return () => {
            state.configListeners = state.configListeners.filter(each => each !== listener);
        };
    },
    /**
     * True when the code list in use is a placeholder, false when it is not, null while that is not
     * known. A placeholder is the one that ships with the tool or any list whose rows say of
     * themselves that they are not from a P32H export: the self-check decides, not this page.
     */
    codeListIsPlaceholder: () => state.placeholderList,
    /** Where the code list in use was read from, in the self-check's words, or null. */
    codeListSource: () => state.codeListSource,
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

    // The result view words an operation and the code list the way the server does. It reads them
    // through the frame, which keeps them current, so no tab has to pass them in.
    useServerWords({operationLabel: name => app.operationLabel(name), codeListIsPlaceholder: () => app.codeListIsPlaceholder(),
        codeListSource: () => app.codeListSource()});

    const first = await api.get('/api/mode');
    if (first.kind === 'ok') {
        state.mode = first.body;
    } else {
        state.pollFailed = true;
    }
    // Both are awaited before any tab is drawn, so the first result already has its words.
    await Promise.all([readHealth(), readOperations(), readCodeList()]);
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

async function poll(announceSame, options) {
    const quiet = Boolean(options && options.quiet);
    // The ledger is asked on the same beat as the mode, and on its own: a ledger that stops
    // recording is news whether or not the configuration moved.
    readHealth();
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
        if (before && (!quiet || relocked(before, answer.body))) {
            notice('The configuration changed. ' + environmentSentence(answer.body) + ' Every tab now shows it.'
                + (relocked(before, answer.body) ? ' Writes to production were unlocked and are locked again: the server locks them on any change '
                    + 'to the configuration. Unlock them again on the Config tab if they are still needed.' : ''));
        }
        // A new collection can carry operations under other words; they are read before any tab redraws.
        await readOperations();
        for (const listener of state.configListeners) {
            listener();
        }
        // Every tab that has been drawn is redrawn now, whichever one is on screen. A tab that has
        // not been opened yet draws from the new configuration when it is.
        for (const entry of state.tabs.values()) {
            if (entry.built && entry.tab.onConfigChange) {
                entry.tab.onConfigChange(answer.body, before);
            }
        }
        app.chain.refresh();
    } else if (before && JSON.stringify(before.writeAccess) !== JSON.stringify(answer.body.writeAccess)) {
        // Writes were unlocked or locked again with no change of the configuration. A lock from any
        // browser ends the unlock of every session and leaves the epoch where it was, so without this
        // a panel drawn while unlocked would go on saying so beside a banner that says locked.
        if (!quiet && relocked(before, answer.body)) {
            notice('Writes to production were locked again. This browser did not lock them, so it may have been done from another '
                + 'browser: locking from any browser ends the unlock of every session. Every tab now shows them as locked. '
                + 'Unlock them again on the Config tab if they are still needed.');
        }
        for (const entry of state.tabs.values()) {
            if (entry.built && entry.tab.onWriteAccessChange) {
                entry.tab.onWriteAccessChange(answer.body, before);
            }
        }
    } else if (announceSame && before && !quiet) {
        // Asked for after a refusal. Saying nothing would leave the tester wondering whether the screen is current.
        notice('The screen shows the configuration now in force. ' + environmentSentence(answer.body), {plain: true});
    }
}

/** True when production writes were unlocked in the earlier answer and the later one, still on production, has them locked. */
function relocked(before, after) {
    const was = before.writeAccess || {};
    const now = after.writeAccess || {};
    return was.production === true && was.writesAllowed === true && now.production === true && now.writesAllowed !== true;
}

/** Reads whether the ledger is recording. A failure to read it is drawn as such, never as "recording". */
async function readHealth() {
    const answer = await api.get('/api/runs/health');
    if (answer.kind === 'ok') {
        state.health = answer.body;
        state.healthUnread = false;
    } else {
        state.healthUnread = true;
    }
    drawStrip();
}

/**
 * Reads each operation's row: its label, where it would be sent, and why it cannot be sent when it
 * cannot. Kept from the last good answer when a read fails; until one answer has arrived nothing is
 * assumed about any operation.
 */
async function readOperations() {
    const answer = await api.get('/api/config/effective');
    if (answer.kind === 'ok' && Array.isArray(answer.body.operations)) {
        state.operations = new Map(answer.body.operations.map(row => [row.operation, row]));
        state.operationsRead = true;
    }
}

/** Reads once whether the code list is a placeholder, and where it came from. Neither can change without a restart. */
async function readCodeList() {
    const answer = await api.get('/api/procedure-codes/self-check');
    if (answer.kind === 'ok' && typeof answer.body.placeholder === 'boolean') {
        state.placeholderList = answer.body.placeholder;
        state.codeListSource = typeof answer.body.source === 'string' ? answer.body.source : null;
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
        const access = mode.writeAccess || {};
        const unlocked = environment.active && environment.kind === 'prod' && access.production === true && access.writesAllowed === true;
        document.title = mode.name + ' ' + mode.version + ' | '
            + (environment.active ? environment.name + ' (' + environment.kind + ')' : 'no environment')
            + (unlocked ? ' | writes UNLOCKED' : '') + ' | ' + mode.mode;
    }
    drawStrip();
    drawBanners();
}

/**
 * The strip is not a live region: a new count of today's runs after every run would be read out
 * on top of the result's own status line. What is announced, from a small region of its own, is a
 * change of where the tool is pointed or of what the ledger is doing, once, and only after the
 * first drawing, which describes the page as it opens.
 */
function announceStrip(text) {
    if (state.stripSaid === undefined) {
        state.stripSaid = text;
        return;
    }
    if (text === state.stripSaid) {
        return;
    }
    state.stripSaid = text;
    const region = document.getElementById('strip-news');
    if (region) {
        region.textContent = text;
    }
}

function stripItem(label, value, extraClass) {
    return el('span', {class: 'strip-item' + (extraClass ? ' ' + extraClass : '')},
        el('span', {class: 'strip-item__label'}, label), el('span', {class: 'strip-item__value'}, value));
}

function drawStrip() {
    const strip = document.getElementById('environment-strip');
    const mode = state.mode;
    // The strip is a live region. It is redrawn only when what it says changes, so a poll that
    // finds everything as it was is not read out again.
    const key = JSON.stringify([mode, state.pollFailed, state.ledger, state.health, state.healthUnread]);
    if (key === state.stripKey) {
        return;
    }
    state.stripKey = key;
    if (!mode) {
        fill(strip, stripItem('Server', 'not answering', 'strip-item--alarm'));
        return;
    }
    const environment = mode.environment || {};
    const ledger = ledgerItem();
    announceStrip([state.pollFailed ? 'The server is not answering.' : null,
        'Profile ' + mode.mode + ', target ' + mode.target + ', '
        + (environment.active ? 'environment ' + environment.name + ' (' + environment.kind + ').' : 'no environment active.'),
        ledger.state].filter(Boolean).join(' '));
    const items = [];
    if (state.pollFailed) {
        items.push(stripItem('Server', 'not answering', 'strip-item--alarm'));
    }
    items.push(stripItem('Profile', [mode.mode === 'mock' ? el('span', {class: 'hatch', aria: {hidden: 'true'}}) : null, mode.mode]));
    items.push(stripItem('Target', mode.target));
    items.push(stripItem('Environment', environment.active
        ? [environment.name, el('span', {class: 'kind kind--' + token(environment.kind)}, environment.kind)]
        : 'none active'));
    items.push(ledger.node);
    fill(strip, items);
}

/**
 * The ledger indicator, drawn from GET /api/runs/health, which is read on every poll.
 *
 * Quiet while the ledger is recording: the count of today's runs, where today is the UTC day the
 * ledger files runs under, and it says so. Loud, in words, when tracking is off, when the ledger
 * cannot write, when today's count cannot be read, and when the latest run made from this page was
 * not recorded: the receipt of a run is the last word about that run, whatever the ledger says of
 * itself. A write that failed earlier, while the ledger records again now, is said in visible words
 * beside the quiet count, never only in a tooltip, and not in the heavy frame: the frame means the
 * ledger is not recording now. The words lead to the History tab, whose health panel says what each
 * state means and what to do.
 *
 * Returns {node, state}: state is a short sentence of what the ledger is doing, without the count,
 * so a change of it can be announced and a new count alone is not.
 */
function ledgerItem() {
    const health = state.health;
    const receipt = state.ledger;
    const problems = [];
    if (state.healthUnread) {
        problems.push(['state cannot be read', 'The tool did not answer when asked whether the ledger is recording.']);
    }
    let failed = null;
    let unreadable = null;
    if (health) {
        if (health.unreadableLines > 0) {
            unreadable = health.unreadableLines.toLocaleString() + (health.unreadableLines === 1 ? ' line unreadable' : ' lines unreadable');
        }
        if (!health.enabled) {
            problems.push(['tracking is OFF', 'Tracking is switched off: runs made now are not recorded anywhere.']);
        } else if (!health.writable) {
            problems.push(['cannot write, runs NOT recorded', 'The ledger cannot write to its folder: runs made now are NOT recorded.']);
        }
        if (health.failedWrites > 0) {
            failed = health.failedWrites.toLocaleString() + (health.failedWrites === 1 ? ' write failed' : ' writes failed');
        }
        if (health.enabled && typeof health.runsToday !== 'number') {
            problems.push(['today\'s count unreadable', 'The ledger could not read today\'s file, so it cannot say how many runs it holds. That is not the same as none.']);
        }
    }
    if (receipt && !receipt.recorded) {
        problems.push(['latest run here NOT recorded', 'The latest run made from this page was NOT recorded. Its result quotes what the ledger said.']);
    }
    const toHistory = ' The History tab says what it means and what to do.';
    if (problems.length > 0) {
        if (failed) {
            problems.push([failed, failed.charAt(0).toUpperCase() + failed.slice(1) + ' since the tool started. Those runs are not in the ledger.']);
        }
        if (unreadable) {
            problems.push([unreadable, unreadable.charAt(0).toUpperCase() + unreadable.slice(1) + ': left out of History and of the export.']);
        }
        const words = problems.map(([each]) => each).join('; ');
        const sentence = problems.map(([, each]) => each).join(' ') + toHistory;
        return {node: ledgerLink(words, 'strip-item--alarm', sentence), state: 'Ledger: ' + words + '.'};
    }
    const counted = health && typeof health.runsToday === 'number' ? health.runsToday
        : receipt && typeof receipt.runsToday === 'number' ? receipt.runsToday : null;
    if (counted === null) {
        return {node: stripItem('Ledger', 'not read yet'), state: null};
    }
    const day = new Date().toISOString().slice(0, 10);
    const recording = 'Recording. Counted over the UTC day ' + day + ', which is how the ledger files its runs, not the date on your clock.';
    // Said after the count, in visible words: runs that were made and that History cannot show.
    const earlier = [failed ? failed + ' earlier, since the tool started' : null, unreadable].filter(Boolean).join('; ') || null;
    const sentence = recording + (failed ? ' ' + failed.charAt(0).toUpperCase() + failed.slice(1)
        + ' earlier, since the tool started: those runs are not in the ledger. It records again now.' : '')
        + (unreadable ? ' ' + unreadable.charAt(0).toUpperCase() + unreadable.slice(1)
            + ': a line of the ledger that cannot be read is left out of History and of the export.' : '') + toHistory;
    return {node: ledgerLink(counted.toLocaleString() + (counted === 1 ? ' run' : ' runs') + ' today (UTC)', null, sentence, earlier),
        state: 'Ledger recording.' + (earlier ? ' ' + earlier.charAt(0).toUpperCase() + earlier.slice(1) + '.' : '')};
}

/**
 * The indicator as a link to the History tab, where the health of the ledger is set out in full.
 * The sentence is the title for a mouse, and is part of the link's name for assistive technology,
 * so a keyboard or screen reader user is not left without it. earlier, when given, is said in
 * visible words after the link.
 */
function ledgerLink(text, extraClass, sentence, earlier) {
    return el('span', {class: 'strip-item' + (extraClass ? ' ' + extraClass : ''), title: sentence},
        el('span', {class: 'strip-item__label'}, 'Ledger'),
        el('a', {class: 'strip-item__value strip-item__link', href: '#history'}, text, el('span', {class: 'visually-hidden'}, '. ' + sentence)),
        earlier ? el('span', {class: 'strip-item__earlier'}, '; ' + earlier) : null);
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
        // Whether writes go is the first thing a tester on production needs, so it is said in two
        // words before the server's sentence, from the server's own flag. Unlocked is framed, so it
        // reads differently from across the room as well.
        const unlocked = access.production === true && access.writesAllowed === true;
        host.append(banner('prod', 'Production', environment.name, access.reason,
            el('span', {class: 'banner__writes' + (unlocked ? ' banner__writes--unlocked' : '')},
                unlocked ? 'Writes UNLOCKED for this browser session' : 'Writes locked')));
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

function banner(kind, label, name, reason, writes) {
    return el('div', {class: 'banner banner--' + kind}, el('div', {class: 'banner__inner'},
        el('span', {class: 'banner__kind'}, label),
        el('span', {class: 'banner__name'}, name),
        writes || null,
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
