// The journey of a case through its four steps: the chain id, each step's last result, and what
// each step hands to the next (plan section 4).
//
// The rules, each held by WorkbenchFlowFrontendTest:
//   - the journey changes only when a result arrives, never on a preview or a refused send, so a
//     step that was not sent is never counted as sent;
//   - a hand-over goes only while the case on screen is the case it was made for (caseKey); the
//     same case typed another way keeps its key and its journey;
//   - a value an answer gave masked goes with no hand-over and is named instead;
//   - a Continue whose step was refused for a mask alone is not offered again for that hand-over;
//   - the page never works out which scenario an ID plays, nor whether a step is needed: the
//     server's chain view says it.

import {withoutMasks, masksIn} from './masks.js';

/** The steps, by number, as the server names them in the chain view and the vocabulary. */
export const STEPS = [
    {n: 1, step: 'COVERAGE', protocol: 'CRD'},
    {n: 2, step: 'QUESTIONNAIRE', protocol: 'DTR'},
    {n: 3, step: 'SUBMIT', protocol: 'PAS'},
    {n: 4, step: 'STATUS', protocol: 'PAS'}
];

/** The step a run's operation belongs to. */
export function stepOfOperation(operation) {
    const name = String(operation || '');
    if (name.startsWith('CRD_')) {
        return 1;
    }
    if (name.startsWith('DTR_')) {
        return 2;
    }
    if (name === 'PAS_SUBMIT') {
        return 3;
    }
    if (name === 'PAS_INQUIRE' || name.startsWith('SUBSCRIPTION') || name.startsWith('NOTIFICATION')) {
        return 4;
    }
    return null;
}

/** The step a next action leads to, or null for an action that opens no step. */
export function stepOfAction(action) {
    switch (action) {
        case 'START_DTR':
        case 'ANSWER_NEXT_QUESTION':
        case 'SUBMIT_RESPONSE':
            return 2;
        case 'START_PAS':
            return 3;
        case 'CHECK_STATUS':
        case 'DELIVER_SAMPLE_NOTIFICATION':
            return 4;
        default:
            return null;
    }
}

/**
 * True when a submission hands the Status step a claim to ask about. A submission that cannot have
 * reached the service hands on no claim identifier (the server leaves it out of its carry), so no
 * inquiry is offered from it, played or live; one that may have arrived keeps the way (history T19).
 */
export function offersInquiryWay(envelope) {
    if (!envelope || envelope.operation !== 'PAS_SUBMIT' || !envelope.nextStep || !envelope.nextStep.carry) {
        return false;
    }
    const claims = envelope.nextStep.carry.claimId;
    return Array.isArray(claims) && claims.some(one => typeof one === 'string' && one.trim() !== '');
}

/**
 * What a submission hands to the Status step: its claim, the submission's own run id (what an
 * inquiry and a sample notification are about) and the codes it sent. Null when it offers no inquiry.
 */
export function statusCarry(envelope) {
    if (!offersInquiryWay(envelope)) {
        return null;
    }
    const carry = envelope.nextStep.carry;
    const strings = values => (Array.isArray(values) ? values.filter(one => typeof one === 'string') : []);
    return {
        claimId: strings(carry.claimId),
        runId: typeof envelope.runId === 'string' && envelope.runId !== '' ? [envelope.runId] : [],
        procedureCodes: strings(carry.procedureCodes)
    };
}

export function createJourney() {
    let chainId = null;
    let results = {};
    let carries = {};
    let withdrawn = {};
    let chain = null;

    return {
        /** The chain id to post: "new" until the server has given one for this case. */
        chainId() {
            return chainId || 'new';
        },
        /** The chain id the server gave, or null before the first result. */
        knownChain() {
            return chainId;
        },
        /**
         * A run envelope arrived for a step, sent for the case with this key. The one place the
         * journey changes. Returns the step's record.
         */
        resulted(step, envelope, key, epoch) {
            if (!envelope || typeof envelope !== 'object') {
                return null;
            }
            if (typeof envelope.chainId === 'string' && envelope.chainId !== '') {
                chainId = envelope.chainId;
            }
            results[step] = {envelope, key, epoch};
            const next = envelope.nextStep && envelope.nextStep.carry && Object.keys(envelope.nextStep.carry).length > 0
                ? stepOfAction(envelope.nextStep.action) : null;
            if (step === 1 && next !== null) {
                const split = withoutMasks(envelope.nextStep.carry);
                carries[2] = {carry: split.carry, masked: split.masked, labels: envelope.nextStep.carryLabels || {}, key, from: 1};
                delete withdrawn[2];
            }
            return results[step];
        },
        /** The step's last result, or null. */
        result(step) {
            return results[step] || null;
        },
        /**
         * What the step before handed to this step, while the case is the one it was made for:
         * {carry, masked, labels, from}; null otherwise.
         */
        carry(step, key) {
            const handed = carries[step];
            return handed && handed.key === key ? handed : null;
        },
        /** A step module hands its carry on (2 to 3, 3 to 4). Masked values are kept apart to be named. */
        handOn(step, carry, key) {
            if (!carry) {
                delete carries[step];
                return;
            }
            const split = withoutMasks(carry);
            carries[step] = {carry: split.carry, masked: split.masked, labels: {}, key, from: step - 1};
            delete withdrawn[step];
        },
        /**
         * The line a step shows above what it sends: from the step before, not handed on because
         * the case changed, or nothing came. Masked values are named, each on its own.
         */
        handedLine(step, key) {
            const handed = carries[step];
            if (!handed) {
                return {kind: 'none', words: 'Not handed on: nothing came from step ' + (step - 1) + '. Sent from the case and the options.'};
            }
            if (handed.key !== key) {
                return {kind: 'changed', words: 'Not handed on: the case changed since step ' + handed.from + '.'};
            }
            return {kind: 'handed', masked: handed.masked.map(one => (handed.labels[one.key] || one.key) + ': masked, not handed on')};
        },
        /**
         * A send of a step was refused. When its refusal is about a mask alone, the hand-over that
         * led to it is not offered again: pressing Continue once more would send the same refusal.
         */
        refused(step, error) {
            if (refusedForAMask(error)) {
                withdrawn[step] = true;
            }
        },
        /** False once the Continue into this step was withdrawn for a mask. */
        offered(step) {
            return withdrawn[step] !== true;
        },
        /** The server's chain view, as last read. */
        chain() {
            return chain;
        },
        setChain(view) {
            chain = view && typeof view === 'object' ? view : null;
        },
        /** Start a new case: every step, carry and the chain forgotten. */
        reset() {
            chainId = null;
            results = {};
            carries = {};
            withdrawn = {};
            chain = null;
        }
    };
}

/** True when every reason a refusal gives names a mask, and it gives at least one. */
export function refusedForAMask(error) {
    if (!error || typeof error !== 'object') {
        return false;
    }
    const reasons = [];
    for (const each of Array.isArray(error.fieldErrors) ? error.fieldErrors : []) {
        reasons.push(each.message);
    }
    if (reasons.length === 0 && error.title) {
        reasons.push(error.title);
    }
    return reasons.length > 0 && reasons.every(reason => masksIn(reason) !== null);
}
