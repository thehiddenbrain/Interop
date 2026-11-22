// Values for one run (plan 3.2, "Values for one run"): placeholders a request needs that nothing
// defines. The server refuses such a run before sending and names each placeholder as a field,
// variables.<name>. This block then shows one field per placeholder, says where the request uses it,
// and every run of the Workbench sends what is typed as its "variables". A value that should last
// belongs in the environment's Values, and the block says so.
//
// A value typed here lives in the page's memory only, until "Remove these values", "Start a new
// case" or a reload: never in browser storage. A name the server reads as a secret, or one that
// reads as a credential when the server could not say, gets a password field, and its value is
// never written back into the page.

import {call} from './api.js';
import {el, button, fill, clear} from './dom.js';

// The one block of the page: sendRun and showPreview read it, whichever step sends.
const typed = new Map();
let shown = null;

/**
 * The block, built once by the Workbench. env: {changed()} (a value typed or the block put away).
 * Returns {node, need(fieldErrors, operation), show(field, message), clear()}.
 */
export function createRunValues(env) {
    const grid = el('div', {class: 'run-values__grid'});
    const node = el('section', {class: 'run-values', hidden: true, 'aria-labelledby': 'run-values-title'},
        el('h3', {class: 'notes__title', id: 'run-values-title'}, 'Values for this run'),
        el('p', {class: 'small muted'}, 'The request uses placeholders that have no value. What is typed here goes with every run '
            + 'from this page until it is removed. A value that should last belongs in the environment\'s Values: ',
        el('a', {href: '#configuration/environments'}, 'Open Configuration'), '.'),
        grid,
        button('Remove these values', () => removeAll(), {small: true, quiet: true}));
    shown = node;

    /**
     * Makes a field for each placeholder a refusal names, keeping what was typed, at once, so the
     * refusal's sentence can be placed at its field; where each is used follows from the server.
     * Returns the names it holds a field for.
     */
    function need(fieldErrors, operation) {
        const names = placeholdersIn(fieldErrors);
        if (names.length === 0) {
            return [];
        }
        for (const name of names) {
            if (!typed.has(name)) {
                add(name);
            }
        }
        node.hidden = false;
        describe(names, operation);
        return names;
    }

    function add(name) {
        const id = 'run-value-' + typed.size;
        const secret = looksSecret(name);
        const input = el('input', {class: 'input mono', id, type: secret ? 'password' : 'text', autocomplete: 'off', spellcheck: false,
            name: 'variables.' + name});
        const where = el('p', {class: 'field__hint', id: id + '-where'});
        const error = el('p', {class: 'field__error', hidden: true});
        const field = el('div', {class: 'field', 'data-case-field': 'variables.' + name},
            el('label', {class: 'field__label', for: id}, el('span', {class: 'mono'}, '{{' + name + '}}')), input, where, error);
        input.setAttribute('aria-describedby', where.id);
        input.addEventListener('input', () => {
            error.hidden = true;
            error.textContent = '';
            if (env && typeof env.changed === 'function') {
                env.changed();
            }
        });
        typed.set(name, {input, where, error, field, secret});
        grid.append(field);
    }

    /** Where the configured request uses each name, and whether the server reads it as a secret, from POST /api/config/resolve. */
    async function describe(names, operation) {
        let rows = [];
        if (operation) {
            const answer = await call('POST', '/api/config/resolve', {body: {operation}});
            rows = answer.ok && answer.data && Array.isArray(answer.data.variables) ? answer.data.variables : [];
        }
        for (const name of names) {
            const entry = typed.get(name);
            if (!entry) {
                continue;
            }
            const row = rows.find(each => each && each.name === name) || null;
            if (row && row.secret === true && !entry.secret) {
                entry.secret = true;
                entry.input.type = 'password';
            }
            const places = row && Array.isArray(row.usedIn) ? row.usedIn.filter(place => typeof place === 'string' && place !== '') : [];
            fill(entry.where, places.length > 0 ? 'Used in ' + places.join(', ') + '.' : 'Where the request uses it was not reported.',
                entry.secret ? ' Read as a secret, so what is typed is not shown.' : null);
        }
    }

    function removeAll() {
        typed.clear();
        clear(grid);
        node.hidden = true;
        if (env && typeof env.changed === 'function') {
            env.changed();
        }
    }

    return {
        node,
        need,
        /** Shows a refusal's sentence at the field of a placeholder and focuses it; false when no field holds it. */
        show(fieldName, message) {
            const name = String(fieldName || '').startsWith('variables.') ? String(fieldName).slice('variables.'.length) : null;
            const entry = name ? typed.get(name) : null;
            if (!entry) {
                return false;
            }
            entry.error.textContent = message || '';
            entry.error.hidden = !message;
            entry.input.focus();
            return true;
        },
        clear: removeAll
    };
}

/** The names of the placeholders a refusal names as variables.<name>, each once. */
export function placeholdersIn(fieldErrors) {
    const names = [];
    for (const each of Array.isArray(fieldErrors) ? fieldErrors : []) {
        const field = each && typeof each.field === 'string' ? each.field : '';
        const name = field.startsWith('variables.') ? field.slice('variables.'.length) : '';
        if (name !== '' && !names.includes(name)) {
            names.push(name);
        }
    }
    return names;
}

/** The values typed, by name; a blank one is left out, never sent as empty. */
export function runValues() {
    const out = {};
    for (const [name, entry] of typed) {
        const value = String(entry.input.value || '');
        if (value !== '') {
            out[name] = value;
        }
    }
    return out;
}

/** A run's body with the values typed for this run as its "variables"; the body as it was when none is typed. */
export function withRunValues(body) {
    const values = runValues();
    if (!body || typeof body !== 'object' || Array.isArray(body) || Object.keys(values).length === 0) {
        return body;
    }
    return {...body, variables: {...values, ...(body.variables && typeof body.variables === 'object' ? body.variables : {})}};
}

/** True while the block is on the page and holds a field. */
export function holdsValues() {
    return Boolean(shown) && typed.size > 0;
}

/** The operation a run's address sends, as /api/config/resolve names it, or null for one it does not know. */
export function operationOf(path, body) {
    const address = String(path || '');
    if (address.startsWith('/api/crd')) {
        return body && body.hook === 'order-select' ? 'CRD_ORDER_SELECT' : 'CRD_ORDER_SIGN';
    }
    const known = [
        ['/api/dtr/package', 'DTR_QUESTIONNAIRE_PACKAGE'], ['/api/dtr/next-question', 'DTR_NEXT_QUESTION'], ['/api/dtr/response', 'DTR_RESPONSE'],
        ['/api/pas/submit', 'PAS_SUBMIT'], ['/api/pas/inquire', 'PAS_INQUIRE']
    ];
    for (const [start, operation] of known) {
        if (address.startsWith(start)) {
            return operation;
        }
    }
    return address.startsWith('/api/pas/subscriptions') ? 'SUBSCRIPTION_CREATE' : null;
}

/**
 * A name that reads as a credential. The server says so for the variables the request it resolves
 * holds; this is for one it did not describe. When in doubt the field is masked: masking a harmless
 * value costs nothing, showing a credential cannot be undone.
 */
export function looksSecret(name) {
    const words = wordsOf(String(name || ''));
    const last = words.length > 0 ? words[words.length - 1] : '';
    if (['url', 'uri', 'endpoint', 'host', 'path', 'type', 'name', 'scope', 'scopes', 'prefix', 'method', 'mode'].includes(last)) {
        return false;
    }
    const joined = words.join('');
    return ['secret', 'password', 'passwd', 'passphrase', 'token', 'apikey', 'key', 'credential', 'creds', 'auth', 'bearer', 'cookie',
        'signature', 'jwt', 'assertion', 'session', 'private', 'pwd'].some(part => joined.includes(part));
}

/** A name split into lower-case words at every character that is not a letter or digit, and before an upper-case letter after a lower-case one. */
function wordsOf(text) {
    const words = [];
    let current = '';
    let previousLower = false;
    for (const character of text) {
        const lower = character >= 'a' && character <= 'z';
        const upper = character >= 'A' && character <= 'Z';
        const digit = character >= '0' && character <= '9';
        if (!lower && !upper && !digit) {
            if (current !== '') {
                words.push(current);
            }
            current = '';
            previousLower = false;
            continue;
        }
        if (upper && previousLower && current !== '') {
            words.push(current);
            current = '';
        }
        current += character.toLowerCase();
        previousLower = lower || digit;
    }
    if (current !== '') {
        words.push(current);
    }
    return words;
}
