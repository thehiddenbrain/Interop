// Step 4, Status (plan 3.2, prototype 04b): after a submission, ask for the decision and follow
// notifications. One panel with three rows, Inquiry, Subscription and Notifications, each with the
// latest answer in a few words and one action; under the rows the latest inquiry or subscription
// result as the shared card, and the notifications the receiver holds, newest first, each folded.
//
// What the submission handed on (claim, the submission's run, its codes) goes with the inquiry while
// the case is the one it was made for; run on its own, the inquiry is sent from the case and the
// step's Claim id. A subscription call carries nothing of the case.
//
// The notification list is asked for every 5 s while this step is on screen and the window shows,
// and never otherwise; after the first answer only what arrived since the newest stamp is asked
// for, because a full answer carries every payload the receiver holds. An error stops the poll
// until "Check now". "Deliver a sample" exists on mock only: on live it is never built.

import {call} from './api.js';
import {el, chip, fold, button, fill, clear, firstSentence} from './dom.js';
import {faultReport} from './fault.js';
import {jsonView} from './json-view.js';
import {vocabulary} from './words.js';
import {showPreview} from './wb-preview.js';
import {panelHead, busyLine, sendRun, failurePanel} from './wb-run.js';
import {masksIn, fillFromAnswer} from './masks.js';

export const POLL_MILLIS = 5000;

// Notifications drawn at first, and how many more each "Show 20 more" adds: a receiver holds up to
// two hundred and a tester looks for one among them.
export const PAGE = 20;

/** The subscription calls of the select, each with its method, its operation and whether it sends a body. */
export const SUBSCRIPTION_CALLS = [
    {value: 'create', words: 'Create', method: 'POST', operation: 'SUBSCRIPTION_CREATE', body: true, needsId: false},
    {value: 'read', words: 'Read', method: 'GET', operation: 'SUBSCRIPTION_GET', body: false, needsId: true},
    {value: 'list', words: 'List', method: 'GET', operation: 'SUBSCRIPTION_GET', body: false, needsId: false},
    {value: 'update', words: 'Update', method: 'PUT', operation: 'SUBSCRIPTION_UPDATE', body: true, needsId: true},
    {value: 'delete', words: 'Delete', method: 'DELETE', operation: 'SUBSCRIPTION_DELETE', body: false, needsId: true}
];

/**
 * The inquiry's body, from the case, the step's options and what step 3 handed on. Only what an
 * inquiry reads is sent (the server refuses a field it does not know). carry: ctx.carry(4) or null;
 * submissionRunId: the run id of this case's latest submission, or null.
 */
export function inquiryBody(request, own, carry, submissionRunId) {
    const body = {
        member: request.member, memberOverrides: request.memberOverrides,
        provider: request.provider, providerOverrides: request.providerOverrides,
        procedureCodes: request.procedureCodes, chainId: request.chainId, configEpoch: request.configEpoch
    };
    if (request.dateOfService) {
        body.dateOfService = request.dateOfService;
    }
    if (own.claimId) {
        body.claimId = own.claimId;
    }
    if (carry) {
        const handed = {};
        for (const key of ['claimId', 'runId', 'procedureCodes']) {
            const values = Array.isArray(carry[key]) ? carry[key].filter(one => typeof one === 'string' && one !== '') : [];
            if (values.length > 0) {
                handed[key] = values;
            }
        }
        // The inquiry is about the submission itself, whatever run the submission followed from.
        if (submissionRunId) {
            handed.runId = [submissionRunId];
        }
        if (Object.keys(handed).length > 0) {
            body.carry = handed;
        }
    }
    sharedFacts(body, request, own, own.expectInquiry);
    return body;
}

/**
 * A subscription call: {method, path, body or query}. The subscription id goes in the address; the
 * run it follows from is the submission of this case, when there is one.
 */
export function subscriptionCall(which, request, own, submissionRunId) {
    const chosen = SUBSCRIPTION_CALLS.find(one => one.value === which) || SUBSCRIPTION_CALLS[0];
    const id = chosen.needsId ? own.subscriptionId : '';
    const path = '/api/pas/subscriptions' + (chosen.needsId && id ? '/' + encodeURIComponent(id) : '');
    const facts = {chainId: request.chainId, configEpoch: request.configEpoch};
    sharedFacts(facts, request, own, own.expectSubscription);
    if (chosen.body) {
        if (submissionRunId) {
            facts.carry = {runId: [submissionRunId]};
        }
        return {method: chosen.method, path, body: facts, chosen};
    }
    if (submissionRunId) {
        facts['carry.runId'] = submissionRunId;
    }
    return {method: chosen.method, path, query: facts, chosen};
}

/** The sample's body (mock only): the submission of this case, and a scenario only when one is chosen. */
export function sampleBody(own, submissionRunId) {
    const body = {};
    if (submissionRunId) {
        body.runId = submissionRunId;
    }
    if (own.mock && own.scenarioId) {
        body.scenarioId = own.scenarioId;
    }
    if (own.subscriptionId) {
        body.subscriptionId = own.subscriptionId;
    }
    return body;
}

function sharedFacts(target, request, own, expected) {
    if (expected) {
        target.expected = expected;
    }
    if (request.testerRef) {
        target.testerRef = request.testerRef;
    }
    if (request.note) {
        target.note = request.note;
    }
    // A scenario is chosen on mock only; the server refuses one on live.
    if (own.mock && own.scenarioId) {
        target.scenarioId = own.scenarioId;
    }
}

/** The short label and tone of an outcome code, from the vocabulary; null when the server gave none. */
export function shortOf(words, protocol, code) {
    const list = words && words.outcomes && Array.isArray(words.outcomes[protocol]) ? words.outcomes[protocol] : [];
    const found = list.find(one => one.code === code);
    return found && found.short ? {text: found.short, tone: found.tone || null} : null;
}

/** The button words of a next action, from the vocabulary; null when it has none. */
export function buttonOf(words, action) {
    const list = words && Array.isArray(words.nextActions) ? words.nextActions : [];
    const found = list.find(one => one.code === action);
    return found && found.button ? found.button : null;
}

/**
 * Fills step 4's Claim id from what step 3 handed on, only through the check for a mask: a masked
 * claim fills nothing and is named. With no claim handed on, a claim an earlier submission filled
 * goes with it, and one the tester typed stays. control: a caseField.
 */
export function fillClaimFrom(control, carry) {
    const value = carry && Array.isArray(carry.claimId) ? carry.claimId[0] : carry ? carry.claimId : undefined;
    if (value === undefined || value === null || value === '') {
        if (control.mark() === FROM_STEP_3) {
            control.clear();
        }
        return;
    }
    if (fillFromAnswer(control, value)) {
        control.set(control.input.value, FROM_STEP_3);
    } else {
        control.masked();
    }
}

const FROM_STEP_3 = 'from step 3';

/** HH:MM:SS of an instant, as the browser's clock shows it; '' when it cannot be read. */
export function clock(instant) {
    if (instant === null || instant === undefined) {
        return '';
    }
    const date = instant instanceof Date ? instant : new Date(instant);
    if (Number.isNaN(date.getTime())) {
        return '';
    }
    const two = n => (n < 10 ? '0' : '') + n;
    return two(date.getHours()) + ':' + two(date.getMinutes()) + ':' + two(date.getSeconds());
}

/** True when PAVE listens on this machine only, so no service elsewhere can call its receiver. */
export function receiverLocalOnly(mode) {
    const address = mode && typeof mode.bindAddress === 'string' ? mode.bindAddress.toLowerCase() : '';
    return address === 'localhost' || address.startsWith('127.') || address === '::1' || address === '[::1]' || address === '0:0:0:0:0:0:0:1';
}

/**
 * The notification poll, apart from the screen so its rules can be run: ask(since) asks the
 * receiver; onScreen() says whether step 4 is shown; onAnswer(answer) is given each answer. It asks
 * only while on screen, only for what arrived since the newest stamp, never twice at once, and
 * stops on an error until start() is called again.
 */
export function createPoll(spec) {
    let timer = null;
    let latest = null;
    let asking = false;
    let stopped = false;
    const stop = () => {
        if (timer !== null) {
            clearInterval(timer);
            timer = null;
        }
    };
    async function ask() {
        if (asking) {
            return null;
        }
        if (!spec.onScreen()) {
            stop();
            return null;
        }
        asking = true;
        let answer;
        try {
            answer = await spec.ask(latest);
        } finally {
            asking = false;
        }
        if (!answer || !answer.ok) {
            // Asking again every 5 s would repeat the same failure; "Check now" asks again.
            stopped = true;
            stop();
        } else if (answer.data && typeof answer.data.latest === 'string' && answer.data.latest !== '') {
            latest = answer.data.latest;
        }
        spec.onAnswer(answer);
        return answer;
    }
    return {
        /** Asks now and keeps asking every POLL_MILLIS while on screen. */
        start() {
            stopped = false;
            if (timer === null && spec.onScreen()) {
                timer = setInterval(ask, spec.every || POLL_MILLIS);
            }
            return ask();
        },
        stop,
        running: () => timer !== null,
        stopped: () => stopped,
        since: () => latest
    };
}

/** mount(root, ctx), as every step module: {show(how), hide(), caseChanged(), reset(), unmount(), redraw(words)}. */
export function mount(root, ctx) {
    let words = null;
    const latest = {inquiry: null, subscription: null};
    const when = {inquiry: null, subscription: null};
    let shownCard = null;

    // The step's own choices, in its Options row with the Claim id and, on mock, the scenario.
    const expectInquiry = el('select', {class: 'select select--expect', id: 'status-expect-inquiry'});
    const expectSubscription = el('select', {class: 'select select--expect', id: 'status-expect-subscription'});
    const optionsRow = ctx.optionsRow ? ctx.optionsRow() : null;
    if (optionsRow) {
        optionsRow.append(
            el('div', {class: 'field'}, el('label', {class: 'field__label', for: 'status-expect-inquiry'}, 'Expect from the inquiry'), expectInquiry),
            el('div', {class: 'field'}, el('label', {class: 'field__label', for: 'status-expect-subscription'}, 'Expect from a subscription call'),
                expectSubscription));
    }
    const toggle = ctx.optionsToggle ? ctx.optionsToggle() : null;

    const busy = busyLine();
    const handed = el('div', {class: 'handed-slot'});
    const readiness = el('div', {class: 'readiness'});
    const refusal = el('div', {class: 'step-refusal'});
    const result = el('div', {class: 'step-result'});

    // Inquiry.
    const inquiryLatest = el('div', {class: 'call__latest'});
    const inquiryPreview = button('Preview', () => previewInquiry());
    const inquirySend = button('Send inquiry', () => sendInquiry(), {primary: true});
    const inquiryRow = el('div', {class: 'call'}, el('span', {class: 'call__name'}, 'Inquiry'), inquiryLatest,
        el('div', {class: 'call__actions'}, inquiryPreview, inquirySend));

    // Subscription.
    const subscriptionLatest = el('div', {class: 'call__latest'});
    const subscriptionId = el('input', {class: 'input mono call__id', id: 'status-subscription-id', type: 'text', autocomplete: 'off',
        spellcheck: 'false', placeholder: 'Subscription id', 'aria-label': 'Subscription id'});
    const which = el('select', {class: 'select', id: 'status-subscription-call', 'aria-label': 'Subscription call'},
        SUBSCRIPTION_CALLS.map(one => el('option', {value: one.value}, one.words)));
    const subscriptionPreview = button('Preview', () => previewSubscription());
    const subscriptionSend = button('Send subscription call', () => sendSubscription());
    const subscriptionRow = el('div', {class: 'call'}, el('span', {class: 'call__name'}, 'Subscription'), subscriptionLatest,
        el('div', {class: 'call__actions'}, subscriptionId, which, subscriptionPreview, subscriptionSend));

    // Notifications.
    const notificationLatest = el('div', {class: 'call__latest'});
    const checkNow = button('Check now', () => poll.start());
    const sampleSlot = el('span', {class: 'call__sample'});
    const notificationRow = el('div', {class: 'call'}, el('span', {class: 'call__name'}, 'Notifications'), notificationLatest,
        el('div', {class: 'call__actions'}, checkNow, sampleSlot));
    const receiverLine = el('div', {class: 'receiver-slot'});
    const notificationProblem = el('div', {class: 'notification-problem'});
    const list = el('ol', {class: 'notes-list'});
    const more = el('p', {hidden: true});

    const panelNodes = [
        panelHead({title: 'Status', tag: 'PAS', lede: 'Ask for the decision, and follow notifications.', id: 'step-title-4',
            controls: toggle ? [toggle] : []}),
        optionsRow, readiness, handed, el('div', {class: 'calls'}, inquiryRow, subscriptionRow, notificationRow), receiverLine, busy.node,
        notificationProblem, refusal, result, list, more
    ];
    root.append(...panelNodes.filter(Boolean));

    /* ---------- what goes with each call ---------- */

    function own() {
        const mock = ctx.isMock();
        return {
            claimId: ctx.option('claimId'),
            scenarioId: mock ? ctx.option('scenarioId') : '',
            mock,
            expectInquiry: expectInquiry.value,
            expectSubscription: expectSubscription.value,
            subscriptionId: String(subscriptionId.value || '').trim()
        };
    }

    /** The run id of this case's latest submission, while step 3 handed this step its carry. */
    function submissionRunId() {
        const carry = ctx.carry(4);
        if (!carry) {
            return null;
        }
        const submitted = ctx.latest(3);
        if (submitted && typeof submitted.runId === 'string' && submitted.runId !== '') {
            return submitted.runId;
        }
        const runs = Array.isArray(carry.runId) ? carry.runId.filter(one => typeof one === 'string' && one !== '') : [];
        return runs.length > 0 ? runs[0] : null;
    }

    /* ---------- the inquiry ---------- */

    async function sendInquiry() {
        await sendRun({
            path: '/api/pas/inquire', body: inquiryBody(ctx.request(), own(), ctx.carry(4), submissionRunId()), busy,
            busyWords: 'Asking for the decision...', buttons: [inquirySend, inquiryPreview], typed: ctx.typedCode(), ctx,
            onResult: envelope => arrived('inquiry', envelope),
            onRefused: (error, placed) => refused(error, placed),
            onFailed: error => fill(refusal, failurePanel(error))
        });
    }

    async function previewInquiry() {
        await showPreview('/api/pas/inquire/preview', inquiryBody(ctx.request(), own(), ctx.carry(4), submissionRunId()),
            error => ctx.placeErrors(error.fieldErrors || []));
    }

    /* ---------- the subscription ---------- */

    function chosenCall() {
        return SUBSCRIPTION_CALLS.find(one => one.value === which.value) || SUBSCRIPTION_CALLS[0];
    }

    async function sendSubscription() {
        const chosen = chosenCall();
        if (chosen.needsId && String(subscriptionId.value || '').trim() === '') {
            fill(refusal, failurePanel({kind: 'VALIDATION', title: 'Type the subscription id to ' + chosen.words.toLowerCase() + '.'}));
            subscriptionId.focus();
            return;
        }
        const made = subscriptionCall(chosen.value, ctx.request(), own(), submissionRunId());
        await sendRun({
            method: made.method, path: made.path, body: made.body, query: made.query, sendsCase: false, busy,
            busyWords: 'Sending the subscription call...', buttons: [subscriptionSend, subscriptionPreview], typed: '', ctx,
            onResult: envelope => arrived('subscription', envelope),
            onRefused: (error, placed) => refused(error, placed),
            onFailed: error => fill(refusal, failurePanel(error))
        });
    }

    async function previewSubscription() {
        const chosen = chosenCall();
        const made = subscriptionCall(chosen.value, ctx.request(), own(), submissionRunId());
        const path = chosen.value === 'update' ? made.path + '/preview' : '/api/pas/subscriptions/preview';
        await showPreview(path, made.body, error => ctx.placeErrors(error.fieldErrors || []));
    }

    /** Preview exists for the calls that send a body; a read, a list and a delete send none. */
    function drawSubscriptionControls() {
        const chosen = chosenCall();
        subscriptionPreview.disabled = !chosen.body;
        subscriptionPreview.title = chosen.body ? '' : 'A ' + chosen.words.toLowerCase() + ' sends no body, so there is nothing to preview.';
        subscriptionId.hidden = !chosen.needsId;
        const blocked = ctx.blocked ? ctx.blocked(chosen.operation) : null;
        subscriptionSend.disabled = Boolean(blocked);
        subscriptionSend.title = blocked || '';
    }
    which.addEventListener('change', drawSubscriptionControls);

    /* ---------- results ---------- */

    function arrived(kind, envelope) {
        clear(refusal);
        latest[kind] = envelope;
        when[kind] = new Date();
        shownCard = kind;
        ctx.resulted(envelope);
        // A create hands on the id the service gave; it fills the field only when the field is empty
        // and the id holds no mask.
        const carry = envelope && envelope.nextStep && envelope.nextStep.carry;
        const ids = carry && Array.isArray(carry.subscriptionId) ? carry.subscriptionId : [];
        if (kind === 'subscription' && ids.length > 0 && typeof ids[0] === 'string' && String(subscriptionId.value || '').trim() === ''
            && masksIn(ids[0]) === null) {
            subscriptionId.value = ids[0];
        }
        draw();
    }

    function refused(error, placed) {
        ctx.refused(4, error);
        const all = (error.fieldErrors || []).every(each => placed.includes(each.field));
        fill(refusal, all && !error.title ? null : failurePanel(error, placed));
    }

    /** A row's latest answer: its short chip, the headline's first sentence, its time as a link to Audit. */
    function latestLine(envelope, at) {
        if (!envelope) {
            const notRun = words && words.stepStates && words.stepStates.NOT_RUN ? words.stepStates.NOT_RUN.text : null;
            return notRun ? [chip(notRun, {dashed: true})] : [];
        }
        const verdict = envelope.verdict || {};
        const short = shortOf(words, envelope.protocol, verdict.outcomeCode);
        const headline = firstSentence(verdict.headline || '')[0];
        const recorded = envelope.ledger && envelope.ledger.recorded !== false && typeof envelope.runId === 'string';
        return [
            short ? chip(short.text, {tone: short.tone}) : null,
            el('span', {class: 'call__text', title: verdict.headline || null}, headline),
            recorded ? el('a', {class: 'call__when', href: '#audit/runs/' + encodeURIComponent(envelope.runId), title: 'Open in Audit'}, clock(at))
                : el('span', {class: 'call__when'}, clock(at))
        ];
    }

    function draw() {
        fill(inquiryLatest, ...latestLine(latest.inquiry, when.inquiry));
        fill(subscriptionLatest, ...latestLine(latest.subscription, when.subscription));
        const envelope = latest[shownCard];
        fill(result, envelope ? ctx.card(envelope) : '');
        fill(handed, ctx.handedLine ? ctx.handedLine(4) : '');
        drawReadiness();
        drawSubscriptionControls();
        drawNotificationLatest();
    }

    function drawReadiness() {
        const blocked = ctx.blocked ? ctx.blocked('PAS_INQUIRE') : null;
        inquirySend.disabled = Boolean(blocked);
        inquirySend.title = blocked || '';
        // One line in the panel says why, as step 1 does; the subscription's own reason is on its button.
        fill(readiness, blocked && ctx.readinessLine ? ctx.readinessLine(blocked) : '');
    }

    function drawWords() {
        const fillExpect = (select, protocol) => {
            const before = select.value;
            const outcomes = words && words.outcomes && Array.isArray(words.outcomes[protocol]) ? words.outcomes[protocol] : [];
            fill(select, el('option', {value: ''}, 'Not set'),
                outcomes.filter(one => one.short).map(one => el('option', {value: one.code}, one.short)));
            select.value = before;
        };
        fillExpect(expectInquiry, 'PAS');
        fillExpect(expectSubscription, 'SUBSCRIPTION');
        drawSample();
        redrawNotifications();
    }

    /* ---------- notifications ---------- */

    const held = [];
    const rows = new Map();
    let limit = PAGE;
    let counts = null;

    const poll = createPoll({
        ask: since => call('GET', '/api/pas/notifications', {query: since ? {since} : undefined}),
        onScreen: () => ctx.visible() && !(typeof document !== 'undefined' && document.hidden),
        onAnswer: answer => took(answer)
    });

    function took(answer) {
        if (!answer.ok) {
            fill(notificationProblem, el('p', {class: 'small'}, 'The receiver could not be asked what arrived. Check now asks again.'),
                failurePanel(answer.error));
            return;
        }
        clear(notificationProblem);
        const got = answer.data || {};
        counts = got;
        const fresh = Array.isArray(got.notifications) ? got.notifications : [];
        // Newest first: what arrived since is newer than everything on screen.
        for (const item of fresh.slice().reverse()) {
            if (item && typeof item.id === 'string' && !rows.has(item.id)) {
                held.unshift(item);
                rows.set(item.id, rowFor(item));
            }
        }
        // What the receiver no longer holds is not shown as if it did; its ledger line remains.
        while (typeof got.held === 'number' && held.length > got.held) {
            const gone = held.pop();
            rows.delete(gone.id);
        }
        drawList();
        drawNotificationLatest();
    }

    function drawList() {
        fill(list, held.slice(0, limit).map(item => rows.get(item.id)));
        fill(more, held.length > limit ? button('Show ' + PAGE + ' more', () => {
            limit += PAGE;
            drawList();
        }, {small: true}) : null);
        more.hidden = held.length <= limit;
    }

    function redrawNotifications() {
        for (const item of held) {
            rows.set(item.id, rowFor(item));
        }
        drawList();
        drawNotificationLatest();
    }

    function drawNotificationLatest() {
        const newest = held[0];
        if (!newest) {
            const received = counts && typeof counts.received === 'number' ? counts.received : 0;
            fill(notificationLatest, counts === null ? null
                : el('span', {class: 'call__text'}, received > 0 ? received + ' arrived; none held now.' : 'Nothing has arrived.'));
            return;
        }
        const verdict = newest.verdict || {};
        const short = shortOf(words, 'NOTIFICATION', verdict.outcomeCode);
        fill(notificationLatest,
            short ? chip(short.text, {tone: short.tone}) : null,
            el('span', {class: 'call__text', title: verdict.headline || null}, firstSentence(verdict.headline || '')[0]),
            el('a', {class: 'call__when', href: '#audit/runs/' + encodeURIComponent(newest.id), title: 'Open in Audit'}, clock(newest.receivedAt)));
    }

    /** One notification, folded: its short label, when, and the headline; opened, what the server said of it. */
    function rowFor(item) {
        const verdict = item.verdict || {};
        const step = item.nextStep || {};
        const short = shortOf(words, 'NOTIFICATION', verdict.outcomeCode);
        const summary = [short ? chip(short.text, {tone: short.tone}) : null, ' ', clock(item.receivedAt), ' ',
            el('span', null, firstSentence(verdict.headline || '')[0])];
        const body = () => {
            const claims = step.carry && Array.isArray(step.carry.claimId) ? step.carry.claimId.filter(Boolean) : [];
            const inquire = step.action === 'CHECK_STATUS' && claims.length > 0 && buttonOf(words, 'CHECK_STATUS')
                ? button(buttonOf(words, 'CHECK_STATUS'), () => inquireFor(step.carry), {small: true, primary: true}) : null;
            const supporting = Array.isArray(verdict.supporting) ? verdict.supporting : [];
            return el('div', {class: 'note-open'},
                el('p', null, verdict.headline || ''),
                verdict.maskedReading ? el('p', {class: 'masked-carry'}, verdict.maskedReading) : null,
                supporting.length > 0 ? el('ul', {class: 'notes'}, supporting.map(line => el('li', null, line))) : null,
                step.headline ? el('p', null, el('strong', null, step.headline), step.reason ? ' ' + step.reason : '') : null,
                inquire,
                item.correlatedRunId ? el('p', {class: 'small'}, 'About ', el('a', {href: '#audit/runs/' + encodeURIComponent(item.correlatedRunId)},
                    item.correlatedRunId)) : null,
                item.faultReference ? faultReport(item.faultReference, item.faultCause) : null,
                fold({summary: 'Payload', hint: (typeof item.bytesReceived === 'number' ? item.bytesReceived + ' bytes' : null),
                    body: () => (typeof item.payload === 'string' ? jsonView(item.payload, {label: 'Notification payload', copyLabel: 'Copy payload'})
                        : el('p', {class: 'small muted'}, 'No payload is held.'))}));
        };
        return el('li', {class: 'note-item'}, fold({summary, body}));
    }

    /** "Send inquiry" on a notification: asks about the request it names, sent from the case as well. */
    async function inquireFor(carry) {
        // The claim is the notification's own, so the step's Claim id is not sent with it.
        const body = inquiryBody(ctx.request(), {...own(), claimId: ''}, carry, null);
        await sendRun({
            path: '/api/pas/inquire', body, busy, busyWords: 'Asking for the decision...', buttons: [inquirySend, inquiryPreview],
            typed: ctx.typedCode(), ctx,
            onResult: envelope => arrived('inquiry', envelope),
            onRefused: (error, placed) => refused(error, placed),
            onFailed: error => fill(refusal, failurePanel(error))
        });
    }

    /** "Deliver a sample" is built on mock only, with the vocabulary's words; on live it does not exist. */
    function drawSample() {
        const words2 = buttonOf(words, 'DELIVER_SAMPLE_NOTIFICATION');
        if (!ctx.isMock() || !words2) {
            clear(sampleSlot);
            return;
        }
        if (sampleSlot.firstChild && sampleSlot.firstChild.textContent === words2) {
            sampleReady(sampleSlot.firstChild);
            return;
        }
        const deliver = button(words2, () => deliverSample(deliver));
        fill(sampleSlot, deliver);
        sampleReady(deliver);
    }

    async function deliverSample(deliver) {
        if (!ctx.isMock() || deliver.disabled) {
            return;
        }
        deliver.disabled = true;
        let answer;
        try {
            answer = await call('POST', '/api/pas/notifications/sample', {body: sampleBody(own(), submissionRunId())});
        } finally {
            sampleReady(deliver);
        }
        if (answer.ok) {
            clear(refusal);
            await poll.start();
        } else {
            fill(refusal, failurePanel(answer.error));
        }
    }

    /** A sample is about this case's submission, or a scenario the tester chose; with neither there is nothing to deliver. */
    function sampleReady(deliver) {
        const ready = Boolean(submissionRunId()) || Boolean(own().scenarioId);
        deliver.disabled = !ready;
        deliver.title = ready ? '' : 'Send a submission in step 3 first, or choose a Mock scenario in Options.';
    }

    function drawReceiver() {
        const mode = typeof ctx.mode === 'function' ? ctx.mode() : null;
        fill(receiverLine, receiverLocalOnly(mode) && mode.bindNote
            ? el('p', {class: 'small muted receiver__line', title: mode.bindNote}, firstSentence(mode.bindNote)[0]) : null);
    }

    // A window brought back to the front starts the poll again when this step is the one on screen.
    const onVisibility = () => {
        if (!document.hidden && ctx.visible() && !poll.running() && !poll.stopped()) {
            poll.start();
        }
    };
    if (typeof document !== 'undefined' && typeof document.addEventListener === 'function') {
        document.addEventListener('visibilitychange', onVisibility);
    }

    vocabulary().then(answer => {
        words = answer;
        drawWords();
        draw();
    }, () => null);
    drawReceiver();
    draw();

    return {
        show(how) {
            drawReceiver();
            drawSample();
            draw();
            poll.start();
            // "Send inquiry" from another step's result opens this step and sends the inquiry: a read.
            if (how && how.start && !inquirySend.disabled) {
                sendInquiry();
            } else if (how && how.sample && sampleSlot.firstChild) {
                deliverSample(sampleSlot.firstChild);
            }
        },
        hide() {
            poll.stop();
        },
        /**
         * An inquiry or a subscription call read back after a reload: its row shows it with the
         * time the ledger gave it, and the card under the rows shows the later of the two.
         */
        restored(envelope, how) {
            const kind = envelope && envelope.operation === 'PAS_INQUIRE' ? 'inquiry'
                : envelope && String(envelope.operation || '').startsWith('SUBSCRIPTION_') ? 'subscription' : null;
            if (!kind) {
                return;
            }
            const at = how && typeof how.at === 'string' ? new Date(how.at) : null;
            latest[kind] = envelope;
            when[kind] = at && !Number.isNaN(at.getTime()) ? at : null;
            const other = kind === 'inquiry' ? 'subscription' : 'inquiry';
            if (!shownCard || !when[other] || (when[kind] && when[kind] >= when[other])) {
                shownCard = kind;
            }
            draw();
        },
        caseChanged() {
            fill(handed, ctx.handedLine ? ctx.handedLine(4) : '');
            drawSample();
        },
        /** Start a new case: the answers of the case before go; the receiver's list is not the case's, and stays. */
        reset() {
            latest.inquiry = null;
            latest.subscription = null;
            when.inquiry = null;
            when.subscription = null;
            shownCard = null;
            subscriptionId.value = '';
            clear(refusal);
            draw();
        },
        unmount() {
            poll.stop();
            if (typeof document !== 'undefined' && typeof document.removeEventListener === 'function') {
                document.removeEventListener('visibilitychange', onVisibility);
            }
            busy.stop();
        },
        redraw(fresh) {
            if (fresh) {
                words = fresh;
            }
            drawWords();
            drawReceiver();
            draw();
        }
    };
}
