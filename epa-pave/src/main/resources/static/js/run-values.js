// Values for one run: placeholders a request needs that no environment defines.
//
// The server refuses such a run before sending and names each missing placeholder as a field,
// variables.<name>. This part then offers a field for each, says where the request uses it, and
// sends what is typed as the run request's "variables". A lasting value belongs in the environment
// on the Config tab, and this part says so.
//
// A value typed here lives in this page only, for as long as the page is open: it is never
// written to browser storage and never kept across a reload. A name that reads as a secret gets a
// password field, and its value is never echoed back into the page.

import * as api from './api.js';
import {el, fill, button, code} from './dom.js';
import {textField} from './forms.js';

/**
 * spec.operation()   the operation the tab is about to send, as /api/config/resolve takes it
 *                    ("crd-order-sign"), or null when the tab cannot say
 * spec.ownText()     the tester's own body while one is chosen, otherwise null
 * spec.navigate(id)  opens another tab
 * spec.changed()     called when a value is typed or the fields are put away
 */
export function createRunValues(spec) {
    const fields = new Map();   // name -> {control, where}
    const grid = el('div', {class: 'field-grid run-values__grid'});
    const node = el('section', {class: 'case-group run-values', hidden: true, aria: {labelledby: 'run-values-title'}},
        el('h2', {class: 'case-legend', id: 'run-values-title'}, 'Values for this run'),
        el('p', {class: 'small'}, 'The request uses placeholders that have no value. A value typed here is sent with the runs from '
            + 'this page and forgotten when the page is left. A value that should last belongs in the environment: ',
        button('Open the Config tab', () => spec.navigate('config'), {class: 'button--text'}), '.'),
        grid,
        el('p', null, button('Remove these values', removeAll, {class: 'button--text'})));

    /**
     * Makes a field for each placeholder a refusal names, keeping what was already typed. Called
     * before the refusal is marked, so the server's sentence lands at the field it is about.
     */
    async function need(fieldErrors) {
        const names = fieldErrors.map(problem => String(problem.field))
            .filter(field => field.startsWith('variables.')).map(field => field.slice('variables.'.length));
        if (names.length === 0) {
            return;
        }
        const known = await whatTheRequestSays();
        for (const name of names) {
            if (!fields.has(name)) {
                add(name, known.get(name));
            }
            describe(name, known.get(name));
        }
        node.hidden = false;
    }

    /** What /api/config/resolve says about each variable of the configured request: where it is used, whether it is a secret. */
    async function whatTheRequestSays() {
        const out = new Map();
        const operation = spec.operation();
        if (!operation) {
            return out;
        }
        const answer = await api.postJson('/api/config/resolve', {operation});
        if (answer.kind === 'ok') {
            for (const row of answer.body.variables || []) {
                out.set(row.name, row);
            }
        }
        return out;
    }

    function add(name, row) {
        const secret = row ? row.secret === true : looksSecret(name);
        const control = textField({label: code('{{' + name + '}}'), field: 'variables.' + name, literal: !secret, secret, wide: true});
        const where = el('span', {class: 'field__hint run-values__where', id: control.input.id + '-where'});
        control.node.append(where);
        control.input.setAttribute('aria-describedby', where.id);
        control.input.addEventListener('input', spec.changed);
        fields.set(name, {control, where, secret});
        grid.append(control.node);
    }

    function describe(name, row) {
        const entry = fields.get(name);
        const places = [];
        const own = spec.ownText();
        if (own !== null && own.includes('{{' + name + '}}')) {
            places.push('the body you pasted');
        }
        for (const place of (row && row.usedIn) || []) {
            // The body of the collection request is not what is sent while a body of the tester's own is.
            if (own !== null && /body/i.test(place)) {
                continue;
            }
            places.push(place);
        }
        fill(entry.where, places.length > 0 ? 'Used in ' + places.join(', ') + '.' : 'Where the request uses it was not reported.',
            entry.secret ? ' Read as a secret, so what is typed is not shown.' : null);
    }

    function removeAll() {
        fields.clear();
        grid.replaceChildren();
        node.hidden = true;
        spec.changed();
    }

    return {
        node,
        need,
        /** The values typed, by name, for the run request's "variables". Blank ones are left out, never sent as empty. */
        values() {
            const out = {};
            for (const [name, entry] of fields) {
                if (entry.control.input.value !== '') {
                    out[name] = entry.control.input.value;
                }
            }
            return out;
        },
        /** True when there are values to send. */
        any() {
            return Object.keys(this.values()).length > 0;
        }
    };
}

/**
 * A name that reads as a credential. The server decides the same for the variables it knows; this
 * is for a placeholder only a pasted body uses, which it has not described. When in doubt the
 * field is masked: masking a harmless value costs nothing, showing a credential cannot be undone.
 */
function looksSecret(name) {
    const words = String(name).replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
    const last = words[words.length - 1] || '';
    if (['url', 'uri', 'endpoint', 'host', 'path', 'type', 'name', 'scope', 'scopes', 'prefix', 'method', 'mode'].includes(last)) {
        return false;
    }
    return /(secret|password|passwd|passphrase|token|apikey|key|credential|creds|auth|bearer|cookie|signature|jwt|assertion|session|private|pwd)/
        .test(words.join(''));
}
