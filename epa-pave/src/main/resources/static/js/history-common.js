// What the views of the History tab share: the link that opens one run, and the few facts of a
// ledger line that every view writes the same way.
//
// A ledger line holds codes, times and identifiers. It never holds a member name, a date of
// birth, or the request or the response (section 15). The member appears the way the tracking
// policy wrote it: the member ID when recording it is switched on, otherwise a token and the last
// characters. A token is not a member ID, and it is never labelled as one.

import {el, code, token} from './dom.js';
import {outcome} from './marks.js';

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
