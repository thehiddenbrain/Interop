// What the views of the History tab share: the link that opens one run, and the few facts of a
// ledger line that every view writes the same way.
//
// A ledger line holds codes, times and identifiers. It never holds a member name, a date of
// birth, or the request or the response (section 15). The member appears the way the tracking
// policy wrote it: the member ID when recording it is switched on, otherwise a token and the last
// characters. A token is not a member ID, and it is never labelled as one.

import {el, code, token, when} from './dom.js';
import {outcome} from './marks.js';

/*
 * The ledger files a run under the UTC day it was made (one file a day, runs-YYYY-MM-DD.jsonl),
 * and its From and To filters, the export, the replay of dates and "today" all count by that day.
 * Times on screen are in the viewer's own zone. Where the two days differ the screen says so,
 * because otherwise a run made in the evening west of UTC is listed under one date and filtered,
 * exported and replayed under the next.
 */

/** The UTC day of a timestamp, YYYY-MM-DD, or null when it cannot be read. */
export function utcDay(timestamp) {
    const date = new Date(timestamp);
    return Number.isNaN(date.getTime()) ? null : date.toISOString().slice(0, 10);
}

function localDay(date) {
    const pad = value => String(value).padStart(2, '0');
    return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate());
}

/** A time as when() shows it, with the UTC day the ledger files it under when that is not the date on the viewer's clock. */
export function whenFiled(timestamp) {
    const shown = when(timestamp);
    const day = utcDay(timestamp);
    if (!shown || !day || day === localDay(new Date(timestamp))) {
        return shown;
    }
    return el('span', {class: 'history-when'}, shown,
        el('span', {class: 'history-when__day', title: 'The ledger files this run under the UTC day ' + day + ', and the From and To dates find it there.'},
            'filed under ' + day + ' UTC'));
}

/** Says, in a sentence, that the dates are UTC days and when the next UTC day starts on the viewer's clock. */
export function utcDayNote() {
    const now = new Date();
    const offset = -now.getTimezoneOffset();
    if (offset === 0) {
        return 'Dates are UTC days, as the ledger files its runs. Your clock is on UTC, so the two agree.';
    }
    const hours = Math.abs(offset) / 60;
    const amount = (Number.isInteger(hours) ? String(hours) : hours.toFixed(1)) + (hours === 1 ? ' hour' : ' hours');
    const nextUtcMidnight = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1));
    const at = nextUtcMidnight.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'});
    return 'Dates are UTC days, as the ledger files its runs, not the dates on your clock. Your clock is ' + amount
        + (offset < 0 ? ' behind' : ' ahead of') + ' UTC, so each UTC day starts at ' + at + ' your time'
        + (offset < 0 ? ', the evening before' : '') + '. A run whose date on your clock differs says "filed under" the UTC day.';
}

/** Today's UTC day, the day the ledger counts as today. */
export function utcToday() {
    return new Date().toISOString().slice(0, 10);
}

/** The address of one run on the History tab. A run id shown anywhere in the tool can point here. */
export function runAddress(runId) {
    return '#history/runs/' + encodeURIComponent(runId);
}

/** A run id that opens the run. An anchor, so it can also be opened in a new browser tab. */
export function runLink(runId, label) {
    return el('a', {href: runAddress(runId), class: 'history-link'}, label || code(runId, 'literal--id'));
}

/** The address of one chain, and a link to it. */
export function chainLink(chainId, label) {
    return el('a', {href: '#history/chains/' + encodeURIComponent(chainId), class: 'history-link'}, label || code(chainId, 'literal--id'));
}

/** A link to the runs of one chain in the Runs view. */
export function chainRunsLink(chainId, label) {
    return el('a', {href: '#history/runs?chainId=' + encodeURIComponent(chainId), class: 'history-link'}, label);
}

/** A link to the runs of one suite run in the Runs view. It works after a restart, because the ledger keeps them. */
export function suiteRunsLink(suiteRunId, label) {
    return el('a', {href: '#history/runs?suiteRunId=' + encodeURIComponent(suiteRunId), class: 'history-link'},
        label || code(suiteRunId, 'literal--id'));
}

export function none(text) {
    return el('span', {class: 'presence presence--none'}, text);
}

/**
 * The member as the ledger line holds it, with words that say which form it is. Returns
 * {label, node}: the label names what the value is, so a token is never shown as a member ID.
 */
export function memberAsRecorded(run) {
    if (run.memberId) {
        return {label: 'Member ID', node: code(run.memberId)};
    }
    if (run.memberToken) {
        return {
            label: 'Member token',
            node: [code(run.memberToken.slice(0, 12), 'literal--id'), run.memberIdLast4 ? [' ending ', code(run.memberIdLast4)] : null,
                el('div', {class: 'small quiet'}, 'Not the member ID. The ledger keeps a token in its place, so two runs of one member can be matched.')]
        };
    }
    return {label: 'Member', node: none('not recorded')};
}

/** The same in a few words, for a table cell. */
export function memberShort(run) {
    if (run.memberId) {
        return code(run.memberId);
    }
    if (run.memberToken) {
        return [el('span', {class: 'small quiet'}, 'token '), code(run.memberToken.slice(0, 8), 'literal--id'),
            run.memberIdLast4 ? [el('span', {class: 'small quiet'}, ' ending '), code(run.memberIdLast4)] : null];
    }
    return none('not recorded');
}

/** The outcome of a run in the server's words and tone. A code it has given no words for is shown as the code, with no tone. */
export function outcomeOf(vocabulary, run) {
    const option = vocabulary ? vocabulary.outcome(run.protocol, run.outcome) : null;
    return option ? outcome(option.label, option.tone) : outcome(code(run.outcome), null);
}

/**
 * What the tester expected, in the same words as the outcome when the server has words for that
 * code, with the code beneath. An expectation the server has no words for is shown as written.
 */
export function expectedOf(vocabulary, run) {
    if (!run.expected) {
        return null;
    }
    const option = vocabulary ? vocabulary.outcome(run.protocol, run.expected) : null;
    return option ? [el('span', {class: 'history-expected'}, option.label), el('div', {class: 'small'}, code(run.expected))] : code(run.expected);
}

/** What ran, in the server's words for the operation. */
export function whatRan(vocabulary, run) {
    const operation = vocabulary ? vocabulary.operation(run.operation) : null;
    return operation ? operation.label : run.operation;
}

/** Where it ran: the environment and its kind, or none. */
export function whereRan(run) {
    return run.environment
        ? [run.environment, run.environmentKind ? el('span', {class: 'kind kind--' + token(run.environmentKind)}, String(run.environmentKind)) : null]
        : el('span', {class: 'quiet'}, 'no environment');
}

/** A list of codes, comma separated, or a statement that there are none. */
export function codes(values, emptyText) {
    if (!values || values.length === 0) {
        return none(emptyText || 'none');
    }
    return values.map((value, index) => [index > 0 ? ', ' : null, code(value)]);
}
