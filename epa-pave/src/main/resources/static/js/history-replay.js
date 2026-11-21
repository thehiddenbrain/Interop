// What a replay answered: for each run it was asked to send again, whether the answer is the same,
// what changed, or why it was not sent, all in the server's words.
//
// POST /api/runs/replay answers with a report, never with the result view of the new run: the new
// run is a ledger record like any other, so it is drawn here as one, with a link that opens it.
// Only same, changed and not-comparable say anything about the service. The other statuses say
// that nothing was compared, and they are drawn as that, never as a failure.

import {el, code, count, when, table, millis, faultReport} from './dom.js';
import {toneMark} from './marks.js';
import {stamp} from './marks.js';
import {runLink, outcomeOf, whatRan, none, whereRan} from './history-common.js';

// The order in which the summary lists the statuses the server counts. A status this page does not
// list is still shown, after these, under the name the server gave it.
const ORDER = ['same', 'changed', 'not-comparable', 'not-replayable', 'blocked', 'not-configured', 'invalid', 'error'];

// Which statuses report an answer from the service. The rest report that nothing was compared.
const COMPARED = new Set(['same', 'changed', 'not-comparable']);

/** A status as words: the server's own value with its hyphens read as spaces. */
export function statusWords(status) {
    const text = String(status || '').replace(/-/g, ' ');
    return text.charAt(0).toUpperCase() + text.slice(1);
}

/** The statuses the report counts, in order, leaving out those it counts none of. */
function countedStatuses(summary) {
    return ORDER.filter(name => summary[name] > 0).concat(Object.keys(summary).filter(name => !ORDER.includes(name) && summary[name] > 0));
}

/** The counts of a report in one short sentence, for a screen reader: "32 same, 7 not replayable." */
export function summaryText(report) {
    const summary = report.summary || {};
    const statuses = countedStatuses(summary);
    return statuses.length === 0 ? 'no run was selected.'
        : statuses.map(name => count(summary[name]) + ' ' + statusWords(name).toLowerCase()).join(', ') + '.';
}

/** The whole report: notes about the replay as a whole, the counts, and one entry per run. */
export function replayReport(report, vocabulary, options) {
    const settings = options || {};
    const summary = report.summary || {};
    const statuses = countedStatuses(summary);
    const results = report.results || [];
    const node = el('section', {class: 'history-replay', aria: {label: 'What the replay found'}},
        // The heading takes the focus when the control that asked for the report is gone.
        el('h3', {class: 'section-heading', tabIndex: -1}, settings.title || 'What the replay found', ' ',
            el('span', {class: 'section-heading__count'}, '(' + results.length + ')')),
        el('p', {class: 'small quiet'}, 'Started ', when(report.startedAt),
            report.from || report.to ? [', for the runs ', report.from ? ['from ', report.from] : null, report.to ? [' to ', report.to] : null] : null,
            '. A run sent again goes to the configuration in force when the replay started.'),
        (report.notes || []).length > 0 ? el('ul', {class: 'note-list'}, report.notes.map(text => el('li', null, text))) : null,
        statuses.length > 0 && !(settings.single && results.length === 1) ? el('p', {class: 'history-replay__counts'}, statuses.map((name, index) =>
            [index > 0 ? ', ' : null, el('strong', null, count(summary[name])), ' ', statusWords(name).toLowerCase()]), '.') : null);
    if (results.length === 1 && settings.single) {
        node.append(entryDetail(results[0], vocabulary));
        return node;
    }
    if (results.length > 0) {
        node.append(el('div', {class: 'table-scroll', role: 'region', tabIndex: 0, aria: {label: 'Each run of the replay'}},
            table([{text: 'Run sent again', class: 'col-22'}, {text: 'What came of it', class: 'col-17'}, 'Then and now', {text: 'Why, or what changed', class: 'col-30'}],
                results.map(entry => [
                    [runLink(entry.runId), el('div', {class: 'small'}, entry.original ? whatRan(vocabulary, entry.original) : null)],
                    statusCell(entry),
                    thenAndNow(entry, vocabulary),
                    whyCell(entry, vocabulary)
                ]), {class: 'history-replay__table'})));
    }
    return node;
}

function statusCell(entry) {
    const compared = COMPARED.has(entry.status);
    return el('div', null,
        el('strong', {class: 'history-replay__status history-replay__status--' + (compared ? entry.status : 'unsent')}, statusWords(entry.status)),
        compared ? null : el('div', {class: 'small quiet'}, entry.replayed ? 'Not compared.' : 'Nothing was compared.'),
        entry.replayRunId ? el('div', {class: 'small'}, 'New run ', runLink(entry.replayRunId)) : null);
}

function thenAndNow(entry, vocabulary) {
    const side = (label, run) => el('div', {class: 'history-replay__side'},
        el('span', {class: 'small quiet'}, label + ' '),
        run ? [outcomeOf(vocabulary, run), ' ', stamp(run.result)] : none('no record of a run'));
    return [side('Then', entry.original), side('Now', entry.replayed)];
}

function whyCell(entry, vocabulary) {
    const differences = entry.differences || [];
    return [
        entry.reason ? el('p', null, entry.reason) : null,
        faultReport({reference: entry.faultReference, cause: entry.faultCause}),
        differences.length > 0 ? differenceTable(differences, entry, vocabulary) : null,
        (entry.notRecorded || []).map(text => el('div', {class: 'ledger--missing small'}, el('strong', null, 'NOT recorded. '), text)),
        !entry.reason && differences.length === 0 && entry.status === 'same'
            ? el('span', {class: 'quiet small'}, 'Same in everything the ledger compares.') : null
    ];
}

/** Each field that came back different, as it was and as it is now. An outcome code is shown with its words. */
function differenceTable(differences, entry, vocabulary) {
    const value = (field, text, run) => {
        if (text === undefined || text === null) {
            return none('none');
        }
        if (field === 'outcome' && run) {
            return [outcomeOf(vocabulary, {protocol: run.protocol, outcome: text}), el('div', {class: 'small'}, code(text))];
        }
        if (field === 'result') {
            return stamp(text);
        }
        return code(text);
    };
    return table([{text: 'Field', class: 'col-25'}, 'Was', 'Now'], differences.map(item => [
        code(item.field),
        value(item.field, item.was, entry.original),
        value(item.field, item.now, entry.replayed || entry.original)
    ]), {class: 'history-replay__diff'});
}

/** One run's entry in full, for a replay of a single run. */
function entryDetail(entry, vocabulary) {
    const compared = COMPARED.has(entry.status);
    const node = el('div', {class: 'history-replay__one'},
        el('p', {class: 'history-replay__headline history-replay__status--' + (compared ? entry.status : 'unsent')}, statusWords(entry.status),
            compared ? null : el('span', {class: 'history-replay__headline-note'}, entry.replayed ? ' Not compared.' : ' Nothing was compared.')),
        entry.reason ? el('p', null, entry.reason) : null,
        faultReport({reference: entry.faultReference, cause: entry.faultCause}),
        entry.status === 'same' && !entry.reason ? el('p', null, 'Everything the ledger compares came back the same.') : null,
        (entry.differences || []).length > 0 ? differenceTable(entry.differences, entry, vocabulary) : null,
        (entry.notRecorded || []).map(text => el('div', {class: 'ledger ledger--missing', role: 'alert'},
            el('p', {class: 'ledger__state'}, 'NOT recorded'), el('p', null, 'The ledger did not keep the run of this replay. It said:'),
            el('blockquote', null, text))));
    if (entry.replayed) {
        const run = entry.replayed;
        node.append(el('div', {class: 'history-replay__new'},
            el('p', {class: 'sub-heading'}, 'The run this replay made'),
            // What the service said this time and what to do next, in the server's words for the new run.
            // A refusal is worded once: when the reason above is the verdict's own headline, it is not set out twice.
            entry.verdict && entry.verdict.headline !== entry.reason ? el('p', {class: 'history-replay__verdict'}, toneMark(entry.verdict.tone), ' ', el('strong', null, entry.verdict.headline)) : null,
            // The lines under the headline say who answered: on mock, which scenario, and that a
            // replay is answered by the scenario the original run recorded, so the same answer is no news.
            entry.verdict && Array.isArray(entry.verdict.supporting) && entry.verdict.supporting.length > 0 && entry.verdict.headline !== entry.reason
                ? el('ul', {class: 'note-list small'}, entry.verdict.supporting.map(line => el('li', null, line))) : null,
            entry.nextStep && entry.nextStep.headline ? el('p', null, el('span', {class: 'quiet'}, 'Next step: '), entry.nextStep.headline,
                entry.nextStep.reason ? el('span', {class: 'small quiet'}, ' ' + entry.nextStep.reason) : null) : null,
            el('dl', {class: 'facts facts--ruled'},
                el('dt', null, 'Run'), el('dd', null, runLink(run.runId), ' ', el('span', {class: 'small quiet'}, 'opens its full record')),
                el('dt', null, 'Outcome'), el('dd', null, outcomeOf(vocabulary, run), ' ', el('span', {class: 'small'}, code(run.outcome))),
                el('dt', null, 'Test result'), el('dd', null, stamp(run.result)),
                el('dt', null, 'Ran against'), el('dd', null, whereRan(run), ', ', run.profile || 'no', ' profile, target ', run.target || 'not stated'),
                el('dt', null, 'Elapsed'), el('dd', null, millis(run.elapsedMillis) || none('not measured'))),
            el('p', {class: 'small quiet'}, 'A replay keeps what the ledger keeps, not the response itself. To read the response, '
                + 'load this case into the form and send it from its tab: the wire view of that result shows it.')));
    }
    return node;
}
