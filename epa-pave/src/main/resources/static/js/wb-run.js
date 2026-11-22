// What every step panel of the Workbench shares: its head (title, protocol tag, the one line saying
// what the step answers, the controls at the right), the busy line that counts seconds while a run
// is on its way, the refusal of a send while a code is typed and not added, and one way of sending
// a run and dealing with whatever comes back:
//
//   a 2xx, and a 403 or 500 that carries a run   the result card; the journey hears of it
//   a 422                                        each field error at its field (folds opened); the
//                                                result before it stays; the journey is unchanged
//   anything else                                the error panel, in one visible place
//
// Every send button is disabled while its call is in flight, so one click never sends twice.

import {call} from './api.js';
import {el, button} from './dom.js';
import {errorPanel} from './fault.js';

/**
 * The text a send is refused for, or empty when it may go: what was typed in the procedure code box
 * and not added, when the call sends the case. Nothing the tester did not see as part of the case is
 * ever sent, so such text is not sent quietly, and not dropped quietly either. A call that sends
 * nothing of the case (a subscription call) is not held up by a box it does not read.
 *
 * facts: typed (the text in the box), caseShown (the case is on screen), ownRequest (a request of
 * the tester's own, which carries nothing of the case), sendsCase (false when the call carries
 * nothing of the case; left out, the case is sent whenever it is shown and no request of the
 * tester's own is chosen).
 */
export function textLeftToRefuse(facts) {
    const typed = typeof facts.typed === 'string' ? facts.typed.trim() : '';
    const sendsCase = facts.sendsCase === false ? false : facts.caseShown === true && facts.ownRequest !== true;
    return sendsCase ? typed : '';
}

/** The words of that refusal, at the code box. */
export function textLeftWords(typed) {
    return '"' + typed + '" was typed in the procedure code box and not added to the case. Choose + to add it, or clear the box, then send again.';
}

/** The head of a step panel. controls: nodes at the right, left to right. */
export function panelHead(spec) {
    return el('div', {class: 'panel__head'},
        el('div', {class: 'panel__intro'},
            el('h2', {class: 'panel__title', id: spec.id || null}, spec.title, ' ', el('span', {class: 'tag'}, spec.tag)),
            el('p', {class: 'panel__lede'}, spec.lede)),
        el('div', {class: 'panel__controls'}, spec.controls));
}

/**
 * A busy line that says what it waits for and counts the seconds: "Running the check... 3 s".
 * start(words) shows it; stop() takes it away.
 */
export function busyLine() {
    const node = el('p', {class: 'busy small muted', role: 'status', 'aria-live': 'polite', hidden: true});
    let timer = null;
    let began = 0;
    let words = '';
    const tick = () => {
        node.textContent = words + ' ' + Math.floor((Date.now() - began) / 1000) + ' s';
    };
    return {
        node,
        start(what) {
            words = what;
            began = Date.now();
            node.hidden = false;
            tick();
            clearInterval(timer);
            timer = setInterval(tick, 1000);
        },
        stop() {
            clearInterval(timer);
            timer = null;
            node.hidden = true;
            node.textContent = '';
        }
    };
}

/**
 * Sends one run of a step. spec: {method (POST when left out), path, body or query, busy (a busyLine), busyWords,
 * buttons (to disable), sendsCase (false for a call that carries nothing of the case),
 * typed (text left in the code box), ctx, onResult(envelope), onRefused(error, placed),
 * onFailed(error)}. Returns the answer of api.js, or null when the send was refused in the page.
 */
export async function sendRun(spec) {
    const typed = textLeftToRefuse({typed: spec.typed, caseShown: true, sendsCase: spec.sendsCase});
    if (typed !== '') {
        const error = {kind: 'VALIDATION', fieldErrors: [{field: 'procedureCodes', message: textLeftWords(typed)}]};
        const placed = spec.ctx.placeErrors(error.fieldErrors);
        spec.onRefused(error, placed);
        return null;
    }
    for (const control of spec.buttons) {
        control.disabled = true;
    }
    spec.busy.start(spec.busyWords);
    let answer;
    try {
        // A read or a delete of a subscription sends its facts as parameters; everything else as a JSON body.
        answer = await call(spec.method || 'POST', spec.path, spec.body === undefined ? {query: spec.query} : {body: spec.body});
    } finally {
        spec.busy.stop();
        for (const control of spec.buttons) {
            control.disabled = false;
        }
    }
    if (answer.ok || (answer.data && typeof answer.data.runId === 'string')) {
        spec.onResult(answer.data);
    } else if (answer.status === 422 && answer.error && Array.isArray(answer.error.fieldErrors)) {
        spec.onRefused(answer.error, spec.ctx.placeErrors(answer.error.fieldErrors));
    } else {
        spec.onFailed(answer.error);
    }
    return answer;
}

/** The error panel of a refused or failed send, placed in the step's one result place. */
export function failurePanel(error, placed) {
    return errorPanel(error, {fieldsPlaced: placed || []});
}

/** The "Options" button of a step's head, which shows and hides the row of the step's own options. */
export function optionsToggle(row) {
    const toggle = button('Options', () => {
        row.hidden = !row.hidden;
        toggle.setAttribute('aria-expanded', row.hidden ? 'false' : 'true');
    }, {small: true, title: 'Options of this step'});
    toggle.setAttribute('aria-expanded', 'false');
    row.hidden = true;
    return toggle;
}
