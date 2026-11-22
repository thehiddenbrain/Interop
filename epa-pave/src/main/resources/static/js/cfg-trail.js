// Configuration > Advanced > Trail: how many runs' requests and responses PAVE keeps beside the
// ledger, where, and the one way to empty it.
//
// The trail holds member data (operator's answer 6), so this page says so before anything else and
// shows no entry: a run's request and response are opened from Audit, one run at a time. Every count
// and the folder are the server's; a trail switched off is said as the server's setting, with
// nothing to press.

import {call} from './api.js';
import {el, fill, clear, button, modal} from './dom.js';
import {errorPanel} from './fault.js';
import {sizeText} from './files.js';
import {pageHead, section, during, counted} from './cfg-parts.js';

const OFF_LINE = 'The trail is switched off on this installation (trail.enabled).';

/** "3 runs'", "1 run's": the possessive the confirmation and the outcome line need. */
function runsOf(count) {
    return count + (count === 1 ? ' run\'s' : ' runs\'');
}

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let gone = false;
    const line = el('span');
    const body = el('div');
    const outcome = el('div', {'aria-live': 'polite'});
    const page = section(pageHead('Trail', line), outcome, body);
    root.append(page);

    function draw(trail) {
        if (trail.enabled === false) {
            clear(line);
            fill(body, el('p', null, OFF_LINE));
            return;
        }
        // The number kept and the folder are the server's, so the line never claims 500 where the installation keeps another number.
        line.textContent = (Number.isInteger(trail.maxEntries) ? 'The last ' + trail.maxEntries + ' runs\'' : 'The runs\'')
            + ' requests and responses, secrets masked, member data inside. '
            + (trail.folder ? 'Kept in ' + trail.folder + ', never' : 'Never') + ' in the ledger or the log.';
        if (!Number.isInteger(trail.count)) {
            // A count the server did not give is not said as 0, and nothing is offered to empty on it.
            clear(body);
            return;
        }
        // An empty trail offers nothing to empty.
        const empty = trail.count > 0 ? button('Empty the trail', () => confirmEmpty(trail.count)) : null;
        fill(body, el('p', null, el('strong', null, counted(trail.count, 'run kept', 'runs kept')),
            Number.isFinite(trail.bytes) ? ', ' + sizeText(trail.bytes) : null),
            empty ? el('div', {class: 'form-foot'}, empty) : null);
    }

    function confirmEmpty(count) {
        const busy = el('span', {class: 'muted small', role: 'status'});
        modal({
            title: 'Empty the trail?',
            body: el('p', null, runsOf(count) + ' requests and responses are removed. The ledger keeps its lines.'),
            footer: dialog => {
                const empty = button('Empty the trail', () => during(empty, busy, 'Emptying', async () => {
                    await emptyNow();
                    dialog.close();
                }), {primary: true});
                return [busy, button('Keep them', () => dialog.close()), empty];
            }
        });
    }

    async function emptyNow() {
        clear(outcome);
        const answer = await call('DELETE', '/api/trail');
        if (gone) {
            return;
        }
        if (!answer.ok) {
            fill(outcome, errorPanel(answer.error));
        } else {
            const removed = answer.data && Number.isInteger(answer.data.removed) ? answer.data.removed : null;
            fill(outcome, el('p', {role: 'status'}, removed === null ? 'The trail is emptied.'
                : 'The trail is emptied: ' + runsOf(removed) + ' requests and responses removed.'));
        }
        await load();
    }

    async function load() {
        const answer = await call('GET', '/api/trail');
        if (gone) {
            return;
        }
        if (!answer.ok) {
            clear(line);
            fill(body, errorPanel(answer.error));
            return;
        }
        draw(answer.data || {});
    }

    load();

    return {
        update() {},
        unmount() {
            gone = true;
            page.remove();
        }
    };
}
