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
import {el, fill, clear, button, code, facts, table, heading, when, count} from './dom.js';
import {shown, shownList} from './shown.js';
import {toneMark, mockMark} from './marks.js';
import {additionalFieldsTable, issuesTable} from './result.js';
import {selectField, textField, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {runLink} from './history-common.js';
import {coded, claimResponseBlock, otherResourcesPart} from './pas-detail.js';

const POLL_MILLIS = 5000;

/**
 * spec.app             the page's app object
 * spec.panel           the PAS tab's panel, whose hidden state says whether the tab is on screen
 * spec.vocabulary()    {outcomes} as the scenarios endpoint served it
 * spec.inquire(carry, about)  sends an inquiry for the request a notification is about
 * spec.lastRunId()     the run id of the latest submission on this tab, or null
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

    const status = el('span', {class: 'pas-notifications__status', role: 'status', aria: {live: 'polite'}});
    const heldCount = el('span', {class: 'section-heading__count'});
    const receiver = el('div', {class: 'pas-receiver'});
    const countsLine = el('p', {class: 'small quiet'});
    const list = el('ol', {class: 'pas-notifications__list'});
    const sampleSlot = el('div', {class: 'pas-sample'});
    const problem = el('div');
    // One line while folded, so a long list does not push a result down. What arrives is announced in that line either way,
    // and the list is open while the Notifications call is chosen.
    const body = el('details', {class: 'pas-notifications__body'},
        el('summary', null,
            el('span', {class: 'pas-notifications__title', id: 'pas-notifications-title'}, 'Notifications received ', heldCount),
            status),
        el('div', {class: 'pas-notifications__inside'},
            el('p', null, button('Ask the receiver now', () => poll(), {class: 'button--quiet button--small'})),
            receiver, countsLine, list));
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
        if (settings === null) {
            parts.push(el('p', {class: 'small'}, 'The configuration could not be read, so whether a service has an address to call back is not known here. ',
                answer.body.title || ''));
        } else if (!callback || !callback.set || !callback.value) {
            parts.push(el('p', {class: 'pas-receiver__unreachable'},
                el('strong', null, 'No service can call this receiver back.'),
                ' The setting ', code('onyx.subscription.callback-url'), ' is blank, so a subscription this tool creates names no address '
                + 'to call back and no notification from a service can arrive. Do not wait for one: follow a pended request with an inquiry.',
                spec.app.isMock() ? ' On the mock profile a sample notification can be delivered from the Notifications call on the left.' : null));
        } else {
            parts.push(el('p', null, 'A subscription this tool creates asks the service to call back ', code(callback.value),
                ', from the setting ', code('onyx.subscription.callback-url'), '. Whether the service can reach that address from where it runs '
                + 'is not something this tool can see. If nothing arrives, follow the request with an inquiry.'));
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
        // Newest first: what arrived since is newer than everything on screen, and goes above it.
        for (const item of arrived.slice().reverse()) {
            const row = rowFor(item, loaded);
            shownItems.unshift(item);
            list.prepend(row);
        }
        if (loaded && arrived.length > 0) {
            status.textContent = arrived.length === 1 ? 'A notification arrived: ' + arrived[0].verdict.headline
                : arrived.length + ' notifications arrived. The newest: ' + arrived[0].verdict.headline;
        } else if (!loaded) {
            status.textContent = shownItems.length === 0 ? 'Nothing has arrived at the receiver since the tool started.'
                : 'The receiver holds ' + shownItems.length + (shownItems.length === 1 ? ' notification.' : ' notifications.');
        }
        loaded = true;
        drawCounts();
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

    function rowFor(item, fresh) {
        const verdict = item.verdict;
        const step = item.nextStep || {};
        const carry = step.carry || {};
        const known = spec.vocabulary().outcomes.get(verdict.outcomeCode);
        const tone = known ? known.tone : verdict.tone;
        const inquiry = step.action === 'CHECK_STATUS' && (carry.claimId || []).length > 0
            ? button('Send inquiry for this request', () => spec.inquire(carry, item), {class: 'button--primary button--small'}) : null;
        const head = el('div', {class: 'pas-note__head'},
            when(item.receivedAt),
            fresh ? el('span', {class: 'flag'}, 'new') : null,
            item.sample ? mockMark() : null,
            el('span', {class: 'outcome'}, toneMark(tone), code(verdict.outcomeCode)),
            item.type && item.type.code ? ['type ', code(item.type.code)] : null);
        const links = el('p', {class: 'small'},
            'Subscription ', item.subscriptionId ? code(item.subscriptionId) : el('span', {class: 'presence presence--none'}, 'none named'),
            item.subscriptionRunId ? [', created by ', runLink(item.subscriptionRunId)] : null,
            '. ',
            item.correlatedRunId ? ['About the submission ', runLink(item.correlatedRunId), item.claimId ? [' (claim ', code(item.claimId), ')'] : null, '.'] : null);
        // When no submission was matched, how each link was or was not made is the server's to say.
        const unmatched = item.correlatedRunId ? [] : item.correlationNotes || [];
        const more = el('details', {class: 'fold pas-note__more'},
            el('summary', null, 'Everything in this notification', el('span', {class: 'quiet'}, count(item.bytesReceived) + ' bytes received')));
        let drawn = false;
        more.addEventListener('toggle', () => {
            if (more.open && !drawn) {
                drawn = true;
                more.append(everything(item));
            }
        });
        return el('li', {class: 'pas-note' + (fresh ? ' pas-note--fresh' : '')},
            head,
            el('p', {class: 'pas-note__headline'}, verdict.headline),
            links,
            unmatched.length > 0 ? el('ul', {class: 'note-list small'}, unmatched.map(note => el('li', null, note))) : null,
            el('div', {class: 'pas-note__next'},
                el('p', null, el('strong', null, step.headline || 'No next step was stated.')),
                step.reason ? el('p', {class: 'small'}, step.reason) : null,
                inquiry),
            more);
    }

    function everything(item) {
        // The verdict's lines can repeat how the notification was matched, which has its own heading below. Each is said once.
        const supporting = (item.verdict.supporting || []).filter(line => !(item.correlationNotes || []).includes(line));
        const events = item.events || [];
        const findings = item.findings || [];
        const parts = [
            supporting.length > 0 ? el('ul', {class: 'note-list'}, supporting.map(line => el('li', null, line))) : null,
            item.correlationNotes && item.correlationNotes.length > 0
                ? el('div', null, el('p', {class: 'sub-heading'}, 'How it was matched'), el('ul', {class: 'note-list'}, item.correlationNotes.map(line => el('li', null, line))))
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
                    ['Errors the service reports', shownList(item.errors)],
                    ['Sample', item.sample ? ['delivered on the mock profile from scenario ', code(item.scenarioId || 'unknown'), '. It did not come from a service.'] : null]
                ], 'facts--ruled')),
            events.length > 0 ? el('div', null, heading(4, 'Events', events.length),
                table(['Number', 'When', 'About', 'Further resources'], events.map(event => [
                    shown(event.eventNumber, {as: 'literal'}), shown(event.timestamp, {as: 'literal'}), shown(event.focus, {as: 'literal'}),
                    shownList(event.additionalContext, {as: 'literal'})]))) : el('p', {class: 'quiet'}, 'It reports no event.'),
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
            (item.payloadNotes || []).length > 0 ? el('ul', {class: 'note-list'}, item.payloadNotes.map(note => el('li', null, note))) : null,
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
                poll();
                return;
            }
            // The sample's fields are named apart from the run's own, so a refusal of one never lands on the other.
            fill(problem, formProblem(answer.body, markFieldErrors([sampleSlot],
                (answer.body.fieldErrors || []).map(item => ({...item, field: 'sample.' + item.field})))));
        });
        sampleSlot.append(el('section', {class: 'case-group'},
            el('h2', {class: 'case-legend'}, 'A sample notification'),
            el('p', {class: 'small'}, el('span', {class: 'hatch pas-hatch', aria: {hidden: 'true'}}),
                ' Mock profile only. The notification a scenario file holds is handed to the receiver as if a service had sent it, '
                + 'so the whole loop can be learned without a vendor. It is marked as a sample wherever it shows.'),
            scenario.node, runId.node, subscriptionId.node, problem,
            el('div', {class: 'button-row'}, deliver, button('Use the latest submission', () => { runId.set(''); fillRun(); }, {class: 'button--text'}))));
        runId.input.addEventListener('input', () => { filled = ''; });
        fillRun();
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
        },
        /** Opens or folds the list. */
        setOpen(open) {
            body.open = open;
        },
        drawReceiver,
        drawSample,
        /** The latest list of notification scenarios, for redrawing the sample form when the mode changes. */
        scenarios: () => scenarios
    };
}
