// Configuration > Advanced > Send as written: any request of an uploaded collection, sent with its
// own method, url and headers and its placeholders filled in, before any of PAVE's own mapping is
// involved. A body of the tester's own goes in the box; the vendor's sample can be put there first.
//
// POST /api/raw answers the shared run envelope, so the result is the shared result card, the
// guardrail judges the request by its method, name and url, and the ledger records the run like
// any other. The placeholders the request needs are listed from POST /api/config/resolve, which
// says where each value would come from. A value typed for a secret is sent and then cleared from
// its field. Nothing typed here is kept beyond the page.

import {call} from './api.js';
import {app} from './app.js';
import {el, fill, clear, chip, button, fold, kv, field, setFieldError} from './dom.js';
import {errorPanel} from './fault.js';
import {jsonView} from './json-view.js';
import {resultCard} from './result.js';
import {remember} from './session-runs.js';
import {pageHead, section, part, during, sentences, none, counted} from './cfg-parts.js';

/**
 * The sample body of a collection request as the collection holds it, read from one answer of
 * POST /api/config/resolve: the request named, or else the one the operation uses by default.
 * One of {sample: {name, body, scripted, operation}}, {unmapped: {operation}},
 * {noBody: {name, operation}}, {refused: error}. Every page that offers the vendor's sample reads
 * it here, so it is read one way.
 */
export async function readSample(operation, request) {
    const body = request ? {operation, request} : {operation};
    const answer = await call('POST', '/api/config/resolve', {body});
    if (!answer.ok) {
        return {refused: answer.error};
    }
    const resolved = answer.data || {};
    const named = resolved.label || operation;
    if (!resolved.requestName) {
        return {unmapped: {operation: named}};
    }
    if (typeof resolved.bodyTemplate !== 'string' || resolved.bodyTemplate.trim() === '') {
        return {noBody: {name: resolved.requestName, operation: named}};
    }
    return {sample: {name: resolved.requestName, body: resolved.bodyTemplate, scripted: resolved.scripted === true, operation: named}};
}

/** Why there is no sample to use, in words, with the way to Collections where one is to be mapped. */
function noSample(found) {
    if (found.refused) {
        return [el('strong', null, 'The vendor\'s sample could not be read. '), found.refused.title || '', ' ', found.refused.action || ''];
    }
    if (found.unmapped) {
        return ['No collection request is mapped to ', found.unmapped.operation, ', so there is no vendor\'s sample to use. ',
            button('Open Collections', () => app.go('#configuration/collections'), {small: true, quiet: true})];
    }
    return ['The collection request ', el('strong', null, found.noBody.name), ', mapped to ', found.noBody.operation,
        ', has no sample body to use.'];
}

/**
 * The button that puts the vendor's sample in a box, exactly as the collection holds it. The
 * sample is read when the button is pressed, never earlier. target(): {operation, request} or
 * null when nothing is chosen yet. box: the text area. said: the line that says what happened.
 * changed(): called after the box was filled, as typing would.
 */
export function sampleButton(target, box, said, changed) {
    const busy = el('span', {class: 'muted small', role: 'status'});
    const control = button('Put the vendor\'s sample in the box', () => during(control, busy, 'Reading the vendor\'s sample', async () => {
        const wanted = target();
        if (!wanted) {
            return;
        }
        const found = await readSample(wanted.operation, wanted.request);
        if (!found.sample) {
            fill(said, noSample(found));
            return;
        }
        box.value = found.sample.body;
        changed();
        fill(said, 'The sample of ', el('strong', null, found.sample.name), ' is in the box, as the collection holds it.',
            found.sample.scripted ? ' It carries a pre-request script, which PAVE never runs.' : null);
        box.focus();
    }), {small: true});
    return el('span', {class: 'inline-controls'}, control, busy);
}

/** Null when the text is valid JSON, otherwise the browser's own sentence about where it is not. */
export function jsonProblem(text) {
    try {
        JSON.parse(text);
        return null;
    } catch (failure) {
        return failure.message + '.';
    }
}

function isSpace(character) {
    return character === ' ' || character === '\t' || character === '\r' || character === '\n';
}

/**
 * Re-indents valid JSON by walking its characters. Parsing and writing it out again would not do:
 * that rewrites numbers such as 1.0 and 12345678901234567890, and keeps only the last of a
 * repeated key. Here only the white space between tokens changes; strings, numbers and every key
 * stay exactly as typed.
 */
export function indent(text) {
    const out = [];
    let depth = 0;
    let inString = false;
    let escaped = false;
    const newline = () => '\n' + '  '.repeat(Math.max(depth, 0));
    for (let index = 0; index < text.length; index += 1) {
        const character = text[index];
        if (inString) {
            out.push(character);
            if (escaped) {
                escaped = false;
            } else if (character === '\\') {
                escaped = true;
            } else if (character === '"') {
                inString = false;
            }
            continue;
        }
        if (character === '"') {
            inString = true;
            out.push(character);
        } else if (character === '{' || character === '[') {
            // An empty object or array stays on one line.
            let next = index + 1;
            while (next < text.length && isSpace(text[next])) {
                next += 1;
            }
            if (text[next] === (character === '{' ? '}' : ']')) {
                out.push(character, text[next]);
                index = next;
            } else {
                depth += 1;
                out.push(character, newline());
            }
        } else if (character === '}' || character === ']') {
            depth -= 1;
            out.push(newline(), character);
        } else if (character === ',') {
            out.push(character, newline());
        } else if (character === ':') {
            out.push(': ');
        } else if (!isSpace(character)) {
            out.push(character);
        }
    }
    return out.join('');
}

/**
 * A box for a JSON body of the tester's own, with a line that says whether it is valid JSON and a
 * Format button that changes only white space. A malformed body is a negative case a tester may
 * mean to send, so it is said, never refused. onChange(problem): problem is null for valid JSON
 * or empty text, else the sentence.
 */
export function bodyBox(label, hint, onChange) {
    const box = el('textarea', {class: 'json mono', value: '', rows: 14, spellcheck: false, name: 'rawBody', autocomplete: 'off',
        attrs: {cols: 90}});
    const validity = el('p', {class: 'small', role: 'status'});
    const formatSaid = el('span', {class: 'small muted', role: 'status'});
    const wrapper = field({label, input: box, hint});
    const check = () => {
        const text = String(box.value || '');
        if (text.trim() === '') {
            fill(validity, none('Nothing in the box.'));
            onChange(null);
            return;
        }
        const problem = jsonProblem(text);
        fill(validity, problem === null ? 'Valid JSON.' : [el('strong', null, 'Not valid JSON as written. '), problem,
            text.includes('{{') ? ' Placeholders are filled in before sending, which may change that.' : null]);
        onChange(problem);
    };
    const format = button('Format the JSON', () => {
        if (jsonProblem(box.value) !== null) {
            formatSaid.textContent = 'Not formatted: the text is not valid JSON, and invalid text is never changed.';
            return;
        }
        box.value = indent(box.value);
        formatSaid.textContent = 'Formatted. Only the white space changed.';
        check();
    }, {small: true, quiet: true});
    box.addEventListener('input', () => {
        formatSaid.textContent = '';
        check();
    });
    check();
    return {box, wrapper, tools: el('div', {class: 'inline-controls'}, validity, format, formatSaid), changed: check};
}

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let gone = false;
    let collections = [];
    let chosen = null;      // {collection, request}
    let reading = 0;
    let notJson = null;     // the box's problem, or null
    const fields = new Map();   // field name the server uses -> the field's wrapper

    const said = el('p', {class: 'small', role: 'status'});
    const picker = el('select', {class: 'select', name: 'request'});
    const pickerField = field({label: 'Request', input: picker});
    fields.set('request', pickerField);
    const about = el('div');
    const values = el('div');
    const busy = el('span', {class: 'muted small', role: 'status'});
    const sendButton = button('Send as written', () => during(sendButton, busy, 'Sending the request as written', send), {primary: true});
    const body = bodyBox('Body to send',
        'Left empty, the body saved with the collection request goes, if it has one. Placeholders in the box are filled in when it is sent.',
        problem => {
            notJson = problem;
            sendButton.textContent = problem === null ? 'Send as written' : 'Send as written anyway, as it is not valid JSON';
        });
    fields.set('rawBody', body.wrapper);
    const sampleSaid = el('p', {class: 'small', role: 'status'});
    const sample = sampleButton(() => (chosen ? {operation: chosen.request.effectiveOperation || 'UNMAPPED',
        request: {collectionId: chosen.collection.id, requestId: chosen.request.id}} : null), body.box, sampleSaid, body.changed);
    const testerRef = el('input', {class: 'input', name: 'testerRef', value: '', autocomplete: 'off', placeholder: 'ticket or initials'});
    const note = el('input', {class: 'input', name: 'note', value: '', autocomplete: 'off'});
    const previewButton = button('Show the request', () => during(previewButton, busy, 'Reading the request before it is sent', preview));
    const problem = el('div');
    const answer = el('div');
    const form = el('div', null,
        pickerField, about, values,
        part(null, body.wrapper, el('div', {class: 'inline-controls'}, sample), sampleSaid, body.tools),
        fold({summary: 'Tester reference and note', hint: 'kept in the ledger with the run',
            body: el('div', {class: 'field-row'}, field({label: 'Tester reference', input: testerRef}), field({label: 'Note', input: note}))}),
        el('div', {class: 'form-foot'}, sendButton, previewButton, busy),
        problem);
    const content = el('div');
    const page = section(pageHead('Send as written', 'A request of a collection, sent with its own method, url and headers.'), said, content, answer);
    root.append(page);
    picker.addEventListener('change', () => choose(picker.value));
    const off = app.on('config', () => {
        clear(answer);
        load().then(() => {
            if (!gone && chosen) {
                said.textContent = 'The configuration changed. The placeholders of the chosen request were read again.';
            }
        });
    });

    async function load() {
        const got = await call('GET', '/api/config/collections');
        if (gone) {
            return;
        }
        if (!got.ok) {
            fill(content, errorPanel(got.error));
            return;
        }
        collections = Array.isArray((got.data || {}).collections) ? got.data.collections : [];
        const requests = collections.reduce((sum, each) => sum + (Array.isArray(each.requests) ? each.requests.length : 0), 0);
        if (requests === 0) {
            chosen = null;
            fill(content, el('p', null, collections.length === 0 ? 'No collection is uploaded. ' : 'The collections uploaded hold no request. ',
                'Send as written sends a request of an uploaded collection. ',
                button('Open Collections', () => app.go('#configuration/collections'), {small: true})));
            return;
        }
        const options = [el('option', {value: ''}, 'Choose a request')];
        for (const collection of collections) {
            const group = el('optgroup', {label: collection.name || collection.id});
            for (const request of collection.requests || []) {
                const mapped = request.effectiveOperation && request.effectiveOperation !== 'UNMAPPED' ? request.effectiveLabel : 'not mapped';
                group.append(el('option', {value: collection.id + ' ' + request.id}, request.name + ' (' + request.method + ', ' + mapped + ')'));
            }
            options.push(group);
        }
        fill(picker, options);
        const key = chosen ? chosen.collection.id + ' ' + chosen.request.id : '';
        if (key && !find(key)) {
            chosen = null;
        }
        picker.value = chosen ? key : '';
        fill(content, form);
        if (chosen) {
            await choose(picker.value);
        }
    }

    function find(key) {
        for (const collection of collections) {
            for (const request of collection.requests || []) {
                if (collection.id + ' ' + request.id === key) {
                    return {collection, request};
                }
            }
        }
        return null;
    }

    async function choose(key) {
        chosen = key ? find(key) : null;
        clear(about);
        clear(values);
        clear(problem);
        clear(sampleSaid);
        clear(said);
        for (const wrapper of fields.values()) {
            setFieldError(wrapper, '');
        }
        if (!chosen) {
            return;
        }
        const request = chosen.request;
        fill(about, kv([
            {key: 'Method and url', value: el('span', {class: 'mono'}, request.method + ' ' + (request.urlTemplate || ''))},
            {key: 'Mapped to', value: request.effectiveOperation && request.effectiveOperation !== 'UNMAPPED' ? request.effectiveLabel : 'no operation'},
            request.scripted ? {key: 'Scripts', value: chip('script, not run', {tone: 'action'})} : null
        ]));
        const turn = ++reading;
        const resolved = await call('POST', '/api/config/resolve', {body: {operation: request.effectiveOperation || 'UNMAPPED',
            request: {collectionId: chosen.collection.id, requestId: request.id}}});
        if (gone || turn !== reading) {
            return;
        }
        if (!resolved.ok) {
            fill(values, el('p', {class: 'small'}, el('strong', null, 'The placeholders of this request could not be listed. '),
                'Those it needs are still named if sending is refused.'), errorPanel(resolved.error));
            return;
        }
        drawValues(resolved.data || {});
    }

    /** One field for every placeholder and every {name} part of the url, each with where its value comes from now. */
    function drawValues(resolved) {
        for (const name of [...fields.keys()]) {
            if (name.startsWith('variables.') || name.startsWith('urlValues.')) {
                fields.delete(name);
            }
        }
        const variables = Array.isArray(resolved.variables) ? resolved.variables : [];
        const tokens = (Array.isArray(resolved.urlTokens) ? resolved.urlTokens : []).filter(token => !token.supplied);
        const tokenFields = tokens.map(token => {
            const input = el('input', {class: 'input mono', value: '', autocomplete: 'off', spellcheck: false, dataset: {token: token.name}});
            const wrapper = field({label: '{' + token.name + '} in the url', input,
                hint: token.savedValue ? 'The collection saved ' + token.savedValue + ' here. It is never sent unless it is typed.' : null});
            fields.set('urlValues.' + token.name, wrapper);
            return wrapper;
        });
        const variableFields = variables.map(variable => {
            const input = el('input', {class: 'input mono', value: '', type: variable.secret ? 'password' : 'text', autocomplete: 'off',
                spellcheck: false, dataset: {variable: variable.name}});
            const wrapper = field({label: '{{' + variable.name + '}}' + (variable.secret ? ', a secret' : ''), input,
                hint: variable.resolved
                    ? 'Now: ' + (variable.display || '') + ' (' + (variable.sourceLabel || variable.source || 'source not stated') + ')'
                    : 'No value. ' + (variable.reason || '')});
            fields.set('variables.' + variable.name, wrapper);
            return wrapper;
        });
        fill(values,
            tokenFields.length > 0 ? part('Parts of the url', el('p', {class: 'small muted'}, 'Type the value each part waits for.'),
                sentences(resolved.notes), el('div', {class: 'field-row'}, tokenFields)) : null,
            variableFields.length > 0
                ? part('Placeholders (' + variableFields.length + ')', el('p', {class: 'small muted'},
                    'Each takes the value the configuration gives it. Type a value to use another one for this send only.'),
                el('div', {class: 'field-row'}, variableFields))
                : el('p', {class: 'small muted'}, 'The url and headers of this request have no placeholders.'));
    }

    function payload() {
        const variables = {};
        const urlValues = {};
        for (const input of values.querySelectorAll('input')) {
            if (input.value === '') {
                continue;
            }
            if (input.getAttribute('data-variable')) {
                variables[input.getAttribute('data-variable')] = input.value;
            } else if (input.getAttribute('data-token')) {
                urlValues[input.getAttribute('data-token')] = input.value;
            }
        }
        const sent = {
            request: chosen ? {collectionId: chosen.collection.id, requestId: chosen.request.id} : null,
            rawBody: String(body.box.value || ''),
            variables,
            configEpoch: app.epoch()
        };
        if (Object.keys(urlValues).length > 0) {
            sent.urlValues = urlValues;
        }
        if (testerRef.value.trim() !== '') {
            sent.testerRef = testerRef.value.trim();
        }
        if (note.value.trim() !== '') {
            sent.note = note.value.trim();
        }
        return sent;
    }

    /** A secret typed for one send has gone. It is not kept in its field. */
    function forgetSecrets() {
        for (const input of values.querySelectorAll('input')) {
            if (input.type === 'password') {
                input.value = '';
            }
        }
    }

    /** A refusal: each field error at its field, the focus on the first, the rest in the panel. */
    function refused(error) {
        const placed = [];
        for (const wrapper of fields.values()) {
            setFieldError(wrapper, '');
        }
        for (const each of Array.isArray(error.fieldErrors) ? error.fieldErrors : []) {
            const wrapper = fields.get(each.field);
            if (wrapper) {
                setFieldError(wrapper, each.message);
                placed.push(each.field);
            }
        }
        fill(problem, errorPanel(error, {fieldsPlaced: placed}));
        const first = placed.length > 0 ? fields.get(placed[0]).querySelector('input, select, textarea') : null;
        if (first) {
            first.focus();
        }
    }

    async function preview() {
        if (!chosen) {
            refused({kind: 'VALIDATION', title: 'Choose a request first. Nothing was shown.',
                fieldErrors: [{field: 'request', message: 'Choose the request to show.'}]});
            return;
        }
        const got = await call('POST', '/api/raw/preview', {body: payload()});
        forgetSecrets();
        if (gone) {
            return;
        }
        if (!got.ok) {
            refused(got.error);
            return;
        }
        clear(problem);
        fill(answer, previewPanel(got.data || {}, () => {
            clear(answer);
            previewButton.focus();
        }));
    }

    async function send() {
        if (!chosen) {
            refused({kind: 'VALIDATION', title: 'Choose a request first. Nothing was sent.',
                fieldErrors: [{field: 'request', message: 'Choose the request to send.'}]});
            return;
        }
        const got = await call('POST', '/api/raw', {body: payload()});
        forgetSecrets();
        if (gone) {
            return;
        }
        if (!got.ok && !(got.data && typeof got.data.runId === 'string')) {
            refused(got.error);
            return;
        }
        clear(problem);
        for (const wrapper of fields.values()) {
            setFieldError(wrapper, '');
        }
        const envelope = got.data;
        remember(envelope);
        fill(answer, part('The result', resultCard(envelope, {context: 'workbench'})));
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

/** The request as it would go, before anything is sent. */
function previewPanel(preview, close) {
    const url = preview.url || {};
    const headers = Array.isArray(preview.headers) ? preview.headers : [];
    return part('The request before it is sent',
        el('p', {class: 'small'}, el('strong', null, 'Nothing was sent. '), button('Close preview', close, {small: true, quiet: true})),
        preview.blocked ? el('p', null, el('strong', null, preview.blocked)) : null,
        kv([
            {key: 'Request', value: preview.requestName || none('not stated')},
            {key: 'Method', value: el('span', {class: 'mono'}, preview.method || '')},
            {key: 'Url', value: url.display ? el('span', {class: 'mono'}, url.display) : none('not complete')},
            {key: 'Url as configured', value: url.template ? el('span', {class: 'mono'}, url.template) : none('none')},
            {key: 'Environment', value: preview.environment && preview.environment.active
                ? preview.environment.name + ' (' + preview.environment.kind + ')' : none('none active')}
        ]),
        sentences(preview.notes),
        headers.length > 0 ? kv(headers.map(header => ({key: el('span', {class: 'mono'}, header.name || ''),
            value: el('span', {class: 'mono'}, header.display || header.value || header.template || '')}))) : null,
        typeof preview.body === 'string' ? jsonView(preview.body, {label: 'Request body'}) : el('p', {class: 'small muted'}, 'No body goes with it.'),
        preview.scripted ? el('p', {class: 'small'}, counted(1, 'script', 'scripts') + ' on this request: PAVE never runs it.') : null);
}
