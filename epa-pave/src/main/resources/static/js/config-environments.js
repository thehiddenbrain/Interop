// Configuration > Environments: one card per environment the tool holds, and the form that edits
// one, opened as a modal (plan 3.4, 5.4).
//
// Every word about an environment's settings is the server's: the method in force, the member ID
// rule and its options, the request shapes and why each exists, the values and where each comes
// from, the address of each operation and its source. A part of the form is drawn only when the
// server's view carries its field, so a server that does not yet answer a setting shows no control
// for it, rather than a control that saves nothing.
//
// A secret never travels from the server to this page: the view says only whether one is stored,
// and its reference when it is one. A secret input is a password field that starts empty, and an
// empty one keeps what is stored. Files are read in the page when the button that uses them is
// pressed (files.js) and posted as text.

import {app} from './app.js';
import {call} from './api.js';
import {el, clear, fill, chip, badge, fold, button, field, setFieldError, modal, named} from './dom.js';
import {errorPanel, faultReport} from './fault.js';
import {readText} from './files.js';
import {filesChangedNote, chooserInField, shownFile, labelsOf} from './cfg-parts.js';

const LIST = '/api/config/environments';
const DRAFT = '/api/config/environments/draft';
const SAVE = '/api/config/environments/settings';

/** The folds the address can open the form at: #configuration/environments/<id>/<section>. */
export const SECTIONS = ['sign-in', 'values', 'addresses', 'timeouts', 'shapes'];

const KINDS = ['sandbox', 'uat', 'prod'];

const METHODS = [
    {value: '', words: 'As PAVE was started'},
    {value: 'NONE', words: 'None'},
    {value: 'CLIENT_CREDENTIALS', words: 'Client credentials'},
    {value: 'BEARER_TOKEN', words: 'Bearer token'}
];

const CLIENT_AUTHENTICATION = [
    {value: 'BASIC', words: 'Basic header'},
    {value: 'BODY', words: 'In the form body'}
];

const PROFILE_WORDS = {mock: 'Mock, chosen when PAVE starts', live: 'Live, chosen when PAVE starts'};

const encode = encodeURIComponent;

/** Draws the Environments page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    const page = createPage(root);
    page.show(route);
    return {update: next => page.show(next), unmount: () => page.unmount()};
}

function createPage(root) {
    const state = {
        list: null,            // the last answer of GET /api/config/environments
        settings: new Map(),   // id -> the settings view, or null when the server gave none
        failure: null,         // the error of the list, when it could not be read
        reads: 0,              // counts reads, so a slow answer never draws over a newer one
        route: [],
        editor: null,          // the form on screen, or null
        gone: false
    };
    const status = el('div', {class: 'config-status', role: 'status', 'aria-live': 'polite'});
    const grid = el('div', {class: 'env-grid'});
    const problems = el('div');
    const section = el('section', {class: 'section', 'aria-labelledby': 'config-environments-title'},
        el('div', {class: 'section__head'},
            el('h2', {class: 'section__title', id: 'config-environments-title'}, 'Environments'),
            el('span', {class: 'muted'}, 'Each environment is one service to test: its sign-in, its addresses, its kind.'),
            el('div', {class: 'section__end'}, button('New environment', () => openEditor(null, null), {primary: true}))),
        status, problems, grid);
    fill(root, section);

    // A change made anywhere, in this window or another, moves the epoch: the cards are read again.
    const off = app.on('config', () => read());

    async function read() {
        state.reads += 1;
        const reading = state.reads;
        const answer = await call('GET', LIST);
        if (state.gone || reading !== state.reads) {
            return;
        }
        if (!answer.ok) {
            state.failure = answer.error;
            state.list = null;
            draw();
            return;
        }
        state.failure = null;
        state.list = answer.data || {};
        const environments = Array.isArray(state.list.environments) ? state.list.environments : [];
        const views = await Promise.all(environments.map(each => call('GET', LIST + '/' + encode(each.id) + '/settings')));
        if (state.gone || reading !== state.reads) {
            return;
        }
        state.settings = new Map();
        environments.forEach((each, index) => {
            state.settings.set(each.id, views[index].ok && views[index].data ? views[index].data : null);
        });
        draw();
        openFromRoute();
    }

    function draw() {
        clear(problems);
        if (state.failure) {
            fill(grid, errorPanel(state.failure));
            return;
        }
        if (!state.list) {
            fill(grid, el('p', {class: 'muted'}, 'Reading the environments.'));
            return;
        }
        const listed = Array.isArray(state.list.problems) ? state.list.problems : [];
        if (listed.length > 0) {
            fill(problems, el('ul', {class: 'notes'}, listed.map(text => el('li', null, text))));
        }
        const environments = Array.isArray(state.list.environments) ? state.list.environments : [];
        if (environments.length === 0) {
            fill(grid, el('p', {class: 'muted config-grid-line'}, 'No environment is stored yet. Start with New environment.'));
            return;
        }
        fill(grid, environments.map(each => card(each, state.settings.get(each.id))));
    }

    function card(environment, settings) {
        const access = (app.mode() || {}).writeAccess || {};
        const facts = [];
        if (environment.active) {
            facts.push(chip('Active', {tone: 'neutral'}));
        } else {
            facts.push(button('Make active', event => activate(environment, event.target), {small: true,
                title: 'Make ' + environment.name + ' the active environment'}));
        }
        if (environment.kind === 'prod') {
            const unlocked = environment.active && access.production === true && access.writesAllowed === true;
            facts.push(chip(unlocked ? 'Writes UNLOCKED for this browser session' : 'Writes locked', {tone: 'blocked'}));
        }
        facts.push(el('span', null, variableCount(environment.variables)));
        const lines = [];
        if (settings && settings.signIn && settings.signIn.inForceWords) {
            lines.push(el('p', {class: 'config-card-line'}, 'Signs in: ', settings.signIn.inForceWords));
        }
        if (settings && settings.memberIdRule && settings.memberIdRule.rule) {
            lines.push(el('p', {class: 'config-card-line'}, 'member ID rule ', ruleWord(settings.memberIdRule.rule)));
        }
        for (const shape of settings && Array.isArray(settings.shapes) ? settings.shapes : []) {
            if (shape.from && shape.from !== 'default') {
                lines.push(el('p', {class: 'config-card-line'}, shapeLine(shape)));
            }
        }
        if (settings && settings.problem) {
            lines.push(el('p', {class: 'config-card-line config-warning'}, settings.problem));
        }
        const addresses = settings && Array.isArray(settings.addresses) ? settings.addresses : [];
        return el('article', {class: ['env-card', environment.active ? 'env-card--active' : null]},
            el('div', {class: 'env-card__head'},
                el('h3', {class: 'env-card__name', title: environment.name}, environment.name),
                badge(environment.kind, environment.kind)),
            el('p', {class: 'env-card__facts'}, facts),
            lines,
            addresses.length > 0 ? el('div', {class: 'env-card__ops'}, groupedChips(addresses.map(operationChip))) : null,
            el('div', {class: 'env-card__foot'},
                button('Edit', () => openEditor(environment, null), {small: true, title: 'Edit ' + environment.name}),
                button('Remove', () => confirmRemoval(environment), {small: true, title: 'Remove ' + environment.name})));
    }

    async function activate(environment, control) {
        if (control) {
            control.disabled = true;
        }
        const answer = await call('POST', LIST + '/' + encode(environment.id) + '/activate');
        if (state.gone) {
            return;
        }
        if (!answer.ok) {
            fill(status, errorPanel(answer.error));
            if (control) {
                control.disabled = false;
            }
            return;
        }
        const warnings = Array.isArray(answer.data.addressWarnings) ? answer.data.addressWarnings : [];
        fill(status, said('"' + named(environment.name) + '" is the active environment now.', warnings));
        await read();
    }

    /* ---------- removing one ---------- */

    function confirmRemoval(environment, after) {
        const line = el('div');
        modal({
            title: 'Remove ' + named(environment.name),
            body: [
                environment.kind === 'prod' ? el('p', {class: 'config-warning'}, 'Its addresses stay remembered as production after it is removed.') : null,
                el('p', null, 'Remove ' + named(environment.name) + '? Runs already recorded keep their lines.'),
                line
            ],
            footer: dialog => [
                button('Keep it', () => dialog.close(), {}),
                el('span', {class: 'push'}),
                button('Remove environment', async event => {
                    event.target.disabled = true;
                    const answer = await call('DELETE', LIST + '/' + encode(environment.id));
                    if (!answer.ok) {
                        fill(line, errorPanel(answer.error));
                        event.target.disabled = false;
                        return;
                    }
                    dialog.close();
                    if (typeof after === 'function') {
                        after();
                    }
                    const warnings = Array.isArray(answer.data.warnings) ? answer.data.warnings : [];
                    fill(status, said('Removed "' + named(answer.data.removed || environment.name) + '".', warnings));
                    await read();
                }, {remove: true})
            ]
        });
    }

    /* ---------- the form ---------- */

    function openEditor(environment, sectionName) {
        if (state.editor) {
            return;
        }
        const settings = environment ? state.settings.get(environment.id) || null : null;
        state.editor = createEditor({
            environment,
            settings,
            section: sectionName,
            onSaved: (saved, warnings) => {
                fill(status, said('Saved "' + named(saved.name) + '".', warnings));
                read();
            },
            onRemove: () => confirmRemoval(environment, () => {
                if (state.editor) {
                    state.editor.close();
                }
            }),
            onClosed: () => {
                state.editor = null;
                // The address named the fold the form was opened at; with the form closed it names the page again.
                if (state.route.length > 1 && window.location.hash.startsWith('#configuration/environments/')) {
                    window.history.replaceState(null, '', '#configuration/environments');
                    state.route = ['environments'];
                }
            }
        });
    }

    /** #configuration/environments/<id>/<section> opens the form of that environment at that fold. */
    function openFromRoute() {
        const [, id, sectionName] = state.route;
        if (!id || state.editor || !state.list) {
            return;
        }
        const environments = Array.isArray(state.list.environments) ? state.list.environments : [];
        const environment = environments.find(each => each.id === id || each.name === id);
        if (!environment) {
            fill(status, el('p', {class: 'muted'}, 'No stored environment is called ' + named(id) + '.'));
            return;
        }
        openEditor(environment, SECTIONS.includes(sectionName) ? sectionName : null);
    }

    return {
        show(route) {
            state.route = Array.isArray(route) ? route.slice() : [];
            if (state.list) {
                openFromRoute();
            } else {
                draw();
                read();
            }
        },
        unmount() {
            state.gone = true;
            off();
            if (state.editor) {
                state.editor.close();
            }
        }
    };
}

/**
 * The form of one environment, or of a new one when environment is null. Opened as a modal with
 * the focus on its first field, or on the first field of the fold the address asked for.
 */
export function createEditor(options) {
    const {environment, section: wanted, onSaved, onRemove, onClosed} = options;
    let settings = options.settings;
    const parts = {fields: new Map(), folds: new Map()};
    let draft = null;              // the last answer of the draft, while its files stay chosen
    let exportsRead = {};          // slot -> {fileName, text} read when Fill was pressed
    let secretReferences = {};     // input name -> a reference the draft filled for it
    const body = el('div', {class: 'config-form'});
    const banner = el('div', {class: 'config-form__banner', role: 'status', 'aria-live': 'polite'});
    let titleNode = null;
    let dialog = null;
    let saveButton = null;

    function isNew() {
        return !settings && !environment;
    }

    function title() {
        const name = settings ? settings.name : environment ? environment.name : null;
        return name ? 'Edit environment: ' + named(name) : 'New environment';
    }

    /* ---------- the parts, each drawn from its server field ---------- */

    function register(name, wrapper, foldName) {
        parts.fields.set(name, {wrapper, fold: foldName || null});
        return wrapper;
    }

    const files = {
        environmentExport: el('input', {type: 'file', class: 'config-file', accept: '.json,application/json'}),
        collectionExport: el('input', {type: 'file', class: 'config-file', accept: '.json,application/json'})
    };
    const fileLines = el('div', {class: 'config-file-lines'});
    const fillButton = button('Fill the form from these files', () => runDraft());

    const inputs = {
        name: el('input', {class: 'input', type: 'text', autocomplete: 'off', spellcheck: false}),
        kind: el('select', {class: 'select'}),
        acknowledge: el('input', {type: 'checkbox'}),
        memberIdRule: el('select', {class: 'select'}),
        method: el('select', {class: 'select'}),
        tokenUrl: el('input', {class: 'input mono', type: 'text', autocomplete: 'off', spellcheck: false}),
        clientId: el('input', {class: 'input mono', type: 'text', autocomplete: 'off', spellcheck: false}),
        clientSecret: secretInput(),
        removeClientSecret: el('input', {type: 'checkbox'}),
        scope: el('input', {class: 'input mono', type: 'text', autocomplete: 'off', spellcheck: false, placeholder: 'not set'}),
        clientAuthentication: el('select', {class: 'select'}),
        bearerToken: secretInput(),
        removeBearerToken: el('input', {type: 'checkbox'}),
        connectMs: el('input', {class: 'input mono', type: 'number', min: 100, max: 600000, step: 1, inputMode: 'numeric'}),
        readMs: el('input', {class: 'input mono', type: 'number', min: 100, max: 600000, step: 1, inputMode: 'numeric'})
    };
    const shapeInputs = new Map();    // key -> select
    const valueInputs = new Map();    // name -> input of a known value
    const otherValues = [];           // {nameInput or name, valueInput, removed}
    const addressInputs = new Map();  // operation -> input
    const ackField = el('div', {class: 'field field--full config-ack', hidden: true},
        el('label', {class: 'config-check'}, inputs.acknowledge, el('span', null, 'This environment is not production')),
        el('p', {class: 'field__error', hidden: true}));
    const signInFields = el('div', {class: 'form-grid'});

    for (const kind of [{value: '', words: 'Choose a kind'}].concat(KINDS.map(each => ({value: each, words: each})))) {
        inputs.kind.append(el('option', {value: kind.value}, kind.words));
    }
    for (const method of METHODS) {
        inputs.method.append(el('option', {value: method.value}, method.words));
    }
    for (const way of CLIENT_AUTHENTICATION) {
        inputs.clientAuthentication.append(el('option', {value: way.value}, way.words));
    }
    inputs.method.addEventListener('change', () => drawSignInFields());
    for (const slot of Object.keys(files)) {
        files[slot].addEventListener('change', () => {
            // A new choice makes the last draft stale: what was read is read again at the next press.
            delete exportsRead[slot];
            fixName();
        });
    }

    function secretInput() {
        // A password field, never given a value from the server, never filled in by the browser.
        return el('input', {class: 'input', type: 'password', autocomplete: 'new-password', spellcheck: false, placeholder: 'blank keeps stored'});
    }

    /** The name is the export's once an export is chosen, and the stored one of an existing environment. */
    function fixName() {
        const chosen = Boolean(files.environmentExport.files && files.environmentExport.files.length > 0 && files.environmentExport.value);
        const existing = !isNew();
        inputs.name.readOnly = existing || chosen;
        const hint = parts.fields.get('name');
        if (hint) {
            const line = hint.wrapper.querySelector('.field__hint');
            if (line) {
                line.textContent = existing ? 'The name of a stored environment does not change.' : chosen ? 'Taken from the environment export.' : '';
                line.hidden = !existing && !chosen;
            }
        }
    }

    function drawAll() {
        const view = settings;
        const existing = !isNew();
        const environmentName = view ? view.name : environment ? environment.name : '';
        inputs.name.value = environmentName || '';
        inputs.kind.value = view && view.kind ? view.kind : environment && environment.kind ? environment.kind : '';
        if (titleNode) {
            // The title follows the form: a new environment, once saved, is edited under its own name.
            fill(titleNode, title());
        }

        const sections = [];
        sections.push(el('p', {class: 'muted'}, 'Saved in the configuration store and used at once. A blank secret keeps the stored value.'));
        if (view && view.problem) {
            sections.push(el('p', {class: 'config-warning'}, view.problem));
        }
        sections.push(banner);
        sections.push(el('div', {class: 'importer'},
            el('p', {class: 'form-section__title'}, 'Start from Postman exports (optional)'),
            el('div', {class: 'importer__row'},
                chooserInField(field({label: 'Environment export', input: files.environmentExport}), files.environmentExport, 'Environment export'),
                chooserInField(field({label: 'Collection export', input: files.collectionExport}), files.collectionExport, 'Collection export'),
                fillButton),
            el('p', {class: 'field__hint'}, 'The fields below are filled for review. Nothing is saved until Save environment.'),
            fileLines));

        const profileWords = view && view.profile && view.profile.words ? view.profile.words
            : PROFILE_WORDS[(app.mode() || {}).mode] || null;
        const first = [
            register('name', field({label: 'Name', input: inputs.name, hint: ' '})),
            register('kind', field({label: 'Kind', input: inputs.kind})),
            register('acknowledgeNotProd', ackField)
        ];
        if (profileWords) {
            first.push(el('div', {class: 'field'}, el('span', {class: 'field__label'}, 'Mock or live'), el('p', {class: 'config-fact'}, profileWords)));
        }
        if (view && view.memberIdRule && Array.isArray(view.memberIdRule.options)) {
            clear(inputs.memberIdRule);
            for (const option of view.memberIdRule.options) {
                inputs.memberIdRule.append(el('option', {value: option.value}, option.words || option.value));
            }
            inputs.memberIdRule.value = view.memberIdRule.rule || '';
            first.push(register('memberIdRule', field({label: 'Member ID rule', input: inputs.memberIdRule})));
        }
        sections.push(el('div', {class: 'form-section'}, el('h3', {class: 'form-section__title'}, 'Environment'), el('div', {class: 'form-grid'}, first)));

        // Sign-in: always offered, since a new environment needs it before its first save.
        const signIn = view && view.signIn ? view.signIn : null;
        inputs.method.value = signIn && signIn.method ? signIn.method : '';
        inputs.tokenUrl.value = signIn && signIn.tokenUrl ? signIn.tokenUrl : '';
        inputs.clientId.value = signIn && signIn.clientId ? signIn.clientId : '';
        inputs.scope.value = signIn && signIn.scope ? signIn.scope : '';
        inputs.clientAuthentication.value = signIn && signIn.clientAuthentication ? signIn.clientAuthentication : 'BASIC';
        inputs.clientSecret.value = '';
        inputs.bearerToken.value = '';
        inputs.removeClientSecret.checked = false;
        inputs.removeBearerToken.checked = false;
        const signInSection = el('div', {class: 'form-section', 'data-section': 'sign-in'},
            el('h3', {class: 'form-section__title'}, 'Sign-in'),
            el('div', {class: 'form-grid'}, register('signIn.method', field({label: 'Method', input: inputs.method}))),
            signInFields);
        parts.folds.set('sign-in', signInSection);
        sections.push(signInSection);
        drawSignInFields();

        if (view && view.timeouts) {
            sections.push(timeoutsFold(view.timeouts));
        }
        if (view && Array.isArray(view.shapes) && view.shapes.length > 0) {
            sections.push(shapesFold(view.shapes));
        }
        if (view && Array.isArray(view.values)) {
            sections.push(valuesFold(view.values));
        }
        if (view && Array.isArray(view.addresses) && view.addresses.length > 0) {
            sections.push(addressesSection(view.addresses));
        }
        const variables = draft && Array.isArray(draft.variables) ? draft.variables
            : view && view.export && Array.isArray(view.export.variables) ? view.export.variables
                : environment && Array.isArray(environment.variables) ? environment.variables : null;
        if (variables) {
            sections.push(variablesFold(variables));
        }
        if (isNew()) {
            sections.push(el('p', {class: 'muted small'}, 'The member ID rule, request shapes, values, timeouts and addresses can be set once the environment is saved.'));
        }
        fill(body, sections);
        fixName();
        if (existing) {
            inputs.name.readOnly = true;
        }
    }

    function drawSignInFields() {
        const method = inputs.method.value;
        const stored = settings && settings.signIn ? settings.signIn : {};
        const rows = [];
        if (method === 'CLIENT_CREDENTIALS') {
            rows.push(register('signIn.tokenUrl', withCandidates(field({label: 'Token url', input: inputs.tokenUrl}), 'tokenUrl')));
            rows.push(register('signIn.clientId', withCandidates(field({label: 'Client id', input: inputs.clientId}), 'clientId')));
            rows.push(register('signIn.clientSecret', withCandidates(secretField('Client secret', inputs.clientSecret, inputs.removeClientSecret,
                'Remove the stored secret', stored.clientSecret, 'clientSecret'), 'clientSecret')));
            rows.push(register('signIn.scope', withCandidates(field({label: 'Scope', input: inputs.scope}), 'scope')));
            rows.push(register('signIn.clientAuthentication', field({label: 'Client authentication', input: inputs.clientAuthentication})));
        } else if (method === 'BEARER_TOKEN') {
            rows.push(register('signIn.bearerToken', secretField('Bearer token', inputs.bearerToken, inputs.removeBearerToken,
                'Remove the stored token', stored.bearerToken, 'bearerToken')));
        }
        fill(signInFields, rows);
        signInFields.hidden = rows.length === 0;
    }

    /**
     * Under a field the files could not fill because several variables could be it (D-305): one
     * button per variable, which fills the field with its reference. Nothing is filled until one is
     * pressed; a secret is filled as the reference, as the draft fills one.
     */
    function withCandidates(wrapper, name) {
        const all = draft && draft.signIn && Array.isArray(draft.signIn.candidates) ? draft.signIn.candidates : [];
        const found = all.find(each => each && each.field === name && Array.isArray(each.references));
        if (!found || found.references.length === 0) {
            return wrapper;
        }
        const choices = found.references.map(reference => button(String(reference), () => {
            if (name === 'clientSecret') {
                secretReferences.clientSecret = String(reference);
                inputs.clientSecret.value = '';
                drawSignInFields();
            } else {
                inputs[name].value = String(reference);
            }
        }, {small: true}));
        wrapper.append(el('div', {class: 'config-candidates'}, el('span', {class: 'field__hint'}, 'Choose one:'), ' ',
            choices.map((choice, index) => [index > 0 ? ' ' : null, choice])));
        return wrapper;
    }

    function secretField(label, input, removeBox, removeWords, stored, name) {
        const lines = [];
        const reference = secretReferences[name];
        if (reference) {
            lines.push('Filled from the export as ' + reference + '. Type a value to use instead.');
        } else if (stored && stored.set === true) {
            lines.push(stored.reference ? 'Stored: ' + stored.reference : 'Stored');
        } else {
            lines.push('Not stored. Or type {{env:PAVE_NAME}} to read it from this machine.');
        }
        const wrapper = field({label, input, hint: lines.join(' ')});
        if (stored && stored.set === true) {
            wrapper.append(el('label', {class: 'config-check small'}, removeBox, el('span', null, removeWords)));
            if (!reference) {
                storedSecretClosed(wrapper, input);
            }
        }
        return wrapper;
    }

    function timeoutsFold(timeouts) {
        const inForce = timeouts.inForce || {};
        const rows = [];
        for (const [name, label, stated, force, from] of [
            ['connectMs', 'Connect timeout (ms)', timeouts.connectMs, inForce.connectMs, inForce.connectFrom],
            ['readMs', 'Wait for a response up to (ms)', timeouts.readMs, inForce.readMs, inForce.readFrom]]) {
            const input = inputs[name];
            input.value = stated === null || stated === undefined ? '' : String(stated);
            input.placeholder = force === null || force === undefined ? '' : String(force) + (from ? ', from ' + from : '');
            const wrapper = field({label, input});
            if (stated !== null && stated !== undefined) {
                const clearIt = button("Use application.yml's value", () => {
                    input.value = '';
                    clearIt.hidden = true;
                    input.focus();
                }, {small: true, quiet: true});
                wrapper.append(clearIt);
            }
            rows.push(register('timeouts.' + name, wrapper, 'timeouts'));
        }
        const stated = timeouts.connectMs !== null && timeouts.connectMs !== undefined || timeouts.readMs !== null && timeouts.readMs !== undefined;
        return keepFold('timeouts', fold({summary: 'Timeouts', hint: stated ? 'set here' : null, open: false,
            body: el('div', {class: 'form-grid'}, rows)}));
    }

    function shapesFold(shapes) {
        const rows = [];
        let differs = false;
        for (const shape of shapes) {
            const select = el('select', {class: 'select'});
            for (const option of Array.isArray(shape.options) ? shape.options : []) {
                select.append(el('option', {value: option.value}, option.words || option.value));
            }
            select.value = shape.value || '';
            shapeInputs.set(shape.key, select);
            const source = el('p', {class: 'field__hint config-source'}, shape.from && shape.from !== 'default' ? 'from ' + shape.from : '');
            source.hidden = !(shape.from && shape.from !== 'default');
            const wrapper = field({label: shape.label || shape.key, input: select, hint: shape.why || null});
            wrapper.append(source);
            rows.push(register('shapes.' + shape.key, wrapper, 'shapes'));
            if (shape.from && shape.from !== 'default') {
                differs = true;
            }
        }
        return keepFold('shapes', fold({summary: 'Request shapes', open: differs, body: el('div', {class: 'form-grid'}, rows)}));
    }

    function valuesFold(values) {
        const known = el('div', {class: 'config-values'});
        const others = el('div', {class: 'config-values'});
        let typed = false;
        for (const value of values) {
            if (value.typed !== null && value.typed !== undefined && value.typed !== '') {
                typed = true;
            }
            if (!value.why && !value.label) {
                others.append(otherValueRow(value.name, value.typed || ''));
                continue;
            }
            const input = el('input', {class: 'input mono', type: 'text', autocomplete: 'off', spellcheck: false,
                placeholder: 'Type a value to use instead'});
            input.value = value.typed === null || value.typed === undefined ? '' : String(value.typed);
            valueInputs.set(value.name, input);
            const wrapper = overField(value.label || value.name, input, value.inForce || {}, value.why || null);
            const exported = exportVariable(value.name);
            if (exported) {
                wrapper.append(el('p', {class: 'field__hint'}, 'from the export: ', el('span', {class: 'mono'}, exported)));
            }
            known.append(register('values.' + value.name, wrapper, 'values'));
        }
        const add = button('Add a value', () => {
            const row = otherValueRow(null, '');
            others.append(row);
            const first = row.querySelector('input');
            if (first) {
                first.focus();
            }
        }, {small: true});
        add.classList.add('config-add');
        return keepFold('values', fold({summary: 'Values', open: typed, body: [known, el('p', {class: 'form-section__title'}, 'Another value'), others, add]}));
    }

    function otherValueRow(name, value) {
        const entry = {name, removed: false};
        entry.nameInput = name === null ? el('input', {class: 'input mono', type: 'text', autocomplete: 'off', spellcheck: false}) : null;
        entry.valueInput = el('input', {class: 'input mono', type: 'text', autocomplete: 'off', spellcheck: false});
        entry.valueInput.value = value;
        otherValues.push(entry);
        const nameField = entry.nameInput ? field({label: 'Name', input: entry.nameInput})
            : el('div', {class: 'field'}, el('span', {class: 'field__label'}, 'Name'), el('p', {class: 'mono config-fact'}, name));
        const valueField = field({label: 'Value', input: entry.valueInput});
        const row = el('div', {class: 'config-value-row'}, nameField, valueField);
        row.append(button('Remove this value', () => {
            entry.removed = true;
            row.hidden = true;
        }, {small: true, quiet: true}));
        if (name !== null) {
            register('values.' + name, valueField, 'values');
        }
        entry.row = row;
        entry.valueField = valueField;
        return row;
    }

    function addressesSection(addresses) {
        const rows = [];
        for (const address of addresses) {
            const input = el('input', {class: 'input mono', type: 'text', autocomplete: 'off', spellcheck: false,
                placeholder: 'Type an address to use instead'});
            input.value = address.typed === null || address.typed === undefined ? '' : String(address.typed);
            addressInputs.set(address.operation, input);
            const wrapper = overField(address.label || address.operation, input, address.inForce || {}, null);
            rows.push(register('addresses.' + address.operation, wrapper, 'addresses'));
        }
        const block = el('div', {class: 'form-section', 'data-section': 'addresses'},
            el('h3', {class: 'form-section__title'}, 'Addresses'), el('div', {class: 'config-addresses'}, rows));
        parts.folds.set('addresses', block);
        return block;
    }

    function variablesFold(variables) {
        const rows = variables.map(variable => el('tr', null,
            el('th', {scope: 'row'}, el('span', {class: 'mono'}, variable.name)),
            el('td', null, variable.secret ? chip('masked', {tone: 'neutral'})
                : variable.display !== undefined && variable.display !== null ? el('span', {class: 'mono'}, String(variable.display))
                    : chip('blank', {dashed: true})),
            el('td', null, variable.enabled === false ? 'switched off' : variable.used === true ? 'used' : variable.used === false ? 'not used' : '')));
        return fold({summary: 'Variables of the Postman environment (' + variables.length + ')',
            body: el('table', {class: 'ops-table'}, el('tbody', null, rows))});
    }

    function keepFold(name, details) {
        details.dataset.section = name;
        parts.folds.set(name, details);
        return details;
    }

    function exportVariable(name) {
        if (!draft || !Array.isArray(draft.variables)) {
            return null;
        }
        const found = draft.variables.find(each => each.name === name);
        if (!found || found.secret) {
            return null;
        }
        return found.display === undefined || found.display === null ? null : String(found.display);
    }

    /* ---------- the draft: files read in the page, the form filled for review ---------- */

    async function readChosen(slot) {
        const input = files[slot];
        // A browser empties the input's value when its file is let go; the value is what says a file is chosen.
        if (!input.files || input.files.length === 0 || !input.value) {
            return {none: true};
        }
        if (exportsRead[slot]) {
            return {ok: true, value: exportsRead[slot]};
        }
        const read = await readText(input);
        // files.js lets a refused input go; the chooser then says no file is chosen.
        shownFile(input);
        if (!read.ok) {
            fixName();
            return {ok: false, line: read.line};
        }
        exportsRead[slot] = {fileName: read.fileName, text: read.text};
        return {ok: true, value: exportsRead[slot]};
    }

    async function runDraft() {
        clear(fileLines);
        const request = {};
        const lines = [];
        for (const [slot, words] of [['environmentExport', 'Environment export'], ['collectionExport', 'Collection export']]) {
            const read = await readChosen(slot);
            if (read.ok) {
                request[slot] = read.value;
            } else if (!read.none) {
                lines.push(el('p', {class: 'config-file-line'}, words + ': not read: ' + read.line));
            }
        }
        if (lines.length > 0) {
            lines.push(filesChangedNote());
        }
        if (!request.environmentExport && !request.collectionExport) {
            fill(fileLines, lines.length > 0 ? lines : el('p', {class: 'config-file-line'}, 'Choose an environment export, a collection export or both.'));
            return;
        }
        if (environment) {
            request.forId = environment.id;
        }
        fillButton.disabled = true;
        const answer = await call('POST', DRAFT, {body: request});
        fillButton.disabled = false;
        if (!answer.ok) {
            fill(fileLines, lines, errorPanel(answer.error));
            return;
        }
        draft = answer.data || {};
        applyDraft(lines);
    }

    function applyDraft(lines) {
        for (const file of Array.isArray(draft.files) ? draft.files : []) {
            lines.push(el('div', {class: 'config-file-line'},
                el('p', null, file.fileName ? el('span', {class: 'mono'}, named(file.fileName)) : null, file.fileName ? ' ' : null, file.line || ''),
                file.read === false ? faultReport(file.reference, file.cause) : null,
                file.read === false ? filesChangedNote() : null));
        }
        if (draft.nameTaken === true && draft.name) {
            lines.push(el('p', {class: 'config-warning'}, 'An environment named "' + named(draft.name)
                + '" is stored already. Open it with Edit to replace its export.'));
        }
        for (const warning of Array.isArray(draft.warnings) ? draft.warnings : []) {
            lines.push(el('p', {class: 'config-file-line'}, warning));
        }
        const operations = Array.isArray(draft.operations) ? draft.operations : [];
        if (operations.length > 0) {
            lines.push(el('div', {class: 'env-card__ops'}, groupedChips(operations.map(operation => ({
                words: operation.short || operation.shortLabel || operation.label || operation.operation,
                title: operation.requestName || null
            })))));
        }
        if (draft.signIn && draft.signIn.from) {
            const filledFields = Array.isArray(draft.signIn.fields) ? draft.signIn.fields.map(String) : [];
            lines.push(el('p', {class: 'config-file-line'}, 'Sign-in filled from ' + draft.signIn.from
                + (filledFields.length > 0 ? ': ' + listWords(filledFields) : '') + '.'));
        }
        // Fill what the files hold, then draw the form again around it, so every hint says where a value came from.
        const signIn = draft.signIn || null;
        secretReferences = {};
        const keep = {
            kind: inputs.kind.value,
            method: signIn && signIn.method ? signIn.method : inputs.method.value,
            tokenUrl: signIn && signIn.tokenUrl ? signIn.tokenUrl : inputs.tokenUrl.value,
            clientId: signIn && signIn.clientId ? signIn.clientId : inputs.clientId.value,
            scope: signIn && signIn.scope ? signIn.scope : inputs.scope.value,
            clientAuthentication: signIn && signIn.clientAuthentication ? signIn.clientAuthentication : inputs.clientAuthentication.value
        };
        if (signIn) {
            for (const name of ['clientSecret', 'bearerToken']) {
                const secret = signIn[name];
                if (secret && secret.reference) {
                    secretReferences[name] = String(secret.reference);
                }
            }
        }
        const shapes = new Map();
        for (const shape of Array.isArray(draft.shapes) ? draft.shapes : []) {
            shapes.set(shape.key, shape);
        }
        const typedValues = collectValues();
        const typedAddresses = collectAddresses();
        const typedTimeouts = {connectMs: inputs.connectMs.value, readMs: inputs.readMs.value};
        resetParts();
        drawAll();
        if (draft.name && isNew() && files.environmentExport.value) {
            inputs.name.value = draft.name;
        }
        inputs.kind.value = keep.kind;
        inputs.method.value = keep.method;
        drawSignInFields();
        inputs.tokenUrl.value = keep.tokenUrl;
        inputs.clientId.value = keep.clientId;
        inputs.scope.value = keep.scope;
        inputs.clientAuthentication.value = keep.clientAuthentication;
        inputs.connectMs.value = typedTimeouts.connectMs;
        inputs.readMs.value = typedTimeouts.readMs;
        for (const [name, value] of Object.entries(typedValues)) {
            if (valueInputs.has(name)) {
                valueInputs.get(name).value = value;
            }
        }
        for (const [operation, value] of Object.entries(typedAddresses)) {
            if (addressInputs.has(operation)) {
                addressInputs.get(operation).value = value;
            }
        }
        for (const [key, shape] of shapes) {
            const select = shapeInputs.get(key);
            if (select && shape.value) {
                select.value = shape.value;
                const wrapper = parts.fields.get('shapes.' + key);
                const source = wrapper ? wrapper.wrapper.querySelector('.config-source') : null;
                if (source && shape.from) {
                    source.textContent = 'from ' + shape.from;
                    source.hidden = false;
                    const holder = parts.folds.get('shapes');
                    if (holder) {
                        holder.open = true;
                    }
                }
            }
        }
        fill(fileLines, lines);
    }

    function resetParts() {
        parts.fields.clear();
        parts.folds.clear();
        shapeInputs.clear();
        valueInputs.clear();
        otherValues.length = 0;
        addressInputs.clear();
    }

    /* ---------- saving ---------- */

    function collectValues() {
        const values = {};
        for (const [name, input] of valueInputs) {
            values[name] = input.value.trim();
        }
        for (const entry of otherValues) {
            const name = entry.name !== null ? entry.name : entry.nameInput.value.trim();
            if (!name) {
                continue;
            }
            values[name] = entry.removed ? '' : entry.valueInput.value.trim();
        }
        return values;
    }

    function collectAddresses() {
        const addresses = {};
        for (const [operation, input] of addressInputs) {
            addresses[operation] = input.value.trim();
        }
        return addresses;
    }

    function number(input) {
        const text = input.value.trim();
        if (text === '') {
            return null;
        }
        const value = Number(text);
        // A value that is not a whole number goes as typed, so the server names it at its field.
        return Number.isInteger(value) ? value : text;
    }

    /** The body of POST /api/config/environments/settings, as the form stands (plan 5.4). */
    function input() {
        const body = {};
        if (!isNew()) {
            body.id = settings ? settings.id : environment.id;
            body.name = settings ? settings.name : environment.name;
        } else if (!inputs.name.readOnly || inputs.name.value.trim() !== '') {
            const name = inputs.name.value.trim();
            if (name !== '') {
                body.name = name;
            }
        }
        if (inputs.kind.value) {
            body.kind = inputs.kind.value;
        }
        if (!ackField.hidden) {
            body.acknowledgeNotProd = inputs.acknowledge.checked;
        }
        for (const slot of Object.keys(files)) {
            if (exportsRead[slot]) {
                body[slot] = exportsRead[slot];
            }
        }
        const method = inputs.method.value;
        const signIn = {method: method === '' ? null : method};
        if (method === 'CLIENT_CREDENTIALS') {
            signIn.tokenUrl = inputs.tokenUrl.value.trim();
            signIn.clientId = inputs.clientId.value.trim();
            signIn.scope = inputs.scope.value.trim();
            signIn.clientAuthentication = inputs.clientAuthentication.value || 'BASIC';
            signIn.clientSecret = inputs.clientSecret.value !== '' ? inputs.clientSecret.value : secretReferences.clientSecret || '';
            signIn.removeClientSecret = inputs.removeClientSecret.checked;
        } else if (method === 'BEARER_TOKEN') {
            signIn.bearerToken = inputs.bearerToken.value !== '' ? inputs.bearerToken.value : secretReferences.bearerToken || '';
            signIn.removeBearerToken = inputs.removeBearerToken.checked;
        }
        body.signIn = signIn;
        if (settings && settings.timeouts) {
            body.timeouts = {connectMs: number(inputs.connectMs), readMs: number(inputs.readMs)};
        }
        if (addressInputs.size > 0) {
            body.addresses = collectAddresses();
        }
        // The rule and the shapes go only when the tester or the files changed them: sending the value
        // shown would turn a default, or the evidence of the collection's sample, into a stated value.
        if (settings && settings.memberIdRule && inputs.memberIdRule.value && inputs.memberIdRule.value !== settings.memberIdRule.rule) {
            body.memberIdRule = inputs.memberIdRule.value;
        }
        const shapes = {};
        for (const [key, select] of shapeInputs) {
            const shown = (Array.isArray(settings && settings.shapes) ? settings.shapes : []).find(each => each.key === key);
            if (!shown || select.value !== shown.value) {
                shapes[key] = select.value;
            }
        }
        if (Object.keys(shapes).length > 0) {
            body.shapes = shapes;
        }
        if (settings && Array.isArray(settings.values)) {
            body.values = collectValues();
        }
        return body;
    }

    async function save() {
        clear(banner);
        for (const {wrapper} of parts.fields.values()) {
            setFieldError(wrapper, '');
        }
        const ackError = ackField.querySelector('.field__error');
        ackError.hidden = true;
        // The file inputs are read now when a file was chosen and not yet read by Fill.
        for (const slot of Object.keys(files)) {
            const read = await readChosen(slot);
            if (read.ok === false) {
                fill(banner, el('p', {class: 'config-warning'}, read.line), filesChangedNote());
                bannerInView();
                return;
            }
        }
        saveButton.disabled = true;
        const answer = await call('POST', SAVE, {body: input()});
        saveButton.disabled = false;
        if (!answer.ok) {
            refused(answer.error);
            return;
        }
        const data = answer.data || {};
        const warnings = Array.isArray(data.warnings) ? data.warnings : [];
        const saved = data.settings || data.environment || {};
        if (isNew() && data.settings) {
            // A new environment stays open as its own form, so the parts a new one could not show can be set now.
            settings = data.settings;
            draft = null;
            exportsRead = {};
            secretReferences = {};
            files.environmentExport.value = '';
            files.collectionExport.value = '';
            shownFile(files.environmentExport);
            shownFile(files.collectionExport);
            clear(fileLines);
            resetParts();
            drawAll();
            fill(banner, said('Saved "' + named(saved.name || '') + '". Its other settings can be set now.', warnings));
            if (typeof onSaved === 'function') {
                onSaved(saved, []);
            }
            return;
        }
        dialog.close();
        if (typeof onSaved === 'function') {
            onSaved(saved.name ? saved : {name: settings ? settings.name : environment ? environment.name : ''}, warnings);
        }
    }

    /** A refusal: each field error at its field, its fold opened; the title and what has no field above the form. */
    function refused(error) {
        const placed = [];
        for (const each of Array.isArray(error.fieldErrors) ? error.fieldErrors : []) {
            if (each.field === 'acknowledgeNotProd') {
                ackField.hidden = false;
                const line = ackField.querySelector('.field__error');
                line.textContent = each.message;
                line.hidden = false;
                placed.push(each.field);
                continue;
            }
            if (each.field === 'environmentExport' || each.field === 'collectionExport') {
                files[each.field].value = '';
                shownFile(files[each.field]);
                delete exportsRead[each.field];
                fixName();
            }
            const part = parts.fields.get(each.field) || fileField(each.field);
            if (!part) {
                continue;
            }
            setFieldError(part.wrapper, each.message);
            if (typeof part.wrapper.openBox === 'function') {
                part.wrapper.openBox();
            }
            openFold(part.fold);
            placed.push(each.field);
        }
        // The title says what was refused and why (which address, which production environment):
        // a field error says only where, so the panel is drawn even when every field error was placed.
        fill(banner, errorPanel(error, {fieldsPlaced: placed, labelOf: labelsOf(name => {
            const part = parts.fields.get(name) || fileField(name);
            return part ? part.wrapper : null;
        })}));
        bannerInView();
    }

    /**
     * The banner is what says why a save was refused. A focus moved to a field further down
     * scrolled it out of the dialog body (look-guardrail-8), so the body is scrolled to it and its
     * first line takes the focus; Tab goes on from there into the form, where each field the
     * server named is marked.
     */
    function bannerInView() {
        if (typeof banner.scrollIntoView === 'function') {
            banner.scrollIntoView({block: 'start'});
        }
        const first = banner.querySelector('p');
        if (first) {
            first.setAttribute('tabindex', '-1');
            first.focus({preventScroll: true});
        }
    }

    function fileField(slot) {
        const input = files[slot];
        if (!input) {
            return null;
        }
        const wrapper = input.closest('.field');
        return wrapper ? {wrapper, fold: null} : null;
    }

    function openFold(name) {
        const holder = name ? parts.folds.get(name) : null;
        if (holder && holder.localName === 'details') {
            holder.open = true;
        }
    }

    /* ---------- the modal ---------- */

    drawAll();
    dialog = modal({
        title: '',
        body,
        footer: shown => {
            saveButton = button('Save environment', () => save(), {primary: true});
            return [
                isNew() ? null : button('Remove environment', () => {
                    if (typeof onRemove === 'function') {
                        onRemove();
                    }
                }, {remove: true}),
                el('span', {class: 'push'}),
                button('Close without saving', () => shown.close()),
                saveButton
            ];
        },
        onClose: () => {
            if (typeof onClosed === 'function') {
                onClosed();
            }
        }
    });
    dialog.classList.add('config-modal');
    titleNode = dialog.querySelector('.modal__title');
    fill(titleNode, title());
    dialog.querySelector('.modal__head').append(button('Close without saving', () => dialog.close(), {small: true}));
    if (wanted) {
        openFold(wanted);
        const holder = parts.folds.get(wanted);
        const first = holder ? holder.querySelector('input, select, textarea') : null;
        if (first) {
            if (typeof holder.scrollIntoView === 'function') {
                holder.scrollIntoView({block: 'start'});
            }
            first.focus();
        }
    }

    return {
        close() {
            if (dialog && dialog.open) {
                dialog.close();
            }
        },
        dialog,
        /** The body this form would send now; for the tests. */
        input
    };
}

/* ---------- small pieces ---------- */

/** A line of this page's, with the server's warnings each on a line of its own beneath it. */
/** Names joined as a sentence lists them: "Token url, Client id and Scope". */
function listWords(items) {
    return items.length < 2 ? items.join('') : items.slice(0, -1).join(', ') + ' and ' + items[items.length - 1];
}

function said(text, warnings) {
    return el('div', {class: 'config-said'}, el('p', null, text),
        warnings && warnings.length > 0 ? el('ul', {class: 'notes'}, warnings.map(each => el('li', null, each))) : null);
}

/** A field with a line between its label and its input: the value in force and where it comes from. */
function fieldWith(label, input, line, hint) {
    const wrapper = field({label, input, hint});
    const [labelNode, ...rest] = Array.from(wrapper.childNodes);
    wrapper.replaceChildren(labelNode, line, ...rest);
    return wrapper;
}

function inForceLine(inForce) {
    return el('p', {class: 'config-in-force'},
        inForce.display ? el('span', {class: 'mono'}, inForce.display) : null,
        inForce.display ? ' ' : null,
        el('span', {class: 'muted'}, inForce.sourceLabel || 'not set'));
}

/**
 * A row of Values or Addresses (D-307). What is in force and where it comes from is the row's own
 * content, and the box to type over it is shown only when something is typed or nothing is in
 * force: on the first real run an empty box beside a value the files gave was read as a field left
 * unfilled, and a typed address replaced the collection's request with its headers and body.
 * "Change" opens the box; the button under it takes what was typed away again.
 */
function overField(label, input, inForce, why) {
    const source = inForce.source || 'NOT_SET';
    const nothing = source === 'NOT_SET' || source === 'NONE';
    const typed = source === 'THIS_ENVIRONMENT' || input.value !== '';
    const wrapper = fieldWith(label, input, inForceLine(inForce), why);
    const gone = el('p', {class: 'field__hint config-over__gone', hidden: true},
        'What was typed here is taken away when the environment is saved.');
    const change = button('Change', () => openBox(true), {small: true, quiet: true});
    const back = button(typed ? "Use the file's again" : againWords(source), () => {
        if (typed) {
            gone.hidden = false;
        }
        input.value = '';
        input.hidden = true;
        back.hidden = true;
        change.hidden = false;
        change.focus();
    }, {small: true, quiet: true});
    function openBox(focus) {
        input.hidden = false;
        change.hidden = true;
        back.hidden = nothing && !typed;
        gone.hidden = true;
        if (focus) {
            input.focus();
        }
    }
    wrapper.classList.add('config-over');
    wrapper.append(el('div', {class: 'config-over__actions'}, change, back), gone);
    wrapper.openBox = () => openBox(false);
    if (typed || nothing) {
        openBox(false);
    } else {
        input.hidden = true;
        back.hidden = true;
    }
    return wrapper;
}

/**
 * A stored secret is said as stored, with its reference, and its password box opens on "Change"
 * (D-307): an empty box under a stored secret read as one still to fill. A box that was opened and
 * left empty still keeps what is stored.
 */
function storedSecretClosed(wrapper, input) {
    const change = button('Change', () => {
        input.hidden = false;
        change.hidden = true;
        input.focus();
    }, {small: true, quiet: true});
    input.hidden = true;
    wrapper.classList.add('config-over');
    wrapper.openBox = () => {
        input.hidden = false;
        change.hidden = true;
    };
    wrapper.append(el('div', {class: 'config-over__actions'}, change));
}

/** The words of the button that goes back to what lies under a box nothing was typed in yet. */
function againWords(source) {
    if (source === 'COLLECTION') {
        return "Use the collection's request again";
    }
    if (source === 'APPLICATION_YML') {
        return "Use application.yml's again";
    }
    if (source === 'EXPORT') {
        return "Use the export's again";
    }
    return "Use the file's again";
}

function variableCount(variables) {
    const all = Array.isArray(variables) ? variables : [];
    const secrets = all.filter(each => each && each.secret).length;
    return all.length + (all.length === 1 ? ' variable' : ' variables')
        + (secrets > 0 ? ', ' + secrets + (secrets === 1 ? ' secret masked' : ' secrets masked') : '');
}

function ruleWord(rule) {
    return String(rule).toLowerCase();
}

/** "draftOrders type: Left out, from the collection's sample": the option's words before its reason. */
function shapeLine(shape) {
    const options = Array.isArray(shape.options) ? shape.options : [];
    const option = options.find(each => each.value === shape.value);
    const words = option && option.words ? String(option.words) : String(shape.value || '');
    const cut = words.indexOf(' (');
    const short = cut > 0 ? words.slice(0, cut) : words;
    return (shape.label || shape.key) + ': ' + short + (shape.from && shape.from !== 'this environment' ? ', from ' + shape.from : '');
}

/** What the chip of one address says: its short words, whether nothing is in force, and where its value comes from. */
function operationChip(address) {
    const inForce = address.inForce || {};
    // Nothing in force for this operation: the server says NONE or NOT_SET.
    const none = !inForce.source || inForce.source === 'NONE' || inForce.source === 'NOT_SET';
    // The plan names the short label "short"; the server's record may name it "shortLabel". Either is the server's word.
    const words = address.short || address.shortLabel || address.label || address.operation;
    const label = address.label || address.operation || words;
    return {words, dashed: none, title: inForce.sourceLabel ? label + ': ' + inForce.sourceLabel : null};
}

/**
 * One chip per short words (and per dashed or not), with its count when several operations share
 * them ("Subscription x4", I-22) and each one's title in the chip's title, so four subscription
 * operations never draw four chips that read the same.
 */
export function groupedChips(items) {
    const groups = new Map();
    for (const item of items) {
        const key = item.words + (item.dashed ? ' dashed' : '');
        const group = groups.get(key) || {words: item.words, dashed: item.dashed === true, count: 0, titles: []};
        group.count += 1;
        if (item.title && !group.titles.includes(item.title)) {
            group.titles.push(item.title);
        }
        groups.set(key, group);
    }
    return Array.from(groups.values(), group => chip(group.count > 1 ? group.words + ' x' + group.count : group.words,
        {dashed: group.dashed, title: group.titles.length > 0 ? group.titles.join(', ') : null}));
}
