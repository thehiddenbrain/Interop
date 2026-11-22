// The Audit view: what the ledger recorded. Runs (#audit, its filters in the address), one run
// (#audit/runs/<runId>) and Journeys (#audit/journeys, one journey at #audit/journeys/<chainId>).
// Suites joins them once its module is in the build; until then no entry leads to a page that is not there.

import {el, fill} from './dom.js';
import {app, ledgerLine} from './app.js';
import {vocabulary} from './words.js';
import {wordsOf} from './audit-common.js';
import {runsView, filtersOf} from './audit-runs.js';
import {runView} from './audit-run.js';
import {journeysView, journeyFiltersOf} from './audit-journeys.js';

// The sub-navigation, in the order it is drawn.
const PAGES = [
    {id: 'runs', label: 'Runs', address: '#audit'},
    {id: 'journeys', label: 'Journeys', address: '#audit/journeys'}
];

/**
 * The ledger's alarm, loud and never folded, only when it is not recording: the page's words for
 * the LedgerHealth (app.ledgerLine), with the ledger's own last error beneath. Null while it records.
 */
function ledgerAlarm(health) {
    const line = ledgerLine(health);
    if (!line) {
        return null;
    }
    const said = health && typeof health.lastError === 'string' && health.lastError !== '' ? health.lastError : null;
    return el('div', {class: 'line line--alarm audit-ledger'}, el('p', {class: 'line__text'}, line),
        said ? el('p', {class: 'line__detail'}, said) : null);
}

/** Which page of Audit a route is on. A part this view does not know shows the runs. */
export function pageOf(parts) {
    const first = Array.isArray(parts) && parts.length > 0 ? parts[0] : '';
    return first === 'journeys' ? first : 'runs';
}

/** Draws the view into root. route: the parts of the address after #audit. */
export function mount(root, route) {
    const page = el('div', {class: 'page audit'});
    const nav = el('nav', {class: 'audit-subnav', 'aria-label': 'Audit'});
    const body = el('div');
    let lastList = [];
    const ctx = {
        app,
        words: () => vocabulary().then(wordsOf),
        ledger: ledgerAlarm,
        go: parts => app.go('#' + ['audit'].concat(parts.filter(part => part !== '?')).map(part => encodeURIComponent(part)).join('/')),
        listAddress: () => '#' + ['audit'].concat(lastList).map(part => encodeURIComponent(part)).join('/')
    };
    const runs = runsView(ctx);
    const one = runView(ctx);
    const journeys = journeysView(ctx);
    page.append(nav, body);
    root.append(page);

    function drawNav(current) {
        fill(nav, PAGES.map(each => el('a', {class: 'subnav__item', href: each.address, 'aria-current': each.id === current ? 'page' : null}, each.label)));
    }

    function update(parts) {
        const rest = Array.isArray(parts) ? parts : [];
        const current = pageOf(rest);
        drawNav(current);
        runs.close();
        if (current === 'journeys') {
            const second = rest.length > 1 ? String(rest[1]) : '';
            fill(body, journeys.node);
            if (second !== '' && !second.startsWith('?')) {
                journeys.open(second);
            } else {
                journeys.show(journeyFiltersOf(second));
            }
            return;
        }
        if (rest[0] === 'runs' && rest.length > 1) {
            fill(body, one.node);
            one.show(rest[1]);
            return;
        }
        // #audit, #audit/runs, or #audit/?<filters>: the list.
        lastList = rest.length > 0 && String(rest[rest.length - 1]).startsWith('?') ? [rest[rest.length - 1]] : [];
        fill(body, runs.node);
        runs.show(filtersOf(lastList.length > 0 ? lastList[0] : ''));
    }

    let shown = route;
    update(route);
    // A change of the configuration changes what a replay would send and where: the open page is drawn again.
    const offConfig = app.on('config', () => update(shown));
    return {
        update(parts) {
            shown = parts;
            update(parts);
        },
        unmount() {
            offConfig();
            runs.close();
        }
    };
}
