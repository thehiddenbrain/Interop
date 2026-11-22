// Audit > one run (#audit/runs/<runId>), from GET /api/runs/{runId}: the result card when this page
// made the run and still holds it, or when the trail keeps it, otherwise a card made from the ledger
// line; "Run again" and "Load into the Workbench"; the note; the request and the answer the trail
// keeps; and what the line keeps.
//
// The line is not the result. It keeps codes and identifiers, never the request, the response or
// the sentences the result was worded in, so the card from the line says only what the line holds.

import {el, chip, kv, fold, button, copyButton, fill, clear, toneName} from './dom.js';
import {call} from './api.js';
import {errorPanel} from './fault.js';
import {jsonView} from './json-view.js';
import {recall} from './session-runs.js';
import {resultCard} from './result.js';
import {none, outcomeChip, testChip, memberShort, utcText} from './audit-common.js';
import {replayLine, entryDetail} from './audit-replay.js';
import {noteEditor} from './audit-note.js';
import {keptEntry, trailFolds} from './audit-trail.js';

function present(value) {
    return typeof value === 'string' && value.trim() !== '';
}

// The replay statuses after which asking again gives the same answer: the line itself, or the
// profile it was made on, is what stops it. Blocked and not configured can change with the
// configuration, so the button stays after those.
const FINAL = new Set(['not-replayable', 'invalid']);

/** The view of one run. ctx: {app, words(), ledger(health), listAddress()}. Returns {node, show(runId)}. */
export function runView(ctx) {
    const node = el('div', {class: 'audit-run'});
    let openId = null;

    async function show(runId) {
        openId = runId;
        fill(node, crumbs(null), el('p', {class: 'muted', role: 'status'}, 'Reading the run from the ledger.'));
        // The trail is asked each time the run is opened, so a run it no longer keeps is never drawn from an earlier reading.
        const [answer, words, health, trail] = await Promise.all([call('GET', '/api/runs/' + encodeURIComponent(runId)), ctx.words(),
            call('GET', '/api/runs/health'), call('GET', '/api/trail/' + encodeURIComponent(runId))]);
        if (openId !== runId) {
            // Another run was opened while this answer was on its way; the later choice stands.
            return;
        }
        if (!answer.ok) {
            fill(node, crumbs(null), ctx.ledger(health.ok ? health.data : null), errorPanel(answer.error),
                answer.status === 404 ? el('p', {class: 'muted'}, 'A run the ledger did not record is not here, though its result showed a run id: '
                    + 'that result said NOT recorded, with the reason.') : null);
            return;
        }
        draw(answer.data || {}, words, health.ok ? health.data : null, trail);
    }

    function crumbs(title) {
        return el('p', {class: 'crumbs'}, el('a', {href: ctx.listAddress()}, 'Runs'), title ? [el('span', {'aria-hidden': 'true'}, '/'), el('span', null, title)] : null);
    }

    function draw(run, words, health, trail) {
        const step = words.stepOf(run.operation);
        const operation = words.operation(run.operation);
        const name = step ? step.name : operation ? operation.label : run.operation || 'Run';
        const when = utcText(run.timestamp);
        const kept = keptEntry(trail);
        const envelope = recall(run.runId) || (kept && kept.envelope && typeof kept.envelope === 'object' ? kept.envelope : null);
        const replaySlot = el('div', {class: 'audit-run__replay', 'aria-live': 'polite'});
        const again = againRow(run, words, replaySlot);
        const card = envelope ? el('div', null, resultCard(envelope, {context: 'audit'}), again)
            : recordCard(run, words, when, again);
        fill(node,
            crumbs(name + (when ? ', ' + when.slice(11, 19) : '')),
            el('div', {class: 'page-head'},
                el('h1', {class: 'page-head__title', tabIndex: -1}, name, run.protocol ? [' ', el('span', {class: 'tag'}, run.protocol)] : null),
                el('span', {class: 'page-head__note'}, el('span', {class: 'mono'}, run.runId)),
                el('div', {class: 'page-head__end'}, copyButton('Copy run id', () => run.runId, {small: true}))),
            ctx.ledger(health),
            // The request and answer come from the trail, or its line says they are not kept (B4b,
            // replacing I-26's line of release 1, as I-26 itself foresaw).
            el('section', {class: 'panel'}, card, replaySlot,
                el('div', {class: 'panel__section audit-run__note'}, noteEditor(run, kept => { run.note = kept.note; run.testerRef = kept.testerRef; })),
                // A result card from page memory has a Why of its own, worded from the answer; the line's is for a run read from the ledger only.
                envelope ? null : whyFold(run, words),
                trailFolds(trail, run.runId),
                fold({summary: 'Ledger line', hint: 'what is kept', body: () => jsonView(JSON.stringify(run, null, 2))})));
    }

    /** A card made from the ledger line: its chips, the outcome in the server's words, and the facts the line keeps. */
    function recordCard(run, words, when, again) {
        const option = words.outcome(run.protocol, run.outcome);
        const answered = words.answered(run.answeredBy);
        const expected = run.expected ? words.outcome(run.protocol, run.expected) : null;
        const codes = Array.isArray(run.coverageCodes) ? run.coverageCodes : [];
        return el('div', {class: 'result result--bare'},
            el('div', {class: 'result__meta'},
                typeof run.httpStatus === 'number' ? chip('HTTP ' + run.httpStatus) : null,
                answered ? chip(answered.chip, {hatched: run.answeredBy === 'SCENARIO', dashed: run.answeredBy !== 'SCENARIO'}) : null,
                typeof run.elapsedMillis === 'number' ? chip(run.elapsedMillis + ' ms') : null,
                run.slow ? chip('slow', {tone: 'action'}) : null,
                testChip(words, run.result),
                when ? chip(when) : null),
            el('div', {class: ['verdict', 'verdict--' + (option && toneName(option.tone) ? toneName(option.tone) : 'neutral')]},
                el('p', {class: 'verdict__headline'}, option ? option.label : run.outcome || 'No outcome recorded'),
                kv([
                    {key: 'Outcome', value: outcomeChip(words, run)},
                    codes.length > 0 ? {key: 'Coverage codes', value: codes.map((code, index) => [index > 0 ? ' ' : null, chip(code, {mono: true})])} : null,
                    {key: 'Expected', value: run.expected ? (expected ? expected.label : el('span', {class: 'mono'}, run.expected)) : chip('Not set', {dashed: true})},
                    {key: 'Member', value: memberShort(run) || none('not recorded')},
                    {key: 'Code', value: Array.isArray(run.procedureCodes) && run.procedureCodes.length > 0
                        ? el('span', {class: 'mono'}, run.procedureCodes.join(', ')) : none('not recorded')},
                    {key: 'Environment', value: run.environment ? [run.environment, ' ', run.environmentKind ? chip(String(run.environmentKind).toLowerCase()) : null]
                        : none('no environment')},
                    {key: 'Profile', value: run.profile || none('not recorded')}
                ]),
                again));
    }

    /** "Run again", or, for a line the server would refuse to send again, why not; and "Load into the Workbench". */
    function againRow(run, words, slot) {
        const inputs = Array.isArray(run.unrecordedInputs) ? run.unrecordedInputs : [];
        const load = loadButton(run);
        if (inputs.length > 0) {
            // Replay refuses a line that lists an input it does not hold, so a button could only ask to be told so.
            return el('div', {class: 'verdict__foot'}, load, el('span', {class: 'muted small'},
                'This run cannot be run again from its line: it also used ' + inputs.join(', ') + ', which the line does not hold.'));
        }
        if (!present(run.memberId) && present(run.memberToken)) {
            // The server refuses a line whose member ID was kept only as a token (live, by default): no
            // button that could only be told so, and no property name in a sentence a tester reads (I-20).
            return el('div', {class: 'verdict__foot'}, load, el('span', {class: 'muted small'},
                'This run cannot be run again: its member ID was not kept.'));
        }
        const control = button('Run again', () => replay(run, words, slot, control), {primary: true});
        return el('div', {class: 'verdict__foot'}, control, el('span', {class: 'muted small'}, 'Sends the same case again and compares the answers.'),
            load ? el('span', {class: 'audit-run__load'}, load) : null);
    }

    /**
     * "Load into the Workbench": the Workbench reads the run's journey back from the chain and the
     * trail (#workbench/load/<runId>) and sends nothing. A run of no journey has no case to load, so
     * no button offers it.
     */
    function loadButton(run) {
        if (typeof run.chainId !== 'string' || run.chainId === '') {
            return null;
        }
        return button('Load into the Workbench', () => ctx.app.go(loadAddress(run.runId)));
    }

    async function replay(run, words, slot, control) {
        control.disabled = true;
        const started = Date.now();
        const busy = el('p', {class: 'muted', role: 'status'}, 'Sending the run again... 0 s');
        const timer = window.setInterval(() => {
            busy.textContent = 'Sending the run again... ' + Math.round((Date.now() - started) / 1000) + ' s';
        }, 1000);
        fill(slot, busy);
        const answer = await call('POST', '/api/runs/replay', {body: {runIds: [run.runId]}});
        window.clearInterval(timer);
        control.disabled = false;
        if (!answer.ok) {
            fill(slot, errorPanel(answer.error));
            control.focus();
            return;
        }
        const entry = answer.data && Array.isArray(answer.data.results) ? answer.data.results[0] : null;
        clear(slot);
        if (!entry) {
            slot.append(el('p', null, replayLine(null, words)));
            return;
        }
        slot.append(entryDetail(entry, words));
        if (FINAL.has(entry.status)) {
            // The server said why this line cannot be sent here; a button that would only ask again is taken away.
            control.hidden = true;
        } else {
            control.focus();
        }
    }

    function whyFold(run, words) {
        const findings = Array.isArray(run.findings) ? run.findings : [];
        const next = run.nextAction ? words.nextAction(run.nextAction) : null;
        const hint = findings.length + (findings.length === 1 ? ' finding' : ' findings') + (run.nextAction ? ', next step recorded' : '');
        return fold({summary: 'Why', hint, body: () => el('div', null,
            el('div', {class: 'notes'}, el('p', {class: 'notes__title'}, 'Findings, as recorded'),
                findings.length > 0 ? el('ul', {class: 'notes'}, findings.map(kind => el('li', null, el('span', {class: 'mono'}, kind)))) : none('none recorded')),
            el('div', {class: 'notes'}, el('p', {class: 'notes__title'}, 'Next step'),
                run.nextAction ? el('p', null, next ? next.label : null, ' ', el('span', {class: 'mono small'}, '(' + run.nextAction + ')')) : none('none recorded')),
            el('p', {class: 'muted small'}, 'The ledger keeps the codes of a run, never its request, its answer or the sentences of its result.'))});
    }

    return {node, show};
}

/** The Workbench's address for a run of Audit to be read back into it. */
export function loadAddress(runId) {
    return '#workbench/load/' + encodeURIComponent(runId);
}
