// What a replay answered (POST /api/runs/replay): for each run it was asked to send again, whether
// the answer is the same, what changed, or why it was not sent, in the server's words.
//
// A replay answers with a report, never with the result card of the new run: the new run is a
// ledger line like any other, so it is drawn here as one, with a link that opens it. Only same,
// changed and not-comparable say anything about the service; the other statuses say that nothing
// was compared, and are drawn as that, never as a failure.

import {el, chip, fold} from './dom.js';
import {faultReport, withoutReference} from './fault.js';
import {maskedReading} from './result.js';
import {runLink, none, outcomeChip, testChip} from './audit-common.js';

// The order in which the report groups its entries: what changed first, what was the same last. A
// status this page does not list is still shown, after these, under the name the server gave it.
const ORDER = ['changed', 'not-comparable', 'blocked', 'not-signed-in', 'not-configured', 'invalid', 'error', 'not-replayable', 'same'];

// Drawn closed under their count: a replay of a day is mostly the same, and dozens of rows of
// "Same" would push the few that changed off the screen.
const FOLDED = new Set(['same', 'not-replayable']);

// The statuses that report an answer from the service. The rest report that nothing was compared.
const COMPARED = new Set(['same', 'changed', 'not-comparable']);

/** A status as words: the server's value with its hyphens read as spaces. */
export function statusWords(status) {
    const text = String(status || '').split('-').join(' ');
    return text.charAt(0).toUpperCase() + text.slice(1);
}

/** The counts of a report in one sentence: "32 same, 7 not replayable." */
export function summaryText(report) {
    const summary = report && report.summary ? report.summary : {};
    const names = ORDER.filter(name => summary[name] > 0)
        .concat(Object.keys(summary).filter(name => !ORDER.includes(name) && summary[name] > 0));
    return names.length === 0 ? 'No run was selected.'
        : names.map(name => summary[name] + ' ' + statusWords(name).toLowerCase()).join(', ') + '.';
}

function faultOf(entry) {
    return {reference: entry.faultReference, cause: entry.faultCause};
}

function shortOf(words, run, code) {
    const option = run ? words.outcome(run.protocol, code) : null;
    return option ? option.short || option.label : code;
}

/**
 * The one line a replay of one run ends in: "Same outcome.", "Changed: <before> to <after>.", or
 * the status and the server's reason when nothing was compared.
 */
export function replayLine(entry, words) {
    if (!entry) {
        return 'Nothing was replayed.';
    }
    if (entry.status === 'same') {
        return 'Same outcome.';
    }
    const differences = Array.isArray(entry.differences) ? entry.differences : [];
    if (entry.status === 'changed' || entry.status === 'not-comparable') {
        const outcome = differences.find(each => each.field === 'outcome');
        if (outcome) {
            return 'Changed: ' + shortOf(words, entry.original, outcome.was) + ' to '
                + shortOf(words, entry.replayed || entry.original, outcome.now) + '.';
        }
        if (differences.length > 0) {
            return 'Changed: ' + differences.map(each => each.field).join(', ') + '.';
        }
        return statusWords(entry.status) + '.';
    }
    return statusWords(entry.status) + ': nothing was compared.';
}

/** One entry in full: its line, the server's reason, the fault report, what changed, and the run it made. */
export function entryDetail(entry, words) {
    const differences = Array.isArray(entry.differences) ? entry.differences : [];
    const compared = COMPARED.has(entry.status);
    const verdict = entry.verdict && typeof entry.verdict === 'object' ? entry.verdict : null;
    return el('div', {class: 'audit-replay__one'},
        el('p', {class: ['audit-replay__line', compared ? null : 'muted']}, replayLine(entry, words)),
        entry.reason ? el('p', null, withoutReference(entry.reason, faultOf(entry))) : null,
        faultReport(entry.faultReference, entry.faultCause),
        differences.length > 0 ? differenceTable(differences, entry, words) : null,
        (Array.isArray(entry.notRecorded) ? entry.notRecorded : []).map(text =>
            el('p', {class: 'audit-replay__unrecorded'}, chip('NOT recorded', {tone: 'fault'}), ' ', text)),
        // What the run sent again found, in the server's words for it; a reason that is the verdict's own headline is not said twice.
        verdict && verdict.headline && verdict.headline !== entry.reason ? el('p', null, el('strong', null, verdict.headline)) : null,
        // A conclusion read from a mask where the original may not have been is said under it, as on the Workbench.
        verdict ? maskedReading(verdict.maskedReading) : null,
        entry.replayRunId ? el('p', {class: 'small'}, 'New run ', runLink(entry.replayRunId),
            entry.replayed ? [' ', outcomeChip(words, entry.replayed), ' ', testChip(words, entry.replayed.result)] : null) : null);
}

/** Each field that came back different, as it was and as it is now. An outcome is shown in its short words. */
function differenceTable(differences, entry, words) {
    const value = (field, text, run) => {
        if (text === undefined || text === null || text === '') {
            return none('none');
        }
        if (field === 'outcome' && run) {
            return outcomeChip(words, {protocol: run.protocol, outcome: text});
        }
        if (field === 'result') {
            return testChip(words, text);
        }
        return el('span', {class: 'mono'}, String(text));
    };
    return el('div', {class: 'table-wrap'}, el('table', {class: 'table audit-replay__diff'},
        el('thead', null, el('tr', null, ['Field', 'Was', 'Now'].map(head => el('th', {scope: 'col'}, head)))),
        el('tbody', null, differences.map(item => el('tr', null,
            el('td', null, el('span', {class: 'mono'}, item.field)),
            el('td', null, value(item.field, item.was, entry.original)),
            el('td', null, value(item.field, item.now, entry.replayed || entry.original)))))));
}

/** The whole report of a replay of the runs listed: the notes on the replay, the counts, and one group per status. */
export function replayReport(report, words) {
    const results = Array.isArray(report.results) ? report.results : [];
    const node = el('section', {class: 'section audit-replay', 'aria-label': 'What the replay of the runs listed found'},
        el('div', {class: 'section__head'}, el('h2', {class: 'section__title', tabIndex: -1}, 'What the replay of the runs listed found'),
            el('span', {class: 'muted small'}, summaryText(report))),
        (Array.isArray(report.notes) ? report.notes : []).length > 0 ? el('ul', {class: 'notes'}, report.notes.map(text => el('li', null, text))) : null);
    const present = Array.from(new Set(results.map(entry => entry.status)));
    const groups = ORDER.filter(name => present.includes(name)).concat(present.filter(name => !ORDER.includes(name)));
    for (const status of groups) {
        const entries = results.filter(entry => entry.status === status);
        const label = statusWords(status) + ' (' + entries.length + ')';
        const body = el('div', {class: 'table-wrap'}, el('table', {class: 'table audit-replay__table'},
            el('thead', null, el('tr', null, ['Run sent again', 'Then', 'What came of it'].map(head => el('th', {scope: 'col'}, head)))),
            el('tbody', null, entries.map(entry => el('tr', null,
                el('td', null, runLink(entry.runId)),
                el('td', null, entry.original ? [outcomeChip(words, entry.original), ' ', testChip(words, entry.original.result)]
                    : none('no record of a run')),
                el('td', null, entryDetail(entry, words)))))));
        node.append(FOLDED.has(status) ? fold({summary: label, body})
            : el('div', {class: 'audit-replay__group'}, el('h3', {class: 'section-label'}, label), body));
    }
    return node;
}
