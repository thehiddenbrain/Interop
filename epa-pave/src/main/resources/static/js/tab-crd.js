// The CRD tab: is prior authorization required for this member and this code, and where does it route.
//
// On the left, the case and what to send. On the right, the readout: the request before it is
// sent, what discovery returned, and the result of the latest run. The panes, the sending and the
// handling of every kind of answer come from the workbench, and the result is drawn by the shared
// result view. What is CRD's own is in this file: the hook, the mock scenario, the expectation,
// discovery, and the words before the first run.

import * as api from './api.js';
import {el, fill, clear, button, code, facts, table, heading} from './dom.js';
import {textField, selectField, setOptions} from './forms.js';
import {errorPanel, wireView, additionalFieldsSection} from './result.js';
import {createWorkbench, requestPreviewPanel} from './workbench.js';
import {crdDetail} from './crd-detail.js';
import {shown, presenceMark} from './shown.js';

let app = null;
let bench = null;
// What the scenarios endpoint serves: the mock scenarios, every outcome with its label and tone,
// and the element names an expectation may assert on. Nothing of it is kept in this file.
let vocabulary = {outcomes: new Map(), elements: [], scenarios: null};

const controls = {};
const slots = {};

function mount(panel, application) {
    app = application;
    bench = createWorkbench(app, panel, {
        formLabel: 'The case and the CRD request',
        readoutLabel: 'The readout: the request, discovery and the result',
        onSubmit: send
    });

    controls.hookSign = el('input', {type: 'radio', name: 'crd-hook', id: 'crd-hook-sign', value: 'order-sign', checked: true});
    controls.hookSelect = el('input', {type: 'radio', name: 'crd-hook', id: 'crd-hook-select', value: 'order-select'});
    controls.date = textField({label: 'Date of service', field: 'dateOfService', placeholder: 'YYYY-MM-DD'});
    controls.scenarioSlot = el('div', {class: 'field--wide'});
    controls.expected = selectField({label: 'Expected outcome (optional)', field: 'expected'});
    controls.assertions = el('div', {class: 'assertions'});
    controls.expectedText = el('p', {class: 'small', id: 'crd-expected-sent'});
    controls.testerRef = textField({label: 'Tester reference', field: 'testerRef', placeholder: 'ticket or initials'});
    controls.note = textField({label: 'Note', field: 'note', wide: true});
    controls.send = el('button', {type: 'submit', class: 'button button--primary'}, 'Send CRD request');
    controls.preview = button('Show the request', showPreview);
    controls.discover = button('Discover the CDS services this environment offers', discover, {class: 'button--text'});

    bench.own.append(
        el('h2', {class: 'case-legend'}, 'CRD request'),
        el('div', {class: 'field field--inline', role: 'radiogroup', dataset: {field: 'hook'}, aria: {labelledby: 'crd-hook-label'}},
            el('span', {class: 'field__label', id: 'crd-hook-label'}, 'Hook'),
            el('div', null,
                el('label', {class: 'choice', htmlFor: 'crd-hook-sign'}, controls.hookSign, code('order-sign')),
                el('label', {class: 'choice', htmlFor: 'crd-hook-select'}, controls.hookSelect, code('order-select')))),
        el('div', {class: 'field-grid'}, controls.date.node, controls.testerRef.node, controls.scenarioSlot, controls.note.node),
        el('p', null, controls.discover));
    // What the tester expects is the last thing set before sending, so it sits with the button
    // and is in view however far the case above it has been scrolled.
    bench.actions.append(
        controls.expected.node,
        el('details', {class: 'fold'}, el('summary', null, 'Assert standard codes as well'), controls.assertions),
        controls.expectedText,
        bench.problem,
        el('div', {class: 'button-row'}, controls.send, controls.preview));
    bench.track(controls.send, controls.preview, controls.discover);
    bench.form.addEventListener('input', drawExpectedText);
    bench.form.addEventListener('change', drawExpectedText);

    slots.preview = bench.slot('preview');
    slots.discovery = bench.slot('discovery');
    addAssertionRow();
    drawEmptyState();
    loadVocabulary();
}

function onShow() {
    bench.showCase();
}

/* ---------- the vocabulary: scenarios, outcomes and element names, all fetched ---------- */

async function loadVocabulary() {
    const answer = await api.get('/api/crd/scenarios');
    if (answer.kind !== 'ok') {
        bench.refuse(answer.body);
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
    if (!bench.hasResult()) {
        drawEmptyState();
    }
}

/**
 * The scenario selector exists on the mock profile only. On live it is not built at all: a
 * control that could force an outcome must not be one stylesheet rule away from a tester who is
 * talking to the real service.
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
            el('div', {class: 'assertions__add'}, button('Add an assertion', () => addAssertionRow(), {class: 'button--text'})));
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

/* ---------- sending ---------- */

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

async function send() {
    const answer = await bench.call(() => api.postJson('/api/crd', buildRequest()), {saying: 'Sending the CRD request.', control: controls.send});
    if (answer) {
        // The request that went out is in the wire view of the result, so the one shown beforehand is put away.
        clear(slots.preview);
        bench.showResult(answer.body, {detailTitle: 'CRD response detail', detail: envelope => crdDetail(envelope, vocabulary)});
    }
}

async function showPreview() {
    const answer = await bench.call(() => api.postJson('/api/crd/preview', buildRequest()),
        {saying: 'Building the request. Nothing is sent.', control: controls.preview});
    if (answer) {
        fill(slots.preview, requestPreviewPanel(answer.body, () => clear(slots.preview)));
        bench.say('The request is shown. Nothing was sent.');
    }
}

/* ---------- discovery ---------- */

async function discover() {
    const answer = await bench.call(() => api.get('/api/crd/discover'),
        {saying: 'Asking the service which CDS services it offers.', control: controls.discover});
    if (!answer) {
        return;
    }
    const found = answer.body;
    const services = found.services || [];
    const url = found.url || {};
    fill(slots.discovery, el('section', {class: 'panel'},
        el('div', {class: 'panel__head'},
            heading(2, 'CDS Hooks discovery', services.length),
            button('Close', () => clear(slots.discovery), {class: 'button--quiet button--small'})),
        found.note ? el('p', null, el('strong', null, found.note)) : null,
        facts([
            ['Url as configured', url.template ? code(url.template) : presenceMark('EMPTY')],
            ['Url it resolves to', url.display ? code(url.display) : el('span', {class: 'presence presence--none'}, 'none')],
            ['Where the url comes from', url.source || null]
        ], 'facts--ruled'),
        services.length === 0 ? el('p', null, 'The answer listed no services.')
            : table([{text: 'Service id', class: 'col-22'}, {text: 'Hook', class: 'col-12'}, {text: 'Title', class: 'col-22'},
                {text: 'Description', class: 'col-30'}, {text: 'Prefetch', class: 'col-14'}], services.map(service => [
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
        el('section', {class: 'result__section'}, wireView(found.wire))));
    bench.say('Discovery answered with ' + services.length + (services.length === 1 ? ' service.' : ' services.'));
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
        parts.push(table([{text: '', class: 'col-5'}, {text: 'Scenario', class: 'col-45'}, {text: 'Member ID', class: 'col-25'},
            {text: '', class: 'col-25'}], list.scenarios.map(scenario => [
            {class: 'num', node: String(scenario.number)},
            scenario.label,
            scenario.sentinelMemberId ? code(scenario.sentinelMemberId) : el('span', {class: 'presence presence--none'}, 'none'),
            scenario.sentinelMemberId
                ? button('Use this member ID', () => app.caseContext.setMemberId(scenario.sentinelMemberId),
                    {class: 'button--text', aria: {label: 'Use the member ID ' + scenario.sentinelMemberId}})
                : null
        ]), {class: 'sentinels'}));
    }
    bench.showEmpty(el('div', {class: 'empty-state'}, parts));
}

/* ---------- when the configuration changes ---------- */

function onConfigChange() {
    // The request shown was built for the configuration that is gone. A refusal stays: it is
    // usually the very answer that said the configuration had changed, and it says what to do.
    clear(slots.preview);
    bench.markStale();
    loadVocabulary();
}

export default {id: 'crd', label: 'CRD', mount, onShow, onConfigChange};
