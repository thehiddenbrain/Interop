// What the parts of Audit share: the server's words for what a ledger line holds as codes, the few
// facts of a line every part writes the same way, and the rules a replay and a total keep.
//
// A ledger line holds codes, times and identifiers, never a member name, a date of birth, a request
// or a response. The member appears as the tracking policy wrote it: the member ID when recording it
// is switched on, otherwise a token, which is never labelled as an ID.

import {el, chip} from './dom.js';

/** The address of one run in Audit. A run id shown anywhere in the tool can point here. */
export function runAddress(runId) {
    return '#audit/runs/' + encodeURIComponent(runId);
}

/** A run id, or other words, as a link that opens the run. */
export function runLink(runId, label) {
    return el('a', {class: 'table__link', href: runAddress(runId)}, label || el('span', {class: 'mono'}, runId));
}

/** Grey words the tool says about a value it has not got, not the dashed frame of a value a service left out. */
export function none(text) {
    return el('span', {class: 'muted small'}, text);
}

/**
 * The server's words for the codes of a ledger line, from GET /api/vocabulary, or null lookups when
 * the vocabulary could not be read: a code with no words is then shown as the code it is.
 */
export function wordsOf(vocabulary) {
    const v = vocabulary && typeof vocabulary === 'object' ? vocabulary : {};
    const steps = Array.isArray(v.steps) ? v.steps : [];
    const operations = Array.isArray(v.operations) ? v.operations : [];
    const outcomes = v.outcomes && typeof v.outcomes === 'object' ? v.outcomes : {};
    const find = (list, code) => (Array.isArray(list) ? list : []).find(each => each && each.code === code) || null;
    return {
        steps,
        operation: code => find(operations, code),
        step: code => steps.find(each => each.step === code) || null,
        /** The step a run of this operation belongs to, or null for a lookup or a request sent as written. */
        stepOf(operationCode) {
            const operation = find(operations, operationCode);
            return operation && operation.step ? steps.find(each => each.step === operation.step) || null : null;
        },
        /**
         * An outcome in its protocol's words. A notification is a run of the subscription protocol
         * with outcomes of its own, and every protocol can end in one of the tool's own outcomes
         * (blocked, not signed in, a failure of this tool), so those groups are asked after it.
         */
        outcome(protocol, code) {
            const name = String(protocol || '').toUpperCase();
            const groups = [name];
            if (name === 'SUBSCRIPTION') {
                groups.push('NOTIFICATION');
            }
            groups.push('RUN');
            for (const group of groups) {
                const found = find(outcomes[group], code);
                if (found) {
                    return found;
                }
            }
            return null;
        },
        /** The outcomes a step's runs can end in, each code once: for the Outcome filter. */
        outcomesOf(step) {
            const groups = step ? Array.from(new Set(operations.filter(each => each.step === step.step).map(each => each.protocol)))
                : Object.keys(outcomes);
            if (groups.includes('SUBSCRIPTION')) {
                groups.push('NOTIFICATION');
            }
            if (step) {
                groups.push('RUN');
            }
            const seen = new Map();
            for (const group of groups) {
                for (const option of Array.isArray(outcomes[group]) ? outcomes[group] : []) {
                    if (option && !seen.has(option.code)) {
                        seen.set(option.code, option);
                    }
                }
            }
            return Array.from(seen.values());
        },
        nextAction: code => find(v.nextActions, code),
        testResult: code => find(v.testResults, String(code || '').toUpperCase()),
        answered: code => find(v.answeredBy, code)
    };
}

/** The outcome of a run as a chip in the server's short words and tone; the code itself when it has none. */
export function outcomeChip(words, run) {
    const option = words.outcome(run.protocol, run.outcome);
    if (!option) {
        return run.outcome ? chip(run.outcome, {mono: true}) : none('not recorded');
    }
    return chip(option.short || option.label, {tone: option.tone, title: option.label});
}

/** The test result of a run as its chip, in the server's label: passed, failed, or unverified dashed. */
export function testChip(words, result) {
    const code = String(result || '').toLowerCase();
    const found = words.testResult(code);
    if (!found) {
        return code ? chip(code) : none('not recorded');
    }
    return chip(found.label, {tone: code === 'pass' || code === 'fail' ? code : 'unverified', dashed: code === 'unverified'});
}

/** A ledger timestamp as UTC, "2026-11-22 14:06:08 UTC", the clock the ledger files runs by; null when it cannot be read. */
export function utcText(timestamp) {
    const date = new Date(timestamp);
    if (!timestamp || Number.isNaN(date.getTime())) {
        return null;
    }
    const iso = date.toISOString();
    return iso.slice(0, 10) + ' ' + iso.slice(11, 19) + ' UTC';
}

/** Today's UTC day, the day the ledger counts as today. */
export function utcToday() {
    return new Date().toISOString().slice(0, 10);
}

/** The member as the ledger line holds it, in a few words: the ID, or a token that is said to be one. */
export function memberShort(run) {
    if (run.memberId) {
        return el('span', {class: 'mono'}, run.memberId);
    }
    if (run.memberToken) {
        return el('span', {title: 'Not the member ID. The ledger keeps a token in its place, so two runs of one member can be matched.'},
            el('span', {class: 'muted small'}, 'token '), el('span', {class: 'mono'}, String(run.memberToken).slice(0, 8)),
            run.memberIdLast4 ? [el('span', {class: 'muted small'}, ' ending '), el('span', {class: 'mono'}, run.memberIdLast4)] : null);
    }
    return null;
}

/**
 * The ids a replay of the runs listed sends: the originals only, a run a replay made being left
 * out, and oldest first. The list is newest first and the server sends runs in the order given, so
 * without the reversal an inquiry would be sent before the submission it asks about.
 */
export function replayIds(listed) {
    return listed.filter(run => !run.replayOfRunId).map(run => run.runId).slice().reverse();
}

/**
 * The time a set of runs took, never read as covering a run nobody timed: the runs that carry a
 * time are counted, and the words say how many of the runs that is.
 */
export function timedTotal(runs) {
    const count = runs.length;
    const timed = runs.filter(run => typeof run.elapsedMillis === 'number');
    const millis = timed.reduce((sum, run) => sum + run.elapsedMillis, 0);
    return totalWords(count, timed.length, millis.toLocaleString('en-US') + ' ms');
}

/** The words of a total: "4 runs taking 500 ms in all", "4 runs, 3 of them measured, taking 500 ms in all", "1 run, not measured". */
export function totalWords(runCount, measured, took) {
    const runs = runCount + (runCount === 1 ? ' run' : ' runs');
    if (measured === 0) {
        return runCount === 1 ? runs + ', not measured' : runs + ', none of them measured';
    }
    if (measured === runCount) {
        return runs + ' taking ' + took + ' in all';
    }
    return runs + ', ' + measured + ' of them measured, taking ' + took + ' in all';
}
