// Writes to production: the panel that says whether this tool can change anything in the active
// environment right now, and the one place a tester can unlock that for this browser session.
//
// Every fact it shows is the server's. Whether the environment is production, whether a write
// would be sent, whether the configuration floor allows an unlock at all, and the sentence that
// says why, all come from writeAccess in /api/mode, which is what the frame's banner is drawn
// from too. Which operations are writes comes from /api/config/effective. The unlock itself is
// decided by the server from the name typed: this page sends the name as typed and nothing else.
//
// The panel is drawn from the frame's last /api/mode answer. After an unlock or a lock the frame
// reads /api/mode again before this is redrawn, so the banner and this panel never disagree.

import * as calls from './config-calls.js';
import {el, fill, clear, button, heading} from './dom.js';
import {clearFieldErrors} from './forms.js';
import {refusal, during} from './config-parts.js';

export function createWritesPanel(context) {
    // Not a live region: the panel is redrawn whole. An unlock or a lock is announced in one line.
    const node = el('section', {class: 'result__section config-writes-section'});
    const typed = el('input', {type: 'text', id: 'config-unlock-name', autocomplete: 'off', spellcheck: false});
    const problem = el('div', {class: 'form-problem-slot'});
    const unlock = el('button', {type: 'submit', class: 'button button--primary'}, 'Unlock production writes for this browser session');
    const form = el('form', {class: 'config-unlock', noValidate: true, attrs: {'aria-label': 'Unlock production writes for this browser session'}},
        el('div', {class: 'field', dataset: {field: 'environment'}},
            el('label', {htmlFor: 'config-unlock-name'}, 'Type the name of the active environment, exactly as it is written above'),
            typed),
        problem,
        el('div', {class: 'button-row'}, unlock));
    form.addEventListener('submit', event => {
        event.preventDefault();
        during(unlock, sendUnlock);
    });
    typed.addEventListener('input', () => {
        clearFieldErrors(form);
        clear(problem);
    });
    const outcome = el('div', {class: 'config-outcome'});

    async function sendUnlock() {
        clearFieldErrors(form);
        clear(problem);
        clear(outcome);
        // The name goes exactly as it was typed, spaces and capitals included. The server compares it.
        const answer = await calls.unlockWrites(typed.value);
        if (answer.kind !== 'ok') {
            fill(problem, refusal(answer, [form]));
            return;
        }
        typed.value = '';
        await context.changed();
        context.announce('Writes to production are now unlocked for this browser session.');
        // The form has gone. The keyboard lands on the heading of the panel that replaced it.
        const title = node.querySelector('.section-heading');
        if (title) {
            title.tabIndex = -1;
            title.focus();
        }
    }

    async function sendLock(control) {
        clear(outcome);
        const answer = await calls.lockWrites();
        if (answer.kind !== 'ok') {
            fill(outcome, refusal(answer));
        }
        await context.changed();
        if (answer.kind === 'ok') {
            context.announce('Writes to production are locked again, for every browser session.');
            // The button has gone. The keyboard lands where an unlock would start again.
            if (node.contains(typed)) {
                typed.focus();
            } else {
                const title = node.querySelector('.section-heading');
                if (title) {
                    title.tabIndex = -1;
                    title.focus();
                }
            }
        }
    }

    function writeOperations() {
        const effective = context.stored().effective;
        return effective ? (effective.operations || []).filter(row => row.write) : [];
    }

    /**
     * What counts as a write here: the operations the server marks as writes, and what the server
     * does with a request sent as written. The server sends no sentence for the second, so this
     * one says only what its refusal says, and points to where its own words appear.
     */
    function whatIsAWrite() {
        const writes = writeOperations();
        return el('div', {class: 'config-writes__what'},
            el('p', null, 'A write is a request that creates or changes something at the far side. These operations are writes:'),
            writes.length > 0
                ? el('ul', {class: 'plain-list config-writes__list'}, writes.map(row => el('li', null, row.label)))
                : el('p', null, el('strong', null, 'The tool did not list its write operations.')),
            el('p', null, el('strong', null, 'A request sent as written is judged again when it is sent. '),
                'The tool cannot tell what it does, so on production one sent with any method other than GET, HEAD or OPTIONS is blocked, '
                + 'even when the same request is mapped to a read such as PAS inquire, and so may one whose name, url or headers read as a write. The mark ', el('span', {class: 'flag'}, 'counts as a write'),
                ' under Collections and mapping is about a request sent as the operation it is mapped to, not about sending it as written. '
                + 'When a send is blocked, the result says why in the tool\'s own words.'));
    }

    function draw() {
        const mode = context.app.mode();
        const access = mode ? mode.writeAccess || null : null;
        const environment = mode ? mode.environment || {} : {};
        if (!access || access.production !== true) {
            // Nothing to unlock. The fact of whether a write would be sent is in "Where this tool is pointed".
            clear(node);
            node.hidden = true;
            return;
        }
        node.hidden = false;
        if (access.writesAllowed === true) {
            fill(node, el('div', {class: 'config-writes config-writes--unlocked', role: 'region', aria: {label: 'Writes to production'}},
                heading(2, 'Writes to production are unlocked for this browser session only'),
                el('p', {class: 'config-writes__reason'}, access.reason),
                el('p', null, context.app.isMock()
                    ? 'On the mock profile nothing leaves this machine. On the live profile a PAS submit sent now would create a real '
                        + 'prior authorization request against a real member. '
                    : 'A PAS submit sent now creates a real prior authorization request against a real member. ',
                'The unlock is held for this browser session alone, and other browsers still read as locked. '
                    + 'It ends with any change to the configuration, made from any browser: a switch of environment, an upload of an '
                    + 'environment or a collection. A restart of the tool ends it too.'),
                whatIsAWrite(),
                el('div', {class: 'button-row'},
                    lockButton()),
                outcome));
            return;
        }
        if (access.floorAllows !== true) {
            fill(node, el('div', {class: 'config-writes config-writes--floor', role: 'region', aria: {label: 'Writes to production'}},
                heading(2, 'Writes to production are blocked, and this installation offers no unlock'),
                el('p', {class: 'config-writes__reason'}, access.reason),
                el('p', null, 'Nothing typed on this page can override that, so no unlock is offered here.'),
                whatIsAWrite()));
            return;
        }
        fill(node, el('div', {class: 'config-writes config-writes--locked', role: 'region', aria: {label: 'Writes to production'}},
            heading(2, 'Writes to production are blocked'),
            el('p', {class: 'config-writes__reason'}, access.reason),
            whatIsAWrite(),
            el('p', null, 'To send a write to ', el('strong', null, environment.name),
                ' from this browser session, type its name below. The unlock is never remembered: it ends with the session, '
                + 'with a restart of the tool, and with any change to the configuration from any browser, an upload included. '
                + 'A lock pressed in any browser ends it as well, for every session at once.'),
            form,
            outcome));
    }

    function lockButton() {
        const control = button('Lock production writes again', () => during(control, () => sendLock(control)), {class: 'button--primary'});
        return control;
    }

    return {node, draw};
}
