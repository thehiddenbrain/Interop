// What the notification receiver has taken in, newest first, and where it listens.
//
// The list is asked for on a gentle poll while the PAS tab is on screen and never while it is
// not: each tick looks at whether the tab's panel is shown, and stops the poll when it is hidden.
// After the first answer only what arrived since the newest stamp on screen is asked for, because
// a full answer carries every payload the receiver holds.
//
// Each notification is drawn as the server words it: its verdict, its next step, which
// subscription it came on and which submission of this tool it was matched to, or that it could
// not be matched. Nothing here decides what a notification means. A notification whose next step
// is to ask for the status leads to an inquiry in one action.
//
// The receiver keeps payloads in memory only and a bounded number of them. The counts the server
// gives are shown with its own note, so a short list is never taken to mean nothing arrived.

import * as api from './api.js';
import {el, fill, clear, button, code, facts, table, heading, when, whenText, count, faultReport, withoutReference} from './dom.js';
import {shown, shownList, presenceMark} from './shown.js';
import {outcome, mockMark} from './marks.js';
import {additionalFieldsTable, issuesTable, maskedReading} from './result.js';
import {selectField, textField, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {runLink} from './history-common.js';
import {coded, claimResponseBlock, otherResourcesPart} from './pas-detail.js';

const POLL_MILLIS = 5000;

// How many notifications are drawn at first, and how many more each press of the button adds. A
// receiver holds up to two hundred, and a tester looks for one among them.
const PAGE = 20;

// The short mark a folded notification and the one-line summary carry when its outcome was read from a mask.
const MASKED_MARK = 'Read from a mask, not from what the service sent: open the notification to see which value.';

// The ways the list can be narrowed. Each reads a fact the server stated on the notification.
const VIEWS = [
    {value: 'all', label: 'Every notification held', test: () => true},
    {value: 'latest', label: 'About the latest submission on this tab', test: (item, lastRunId) => Boolean(lastRunId) && item.correlatedRunId === lastRunId},
    {value: 'matched', label: 'About a submission of this tool', test: item => Boolean(item.correlatedRunId)},
    {value: 'unmatched', label: 'About no submission of this tool', test: item => !item.correlatedRunId},
    {value: 'samples', label: 'Samples delivered on the mock profile', test: item => Boolean(item.sample)}
];

/**
 * spec.app             the page's app object
 * spec.panel           the PAS tab's panel, whose hidden state says whether the tab is on screen
 * spec.vocabulary()    {outcomes} as the scenarios endpoint served it
 * spec.inquire(carry, about)  sends an inquiry for the request a notification is about
 * spec.lastRunId()     the run id of the latest submission on this tab, or null
 * spec.showSubscriptions()  puts the Subscriptions call on screen, where a subscription is created
 */
export function createNotifications(spec) {
    let timer = null;
    let latest = null;
    let loaded = false;
    let failed = null;
    let counts = null;
    let scenarios = [];
    let fillRun = () => {};
    const shownItems = [];
    // The row drawn for each notification, and which of them the tester has opened. Both outlive a redraw of the view.
    const rows = new Map();
    const openIds = new Set();
    let limit = PAGE;
    let autoOpened = null;

    const status = el('span', {class: 'pas-notifications__status', role: 'status', aria: {live: 'polite'}});
    const heldCount = el('span', {class: 'section-heading__count'});
    const receiver = el('div', {class: 'pas-receiver'});
    const countsLine = el('p', {class: 'small quiet'});
    const list = el('ol', {class: 'pas-notifications__list'});
    const view = selectField({label: 'Show', field: 'notifications.view', options: VIEWS.map(item => ({value: item.value, label: item.label}))});
    view.node.classList.add('pas-notifications__view');
    view.select.addEventListener('change', () => { limit = PAGE; applyView(); });
    const showing = el('p', {class: 'small quiet', aria: {live: 'polite'}});
    const moreButton = button('Show ' + PAGE + ' more', () => { limit += PAGE; applyView(); }, {class: 'button--quiet button--small'});
    const moreRow = el('p', {hidden: true}, moreButton);
    // Its refusals are listed by its own summary, so the summary of the PAS form leaves them out.
    const sampleSlot = el('div', {class: 'pas-sample', dataset: {summary: 'own'}});
    const problem = el('div');
    const noSubscription = el('div', {aria: {live: 'polite'}});
    // One line while folded, so a long list does not push a result down. What arrives is announced in that line either way,
    // and the list is open while the Notifications call is chosen.
    const body = el('details', {class: 'pas-notifications__body'},
        el('summary', null,
            el('span', {class: 'pas-notifications__title', id: 'pas-notifications-title'}, 'Notifications received ', heldCount),
            status),
        el('div', {class: 'pas-notifications__inside'},
            el('p', null, button('Ask the receiver now', () => poll(), {class: 'button--quiet button--small'})),
            receiver, countsLine, view.node, showing, list, moreRow));
    const node = el('section', {class: 'pas-notifications', aria: {labelledby: 'pas-notifications-title'}}, body);

    /* ---------- where the receiver listens ---------- */

    async function drawReceiver() {
        const mode = spec.app.mode();
        const answer = await api.get('/api/config/effective');
        const settings = answer.kind === 'ok' ? answer.body.settings || [] : null;
        const callback = settings ? settings.find(setting => setting.property === 'onyx.subscription.callback-url') : null;
        const port = mode && typeof mode.port === 'number' ? String(mode.port) : null;
        const parts = [
            el('p', null, 'This tool receives notifications at ', code('POST /api/pas/notifications'),
                port ? [' on port ', code(port), ' of the machine it runs on'] : null, '.')
        ];
        // Whether anything on another machine can reach this tool is the server's to say, from
        // the address it listens on.
        if (mode && mode.bindNote) {
            parts.push(el('p', null, mode.bindNote));
        }
        if (settings === null) {
            parts.push(el('p', {class: 'small'}, 'The configuration could not be read, so whether a subscription names an address to call back is not known here. ',
                answer.body.title || ''));
        } else if (!callback || !callback.set || !callback.value) {
            parts.push(el('p', null,
                'The setting ', code('onyx.subscription.callback-url'), ' is blank, so a subscription this tool creates names no address '
                + 'to call back. Follow a pended request with an inquiry.',
                spec.app.isMock() ? ' On the mock profile a sample notification can be delivered from the Notifications call in the form.' : null));
        } else {
            parts.push(el('p', null, 'A subscription this tool creates asks the service to call back ', code(callback.value),
                ', from the setting ', code('onyx.subscription.callback-url'), '. If nothing arrives, follow the request with an inquiry.'));
        }
        fill(receiver, parts);
    }

    /* ---------- the poll ---------- */

    function onScreen() {
        return !spec.panel.hidden && !document.hidden;
    }

    function start() {
        if (timer === null) {
            timer = window.setInterval(tick, POLL_MILLIS);
        }
        drawReceiver();
        poll();
    }

    function tick() {
        if (!onScreen()) {
            // The tab is not on screen, so nobody is reading the list. It is asked for again when the tab is shown.
            window.clearInterval(timer);
            timer = null;
            return;
        }
        poll();
        if (spec.tick) {
            spec.tick();
        }
    }

    let asking = false;
    async function poll() {
        if (asking) {
            return;
        }
        asking = true;
        let answer;
        try {
            answer = await api.get('/api/pas/notifications' + (latest ? '?since=' + encodeURIComponent(latest) : ''));
        } finally {
            asking = false;
        }
        if (answer.kind !== 'ok') {
            if (!failed) {
                status.textContent = 'The receiver could not be asked what arrived. ' + answer.body.title;
            }
            failed = answer.body;
            return;
        }
        failed = null;
        const got = answer.body;
        counts = got;
        const arrived = got.notifications || [];
        if (got.latest) {
            latest = got.latest;
        }
        // The newest of what arrived is opened, so the one a tester is waiting for reads in full. The one opened that way
        // before it is folded again; the rest stay two lines each.
        if (arrived.length > 0) {
            const before = autoOpened ? rows.get(autoOpened) : null;
            if (before) {
                before.setOpen(false);
            }
            autoOpened = arrived[0].id;
            openIds.add(autoOpened);
        }
        // Newest first: what arrived since is newer than everything on screen, and goes above it.
        for (const item of arrived.slice().reverse()) {
            const row = rowFor(item, loaded);
            rows.set(item.id, row);
            shownItems.unshift(item);
            list.prepend(row);
        }
        if (loaded && arrived.length > 0) {
            // The summary stays one line, so a reading from a mask is only marked here; the opened notification names the value.
            const masked = arrived[0].verdict.maskedReading ? ' ' + MASKED_MARK : '';
            status.textContent = (arrived.length === 1 ? 'A notification arrived: ' + arrived[0].verdict.headline
                : arrived.length + ' notifications arrived. The newest: ' + arrived[0].verdict.headline) + masked;
        } else if (!loaded) {
            status.textContent = shownItems.length === 0 ? 'Nothing has arrived at the receiver since the tool started.'
                : 'The receiver holds ' + shownItems.length + (shownItems.length === 1 ? ' notification.' : ' notifications.');
        }
        // The receiver holds a bounded number, newest kept. What it no longer holds is not shown as if it did; its ledger line remains.
        while (typeof got.held === 'number' && shownItems.length > got.held) {
            const gone = shownItems.pop();
            const row = rows.get(gone.id);
            if (row) {
                row.remove();
            }
            rows.delete(gone.id);
            openIds.delete(gone.id);
        }
        loaded = true;
        drawCounts();
        applyView();
    }

    /** Shows the notifications the chosen view keeps, newest first, a page at a time. */
    function applyView() {
        const chosen = VIEWS.find(item => item.value === view.value()) || VIEWS[0];
        const lastRunId = spec.lastRunId();
        let matching = 0;
        for (const item of shownItems) {
            const row = rows.get(item.id);
            if (!row) {
                continue;
            }
            const keep = chosen.test(item, lastRunId);
            row.hidden = !keep || matching >= limit;
            if (keep) {
                matching += 1;
            }
        }
        moreRow.hidden = matching <= limit;
        if (shownItems.length === 0) {
            showing.textContent = '';
        } else if (chosen.value === 'latest' && !lastRunId) {
            showing.textContent = 'No submission has been sent from this tab yet, so none is about the latest one.';
        } else if (matching === 0) {
            showing.textContent = 'None of the ' + count(shownItems.length) + ' notifications here matches "' + chosen.label + '".';
        } else {
            showing.textContent = 'Showing ' + count(Math.min(matching, limit)) + ' of ' + count(matching)
                + (chosen.value === 'all' ? '' : ' that match') + ', newest first.';
        }
    }

    function drawCounts() {
        if (!counts) {
            return;
        }
        heldCount.textContent = '(' + count(counts.held) + ')';
        fill(countsLine, 'Held now ', el('strong', null, count(counts.held)), ' of at most ', count(counts.capacity),
            '. Arrived since the tool started ', el('strong', null, count(counts.received)), '.',
            counts.evicted > 0 ? [' ', el('strong', null, count(counts.evicted) + ' older ones are no longer held.'), ' Their ledger lines remain.'] : null,
            counts.note ? el('span', {class: 'pas-notifications__note'}, ' ' + counts.note) : null);
        if (shownItems.length === 0) {
            fill(list, el('li', {class: 'pas-notifications__empty'}, counts.received > 0
                ? 'None is held now, though ' + count(counts.received) + ' arrived since the tool started. The ledger lists them under History.'
                : 'Nothing has arrived. A notification shows here within a few seconds of arriving, without reloading.'));
        } else {
            const empty = list.querySelector('.pas-notifications__empty');
            if (empty) {
                empty.remove();
            }
        }
    }

    /* ---------- one notification ---------- */

    /**
     * One notification as one short line that can be read in a list of two hundred: when, what
     * the server calls it, which submission it is about, and the decision the ClaimResponse it
     * carries states, item by item. Opening it shows the server's verdict in full, its next step
     * and, further folded, everything the notification held.
     */
    function rowFor(item, fresh) {
        const verdict = item.verdict;
        const step = item.nextStep || {};
        const carry = step.carry || {};
        const known = spec.vocabulary().outcomes.get(verdict.outcomeCode);
        const tone = known ? known.tone : verdict.tone;
        const label = verdict.outcomeLabel || (known ? known.label : verdict.outcomeCode);
        const inquiry = step.action === 'CHECK_STATUS' && (carry.claimId || []).length > 0
            ? button('Send inquiry', () => spec.inquire(carry, item), {class: 'button--primary button--small', aria: {label: 'Send inquiry for the request this notification is about'}}) : null;
        const openId = 'pas-note-open-' + item.id;
        const toggle = el('button', {type: 'button', class: 'button button--quiet button--small pas-note__toggle',
            aria: {expanded: 'false', controls: openId}}, 'Open');
        // Two short lines: what the server calls it, then when it came and what it is about. The code is the server's too, set second.
        const line = el('div', {class: 'pas-note__line'},
            el('span', {class: 'pas-note__what'}, outcome(label, tone), ' ', el('span', {class: 'small quiet'}, code(verdict.outcomeCode)),
                // Folded, the row says only that its outcome was read from a mask; opened, the line under the headline says which value.
                verdict.maskedReading ? [' ', el('span', {class: 'masked-mark small'}, MASKED_MARK)] : null),
            toggle);
        const meta = el('p', {class: 'pas-note__meta small'},
            when(item.receivedAt),
            fresh ? el('span', {class: 'flag'}, 'new') : null,
            item.sample ? mockMark() : null,
            el('span', {class: 'pas-note__about'}, item.correlatedRunId ? ['about ', runLink(item.correlatedRunId)]
                : el('span', {class: 'presence presence--none'}, 'about no submission of this tool')));
        const decided = decisionLine(item);
        // A sample says on its own line why it is matched or not, and what to do about it. The tester must not have to open it to learn that.
        const sampleNotes = item.sample && (item.payloadNotes || []).length > 0
            ? el('ul', {class: 'note-list small pas-note__sample-notes'}, item.payloadNotes.map(note => el('li', null, note))) : null;
        const links = el('p', {class: 'small'},
            'Subscription ', item.subscriptionId ? code(item.subscriptionId) : el('span', {class: 'presence presence--none'}, 'none named'),
            item.subscriptionRunId ? [', created by ', runLink(item.subscriptionRunId)] : null,
            '. ',
            item.correlatedRunId ? ['About the submission ', runLink(item.correlatedRunId), item.claimId ? [' (claim ', code(item.claimId), ')'] : null, '.'] : null);
        const supporting = verdict.supporting || [];
        const fault = {reference: item.faultReference, cause: item.faultCause};
        const more = el('details', {class: 'fold pas-note__more'},
            el('summary', null, 'Everything in this notification', el('span', {class: 'quiet'}, count(item.bytesReceived) + ' bytes received')));
        let drawn = false;
        more.addEventListener('toggle', () => {
            if (more.open && !drawn) {
                drawn = true;
                more.append(everything(item));
            }
        });
        const opened = el('div', {class: 'pas-note__open', id: openId, hidden: true},
            el('p', {class: 'pas-note__headline'}, verdict.headline),
            maskedReading(verdict.maskedReading),
            supporting.length > 0 ? el('ul', {class: 'note-list small'}, supporting.map(text => el('li', null, withoutReference(text, fault)))) : null,
            // A failure of this tool while reading: its cause beside the reference, as the server log names it, and one copy of both.
            faultReport(fault),
            links,
            el('div', {class: 'pas-note__next'},
                el('p', null, el('strong', null, step.headline || 'No next step was stated.')),
                step.reason ? el('p', {class: 'small'}, step.reason) : null),
            more);
        const row = el('li', {class: 'pas-note' + (fresh ? ' pas-note--fresh' : ''), tabIndex: -1, aria: {label: 'Notification of ' + whenText(item.receivedAt)}},
            line, meta, decided, sampleNotes, inquiry ? el('p', null, inquiry) : null, opened);
        const setOpen = open => {
            opened.hidden = !open;
            toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
            toggle.textContent = open ? 'Fold' : 'Open';
            // Twenty buttons read "Open" in a list; each names the notification it belongs to.
            toggle.setAttribute('aria-label', (open ? 'Fold' : 'Open') + ' the notification received ' + whenText(item.receivedAt));
            if (open) {
                openIds.add(item.id);
            } else {
                openIds.delete(item.id);
            }
        };
        toggle.addEventListener('click', () => {
            setOpen(opened.hidden);
            // Once the tester has opened or folded it, it is theirs: a later arrival does not fold it.
            if (autoOpened === item.id) {
                autoOpened = null;
            }
        });
        setOpen(openIds.has(item.id));
        row.setOpen = setOpen;
        return row;
    }

    /**
     * The decision each ClaimResponse in the notification states, as the server set it for each
     * item: its outcome in its tone, the authorization number and the period. Nothing is read from
     * codes here; the outcome is the server's code, worded from the vocabulary.
     */
    function decisionLine(item) {
        const responses = item.claimResponses || [];
        if (responses.length === 0) {
            return null;
        }
        const decisions = responses.flatMap(response => response.decisions || []);
        if (decisions.length === 0) {
            return el('p', {class: 'pas-note__decision small'}, 'It carries a ClaimResponse that states no decision for any item.');
        }
        const outcomes = spec.vocabulary().outcomes;
        return el('ul', {class: 'pas-note__decision plain-list'}, decisions.map(decision => {
            const known = decision.outcome ? outcomes.get(decision.outcome) : null;
            const numbers = decision.authorizationNumbers || [];
            return el('li', null,
                decisions.length > 1 && decision.subject ? [code(decision.subject), ': '] : null,
                decision.outcome ? outcome(known ? known.label : decision.outcome, known ? known.tone : null)
                    : el('span', {class: 'presence presence--none'}, 'no decision stated'),
                numbers.length > 0 ? [', authorization ', numbers.map((value, index) => [index > 0 ? ', ' : null, code(value, 'pas-authno')])] : null,
                decision.authorizedFrom || decision.authorizedTo ? [', valid ', decision.authorizedFrom ? code(decision.authorizedFrom) : presenceMark('ABSENT'),
                    ' to ', decision.authorizedTo ? code(decision.authorizedTo) : presenceMark('ABSENT')] : null);
        }));
    }

    function everything(item) {
        // The verdict's lines are on the opened line above. How the notification was matched is said here only where they did not say it.
        const matching = (item.correlationNotes || []).filter(line => !(item.verdict.supporting || []).includes(line));
        const events = item.events || [];
        const findings = item.findings || [];
        const parts = [
            matching.length > 0
                ? el('div', null, el('p', {class: 'sub-heading'}, 'How it was matched'), el('ul', {class: 'note-list'}, matching.map(line => el('li', null, line))))
                : null,
            findings.length > 0 ? el('div', null, el('p', {class: 'sub-heading'}, 'Findings (' + findings.length + ')'),
                findings.map(finding => el('div', {class: 'finding'}, el('p', {class: 'finding__title'}, finding.title),
                    finding.detail ? el('p', null, finding.detail) : null, el('p', {class: 'finding__meta'}, 'Kind ', code(finding.kind))))) : null,
            el('div', {class: 'facts-pair'},
                facts([
                    ['Ledger line', [runLink(item.id), ' ', item.ledger && item.ledger.recorded ? 'recorded'
                        : el('strong', null, 'NOT recorded' + (item.ledger && item.ledger.message ? ': ' + item.ledger.message : ''))]],
                    ['What arrived', code(item.kind)],
                    ['Type', coded(item.type)],
                    ['Subscription status', coded(item.subscriptionStatus)],
                    ['Subscription', shown(item.subscription, {as: 'literal'})],
                    ['Topic', shown(item.topic, {as: 'literal'})],
                    ['Events since the subscription started', shown(item.eventsSinceSubscriptionStart, {as: 'literal'})],
                    ['Payload content', item.payloadContent ? code(item.payloadContent) : el('span', {class: 'presence presence--none'}, 'not a notification')]
                ], 'facts--ruled'),
                facts([
                    ['Claim id', item.claimId ? code(item.claimId) : el('span', {class: 'presence presence--none'}, 'not established')],
                    ['ClaimResponse id', item.claimResponseId ? code(item.claimResponseId) : el('span', {class: 'presence presence--none'}, 'none named')],
                    ['Bundle', ['type ', shown(item.bundleType, {as: 'literal'}), ', id ', shown(item.bundleId, {as: 'literal'})]],
                    ['Bundle timestamp', shown(item.bundleTimestamp, {as: 'literal'})],
                    ['Errors the service reports', listOf(item.errors, item.errorsPresence)],
                    ['Sample', item.sample ? ['delivered on the mock profile from scenario ', code(item.scenarioId || 'unknown'), '. It did not come from a service.'] : null]
                ], 'facts--ruled')),
            events.length > 0 ? el('div', null, heading(4, 'Events', events.length),
                table(['Number', 'When', 'About', 'Further resources'], events.map(event => [
                    shown(event.eventNumber, {as: 'literal'}), shown(event.timestamp, {as: 'literal'}), shown(event.focus, {as: 'literal'}),
                    listOf(event.additionalContext, event.additionalContextPresence, {as: 'literal'})]))) : el('p', {class: 'quiet'}, 'It reports no event.'),
            (item.claimResponses || []).map((response, index) => el('div', null,
                heading(4, 'The ClaimResponse it carries' + ((item.claimResponses.length > 1) ? ' (' + (index + 1) + ')' : '')),
                claimResponseBlock(response, spec.vocabulary(), 0))),
            otherResourcesPart(item.otherResources || []),
            item.issues && item.issues.length > 0 ? el('div', null, heading(4, 'Issues in the OperationOutcome', item.issues.length), issuesTable(item.issues)) : null,
            el('div', null, heading(4, 'Returned but not interpreted', (item.additionalFields || []).length),
                (item.additionalFields || []).length > 0 ? additionalFieldsTable(item.additionalFields)
                    : el('p', {class: 'quiet'}, 'Nothing was carried that is not shown above.')),
            payloadBlock(item)
        ];
        return el('div', {class: 'pas-note__everything'}, parts);
    }

    function payloadBlock(item) {
        const text = el('pre', {class: 'body-text', tabIndex: 0, aria: {label: 'The payload as received'}}, item.payload === undefined ? '' : item.payload);
        return el('div', {class: 'raw-body'},
            el('p', null, el('strong', null, 'The payload as received. '), count(item.bytesReceived) + ' bytes arrived and ' + count(item.bytesKept) + ' are held.',
                item.truncated ? el('strong', null, ' It was cut: what is shown is not all that was sent.') : null),
            // A sample's notes are on its line already.
            !item.sample && (item.payloadNotes || []).length > 0 ? el('ul', {class: 'note-list'}, item.payloadNotes.map(note => el('li', null, note))) : null,
            item.payload === undefined ? el('p', {class: 'quiet'}, 'No payload is held.') : text);
    }

    /* ---------- a sample notification, on the mock profile only ---------- */

    /**
     * The sample is built on the mock profile only. On live it is not built at all, so nothing on the
     * tab can be taken for a notification from the service.
     */
    function drawSample(list) {
        scenarios = list || [];
        clear(sampleSlot);
        if (!spec.app.isMock() || scenarios.length === 0) {
            return;
        }
        const scenario = selectField({label: 'Notification from scenario', field: 'sample.scenarioId',
            options: [{value: '', label: 'The scenario that answered the run named below'}].concat(scenarios.map(item =>
                ({value: item.id, label: item.number + '. ' + item.label})))});
        const about = el('span', {class: 'field__hint'});
        const describe = () => {
            const chosen = scenarios.find(item => item.id === scenario.value());
            fill(about, chosen ? chosen.description : 'Blank uses the scenario that answered the submission named below.');
        };
        scenario.select.addEventListener('change', describe);
        describe();
        scenario.node.append(about);
        const runId = textField({label: 'About the submission (run id)', field: 'sample.runId', literal: true, placeholder: 'run-...',
            hint: 'Needed for a notification about a request. A handshake and a heartbeat are about no request.'});
        const subscriptionId = textField({label: 'On the subscription (optional)', field: 'sample.subscriptionId', literal: true,
            hint: 'Blank uses the subscription this tool created most recently.'});
        // The field follows the latest submission on this tab until the tester types a run of their own into it.
        let filled = '';
        fillRun = () => {
            if ((runId.value() === '' || runId.value() === filled) && spec.lastRunId()) {
                runId.set(spec.lastRunId());
                filled = runId.value();
            }
        };
        const deliver = button('Deliver a sample notification', async () => {
            clearFieldErrors(sampleSlot);
            clear(problem);
            const payload = {};
            if (scenario.value() !== '') {
                payload.scenarioId = scenario.value();
            }
            if (runId.value() !== '') {
                payload.runId = runId.value();
            }
            if (subscriptionId.value() !== '') {
                payload.subscriptionId = subscriptionId.value();
            }
            deliver.disabled = true;
            let answer;
            try {
                answer = await api.postJson('/api/pas/notifications/sample', payload);
            } finally {
                deliver.disabled = false;
            }
            if (answer.kind === 'ok') {
                status.textContent = 'The sample was delivered to the receiver. It shows in the list as it arrives.';
                const before = shownItems.length > 0 ? shownItems[0].id : null;
                await poll();
                // The keyboard goes to the notification that arrived, which opens on its own; without it, back to the button, never to the page.
                const newest = shownItems.length > 0 && shownItems[0].id !== before ? rows.get(shownItems[0].id) : null;
                if (newest && !newest.hidden) {
                    newest.focus({preventScroll: false});
                } else {
                    deliver.focus();
                }
                return;
            }
            deliver.focus();
            // The sample's fields are named apart from the run's own, so a refusal of one never lands on the other.
            fill(problem, formProblem(answer.body, markFieldErrors([sampleSlot],
                (answer.body.fieldErrors || []).map(item => ({...item, field: 'sample.' + item.field})))));
        });
        sampleSlot.append(el('section', {class: 'case-group'},
            el('h2', {class: 'case-legend'}, 'A sample notification'),
            el('p', {class: 'small'}, el('span', {class: 'hatch pas-hatch', aria: {hidden: 'true'}}),
                ' Mock profile only. The notification a scenario file holds is handed to the receiver as if a service had sent it, '
                + 'so the whole loop can be learned without a vendor. It is marked as a sample wherever it shows.'),
            scenario.node, runId.node, subscriptionId.node, noSubscription, problem,
            el('div', {class: 'button-row'}, deliver, button('Use the latest submission', () => { runId.set(''); fillRun(); }, {class: 'button--text'}))));
        runId.input.addEventListener('input', () => { filled = ''; });
        fillRun();
        checkSubscriptions();
    }

    /**
     * Says, before a sample is delivered, when the ledger lists no subscription created by this
     * tool: the sample then names a subscription nobody created. The ledger is asked, which is
     * what the receiver matches against; when it cannot be asked nothing is said.
     */
    async function checkSubscriptions() {
        const answer = await api.get('/api/runs?operation=SUBSCRIPTION_CREATE&limit=1');
        if (answer.kind !== 'ok' || typeof answer.body.count !== 'number' || answer.body.count > 0) {
            clear(noSubscription);
            return;
        }
        fill(noSubscription, el('p', {class: 'pas-sample__nosub'},
            el('strong', null, 'The ledger lists no subscription created by this tool.'),
            ' A sample delivered now names a subscription nobody created, so it is shown as matching no subscription of this tool. '
            + 'To see a notification matched to its subscription, create one first. ',
            spec.showSubscriptions ? button('Go to the Subscriptions call', () => spec.showSubscriptions(), {class: 'button--text'}) : null));
    }

    return {
        node,
        /** The sample form, which belongs with the controls on the left. */
        sampleNode: sampleSlot,
        start,
        poll,
        /** Call when a submission has been sent from the tab, so the sample can be about it. */
        submitted() {
            fillRun();
            applyView();
        },
        /** Opens or folds the list. Opening it asks the ledger again whether a subscription exists, for the sample form. */
        setOpen(open) {
            body.open = open;
            if (open && spec.app.isMock() && scenarios.length > 0) {
                checkSubscriptions();
            }
        },
        drawReceiver,
        drawSample,
        /** Draws every row again, for when the outcome vocabulary arrives after the first notifications did. */
        redraw() {
            for (const item of shownItems) {
                const old = rows.get(item.id);
                if (old) {
                    const row = rowFor(item, false);
                    row.hidden = old.hidden;
                    old.replaceWith(row);
                    rows.set(item.id, row);
                }
            }
        },
        /** The latest list of notification scenarios, for redrawing the sample form when the mode changes. */
        scenarios: () => scenarios
    };
}

/**
 * A list a notification carries, or what it was when it holds no entry: not returned, null or
 * empty, as the server says. A value that is not a list is shown among the fields returned but not
 * interpreted. An answer that carries no presence is drawn as before.
 */
function listOf(values, presence, options) {
    if (Array.isArray(values) && values.length > 0) {
        return shownList(values, options);
    }
    if (presence === 'VALUE') {
        return el('span', {class: 'small'}, 'not a list: shown among the fields returned but not interpreted');
    }
    return presence ? presenceMark(presence) : shownList(values, options);
}
