// Configuration > Members and Providers: the form of the member lookup or the provider lookup
// (plan 3.4, 5.5, R-11). The router draws it above the saved test lists, which are a page of their own.
//
// A lookup is configured once for the installation. A secret (an API key, a token, a client
// secret, a header value) never travels from the server to this page: the view says only whether
// one is stored, and a blank input keeps it (R-04). "Test the lookup" runs the saved settings,
// never the form as it stands, and says how the lookup ended in one line.

import {app} from './app.js';
import {call} from './api.js';
import {el, clear, fill, button, field, setFieldError, fold, firstSentence} from './dom.js';
import {errorPanel} from './fault.js';

/** The words of each page, by the first part of its address. */
export const KINDS = {
    members: {
        kind: 'member', title: 'Member lookup', token: '{memberId}', idName: 'member ID', tryLabel: 'Member ID to try',
        tryPlaceholder: 'S123456789', tryPath: '/api/members', tryName: 'memberId'
    },
    providers: {
        kind: 'provider', title: 'Provider lookup', token: '{npi}', idName: 'NPI', tryLabel: 'NPI to try',
        tryPlaceholder: '1234567893', tryPath: '/api/providers', tryName: 'npi'
    }
};

const SIGN_IN = [
    {value: 'NONE', words: 'None'},
    {value: 'API_KEY', words: 'API key header'},
    {value: 'BEARER_TOKEN', words: 'Bearer token'},
    {value: 'CLIENT_CREDENTIALS', words: 'Client credentials'},
    {value: 'ENVIRONMENT', words: "The active environment's sign-in"}
];

const ID_FORMS = [
    {value: 'AS_TYPED', words: 'As typed'},
    {value: 'PACKED', words: 'packed'},
    {value: 'SPACED', words: 'spaced'},
    {value: 'SPLIT', words: 'split'}
];

const DATE_FORMATS = ['yyyy-MM-dd', 'MM/dd/yyyy', 'yyyyMMdd'];

const MOCK_LINE = "On mock, lookups answer from PAVE's test fixtures. These settings are used on live.";

function kindOf(route) {
    return Array.isArray(route) && route[0] === 'providers' ? KINDS.providers : KINDS.members;
}

/** Draws the lookup form of the page the route names (members or providers). */
export function mount(root, route) {
    let gone = false;
    let reads = 0;            // a view read for an earlier address is dropped when it arrives late
    let current = route;
    const host = el('div', {class: 'config-lookup'});
    root.append(host);
    const off = app.on('config', () => show(current));

    async function show(next, said) {
        current = next;
        const words = kindOf(next);
        reads += 1;
        const reading = reads;
        const answer = await call('GET', '/api/config/lookups');
        if (gone || reading !== reads) {
            return;
        }
        if (!answer.ok && answer.status === 404) {
            // An installation without configurable lookups: nothing is drawn for a setting it does not have.
            clear(host);
            return;
        }
        if (!answer.ok) {
            fill(host, el('section', {class: 'section', 'aria-label': words.title},
                el('div', {class: 'section__head'}, el('h2', {class: 'section__title'}, words.title)),
                errorPanel(answer.error)));
            return;
        }
        const lookupView = answer.data ? answer.data[words.kind] : null;
        if (!lookupView || typeof lookupView !== 'object') {
            clear(host);
            return;
        }
        const form = createLookupForm(words, lookupView, line => show(current, line));
        fill(host, form.node);
        if (said) {
            form.say(said);
        }
    }

    show(route);
    return {
        update: next => show(next),
        unmount() {
            gone = true;
            off();
        }
    };
}

/**
 * The form of one lookup from its view (plan 5.5), and its test. reload(said) reads the view
 * again and draws the form afresh, with said (a node) in its status line.
 */
export function createLookupForm(words, view, reload) {
    const settings = view || {};
    const signIn = settings.signIn || {};
    const parts = new Map();
    const status = el('div', {class: 'config-status', role: 'status', 'aria-live': 'polite'});
    const inputs = {
        enabled: el('input', {type: 'checkbox'}),
        method: select([{value: 'GET', words: 'GET'}, {value: 'POST', words: 'POST'}]),
        address: text(),
        bodyTemplate: el('textarea', {class: 'input mono config-textarea', rows: 5, spellcheck: false}),
        dateFormat: select(DATE_FORMATS.map(each => ({value: each, words: each}))),
        idForm: select(ID_FORMS),
        signIn: select(SIGN_IN),
        headerName: text(),
        apiKey: secret(),
        removeApiKey: el('input', {type: 'checkbox'}),
        bearerToken: secret(),
        removeBearerToken: el('input', {type: 'checkbox'}),
        tokenUrl: text(),
        clientId: text(),
        clientSecret: secret(),
        removeClientSecret: el('input', {type: 'checkbox'}),
        scope: text('not set'),
        clientAuthentication: select([{value: 'BASIC', words: 'Basic header'}, {value: 'BODY', words: 'In the form body'}]),
        listPath: text(),
        connectMs: number(),
        readMs: number()
    };
    // The card fields a path may name are the server's (names and labels), never a copy kept here.
    const pathFields = (Array.isArray(settings.fields) ? settings.fields : []).filter(each => each && each.name && each.label);
    const pathInputs = new Map(pathFields.map(each => [each.name, text()]));
    const headers = [];

    inputs.enabled.checked = settings.enabled === true;
    inputs.method.value = settings.method === 'POST' ? 'POST' : 'GET';
    inputs.address.value = settings.address || '';
    inputs.bodyTemplate.value = settings.bodyTemplate || '';
    inputs.dateFormat.value = DATE_FORMATS.includes(settings.dateFormat) ? settings.dateFormat : DATE_FORMATS[0];
    // With no form stated the select shows packed, the form the server saves when none is given.
    inputs.idForm.value = ID_FORMS.some(each => each.value === settings.idForm) ? settings.idForm : 'PACKED';
    inputs.signIn.value = SIGN_IN.some(each => each.value === signIn.method) ? signIn.method : 'NONE';
    inputs.headerName.value = signIn.headerName || '';
    inputs.tokenUrl.value = signIn.tokenUrl || '';
    inputs.clientId.value = signIn.clientId || '';
    inputs.scope.value = signIn.scope || '';
    inputs.clientAuthentication.value = signIn.clientAuthentication === 'BODY' ? 'BODY' : 'BASIC';
    inputs.listPath.value = settings.listPath || '';
    for (const [name, input] of pathInputs) {
        input.value = settings.fieldPaths && settings.fieldPaths[name] ? settings.fieldPaths[name] : '';
    }
    inputs.connectMs.value = typeof settings.connectMs === 'number' ? String(settings.connectMs) : '';
    inputs.readMs.value = typeof settings.readMs === 'number' ? String(settings.readMs) : '';
    const defaults = settings.defaults || {};
    if (typeof defaults.connectMs === 'number') {
        inputs.connectMs.placeholder = defaults.connectMs + ', from application.yml';
    }
    if (typeof defaults.readMs === 'number') {
        inputs.readMs.placeholder = defaults.readMs + ', from application.yml';
    }

    function register(name, wrapper) {
        parts.set(name, wrapper);
        return wrapper;
    }

    const addressHint = el('p', {class: 'field__hint'});
    const addressField = register('address', field({label: 'Address', input: inputs.address}));
    addressField.append(addressHint);
    const bodyField = register('bodyTemplate', field({label: 'Body template', input: inputs.bodyTemplate,
        hint: 'Put ' + words.token + ' where the ' + words.idName + ' goes, and {dateOfService} for the date of service.'}));
    const dateField = register('dateFormat', field({label: 'Date format', input: inputs.dateFormat}));
    const signInFields = el('div', {class: 'form-grid'});
    const headerRows = el('div', {class: 'config-values'});
    // The server names a header's problem at "headers" as a whole; it is said under the rows.
    const headersLine = register('headers', el('div', {class: 'config-headers-line'}, el('p', {class: 'field__error', hidden: true})));
    for (const header of Array.isArray(settings.headers) ? settings.headers : []) {
        headerRows.append(headerRow(header));
    }
    const tryInput = text(words.tryPlaceholder);
    const tryDate = el('input', {class: 'input mono', type: 'date'});
    const tryDateField = field({label: 'Date of service to try', input: tryDate});
    const tryLine = el('div', {class: 'config-status', role: 'status', 'aria-live': 'polite'});
    const saveButton = button('Save lookup', () => save(), {primary: true});
    const tryButton = button('Test the lookup', () => test());

    function headerRow(header) {
        const entry = {stored: Boolean(header), name: text(), value: secret(), removed: false};
        entry.name.value = header && header.name ? header.name : '';
        if (header) {
            // A stored header is named by what it was saved as; a new name is a new header.
            entry.name.readOnly = true;
        }
        headers.push(entry);
        const stored = Boolean(header && header.value && header.value.set === true);
        const row = el('div', {class: 'config-value-row'},
            field({label: 'Name', input: entry.name}),
            field({label: 'Value', input: entry.value, hint: stored ? 'Stored' : null}),
            button('Remove the header', () => {
                entry.removed = true;
                row.hidden = true;
            }, {small: true, quiet: true}));
        return row;
    }

    function drawMethod() {
        const post = inputs.method.value === 'POST';
        addressHint.textContent = post ? 'The ' + words.idName + ' goes in the body, never in the address.'
            : 'Put {id} where the ' + words.idName + ' goes, in the path or the query.';
        bodyField.hidden = !post;
        dateField.hidden = !(post && inputs.bodyTemplate.value.includes('{dateOfService}'));
        tryDateField.hidden = dateField.hidden;
    }

    function drawSignIn() {
        const method = inputs.signIn.value;
        const rows = [];
        if (method === 'API_KEY') {
            rows.push(register('signIn.headerName', field({label: 'Header name', input: inputs.headerName})));
            rows.push(register('signIn.apiKey', secretField('API key', inputs.apiKey, inputs.removeApiKey, signIn.apiKey)));
        } else if (method === 'BEARER_TOKEN') {
            rows.push(register('signIn.bearerToken', secretField('Bearer token', inputs.bearerToken, inputs.removeBearerToken, signIn.bearerToken)));
        } else if (method === 'CLIENT_CREDENTIALS') {
            rows.push(register('signIn.tokenUrl', field({label: 'Token url', input: inputs.tokenUrl})));
            rows.push(register('signIn.clientId', field({label: 'Client id', input: inputs.clientId})));
            rows.push(register('signIn.clientSecret', secretField('Client secret', inputs.clientSecret, inputs.removeClientSecret, signIn.clientSecret)));
            rows.push(field({label: 'Scope', input: inputs.scope}));
            rows.push(field({label: 'Client authentication', input: inputs.clientAuthentication}));
        }
        fill(signInFields, rows);
        signInFields.hidden = rows.length === 0;
    }

    function secretField(label, input, removeBox, stored) {
        const set = Boolean(stored && stored.set === true);
        const wrapper = field({label, input, hint: set ? (stored.reference ? 'Stored: ' + stored.reference : 'Stored') : 'Not stored'});
        if (set) {
            wrapper.append(el('label', {class: 'config-check small'}, removeBox, el('span', null, 'Remove the stored secret')));
        }
        return wrapper;
    }

    inputs.method.addEventListener('change', drawMethod);
    inputs.bodyTemplate.addEventListener('input', drawMethod);
    inputs.signIn.addEventListener('change', drawSignIn);

    const inForce = settings.inForce && settings.inForce.words
        ? el('span', {class: 'muted small'}, settings.inForce.words + (settings.inForce.from ? ' (' + settings.inForce.from + ')' : ''))
        : null;
    // The settings are set once and read rarely: they wait in a closed fold, so the test and the
    // saved list below stay in view. A refusal opens it at the field.
    const settingsFold = fold({summary: 'Lookup settings', open: false, body: null});
    const node = el('section', {class: 'section', 'aria-label': words.title},
        el('div', {class: 'section__head'}, el('h2', {class: 'section__title'}, words.title), inForce),
        app.isMock() ? el('p', {class: 'muted'}, MOCK_LINE) : null,
        settings.problem ? el('p', {class: 'config-warning'}, settings.problem) : null,
        status,
        settingsFold,
        el('div', {class: 'config-try'},
            el('div', {class: 'config-try__row'}, field({label: words.tryLabel, input: tryInput}), tryDateField,
                el('div', {class: 'config-actions'}, tryButton, el('span', {class: 'field__hint'}, 'Uses the saved settings.'))),
            tryLine));
    settingsFold.querySelector('.fold__body').append(formBody());
    drawMethod();
    drawSignIn();

    function formBody() {
        return el('div', {class: 'config-form'},
            el('label', {class: 'config-check'}, inputs.enabled, el('span', null, 'Lookup on')),
            el('div', {class: 'form-grid'},
                register('method', field({label: 'Method', input: inputs.method})),
                words.kind === 'member' ? register('idForm', field({label: 'Member ID form', input: inputs.idForm})) : null,
                el('div', {class: 'field--full'}, addressField),
                el('div', {class: 'field--full'}, bodyField),
                dateField),
            el('div', {class: 'form-section'},
                el('div', {class: 'form-grid'}, register('signIn.method', field({label: 'Sign in with', input: inputs.signIn}))),
                signInFields),
            el('div', {class: 'form-section'},
                el('h3', {class: 'form-section__title'}, 'Headers'),
                headerRows,
                headersLine,
                el('div', null, button('Add a header', () => headerRows.append(headerRow(null)), {small: true}))),
            fold({summary: "Where the answer's fields are", open: Boolean(settings.listPath) || hasPaths(settings),
                body: [
                    el('p', {class: 'field__hint'}, 'Names joined by dots, [n] for a position, for example data.members[0].dob. Blank: PAVE looks in the usual places.'),
                    el('div', {class: 'form-grid'},
                        register('listPath', field({label: 'List of matches', input: inputs.listPath})),
                        pathFields.map(each => register('fieldPaths.' + each.name, field({label: each.label, input: pathInputs.get(each.name)}))))
                ]}),
            el('div', {class: 'form-grid'},
                register('connectMs', field({label: 'Connect timeout (ms)', input: inputs.connectMs})),
                register('readMs', field({label: 'Wait for a response up to (ms)', input: inputs.readMs}))),
            el('div', {class: 'config-actions'},
                saveButton,
                settings.stated === true ? button('Remove the saved lookup', () => remove(), {remove: true}) : null));
    }

    /** The body of PUT /api/config/lookups/<kind> (plan 5.5): secrets as typed, blank keeps. */
    function input() {
        const body = {
            enabled: inputs.enabled.checked === true,
            method: inputs.method.value,
            address: inputs.address.value.trim(),
            signIn: {method: inputs.signIn.value}
        };
        // A part hidden by the method is not sent, so a template typed under POST and left there is
        // never saved behind a GET.
        if (body.method === 'POST') {
            body.bodyTemplate = inputs.bodyTemplate.value;
            if (inputs.bodyTemplate.value.includes('{dateOfService}')) {
                body.dateFormat = inputs.dateFormat.value;
            }
        }
        if (words.kind === 'member') {
            body.idForm = inputs.idForm.value;
        }
        const method = inputs.signIn.value;
        if (method === 'API_KEY') {
            Object.assign(body.signIn, {headerName: inputs.headerName.value.trim(), apiKey: inputs.apiKey.value || '',
                removeApiKey: inputs.removeApiKey.checked === true});
        } else if (method === 'BEARER_TOKEN') {
            Object.assign(body.signIn, {bearerToken: inputs.bearerToken.value || '', removeBearerToken: inputs.removeBearerToken.checked === true});
        } else if (method === 'CLIENT_CREDENTIALS') {
            Object.assign(body.signIn, {tokenUrl: inputs.tokenUrl.value.trim(), clientId: inputs.clientId.value.trim(),
                clientSecret: inputs.clientSecret.value || '', removeClientSecret: inputs.removeClientSecret.checked === true,
                scope: inputs.scope.value.trim(), clientAuthentication: inputs.clientAuthentication.value});
        }
        body.headers = headers.filter(each => each.stored || each.name.value.trim() !== '')
            .map(each => ({name: each.name.value.trim(), value: each.value.value || '', remove: each.removed}));
        body.listPath = inputs.listPath.value.trim();
        body.fieldPaths = {};
        for (const [name, pathInput] of pathInputs) {
            body.fieldPaths[name] = pathInput.value.trim();
        }
        body.connectMs = whole(inputs.connectMs);
        body.readMs = whole(inputs.readMs);
        return body;
    }

    function clearSecrets() {
        // A secret typed into the page leaves it as soon as it is sent.
        for (const input of [inputs.apiKey, inputs.bearerToken, inputs.clientSecret]) {
            input.value = '';
        }
        for (const entry of headers) {
            entry.value.value = '';
        }
    }

    async function save() {
        clear(status);
        for (const wrapper of parts.values()) {
            setFieldError(wrapper, '');
        }
        saveButton.disabled = true;
        const body = input();
        clearSecrets();
        const answer = await call('PUT', '/api/config/lookups/' + words.kind, {body});
        saveButton.disabled = false;
        if (!answer.ok) {
            const placed = [];
            for (const each of answer.error && Array.isArray(answer.error.fieldErrors) ? answer.error.fieldErrors : []) {
                const wrapper = parts.get(each.field);
                if (wrapper) {
                    setFieldError(wrapper, each.message);
                    placed.push(each.field);
                    for (let at = wrapper.closest('details'); at; at = at.parentNode ? at.parentNode.closest('details') : null) {
                        at.open = true;
                    }
                }
            }
            fill(status, errorPanel(answer.error, {fieldsPlaced: placed}));
            return;
        }
        const warnings = answer.data && Array.isArray(answer.data.warnings) ? answer.data.warnings : [];
        const said = el('div', {class: 'config-said'}, el('p', null, 'Saved the ' + words.kind + ' lookup.'),
            warnings.length > 0 ? el('ul', {class: 'notes'}, warnings.map(each => el('li', null, each))) : null);
        if (typeof reload === 'function') {
            await reload(said);
        } else {
            fill(status, said);
        }
    }

    async function remove() {
        clear(status);
        const answer = await call('DELETE', '/api/config/lookups/' + words.kind);
        if (!answer.ok) {
            fill(status, errorPanel(answer.error));
            return;
        }
        const said = el('div', {class: 'config-said'}, el('p', null, "Removed the saved " + words.kind + " lookup."));
        if (typeof reload === 'function') {
            await reload(said);
        } else {
            fill(status, said);
        }
    }

    async function test() {
        clear(tryLine);
        const query = {[words.tryName]: tryInput.value.trim()};
        if (!tryDateField.hidden && tryDate.value) {
            query.dateOfService = tryDate.value;
        }
        tryButton.disabled = true;
        const started = Date.now();
        const answer = await call('GET', words.tryPath, {query});
        const waited = Date.now() - started;
        tryButton.disabled = false;
        if (!answer.ok) {
            fill(tryLine, errorPanel(answer.error));
            return;
        }
        fill(tryLine, el('p', null, testLine(answer.data, waited)));
    }

    return {
        node,
        input,
        say(said) {
            fill(status, said);
        }
    };
}

/**
 * The one line a lookup's test ends with: its status in the server's words, the HTTP status it got,
 * how long it took and the first sentence of the server's message. The time is the server's when it
 * states one, else the wait this page measured.
 */
export function testLine(result, waited) {
    const data = result || {};
    const response = data.wire && data.wire.response ? data.wire.response : null;
    const facts = [data.statusLabel || data.status];
    if (response && typeof response.status === 'number') {
        facts.push('HTTP ' + response.status);
    }
    const millis = typeof data.elapsedMillis === 'number' ? data.elapsedMillis : waited;
    if (typeof millis === 'number') {
        facts.push(millis + ' ms');
    }
    const [first] = firstSentence(data.message);
    if (first) {
        facts.push(first);
    }
    return facts.filter(Boolean).join(', ');
}

/* ---------- small pieces ---------- */

function hasPaths(settings) {
    return Boolean(settings.fieldPaths) && Object.values(settings.fieldPaths).some(Boolean);
}

function select(options) {
    const node = el('select', {class: 'select'});
    for (const option of options) {
        node.append(el('option', {value: option.value}, option.words));
    }
    return node;
}

function text(placeholder) {
    return el('input', {class: 'input mono', type: 'text', autocomplete: 'off', spellcheck: false, placeholder: placeholder || null});
}

function secret() {
    // A password field, never given a value from the server.
    return el('input', {class: 'input', type: 'password', autocomplete: 'new-password', spellcheck: false, placeholder: 'blank keeps stored'});
}

function number() {
    return el('input', {class: 'input mono', type: 'number', min: 100, max: 60000, step: 1});
}

function whole(input) {
    const value = input.value.trim();
    if (value === '') {
        return null;
    }
    const parsed = Number(value);
    // A value that is not a whole number goes as typed, so the server refuses it at its field.
    return Number.isInteger(parsed) ? parsed : value;
}
