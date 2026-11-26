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
        /** A chain read back after a reload: the runs that follow post to it. */
        setChainId(id) {
            chainId = typeof id === 'string' && id !== '' ? id : chainId;
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

/* ---------- the reload (R-38) ---------- */

/** The words a reload shows when a run of the case is no longer in the trail. */
export const NOT_KEPT_LINE = 'Not kept in the trail: the case shows what the ledger kept.';

// The step 4 rows a reload fills, each from the latest run of its operations.
const STATUS_ROWS = {
    inquiry: ['PAS_INQUIRE'],
    subscription: ['SUBSCRIPTION_CREATE', 'SUBSCRIPTION_GET', 'SUBSCRIPTION_UPDATE', 'SUBSCRIPTION_DELETE']
};

/**
 * Every run a chain view lists, from its stages and its other runs: a subscription call is of no
 * stage (its protocol is not CRD, DTR or PAS), so it is among the other runs.
 */
export function chainRuns(chain) {
    const runs = [];
    if (!chain || typeof chain !== 'object') {
        return runs;
    }
    for (const stage of Array.isArray(chain.stages) ? chain.stages : []) {
        for (const run of stage && Array.isArray(stage.runs) ? stage.runs : []) {
            runs.push(run);
        }
    }
    for (const run of Array.isArray(chain.otherRuns) ? chain.otherRuns : []) {
        runs.push(run);
    }
    return runs.filter(run => run && typeof run.runId === 'string' && run.runId !== '');
}

/**
 * The runs a reload redraws: for steps 1 to 3 the latest run the chain's steps name, and for step 4
 * the latest inquiry and the latest subscription call, each by its own operation (a notification is
 * not a run envelope and is read from the receiver). Each is {runId, at} with the time the chain
 * gives it. Returns {1, 2, 3, inquiry, subscription}, a step with no run left out.
 */
export function runsToRedraw(chain) {
    const out = {};
    const all = chainRuns(chain);
    const timeOf = runId => {
        const found = all.find(run => run.runId === runId);
        return found && typeof found.timestamp === 'string' ? found.timestamp : null;
    };
    for (const step of chain && Array.isArray(chain.steps) ? chain.steps : []) {
        const known = STEPS.find(each => each.step === step.step);
        if (known && known.n < 4 && typeof step.latestRunId === 'string' && step.latestRunId !== '') {
            out[known.n] = {runId: step.latestRunId, at: timeOf(step.latestRunId)};
        }
    }
    for (const [row, operations] of Object.entries(STATUS_ROWS)) {
        let latest = null;
        for (const run of all) {
            if (operations.includes(run.operation) && (!latest || String(run.timestamp || '') >= String(latest.timestamp || ''))) {
                latest = run;
            }
        }
        if (latest) {
            out[row] = {runId: latest.runId, at: typeof latest.timestamp === 'string' ? latest.timestamp : null};
        }
    }
    return out;
}

/**
 * Reads back a case and its results after a reload or "Load into the Workbench": the chain, then
 * each run to redraw from the trail, and from the ledger the runs the trail no longer keeps.
 * target: {chainId} or {runId}. ask(method, path): api.js's call. Never throws.
 *
 * Returns {ok: false, error} when the chain cannot be read, else {ok: true, chainId, chain, runs:
 * {<1|2|3|inquiry|subscription>: {runId, at, step, envelope, entry} | {runId, at, step, record,
 * line}}, theCase: {from: 'trail', entry} (the newest kept run that posted a case) | {from: 'ledger',
 * record} | null, notKept: true when a run came from the ledger}. Nothing of it is written anywhere but the page's memory.
 */
export async function readBack(target, ask) {
    let chainId = target && typeof target.chainId === 'string' ? target.chainId : null;
    const firstKept = {};
    if (!chainId && target && typeof target.runId === 'string') {
        // A run named alone leads to its chain: the trail names it, else the ledger line does.
        const kept = await ask('GET', '/api/trail/' + encodeURIComponent(target.runId));
        if (kept.ok && kept.data && kept.data.kept === true && kept.data.entry) {
            firstKept[target.runId] = kept.data;
            chainId = kept.data.entry.chainId || (kept.data.entry.envelope && kept.data.entry.envelope.chainId) || null;
        }
        if (!chainId) {
            const line = await ask('GET', '/api/runs/' + encodeURIComponent(target.runId));
            if (!line.ok) {
                return {ok: false, error: line.error};
            }
            chainId = line.data && typeof line.data.chainId === 'string' ? line.data.chainId : null;
        }
        if (!chainId) {
            return {ok: false, error: {kind: 'NOT_FOUND', title: 'This run belongs to no journey, so there is no case to load.'}};
        }
    }
    if (!chainId) {
        return {ok: false, error: {kind: 'NOT_FOUND', title: 'No journey was named.'}};
    }
    const read = await ask('GET', '/api/chains/' + encodeURIComponent(chainId));
    if (!read.ok) {
        return {ok: false, error: read.error};
    }
    const chain = read.data || {};
    const wanted = runsToRedraw(chain);
    const runs = {};
    let notKept = false;
    let newestEntry = null;
    let newestRecord = null;
    for (const [slot, run] of Object.entries(wanted)) {
        const step = slot === 'inquiry' || slot === 'subscription' ? 4 : Number(slot);
        const kept = firstKept[run.runId] || await keptRun(run.runId, ask);
        if (kept && kept.kept === true && kept.entry && kept.entry.envelope) {
            runs[slot] = {runId: run.runId, at: run.at, step, envelope: kept.entry.envelope, entry: kept.entry};
            const when = kept.keptAt || run.at || '';
            // A subscription call posts no case (no member, no codes): the case is read from a run that posted one.
            const carriesCase = kept.entry.case && typeof kept.entry.case === 'object' && kept.entry.case.member;
            if (carriesCase && (!newestEntry || String(when) >= String(newestEntry.when))) {
                newestEntry = {when, entry: kept.entry};
            }
            continue;
        }
        notKept = true;
        const line = await ask('GET', '/api/runs/' + encodeURIComponent(run.runId));
        const record = line.ok && line.data ? line.data : null;
        runs[slot] = {runId: run.runId, at: run.at, step, record, line: kept && typeof kept.line === 'string' ? kept.line : null};
        if (record && (!newestRecord || String(record.timestamp || '') >= String(newestRecord.timestamp || ''))) {
            newestRecord = record;
        }
    }
    const theCase = newestEntry ? {from: 'trail', entry: newestEntry.entry} : newestRecord ? {from: 'ledger', record: newestRecord} : null;
    return {ok: true, chainId, chain, runs, theCase, notKept};
}

// The steps whose request carries the diagnosis codes and the servicing provider of the case (D-321).
const CASE_PART_STEPS = ['CRD_ORDER_SIGN', 'CRD_ORDER_SELECT', 'DTR_QUESTIONNAIRE_PACKAGE', 'PAS_SUBMIT'];

/**
 * The case a kept run posted, as a reload reads it back, with the diagnosis codes and the servicing
 * provider of the newest kept run of a step that sends them (D-321) when the run is of one that sends
 * neither (a later questionnaire request, an inquiry): the case is one, and a reload keeps both.
 * entry: the trail's entry of the run; runs: readBack's runs.
 */
export function casePosted(entry, runs) {
    const posted = {...(entry && entry.case && typeof entry.case === 'object' ? entry.case : {})};
    if (entry && entry.envelope && CASE_PART_STEPS.includes(entry.envelope.operation)) {
        return posted;
    }
    let giver = null;
    for (const run of Object.values(runs || {})) {
        const kept = run && run.entry && run.entry.case && run.entry.envelope ? run : null;
        if (kept && CASE_PART_STEPS.includes(kept.entry.envelope.operation) && (!giver || String(kept.at || '') >= String(giver.at || ''))) {
            giver = kept;
        }
    }
    for (const name of ['diagnosisCodes', 'servicingProvider']) {
        if (giver && posted[name] === undefined && giver.entry.case[name] !== undefined) {
            posted[name] = giver.entry.case[name];
        }
    }
    return posted;
}

async function keptRun(runId, ask) {
    const answer = await ask('GET', '/api/trail/' + encodeURIComponent(runId));
    return answer.ok && answer.data ? answer.data : null;
}

/**
 * The case fields a ledger line keeps, as the case cards take them: the member ID only where
 * tracking recorded it (never the token or its last characters), the NPI, the codes, the date of
 * service and the line of business. A field the line does not hold is left out, never guessed.
 */
export function caseFromLedger(record) {
    const out = {member: {}, provider: {}, procedureCodes: []};
    if (!record || typeof record !== 'object') {
        return out;
    }
    if (typeof record.memberId === 'string' && record.memberId !== '') {
        out.member.memberId = record.memberId;
    }
    if (typeof record.lineOfBusiness === 'string' && record.lineOfBusiness !== '') {
        out.member.lineOfBusiness = record.lineOfBusiness;
    }
    if (typeof record.npi === 'string' && record.npi !== '') {
        out.provider.npi = record.npi;
    }
    out.procedureCodes = (Array.isArray(record.procedureCodes) ? record.procedureCodes : [])
        .filter(code => typeof code === 'string' && code !== '').map(code => ({code}));
    if (typeof record.dateOfService === 'string' && record.dateOfService !== '') {
        out.dateOfService = record.dateOfService;
    }
    return out;
}
