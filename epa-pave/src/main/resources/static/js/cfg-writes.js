// Configuration > Production writes: whether PAVE can change anything in the active environment
// right now, the one place a tester unlocks that for this browser session, and the addresses PAVE
// remembers as production.
//
// Every fact is the server's: whether the environment is production, whether a write would be
// sent, whether the installation allows an unlock at all and the sentence that says why come from
// writeAccess in /api/mode, which the frame's production line is drawn from too, so the two never
// disagree. The unlock is decided by the server from the name typed: this page sends the name as
// typed and nothing else. The words of the guardrail are the ones it has always had.

import {call} from './api.js';
import {app} from './app.js';
import {el, fill, clear, button, field, setFieldError, modal} from './dom.js';
import {errorPanel} from './fault.js';
import {pageHead, section, part, during, none} from './cfg-parts.js';

const UNLOCK = 'Unlock production writes for this browser session';

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let writeRows = null;        // the operations the server marks as writes, from /api/config/effective
    let destinations = null;     // productionDestinations from /api/config/environments
    let gone = false;

    const panel = el('div');
    const outcome = el('div', {'aria-live': 'polite'});
    const remembered = el('div');
    const page = section(pageHead('Production writes', null), outcome, panel, remembered);
    root.append(page);

    const typed = el('input', {type: 'text', class: 'input', autocomplete: 'off', spellcheck: false});
    const typedField = field({label: 'Type the name of the active environment, exactly as it is written above', input: typed});
    const busy = el('span', {class: 'muted small', role: 'status'});
    const unlock = button(UNLOCK, () => during(unlock, busy, 'Unlocking', sendUnlock), {primary: true});
    typed.addEventListener('input', () => setFieldError(typedField, null));
    typed.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            unlock.click();
        }
    });

    async function sendUnlock() {
        setFieldError(typedField, null);
        clear(outcome);
        // The name goes exactly as it was typed, spaces and capitals included. The server compares it.
        const answer = await call('POST', '/api/config/writes', {body: {action: 'unlock', environment: typed.value}});
        if (!answer.ok) {
            const atField = answer.error && Array.isArray(answer.error.fieldErrors)
                ? answer.error.fieldErrors.find(each => each && each.field === 'environment') : null;
            if (atField) {
                // The refusal is about the name alone, and it is said at the name, beside what was typed.
                setFieldError(typedField, atField.message);
                return;
            }
            fill(outcome, errorPanel(answer.error));
            return;
        }
        typed.value = '';
        fill(outcome, el('p', {role: 'status'}, 'Writes to production are now unlocked for this browser session.'));
        draw(answer.data);
    }

    async function sendLock(control, line) {
        clear(outcome);
        const answer = await call('POST', '/api/config/writes', {body: {action: 'lock'}});
        if (!answer.ok) {
            fill(outcome, errorPanel(answer.error));
            return;
        }
        fill(outcome, el('p', {role: 'status'}, 'Writes to production are locked again, for every browser session.'));
        draw(answer.data);
    }

    /**
     * What counts as a write here: the operations the server marks as writes, and what the server
     * does with a request sent as written. The server sends no sentence for the second, so this
     * one says only what its refusal says.
     */
    function whatIsAWrite() {
        return part('What counts as a write',
            el('p', null, 'A write is a request that creates or changes something at the far side. These operations are writes:'),
            writeRows === null ? none('Reading the operations from PAVE.')
                : writeRows.length > 0 ? el('ul', {class: 'notes'}, writeRows.map(row => el('li', null, row.label)))
                    : el('p', null, el('strong', null, 'The tool did not list its write operations.')),
            el('p', null, el('strong', null, 'A request sent as written is judged again when it is sent. '),
                'The tool cannot tell what it does, so on production one sent with any method other than GET, HEAD or OPTIONS is blocked, '
                + 'even when the same request is mapped to a read such as PAS inquire, and so may one whose name, url or headers read as a write. '
                + 'When a send is blocked, the result says why in the tool\'s own words.'));
    }

    /** access: an answer of POST /api/config/writes, read ahead of the frame's next /api/mode. */
    function draw(access) {
        const mode = app.mode() || {};
        const writes = access || mode.writeAccess || null;
        const environment = mode.environment || {};
        if (!writes) {
            fill(panel, none('Reading the write access from PAVE.'));
            return;
        }
        if (writes.production !== true) {
            // Nothing to unlock: the server's sentence says why writes go or not.
            fill(panel, el('p', null, writes.reason || ''), whatIsAWrite());
            return;
        }
        if (writes.writesAllowed === true) {
            const line = el('span', {class: 'muted small', role: 'status'});
            const lock = button('Lock production writes again', () => during(lock, line, 'Locking', () => sendLock(lock, line)), {primary: true});
            fill(panel, el('div', {class: 'verdict verdict--blocked'},
                el('p', {class: 'verdict__headline'}, 'Writes to production are unlocked for this browser session only'),
                el('p', null, writes.reason || ''),
                el('p', null, app.isMock()
                    ? 'On the mock profile nothing leaves this machine. On the live profile a PAS submit sent now would create a real '
                        + 'prior authorization request against a real member. '
                    : 'A PAS submit sent now creates a real prior authorization request against a real member. ',
                'The unlock is held for this browser session alone, and other browsers still read as locked. '
                    + 'It ends with any change to the configuration, made from any browser: a switch of environment, an upload of an '
                    + 'environment or a collection. A restart of the tool ends it too.'),
                el('div', {class: 'verdict__foot'}, lock, ' ', line)),
                whatIsAWrite());
            return;
        }
        if (writes.floorAllows !== true) {
            fill(panel, el('div', {class: 'verdict verdict--blocked'},
                el('p', {class: 'verdict__headline'}, 'Writes to production are blocked, and this installation offers no unlock'),
                el('p', null, writes.reason || ''),
                el('p', null, 'Nothing typed on this page can override that, so no unlock is offered here.')),
                whatIsAWrite());
            return;
        }
        fill(panel, el('div', {class: 'verdict verdict--blocked'},
            el('p', {class: 'verdict__headline'}, 'Writes to production are blocked'),
            el('p', null, writes.reason || '')),
            part('Unlock', el('p', null, 'To send a write to ', el('strong', {class: 'environment-name'}, environment.name || ''),
                ' from this browser session, type its name below. The unlock is never remembered: it ends with the session, '
                + 'with a restart of the tool, and with any change to the configuration from any browser, an upload included. '
                + 'A lock pressed in any browser ends it as well, for every session at once.'),
            typedField,
            el('div', {class: 'form-foot'}, unlock, ' ', busy)),
            whatIsAWrite());
    }

    function drawRemembered() {
        if (destinations === null) {
            clear(remembered);
            return;
        }
        // The action column is there only when the server offers Forget for at least one address.
        const offered = destinations.some(row => row.forgettable === true);
        fill(remembered, part('Addresses remembered as production',
            destinations.length === 0
                ? el('p', {class: 'muted'}, 'None. An address is remembered once an environment of kind prod uses it.')
                : el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
                    el('thead', null, el('tr', null, el('th', {scope: 'col'}, 'Address'), el('th', {scope: 'col'}, 'Environment'),
                        el('th', {scope: 'col'}, 'Environment still stored'), offered ? el('th', {scope: 'col'}, 'Forget') : null)),
                    el('tbody', null, destinations.map(row => el('tr', null,
                        el('td', null, el('span', {class: 'mono'}, row.destination || '')),
                        el('td', null, row.environment || ''),
                        el('td', null, row.stored === true ? 'Yes' : 'No, it was removed'),
                        offered ? el('td', null, row.forgettable === true
                            ? button('Forget this production address', () => confirmForget(row), {small: true}) : null) : null)))))));
    }

    /**
     * Forget asks for the name of the environment that made the address production, typed exactly
     * as it was. The server decides whether the name matches and whether the address may be
     * forgotten at all: this page sends what was typed, as typed.
     */
    function confirmForget(row) {
        const name = el('input', {type: 'text', class: 'input', autocomplete: 'off', spellcheck: false});
        const nameField = field({label: 'Type the name of the environment it was remembered for, exactly as it was', input: name});
        const problem = el('div', {'aria-live': 'polite'});
        const busy = el('span', {class: 'muted small', role: 'status'});
        name.addEventListener('input', () => setFieldError(nameField, null));
        modal({
            title: 'Forget this production address?',
            body: [el('p', null, el('span', {class: 'mono'}, row.destination || '')),
                el('p', null, 'It is remembered as production for ', el('strong', {class: 'environment-name'}, row.environment || ''),
                    ', which is no longer stored as production. Once it is forgotten, an environment of kind sandbox or uat can use this address.'),
                nameField, problem],
            footer: dialog => {
                const forget = button('Forget this production address', () => during(forget, busy, 'Forgetting', async () => {
                    if (await sendForget(row, name.value, nameField, problem)) {
                        dialog.close();
                    }
                }), {primary: true});
                name.addEventListener('keydown', event => {
                    if (event.key === 'Enter') {
                        forget.click();
                    }
                });
                return [busy, button('Keep it', () => dialog.close()), forget];
            }
        });
    }

    /** Returns true when the server forgot the address. */
    async function sendForget(row, typedName, nameField, problem) {
        setFieldError(nameField, null);
        clear(problem);
        // The name goes exactly as it was typed, spaces and capitals included. The server compares it.
        const answer = await call('POST', '/api/config/production-destinations/forget',
            {body: {destination: row.destination, environment: typedName}});
        if (!answer.ok) {
            const atField = answer.error && Array.isArray(answer.error.fieldErrors)
                ? answer.error.fieldErrors.find(each => each && each.field === 'environment') : null;
            if (atField) {
                setFieldError(nameField, atField.message);
            } else {
                fill(problem, errorPanel(answer.error));
            }
            return false;
        }
        if (!gone) {
            fill(outcome, el('p', {role: 'status'}, el('span', {class: 'mono'}, row.destination || ''), ' is no longer remembered as production.'));
            await load();
        }
        return true;
    }

    async function load() {
        const [effective, environments] = await Promise.all([call('GET', '/api/config/effective'), call('GET', '/api/config/environments')]);
        if (gone) {
            return;
        }
        if (effective.ok && effective.data && Array.isArray(effective.data.operations)) {
            writeRows = effective.data.operations.filter(row => row.write === true);
        }
        if (environments.ok && environments.data && Array.isArray(environments.data.productionDestinations)) {
            destinations = environments.data.productionDestinations;
        }
        draw();
        drawRemembered();
    }

    const offs = [app.on('mode', () => draw()), app.on('config', () => load())];
    draw();
    load();

    return {
        update() {},
        unmount() {
            gone = true;
            offs.forEach(off => off());
            page.remove();
        }
    };
}
