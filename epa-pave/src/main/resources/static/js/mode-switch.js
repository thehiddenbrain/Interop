// The switch between mock and live in the top bar: the control in place of the MOCK or LIVE badge,
// the one question before a switch, the page while PAVE restarts itself, and the line that says
// what came of it once the page is drawn afresh.
//
// A switch restarts PAVE in place on the other profile (POST /api/mode). Everything this page drew
// belongs to the profile it was drawn for, so once the new start answers the page is loaded again
// onto its tab, with a new empty case on the Workbench: nothing of the other mode can stay on
// screen. What the line after the reload says travels in the address and is taken out of it at
// once: the browser keeps nothing new, and the only storage key stays pave.tab.

import {el, fill, button, modal} from './dom.js';
import {UNREACHABLE_LINE} from './fault.js';

/** How long the page waits for PAVE to come back before it says it did not. */
export const COME_BACK_MILLIS = 60000;

/** How often the page asks whether the new start answers. */
export const ASK_EVERY_MILLIS = 1000;

/** How long one question waits for its answer before the page asks again. */
export const ANSWER_WITHIN_MILLIS = 10000;

export const NO_COMEBACK = 'PAVE did not come back. Look at its console, then start it again.';

// The parameters of the address the page is loaded again on, read once and taken out at once.
const NOW_ON = 'pave-now';
const DROPPED = 'pave-dropped';

/** mock or live from an answer of /api/mode, or null for anything else. */
export function modeOf(view) {
    const mode = view && typeof view === 'object' ? view.mode : null;
    return mode === 'mock' || mode === 'live' ? mode : null;
}

/** The other mode, or null when the one given is neither. */
export function otherMode(mode) {
    return mode === 'mock' ? 'live' : mode === 'live' ? 'mock' : null;
}

/** What this browser session holds that a switch drops, from an answer of /api/mode; absent is not held. */
export function heldOf(view) {
    const held = view && typeof view === 'object' && view.held && typeof view.held === 'object' ? view.held : {};
    return {productionUnlock: held.productionUnlock === true, pastedToken: held.pastedToken === true};
}

/** The words of the one question before a switch: title, body lines, and the two buttons. */
export function dialogWords(to, held) {
    const lines = ['PAVE restarts itself on ' + to + '. It takes a few seconds.', 'The case on the Workbench is cleared.'];
    if (to === 'mock') {
        lines.push('On mock, answers come from scenario files and nothing is sent.');
    }
    if (held && held.productionUnlock) {
        lines.push('The production unlock of this browser session is dropped.');
    }
    if (held && held.pastedToken) {
        lines.push('The pasted access token is dropped.');
    }
    return {title: 'Switch to ' + to + '?', lines, confirm: 'Switch to ' + to, cancel: 'Cancel'};
}

/** The words shown while PAVE restarts itself. */
export function switchingWords(to) {
    return 'Switching to ' + to + '...';
}

/** What a switch dropped, in one sentence, or '' when it dropped nothing. */
export function droppedWords(held) {
    const unlock = Boolean(held && held.productionUnlock);
    const token = Boolean(held && held.pastedToken);
    if (unlock && token) {
        return 'The production unlock and the pasted access token were dropped.';
    }
    if (unlock) {
        return 'The production unlock was dropped.';
    }
    if (token) {
        return 'The pasted access token was dropped.';
    }
    return '';
}

/**
 * The line once the page is drawn afresh: on the mode wanted, "Now on live."; back on the mode it
 * had because the start on the wanted one failed, the sentence that says so. Then what was dropped.
 */
export function arrivedWords(wanted, now, held) {
    const dropped = droppedWords(held);
    const first = wanted === now ? 'Now on ' + now + '.'
        : 'PAVE could not start on ' + wanted + ' and is back on ' + now + '. Its console says why.';
    return dropped ? first + ' ' + dropped : first;
}

/**
 * Where a switch stands from an answer of /api/mode asked while it is under way: 'waiting' while
 * the answer is not from a new start (no answer, the start that is closing, or a switch still under
 * way), 'arrived' on the mode wanted, 'back' on another.
 */
export function outcomeOf(before, answer, wanted) {
    if (!answer || !answer.ok || !answer.data || typeof answer.data !== 'object') {
        return 'waiting';
    }
    const view = answer.data;
    const was = before && typeof before.startedAt === 'string' ? before.startedAt : null;
    if (typeof view.startedAt !== 'string' || view.startedAt === '' || view.startedAt === was || view.switchingTo) {
        return 'waiting';
    }
    return modeOf(view) === wanted ? 'arrived' : 'back';
}

/**
 * The address the page is loaded again on after a switch: the tab alone, so the Workbench opens on
 * a new empty case, with what the line is to say. Nothing of a case or a member is in it.
 */
export function returnAddress(path, tab, wanted, held) {
    const params = new URLSearchParams();
    params.set(NOW_ON, wanted);
    const dropped = [held && held.productionUnlock ? 'unlock' : null, held && held.pastedToken ? 'token' : null].filter(Boolean);
    if (dropped.length > 0) {
        params.set(DROPPED, dropped.join(','));
    }
    const shown = tab === 'audit' || tab === 'configuration' ? tab : 'workbench';
    return String(path || '/') + '?' + params.toString() + '#' + shown;
}

/** The address a page left open on another mode is loaded again on: as returnAddress, for a switch it did not ask for. */
export function noticedAddress(path, tab, now, held) {
    return returnAddress(path, tab, now, held);
}

/**
 * What the address of a page loaded after a switch says, or null when it says nothing of one:
 * {wanted, held}. A value this page did not write is not read.
 */
export function readReturn(search) {
    let params;
    try {
        params = new URLSearchParams(String(search || ''));
    } catch (unreadable) {
        return null;
    }
    const wanted = params.get(NOW_ON);
    if (wanted !== 'mock' && wanted !== 'live') {
        return null;
    }
    const dropped = String(params.get(DROPPED) || '').split(',');
    return {wanted, held: {productionUnlock: dropped.includes('unlock'), pastedToken: dropped.includes('token')}};
}

/** True when an address carries what readReturn reads, so the page takes it out of the address. */
export function carriesReturn(search) {
    try {
        const params = new URLSearchParams(String(search || ''));
        return params.has(NOW_ON) || params.has(DROPPED);
    } catch (unreadable) {
        return false;
    }
}

/**
 * The control in place of the badge: two parts, Mock and Live, in one group. The part in force
 * looks as the badge did and says it is pressed; the other is a quiet button that asks to switch.
 * While a switch is on its way both parts are disabled.
 */
export function drawControl(host, view, options) {
    const {onChoose, busy, prod} = options || {};
    const mode = modeOf(view);
    // Drawn again only when what it shows changes, so a poll never takes the focus from a part.
    const key = JSON.stringify([view && view.mode, Boolean(busy), Boolean(prod)]);
    if (host.drawnFrom === key) {
        return;
    }
    host.drawnFrom = key;
    host.hidden = false;
    host.className = 'mode-switch' + (prod ? ' mode-switch--prod' : '');
    host.setAttribute('role', 'group');
    host.setAttribute('aria-label', 'Profile: mock or live');
    if (!mode) {
        // The server's own word when it is neither: a profile this page does not know is not offered a switch.
        fill(host, el('span', {class: 'badge'}, String(view && view.mode)));
        return;
    }
    const part = (which, words) => {
        const inForce = which === mode;
        const title = inForce
            ? (which === 'mock' ? 'Mock profile: scenarios answer and nothing leaves this machine.'
                : 'Live profile: calls go to the active environment\'s service.')
            : 'Switch to ' + which + ': PAVE restarts itself on ' + which + '.';
        return el('button', {
            type: 'button',
            class: ['mode-switch__part', inForce ? 'badge badge--' + which : 'mode-switch__other'],
            'aria-pressed': inForce ? 'true' : 'false',
            title,
            disabled: busy ? true : null,
            'data-mode': which,
            on: {click: () => {
                if (!inForce && !busy && typeof onChoose === 'function') {
                    onChoose(which);
                }
            }}
        }, words);
    };
    fill(host, part('mock', 'MOCK'), part('live', 'LIVE'));
}

/**
 * Asks before a switch, in the dialog the screens share. Confirmed, it posts the switch and stays
 * open until PAVE answers: refused, the refusal is said in the dialog and nothing changed; accepted,
 * the dialog closes and onAccepted is given the answer (what was in force, and what is dropped).
 */
export function askToSwitch(to, held, deps) {
    const {call, onAccepted} = deps;
    const words = dialogWords(to, held);
    const line = el('p', {class: 'mode-switch__refusal', role: 'status'});
    let confirm;
    let cancel;
    const dialog = modal({
        title: words.title,
        body: el('div', {class: 'mode-switch__words'}, words.lines.map(text => el('p', null, text))),
        footer: shown => {
            cancel = button(words.cancel, () => shown.close());
            confirm = button(words.confirm, async () => {
                confirm.disabled = true;
                cancel.disabled = true;
                line.textContent = '';
                const answer = await call('POST', '/api/mode', {body: {mode: to}});
                if (answer.ok && answer.status === 202 && answer.data && typeof answer.data === 'object') {
                    shown.close();
                    onAccepted(answer.data);
                    return;
                }
                confirm.disabled = false;
                cancel.disabled = false;
                if (answer.error && answer.error.kind === 'UNREACHABLE') {
                    line.textContent = UNREACHABLE_LINE;
                } else if (answer.error && answer.error.title) {
                    // Refused by the server, in its words: what is in the way and what to do.
                    line.textContent = [answer.error.title, answer.error.action].filter(Boolean).join(' ');
                } else {
                    line.textContent = 'PAVE did not switch.';
                }
            }, {primary: true});
            return [line, el('span', {class: 'push'}), cancel, confirm];
        }
    });
    // A question of a few lines: the dialog is as wide as they need, not as a form.
    dialog.classList.add('modal--small');
    return dialog;
}

/**
 * The page while PAVE restarts itself: a dialog that says so and takes no input, Escape included,
 * so nothing is sent to a PAVE that is stopping. Returns {close, say(text)}.
 */
export function showSwitching(to) {
    const words = el('p', {class: 'mode-switch__switching', role: 'status'}, switchingWords(to));
    const dialog = el('dialog', {class: 'modal modal--switching', 'aria-label': switchingWords(to)}, el('div', {class: 'modal__body'}, words));
    dialog.addEventListener('cancel', event => event.preventDefault());
    document.body.append(dialog);
    dialog.showModal();
    return {
        close() {
            if (dialog.open) {
                dialog.close();
            }
            dialog.remove();
        },
        say(text) {
            words.textContent = text;
        }
    };
}

/**
 * Asks GET /api/mode until a new start answers, or until COME_BACK_MILLIS have passed. Resolves to
 * {outcome: 'arrived' | 'back' | 'gone', view}.
 */
export async function waitForStart(before, wanted, deps) {
    const {call, pause, now} = deps;
    const until = now() + COME_BACK_MILLIS;
    while (now() < until) {
        await pause(ASK_EVERY_MILLIS);
        // A PAVE that hangs takes the question and never answers it: the minute is counted all the same.
        const answer = await Promise.race([call('GET', '/api/mode'), pause(ANSWER_WITHIN_MILLIS).then(() => null)]);
        const outcome = outcomeOf(before, answer, wanted);
        if (outcome !== 'waiting') {
            return {outcome, view: answer.data};
        }
    }
    return {outcome: 'gone', view: null};
}
