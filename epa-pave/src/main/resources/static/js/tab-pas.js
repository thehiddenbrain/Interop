// The PAS tab: submit a prior authorization request, follow it when it is pended, and manage the
// subscriptions and notifications that bring a later decision.
//
// A submission is the step of the journey that can create a real prior authorization, so the
// tab says before sending what will go, where, and whether writes are locked, and afterwards what
// the payer decided for each item. The panes, the sending and the handling of every kind of
// answer come from the workbench; the result is drawn by the shared result view with the PAS
// detail in it. What is PAS's own is here: the four kinds of call, the hand-over from CRD and DTR,
// the inquiry that follows a submission, and the subscriptions.
//
// One kind of call is on screen at a time, and only its fields are in the form: a refusal names
// fields such as claimId, and it must land on the field of the call that was sent.

import * as api from './api.js';
import {el, fill, clear, button, code, table, copyButton} from './dom.js';
import {textField, selectField, formProblem, markFieldErrors} from './forms.js';
import {createWorkbench, requestPreviewPanel} from './workbench.js';
import {detailFor} from './pas-detail.js';
import {createExpectation} from './pas-expectation.js';
import {createAttachments} from './pas-attachments.js';
import {createHandover} from './pas-handover.js';
import {createNotifications} from './pas-notifications.js';
import {runLink} from './history-common.js';
import {bringIntoPane} from './case-context.js';

const MODES = [
    {id: 'submit', label: 'Submit', use: 'submit'},
    {id: 'inquire', label: 'Inquire', use: 'inquire'},
    {id: 'subscriptions', label: 'Subscriptions', use: 'subscription'},
    {id: 'notifications', label: 'Notifications', use: 'notification'}
];

const WRITE_MODES = ['submit', 'subscriptions'];

let app = null;
let bench = null;
let panel = null;
let mode = 'submit';
let ownRequest = null;
let notifications = null;

// What the scenarios endpoint serves, for each kind of call: the scenarios that answer it, the
// outcomes and the element names. Nothing of it is kept in this file.
const vocabulary = {outcomes: new Map(), byUse: new Map(), settings: null, operations: []};

// The latest submission on this tab: what an inquiry follows unless the tester types another claim id.
let following = null;

const controls = {};
const sections = {};
const slots = {};

function mount(target, application) {
    app = application;
    panel = target;
    bench = createWorkbench(app, panel, {
        formLabel: 'The case and the PAS request',
        readoutLabel: 'The readout: the request, notifications and the result',
        onSubmit: () => primaryAction()
    });

    buildModeChoice();
    buildSubmit();
    buildInquire();
    buildSubscriptions();
    buildCommon();

    controls.writes = el('div', {class: 'pas-writes', aria: {live: 'polite'}});
    controls.expectation = createExpectation();
    controls.buttons = el('div', {class: 'button-row pas-buttons'});
    controls.modeSaid = el('p', {class: 'small', role: 'status'});
    bench.actions.append(controls.writes, controls.expectation.node, bench.problem, controls.modeSaid, controls.buttons);

    controls.sectionSlot = el('div', {class: 'pas-section'});
    bench.own.append(controls.sectionSlot, controls.common);
    // Which call is made decides what the rest of the form means, so the choice comes before everything else in the pane.
    controls.noCase = el('p', {class: 'small pas-nocase', hidden: true});
    const scroll = bench.form.querySelector('.case-pane__scroll');
    scroll.prepend(el('div', {class: 'case-group pas-call'}, el('h2', {class: 'case-legend', id: 'pas-call-title'}, 'PAS call'), controls.modeChoice),
        controls.noCase);
    controls.caseSlot = scroll.querySelector('.case-slot');

    controls.submit = el('button', {type: 'submit', class: 'button button--primary'}, 'Send PAS request');
    controls.preview = button('Show the request', () => showPreview('submit'));
    controls.inquire = button('Send inquiry', () => sendInquiry(), {class: 'button--primary'});
    controls.inquirePreview = button('Show the inquiry', () => showPreview('inquire'));
    controls.create = button('Create subscription', () => subscriptionCall('create'), {class: 'button--primary'});
    controls.createPreview = button('Show the create request', () => showPreview('create'));
    controls.list = button('List subscriptions', () => subscriptionCall('list'));
    controls.read = button('Read subscription', () => subscriptionCall('read'));
    controls.update = button('Update subscription', () => subscriptionCall('update'));
    controls.updatePreview = button('Show the update request', () => showPreview('update'));
    controls.remove = button('Delete subscription', () => subscriptionCall('delete'));
    bench.track(controls.submit, controls.preview, controls.inquire, controls.inquirePreview, controls.create, controls.createPreview,
        controls.list, controls.read, controls.update, controls.updatePreview, controls.remove);

    ownRequest = bench.useOwnRequest({protocol: 'PAS', sendButton: controls.submit, sendLabel: 'Send PAS request',
        modeChanged: () => { if (!bench.hasResult()) { drawEmptyState(); } }});
    bench.useRunValues({operation: () => ({submit: 'pas-submit', inquire: 'pas-inquire', subscriptions: 'subscription-create'}[mode] || null)});

    notifications = createNotifications({
        app, panel,
        vocabulary: () => vocabulary,
        inquire: (carry, item) => inquireFrom({claimId: first(carry.claimId), runId: first(carry.runId), codes: [],
            carry: pick(carry, ['claimId', 'runId']), why: first(carry.runId)
                ? ['the notification ', runLink(item.id), ' about the submission ', runLink(first(carry.runId))] : ['the notification ', runLink(item.id)]}),
        lastRunId: () => (following ? following.runId : null),
        tick: () => drawWrites()
    });
    sections.notifications.append(notifications.sampleNode);

    slots.preview = bench.slot('preview');
    slots.notifications = bench.slot('notifications');
    slots.notifications.append(notifications.node);

    app.caseContext.onChange(() => { controls.handover.redraw(); drawFollowing(); });
    showMode('submit');
    drawEmptyState();
    loadVocabulary();
}

function onShow() {
    bench.showCase();
    takeHandover();
    drawWrites();
    notifications.start();
}

/* ---------- the four kinds of call ---------- */

function buildModeChoice() {
    controls.modeInputs = new Map();
    controls.modeChoice = el('div', {class: 'field field--inline pas-modes', role: 'radiogroup', aria: {labelledby: 'pas-call-title'}},
        MODES.map(item => {
            const input = el('input', {type: 'radio', name: 'pas-mode', id: 'pas-mode-' + item.id, value: item.id, checked: item.id === 'submit'});
            input.addEventListener('change', () => { if (input.checked) { showMode(item.id); } });
            controls.modeInputs.set(item.id, input);
            return el('label', {class: 'choice', htmlFor: input.id}, input, item.label);
        }));
}

function showMode(id) {
    const leavingOwn = mode === 'submit' && id !== 'submit' && ownRequest && ownRequest.active();
    mode = id;
    controls.modeInputs.get(id).checked = true;
    fill(controls.sectionSlot, sections[id]);
    clear(bench.problem);
    if (leavingOwn) {
        // A body of the tester's own is a submission. It is not sent as an inquiry or a subscription, so the built request is chosen again; the text stays in the box.
        const built = ownRequest.node.querySelector('input[value="built"]');
        if (built) {
            built.click();
        }
        controls.modeSaid.textContent = 'A request of your own applies to a submission only. The text you pasted stays in its box for when you go back to Submit.';
    } else {
        controls.modeSaid.textContent = '';
    }
    if (ownRequest) {
        ownRequest.node.hidden = id !== 'submit';
    }
    controls.common.hidden = id === 'notifications';
    // A subscription call and a notification carry no member, provider or code, so the case is put out of the way while one is chosen.
    const noCase = id === 'subscriptions' || id === 'notifications';
    if (controls.caseSlot) {
        controls.caseSlot.hidden = noCase;
    }
    controls.noCase.hidden = !noCase;
    controls.noCase.textContent = noCase ? 'A ' + (id === 'subscriptions' ? 'subscription call' : 'notification') + ' carries no member, provider or '
        + 'procedure code, so the case is not shown. Choose Submit or Inquire to see it again.' : '';
    controls.expectation.node.hidden = id === 'notifications';
    const buttons = {
        submit: [controls.submit, controls.preview],
        inquire: [controls.inquire, controls.inquirePreview],
        subscriptions: [el('div', {class: 'pas-buttons__group'}, controls.create, controls.createPreview, controls.list),
            el('div', {class: 'pas-buttons__group'}, controls.read, controls.update, controls.updatePreview, controls.remove)],
        notifications: []
    }[id];
    fill(controls.buttons, buttons);
    controls.buttons.hidden = buttons.length === 0;
    drawScenarioSelector();
    drawExpectationVocabulary();
    drawWrites();
    if (id === 'inquire') {
        drawFollowing();
    }
    if (notifications) {
        notifications.setOpen(id === 'notifications');
    }
    if (!bench.hasResult()) {
        drawEmptyState();
    }
}

/** The button a press of Enter would mean, if the form were ever submitted: the primary action of the call on screen. */
function primaryAction() {
    if (mode === 'submit') {
        sendSubmit();
    } else if (mode === 'inquire') {
        sendInquiry();
    }
}

/* ---------- submit ---------- */

function buildSubmit() {
    controls.handover = createHandover({caseNow: () => app.caseContext.summary(), changed: () => {}});
    controls.claimId = textField({label: 'Claim identifier', field: 'claimId', literal: true, wide: true,
        hint: 'Blank: the tool mints one for this submission, or uses the one an earlier step handed on. An inquiry and a notification are matched by it.'});
    controls.date = textField({label: 'Date of service', field: 'dateOfService', placeholder: 'YYYY-MM-DD'});
    controls.diagnoses = textField({label: 'Diagnosis codes', field: 'diagnosisCodes', literal: true, placeholder: 'such as G47.33',
        hint: 'Separate codes with a comma or a space. Optional.'});
    controls.attachments = createAttachments({changed: () => {}});
    sections.submit = el('div', {class: 'pas-section__body'},
        controls.handover.node,
        el('div', {class: 'field-grid'}, controls.claimId.node, controls.date.node, controls.diagnoses.node),
        controls.attachments.node);
}

function diagnosisCodes() {
    return controls.diagnoses.value().split(/[\s,]+/).filter(value => value !== '').map(value => ({code: value}));
}

function takeHandover() {
    const handed = app.carriedTo('pas');
    controls.handover.take(handed);
    if (!handed || handed === controls.lastHanded) {
        return;
    }
    controls.lastHanded = handed;
    // A step handed on to PAS. From a PAS result it is the same request going on: a submission after
    // documentation was asked for. The hand-over does not say which step it was for today; were it
    // to say the status is next, the request is followed with an inquiry, not submitted again.
    if (handed.fromProtocol === 'PAS' && handed.step === 'CHECK_STATUS') {
        inquireFrom({claimId: first((handed.carry || {}).claimId), runId: first((handed.carry || {}).runId),
            codes: (handed.carry || {}).procedureCodes || [], carry: handed.carry, why: ['the result of run ', runLink(handed.fromRunId)]}, false);
        return;
    }
    if (mode !== 'submit') {
        showMode('submit');
    }
    // What was handed on is read before sending, so it is brought into view below the case it belongs to.
    window.requestAnimationFrame(() => bringIntoPane(controls.handover.node));
}

function submitRequest() {
    const request = {...app.caseContext.request(), ...bench.requestFields()};
    const optional = {
        claimId: controls.claimId.value(),
        dateOfService: controls.date.value(),
        scenarioId: scenarioValue(),
        expected: controls.expectation.text(),
        testerRef: controls.testerRef.value(),
        note: controls.note.value(),
        chainId: app.chain.requestValue()
    };
    copyPresent(request, optional);
    const diagnoses = diagnosisCodes();
    if (diagnoses.length > 0) {
        request.diagnosisCodes = diagnoses;
    }
    const documents = controls.attachments.values();
    if (documents.length > 0) {
        request.attachments = documents;
    }
    const carry = controls.handover.carry();
    if (carry && Object.keys(carry).length > 0) {
        request.carry = carry;
    }
    request.configEpoch = app.epoch();
    return request;
}

async function sendSubmit() {
    const answer = await bench.call(() => api.postJson('/api/pas/submit', submitRequest()), {saying: 'Sending the PAS request.', control: controls.submit});
    if (answer) {
        showRun(answer.body);
    }
}

/* ---------- inquire ---------- */

function buildInquire() {
    controls.following = el('div', {class: 'pas-following', aria: {live: 'polite'}});
    controls.inquiryClaimId = textField({label: 'Claim identifier to ask about', field: 'claimId', literal: true, wide: true,
        hint: 'Filled from the latest submission on this tab. Type another to ask about a request this tab did not send.'});
    controls.inquiryClaimId.input.addEventListener('input', drawFollowing);
    sections.inquire = el('div', {class: 'pas-section__body'}, controls.following, el('div', {class: 'field-grid'}, controls.inquiryClaimId.node));
}

/** Makes the inquiry follow a request: the claim id and the run are carried, so the tester types nothing. */
function inquireFrom(source, send = true) {
    following = source;
    controls.inquiryClaimId.set(source.claimId || '');
    showMode('inquire');
    drawFollowing();
    if (send) {
        sendInquiry();
    }
}

/** True while the claim id on screen is the one of the request the inquiry follows. Typing another makes it an inquiry by hand. */
function stillFollowing() {
    return Boolean(following) && following.claimId === controls.inquiryClaimId.value();
}

function drawFollowing() {
    if (!controls.following) {
        return;
    }
    if (!following) {
        fill(controls.following, el('p', {class: 'small'}, 'No submission has been sent from this tab yet. Type the claim identifier of the request to ask about.'));
        return;
    }
    if (!stillFollowing()) {
        fill(controls.following, el('p', {class: 'small'}, 'An inquiry by hand: the claim identifier typed below is not the one of the latest submission (',
            code(following.claimId || 'none'), '). It is not linked to a run, and its items are the procedure codes of the case on screen.'));
        return;
    }
    const codes = following.codes || [];
    fill(controls.following, el('div', {class: 'small'},
        el('p', null, 'Follows ', following.why || ['run ', runLink(following.runId)], '. The claim identifier and the run are carried, so the answer is linked to that submission.'),
        el('p', null, 'Items: ', codes.length > 0 ? ['the codes the submission sent, in its order: ', codes.map((value, index) => [index > 0 ? ', ' : null, code(value)])]
            : 'the codes on the ledger line of that submission.')));
}

function inquiryRequest() {
    const request = {...app.caseContext.request(), ...bench.requestFields()};
    const claimId = controls.inquiryClaimId.value();
    if (stillFollowing()) {
        // The submission's own codes, in its order, tie each decision to its code. The case's codes may have changed since.
        request.procedureCodes = undefined;
        request.carry = pick(following.carry || {}, ['claimId', 'runId', 'procedureCodes']);
    }
    copyPresent(request, {
        claimId,
        dateOfService: controls.date.value(),
        scenarioId: scenarioValue(),
        expected: controls.expectation.text(),
        testerRef: controls.testerRef.value(),
        note: controls.note.value(),
        chainId: app.chain.requestValue()
    });
    request.configEpoch = app.epoch();
    return request;
}

async function sendInquiry() {
    const answer = await bench.call(() => api.postJson('/api/pas/inquire', inquiryRequest()), {saying: 'Sending the inquiry.', control: controls.inquire});
    if (answer) {
        showRun(answer.body);
    }
}

/* ---------- subscriptions ---------- */

function buildSubscriptions() {
    controls.subscriptionId = textField({label: 'Subscription id', field: 'subscriptionId', literal: true, wide: true,
        hint: 'For a read, an update or a delete. Filled from the latest create on this tab. A create and a list need none.'});
    sections.subscriptions = el('div', {class: 'pas-section__body'},
        el('div', {class: 'field-grid'}, controls.subscriptionId.node),
        el('p', {class: 'small'}, 'What a subscription subscribes to, how much a notification carries and how often a heartbeat is wanted are '
            + 'values of the active environment, not fields here. "Show the create request" lists each value that will be used and where it comes from.'));
    sections.notifications = el('div', {class: 'pas-section__body'},
        el('p', {class: 'small'}, 'The notifications the receiver has taken in are listed on the right, newest first, and refreshed every few seconds while this tab is open.'));
}

function subscriptionBody() {
    const request = {...bench.requestFields()};
    for (const name of ['member', 'memberOverrides', 'provider', 'providerOverrides', 'procedureCodes', 'rawBody']) {
        delete request[name];
    }
    copyPresent(request, {
        scenarioId: scenarioValue(),
        expected: controls.expectation.text(),
        testerRef: controls.testerRef.value(),
        note: controls.note.value(),
        chainId: app.chain.requestValue()
    });
    request.configEpoch = app.epoch();
    return request;
}

/** A read, a list and a delete carry no body, so what a body would carry goes as parameters, a value for one run as var.name. */
function subscriptionQuery() {
    const body = subscriptionBody();
    const parameters = new URLSearchParams();
    for (const [name, value] of Object.entries(body)) {
        if (name === 'variables') {
            for (const [variable, typed] of Object.entries(value || {})) {
                parameters.append('var.' + variable, typed);
            }
        } else if (value !== undefined && value !== null && value !== '') {
            parameters.append(name, String(value));
        }
    }
    const text = parameters.toString();
    return text === '' ? '' : '?' + text;
}

async function subscriptionCall(kind) {
    const id = controls.subscriptionId.value();
    const address = '/api/pas/subscriptions' + (id !== '' && kind !== 'create' && kind !== 'list' ? '/' + encodeURIComponent(id) : '');
    if ((kind === 'read' || kind === 'update' || kind === 'delete') && id === '') {
        // Without an id a read would be a list and an update or delete has no address. Nothing is asked of the server.
        const said = {title: 'There is no subscription id. Nothing was sent.', action: 'Enter the id of the subscription, or list the subscriptions to find it.',
            fieldErrors: [{field: 'subscriptionId', message: 'Enter the id of the subscription to ' + kind + '.'}]};
        bench.say(said.title);
        fill(bench.problem, formProblem(said, markFieldErrors([panel], said.fieldErrors)));
        return;
    }
    const send = {
        create: () => api.postJson(address, subscriptionBody()),
        list: () => api.get(address + subscriptionQuery()),
        read: () => api.get(address + subscriptionQuery()),
        update: () => api.putJson(address, subscriptionBody()),
        delete: () => api.del(address + subscriptionQuery())
    }[kind];
    const words = {create: 'Creating the subscription.', list: 'Listing the subscriptions.', read: 'Reading the subscription.',
        update: 'Updating the subscription.', delete: 'Deleting the subscription.'}[kind];
    const control = {create: controls.create, list: controls.list, read: controls.read, update: controls.update, delete: controls.remove}[kind];
    const answer = await bench.call(send, {saying: words, control});
    if (answer) {
        showRun(answer.body);
    }
}

/* ---------- shared fields: scenario, tester reference, note ---------- */

function buildCommon() {
    controls.scenarioSlot = el('div', {class: 'field--wide'});
    controls.testerRef = textField({label: 'Tester reference', field: 'testerRef', placeholder: 'ticket or initials'});
    controls.note = textField({label: 'Note', field: 'note', wide: true});
    controls.common = el('div', {class: 'field-grid pas-common'}, controls.scenarioSlot, controls.testerRef.node, controls.note.node);
}

function useOf(id) {
    return (MODES.find(item => item.id === id) || MODES[0]).use;
}

/**
 * The scenario selector exists on the mock profile only. On live it is not built at all: a
 * control that could force an outcome must not be one stylesheet rule away from a tester talking
 * to the real service. It offers only the scenarios that answer the call on screen.
 */
function drawScenarioSelector() {
    const keep = controls.scenario ? controls.scenario.value() : '';
    clear(controls.scenarioSlot);
    controls.scenario = null;
    const list = vocabulary.byUse.get(useOf(mode));
    if (!app.isMock() || !list || !list.available || mode === 'notifications') {
        controls.scenarioSlot.hidden = true;
        return;
    }
    controls.scenarioSlot.hidden = false;
    const scenarios = list.scenarios || [];
    const field = selectField({
        label: 'Mock scenario', field: 'scenarioId',
        options: [{value: '', label: mode === 'subscriptions' ? 'The default answer' : 'The member ID decides'}].concat(scenarios.map(item =>
            ({value: item.id, label: item.number + '. ' + item.label})))
    });
    const about = el('span', {class: 'field__hint', id: 'pas-scenario-about'});
    field.select.setAttribute('aria-describedby', 'pas-scenario-about');
    const describe = () => {
        const chosen = scenarios.find(item => item.id === field.value());
        fill(about, chosen ? chosen.description : mode === 'subscriptions'
            ? 'With none chosen, the mock answers from its default scenario for subscription calls.'
            : 'With none chosen here, the member ID picks the scenario.');
    };
    field.select.addEventListener('change', describe);
    if (scenarios.some(item => item.id === keep)) {
        field.set(keep);
    }
    describe();
    field.node.append(about);
    controls.scenario = field;
    controls.scenarioSlot.append(field.node);
    if (list.problems && list.problems.length > 0) {
        controls.scenarioSlot.append(el('ul', {class: 'note-list small'}, list.problems.map(problem => el('li', null, problem))));
    }
}

function scenarioValue() {
    return controls.scenario ? controls.scenario.value() : '';
}

function drawExpectationVocabulary() {
    const list = vocabulary.byUse.get(useOf(mode));
    if (!list) {
        return;
    }
    controls.expectation.setVocabulary(list.outcomes, list.elements);
}

/* ---------- the vocabulary and the configuration, all fetched ---------- */

async function loadVocabulary() {
    const uses = ['submit', 'inquire', 'subscription', 'notification'];
    const answers = await Promise.all([api.get('/api/pas/scenarios')].concat(uses.map(use => api.get('/api/pas/scenarios?for=' + use))));
    const [all, ...each] = answers;
    if (all.kind !== 'ok') {
        bench.refuse(all.body);
        return;
    }
    vocabulary.outcomes = new Map((all.body.outcomes || []).map(option => [option.code, option]));
    uses.forEach((use, index) => {
        if (each[index].kind === 'ok') {
            vocabulary.byUse.set(use, each[index].body);
        }
    });
    drawScenarioSelector();
    drawExpectationVocabulary();
    const forNotifications = vocabulary.byUse.get('notification');
    notifications.drawSample(forNotifications && forNotifications.available ? forNotifications.scenarios : []);
    await loadConfiguration();
    if (!bench.hasResult()) {
        drawEmptyState();
    }
}

async function loadConfiguration() {
    const answer = await api.get('/api/config/effective');
    if (answer.kind !== 'ok') {
        vocabulary.settings = null;
        vocabulary.operations = [];
        controls.attachments.setMode(null);
        return;
    }
    vocabulary.settings = answer.body.settings || [];
    vocabulary.operations = (answer.body.operations || []).filter(item => item.protocol === 'PAS' || item.protocol === 'SUBSCRIPTION');
    const setting = vocabulary.settings.find(item => item.property === 'onyx.pas.attachment-mode');
    controls.attachments.setMode(setting && setting.set && setting.value ? setting.value : null);
}

/* ---------- writes on production ---------- */

/**
 * Whether a write would be refused, from /api/mode as the frame last read it. The tab offers no
 * unlock of its own: the Config tab is the one place where writes are unlocked.
 */
function drawWrites() {
    const current = app.mode();
    const access = current ? current.writeAccess || {} : {};
    if (!WRITE_MODES.includes(mode) || !current) {
        clear(controls.writes);
        return;
    }
    const what = mode === 'submit' ? 'A PAS request' : 'Creating, updating or deleting a subscription';
    if (access.writesAllowed === false) {
        fill(controls.writes, el('div', {class: 'pas-writes__blocked'},
            el('p', null, el('strong', null, 'Writes are blocked here. '), what + ' sent now is refused by the production guardrail and recorded as blocked. ',
                access.reason || ''),
            el('p', null, 'Writes are unlocked on the Config tab, for this session only. ', button('Open the Config tab', () => app.navigate('config'), {class: 'button--text'}))));
        return;
    }
    if (access.production) {
        fill(controls.writes, el('div', {class: 'pas-writes__open'},
            el('p', null, el('strong', null, 'Production, with writes unlocked for this session. '),
                mode === 'submit' ? 'A PAS request sent now creates a real prior authorization request against a real member. ' : 'A subscription change now changes the real service. ',
                access.reason || '')));
        return;
    }
    clear(controls.writes);
}

/* ---------- the result, and the request before it is sent ---------- */

function showRun(envelope) {
    clear(slots.preview);
    bench.showResult(envelope, {detailTitle: envelope.protocol === 'SUBSCRIPTION' ? 'Subscription detail' : 'PAS response detail',
        detail: detailFor(vocabulary)});
    const step = envelope.nextStep || {};
    const carry = step.carry || {};
    if (envelope.operation === 'PAS_SUBMIT' && carry.claimId && carry.claimId.length > 0) {
        following = {claimId: first(carry.claimId), runId: first(carry.runId) || envelope.runId, codes: carry.procedureCodes || [],
            carry: pick(carry, ['claimId', 'runId', 'procedureCodes']), why: ['the submission ', runLink(envelope.runId)]};
        controls.inquiryClaimId.set(following.claimId);
        notifications.submitted();
    }
    if (envelope.protocol === 'SUBSCRIPTION' && envelope.operation === 'SUBSCRIPTION_CREATE' && envelope.detail && envelope.detail.subscriptionId) {
        controls.subscriptionId.set(envelope.detail.subscriptionId);
    }
    // The shared next step offers a button for the steps every tab knows. Asking for the status is this tab's own.
    const row = bench.form.closest('.workbench').querySelector('.readout__result .nextstep__row');
    if (step.action === 'CHECK_STATUS' && row && !row.querySelector('button') && carry.claimId && carry.claimId.length > 0) {
        row.append(button('Send inquiry', () => inquireFrom({claimId: first(carry.claimId), runId: first(carry.runId) || envelope.runId,
            codes: carry.procedureCodes || [], carry: pick(carry, ['claimId', 'runId', 'procedureCodes']), why: ['the result of run ', runLink(envelope.runId)]}),
        {class: 'button--primary'}));
    }
    drawFollowing();
}

async function showPreview(kind) {
    const id = controls.subscriptionId.value();
    const send = {
        submit: () => api.postJson('/api/pas/submit/preview', submitRequest()),
        inquire: () => api.postJson('/api/pas/inquire/preview', inquiryRequest()),
        create: () => api.postJson('/api/pas/subscriptions/preview', subscriptionBody()),
        update: () => api.postJson('/api/pas/subscriptions/' + encodeURIComponent(id === '' ? '-' : id) + '/preview', subscriptionBody())
    }[kind];
    const control = {submit: controls.preview, inquire: controls.inquirePreview, create: controls.createPreview, update: controls.updatePreview}[kind];
    const answer = await bench.call(send, {saying: 'Building the request. Nothing is sent.', control});
    if (answer) {
        fill(slots.preview, requestPreviewPanel(answer.body, () => clear(slots.preview)));
        bench.say('The request is shown. Nothing was sent.');
    }
}

/* ---------- before the first run ---------- */

function drawEmptyState() {
    if (!bench) {
        return;
    }
    const parts = [el('p', {class: 'empty-state__lead'}, 'Nothing has been sent from this tab yet.')];
    const own = bench.ownRequestActive();
    if (mode === 'submit') {
        parts.push(el('p', null, own
            ? 'Paste the request body on the left, then choose "Send PAS request". The member, provider and codes on the form are not sent with it.'
            : 'Enter a member ID, the ordering provider\'s NPI and at least one procedure code on the left, then choose "Send PAS request". '
                + '"Show the request" shows the bundle first and lists what it leaves out. The answer appears here: what the payer decided for each item, '
                + 'the authorization number, whether it matched what you expected, and what to do next.'));
    } else if (mode === 'inquire') {
        parts.push(el('p', null, 'An inquiry asks for the status of a submitted request. Send a submission first and the inquiry follows it, '
            + 'or type the claim identifier of the request to ask about. The member and the provider of the case on the left go with it.'));
    } else if (mode === 'subscriptions') {
        parts.push(el('p', null, 'A subscription asks the service to call this tool back when a decision changes. Create one, list what the service holds, '
            + 'or read, update or delete one by its id.'));
    } else {
        parts.push(el('p', null, 'Notifications appear in the list above as the receiver takes them in.'));
    }
    const notes = vocabulary.operations.filter(item => (mode === 'submit' && item.operation === 'PAS_SUBMIT')
        || (mode === 'inquire' && item.operation === 'PAS_INQUIRE')
        || (mode === 'subscriptions' && item.protocol === 'SUBSCRIPTION' && item.operation !== 'SUBSCRIPTION_NOTIFICATION'));
    if (notes.length > 0) {
        parts.push(el('div', null, el('p', {class: 'sub-heading'}, 'Where these calls go'),
            el('ul', {class: 'note-list'}, notes.map(item => el('li', null, el('strong', null, item.label + '. '), item.unavailable || item.note || 'Configured.'))),
            app.isMock() ? null : el('p', null, button('Open the Config tab', () => app.navigate('config'), {class: 'button--text'}))));
    }
    const list = vocabulary.byUse.get(useOf(mode));
    if (mode !== 'subscriptions' && mode !== 'notifications' && app.isMock() && list && list.available && list.scenarios.length > 0) {
        parts.push(el('p', null, own ? 'On the mock profile a sentinel member ID inside your body selects the scenario that answers. These are the member IDs that do:'
            : 'On the mock profile the member ID selects the scenario that answers. These are the member IDs that do:'));
        parts.push(table([{text: '', class: 'col-5'}, {text: 'Scenario', class: 'col-45'}, {text: 'Member ID', class: 'col-25'}, {text: '', class: 'col-25'}],
            list.scenarios.map(item => [
                {class: 'num', node: String(item.number)},
                item.label,
                item.sentinelMemberId ? code(item.sentinelMemberId) : el('span', {class: 'presence presence--none'}, 'none'),
                !item.sentinelMemberId ? null
                    : own ? copyButton('Copy this member ID', () => item.sentinelMemberId)
                        : button('Use this member ID', () => app.caseContext.setMemberId(item.sentinelMemberId),
                            {class: 'button--text', aria: {label: 'Use the member ID ' + item.sentinelMemberId}})
            ]), {class: 'sentinels'}));
    }
    bench.showEmpty(el('div', {class: 'empty-state'}, parts));
}

/* ---------- when the configuration changes ---------- */

function onConfigChange() {
    // The request shown was built for the configuration that is gone. A refusal stays: it is usually the answer that said so.
    clear(slots.preview);
    bench.markStale();
    notifications.drawReceiver();
    drawWrites();
    loadVocabulary();
}

/* ---------- small helpers ---------- */

function first(values) {
    return Array.isArray(values) && values.length > 0 ? values[0] : null;
}

/** The named keys of a carry, unchanged, leaving out the ones it does not hold. */
function pick(carry, keys) {
    const out = {};
    for (const key of keys) {
        if (Array.isArray(carry[key]) && carry[key].length > 0) {
            out[key] = carry[key];
        }
    }
    return out;
}

function copyPresent(target, values) {
    for (const [name, value] of Object.entries(values)) {
        if (value !== undefined && value !== null && value !== '') {
            target[name] = value;
        }
    }
}

export default {id: 'pas', label: 'PAS', mount, onShow, onConfigChange};

