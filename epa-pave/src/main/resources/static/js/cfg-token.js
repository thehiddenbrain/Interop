// Configuration > Access token: how PAVE signs in to the active environment's service, the test of
// that sign-in, and a token pasted for this session (plan R-43).
//
// A pasted token is a secret: it is typed into a password field, sent once and the field is
// emptied; the server holds it in memory only and never gives it back, so nothing here can show
// it. A secret of the environment is said as "Stored" or "Not stored", never shown. A fact is drawn
// only when the server's answer carries it.

import {call} from './api.js';
import {app} from './app.js';
import {el, fill, clear, chip, button, field, kv} from './dom.js';
import {errorPanel} from './fault.js';
import {pageHead, section, part, during, none} from './cfg-parts.js';

const HELD_LINE = 'Held in memory for this environment, never saved. Cleared by a 401, a restart or a change of the configuration.';

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let gone = false;
    let active = null;        // the active environment as GET /api/config/environments lists it: {id, name, kind}
    let settings = null;      // GET /api/config/environments/{id}/settings, when the server serves it
    let heldHere = false;     // a token was pasted from this page and not forgotten since

    const facts = el('div');
    const tested = el('div', {'aria-live': 'polite'});
    const pasted = el('div');
    const page = section(pageHead('Access token', 'How PAVE signs in to the active environment\'s service.'), facts, tested, pasted);
    root.append(page);

    const token = el('input', {type: 'password', class: 'input', autocomplete: 'off', spellcheck: false, placeholder: 'paste the access token'});
    const tokenField = field({label: 'Paste a token', input: token});
    const pasteBusy = el('span', {class: 'muted small', role: 'status'});
    const pasteOutcome = el('div', {'aria-live': 'polite'});
    const use = button('Use this token', () => during(use, pasteBusy, 'Sending the token', sendToken), {primary: true});
    const forget = button('Forget the pasted token', () => during(forget, pasteBusy, 'Forgetting the token', forgetToken), {quiet: true});

    async function sendToken() {
        clear(pasteOutcome);
        const value = token.value;
        // The field is emptied before the answer comes, so the token is never left on screen.
        token.value = '';
        const answer = await call('POST', '/api/config/sign-in/token', {body: {token: value, environment: active ? active.id : null}});
        if (!answer.ok) {
            fill(pasteOutcome, errorPanel(answer.error));
            return;
        }
        heldHere = Boolean(answer.data && answer.data.held === true);
        drawPaste();
    }

    async function forgetToken() {
        clear(pasteOutcome);
        const answer = await call('DELETE', '/api/config/sign-in/token');
        if (!answer.ok) {
            fill(pasteOutcome, errorPanel(answer.error));
            return;
        }
        heldHere = false;
        drawPaste();
    }

    async function testSignIn(control, line) {
        clear(tested);
        const answer = await call('POST', '/api/config/sign-in/test');
        if (!answer.ok) {
            fill(tested, errorPanel(answer.error));
            return;
        }
        const data = answer.data || {};
        fill(tested, el('p', {role: 'status'}, data.line || '',
            // The line is a whole sentence of the server's, so the time follows as a sentence of its own.
            typeof data.elapsedMillis === 'number' ? el('span', {class: 'muted'}, ' Took ' + data.elapsedMillis + ' ms.') : null));
    }

    function drawFacts() {
        const mode = app.mode() || {};
        const environment = mode.environment || {};
        const signInState = mode.signIn && typeof mode.signIn === 'object' && mode.signIn.short ? mode.signIn : null;
        const signIn = settings && settings.signIn && typeof settings.signIn === 'object' ? settings.signIn : null;
        const secret = signIn && signIn.clientSecret && typeof signIn.clientSecret === 'object' ? signIn.clientSecret : null;
        const rows = [
            {key: 'Environment', value: environment.active ? el('span', {class: 'environment-name'}, environment.name) : none('none is active')}
        ];
        if (signIn) {
            rows.push({key: 'Method', value: signIn.inForceWords || signIn.method || none('not stated')});
            if (signIn.tokenUrl !== undefined) {
                rows.push({key: 'Token url', value: signIn.tokenUrl ? el('span', {class: 'mono'}, signIn.tokenUrl) : none('not set')});
            }
            if (signIn.clientId !== undefined) {
                rows.push({key: 'Client id', value: signIn.clientId ? el('span', {class: 'mono'}, signIn.clientId) : none('not set')});
            }
            if (secret) {
                rows.push({key: 'Client secret', value: secret.set === true ? chip('Stored', {tone: 'neutral'}) : none('Not stored')});
            }
        }
        if (signInState) {
            rows.push({key: 'State', value: chip(signInState.short, {tone: signInState.state === 'FAILED' ? 'fault' : null, title: signInState.detail || null})});
        }
        const line = el('span', {class: 'muted small', role: 'status'});
        // The test endpoint comes with the sign-in state in /api/mode, so it is offered only when that state is served.
        const test = !app.isMock() && signInState ? button('Test the sign-in', () => during(test, line, 'Signing in', () => testSignIn(test, line))) : null;
        const edit = active ? button('Edit environment', () => app.go('#configuration/environments/' + encodeURIComponent(active.id) + '/sign-in')) : null;
        fill(facts, el('div', {class: 'form-section'},
            app.isMock() ? el('p', null, 'Mock profile: nothing signs in.') : null,
            kv(rows),
            test || edit ? el('div', {class: 'form-foot'}, test, test ? ' ' : null, edit, ' ', line) : null));
    }

    function drawPaste() {
        const mode = app.mode() || {};
        const environment = mode.environment || {};
        // On mock nothing signs in, and the server refuses a pasted token there.
        if (app.isMock() || !environment.active || !active) {
            clear(pasted);
            return;
        }
        const held = heldHere || (mode.signIn && mode.signIn.state === 'PASTED');
        fill(pasted, part(null,
            tokenField,
            el('p', {class: 'small muted'}, HELD_LINE),
            el('div', {class: 'form-foot'}, use, held ? [' ', forget] : null, ' ', pasteBusy),
            pasteOutcome));
    }

    async function load() {
        const environments = await call('GET', '/api/config/environments');
        if (gone) {
            return;
        }
        const list = environments.ok && environments.data && Array.isArray(environments.data.environments) ? environments.data.environments : [];
        active = list.find(each => each.active) || null;
        settings = null;
        if (active) {
            const answer = await call('GET', '/api/config/environments/' + encodeURIComponent(active.id) + '/settings');
            if (gone) {
                return;
            }
            settings = answer.ok && answer.data && typeof answer.data === 'object' ? answer.data : null;
        }
        drawFacts();
        drawPaste();
    }

    const offs = [
        app.on('config', () => {
            // A change of the configuration drops a pasted token on the server; the page follows.
            heldHere = false;
            load();
        }),
        app.on('mode', () => {
            if (app.mode() && app.mode().signIn && app.mode().signIn.state !== 'PASTED') {
                heldHere = false;
            }
            drawFacts();
            drawPaste();
        })
    ];
    drawFacts();
    load();

    return {
        update() {},
        unmount() {
            gone = true;
            offs.forEach(off => off());
            token.value = '';
            page.remove();
        }
    };
}
