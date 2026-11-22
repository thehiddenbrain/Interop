// Configuration > Advanced > Try a value: how one operation's request would resolve, with values
// typed for this try.
//
// POST /api/config/resolve answers what a request would be without sending it: the url template
// beside what it resolves to, every placeholder it needs with where its value came from, the
// headers, the body, and whether the guardrail would let it through. A tester types a value for
// any placeholder, or for a {name} part of the url, and sees the effect. Nothing is sent and
// nothing is recorded. A value typed for a secret is sent once and never drawn back: its field is
// a password field, emptied as soon as the answer arrives, and the answer carries the server's
// mask, not the value.

import {call} from './api.js';
import {app} from './app.js';
import {el, fill, clear, chip, button, fold, kv, field, setFieldError} from './dom.js';
import {errorPanel} from './fault.js';
import {jsonView} from './json-view.js';
import {pageHead, section, part, during, sentences, none} from './cfg-parts.js';

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let gone = false;
    let rows = [];
    let last = null;
    const typed = new Map();        // placeholder or url part -> value typed, never for a secret
    const fields = new Map();       // the server's field name -> the field's wrapper

    const operationSelect = el('select', {class: 'select', name: 'operation'});
    const requestSelect = el('select', {class: 'select', name: 'request'});
    const operationField = field({label: 'Operation', input: operationSelect});
    const requestField = field({label: 'Request of a collection', input: requestSelect});
    fields.set('operation', operationField);
    fields.set('request', requestField);
    const valueFields = el('div');
    const problem = el('div');
    const answerNode = el('div');
    const busy = el('span', {class: 'muted small', role: 'status'});
    const go = button('Show how it resolves', () => during(go, busy, 'Resolving', resolveNow), {primary: true});
    const content = el('div');
    const form = el('div', null, el('div', {class: 'field-row'}, operationField, requestField), valueFields,
        el('div', {class: 'form-foot'}, go, busy), problem, answerNode);
    const page = section(pageHead('Try a value', 'How a request would resolve, and what a typed value changes. Nothing is sent and nothing is recorded.'),
        content);
    root.append(page);

    operationSelect.addEventListener('change', () => {
        last = null;
        typed.clear();
        drawRequests();
        clear(valueFields);
        clear(answerNode);
        clear(problem);
    });
    const off = app.on('config', () => {
        if (last) {
            last = null;
            clear(valueFields);
            fill(answerNode, el('p', null, el('strong', null, 'The configuration changed, so the answer shown here was taken away. '),
                'Press "Show how it resolves" to see it under the configuration in force now.'));
        }
        load();
    });

    async function load() {
        const got = await call('GET', '/api/config/effective');
        if (gone) {
            return;
        }
        if (!got.ok) {
            fill(content, errorPanel(got.error));
            return;
        }
        const operations = Array.isArray((got.data || {}).operations) ? got.data.operations : [];
        rows = operations.filter(row => row.outbound === true && row.operation !== 'UNMAPPED');
        if (rows.length === 0) {
            fill(content, el('p', null, 'PAVE listed no operation that leaves this machine, so there is nothing to resolve.'));
            return;
        }
        const kept = operationSelect.value;
        fill(operationSelect, rows.map(row => el('option', {value: row.operation}, row.label || row.operation)));
        operationSelect.value = rows.some(row => row.operation === kept) ? kept : rows[0].operation;
        drawRequests();
        fill(content, form);
    }

    function drawRequests() {
        const row = rows.find(candidate => candidate.operation === operationSelect.value);
        const requests = row && Array.isArray(row.requests) ? row.requests : [];
        const kept = requestSelect.value;
        // With nothing mapped, where the address comes from is the server's to say, in the answer.
        const options = [el('option', {value: ''}, requests.length > 0 ? 'The one the operation uses by default'
            : 'No request of a collection is mapped to it')];
        for (const available of requests) {
            const request = available.request || {};
            options.push(el('option', {value: available.collectionId + ' ' + request.id},
                request.name + ' (' + (available.collectionName || available.collectionId) + ')' + (available.usedByDefault ? ', used by default' : '')));
        }
        fill(requestSelect, options);
        requestSelect.value = options.some(option => option.value === kept) ? kept : '';
    }

    function collect() {
        const variables = {};
        const urlValues = {};
        for (const input of valueFields.querySelectorAll('input')) {
            const value = input.value;
            const name = input.getAttribute('data-variable') || input.getAttribute('data-token');
            const key = (input.getAttribute('data-variable') ? 'variables.' : 'urlValues.') + name;
            if (value !== '') {
                (input.getAttribute('data-variable') ? variables : urlValues)[name] = value;
            }
            if (input.type !== 'password') {
                if (value !== '') {
                    typed.set(key, value);
                } else {
                    typed.delete(key);
                }
            }
        }
        return {variables, urlValues};
    }

    async function resolveNow() {
        clear(problem);
        for (const wrapper of fields.values()) {
            setFieldError(wrapper, '');
        }
        const operation = operationSelect.value;
        if (!operation) {
            return;
        }
        const {variables, urlValues} = collect();
        const body = {operation};
        if (requestSelect.value) {
            const [collectionId, requestId] = requestSelect.value.split(' ');
            body.request = {collectionId, requestId};
        }
        if (Object.keys(variables).length > 0) {
            body.variables = variables;
        }
        if (Object.keys(urlValues).length > 0) {
            body.urlValues = urlValues;
        }
        const got = await call('POST', '/api/config/resolve', {body});
        // A secret typed for this try has been sent. It is not kept, here or in the field.
        for (const input of valueFields.querySelectorAll('input')) {
            if (input.type === 'password') {
                input.value = '';
            }
        }
        if (gone) {
            return;
        }
        if (!got.ok) {
            const placed = [];
            for (const each of Array.isArray(got.error.fieldErrors) ? got.error.fieldErrors : []) {
                const wrapper = fields.get(each.field);
                if (wrapper) {
                    setFieldError(wrapper, each.message);
                    placed.push(each.field);
                }
            }
            fill(problem, errorPanel(got.error, {fieldsPlaced: placed}));
            return;
        }
        last = got.data || {};
        drawValueFields();
        fill(answerNode, resolvedView(last));
    }

    /** One field for every placeholder and url part the latest answer named, so a value can be typed for any of them. */
    function drawValueFields() {
        for (const name of [...fields.keys()]) {
            if (name.startsWith('variables.') || name.startsWith('urlValues.')) {
                fields.delete(name);
            }
        }
        const variables = Array.isArray(last.variables) ? last.variables : [];
        const tokens = Array.isArray(last.urlTokens) ? last.urlTokens : [];
        if (variables.length === 0 && tokens.length === 0) {
            fill(valueFields, el('p', {class: 'small muted'}, last.url && last.url.template
                ? 'This request has no placeholders to type a value for.'
                : 'There is nothing to type a value for: the answer below names no url and no placeholder.'));
            return;
        }
        const one = (key, label, secret, dataset, hint) => {
            const input = el('input', {class: 'input mono', type: secret ? 'password' : 'text', autocomplete: 'off', spellcheck: false,
                dataset, value: secret ? '' : (typed.get(key) || '')});
            const wrapper = field({label, input, hint});
            fields.set(key, wrapper);
            return wrapper;
        };
        fill(valueFields, part('Values for this try',
            el('p', {class: 'small muted'}, 'A field left empty takes the value the configuration gives it. A value typed for a secret is sent once and then cleared.'),
            el('div', {class: 'field-row'},
                variables.map(variable => one('variables.' + variable.name, '{{' + variable.name + '}}' + (variable.secret ? ', a secret' : ''),
                    variable.secret === true, {variable: variable.name}, null)),
                tokens.map(token => one('urlValues.' + token.name, '{' + token.name + '} in the url', false, {token: token.name},
                    token.savedValue ? 'The collection saved ' + token.savedValue + ' here. It is never sent unless it is typed.' : null)))));
    }

    load();
    return {
        update() {},
        unmount() {
            gone = true;
            off();
            page.remove();
        }
    };
}

const mono = text => el('span', {class: 'mono'}, text);

/** What one operation's request resolves to, as the server answered it. */
function resolvedView(resolved) {
    const url = resolved.url || {};
    const environment = resolved.environment || {};
    const headers = Array.isArray(resolved.headers) ? resolved.headers : [];
    const variables = Array.isArray(resolved.variables) ? resolved.variables : [];
    const scripts = Array.isArray(resolved.scripts) ? resolved.scripts : [];
    const bodyText = typeof resolved.body === 'string' ? resolved.body : typeof resolved.bodyTemplate === 'string' ? resolved.bodyTemplate : null;
    const missingInBody = Array.isArray(resolved.bodyUnresolved) ? resolved.bodyUnresolved : [];
    return part('How it resolves',
        resolved.blocked ? el('div', {class: 'verdict verdict--blocked'},
            el('p', {class: 'verdict__label'}, 'The production guardrail would refuse this request now'),
            el('p', {class: 'verdict__headline'}, resolved.blocked)) : null,
        resolved.unavailable ? el('div', {class: 'verdict verdict--action'},
            el('p', {class: 'verdict__label'}, 'This cannot be sent as things stand'),
            el('p', {class: 'verdict__headline'}, resolved.unavailable)) : null,
        kv([
            {key: 'Operation', value: [resolved.label || resolved.operation || '', ' ', resolved.method ? mono(resolved.method) : null,
                resolved.write ? [' ', chip('write', {tone: 'action'})] : null]},
            {key: 'Environment', value: environment.active ? environment.name + ' (' + environment.kind + ')' : none('none active')},
            {key: 'Url as configured', value: url.template ? mono(url.template) : none('none')},
            {key: 'Url it resolves to', value: url.display ? mono(url.display)
                : !url.template && !resolved.urlShown ? none('there is no url')
                    : [none('not complete'), resolved.urlShown ? [' So far: ', mono(resolved.urlShown)] : null]},
            {key: 'Where the url comes from', value: url.source || url.sourceLabel || none('not stated')},
            {key: 'Collection request', value: resolved.requestName
                ? [resolved.requestName, resolved.scripted ? [' ', chip('script, not run', {tone: 'action'})] : null]
                : none('none: the address is not from a collection')}
        ]),
        sentences(resolved.notes),
        variables.length > 0
            ? kv(variables.map(variable => ({
                key: mono('{{' + variable.name + '}}'),
                value: variable.resolved
                    ? [mono(variable.display || ''), variable.secret ? [' ', chip('secret, masked', {tone: 'neutral'})] : null,
                        el('span', {class: 'kv__display'}, (variable.sourceLabel || variable.source || '')
                            + (Array.isArray(variable.usedIn) && variable.usedIn.length > 0 ? ', in ' + variable.usedIn.join(', ') : ''))]
                    : [el('strong', null, 'No value. '), variable.reason || '']
            })))
            : el('p', {class: 'small'}, url.template || resolved.requestName ? 'The request has no placeholders.'
                : 'There is no url and no request, so there is no placeholder.'),
        headers.length > 0 ? fold({summary: 'Headers', hint: String(headers.length), body: kv(headers.map(header => ({
            key: mono(header.name || ''),
            value: header.resolved ? mono(header.display || '')
                : [el('strong', null, 'Not complete: '), (header.unresolved || []).map(name => '{{' + name + '}}').join(', ')]
        })))}) : null,
        bodyText !== null ? fold({summary: typeof resolved.body === 'string' ? 'The body with its placeholders filled in' : 'The body as the collection holds it',
            hint: missingInBody.length > 0 ? 'still missing: ' + missingInBody.join(', ') : null,
            body: () => jsonView(bodyText, {label: 'Request body'})}) : null,
        scripts.length > 0 ? fold({summary: 'Scripts Postman would run, which PAVE never runs', hint: String(scripts.length),
            body: () => el('div', null, scripts.map(script => el('div', null,
                el('p', {class: 'small'}, mono(script.listen || ''), ' from ', script.from || ''),
                el('pre', {class: 'json', tabindex: 0}, script.source || ''))))}) : null);
}
