// The frame: the top bar, the three tabs and their addresses, the lines under the top bar, and
// the poll of /api/mode and /api/runs/health every 4 s and whenever the window comes back.
//
// The top bar says what this tool is and where it is pointed, whatever view is open: the active
// environment by its whole name and its kind, MOCK or LIVE, how calls sign in, and the build. On
// an environment of kind prod the whole bar turns purple and the line under it says whether writes
// go, with the guardrail's own sentence, never folded and never cut.

import {call, watch, restarted} from './api.js';
import {el, clear, fill, button, chip} from './dom.js';
import {UNREACHABLE_LINE, PAGE_FAULT_LINE, notJsonLine, setFilesChanged} from './fault.js';
import {useEpoch} from './words.js';

// The one thing this tool keeps in browser storage: which tab was open. It holds no member data,
// and StaticFrontendTest fails the build if any script stores under another key.
const TAB_KEY = 'pave.tab';

const POLL_MILLIS = 4000;

// A view is drawn once the profile is known, so nothing that exists on mock only is drawn on a
// guess. A server that hangs must not hold the page blank, so the wait ends after this long.
const FIRST_ANSWER_WAIT_MILLIS = 5000;

// A view is loaded the first time its tab opens: a defect in the scripts of one view then leaves
// the frame and the other views working, where a static import would leave a blank page.
const TABS = [
    {id: 'workbench', label: 'Workbench', load: () => import('./workbench.js')},
    {id: 'audit', label: 'Audit', load: () => import('./audit.js')},
    {id: 'configuration', label: 'Configuration', load: () => import('./configuration.js')}
];

const CONFIG_CHANGED = 'The configuration changed in another window. This page now shows it.';
const STARTED_AGAIN = 'PAVE was started again.';
const WRITES_RELOCKED = 'Production writes were locked again from another window.';

const state = {
    mode: null,               // the last answer of /api/mode, as drawn
    answering: true,          // false from the first call that got no answer until the next that did
    health: null,             // the last answer of /api/runs/health
    healthRead: true,         // false when the last ask of /api/runs/health got an answer that could not be used
    environments: null,       // the last answer of /api/config/environments
    chooserKey: null,         // what the chooser was last drawn from, so a poll that changes nothing leaves it alone
    activating: false,        // an activation chosen in the top bar is on its way
    madeHere: [],             // epochs answered to changes made from this page, which need no notice
    writesMadeHere: null,     // the write access answered to an unlock or a lock made from this page
    failing: new Set(),       // calls whose last answer was not JSON, already said under the top bar
    notice: null,             // {text, id, row} of the line under the top bar, or null
    view: null,               // {tab, root, handle, route} of the view on screen
    showing: 0,               // counts view changes, so a view that loads late is not drawn over a newer one
    announced: undefined,     // what was last said about the environment; undefined until the first drawing
    busy: {},                 // the poll of each address that is on its way, so polls never pile up
    again: {}                 // a poll asked for while one was on its way, run when it ends
};

const listeners = {mode: new Set(), config: new Set(), writes: new Set()};

export const app = {
    /** The last answer of GET /api/mode, or null before the first. */
    mode() {
        return state.mode;
    },
    /** True on the mock profile. False before the first answer, so nothing that exists on mock only is drawn on a guess. */
    isMock() {
        return Boolean(state.mode) && state.mode.mode === 'mock';
    },
    /** The configuration epoch the screens are drawn from. A run sends it back as configEpoch. */
    epoch() {
        return state.mode && typeof state.mode.epoch === 'number' ? state.mode.epoch : null;
    },
    /**
     * Calls fn(mode, before) on 'mode' (the answer of /api/mode changed in any way), 'config' (the
     * epoch moved: the configuration changed) or 'writes' (the write access changed, with or
     * without the epoch). Returns a function that stops it, for the view to call when unmounted.
     */
    on(name, fn) {
        const set = listeners[name];
        if (!set || typeof fn !== 'function') {
            return () => false;
        }
        set.add(fn);
        return () => set.delete(fn);
    },
    /** Shows the view of an address, '#audit/runs/<runId>' or 'audit/runs/<runId>', as a new step of the browser's history. */
    go(hash) {
        const before = state.view ? state.view.tab : null;
        show(routeOf(hash), true);
        if (state.view && state.view.tab !== before) {
            document.getElementById('main').focus({preventScroll: true});
            window.scrollTo(0, 0);
        }
    },
    /**
     * One line under the top bar, in place of the line before it, with "Dismiss this notice".
     * tone 'fault' frames it heavily, for a fault to report; any other tone is plain. With no
     * text, the line is taken away.
     */
    notice(text, tone, link) {
        if (text === undefined || text === null || String(text) === '') {
            unsay(null);
            return;
        }
        say(String(text), tone, 'view', link);
    }
};

/* ---------- start ---------- */

async function start() {
    // A defect of this page must not leave a tester looking at a screen that silently stopped half
    // way. The detail stays in the browser console: an error message can quote what it handled.
    window.addEventListener('error', () => say(PAGE_FAULT_LINE, 'fault', 'page'));
    window.addEventListener('unhandledrejection', () => say(PAGE_FAULT_LINE, 'fault', 'page'));
    useEpoch(() => app.epoch());
    watch(heard);
    wireTabs();
    wireChooser();
    await Promise.race([poll('/api/mode'), pause(FIRST_ANSWER_WAIT_MILLIS)]);
    poll('/api/runs/health');
    poll('/api/config/environments');
    show(routeOf(window.location.hash), false);
    window.addEventListener('hashchange', () => show(routeOf(window.location.hash), false));
    window.setInterval(() => {
        if (!document.hidden) {
            tick();
        }
    }, POLL_MILLIS);
    document.addEventListener('visibilitychange', () => {
        if (!document.hidden) {
            tick();
        }
    });
    window.addEventListener('focus', tick);
}

function tick() {
    poll('/api/mode');
    poll('/api/runs/health');
}

function pause(millis) {
    return new Promise(resolve => window.setTimeout(resolve, millis));
}

/* ---------- reading the server ---------- */

const READERS = {
    '/api/mode': received,
    '/api/runs/health': health => {
        state.health = health;
        state.healthRead = true;
        drawLedger();
    },
    '/api/config/environments': list => {
        state.environments = list;
        drawChooser();
    }
};

/** Reads an address the frame draws from. One read of each at a time; one asked for meanwhile runs after it. */
function poll(path) {
    if (state.busy[path]) {
        state.again[path] = true;
        return state.busy[path];
    }
    state.busy[path] = (async () => {
        try {
            do {
                state.again[path] = false;
                const answer = await call('GET', path);
                if (answer.ok && answer.data && typeof answer.data === 'object') {
                    READERS[path](answer.data);
                } else if (path === '/api/runs/health' && answer.error && answer.error.kind !== 'UNREACHABLE') {
                    // An answer, and not the ledger's: what the ledger is doing cannot be said, which is not "recording".
                    state.healthRead = false;
                    drawLedger();
                }
            } while (state.again[path]);
        } finally {
            state.busy[path] = null;
        }
    })();
    return state.busy[path];
}

/**
 * Hears every answer the page gets. PAVE not answering, or answering with something that is not
 * JSON, is said once under the top bar, whichever view asked. A change of the configuration made
 * from this page is read again at once, and its epoch is kept, so it is not announced as a change
 * made in another window.
 */
function heard({method, path, answer}) {
    const kind = !answer.ok && answer.error ? answer.error.kind : null;
    if (kind === 'UNREACHABLE') {
        if (state.answering) {
            state.answering = false;
            say(UNREACHABLE_LINE, 'fault', 'unreachable');
        }
        return;
    }
    if (!state.answering) {
        state.answering = true;
        unsay('unreachable');
    }
    const key = method + ' ' + path;
    if (kind === 'NOT_JSON') {
        if (!state.failing.has(key)) {
            state.failing.add(key);
            say(notJsonLine(answer.error.status), 'fault', 'not-json');
        }
    } else if (state.failing.delete(key) && state.failing.size === 0) {
        unsay('not-json');
    }
    if (kind === 'PAGE_FAULT') {
        say(PAGE_FAULT_LINE, 'fault', 'page');
    }
    if (answer.status === 409) {
        poll('/api/mode');
    }
    if (method !== 'GET') {
        if (path.startsWith('/api/config/')) {
            if (answer.ok && answer.data && typeof answer.data === 'object') {
                if (typeof answer.data.epoch === 'number') {
                    state.madeHere = state.madeHere.concat(answer.data.epoch).slice(-20);
                }
                if (path === '/api/config/writes') {
                    state.writesMadeHere = JSON.stringify(answer.data);
                }
            }
            poll('/api/mode');
            poll('/api/config/environments');
        }
        poll('/api/runs/health');
    }
}

function received(next) {
    const before = state.mode;
    if (before && before.mode !== next.mode) {
        // PAVE was started again on the other profile. Everything this page drew belongs to the
        // profile it was drawn for, and a view must never keep what exists on mock only on live.
        window.location.reload();
        return;
    }
    if (before && JSON.stringify(before) === JSON.stringify(next)) {
        return;
    }
    state.mode = next;
    setFilesChanged(Boolean(next.build && next.build.changedOnDisk === true));
    const epochMoved = Boolean(before) && before.epoch !== next.epoch;
    const writesChanged = Boolean(before) && JSON.stringify(before.writeAccess) !== JSON.stringify(next.writeAccess);
    drawFrame();
    if (epochMoved) {
        if (restarted(before, next)) {
            // The epoch moves at every start: a restart is not a change made in another window.
            say(STARTED_AGAIN, 'plain', 'config');
        } else if (!state.madeHere.includes(next.epoch)) {
            say(CONFIG_CHANGED, 'plain', 'config');
        }
        poll('/api/config/environments');
    } else if (writesChanged && relocked(before, next) && JSON.stringify(next.writeAccess) !== state.writesMadeHere) {
        say(WRITES_RELOCKED, 'plain', 'writes');
    }
    if (writesChanged) {
        state.writesMadeHere = null;
    }
    emit('mode', next, before);
    if (epochMoved) {
        emit('config', next, before);
    }
    if (writesChanged) {
        emit('writes', next, before);
    }
}

/** True when production writes were unlocked before and the later answer, still on production, has them locked. */
function relocked(before, after) {
    const was = before.writeAccess || {};
    const now = after.writeAccess || {};
    return was.production === true && was.writesAllowed === true && now.production === true && now.writesAllowed !== true;
}

function emit(name, mode, before) {
    for (const fn of Array.from(listeners[name])) {
        try {
            fn(mode, before);
        } catch (defect) {
            say(PAGE_FAULT_LINE, 'fault', 'page');
        }
    }
}

/* ---------- the top bar ---------- */

function activeEnvironment() {
    const environment = state.mode && state.mode.environment;
    return environment && environment.active ? environment : null;
}

function writesUnlocked() {
    const access = (state.mode && state.mode.writeAccess) || {};
    return access.production === true && access.writesAllowed === true;
}

function drawFrame() {
    const mode = state.mode;
    const environment = activeEnvironment();
    const prod = Boolean(environment) && environment.kind === 'prod';
    document.getElementById('topbar').classList.toggle('topbar--prod', prod);
    document.getElementById('tool-name').textContent = mode.name || 'PAVE';
    // The browser tab carries the same facts as the top bar, so it can be told apart from the
    // other tools of the programme when it is narrowed to a few words.
    document.title = (mode.name || 'PAVE') + ' ' + version(mode) + ' | '
        + (environment ? environment.name + ' (' + environment.kind + ')' : 'no environment')
        + (prod && writesUnlocked() ? ' | writes UNLOCKED' : '') + ' | ' + mode.mode;

    const kind = document.getElementById('kind');
    kind.hidden = !environment;
    kind.className = environment ? 'badge badge--' + environment.kind : 'badge';
    kind.textContent = environment ? environment.kind : '';

    const profile = document.getElementById('profile');
    const mock = mode.mode === 'mock';
    const live = mode.mode === 'live';
    profile.hidden = false;
    profile.className = mock ? 'badge badge--mock' : live ? 'badge badge--live' : 'badge';
    // The server's own word when it is neither: a profile this page does not know is not called live.
    profile.textContent = mock ? 'MOCK' : live ? 'LIVE' : String(mode.mode);
    profile.title = mock ? 'Mock profile: scenarios answer and nothing leaves this machine.'
        : live ? 'Live profile: calls go to the active environment\'s service.' : '';

    // How calls sign in, in the server's words. Before the server says it, nothing is drawn for it.
    const signIn = mode.signIn && typeof mode.signIn === 'object' && mode.signIn.short ? mode.signIn : null;
    const signInHost = document.getElementById('sign-in');
    signInHost.hidden = !signIn;
    fill(signInHost, signIn ? chip(signIn.short, {tone: signIn.state === 'FAILED' ? 'fault' : null, title: signIn.detail || signIn.short}) : null);

    drawStamp(mode);
    drawChooser();
    drawEnvironmentLine();
    drawFilesLine(mode);
    announce(environment ? 'The active environment is ' + environment.name + ' (' + environment.kind + ').' : 'No environment is active.');
}

function version(mode) {
    return mode.build && typeof mode.build === 'object' && mode.build.version ? String(mode.build.version) : String(mode.version || '');
}

/**
 * The build by its time, "11-23 06:10 UTC" where there is room and "11-23 06:10" below that, so a
 * tester sees at a glance whether the PAVE in front of them is the one just built. The whole,
 * "build 1.0.0, 2026-11-23 06:10 UTC", is in the title. A build that states no time is named by its
 * version alone, "build 1.0.0".
 */
function drawStamp(mode) {
    const stamp = document.getElementById('stamp');
    const shown = version(mode);
    const time = mode.build && typeof mode.build === 'object' && mode.build.time ? stampTime(mode.build.time) : null;
    stamp.hidden = shown === '' && !time;
    stamp.title = 'build ' + shown + (time ? ', ' + time.whole : '');
    if (time) {
        fill(stamp, el('span', {class: 'stamp-full'}, time.day + ' UTC'), el('span', {class: 'stamp-short'}, time.day));
    } else {
        fill(stamp, el('span', {class: 'stamp-full'}, 'build ' + shown), el('span', {class: 'stamp-short'}, 'build ' + shown));
    }
}

/** {whole: "2026-11-23 06:10 UTC", day: "11-23 06:10"}, or null for a time that cannot be read. */
function stampTime(text) {
    const date = new Date(text);
    if (Number.isNaN(date.getTime())) {
        return null;
    }
    const iso = date.toISOString();
    return {whole: iso.slice(0, 10) + ' ' + iso.slice(11, 16) + ' UTC', day: iso.slice(5, 10) + ' ' + iso.slice(11, 16)};
}

/**
 * The chooser lists every environment by its whole name: the unlock of production asks for the
 * name exactly as it is shown, and two names can differ only at their end. It is drawn again only
 * when what it lists changes, so a poll never closes it under the tester's pointer.
 */
function drawChooser() {
    const select = document.getElementById('environment');
    const environment = activeEnvironment();
    const list = state.environments && Array.isArray(state.environments.environments) ? state.environments.environments : null;
    const key = JSON.stringify([environment ? [environment.name, environment.kind] : null,
        list ? list.map(each => [each.id, each.name]) : null, state.activating]);
    if (key === state.chooserKey) {
        return;
    }
    state.chooserKey = key;
    const options = [];
    if (!environment) {
        options.push(el('option', {value: '', selected: true, disabled: true}, 'No environment active'));
    }
    if (list) {
        for (const each of list) {
            options.push(el('option', {value: each.id, selected: Boolean(environment) && each.name === environment.name}, each.name));
        }
    }
    if (environment && (!list || !list.some(each => each.name === environment.name))) {
        // The list could not be read, or does not hold the active one yet: the active one is still named.
        options.push(el('option', {value: '', selected: true}, environment.name));
    }
    fill(select, options);
    select.disabled = state.activating || !list || list.length === 0;
    select.title = environment ? environment.name : 'No environment active';
}

function wireChooser() {
    const select = document.getElementById('environment');
    select.addEventListener('change', async () => {
        const list = state.environments && Array.isArray(state.environments.environments) ? state.environments.environments : [];
        const chosen = list.find(each => each.id === select.value);
        const environment = activeEnvironment();
        if (!chosen || (environment && chosen.name === environment.name)) {
            return;
        }
        state.activating = true;
        drawChooser();
        const answer = await call('POST', '/api/config/environments/' + encodeURIComponent(chosen.id) + '/activate');
        state.activating = false;
        state.chooserKey = null;
        if (answer.ok) {
            const warnings = answer.data && Array.isArray(answer.data.addressWarnings) ? answer.data.addressWarnings.filter(Boolean) : [];
            if (warnings.length > 0) {
                say(warnings.join(' '), 'plain', 'activate');
            }
        } else if (answer.error && answer.error.title) {
            // Refused by the server, in its words. No answer at all is said by the frame already.
            say([answer.error.title, answer.error.action].filter(Boolean).join(' '), 'plain', 'activate');
        }
        drawChooser();
        await Promise.all([poll('/api/mode'), poll('/api/config/environments')]);
    });
}

/* ---------- the lines under the top bar ---------- */

function drawEnvironmentLine() {
    const host = document.getElementById('line-environment');
    const environment = activeEnvironment();
    if (environment && environment.kind === 'prod') {
        const access = state.mode.writeAccess || {};
        const unlocked = writesUnlocked();
        // Whether writes go is said in two words before the guardrail's own sentence, from the
        // server's flag. The name is whole: the unlock asks for it exactly as it is written here.
        fill(host, el('div', {class: 'line line--prod'},
            el('strong', {class: 'line__kind'}, 'Production'),
            el('span', {class: 'line__name environment-name'}, environment.name),
            el('span', {class: unlocked ? 'line__writes line__writes--unlocked' : 'line__writes'},
                unlocked ? 'Writes UNLOCKED for this browser session' : 'Writes locked'),
            access.reason ? el('span', {class: 'line__reason'}, access.reason) : null,
            el('a', {class: 'line__link', href: '#configuration/production-writes'}, 'Production writes')));
    } else if (environment && environment.kind === 'uat') {
        fill(host, el('div', {class: 'line line--uat'},
            el('strong', {class: 'line__kind'}, 'UAT'),
            el('span', {class: 'line__name environment-name'}, environment.name)));
    } else {
        clear(host);
    }
}

/** The words of the line shown while PAVE's own files are newer on disk than the PAVE that runs (plan R-24). */
export const FILES_CHANGED_LINE = 'PAVE\'s files changed on disk since it started. Stop PAVE and start it again.';

/**
 * A line under the top bar, with no dismiss, while the server says its classes or its page files
 * changed on disk since it started, as the IDE does when it rebuilds under a running PAVE: the
 * page is then served fresh while the server keeps its old classes, and nothing else would say so.
 */
function drawFilesLine(mode) {
    const host = document.getElementById('line-files');
    const changed = mode.build && typeof mode.build === 'object' && mode.build.changedOnDisk === true;
    if (changed) {
        fill(host, el('div', {class: 'line line--alarm'}, el('p', {class: 'line__text'}, FILES_CHANGED_LINE)));
    } else {
        clear(host);
    }
}

/**
 * The words of the ledger alarm for a LedgerHealth: what the ledger is not doing, or null while it
 * records. LedgerHealth carries no sentence, so the page words it, the same here and in Audit. The
 * ledger's own lastError, when it has one, is for the caller to show beneath.
 */
export function ledgerLine(health) {
    if (!health || typeof health !== 'object') {
        return null;
    }
    if (health.enabled === false) {
        return 'Tracking is switched off: runs made now are not recorded anywhere.';
    }
    if (health.writable === false) {
        return 'The ledger cannot write to its folder: runs made now are NOT recorded.';
    }
    return null;
}

/**
 * The count of today's runs on the Audit tab, and a line under the top bar, with no dismiss, while
 * the ledger is not recording: the failure that matters is tracking that silently stopped.
 */
function drawLedger() {
    const count = document.getElementById('audit-count');
    const host = document.getElementById('line-ledger');
    const health = state.health;
    let problem = null;
    if (!state.healthRead) {
        problem = 'PAVE did not answer when asked whether the ledger is recording.';
    } else {
        problem = ledgerLine(health);
    }
    if (problem) {
        // The ledger's own sentence about its last failed write, when it has one, follows the page's.
        const said = health && health.writable === false && typeof health.lastError === 'string' && health.lastError !== ''
            ? health.lastError : null;
        fill(host, el('div', {class: 'line line--alarm'}, el('p', {class: 'line__text'}, problem),
            said ? el('p', {class: 'line__detail'}, said) : null));
        fill(count, el('span', {'aria-hidden': 'true'}, '!'), el('span', {class: 'visually-hidden'}, ' ' + problem));
        count.className = 'tabs__count tabs__count--alarm';
        count.title = problem;
        count.hidden = false;
        return;
    }
    clear(host);
    if (!health) {
        count.hidden = true;
        return;
    }
    const failed = typeof health.failedWrites === 'number' && health.failedWrites > 0
        ? ' ' + health.failedWrites + (health.failedWrites === 1 ? ' write' : ' writes')
          + ' failed earlier, since PAVE started: those runs are not in the ledger.' : '';
    if (typeof health.runsToday === 'number') {
        const counted = health.runsToday + (health.runsToday === 1 ? ' run' : ' runs') + ' recorded today (UTC)';
        const sentence = failed ? counted + '.' + failed : counted;
        fill(count, el('span', {'aria-hidden': 'true'}, String(health.runsToday)), el('span', {class: 'visually-hidden'}, ' ' + sentence));
        count.title = sentence;
    } else {
        // Not none: unknown. A count that cannot be read is never drawn as 0.
        const sentence = 'The ledger could not read today\'s file, so it cannot say how many runs it holds. That is not the same as none.'
            + failed;
        fill(count, el('span', {'aria-hidden': 'true'}, '?'), el('span', {class: 'visually-hidden'}, ' ' + sentence));
        count.title = sentence;
    }
    count.className = failed ? 'tabs__count tabs__count--failed' : 'tabs__count';
    count.hidden = false;
}

/** One line under the top bar at a time: a newer one replaces the older, and the same line is not drawn twice. */
function say(text, tone, id, link) {
    if (state.notice && state.notice.text === text) {
        return;
    }
    const host = document.getElementById('line-notice');
    const row = el('div', {class: tone === 'fault' ? 'line line--notice line--fault' : 'line line--notice'},
        el('p', {class: 'line__text'}, text,
            link && link.href ? [' ', el('a', {href: link.href}, link.words)] : null));
    row.append(button('Dismiss this notice', () => {
        row.remove();
        if (state.notice && state.notice.row === row) {
            state.notice = null;
        }
    }, {small: true, quiet: true}));
    fill(host, row);
    state.notice = {text, id, row};
}

/** Takes the line away when it is the one named, or whichever it is when no id is given. */
function unsay(id) {
    if (state.notice && (id === null || state.notice.id === id)) {
        state.notice.row.remove();
        state.notice = null;
    }
}

/** Says a change of where the tool is pointed to assistive technology, once, after the first drawing. */
function announce(text) {
    if (state.announced === undefined) {
        state.announced = text;
        return;
    }
    if (text !== state.announced) {
        state.announced = text;
        document.getElementById('frame-news').textContent = text;
    }
}

/* ---------- tabs and addresses ---------- */

function wireTabs() {
    const nav = document.getElementById('tabs');
    nav.addEventListener('keydown', event => {
        const links = Array.from(nav.querySelectorAll('.tabs__tab'));
        const at = links.indexOf(document.activeElement);
        if (at < 0) {
            return;
        }
        let next = null;
        if (event.key === 'ArrowRight') {
            next = links[(at + 1) % links.length];
        } else if (event.key === 'ArrowLeft') {
            next = links[(at + links.length - 1) % links.length];
        } else if (event.key === 'Home') {
            next = links[0];
        } else if (event.key === 'End') {
            next = links[links.length - 1];
        }
        if (next) {
            event.preventDefault();
            next.focus();
            show([next.dataset.tab], true);
        }
    });
}

/** The parts of an address after '#', each decoded. A part that cannot be decoded is kept as written. */
function routeOf(hash) {
    let text = String(hash || '');
    if (text.startsWith('#')) {
        text = text.slice(1);
    }
    const parts = [];
    for (const part of text.split('/')) {
        if (part !== '') {
            try {
                parts.push(decodeURIComponent(part));
            } catch (malformed) {
                parts.push(part);
            }
        }
    }
    return parts;
}

/** The address of a view as this page writes it, or null for a tab it does not have. Addresses of the earlier screens lead to their new places. */
function canonical(parts) {
    const [tab, ...rest] = parts;
    if (tab === 'workbench' || tab === 'audit' || tab === 'configuration') {
        return parts;
    }
    if (tab === 'history') {
        if (rest[0] === 'runs' && rest[1]) {
            return ['audit', 'runs', rest[1]];
        }
        if (rest[0] === 'chains' && rest[1]) {
            return ['audit', 'journeys', rest[1]];
        }
        return ['audit'];
    }
    if (tab === 'config') {
        return ['configuration'];
    }
    if (tab === 'crd' || tab === 'dtr' || tab === 'pas') {
        return ['workbench'];
    }
    return null;
}

function addressOf(route) {
    return '#' + route.map(part => encodeURIComponent(part)).join('/');
}

function rememberedTab() {
    try {
        return window.localStorage.getItem(TAB_KEY);
    } catch (storageOff) {
        return null;
    }
}

function rememberTab(tab) {
    try {
        window.localStorage.setItem(TAB_KEY, tab);
    } catch (storageOff) {
        // Storage can be switched off in the browser. Remembering the tab is a convenience only.
    }
}

/** Shows the view of a route; push makes it a new step of the browser's history, otherwise the address is replaced. */
async function show(parts, push) {
    const route = canonical(parts) || canonical([rememberedTab() || '']) || ['workbench'];
    const address = addressOf(route);
    if (window.location.hash !== address) {
        if (push) {
            window.history.pushState(null, '', address);
        } else {
            window.history.replaceState(null, '', address);
        }
    }
    const [tab, ...rest] = route;
    for (const link of document.querySelectorAll('#tabs .tabs__tab')) {
        if (link.dataset.tab === tab) {
            link.setAttribute('aria-current', 'page');
        } else {
            link.removeAttribute('aria-current');
        }
    }
    rememberTab(tab);
    if (state.view && state.view.tab === tab) {
        state.view.route = rest;
        if (state.view.handle && typeof state.view.handle.update === 'function') {
            guarded(() => state.view.handle.update(rest));
        }
        return;
    }
    if (state.view && state.view.handle && typeof state.view.handle.unmount === 'function') {
        const leaving = state.view.handle;
        guarded(() => leaving.unmount());
    }
    state.showing += 1;
    const showing = state.showing;
    const entry = TABS.find(each => each.id === tab);
    const root = el('section', {class: 'view view--' + tab, 'aria-label': entry.label});
    fill(document.getElementById('main'), root);
    state.view = {tab, root, handle: null, route: rest};
    let module;
    try {
        module = await entry.load();
    } catch (failed) {
        if (showing === state.showing) {
            root.append(el('div', {class: 'page'}, el('p', {class: 'stub-line'}, PAGE_FAULT_LINE)));
            say(PAGE_FAULT_LINE, 'fault', 'page');
        }
        return;
    }
    if (showing !== state.showing) {
        return;
    }
    guarded(() => {
        const handle = module.mount(root, state.view.route);
        state.view.handle = handle && typeof handle === 'object' ? handle : null;
    });
}

function guarded(work) {
    try {
        work();
    } catch (defect) {
        say(PAGE_FAULT_LINE, 'fault', 'page');
    }
}

start();
