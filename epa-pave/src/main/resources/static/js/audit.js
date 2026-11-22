// The Audit view: what the ledger recorded. Runs (#audit, its filters in the address) and one run
// (#audit/runs/<runId>). Journeys and Suites come in a later release; until then the
// sub-navigation that leads to them is not drawn, so nothing offers a page that is not there.

import {el, fill} from './dom.js';
import {app, ledgerLine} from './app.js';
import {vocabulary} from './words.js';
import {wordsOf} from './audit-common.js';
import {runsView, filtersOf} from './audit-runs.js';
import {runView} from './audit-run.js';

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

/** Draws the view into root. route: the parts of the address after #audit. */
export function mount(root, route) {
    const page = el('div', {class: 'page audit'});
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
    // The sub-navigation (Runs, Journeys, Suites) is drawn once there is more than Runs to go to.
    page.append(body);
    root.append(page);

    function update(parts) {
        const rest = Array.isArray(parts) ? parts : [];
        if (rest[0] === 'runs' && rest.length > 1) {
            runs.close();
            fill(body, one.node);
            one.show(rest[1]);
            return;
        }
        // #audit, #audit/runs, or #audit/?<filters>: the list. A part this view does not know shows the list unfiltered.
        lastList = rest.length > 0 && String(rest[rest.length - 1]).startsWith('?') ? [rest[rest.length - 1]] : [];
        fill(body, runs.node);
        runs.show(filtersOf(lastList.length > 0 ? lastList[0] : ''));
    }

    update(route);
    // A change of the configuration changes what a replay would send and where: the open page is drawn again.
    const offConfig = app.on('config', () => update(currentRoute()));
    let shown = route;
    function currentRoute() {
        return shown;
    }
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

