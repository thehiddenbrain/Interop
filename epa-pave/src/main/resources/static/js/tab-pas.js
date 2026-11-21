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
import {el, fill, clear, button, code, aboutStep} from './dom.js';
import {textField, selectField, formProblem, showFormProblem, refusedSaying, markFieldErrors, clearFieldErrors, LEDGER_TEXT_HINT} from './forms.js';
import {createWorkbench, requestPreviewPanel, scenarioTable, scenarioListNotes, scenarioAbout, scenarioOverride, scenarioOverrideNote,
    scenarioDefaultNotes, CODE_PICKER_ABOUT, handedOnLead, sinceLoaded, anotherCaseFold} from './workbench.js';
import {detailFor} from './pas-detail.js';
import {createExpectation} from './pas-expectation.js';
import {createAttachments} from './pas-attachments.js';
import {createHandover} from './pas-handover.js';
import {createNotifications} from './pas-notifications.js';
import {answersInRequest, responsesInBody} from './pas-answers.js';
import {runLink} from './history-common.js';
import {bringIntoPane} from './case-context.js';
import {fillFromAnswer, withoutMasks} from './masks.js';

const MODES = [
    {id: 'submit', label: 'Send PAS request', use: 'submit'},
    {id: 'inquire', label: 'Send inquiry', use: 'inquire'},
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

// The request the payer asked documentation for, while the tester prepares to send it again from this tab.
let resubmitting = null;

// The run and the operation of the result on screen, so a notification about that run, or a call
// chosen that did not make it, can be said at its top. Null while the readout holds no result.
let shownRunId = null;
let shownOperation = null;

const controls = {};
const sections = {};
const slots = {};

function mount(target, application) {
    app = application;
    panel = target;
    bench = createWorkbench(app, panel, {
        moreOptions: true,
        formLabel: 'The case and the PAS request',
        readoutLabel: 'The readout: the request, notifications and the result',
        onSubmit: () => primaryAction(),
        // What the call chosen sends. The notification receiver sends nothing, so it is always offered,
        // and the statement that withholds the form says so, with the way to it.
        operations: () => ({submit: ['PAS_SUBMIT', 'PAS_INQUIRE'], inquire: ['PAS_INQUIRE'], subscriptions: ['SUBSCRIPTION_CREATE']}[mode] || []),
        readinessWays: () => el('p', null, 'The notification receiver needs no service: ',
            button('Choose Notifications', () => showMode('notifications'), {class: 'button--text'}), '.')
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
    bench.actions.append(controls.writes, controls.expectation.node, controls.modeSaid, controls.buttons);
    // A refusal can name many fields; at the top of the scrolling form it takes no height from the pinned bar.
    bench.scroll.prepend(bench.problem);

    controls.sectionSlot = el('div', {class: 'pas-section'});
    // The call's own fields (the claim identifier an inquiry needs among them) are never folded;
    // the scenario, the tester reference and the note are optional and go in More options.
    bench.beforeOptions.append(controls.sectionSlot);
    bench.own.append(controls.common);
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
    controls.list = button('List subscriptions', () => subscriptionCall('list'), {class: 'button--quiet button--small'});
    controls.read = button('Read subscription', () => subscriptionCall('read'), {class: 'button--quiet button--small'});
    controls.update = button('Update subscription', () => subscriptionCall('update'), {class: 'button--quiet button--small'});
    controls.updatePreview = button('Show the update request', () => showPreview('update'), {class: 'button--quiet button--small'});
    controls.remove = button('Delete subscription', () => subscriptionCall('delete'), {class: 'button--quiet button--small'});
    fill(controls.subscriptionButtons, controls.read, controls.update, controls.remove, controls.updatePreview);
    fill(controls.listButton, controls.list);
    bench.track(controls.submit, controls.preview, controls.inquire, controls.inquirePreview, controls.create, controls.createPreview,
        controls.list, controls.read, controls.update, controls.updatePreview, controls.remove);

    ownRequest = bench.useOwnRequest({protocol: 'PAS', sendButton: controls.submit, sendLabel: 'Send PAS request',
        modeChanged: () => { if (!bench.hasResult()) { drawEmptyState(); } }});
    controls.runValues = bench.useRunValues({operation: () => ({submit: 'pas-submit', inquire: 'pas-inquire', subscriptions: 'subscription-create'}[mode] || null)});

    notifications = createNotifications({
        app, panel,
        vocabulary: () => vocabulary,
        inquire: (carry, item) => inquireFrom({claimId: first(carry.claimId), runId: first(carry.runId), codes: [],
            carry: pick(carry, ['claimId', 'runId']), why: first(carry.runId)
                ? ['the notification ', runLink(item.id), ' about the submission ', runLink(first(carry.runId))] : ['the notification ', runLink(item.id)]}),
        lastRunId: () => (following ? following.runId : null),
        showSubscriptions: () => {
            showMode('subscriptions');
            controls.create.focus();
        },
        tick: () => drawWrites()
    });
    sections.notifications.append(notifications.sampleNode);

    slots.preview = bench.slot('preview');
    slots.notifications = bench.slot('notifications');
    slots.notifications.append(notifications.node);
    // An arrival is announced in the call chooser, where it is seen whichever call is chosen.
    controls.modeChoice.after(notifications.statusNode);
    notifications.onArrived(markNewer);

    app.caseContext.onChange(() => { controls.handover.redraw(); drawFollowing(); });
    showMode('submit');
    drawEmptyState();
    loadVocabulary();
}

function onShow() {
    bench.showCase();
    drawOverrideNote();
    takeHandover();
    drawWrites();
    notifications.start();
    if (!bench.hasResult()) {
        // A hand-over that arrived while the tab was hidden changes what the empty readout says.
        drawEmptyState();
    }
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
    if (slots.notifications) {
        // The receiver's list is read while its call is chosen; above any other result it pushed the verdict down.
        slots.notifications.hidden = id !== 'notifications';
    }
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
        controls.modeSaid.textContent = 'A request of your own applies to a submission only. The text you pasted stays in its box for when you go back to Send PAS request.';
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
        + 'procedure code, so the case is not shown. Choose Send PAS request or Send inquiry to see it again.' : '';
    controls.expectation.node.hidden = id === 'notifications';
    const buttons = {
        submit: [controls.submit, controls.preview],
        inquire: [controls.inquire, controls.inquirePreview],
        // A read, an update and a delete act on the id typed above, and sit with it. Only the create stays with the send button.
        subscriptions: [controls.create, controls.createPreview],
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
    markOther();
    // Coming back to a call, the readout keeps the scroll it had on the call left; a mark at the top of the result is shown.
    if (readoutResult().querySelector('.result__newer, .result__other')) {
        scrollReadoutTop();
    }
    const go = readoutResult().querySelector('.result__newer-go');
    if (go) {
        go.hidden = id === 'notifications';
    }
    bench.checkReadiness();
}

// The operations each call makes, to tell whether the result on screen is of the call chosen.
const OPERATIONS_OF_MODE = {
    submit: ['PAS_SUBMIT'],
    inquire: ['PAS_INQUIRE'],
    subscriptions: ['SUBSCRIPTION_CREATE', 'SUBSCRIPTION_GET', 'SUBSCRIPTION_UPDATE', 'SUBSCRIPTION_DELETE'],
    notifications: []
};

/**
 * Marks the result on screen when it is of another call than the one chosen, and takes the mark
 * away when a call that matches it is chosen again. The result is kept: the tester may be reading it.
 */
function markOther() {
    const shown = readoutResult().querySelector('.result');
    if (!shown) {
        return;
    }
    const other = shownOperation !== null && !(OPERATIONS_OF_MODE[mode] || []).includes(shownOperation);
    const mark = shown.querySelector('.result__other');
    if (other && !mark) {
        shown.prepend(el('p', {class: 'result__other'},
            'This result is of another call than the one now chosen. Sending the chosen call replaces it.'));
        // The readout keeps where it was scrolled on the call left, so the mark at the top of the result is brought into view.
        scrollReadoutTop();
    } else if (!other && mark) {
        mark.remove();
    }
}

/**
 * A notification about the run on screen gives the next step now, so the result says its own next
 * step came before it, in the tool's words, and points to the notification. Once per result.
 */
function markNewer(items) {
    const shown = readoutResult().querySelector('.result');
    if (!shown || !shownRunId || shown.querySelector('.result__newer')) {
        return;
    }
    if (!items.some(item => item.correlatedRunId === shownRunId)) {
        return;
    }
    // On the Notifications call itself the button would offer the call already chosen, so it is left out there.
    shown.prepend(el('p', {class: 'result__newer'},
        'A notification about this run arrived after this result. The next step below was given before it; the notification gives the next step now. ',
        button('Choose the Notifications call', () => showMode('notifications'), {class: 'button--text result__newer-go', hidden: mode === 'notifications'})));
    scrollReadoutTop();
}

/** Puts the readout back at its top, where a mark just added to the result is. */
function scrollReadoutTop() {
    const readout = readoutResult().closest('.readout');
    if (readout) {
        readout.scrollTop = 0;
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
        hint: 'Blank: the tool creates one for this submission, or uses the one an earlier step handed on. An inquiry and a notification are matched by it.'});
    controls.date = textField({label: 'Date of service', field: 'dateOfService', placeholder: 'YYYY-MM-DD'});
    controls.diagnoses = textField({label: 'Diagnosis codes', field: 'diagnosisCodes', literal: true, placeholder: 'such as G47.33',
        hint: 'Separate codes with a comma or a space. Optional.'});
    controls.attachments = createAttachments({changed: () => {}});
    // Said when the payer asked for documentation and the tester chose to send again from here: the claim identifier is reused.
    controls.resubmit = el('div', {class: 'pas-resubmit', hidden: true, aria: {live: 'polite'}});
    controls.claimId.input.addEventListener('input', drawResubmit);
    sections.submit = el('div', {class: 'pas-section__body'},
        controls.handover.node,
        el('div', {class: 'field-grid'}, controls.claimId.node, controls.date.node, controls.diagnoses.node),
        controls.resubmit,
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
    // What the hand-over held when the request left, so the result says what went with it even if the tester changes it afterwards.
    const answers = controls.handover.answersState();
    const answer = await call(() => api.postJson('/api/pas/submit', submitRequest()), {saying: 'Sending the PAS request.', control: controls.submit, result: true});
    if (answer) {
        showRun(answer.body);
        showAnswersSent(answer.body, answers);
    }
}

/**
 * Puts the questionnaire answers the request body carried on the result, just above the wire view
 * that holds the body itself. Shown when DTR handed answers on or the body holds some; a submission
 * that had nothing to do with DTR gets no section about it.
 */
function showAnswersSent(envelope, answers) {
    const request = envelope.wire && envelope.wire.request ? envelope.wire.request : null;
    const wire = bench.form.closest('.workbench').querySelector('.readout__result details.wire');
    if (!request || !wire) {
        return;
    }
    if (answers.handed === 0 && responsesInBody(request.body).found.length === 0) {
        return;
    }
    wire.closest('.result__section').before(el('section', {class: 'result__section'},
        answersInRequest({body: request.body, when: request.sent ? 'sent' : 'built', handed: answers.handed, withheld: answers.withheld})));
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
    showMode('inquire');
    // A claim identifier an answer gave masked fills nothing and sends nothing: the line beside the field says why.
    if (!fillFromAnswer(controls.inquiryClaimId, source.claimId || '')) {
        return;
    }
    following = {...source, carry: withoutMasks(source.carry).carry};
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
    const answer = await call(() => api.postJson('/api/pas/inquire', inquiryRequest()), {saying: 'Sending the inquiry.', control: controls.inquire, result: true});
    if (answer) {
        showRun(answer.body);
    }
}

/* ---------- subscriptions ---------- */

function buildSubscriptions() {
    controls.subscriptionId = textField({label: 'Subscription id', field: 'subscriptionId', literal: true, wide: true,
        hint: 'For the read, update and delete below. Filled from the latest create on this tab. A create and a list need none.'});
    // A value a subscription is built from is a variable of the environment. One can be given for the runs from this page
    // through the workbench's values for one run: naming it here opens its field there.
    controls.variableName = textField({label: 'Give a variable a value for this run', field: 'pas.variableName', literal: true, wide: true,
        placeholder: 'such as subscriptionHeartbeatSeconds',
        hint: 'The notes on "Show the create request" name each variable the subscription is built from.',
        beside: button('Add its field', () => addRunValue(), {class: 'button--quiet button--small'})});
    controls.variableName.input.addEventListener('keydown', event => {
        if (event.key === 'Enter' && !event.isComposing) {
            event.preventDefault();
            addRunValue();
        }
    });
    controls.subscriptionButtons = el('div', {class: 'button-row pas-subscription-calls'});
    controls.listButton = el('div', {class: 'button-row'});
    sections.subscriptions = el('div', {class: 'pas-section__body'},
        el('div', {class: 'field-grid'}, controls.subscriptionId.node),
        controls.subscriptionButtons,
        controls.listButton,
        el('p', {class: 'small'}, 'What a subscription subscribes to, how much a notification carries and how often a heartbeat is wanted are '
            + 'values of the active environment, not fields here. "Show the create request" lists each value that will be used and where it comes from.'),
        el('div', {class: 'field-grid'}, controls.variableName.node));
    sections.notifications = el('div', {class: 'pas-section__body'},
        el('p', {class: 'small'}, 'The notifications the receiver has taken in are listed under Notifications received, newest first, and refreshed every few seconds while this tab is open.'));
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

/** Opens a field among the values for one run for the variable named, with what the configured request says of it. */
async function addRunValue() {
    const name = controls.variableName.value().replace(/^\{\{|\}\}$/g, '');
    if (name === '') {
        controls.variableName.input.focus();
        return;
    }
    await controls.runValues.need([{field: 'variables.' + name}]);
    controls.variableName.set('');
    const field = bench.form.querySelector('[data-field="variables.' + CSS.escape(name) + '"]');
    if (field) {
        bringIntoPane(field);
        field.querySelector('input').focus({preventScroll: true});
    }
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
        // Marks left by an earlier refusal go first, as bench.call does: the submit fields are hidden in this
        // mode, and a summary that mirrors the marks would otherwise list them once the id is typed.
        clearFieldErrors(panel);
        bench.clearRefusal();
        bench.say(refusedSaying(said.fieldErrors.length));
        const placed = [];
        showFormProblem(bench.problem, formProblem(said, markFieldErrors([panel], said.fieldErrors, {placed}), placed));
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
    // A subscription call carries nothing of the case, so text left in its code box does not hold it up.
    const answer = await call(send, {saying: words, control, sendsCase: false, result: true});
    if (answer) {
        showRun(answer.body);
    }
}

/* ---------- shared fields: scenario, tester reference, note ---------- */

function buildCommon() {
    controls.scenarioSlot = el('div', {class: 'field--wide'});
    controls.testerRef = textField({label: 'Tester reference', field: 'testerRef', placeholder: 'ticket or initials', hint: LEDGER_TEXT_HINT});
    controls.note = textField({label: 'Note', field: 'note', wide: true, hint: LEDGER_TEXT_HINT});
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
        drawOverrideNote();
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
        fill(about, chosen ? scenarioAbout(chosen) : mode === 'subscriptions'
            ? 'With none chosen, the mock answers from its default scenario for subscription calls.'
            : 'With none chosen here, the member ID picks the scenario.');
    };
    field.select.addEventListener('change', describe);
    field.select.addEventListener('change', drawOverrideNote);
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
    drawOverrideNote();
}

/**
 * Says under the Member ID field, while this tab is on screen, that a mock scenario chosen by hand
 * answers in place of the one the member ID would pick. Only the tab on screen writes it: the case
 * is one node, moved to whichever protocol tab is open.
 */
function drawOverrideNote() {
    if (bench) {
        // The selector may have been drawn again with no event, so the fold's summary is said again too.
        bench.optionsChanged();
    }
    if (!bench || !bench.form.contains(app.caseContext.node)) {
        return;
    }
    const chosen = controls.scenario ? scenarioOverride(mode === 'subscriptions' ? null : vocabulary.byUse.get(useOf(mode)), controls.scenario.value()) : null;
    app.caseContext.identifierNote('member', chosen ? scenarioOverrideNote(chosen, 'under More options', letTheMemberIdDecide) : null);
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
    notifications.redraw();
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
    drawWrites();
}

/* ---------- writes on production ---------- */

// Whether a write would be refused, from /api/mode as the frame last read it. The tab offers no
// unlock of its own: the Config tab is the one place where writes are unlocked. Whether the call
// on screen can reach a service at all is the workbench's to say: with nowhere to send to, it
// withholds the form and says what is missing, in the server's words (spec.operations).
function drawWrites() {
    drawWriteBox();
}

function drawWriteBox() {
    const current = app.mode();
    const access = current ? current.writeAccess || {} : {};
    if (!WRITE_MODES.includes(mode) || !current) {
        clear(controls.writes);
        return;
    }
    const refused = mode === 'submit' ? 'Sending is refused and recorded. '
        : 'A create, an update or a delete is refused and recorded; a read and a list are not. ';
    // This box sits with the send button, which stays in view on a laptop screen only while the box is short. The reason
    // in full is the production banner's, at the top of every tab, and is kept as the title here.
    if (access.writesAllowed === false && !access.production) {
        // Refused for a reason other than production, such as no environment being active on the live profile. No banner says it, so it is said here.
        fill(controls.writes, el('div', {class: 'pas-writes__blocked'},
            el('p', null, el('strong', null, 'Writes are refused. '), access.reason || '', ' ',
                button('Open the Config tab', () => app.navigate('config'), {class: 'button--text'}))));
        return;
    }
    if (access.writesAllowed === false) {
        fill(controls.writes, el('div', {class: 'pas-writes__blocked', title: access.reason || ''},
            el('p', null, el('strong', null, 'Writes are blocked. '), refused,
                // Where an owner has locked the installation there is no unlock to go to: the banner above says so, and no way is offered.
                access.floorAllows === false ? 'The banner above says why.'
                    : ['Unlock them on the Config tab. ', button('Open the Config tab', () => app.navigate('config'), {class: 'button--text'})])));
        return;
    }
    if (access.production) {
        fill(controls.writes, el('div', {class: 'pas-writes__open', title: access.reason || ''},
            el('p', null, el('strong', null, 'Production, writes unlocked for this session. '),
                // On the mock profile nothing leaves this machine, so the sentence about a real request is kept for the live profile.
                app.isMock() ? 'On the mock profile a scenario file answers and nothing leaves this machine.'
                    : mode === 'submit' ? 'A PAS request sent now creates a real prior authorization request against a real member.'
                        : 'Creating, updating or deleting a subscription now changes the real service.')));
        return;
    }
    clear(controls.writes);
}

/* ---------- the result, and the request before it is sent ---------- */

function showRun(envelope) {
    clear(slots.preview);
    if (envelope.operation === 'PAS_SUBMIT' && resubmitting) {
        // The request that was being prepared has gone. What it said no longer describes the form.
        resubmitting = null;
        drawResubmit();
    }
    const step = envelope.nextStep || {};
    const carry = step.carry || {};
    const hasClaim = carry.claimId && carry.claimId.length > 0;
    shownRunId = envelope.runId || null;
    shownOperation = envelope.operation || null;
    bench.showResult(envelope, {detailTitle: envelope.protocol === 'SUBSCRIPTION' ? 'Subscription detail' : 'PAS response detail',
        detail: detailFor(vocabulary),
        // A submission or an inquiry is a case of its own; the subscription calls and notifications have no case table.
        anotherCase: () => mode === 'submit' || mode === 'inquire'
            ? anotherCaseFold(app, vocabulary.byUse.get(useOf(mode)), bench.ownRequestActive(), letTheMemberIdDecide) : null,
        // Asking for the status and sending a submission again are this tab's own steps. The shared
        // control never offers to carry a PAS result into the PAS tab.
        nextStepControl: shownStep => {
            if (shownStep.action === 'CHECK_STATUS' && hasClaim) {
                return {label: 'Send inquiry', run: () => inquireFrom({claimId: first(carry.claimId), runId: first(carry.runId) || envelope.runId,
                    codes: carry.procedureCodes || [], carry: pick(carry, ['claimId', 'runId', 'procedureCodes']), why: ['the result of run ', runLink(envelope.runId)]})};
            }
            // On mock the server's step after a create points to the sample on the Notifications call; this is the way there.
            if (envelope.operation === 'SUBSCRIPTION_CREATE' && envelope.answeredBy === 'SCENARIO' && shownStep.action === 'START_PAS') {
                return {label: 'Choose the Notifications call', run: () => showMode('notifications')};
            }
            if (shownStep.action === 'START_PAS' && envelope.protocol === 'PAS') {
                return {label: 'Add the documentation and send again', run: () => resubmit(envelope, shownStep)};
            }
            return null;
        }});
    if (envelope.operation === 'PAS_SUBMIT' && carry.claimId && carry.claimId.length > 0) {
        following = {claimId: first(carry.claimId), runId: first(carry.runId) || envelope.runId, codes: carry.procedureCodes || [],
            carry: pick(carry, ['claimId', 'runId', 'procedureCodes']), why: ['the submission ', runLink(envelope.runId)]};
        if (!fillFromAnswer(controls.inquiryClaimId, following.claimId)) {
            following = null;
        }
        notifications.submitted();
    }
    if (envelope.protocol === 'SUBSCRIPTION' && envelope.operation === 'SUBSCRIPTION_CREATE' && envelope.detail && envelope.detail.subscriptionId) {
        fillFromAnswer(controls.subscriptionId, envelope.detail.subscriptionId);
    }
    const row = readoutResult().querySelector('.nextstep__row');
    // After a fault the server's step is to check the environment, and whether to ask for the status first it says in words.
    // No inquiry is sent from here on the browser's own account; the Send inquiry call is filled from this submission, and the way to it is offered.
    // On mock the fault was played: the way to the inquiry is said as part of the rehearsal, as the step above it is.
    const played = envelope.answeredBy === 'SCENARIO';
    if (row && offersInquiryWay(envelope, step, hasClaim)) {
        const choose = button('Choose Send inquiry', () => {
            showMode('inquire');
            controls.inquiryClaimId.input.focus();
        }, {class: 'button--text'});
        // On mock the server's reason already gives both ways on in one line, the inquiry second, so the way to it ends that line.
        const reason = played ? row.closest('.nextstep').querySelector('.nextstep__reason') : null;
        if (reason) {
            reason.append(' It is filled in the form with the claim identifier of this submission. ', choose);
        } else {
            row.closest('.nextstep').append(el('p', {class: 'small pas-nextstep-way'},
                played ? 'Had this been a real service, Send inquiry would check whether the payer has it. It is filled in the form with the '
                    + 'claim identifier of this submission, to rehearse it. '
                    : 'Send inquiry, in the form, is already filled with the claim identifier of this submission. ', choose));
        }
    }
    drawFollowing();
    markOther();
}

/**
 * Whether the result of a submission offers the way to Send inquiry for it: after a fault, where the
 * server's step is to check the environment and hands on the claim identifier. The server hands on
 * none after a submission that cannot have reached the service, played or live, since nothing was
 * submitted; a connection that broke after it opened may have delivered it, and keeps the way. The
 * outcome alone cannot tell the two apart: both are UNREACHABLE.
 */
export function offersInquiryWay(envelope, step, hasClaim) {
    return step.action === 'CHECK_ENVIRONMENT' && envelope.operation === 'PAS_SUBMIT' && !!hasClaim;
}

/** The part of the readout that holds the result. */
function readoutResult() {
    return bench.form.closest('.workbench').querySelector('.readout__result');
}

/**
 * Sends one call through the workbench. When nothing came back to show, the control that was
 * pressed takes the focus back: it was disabled while the call was on its way, which drops the
 * focus to the page. A refusal of a form has already put it on the first marked field.
 */
async function call(send, options) {
    const answer = await bench.call(send, options);
    const now = document.activeElement;
    if (!answer && options.control && (!now || now === document.body || now.disabled)) {
        options.control.focus({preventScroll: true});
    }
    return answer;
}

/**
 * The payer asked for documentation. The shared button would carry this result back into the
 * same tab under the name of the tab, and the next send would repeat the submission without a
 * word about it. Here it says what it does: it opens the attachment fields of the submission,
 * with the claim identifier of that request in its field, and the form says plainly that the
 * next send reuses it, beside the server's own next step.
 */
function resubmit(envelope, step) {
    const claimId = first((step.carry || {}).claimId);
    if (mode !== 'submit') {
        showMode('submit');
    }
    resubmitting = {claimId, runId: envelope.runId, headline: step.headline};
    if (claimId) {
        fillFromAnswer(controls.claimId, claimId);
    }
    drawResubmit();
    const field = controls.attachments.firstField();
    // The sentence about the claim identifier and the first attachment field are read together, so the sentence goes to the top of the pane.
    const pane = controls.resubmit.closest('.case-pane__scroll');
    if (pane && pane.scrollHeight > pane.clientHeight) {
        pane.scrollTop += controls.resubmit.getBoundingClientRect().top - pane.getBoundingClientRect().top - 8;
        // The field takes the keyboard, so it must be seen whole even when that pushes the top of the sentence out of view.
        const below = field ? field.closest('[data-field]').getBoundingClientRect().bottom - pane.getBoundingClientRect().bottom + 8 : 0;
        if (below > 0) {
            pane.scrollTop += below;
        }
    } else {
        bringIntoPane(controls.resubmit);
    }
    if (field) {
        field.focus({preventScroll: true});
    }
}

function drawResubmit() {
    if (!controls.resubmit) {
        return;
    }
    if (!resubmitting) {
        controls.resubmit.hidden = true;
        clear(controls.resubmit);
        return;
    }
    const reused = resubmitting.claimId && controls.claimId.value() === resubmitting.claimId;
    controls.resubmit.hidden = false;
    fill(controls.resubmit,
        el('p', null, el('strong', null, 'Sending again after run '), runLink(resubmitting.runId), el('strong', null, ', where the payer asked for documentation.')),
        reused
            ? el('p', null, 'The claim identifier field holds that request\'s identifier, ', code(resubmitting.claimId),
                ', so "Send PAS request" sends a new request with the same claim identifier. Clear the field to have the tool create a new one.')
            : el('p', null, 'The claim identifier field no longer holds that request\'s identifier, so this send is not tied to it.'),
        el('p', {class: 'small'}, 'The next step of that run says: ', el('q', null, resubmitting.headline)),
        el('p', null, button('Stop resubmitting run ' + resubmitting.runId, () => { resubmitting = null; drawResubmit(); }, {class: 'button--text'})));
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
    const answer = await call(send, {saying: 'Building the request. Nothing is sent.', control, preview: true, sendsCase: kind === 'submit' || kind === 'inquire'});
    if (answer) {
        const shownPanel = requestPreviewPanel(answer.body, () => clear(slots.preview));
        if (kind === 'submit') {
            // The answers are read out of the body the preview shows, and set above it, so a tester sees them before the long text.
            const answers = controls.handover.answersState();
            if (answers.handed > 0 || responsesInBody(answer.body.body).found.length > 0) {
                const section = answersInRequest({body: answer.body.body, when: 'preview', handed: answers.handed, withheld: answers.withheld});
                const bodyPart = shownPanel.querySelector('.raw-body');
                if (bodyPart) {
                    bodyPart.before(section);
                } else {
                    shownPanel.append(section);
                }
            }
        }
        fill(slots.preview, shownPanel);
        bench.say('The request is shown. Nothing was sent.');
        const title = shownPanel.querySelector('h2');
        if (title) {
            title.setAttribute('tabindex', '-1');
            title.focus({preventScroll: true});
        }
    }
}

/* ---------- before the first run ---------- */

/** The server's label of an outcome, or the code itself while the list is not read. */
function labelOf(code) {
    return (vocabulary.outcomes.get(code) || {}).label || code;
}

function drawEmptyState() {
    if (!bench) {
        return;
    }
    const own = bench.ownRequestActive();
    const handed = mode === 'submit' && !own ? controls.handover.current() : null;
    const parts = [handed
        ? handedOnLead(handed, 'Handed on from ' + (handed.fromProtocol || 'an earlier step'), 'Send PAS request', () => controls.submit.click())
        // On the Subscriptions call the newest run is a subscription run, not a PAS submit or inquiry.
        : mode === 'subscriptions' ? sinceLoaded('subscription', 'Nothing has been sent from this tab since the page was loaded.', labelOf, 'subscription')
            : sinceLoaded('PAS', 'Nothing has been sent from this tab since the page was loaded.', labelOf)];
    // With a case handed on, the lead above says what to do.
    if (mode === 'submit' && !handed) {
        parts.push(el('p', null, own
            ? 'Paste the request body in the form, then choose "Send PAS request". The member, provider and codes on the form are not sent with it.'
            : 'Fill the member ID, the NPI and a procedure code in the form, then choose "Send PAS request".'));
    } else if (mode === 'inquire') {
        parts.push(el('p', null, 'An inquiry asks for the status of a submitted request. Send a submission first and the inquiry follows it, '
            + 'or type the claim identifier of the request to ask about. The member and the provider of the case in the form go with it.'));
    } else if (mode === 'subscriptions') {
        parts.push(el('p', null, 'A subscription asks the service to call this tool back when a decision changes. Create one, list what the service holds, '
            + 'or read, update or delete one by its id.'));
    } else if (mode === 'notifications') {
        parts.push(el('p', null, 'Notifications appear in the list above as the receiver takes them in.'));
    }
    const notes = vocabulary.operations.filter(item => (mode === 'submit' && item.operation === 'PAS_SUBMIT')
        || (mode === 'inquire' && item.operation === 'PAS_INQUIRE')
        || (mode === 'subscriptions' && item.protocol === 'SUBSCRIPTION' && item.operation !== 'SUBSCRIPTION_NOTIFICATION'));
    // On live this says what is missing before a send, so it stays in view. On mock nothing is sent
    // and there is nothing to fix, so it is kept for whoever asks, in About this step.
    const whereTheyGo = notes.length === 0 ? null : el('div', null, el('p', {class: 'sub-heading'}, 'Where these calls go'),
        el('ul', {class: 'note-list'}, notes.map(item => el('li', null, el('strong', null, item.label + '. '), item.unavailable || item.note || 'Configured.'))),
        app.isMock() ? null : el('p', null, button('Open the Config tab', () => app.navigate('config'), {class: 'button--text'})));
    if (whereTheyGo && !app.isMock()) {
        parts.push(whereTheyGo);
    }
    const list = vocabulary.byUse.get(useOf(mode));
    if (mode !== 'subscriptions' && mode !== 'notifications' && app.isMock() && list && list.available && list.scenarios.length > 0) {
        const pick = [el('p', null, own ? 'On mock, a member ID from this table inside your body picks the answer. Copy one:'
            : 'On mock, each member ID picks the answer. Pick a case:'), scenarioListNotes(list), scenarioTable(app, list, {own, onUse: letTheMemberIdDecide})];
        // With a case handed on, a case from the table would replace it, so the table is folded away.
        parts.push(handed ? el('details', {class: 'fold'}, el('summary', null, 'Start another case on mock (' + list.scenarios.length + ')'), pick) : pick);
    }
    const listed = mode !== 'subscriptions' && mode !== 'notifications' && app.isMock() && list && list.available && list.scenarios.length > 0;
    parts.push(aboutStep(
        mode === 'submit' && !own ? el('p', null, '"Show the request" shows the bundle first and lists what it leaves out. The answer appears here: '
            + 'what the payer decided for each item, the authorization number, whether it matched what you expected, and what to do next.') : null,
        mode === 'submit' || mode === 'inquire' ? el('p', null, CODE_PICKER_ABOUT) : null,
        listed ? scenarioDefaultNotes(list) : null,
        app.isMock() ? whereTheyGo : null));
    shownRunId = null;
    shownOperation = null;
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
    // The request shown was built for the configuration that is gone. A refusal stays: it is usually the answer that said so.
    clear(slots.preview);
    bench.markStale();
    notifications.drawReceiver();
    drawWrites();
    loadVocabulary();
}

/** Writes were unlocked or locked again with no change of the configuration: only the write box says so. */
function onWriteAccessChange() {
    drawWrites();
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

export default {id: 'pas', label: 'PAS', mount, onShow, onConfigChange, onWriteAccessChange};

