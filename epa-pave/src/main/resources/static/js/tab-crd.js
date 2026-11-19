// The CRD tab: is prior authorization required for this member and this code, and where does it route.
//
// On the left, the case and what to send. On the right, the readout: the request before it is
// sent, what discovery returned, and the result of the latest run. The result is drawn by the
// shared result view with the CRD part passed in; nothing about a result is decided here.

import * as api from './api.js';
import {el, fill, clear, append, button, code, facts, table, heading, copyButton} from './dom.js';
import {textField, selectField, setOptions, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {renderResult, errorPanel, wireView, additionalFieldsSection} from './result.js';
import {crdDetail} from './crd-detail.js';
import {shown, presenceMark} from './shown.js';
import {resultWord} from './marks.js';

let app = null;
let panel = null;
let caseSlot = null;
let vocabulary = {outcomes: new Map(), elements: [], scenarios: null};
let busy = false;

// The controls of the request itself. The case (member, provider, codes) is shared and sits above them.
const controls = {};
// The parts of the readout, top to bottom.
const readout = {};

function mount(target, application) {
    app = application;
    panel = target;
    caseSlot = el('div', {class: 'case-slot'});

    controls.hookSign = el('input', {type: 'radio', name: 'crd-hook', id: 'crd-hook-sign', value: 'order-sign', checked: true});
    controls.hookSelect = el('input', {type: 'radio', name: 'crd-hook', id: 'crd-hook-select', value: 'order-select'});
    controls.date = textField({label: 'Date of service', field: 'dateOfService', placeholder: 'YYYY-MM-DD'});
    controls.scenarioSlot = el('div', {class: 'field--wide'});
    controls.expected = selectField({label: 'Expected outcome', field: 'expected', wide: true,
        hint: 'Optional. Without one the run is recorded as unverified.'});
    controls.assertions = el('div', {class: 'assertions'});
    controls.expectedText = el('p', {class: 'small field--wide', id: 'crd-expected-sent'});
    controls.testerRef = textField({label: 'Tester reference', field: 'testerRef', placeholder: 'ticket or initials'});
    controls.note = textField({label: 'Note', field: 'note', wide: true});
    controls.problem = el('div', {class: 'crd-problem'});
    controls.send = el('button', {type: 'submit', class: 'button button--primary'}, 'Send CRD request');
    controls.preview = button('Show the request', showPreview);
    controls.discover = button('Discover services', discover);

    const form = el('form', {class: 'case-pane', noValidate: true, attrs: {'aria-label': 'The case and the CRD request'}},
        el('div', {class: 'case-pane__scroll'},
            caseSlot,
            el('div', {class: 'case-group case-pane__own'},
                el('h2', {class: 'case-legend'}, 'CRD request'),
                el('div', {class: 'field field--inline', role: 'radiogroup', dataset: {field: 'hook'}, aria: {labelledby: 'crd-hook-label'}},
                    el('span', {class: 'field__label', id: 'crd-hook-label'}, 'Hook'),
                    el('div', null,
                        el('label', {class: 'choice', htmlFor: 'crd-hook-sign'}, controls.hookSign, code('order-sign')),
                        el('label', {class: 'choice', htmlFor: 'crd-hook-select'}, controls.hookSelect, code('order-select')))),
                el('div', {class: 'field-grid'},
                    controls.date.node,
                    controls.testerRef.node,
                    controls.scenarioSlot,
                    controls.expected.node,
                    el('details', {class: 'fold field--wide'},
                        el('summary', null, 'Assert standard codes as well'),
                        controls.assertions),
                    controls.expectedText,
                    controls.note.node))),
        el('div', {class: 'case-pane__actions'},
            controls.problem,
            el('div', {class: 'button-row'}, controls.send, controls.preview, controls.discover)));
    form.addEventListener('submit', event => {
        event.preventDefault();
        send();
    });
    form.addEventListener('input', event => {
        // A field that was refused is unmarked as soon as the tester changes it.
        const field = event.target.closest('.field--invalid');
        if (field) {
            field.classList.remove('field--invalid');
            field.querySelectorAll('.field__error').forEach(message => message.remove());
            event.target.removeAttribute('aria-invalid');
        }
        drawExpectedText();
    });
    form.addEventListener('change', drawExpectedText);

    readout.status = el('p', {class: 'readout__status', role: 'status', aria: {live: 'polite'}});
    readout.refusal = el('div', {class: 'readout__refusal', aria: {live: 'polite'}});
    readout.preview = el('div', {class: 'readout__preview', aria: {live: 'polite'}});
    readout.discovery = el('div', {class: 'readout__discovery', aria: {live: 'polite'}});
    readout.result = el('div', {class: 'readout__result', aria: {busy: 'false'}});
    const side = el('div', {class: 'readout', role: 'region', tabIndex: 0, aria: {label: 'The readout: the request, discovery and the result'}},
        readout.status, readout.refusal, readout.preview, readout.discovery, readout.result);

    panel.classList.add('tabpanel--workbench');
    fill(panel, el('div', {class: 'workbench'}, form, side));
    addAssertionRow();
    drawEmptyState();
    loadVocabulary();
}

function onShow() {
    // The one case node moves to whichever protocol tab is on screen.
    caseSlot.append(app.caseContext.node, app.chain.control);
}

/* ---------- the vocabulary: scenarios, outcomes and element names, all fetched ---------- */

async function loadVocabulary() {
    const answer = await api.get('/api/crd/scenarios');
    if (answer.kind !== 'ok') {
        fill(readout.refusal, errorPanel(answer.body));
        return;
    }
    const list = answer.body;
    vocabulary = {
        outcomes: new Map((list.outcomes || []).map(option => [option.code, option])),
        elements: list.elements || [],
        scenarios: list
    };
    setOptions(controls.expected.select, [{value: '', label: 'No expectation: the run will be unverified'}]
        .concat((list.outcomes || []).map(option => ({value: option.code, label: option.label}))));
    for (const select of controls.assertions.querySelectorAll('select')) {
        setOptions(select, elementOptions());
    }
    drawScenarioSelector(list);
    drawExpectedText();
    if (!readout.result.querySelector('.result')) {
        drawEmptyState();
    }
}

/**
 * The scenario selector exists on the mock profile only. On live the node is not built at all:
 * a control that could force an outcome must not be one stylesheet rule away from a tester who
 * is talking to the real service.
 */
function drawScenarioSelector(list) {
    clear(controls.scenarioSlot);
    controls.scenario = null;
    if (!app.isMock() || !list.available) {
        controls.scenarioSlot.hidden = true;
        return;
    }
    controls.scenarioSlot.hidden = false;
    const description = el('span', {class: 'field__hint', id: 'crd-scenario-about'});
    const field = selectField({
        label: 'Mock scenario', field: 'scenarioId',
        options: [{value: '', label: 'The member ID decides'}].concat(list.scenarios.map(scenario =>
            ({value: scenario.id, label: scenario.number + '. ' + scenario.label})))
    });
    field.select.setAttribute('aria-describedby', 'crd-scenario-about');
    const describe = () => {
        const chosen = list.scenarios.find(scenario => scenario.id === field.value());
        fill(description, chosen ? chosen.description : 'With none chosen here, the member ID picks the scenario.');
    };
    field.select.addEventListener('change', describe);
    describe();
    field.node.append(description);
    controls.scenario = field;
    controls.scenarioSlot.append(field.node);
    if (list.problems && list.problems.length > 0) {
        controls.scenarioSlot.append(el('ul', {class: 'note-list small'}, list.problems.map(problem => el('li', null, problem))));
    }
}

/* ---------- the expectation ---------- */

function elementOptions() {
    return [{value: '', label: 'element'}].concat(vocabulary.elements.map(name => ({value: name, label: name})));
}

function addAssertionRow() {
    const subject = el('input', {type: 'text', class: 'literal-input', placeholder: 'every answer', autocomplete: 'off', spellcheck: false,
        aria: {label: 'The procedure code this assertion is about. Leave empty for every answer.'}});
    const element = el('select', {aria: {label: 'Element'}});
    setOptions(element, elementOptions());
    const value = el('input', {type: 'text', class: 'literal-input', placeholder: 'code', autocomplete: 'off', spellcheck: false,
        aria: {label: 'The code expected in that element'}});
    const row = el('div', {class: 'assertion field'}, subject, element, el('span', {aria: {hidden: 'true'}}, '='), value,
        button('Remove', () => { row.remove(); drawExpectedText(); }, {class: 'button--text', aria: {label: 'Remove this assertion'}}));
    row.assertion = () => {
        if (element.value === '' && value.value.trim() === '' && subject.value.trim() === '') {
            return null;
        }
        return (subject.value.trim() === '' ? '' : subject.value.trim() + ': ') + element.value + '=' + value.value.trim();
    };
    const adder = controls.assertions.querySelector('.assertions__add');
    if (adder) {
        adder.before(row);
    } else {
        controls.assertions.append(
            el('p', {class: 'field__hint'}, 'Each line asserts one standard code, such as pa-needed = no-auth. Every line must hold for the run to pass.'),
            row,
            el('div', {class: 'assertions__add'}, button('Add another assertion', () => addAssertionRow(), {class: 'button--text'})));
    }
}

/** The expectation exactly as it is sent: terms separated by semicolons. A suite file takes the same text. */
function expectedText() {
    const terms = [];
    if (controls.expected.value() !== '') {
        terms.push(controls.expected.value());
    }
    for (const row of controls.assertions.querySelectorAll('.assertion')) {
        const term = row.assertion();
        if (term) {
            terms.push(term);
        }
    }
    return terms.join('; ');
}

function drawExpectedText() {
    const text = expectedText();
    fill(controls.expectedText, text === '' ? null : ['Sent as ', code(text)]);
}

/* ---------- the request ---------- */

function buildRequest() {
    const request = {hook: controls.hookSelect.checked ? 'order-select' : 'order-sign', ...app.caseContext.request()};
    const optional = {
        dateOfService: controls.date.value(),
        scenarioId: controls.scenario ? controls.scenario.value() : '',
        expected: expectedText(),
        testerRef: controls.testerRef.value(),
        note: controls.note.value(),
        chainId: app.chain.requestValue()
    };
    for (const [name, value] of Object.entries(optional)) {
        if (value !== undefined && value !== null && value !== '') {
            request[name] = value;
        }
    }
    // The configuration this screen was drawn from. The server refuses the run if it has changed
    // since, so a run can never go to an environment other than the one the tester was looking at.
    request.configEpoch = app.epoch();
    return request;
}

function setBusy(state, control) {
    busy = state;
    for (const each of [controls.send, controls.preview, controls.discover]) {
        each.disabled = state;
    }
    if (control) {
        control.setAttribute('aria-busy', state ? 'true' : 'false');
    }
    readout.result.setAttribute('aria-busy', state ? 'true' : 'false');
}

function clearProblems() {
    clearFieldErrors(panel);
    clear(controls.problem);
    clear(readout.refusal);
}

function refuseForm(error) {
    const unplaced = markFieldErrors([panel], error.fieldErrors);
    fill(controls.problem, formProblem(error, unplaced));
}

async function send() {
    if (busy) {
        return;
    }
    clearProblems();
    clear(readout.preview);
    setBusy(true, controls.send);
    readout.status.textContent = 'Sending the CRD request.';
    const answer = await api.postJson('/api/crd', buildRequest());
    setBusy(false, controls.send);
    if (answer.kind === 'ok' || answer.kind === 'result') {
        showResult(answer.body);
    } else if (answer.kind === 'form') {
        // A form problem is marked at the fields. The result of the earlier run stays where it is.
        refuseForm(answer.body);
        readout.status.textContent = 'Not sent. ' + answer.body.title;
    } else {
        refused(answer);
    }
}

/** An answer that is neither a result nor a form problem: the tool would not or could not do it. */
function refused(answer) {
    fill(readout.refusal, errorPanel(answer.body));
    readout.status.textContent = 'Not sent. ' + answer.body.title;
    if (answer.kind === 'conflict') {
        // The usual cause is that the configuration changed under this screen. Reading the mode
        // again redraws every tab if it did, and then the tester can send again.
        app.refreshMode();
    }
}

function showResult(envelope) {
    const node = renderResult(envelope, {
        detailTitle: 'CRD response detail',
        detail: result => crdDetail(result, vocabulary),
        nextStepControl
    });
    fill(readout.result, node);
    readout.result.closest('.readout').scrollTop = 0;
    readout.status.textContent = 'Result received. ' + envelope.verdict.headline + ' Test result: ' + resultWord(envelope.expectation.result) + '.';
    app.ledgerReceipt(envelope.ledger);
    app.chain.onResult(envelope);
}

/** The button a result offers when its next step is something this tool can do from here. */
function nextStepControl(step, envelope) {
    const carryTo = tabId => () => {
        app.handOver(tabId, {carry: step.carry || {}, fromRunId: envelope.runId, fromProtocol: envelope.protocol, chainId: envelope.chainId});
        app.navigate(tabId);
    };
    if (step.action === 'START_DTR') {
        return {label: 'Continue in DTR', run: carryTo('dtr')};
    }
    if (step.action === 'START_PAS') {
        return {label: 'Continue in PAS', run: carryTo('pas')};
    }
    if (step.action === 'OPEN_CONFIG' || step.action === 'UNLOCK_OR_SWITCH') {
        return {label: 'Open the Config tab', run: () => app.navigate('config')};
    }
    return null;
}

/* ---------- the request before it is sent ---------- */

async function showPreview() {
    if (busy) {
        return;
    }
    clearProblems();
    setBusy(true, controls.preview);
    readout.status.textContent = 'Building the request. Nothing is sent.';
    const answer = await api.postJson('/api/crd/preview', buildRequest());
    setBusy(false, controls.preview);
    if (answer.kind === 'form') {
        refuseForm(answer.body);
        readout.status.textContent = 'The request could not be built. ' + answer.body.title;
        return;
    }
    if (answer.kind !== 'ok') {
        refused(answer);
        return;
    }
    const preview = answer.body;
    const url = preview.url || {};
    const body = preview.body === undefined || preview.body === null ? null
        : el('pre', {class: 'body-text', tabIndex: 0, aria: {label: 'The request body as it would be sent'}}, preview.body);
    const node = el('section', {class: 'panel'},
        el('div', {class: 'panel__head'},
            heading(2, 'The request as it would be sent'),
            button('Close', () => clear(readout.preview), {class: 'button--quiet button--small'})),
        el('p', {class: 'quiet'}, 'Nothing was sent and nothing was recorded.'),
        preview.blocked ? el('div', {class: 'error-panel error-panel--blocked'}, el('p', {class: 'error-panel__title'}, preview.blocked)) : null,
        facts([
            ['Operation', [code(preview.operation), preview.method ? [' with ', code(preview.method)] : null]],
            ['Url as configured', url.template ? code(url.template) : presenceMark('EMPTY')],
            ['Url it resolves to', url.display ? code(url.display) : el('span', {class: 'presence presence--none'}, 'none')],
            ['Where the url comes from', url.source || null],
            ['Placeholders with no value', url.unresolved && url.unresolved.length > 0
                ? el('strong', null, url.unresolved.map((name, index) => [index > 0 ? ', ' : null, code('{{' + name + '}}')])) : null],
            ['Environment', preview.environment && preview.environment.active
                ? preview.environment.name + ' (' + preview.environment.kind + ')' : 'none active'],
            ['Collection request', preview.requestName ? [preview.requestName, preview.scripted
                ? el('div', null, el('span', {class: 'flag'}, 'pre-request script'), ' This request carries a script that this tool does not run.') : null] : null],
            ['Member ID', preview.memberId ? (preview.memberId.changed
                ? ['Typed as ', code(preview.memberId.typed), ' and sent as ', code(preview.memberId.sent), '.']
                : ['Sent as typed: ', code(preview.memberId.sent), '.']) : null]
        ], 'facts--ruled'),
        url.variables && url.variables.length > 0
            ? table(['Placeholder', 'Value shown', 'Comes from'], url.variables.map(variable => [
                code('{{' + variable.name + '}}'),
                variable.display === undefined ? presenceMark('ABSENT') : [code(variable.display), variable.secret ? ' (masked: a secret)' : null],
                code(variable.source)]))
            : null,
        preview.notes && preview.notes.length > 0 ? el('ul', {class: 'note-list'}, preview.notes.map(note => el('li', null, note))) : null,
        body ? el('div', {class: 'raw-body'},
            el('div', {class: 'panel__head'}, el('span', {class: 'sub-heading'}, 'Request body'),
                copyButton('Copy request body', () => body.textContent, body)),
            body) : null);
    fill(readout.preview, node);
    readout.status.textContent = 'The request is shown. Nothing was sent.';
}

/* ---------- discovery ---------- */

async function discover() {
    if (busy) {
        return;
    }
    clearProblems();
    setBusy(true, controls.discover);
    readout.status.textContent = 'Asking the service which CDS services it offers.';
    const answer = await api.get('/api/crd/discover');
    setBusy(false, controls.discover);
    if (answer.kind !== 'ok') {
        refused(answer);
        return;
    }
    const found = answer.body;
    const services = found.services || [];
    const url = found.url || {};
    const node = el('section', {class: 'panel'},
        el('div', {class: 'panel__head'},
            heading(2, 'CDS Hooks discovery', services.length),
            button('Close', () => clear(readout.discovery), {class: 'button--quiet button--small'})),
        found.note ? el('p', null, el('strong', null, found.note)) : null,
        facts([
            ['Url as configured', url.template ? code(url.template) : presenceMark('EMPTY')],
            ['Url it resolves to', url.display ? code(url.display) : el('span', {class: 'presence presence--none'}, 'none')],
            ['Where the url comes from', url.source || null]
        ], 'facts--ruled'),
        services.length === 0 ? el('p', null, 'The answer listed no services.')
            : table(['Service id', 'Hook', 'Title', 'Description', 'Prefetch'], services.map(service => [
                shown(service.id, {as: 'literal'}),
                shown(service.hook, {as: 'literal'}),
                shown(service.title),
                shown(service.description),
                service.prefetch && service.prefetch.length > 0
                    ? el('ul', {class: 'plain-list'}, service.prefetch.map(entry => el('li', null, code(entry.key), ' ', shown(entry.template, {as: 'literal'}))))
                    : el('span', {class: 'presence presence--none'}, 'none declared')
            ])),
        found.error ? errorPanel(found.error) : null,
        additionalFieldsSection(found.additionalFields || []),
        el('section', {class: 'result__section'}, wireView(found.wire)));
    fill(readout.discovery, node);
    readout.status.textContent = 'Discovery answered with ' + services.length + (services.length === 1 ? ' service.' : ' services.');
}

/* ---------- before the first run ---------- */

function drawEmptyState() {
    const parts = [
        el('p', {class: 'empty-state__lead'}, 'Nothing has been sent from this tab yet.'),
        el('p', null, 'Enter a member ID, the ordering provider\'s NPI and at least one procedure code on the left, then choose "Send CRD request". '
            + 'The answer appears here: what the service said, whether it matched what you expected, and what to do next.')
    ];
    const list = vocabulary.scenarios;
    if (app.isMock() && list && list.available && list.scenarios.length > 0) {
        parts.push(el('p', null, 'On the mock profile the member ID selects the scenario that answers. These are the member IDs that do:'));
        parts.push(table(['', 'Scenario', 'Member ID', ''], list.scenarios.map(scenario => [
            {class: 'num', node: String(scenario.number)},
            scenario.label,
            scenario.sentinelMemberId ? code(scenario.sentinelMemberId) : el('span', {class: 'presence presence--none'}, 'none'),
            scenario.sentinelMemberId
                ? button('Use this member ID', () => app.caseContext.setMemberId(scenario.sentinelMemberId),
                    {class: 'button--text', aria: {label: 'Use the member ID ' + scenario.sentinelMemberId}})
                : null
        ]), {class: 'sentinels'}));
    }
    fill(readout.result, el('div', {class: 'empty-state'}, parts));
}

/* ---------- when the configuration changes ---------- */

function onConfigChange() {
    clear(readout.preview);
    clear(readout.refusal);
    const result = readout.result.querySelector('.result');
    if (result && !result.querySelector('.result__stale')) {
        // The result stays, because the tester may still be reading it. It is marked, so it is
        // not taken for an answer from the configuration now in force.
        result.prepend(el('p', {class: 'result__stale'},
            'This result was produced before the configuration changed. It does not describe the environment now active.'));
    }
    loadVocabulary();
}

export default {id: 'crd', label: 'CRD', mount, onShow, onConfigChange};
