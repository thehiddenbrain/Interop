// The CRD tab: is prior authorization required for this member and this code, and where does it route.
//
// On the left, the case and what to send. On the right, the readout: the request before it is
// sent, what discovery returned, and the result of the latest run. The panes, the sending and the
// handling of every kind of answer come from the workbench, and the result is drawn by the shared
// result view. What is CRD's own is in this file: the hook, the mock scenario, the expectation,
// discovery, and the words before the first run.

import * as api from './api.js';
import {el, fill, clear, button, code, facts, table, heading, copyButton} from './dom.js';
import {textField, selectField, setOptions} from './forms.js';
import {errorPanel, wireView, additionalFieldsSection} from './result.js';
import {createWorkbench, requestPreviewPanel} from './workbench.js';
import {crdDetail} from './crd-detail.js';
import {createComparison} from './crd-compare.js';
import {shown, presenceMark} from './shown.js';

let app = null;
let bench = null;
let comparison = null;
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
        onSubmit: send,
        // The hook chosen is what a run sends; discovery is the step a tester may take after it.
        operations: () => [controls.hookSelect && controls.hookSelect.checked ? 'CRD_ORDER_SELECT' : 'CRD_ORDER_SIGN', 'CRD_DISCOVERY']
    });

    controls.hookSign = el('input', {type: 'radio', name: 'crd-hook', id: 'crd-hook-sign', value: 'order-sign', checked: true});
    controls.hookSelect = el('input', {type: 'radio', name: 'crd-hook', id: 'crd-hook-select', value: 'order-select'});
    controls.date = textField({label: 'Date of service', field: 'dateOfService', placeholder: 'YYYY-MM-DD'});
    controls.prefetch = el('input', {type: 'checkbox', id: 'crd-prefetch', checked: true});
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
        el('div', {class: 'field', dataset: {field: 'includePrefetch'}},
            el('label', {class: 'choice', htmlFor: 'crd-prefetch'}, controls.prefetch, 'Send prefetch'),
            el('span', {class: 'field__hint'}, 'The Patient, the Coverage and the provider go in the request as prefetch. '
                + 'Without them the service has to fetch them from a fhirServer, and the request notes say whether one is sent.')),
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
    controls.ownRequest = bench.useOwnRequest({protocol: 'CRD', sendButton: controls.send, sendLabel: 'Send CRD request',
        modeChanged: () => { if (!bench.hasResult()) { drawEmptyState(); } }});
    bench.useRunValues({operation});
    bench.form.addEventListener('input', drawExpectedText);
    bench.form.addEventListener('change', drawExpectedText);
    for (const hook of [controls.hookSign, controls.hookSelect]) {
        hook.addEventListener('change', () => bench.checkReadiness());
    }

    slots.preview = bench.slot('preview');
    slots.discovery = bench.slot('discovery');
    slots.compare = bench.slot('compare');
    comparison = createComparison({
        app, bench, ownRequest: controls.ownRequest, slot: slots.compare,
        buildRequest: compareRequest, operation, fill: fillFromComparison, useBuilt, useMemberId: value => app.caseContext.setMemberId(value),
        track: (...list) => bench.track(...list)
    });
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

let assertionCount = 0;

/**
 * One code assertion: an element, the code expected in it, and on a second line the procedure
 * code it is limited to. The second line is there because the three do not fit side by side in
 * this pane without cutting off what is typed into them.
 */
function addAssertionRow() {
    assertionCount += 1;
    const subjectId = 'crd-assertion-for-' + assertionCount;
    const subject = el('input', {type: 'text', id: subjectId, class: 'literal-input', placeholder: 'every answer', autocomplete: 'off', spellcheck: false});
    const element = el('select', {aria: {label: 'Element'}});
    setOptions(element, elementOptions());
    const value = el('input', {type: 'text', class: 'literal-input', placeholder: 'code', autocomplete: 'off', spellcheck: false,
        aria: {label: 'The code expected in that element'}});
    const row = el('div', {class: 'assertion field'},
        el('div', {class: 'assertion__what'}, element, el('span', {aria: {hidden: 'true'}}, '='), value),
        el('div', {class: 'assertion__for'},
            el('label', {htmlFor: subjectId}, 'Only in the answer for code'), subject,
            button('Remove', () => { row.remove(); drawExpectedText(); }, {class: 'button--text', aria: {label: 'Remove this assertion'}})));
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
            el('p', {class: 'field__hint'}, 'Each asserts one standard code, such as pa-needed = no-auth. Every one must hold for the run to pass.'),
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

/** The operation the hook on the form is sent as, as the configuration names it. */
function operation() {
    return 'crd-' + (controls.hookSelect.checked ? 'order-select' : 'order-sign');
}

function buildRequest() {
    const request = {hook: controls.hookSelect.checked ? 'order-select' : 'order-sign', ...app.caseContext.request(), ...bench.requestFields()};
    const optional = {
        dateOfService: controls.date.value(),
        // Left out when it is to be sent, which is what the server takes an absent value to mean.
        includePrefetch: controls.prefetch.checked ? undefined : false,
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

/**
 * What a comparison posts: the run request with the pasted text, and the case on the form even
 * while a request of the tester's own is chosen. A run leaves the form's case out then, because
 * the body is what goes; a comparison builds this tool's request from the form where the form has
 * a value, and reads only the rest out of the pasted text.
 */
function compareRequest() {
    const request = buildRequest();
    const onForm = app.caseContext.request();
    for (const name of ['member', 'provider']) {
        if (Object.keys(onForm[name] || {}).length > 0) {
            request[name] = onForm[name];
            request[name + 'Overrides'] = onForm[name + 'Overrides'];
        }
    }
    if ((onForm.procedureCodes || []).length > 0) {
        request.procedureCodes = onForm.procedureCodes;
    }
    return request;
}

async function send() {
    const answer = await bench.call(() => api.postJson('/api/crd', buildRequest()), {saying: 'Sending the CRD request.', control: controls.send});
    if (answer) {
        // A comparison on screen is folded away, so the result below it is what is in view.
        comparison.fold();
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
        // Whatever the readout was scrolled to, the request asked for is brought into view.
        slots.preview.scrollIntoView({block: 'start'});
        bench.say('The request is shown. Nothing was sent.');
    }
}

/* ---------- filling the form from a pasted request ---------- */

/** Chooses the request this tool builds from the form, as the tester would by choosing it. */
function useBuilt() {
    const built = controls.ownRequest.node.querySelector('input[type="radio"][value="built"]');
    if (built && !built.checked) {
        built.click();
    }
    built.focus();
}

/**
 * Puts on the form each value the comparison read out of the pasted request: the hook, the
 * member, the provider, the codes and the date of service. A value the form already had was used
 * as it is by the comparison and is left alone.
 *
 * The member ID and the NPI are put in first and looked up, as they are when a tester types one
 * and leaves the field. The details the pasted request holds (a name, a birth date, a plan) are
 * then set beside what the lookup found, and never typed over it unasked: the lookup may have
 * found another person under the same identifier, and a request built from both would be about
 * two people. A detail the lookup gave no value for is typed in from the pasted request. A detail
 * that disagrees with the lookup is listed, with a button that puts the pasted value in its field.
 *
 * Resolves to the report, in words, for the comparison to say:
 * {filled, typedIn, sameAsLookup, fromLookup, disagree, applyPasted, leftAlone, lookups}.
 */
async function fillFromComparison(answer) {
    const filled = [];
    const leftAlone = [];
    const codes = new Map();
    const valueOf = source => (source.value && source.value.presence === 'VALUE' ? String(source.value.value) : null);
    let memberId = null;
    let npi = null;
    const details = [];
    for (const source of answer.caseSource || []) {
        const label = source.label || source.field;
        if (source.source !== 'YOUR_REQUEST') {
            leftAlone.push(label + ' (the form\'s own value was used)');
            continue;
        }
        const value = valueOf(source);
        if (value === null) {
            leftAlone.push(label + ' (no value was read)');
            continue;
        }
        const codePart = /^procedureCodes\[(\d+)\]\.(code|system|description)$/.exec(source.field);
        if (codePart) {
            const entry = codes.get(codePart[1]) || {};
            entry[codePart[2]] = value;
            codes.set(codePart[1], entry);
            filled.push(label + ' ' + value);
        } else if (source.field === 'hook') {
            const hook = value === 'order-select' ? controls.hookSelect : controls.hookSign;
            hook.checked = true;
            // The form hears of it as it hears of a choice made by hand, so what depends on the hook follows it.
            hook.dispatchEvent(new Event('change', {bubbles: true}));
            filled.push(label + ' ' + value);
        } else if (source.field === 'dateOfService') {
            controls.date.set(value);
            filled.push(label + ' ' + value);
        } else if (source.field === 'member.memberId') {
            memberId = value;
            filled.push(label + ' ' + value + ', looked up');
        } else if (source.field === 'provider.npi') {
            npi = value;
            filled.push(label + ' ' + value + ', looked up');
        } else if (detailInput(source.field)) {
            details.push({field: source.field, label, value});
        } else {
            leftAlone.push(label + ' (the form has no field for it)');
        }
    }
    for (const entry of answer.notFound || []) {
        leftAlone.push((entry.label || entry.field) + ' (not found in your request)');
    }
    if (memberId !== null) {
        app.caseContext.setMemberId(memberId);
    }
    const npiInput = detailInput('provider.npi');
    if (npi !== null && npiInput) {
        npiInput.value = npi;
        npiInput.dispatchEvent(new Event('input', {bubbles: true}));
        // Leaving the field is what looks an NPI up.
        npiInput.dispatchEvent(new FocusEvent('blur'));
    }
    if (codes.size > 0) {
        app.caseContext.setCodes(Array.from(codes.keys()).sort((one, other) => Number(one) - Number(other)).map(key => codes.get(key)));
    }
    drawExpectedText();
    await lookupsSettled();

    const typedIn = [];
    const sameAsLookup = [];
    const disagree = [];
    for (const detail of details) {
        const input = detailInput(detail.field);
        const onForm = input.value.trim();
        if (onForm === detail.value) {
            sameAsLookup.push(detail.label);
        } else if (onForm === '') {
            app.caseContext.setDetail(detail.field, detail.value, 'pasted');
            typedIn.push(detail.label + ' ' + detail.value);
        } else {
            disagree.push({field: detail.field, label: detail.label, pasted: detail.value, onForm});
        }
    }
    // The lookup found someone, and the pasted request names details that differ from what it
    // found: said under the lookup too, where the tester reads who the identifier belongs to.
    for (const subject of ['member', 'provider']) {
        const differing = disagree.filter(entry => entry.field.startsWith(subject + '.'));
        app.caseContext.lookupNote(subject, differing.length === 0 ? null
            : 'The pasted request does not agree with this lookup on ' + differing.map(entry => entry.label).join(', ')
                + '. The comparison lists both values, and the form keeps what the lookup found unless you put the pasted ones in.');
    }
    // What the lookups put on the form that the pasted request did not hold: it goes out with the
    // request all the same, so it is named.
    const pastedFields = new Set(details.map(detail => detail.field));
    const fromLookup = [];
    for (const field of app.caseContext.node.querySelectorAll('.field--fromlookup[data-field]')) {
        const input = field.querySelector('input');
        if (input && !pastedFields.has(field.dataset.field) && input.value.trim() !== '') {
            const label = field.querySelector('label');
            fromLookup.push((label ? label.textContent : field.dataset.field) + ' ' + input.value.trim());
        }
    }
    return {
        filled, typedIn, sameAsLookup, fromLookup, leftAlone,
        disagree: disagree.map(entry => ({label: entry.label, pasted: entry.pasted, onForm: entry.onForm})),
        lookups: lookupSentences(memberId !== null, npi !== null),
        /** Puts the pasted value in every field that disagreed with the lookup, when the tester asks for it. */
        applyPasted() {
            for (const entry of disagree) {
                app.caseContext.setDetail(entry.field, entry.pasted, 'pasted');
            }
            return disagree.map(entry => entry.label);
        }
    };
}

/** The input of a field of the case, by the name the request gives it, or null. */
function detailInput(field) {
    return app.caseContext.node.querySelector('[data-field="' + field + '"] input');
}

/**
 * Resolves once no lookup of the case is on its way: each lookup marks its button busy while it
 * waits for an answer. A lookup that never ends is given up on after a while, and the details are
 * then set beside whatever the form holds.
 */
function lookupsSettled() {
    const busy = () => app.caseContext.node.querySelector('.lookup-action[aria-busy="true"]') !== null;
    return new Promise(resolve => {
        const started = Date.now();
        const check = () => {
            if (!busy() || Date.now() - started > 20000) {
                resolve();
            } else {
                window.setTimeout(check, 100);
            }
        };
        check();
    });
}

/** How each lookup the fill made ended, in the lookup's own sentence as its strip shows it. */
function lookupSentences(member, provider) {
    const out = [];
    for (const [asked, field, name] of [[member, 'member.memberId', 'Member lookup'], [provider, 'provider.npi', 'Provider lookup']]) {
        const state = asked ? app.caseContext.node.querySelector('[data-field="' + field + '"] .lookup-strip__state') : null;
        if (state && state.textContent.trim() !== '') {
            out.push(name + ': ' + state.textContent.trim());
        }
    }
    return out;
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
            : table([{text: 'Service id', class: 'col-17'}, {text: 'Hook', class: 'col-12'}, {text: 'Title', class: 'col-17'},
                {text: 'Description', class: 'col-22'}, 'Prefetch'], services.map(service => [
                shown(service.id, {as: 'literal'}),
                shown(service.hook, {as: 'literal'}),
                shown(service.title),
                shown(service.description),
                service.prefetch && service.prefetch.length > 0
                    ? el('ul', {class: 'plain-list crd-prefetch'}, service.prefetch.map(entry => el('li', null, code(entry.key), ' ', template(entry.template))))
                    : el('span', {class: 'presence presence--none'}, 'none declared')
            ])),
        found.error ? errorPanel(found.error) : null,
        additionalFieldsSection(found.additionalFields || [], null, found.wire),
        el('section', {class: 'result__section'}, wireView(found.wire))));
    slots.discovery.scrollIntoView({block: 'start'});
    bench.say('Discovery answered with ' + services.length + (services.length === 1 ? ' service.' : ' services.'));
}

/**
 * A prefetch template, set so that a narrow column breaks it after a slash, a dot, a question
 * mark, an ampersand or an equals sign, and inside a name only when nothing else fits. The text
 * is unchanged: what is copied is the template as the service declared it.
 */
function template(value) {
    if (!value || value.presence !== 'VALUE') {
        return shown(value, {as: 'literal'});
    }
    const node = el('code', {class: 'literal'});
    for (const part of String(value.value).split(/(?<=[/.?&=])/)) {
        node.append(part, el('wbr'));
    }
    return node;
}

/* ---------- before the first run ---------- */

function drawEmptyState() {
    // With a request of the tester's own chosen, the body is what goes and the form's member is not sent.
    const own = bench.ownRequestActive();
    const parts = [
        el('p', {class: 'empty-state__lead'}, 'Nothing has been sent from this tab yet.'),
        el('p', null, own
            ? 'Paste the request body on the left, then choose "Send CRD request". The member, provider and codes on the form are not sent with it. '
                + 'The answer appears here: what the service said, whether it matched what you expected, and what to do next.'
            : 'Enter a member ID, the ordering provider\'s NPI and at least one procedure code on the left, then choose "Send CRD request". '
                + 'The answer appears here: what the service said, whether it matched what you expected, and what to do next.')
    ];
    const list = vocabulary.scenarios;
    if (app.isMock() && list && list.available && list.scenarios.length > 0) {
        parts.push(el('p', null, own
            ? 'On the mock profile a sentinel member ID inside your body selects the scenario that answers, unless one is chosen in the Mock '
                + 'scenario field. These are the member IDs that do:'
            : 'On the mock profile the member ID selects the scenario that answers. These are the member IDs that do:'));
        parts.push(table([{text: '', class: 'col-5'}, {text: 'Scenario', class: 'col-45'}, {text: 'Member ID', class: 'col-25'},
            {text: '', class: 'col-25'}], list.scenarios.map(scenario => [
            {class: 'num', node: String(scenario.number)},
            scenario.label,
            scenario.sentinelMemberId ? code(scenario.sentinelMemberId) : el('span', {class: 'presence presence--none'}, 'none'),
            // The form's member is not sent with a body of the tester's own: the ID is copied, to be put into the body.
            !scenario.sentinelMemberId ? null
                : own ? copyButton('Copy this member ID', () => scenario.sentinelMemberId)
                    : button('Use this member ID', () => app.caseContext.setMemberId(scenario.sentinelMemberId),
                        {class: 'button--text', aria: {label: 'Use the member ID ' + scenario.sentinelMemberId}})
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
    comparison.markStale();
    loadVocabulary();
}

export default {id: 'crd', label: 'CRD', mount, onShow, onConfigChange};
