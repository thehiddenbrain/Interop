// The DTR tab: the payer's questionnaires for the case, answered by a person, and completed into
// the response PAS carries.
//
// On the left, the case and how to ask for the package: what CRD handed on, a questionnaire url
// typed by hand, or the codes of the case alone. On the right, the readout: the request before it
// is sent, then the result of the latest step. Below a step's verdict and findings sit the
// questions to answer, with the buttons that send the next step, and then what the step returned.
// The panes, the sending and every kind of answer come from the workbench, and the result is
// drawn by the shared result view. What is DTR's own is in this file and in the dtr- modules.
//
// There is no SMART launch behind this tab and no patient record: the tab says so where it is seen.

import * as api from './api.js';
import {el, fill, clear, button, code, facts} from './dom.js';
import {textField, textArea, selectField, setOptions, LEDGER_TEXT_HINT} from './forms.js';
import {createWorkbench, requestPreviewPanel, nextStepControl, scenarioTable, scenarioListNotes, scenarioAbout} from './workbench.js';
import {sameCase} from './handover.js';
import {dtrDetail} from './dtr-detail.js';
import {createSession, hasQuestionnaires} from './dtr-questionnaire.js';

let app = null;
let bench = null;
// What the scenarios endpoint serves: the mock scenarios, every outcome with its label and tone,
// and the element names an expectation may assert on. Nothing of it is kept in this file.
let vocabulary = {outcomes: new Map(), elements: [], scenarios: null, actions: new Map()};
// The questionnaires of the latest package and everything their next steps must post. In memory
// only: a reload, or the next package fetched, starts again.
let session = null;
// The operation the latest request was for, so values for one run are looked up for that one.
let lastOperation = 'dtr-questionnaire-package';
// The expectation the latest step went with, and its run, once it has been cleared from the field.
let lastExpectation = null;

const controls = {};
const slots = {};

function mount(panel, application) {
    app = application;
    bench = createWorkbench(app, panel, {
        formLabel: 'The case and the DTR package request',
        readoutLabel: 'The readout: the request, the questionnaires and the result',
        onSubmit: fetchPackage,
        // Before anything is configured on live the workbench says what stops the package from being
        // fetched, in the server's words, in place of a form that cannot send.
        operations: ['DTR_QUESTIONNAIRE_PACKAGE', 'DTR_NEXT_QUESTION']
    });

    controls.carried = el('div', {class: 'dtr-carried', aria: {live: 'polite'}});
    controls.questionnaires = textArea({
        label: 'Questionnaire urls (optional)', field: 'questionnaires', rows: 2,
        hint: 'One canonical url on each line, with its version after a bar if it has one. Left empty, the questionnaire CRD named is asked for, '
            + 'or none, which leaves the choice to the service.'
    });
    controls.assertionId = textField({label: 'Coverage assertion id (optional)', field: 'coverageAssertionId', literal: true,
        hint: 'Left empty, the one CRD handed on is used.'});
    controls.date = textField({label: 'Date of service', field: 'dateOfService', placeholder: 'YYYY-MM-DD'});
    controls.scenarioSlot = el('div', {class: 'field--wide'});
    controls.expected = selectField({label: 'Expected outcome of the next step sent (optional)', field: 'expected'});
    controls.assertions = el('div', {class: 'assertions'});
    controls.expectedText = el('p', {class: 'small'});
    controls.testerRef = textField({label: 'Tester reference', field: 'testerRef', placeholder: 'ticket or initials', hint: LEDGER_TEXT_HINT});
    controls.note = textField({label: 'Note', field: 'note', wide: true, hint: LEDGER_TEXT_HINT});
    controls.send = el('button', {type: 'submit', class: 'button button--primary'}, 'Fetch the questionnaire package');
    controls.preview = button('Show the request', showPackagePreview);
    controls.restart = el('p', {class: 'small dtr-restart', hidden: true},
        'Fetching the package again starts the questionnaires over: the answers typed on the right are dropped.');
    // The summary of a refused package form goes here; a refused loop or response step has its own,
    // beside the button that sent it, in the questionnaire.
    controls.problemHome = el('div', {class: 'dtr-problem-home'}, bench.problem);

    // The questionnaire field takes one url a line, and Enter in it starts the next line.
    controls.questionnaires.input.wrap = 'soft';
    bench.own.append(
        el('h2', {class: 'case-legend'}, 'DTR questionnaire package'),
        controls.carried,
        el('div', {class: 'field-grid'}, controls.questionnaires.node, controls.assertionId.node, controls.date.node, controls.testerRef.node,
            controls.scenarioSlot, controls.note.node));
    bench.actions.append(
        controls.expected.node,
        el('details', {class: 'fold'}, el('summary', null, 'Assert standard codes as well'), controls.assertions),
        controls.expectedText,
        controls.problemHome,
        controls.restart,
        el('div', {class: 'button-row'}, controls.send, controls.preview));
    bench.track(controls.send, controls.preview);
    bench.useOwnRequest({protocol: 'DTR', sendButton: controls.send, sendLabel: 'Fetch the questionnaire package',
        modeChanged: () => { if (!bench.hasResult()) { drawEmptyState(); } }});
    bench.useRunValues({operation: () => lastOperation});
    bench.form.addEventListener('input', drawExpectedText);
    bench.form.addEventListener('change', drawExpectedText);

    slots.preview = bench.slot('preview');
    addAssertionRow();
    drawEmptyState();
    loadVocabulary();
}

function onShow() {
    bench.showCase();
    drawCarried();
}

/* ---------- the vocabulary: scenarios, outcomes and element names, all fetched ---------- */

/**
 * The server's label of a next action. The tab's two step buttons carry these labels, so a next
 * step that quotes one names the button. Before the vocabulary is read the code itself is shown.
 */
function actionName(code) {
    return vocabulary.actions.get(code) || code;
}

async function loadVocabulary() {
    const answer = await api.get('/api/dtr/scenarios');
    if (answer.kind !== 'ok') {
        bench.refuse(answer.body);
        return;
    }
    const list = answer.body;
    vocabulary = {
        outcomes: new Map((list.outcomes || []).map(option => [option.code, option])),
        elements: list.elements || [],
        scenarios: list,
        actions: new Map((list.nextActions || []).map(action => [action.code, action.label]))
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
 * talking to the real service. It steers the package fetch and the loop steps, which are calls;
 * the response step makes none.
 */
function drawScenarioSelector(list) {
    clear(controls.scenarioSlot);
    controls.scenario = null;
    if (!app.isMock() || !list.available) {
        controls.scenarioSlot.hidden = true;
        return;
    }
    controls.scenarioSlot.hidden = false;
    const description = el('span', {class: 'field__hint', id: 'dtr-scenario-about'});
    const field = selectField({
        label: 'Mock scenario', field: 'scenarioId',
        options: [{value: '', label: 'The member ID decides'}].concat(list.scenarios.map(scenario =>
            ({value: scenario.id, label: scenario.number + '. ' + scenario.label})))
    });
    field.select.setAttribute('aria-describedby', 'dtr-scenario-about');
    const describe = () => {
        const chosen = list.scenarios.find(scenario => scenario.id === field.value());
        fill(description, chosen ? scenarioAbout(chosen) : 'With none chosen here, the member ID picks the scenario.');
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

/* ---------- what CRD handed on ---------- */

/** The hand-over, when there is one and it still belongs to the case on screen. Values carried for one member never go out with another. */
function usableHandover() {
    const handed = app.carriedTo('dtr');
    if (!handed) {
        return null;
    }
    if (handed.forCase && !sameCase(handed.forCase, app.caseContext.summary())) {
        return null;
    }
    return handed;
}

function drawCarried() {
    const handed = app.carriedTo('dtr');
    if (!handed) {
        fill(controls.carried, el('p', {class: 'small quiet'},
            'Nothing was handed on from CRD. The package is asked for by the codes of the case, or by a questionnaire url typed below.'));
        return;
    }
    const usable = usableHandover();
    const carry = handed.carry || {};
    const entries = Object.entries(carry).filter(([, values]) => (Array.isArray(values) ? values : [values]).length > 0);
    fill(controls.carried,
        el('p', {class: 'sub-heading'}, 'Handed on from the ', handed.fromProtocol, ' result'),
        el('p', {class: 'small'}, 'Run ', code(handed.fromRunId), handed.chainId ? [', chain ', code(handed.chainId)] : null, '.'),
        usable ? null : el('p', {class: 'dtr-carried__stale'}, el('strong', null, 'Not sent: the case on screen has changed since that run. '),
            'What was handed on belongs to the case that run was sent with, so the package request goes without it.'),
        entries.length === 0 ? el('p', {class: 'small quiet'}, 'The step handed on no values.')
            : facts(entries.map(([key, values]) => [
                carriedName(handed, key),
                el('ul', {class: 'plain-list'}, (Array.isArray(values) ? values : [values]).map(value => el('li', null, code(String(value)))))
            ]), 'facts--ruled handed-on'));
}

/** A key of what was handed on, in the server's words with the key beside them, or the key alone when it sent none. */
function carriedName(handed, key) {
    const label = handed.carryLabels ? handed.carryLabels[key] : null;
    return label ? [label, ' ', el('span', {class: 'small quiet'}, '(', code(key), ')')] : code(key);
}

/* ---------- the expectation ---------- */

function elementOptions() {
    return [{value: '', label: 'element'}].concat(vocabulary.elements.map(name => ({value: name, label: name})));
}

function addAssertionRow() {
    const element = el('select', {aria: {label: 'Element'}});
    setOptions(element, elementOptions());
    const value = el('input', {type: 'text', class: 'literal-input', placeholder: 'value', autocomplete: 'off', spellcheck: false,
        aria: {label: 'The value expected in that element'}});
    const row = el('div', {class: 'assertion field'},
        el('div', {class: 'assertion__what'}, element, el('span', {aria: {hidden: 'true'}}, '='), value,
            button('Remove', () => { row.remove(); drawExpectedText(); }, {class: 'button--text', aria: {label: 'Remove this assertion'}})));
    row.assertion = () => (element.value === '' && value.value.trim() === '' ? null : element.value + '=' + value.value.trim());
    const adder = controls.assertions.querySelector('.assertions__add');
    if (adder) {
        adder.before(row);
    } else {
        controls.assertions.append(
            el('p', {class: 'field__hint'}, 'Each asserts one element of the step, such as questionnaire-kind = static. Every one must hold for the run to pass. ' + LEDGER_TEXT_HINT),
            row,
            el('div', {class: 'assertions__add'}, button('Add an assertion', () => addAssertionRow(), {class: 'button--text'})));
    }
}

/** The expectation exactly as it is sent: terms separated by semicolons. */
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
    fill(controls.expectedText,
        text !== '' ? ['Sent with the next step, whichever button sends it, as ', code(text), '. It is cleared once that step has a result.']
            : lastExpectation ? ['The expectation ', code(lastExpectation.text), ' went with run ', code(lastExpectation.runId),
                ' and was then cleared. The next step goes with none unless you choose one.'] : null);
    if (session) {
        session.refreshExpectation();
    }
}

/**
 * An expectation is about one step. Once a step it went with has a result, it is cleared, so it
 * is never sent again with a step it was not declared for (a package expectation judging the
 * response step, say, and writing a fail to the ledger).
 */
function clearExpectation(envelope) {
    const text = expectedText();
    if (text === '') {
        return;
    }
    lastExpectation = {text, runId: envelope.runId};
    controls.expected.select.value = '';
    for (const row of controls.assertions.querySelectorAll('.assertion')) {
        row.remove();
    }
    addAssertionRow();
    drawExpectedText();
}

function editExpectation() {
    controls.expected.select.scrollIntoView({block: 'center'});
    controls.expected.select.focus({preventScroll: true});
}

/* ---------- what every step posts ---------- */

/** What every DTR step shares: the case, the expectation, the chain and the configuration it was drawn from. */
function commonFields(withScenario) {
    const request = {...app.caseContext.request()};
    const optional = {
        dateOfService: controls.date.value(),
        scenarioId: withScenario && controls.scenario ? controls.scenario.value() : '',
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

function packageRequest() {
    const request = {...commonFields(true), ...bench.requestFields()};
    const urls = controls.questionnaires.value().split('\n').map(line => line.trim()).filter(line => line !== '');
    if (urls.length > 0) {
        request.questionnaires = urls;
    }
    if (controls.assertionId.value() !== '') {
        request.coverageAssertionId = controls.assertionId.value();
    }
    const handed = usableHandover();
    if (handed && handed.carry && Object.keys(handed.carry).length > 0) {
        request.carry = handed.carry;
    }
    return request;
}

/** A loop step or a response step for one questionnaire: the case, the hand-over of the latest DTR result, and what the session says to post. */
function stepRequest(entry, kind) {
    const request = {...commonFields(kind === 'loop'), ...(kind === 'loop' ? session.loopBody(entry) : session.responseBody(entry))};
    if (kind === 'loop') {
        const values = bench.requestFields().variables;
        if (values) {
            request.variables = values;
        }
    }
    request.carry = session.carry;
    return request;
}

/**
 * The server names a refused answer by its place in the list posted, answers[2].values[0]. The
 * question it is about carries its linkId on screen, so the name is turned into that, and the
 * server's sentence lands at the question.
 */
function placeAnswerProblems(answer, posted) {
    if (answer.kind !== 'form' || !Array.isArray(answer.body.fieldErrors)) {
        return answer;
    }
    const fieldErrors = answer.body.fieldErrors.map(problem => {
        const match = /^answers\[(\d+)\]/.exec(String(problem.field));
        const sent = match ? posted[Number(match[1])] : null;
        return sent ? {...problem, field: 'dtr-answer:' + sent.linkId} : problem;
    });
    return {...answer, body: {...answer.body, fieldErrors}};
}

/* ---------- sending ---------- */

/** Puts the summary of a refused form beside the button that was pressed: the package form's, or a step's. */
function placeProblem(slot) {
    const home = slot || controls.problemHome;
    if (bench.problem.parentNode !== home) {
        home.prepend(bench.problem);
    }
    for (const said of document.querySelectorAll('.dtr-step-again')) {
        said.remove();
    }
}

/** After a configuration refusal there is nothing to fetch, so the empty state stops saying to fetch. */
function noteRefusal(reply) {
    if (reply && reply.kind === 'conflict' && reply.body && reply.body.kind === 'CONFIGURATION' && !bench.hasResult()) {
        bench.showEmpty(el('div', {class: 'empty-state'},
            el('p', {class: 'empty-state__lead'}, 'No questionnaire package can be fetched until DTR is configured.'),
            el('p', null, 'What is missing is said above, in the server\'s words. Once it is set on the Config tab, come back and fetch the package.')));
    }
    return reply;
}

/** The focus goes to what arrived, so a keyboard user carries on from there and not from the top of the page. */
function focusOn(node) {
    if (node) {
        node.tabIndex = -1;
        node.focus({preventScroll: true});
    }
}

async function fetchPackage() {
    lastOperation = 'dtr-questionnaire-package';
    placeProblem(null);
    const answer = await bench.call(() => api.postJson('/api/dtr/package', packageRequest()).then(noteRefusal),
        {saying: 'Fetching the questionnaire package.', control: controls.send});
    if (!answer) {
        return;
    }
    clear(slots.preview);
    const envelope = answer.body;
    session = hasQuestionnaires(envelope) && envelope.operation === 'DTR_QUESTIONNAIRE_PACKAGE' ? createSession(envelope, hooks()) : null;
    controls.restart.hidden = !session;
    clearExpectation(envelope);
    show(envelope);
}

function hooks() {
    return {
        loop: entry => sendStep(entry, 'loop'),
        complete: entry => sendStep(entry, 'response'),
        previewLoop: entry => showLoopPreview(entry),
        // The case the latest step was sent for goes with the hand-over, as it does from the result's own button.
        nextControl: (step, envelope) => nextStepControl(app, app.caseContext.summary())(step, envelope),
        track: (...list) => bench.track(...list),
        actionName,
        expected: () => expectedText(),
        editExpectation
    };
}

async function sendStep(entry, kind) {
    lastOperation = kind === 'loop' ? 'dtr-next-question' : 'dtr-questionnaire-package';
    const request = stepRequest(entry, kind);
    const posted = request.answers || [];
    const path = kind === 'loop' ? '/api/dtr/next-question' : '/api/dtr/response';
    const saying = kind === 'loop' ? 'Sending the answers to the service and asking for the next question.'
        : 'Putting the response together. Nothing is sent to the payer.';
    const slot = session.problemSlot();
    placeProblem(slot);
    const answer = await bench.call(() => api.postJson(path, request).then(reply => placeAnswerProblems(reply, posted)), {saying, control: null});
    if (!answer) {
        sayAgain(slot, actionName(kind === 'loop' ? 'ANSWER_NEXT_QUESTION' : 'SUBMIT_RESPONSE'));
        return;
    }
    clear(slots.preview);
    if (kind === 'loop') {
        // A wrapped step is sent as the exception it is, one step at a time.
        session.wrap = '';
    }
    clearExpectation(answer.body);
    session.applyResult(entry, kind, answer.body);
    show(answer.body);
    // The tester is answering questions, and the next ones are where they work, not at the top of
    // the result. The line at the head of the questionnaire repeats what the step answered.
    session.node.scrollIntoView({block: 'start'});
    // The button that was pressed is redrawn with the result, so the focus is put on the line that
    // says what the step answered, from where the questions are the next stops of the Tab key.
    const said = session.node.querySelector('.dtr-laststep');
    if (said) {
        said.focus({preventScroll: true});
    }
}

/** After a refused step, names the button to press again, beside the summary of what to correct. */
function sayAgain(slot, label) {
    if (slot && bench.problem.parentNode === slot && bench.problem.childNodes.length > 0) {
        slot.append(el('p', {class: 'dtr-step-again'}, 'Then choose "' + label + '" again.'));
    }
}

function show(envelope) {
    bench.showResult(envelope, {
        detailTitle: session ? 'The questionnaire, and what this step returned' : 'DTR response detail',
        detail: shownEnvelope => [session ? session.node : null, dtrDetail(shownEnvelope)],
        // While questionnaires are being answered, the hand-over to PAS is the session's: it is
        // withdrawn while the answers on screen differ from the ones last completed.
        nextStepControl: step => (session ? session.resultControl(step) : null)
    });
    if (session) {
        // The result's own next-step button is drawn afresh, so whether it is offered is settled again.
        session.sync();
    }
}

async function showPackagePreview() {
    placeProblem(null);
    const answer = await bench.call(() => api.postJson('/api/dtr/package/preview', packageRequest()).then(noteRefusal),
        {saying: 'Building the package request. Nothing is sent.', control: controls.preview});
    if (answer) {
        fill(slots.preview, requestPreviewPanel(answer.body, () => clear(slots.preview)));
        bench.say('The package request is shown. Nothing was sent.');
        focusOn(slots.preview.querySelector('h2'));
    }
}

async function showLoopPreview(entry) {
    const request = stepRequest(entry, 'loop');
    const posted = request.answers || [];
    const slot = session.problemSlot();
    placeProblem(slot);
    const answer = await bench.call(() => api.postJson('/api/dtr/next-question/preview', request).then(reply => placeAnswerProblems(reply, posted)),
        {saying: 'Building the next-question request. Nothing is sent.', control: null});
    if (!answer) {
        sayAgain(slot, 'Show the next-question request');
        return;
    }
    fill(slots.preview, requestPreviewPanel(answer.body, () => clear(slots.preview)));
    bench.say('The next-question request is shown above the result. Nothing was sent.');
    slots.preview.scrollIntoView({block: 'start'});
    focusOn(slots.preview.querySelector('h2'));
}

/* ---------- before the first run ---------- */

function drawEmptyState() {
    const own = bench.ownRequestActive();
    const parts = [
        el('p', {class: 'empty-state__lead'}, 'No questionnaire package has been fetched from this tab yet.'),
        // Said once, where it is seen first. Once a package is here, it is said where its questions start.
        el('p', {class: 'dtr-honest dtr-honest--lead'},
            'There is no SMART launch here and no patient record: you type each answer yourself. Answers marked as pre-populated come from the payer\'s service.'),
        el('p', null, own
            ? 'Paste the package request body on the left, then choose "Fetch the questionnaire package". The member, provider and codes on the form are not sent with it.'
            : 'With the member ID, the ordering provider\'s NPI and a procedure code on the left, choose "Fetch the questionnaire package". '
                + 'What CRD handed on goes with it. The questions then appear here to be answered, one step at a time for an adaptive '
                + 'questionnaire, and the completed response is handed to PAS.')
    ];
    const list = vocabulary.scenarios;
    if (app.isMock() && list && list.available && list.scenarios.length > 0) {
        parts.push(el('p', null, own
            ? 'On the mock profile a sentinel member ID inside your body selects the scenario that answers. These are the member IDs that do:'
            : 'On the mock profile the member ID selects the scenario that answers. These are the member IDs that do:'));
        parts.push(scenarioListNotes(list));
        parts.push(scenarioTable(app, list, {own, onUse: letTheMemberIdDecide}));
    }
    bench.showEmpty(el('div', {class: 'empty-state'}, parts));
}

/** Sets the mock scenario selector back to "The member ID decides", as a tester choosing a case from the table means. */
function letTheMemberIdDecide() {
    if (controls.scenario && controls.scenario.value() !== '') {
        controls.scenario.select.value = '';
        controls.scenario.select.dispatchEvent(new Event('change', {bubbles: true}));
    }
}

/* ---------- when the configuration changes ---------- */

function onConfigChange() {
    clear(slots.preview);
    bench.markStale();
    loadVocabulary();
}

export default {id: 'dtr', label: 'DTR', mount, onShow, onConfigChange};
