// Whether the ledger is recording, from GET /api/runs/health, always in view on the Runs view.
//
// The failure that matters is tracking having stopped while a tester believes it is on. So a
// ledger that is off, cannot write, has failed a write since the tool started, or has lines it
// could not read is drawn with the fault frame, in words that say what it means for the runs
// on this tab and what to do. A healthy ledger is one quiet line.
//
// The health answer carries facts, not sentences: whether tracking is enabled, whether the folder
// is writable, the count of runs today, the last write and the last failure. The sentences below
// say what each fact means; none of them decides anything the facts do not state.

import * as api from './api.js';
import {el, fill, clear, code, count, when} from './dom.js';
import {utcToday} from './history-common.js';

// The panel is redrawn after every list, note and replay, so it is not a live region: a screen
// reader would hear the whole of it, folder path included, each time. A change of state is
// announced once, in a sentence, through announce.
export function createHealth(app, announce) {
    const node = el('section', {class: 'history-health', aria: {label: 'Whether the ledger is recording'}});
    // The same state in one line, for the Chains and Suite runs views. Empty while the ledger is healthy.
    const brief = el('div', {class: 'history-health-brief'});
    let last = null;
    let lastState = null;

    async function refresh() {
        const answer = await api.get('/api/runs/health');
        if (answer.kind !== 'ok') {
            last = null;
            fill(node, el('div', {class: 'ledger ledger--missing'},
                el('p', {class: 'history-health__state'}, 'The state of the ledger could not be read.'),
                el('p', null, answer.body.title, ' ', answer.body.action || '')));
            drawBrief(['The state of the ledger could not be read.']);
            said('The state of the ledger could not be read.');
            return null;
        }
        last = answer.body;
        const titles = draw(last);
        drawBrief(titles);
        said(titles.length === 0 ? 'The ledger is recording.' : 'The ledger reports a problem: ' + titles.join(' '));
        if (last.enabled && last.writable && !last.lastError && typeof last.runsToday === 'number') {
            // The masthead indicator shows the count of today's runs. A healthy answer is the same
            // fact it shows after a run, so it is passed on. A failing one is not: the indicator has
            // only one alarm sentence, about the latest run, and this panel says more.
            app.ledgerReceipt({recorded: true, runsToday: last.runsToday});
        }
        return last;
    }

    /** Announces the state only when it is not the one announced last, so a redraw is silent. */
    function said(sentence) {
        if (sentence !== lastState) {
            const first = lastState === null;
            lastState = sentence;
            // A healthy ledger found on first showing the tab is not news. Anything else is.
            if (!(first && sentence === 'The ledger is recording.') && announce) {
                announce(sentence);
            }
        }
    }

    function drawBrief(titles) {
        if (titles.length === 0) {
            clear(brief);
            return;
        }
        fill(brief, el('div', {class: 'ledger ledger--missing history-health-brief__alarm'},
            el('p', null, el('strong', null, titles.join(' ')), ' ',
                el('a', {href: '#history/runs', class: 'history-link'}, 'The Runs view says what it means and what to do'), '.')));
    }

    function draw(health) {
        const problems = [];
        if (!health.enabled) {
            problems.push(['Tracking is OFF.', 'Runs made now are not recorded anywhere, and this list will not show them. '
                + 'To record them, set tracking.enabled to true and restart the tool. What earlier sessions recorded is still listed.']);
        }
        if (health.enabled && !health.writable) {
            problems.push(['The ledger cannot write.', ['Runs made now are NOT recorded. The folder is ', code(health.directory || 'not stated'),
                '. Make it writable, or set tracking.directory to a folder that is, and restart the tool.']]);
        }
        if (health.lastError) {
            const recovered = health.lastWriteAt && health.lastErrorAt && Date.parse(health.lastWriteAt) > Date.parse(health.lastErrorAt);
            problems.push([count(health.failedWrites) + (health.failedWrites === 1 ? ' write has' : ' writes have') + ' failed since the tool started.',
                ['The latest failure was at ', when(health.lastErrorAt), '. The ledger said:', el('blockquote', null, health.lastError),
                    recovered ? ['A later write, at ', when(health.lastWriteAt), ', succeeded. '] : null,
                    'A run that was not recorded is not in this list. Its result said so, with the message above, where it was made: '
                    + 'note its run id from there if you need it.']]);
        }
        if (health.enabled && typeof health.runsToday !== 'number') {
            problems.push(['Today\'s count cannot be given.', 'The ledger could not read today\'s file, so it does not say how many runs it holds. '
                + 'That is not the same as none.']);
        }
        if (health.unreadableLines > 0) {
            const files = Object.entries(health.unreadableFiles || {});
            problems.push([count(health.unreadableLines) + (health.unreadableLines === 1 ? ' line' : ' lines') + ' of the ledger could not be read.',
                ['They are left out of this list and out of the export. The files themselves are not changed: look at them in the folder below.',
                    files.length > 0 ? el('ul', {class: 'plain-list small'}, files.map(([file, lines]) =>
                        el('li', null, code(file), ': ' + count(lines) + (lines === 1 ? ' line' : ' lines')))) : null]]);
        }
        const today = 'today, the UTC day ' + utcToday() + ', which is how the ledger counts days';
        const where = el('p', {class: 'small quiet'}, health.enabled && health.writable ? 'Writing to ' : 'The ledger folder is ', code(health.directory || 'no folder stated'),
            health.currentFile ? [', today in ', code(health.currentFile)] : null, '.',
            health.lastWriteAt ? [' Last written ', when(health.lastWriteAt), '.'] : (health.enabled ? ' Nothing written since the tool started.' : null));
        if (problems.length === 0) {
            fill(node, el('div', {class: 'history-health__ok'},
                el('p', null, el('strong', null, 'Tracking is on.'), ' ',
                    count(health.runsToday) + (health.runsToday === 1 ? ' run' : ' runs') + ' recorded ' + today + '.'),
                where));
            return [];
        }
        fill(node, el('div', {class: 'ledger ledger--missing history-health__alarm'},
            problems.map(([title, detail]) => el('div', {class: 'history-health__problem'},
                el('p', {class: 'history-health__state'}, title), el('div', null, detail))),
            health.enabled && typeof health.runsToday === 'number'
                ? el('p', null, count(health.runsToday) + (health.runsToday === 1 ? ' run is' : ' runs are') + ' recorded in the file of ' + today + '.') : null,
            where));
        return problems.map(([title]) => title);
    }

    /** True when the last answer reported anything that keeps runs out of the list. */
    function troubled() {
        return Boolean(last) && (!last.enabled || !last.writable || Boolean(last.lastError) || last.unreadableLines > 0);
    }

    return {node, brief, refresh, current: () => last, troubled};
}
