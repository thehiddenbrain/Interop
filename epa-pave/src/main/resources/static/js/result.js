// The result card of a run, drawn the same on the Workbench and in Audit (plan 3.2, "The result").
//
// A result is read top down in a few seconds: a row of chips (HTTP status, who answered, how long,
// the test result, the member ID as sent), then the card with the server's headline, a few facts
// and the next step, then closed folds for everything else. Words the envelope carries as codes
// (the outcome's short label, the next action's button, the answered-by chip, the test result) are
// looked up in the server's vocabulary; the page keeps no copy of them and draws nothing for a word
// the server has not given. An error, a refused field and a run that was NOT recorded are never put
// inside a closed fold.

import {el, append, chip, fold, kv, button, copyButton, firstSentence, toneName, fill, clear} from './dom.js';
import {responseDetail} from './detail.js';
import {errorPanel, faultReport} from './fault.js';
import {jsonView} from './json-view.js';
import {vocabulary} from './words.js';
import {presenceWords} from './shown.js';

// The facts the card shows before the rest go into a fold inside it, so the card stays short.
const MOST_FACTS = 8;

// A server sentence longer than this shows its first sentence; the rest goes to "Why".
const LONGEST_NEXT_STEP = 90;

/**
 * A run envelope as a result card.
 * options.context: 'workbench' or 'audit'.
 * options.onNext(action, envelope): called when the next step's button is pressed (the Workbench
 *   opens the step it names). Without it the next step is drawn as words, never as a dead button.
 * options.nextBlocked(action): the server's sentence when the step the button leads to cannot be
 *   sent as things stand (readiness), or null.
 * options.stale: true when the configuration changed since this run was sent.
 * options.fieldsPlaced: names of fields whose errors the caller shows at the field itself.
 */
export function resultCard(envelope, options) {
    const settings = options || {};
    if (!envelope || typeof envelope !== 'object') {
        return el('div', {class: 'result'});
    }
    const root = el('div', {class: 'result', 'aria-live': 'polite'});
    const words = {value: null};
    const meta = chipRow(envelope, settings);
    root.append(meta.node);
    const verdict = envelope.verdict || {};
    const card = el('div', {class: ['verdict', 'verdict--' + (toneName(verdict.tone) || 'neutral')]});
    root.append(card);
    if (typeof verdict.headline === 'string' && verdict.headline !== '') {
        card.append(el('p', {class: 'verdict__headline'}, verdict.headline));
    }
    const masked = maskedReading(verdict.maskedReading);
    if (masked) {
        card.append(masked);
    }
    const serviceSaid = serviceLine(envelope);
    if (serviceSaid) {
        card.append(serviceSaid);
    }
    const facts = factRows(envelope);
    const shownFacts = facts.present.slice(0, MOST_FACTS);
    const moreFacts = facts.present.slice(MOST_FACTS);
    const factSlot = el('div', {class: 'verdict__facts'});
    card.append(factSlot);
    const ledger = ledgerLine(envelope);
    if (ledger) {
        card.append(ledger);
    }
    const inCard = faultInCard(envelope);
    if (inCard) {
        append(card, faultFacts(envelope));
    }
    const nextSlot = el('div', {class: 'verdict__foot'});
    card.append(nextSlot);

    const drawWithWords = () => {
        const table = kv(shownFacts.map(row => row(words.value)).concat([expectedRow(envelope, words.value)]));
        fill(factSlot, table);
        if (moreFacts.length > 0) {
            factSlot.append(fold({summary: moreFacts.length + ' more', inline: true, body: () => kv(moreFacts.map(row => row(words.value)))}));
        }
        if (facts.absent.length > 0) {
            factSlot.append(fold({summary: 'Not returned (' + facts.absent.length + ')', inline: true,
                body: () => kv(facts.absent.map(row => row(words.value)))}));
        }
        fill(nextSlot, ...nextStep(envelope, settings, words.value));
        meta.fill(words.value);
    };
    drawWithWords();
    const asked = vocabulary();
    if (asked && typeof asked.then === 'function') {
        asked.then(found => {
            if (found) {
                words.value = found;
                drawWithWords();
            }
        }, () => null);
    }

    if (envelope.error && !inCard) {
        const panel = errorPanel(envelope.error, {fieldsPlaced: settings.fieldsPlaced});
        if (panel) {
            root.append(panel);
        }
    }
    const disagreement = codeListLine(envelope);
    if (disagreement) {
        root.append(disagreement);
    }
    root.append(...folds(envelope));
    if (settings.stale) {
        root.append(el('p', {class: 'small muted result__stale'}, 'This result was produced before the configuration changed.'));
    }
    return root;
}

/* ---------- the chip row ---------- */

function chipRow(envelope, settings) {
    const node = el('div', {class: 'result__meta'});
    const status = httpStatus(envelope);
    const answered = el('span', {class: 'result__answered'});
    const result = el('span', {class: 'result__test'});
    const timing = envelope.timing || {};
    append(node,
        status !== null ? chip('HTTP ' + status) : null,
        answered,
        typeof timing.elapsedMillis === 'number' ? chip(timing.elapsedMillis + ' ms') : null,
        timing.slow === true ? chip('slow', {tone: 'pending', title: 'The call completed but took longer than ' + timing.thresholdMillis + ' ms.'}) : null,
        result,
        sentAs(envelope.wire && envelope.wire.memberId),
        auditLink(envelope, settings));
    return {
        node,
        fill(words) {
            clear(answered);
            clear(result);
            if (!words) {
                return;
            }
            const who = (words.answeredBy || []).find(each => each.code === envelope.answeredBy);
            if (who && who.chip) {
                answered.append(chip(who.chip, {
                    hatched: envelope.answeredBy === 'SCENARIO',
                    dashed: envelope.answeredBy !== 'SCENARIO',
                    title: envelope.answeredBy === 'SCENARIO' ? 'A scenario answered; nothing left this machine.' : null
                }));
            }
            const expectation = envelope.expectation || {};
            const code = String(expectation.result || '').toUpperCase();
            const label = (words.testResults || []).find(each => String(each.code).toUpperCase() === code);
            if (label && label.label) {
                result.append(chip(label.label, {tone: code === 'PASS' ? 'pass' : code === 'FAIL' ? 'fail' : 'unverified',
                    dashed: code === 'UNVERIFIED', title: expectation.explanation || null}));
            }
        }
    };
}

function httpStatus(envelope) {
    const response = envelope.wire && envelope.wire.response;
    if (response && typeof response.status === 'number') {
        return response.status;
    }
    return envelope.error && typeof envelope.error.httpStatus === 'number' ? envelope.error.httpStatus : null;
}

/** "Sent as <sent>, rule: <rule>" when a member ID was sent; the rule only when the server names one. */
export function sentAs(forms) {
    if (!forms || typeof forms.sent !== 'string' || forms.sent === '') {
        return null;
    }
    return el('span', {class: 'small muted sent-as'}, 'Sent as ', el('span', {class: 'mono'}, forms.sent),
        typeof forms.rule === 'string' && forms.rule !== '' ? ', rule: ' + forms.rule : null);
}

function auditLink(envelope, settings) {
    const recorded = envelope.ledger && envelope.ledger.recorded === true;
    if (!recorded || typeof envelope.runId !== 'string' || settings.context === 'audit') {
        return null;
    }
    return el('a', {class: 'push small', href: '#audit/runs/' + encodeURIComponent(envelope.runId)}, 'Open in Audit');
}

/* ---------- the card ---------- */

/**
 * The line a server sends when a conclusion was read from a mask and not from what the service
 * sent, drawn as the screens draw what must be read before acting: a heavy rule on its left. Null
 * when there is none. Every place that draws a conclusion from an answer draws this under it.
 */
export function maskedReading(line) {
    return typeof line === 'string' && line !== '' ? el('p', {class: 'masked-reading'}, line) : null;
}

/**
 * A service's own refusal in one line, as it said it: an OperationOutcome's first issue, or the
 * summary and detail a CDS service answers a refusal with, which the server leaves among the fields
 * returned but not interpreted. Never from a body the wire says was masked: a secret the service
 * repeated must not reach the screen here.
 */
function serviceLine(envelope) {
    const error = envelope.error;
    if (!error || (error.kind !== 'VENDOR_REJECTED' && error.kind !== 'VENDOR_ERROR')) {
        return null;
    }
    const issue = Array.isArray(error.issues) && error.issues.length > 0 ? error.issues[0] : null;
    const said = issue && issue.diagnostics && issue.diagnostics.presence === 'VALUE' ? String(issue.diagnostics.value) : null;
    if (!said) {
        const cds = cdsRefusal(envelope);
        return cds ? el('p', {class: 'small service-said'}, el('strong', null, 'Service said: '), cds) : null;
    }
    const where = issue.expression && issue.expression.length > 0 && issue.expression[0].presence === 'VALUE'
        ? String(issue.expression[0].value) : null;
    return el('p', {class: 'small'}, el('strong', null, 'Service said: '), said, where ? [' (', el('span', {class: 'mono'}, where), ')'] : null);
}

/** "summary: detail" of a CDS refusal, from the fields returned but not interpreted, or null. */
function cdsRefusal(envelope) {
    const masked = envelope.wire && envelope.wire.response && envelope.wire.response.maskedPlaces > 0;
    const fields = Array.isArray(envelope.additionalFields) ? envelope.additionalFields : [];
    if (masked) {
        return null;
    }
    const value = path => {
        const found = fields.find(field => field && field.path === path && field.presence === 'VALUE' && field.kind !== 'NOTE');
        return found && found.value !== undefined && found.value !== null && String(found.value) !== '' ? String(found.value) : null;
    };
    const summary = value('summary');
    const detail = value('detail');
    return summary || detail ? [summary, summary && detail ? ': ' : null, detail].filter(Boolean).join('') : null;
}

// The kinds of failure that are the service's or its environment's, drawn inside the result card
// itself (prototype 05a): one card, never a second framed box repeating its headline.
const SERVICE_FAULTS = new Set(['VENDOR_ERROR', 'VENDOR_REJECTED', 'TIMEOUT', 'UNREACHABLE', 'MALFORMED_RESPONSE']);

/** True when a run's failure is drawn inside its card: a service fault on a run that has a headline of its own. */
export function faultInCard(envelope) {
    const verdict = envelope && envelope.verdict;
    return Boolean(envelope && envelope.error && SERVICE_FAULTS.has(envelope.error.kind) && verdict
        && typeof verdict.headline === 'string' && verdict.headline !== '');
}

/**
 * The rest of a service fault, inside the card: who played it (the heading), each issue on one line
 * (severity, code, what it says, where), and the action's first sentence when the card has no next
 * step of its own. The title is the card's headline in other words and is not said again.
 */
function faultFacts(envelope) {
    const error = envelope.error;
    const issues = Array.isArray(error.issues) ? error.issues : [];
    const text = value => (value && value.presence === 'VALUE' && value.value !== undefined && value.value !== null ? String(value.value) : null);
    // The first issue's diagnostics are already the card's "Service said" line; its own line keeps the rest.
    const saidAbove = serviceLine(envelope) ? text(issues.length > 0 ? issues[0].diagnostics : null) : null;
    const lines = issues.map((issue, index) => {
        const said = index === 0 && saidAbove ? null : text(issue.diagnostics);
        const details = text(issue.detailsText);
        const where = Array.isArray(issue.expression) ? issue.expression.map(text).filter(Boolean) : [];
        return el('li', {class: 'issue-line'},
            text(issue.severity) ? chip(text(issue.severity), {mono: true}) : chip('severity not returned', {dashed: true}), ' ',
            text(issue.code) ? chip(text(issue.code), {mono: true}) : null, ' ',
            said || (details || (index === 0 && saidAbove) ? null : chip('diagnostics not returned', {dashed: true})),
            details && details !== said && details !== saidAbove ? [said ? ' ' : null, el('span', {class: 'muted'}, details)] : null,
            where.length > 0 ? [' ', el('span', {class: 'mono small'}, where.join(', '))] : null);
    });
    const hasNext = envelope.nextStep && envelope.nextStep.action;
    const [first, rest] = firstSentence(error.action || '');
    return [
        error.heading ? el('p', {class: 'verdict__label'}, error.heading) : null,
        lines.length > 0 ? el('ul', {class: 'issue-lines'}, lines) : null,
        !hasNext && first ? el('p', {class: 'verdict__next'}, first) : null,
        !hasNext && rest ? fold({summary: 'Why', inline: true, body: el('p', null, rest)}) : null,
        faultReport(error.reference, error.cause)
    ];
}

/**
 * The facts of the card: one row per determination row, a delegated vendor when the answer names
 * one, and for PAS one row per item asked. Rows the answer did not return go to their own fold, so
 * the card shows what was said. Each row is a function of the vocabulary, which may come later.
 */
function factRows(envelope) {
    const present = [];
    const absent = [];
    for (const row of Array.isArray(envelope.determination) ? envelope.determination : []) {
        const returned = row.presence === 'VALUE' || row.sentWithNoValue === true;
        (returned ? present : absent).push(() => ({key: row.subject ? row.label + ' (' + row.subject + ')' : row.label, value: determinationValue(row)}));
    }
    const detail = envelope.detail || {};
    for (const coverage of Array.isArray(detail.coverageInformation) ? detail.coverageInformation : []) {
        if (typeof coverage.delegatedVendor === 'string' && coverage.delegatedVendor !== '') {
            present.push(() => ({key: 'Delegated vendor', value: el('span', null, coverage.delegatedVendor)}));
        }
    }
    for (const response of Array.isArray(detail.claimResponses) ? detail.claimResponses : []) {
        for (const decision of Array.isArray(response.decisions) ? response.decisions : []) {
            const name = 'Item ' + (decision.sequence !== undefined && decision.sequence !== null ? decision.sequence : '?')
                + (decision.procedureCode ? ' ' + decision.procedureCode : '');
            present.push(words => ({key: name, value: outcomeChip('PAS', decision.outcome, words)}));
        }
    }
    return {present, absent};
}

function determinationValue(row) {
    if (row.sentWithNoValue === true) {
        return chip('sent with no value', {dashed: true});
    }
    if (row.presence !== 'VALUE') {
        return chip(presenceWords(row.presence), {dashed: true});
    }
    const code = row.code === undefined || row.code === null ? '' : String(row.code);
    // The display is shown only when it adds words to the code.
    const adds = typeof row.display === 'string' && row.display !== '' && row.display.toLowerCase() !== code.toLowerCase();
    return el('span', null, chip(code, {mono: true, tone: row.tone || null}),
        adds ? el('span', {class: 'kv__display'}, ' ' + row.display) : null,
        row.recognised === false ? chip('not a code this tool knows', {dashed: true}) : null);
}

/** An outcome code as its short label from the vocabulary, in its tone; the code itself until the words come. */
function outcomeChip(protocol, code, words) {
    const found = words && words.outcomes ? (words.outcomes[protocol] || []).find(each => each.code === code) : null;
    return found && found.short ? chip(found.short, {tone: found.tone}) : chip(String(code || ''), {mono: true});
}

function expectedRow(envelope, words) {
    const expectation = envelope.expectation || {};
    const label = expectation.expectedLabel || expectation.expected;
    return {key: 'Expected', value: label ? String(label) : chip('Not set', {dashed: true})};
}

/** The NOT recorded chip and the ledger's own sentence, whole, when the run was not recorded. Never folded. */
function ledgerLine(envelope) {
    const ledger = envelope.ledger;
    if (!ledger || ledger.recorded !== false) {
        return null;
    }
    return el('p', {class: 'result__not-recorded'}, chip('NOT recorded', {tone: 'fault'}), ' ', ledger.message || null);
}

/**
 * The next step: a primary button named by the vocabulary when its action has one and the caller
 * can act on it; otherwise the server's headline, whole when short, else its first sentence.
 */
function nextStep(envelope, settings, words) {
    const step = envelope.nextStep;
    if (!step || !step.action) {
        return [];
    }
    const action = words && Array.isArray(words.nextActions) ? words.nextActions.find(each => each.code === step.action) : null;
    if (action && action.button && typeof settings.onNext === 'function') {
        const blocked = typeof settings.nextBlocked === 'function' ? settings.nextBlocked(step.action) : null;
        return [button(action.button, () => settings.onNext(step.action, envelope),
            {primary: true, disabled: Boolean(blocked), title: blocked || null})];
    }
    const headline = typeof step.headline === 'string' ? step.headline : '';
    if (headline === '') {
        return [];
    }
    const shown = headline.length <= LONGEST_NEXT_STEP ? headline : firstSentence(headline)[0];
    return [el('p', {class: 'verdict__next'}, shown)];
}

/** One visible line when the code list and the service disagree, above the folds that explain it. */
function codeListLine(envelope) {
    const findings = Array.isArray(envelope.findings) ? envelope.findings : [];
    const disagree = findings.find(each => each.kind === 'CODE_LIST_DISAGREES');
    return disagree ? el('p', {class: 'small result-line'}, disagree.title) : null;
}

/* ---------- the folds ---------- */

function folds(envelope) {
    const out = [];
    const why = whyBody(envelope);
    if (why) {
        out.push(fold({summary: 'Why', hint: why.hint, body: why.body}));
    }
    const detail = responseDetail(envelope);
    if (detail) {
        out.push(fold({summary: 'Response detail', body: detail}));
    }
    const returned = Array.isArray(envelope.additionalFields) ? envelope.additionalFields : [];
    if (returned.length > 0) {
        out.push(fold({summary: 'Returned but not interpreted (' + returned.length + ')', body: () => returnedTable(returned, envelope.wire)}));
    }
    out.push(wireFold(envelope.wire, {profile: envelope.profile, error: envelope.error, answeredBy: envelope.answeredBy}));
    return out;
}

function whyBody(envelope) {
    const verdict = envelope.verdict || {};
    const supporting = Array.isArray(verdict.supporting) ? verdict.supporting : [];
    const findings = Array.isArray(envelope.findings) ? envelope.findings : [];
    const notes = envelope.detail && Array.isArray(envelope.detail.requestNotes) ? envelope.detail.requestNotes : [];
    const step = envelope.nextStep || {};
    const [, nextRest] = firstSentence(step.headline || '');
    const expectation = envelope.expectation || {};
    if (supporting.length + findings.length + notes.length === 0 && !step.reason && !nextRest && !expectation.explanation) {
        return null;
    }
    const hint = count(findings.length, 'finding', 'findings') + ', ' + count(notes.length, 'request note', 'request notes');
    return {
        hint,
        body: () => el('div', {class: 'why'},
            supporting.length > 0 ? section('What the service said', el('ul', {class: 'notes'}, supporting.map(line => el('li', null, line)))) : null,
            step.headline || step.reason ? section('Next step', el('p', null, step.headline || null), step.reason ? el('p', {class: 'small'}, step.reason) : null) : null,
            findings.length > 0 ? section('Findings', el('ul', {class: 'notes'}, findings.map(finding))) : null,
            notes.length > 0 ? section('Request notes', el('ul', {class: 'notes'}, notes.map(line => el('li', null, line)))) : null,
            expectation.explanation ? section('Expectation', el('p', null, expectation.explanation)) : null)
    };
}

function section(title, ...children) {
    return el('div', {class: 'why__part'}, el('p', {class: 'notes__title'}, title), children);
}

function finding(item) {
    return el('li', null,
        item.groupLabel ? el('span', {class: 'small muted'}, item.groupLabel + ': ') : null,
        el('strong', null, item.title || ''),
        item.detail ? el('div', {class: 'small'}, item.detail) : null,
        item.codeListSays ? el('div', {class: 'small'}, 'Code list says: ' + item.codeListSays) : null,
        item.serviceSays ? el('div', {class: 'small'}, 'Service says: ' + item.serviceSays) : null);
}

function count(n, one, many) {
    return n + ' ' + (n === 1 ? one : many);
}

/**
 * Returned but not interpreted: each field's path, presence and value. When the wire says secrets
 * were masked in the answer, a value is shown only from the masked body, never as it came, so a
 * secret the service repeated cannot reach the screen here.
 */
export function returnedTable(fields, wire) {
    const masked = wire && wire.response && wire.response.maskedPlaces > 0;
    return kv(fields.map(field => ({
        key: el('span', {class: 'mono'}, field.path || ''),
        value: field.kind === 'NOTE' ? String(field.value || '')
            : masked ? el('span', {class: 'small muted'}, 'In the wire traffic below, as masked.')
                : field.presence !== 'VALUE' ? chip(presenceWords(field.presence), {dashed: true})
                    : jsonView(field.value === undefined || field.value === null ? '' : String(field.value))
    })));
}

/**
 * The wire traffic of a run or a lookup: the request (method, url, url as configured, headers,
 * body) and the response (status, bytes, whether it read as JSON, headers, body), each body with
 * Copy, a long string cut on screen and copied whole. After a timeout or an unreachable service,
 * the address called is shown without its query, which can carry a member ID.
 */
export function wireFold(wire, context) {
    const facts = context || {};
    return fold({summary: 'Wire traffic', hint: 'request and response JSON', body: () => wireBody(wire, facts)});
}

function wireBody(wire, facts) {
    if (!wire) {
        return el('p', {class: 'small muted'}, 'This result carries no request and no response.');
    }
    const request = wire.request;
    const response = wire.response;
    const failedToReach = facts.error && (facts.error.kind === 'UNREACHABLE' || facts.error.kind === 'TIMEOUT');
    const parts = [];
    if (request) {
        parts.push(el('div', {class: 'wire__side'},
            el('p', {class: 'notes__title'}, 'Request'),
            request.note ? el('p', {class: 'small'}, request.note) : null,
            kv([
                {key: 'Method', value: el('span', {class: 'mono'}, request.method || '')},
                {key: 'Url', value: request.url ? el('span', {class: 'mono'}, failedToReach ? addressOnly(request.url) : request.url)
                    : chip('none', {dashed: true})},
                {key: 'Url as configured', value: request.urlTemplate ? el('span', {class: 'mono'}, request.urlTemplate) : chip('none', {dashed: true})},
                {key: 'Sent', value: request.sent ? 'Yes, sent' : 'Not sent'}
            ]),
            headers(request.headers),
            request.body !== undefined && request.body !== null ? jsonView(request.body, {label: 'Request body'}) : null));
    }
    if (response) {
        parts.push(el('div', {class: 'wire__side'},
            el('p', {class: 'notes__title'}, 'Response'),
            kv([
                {key: 'Status', value: typeof response.status === 'number' ? 'HTTP ' + response.status : chip('no response arrived', {dashed: true})},
                {key: 'Bytes', value: typeof response.byteLength === 'number' ? String(response.byteLength) : chip('not counted', {dashed: true})},
                {key: 'Read as JSON', value: response.parsed ? 'yes' : 'no'}
            ]),
            response.maskNote ? el('p', {class: 'small masked-reading'}, response.maskNote) : null,
            headers(response.headers),
            response.body !== undefined && response.body !== null ? jsonView(response.body, {label: 'Response body'}) : null));
    }
    const forms = wire.memberId;
    if (forms && forms.sent) {
        parts.push(el('p', {class: 'small'}, 'Member ID as typed ', el('span', {class: 'mono'}, forms.typed || ''),
            ', sent as ', el('span', {class: 'mono'}, forms.sent), forms.rule ? ', rule: ' + forms.rule : null,
            forms.note ? el('span', {class: 'muted'}, ' ' + forms.note) : null));
    }
    return el('div', {class: 'wire'}, parts);
}

function headers(list) {
    if (!Array.isArray(list) || list.length === 0) {
        return el('p', {class: 'small muted'}, 'Headers: none.');
    }
    return kv(list.map(header => ({key: el('span', {class: 'mono'}, header.name || ''), value: el('span', {class: 'mono'}, header.value || '')})));
}

/** The text of a url before its first "?" or "#": scheme, host, port and path. A url with neither is returned whole. */
export function addressOnly(url) {
    const text = String(url);
    for (let i = 0; i < text.length; i++) {
        if (text[i] === '?' || text[i] === '#') {
            return text.slice(0, i);
        }
    }
    return text;
}

/** For a caller that copies a whole result: the run's id and outcome as one line. */
export function resultLine(envelope) {
    const verdict = (envelope && envelope.verdict) || {};
    return [envelope && envelope.runId, verdict.outcomeCode, verdict.headline].filter(Boolean).join(' ');
}

/** A button that copies a value whole, beside a value cut on screen. */
export function copyWhole(label, text) {
    return copyButton(label, () => text, {small: true});
}
