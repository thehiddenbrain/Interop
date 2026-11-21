// The words for what a ledger line holds as codes: the label and tone of each outcome, and the
// label of each operation.
//
// A ledger line keeps codes only. The server serves the words for them: every scenarios endpoint
// lists the outcomes of its protocol with their labels and tones, and the effective configuration
// lists every operation with its label. This page asks for them and keeps them for as long as the
// page is open. Nothing is written here: a code the server has given no words for is shown as the
// code it is, with no tone, which is the honest fallback.

import * as api from './api.js';

// Which scenarios endpoint gives the outcomes of which protocol. Subscription outcomes are served
// with PAS, because subscriptions belong to the PAS tab.
const SOURCES = [
    {path: '/api/crd/scenarios', protocols: ['CRD']},
    {path: '/api/dtr/scenarios', protocols: ['DTR']},
    {path: '/api/pas/scenarios', protocols: ['PAS', 'SUBSCRIPTION']}
];

/**
 * Reads the vocabulary. Never fails: what could not be read is listed in problems, and the
 * lookups below then answer null for it.
 */
export async function loadVocabulary() {
    const byProtocol = new Map();   // protocol -> Map(code -> {code, label, tone})
    const nextActions = new Map();  // next action name -> its words, as the scenarios endpoints serve them
    const operations = new Map();   // operation -> {operation, label, protocol, write, outbound}
    const problems = [];
    const answers = await Promise.all(SOURCES.map(source => api.get(source.path)));
    answers.forEach((answer, index) => {
        if (answer.kind !== 'ok') {
            problems.push(answer.body.title);
            return;
        }
        const outcomes = new Map((answer.body.outcomes || []).map(option => [option.code, option]));
        for (const protocol of SOURCES[index].protocols) {
            byProtocol.set(protocol, outcomes);
        }
        for (const option of answer.body.nextActions || []) {
            nextActions.set(option.code, option.label);
        }
    });
    const effective = await api.get('/api/config/effective');
    if (effective.kind === 'ok') {
        for (const entry of effective.body.operations || []) {
            operations.set(entry.operation, entry);
        }
    } else {
        problems.push(effective.body.title);
    }
    return {
        problems,

        /** The outcome of a protocol as the server words it, or null when it has given no words for that code. */
        outcome(protocol, code) {
            const outcomes = byProtocol.get(String(protocol || '').toUpperCase());
            if (outcomes) {
                return outcomes.get(code) || null;
            }
            // A run sent as written (RAW) has no list of its own; its outcomes are the shared ones every protocol words alike
            // (not signed in, refused, blocked), so the first protocol that words the code gives its words.
            if (String(protocol || '').toUpperCase() === 'RAW') {
                for (const shared of ['CRD', 'DTR', 'PAS']) {
                    const option = byProtocol.has(shared) ? byProtocol.get(shared).get(code) : null;
                    if (option) {
                        return option;
                    }
                }
            }
            return null;
        },

        /** A next action's words as the server serves them, or null when it has given none for that name. */
        nextAction: name => nextActions.get(name) || null,

        /** The operation as the server words it, or null. */
        operation(name) {
            return operations.get(name) || null;
        },

        /** Every operation the server lists, in its order. */
        operations: () => Array.from(operations.values()),

        /**
         * Every outcome code of the protocols given (or of every protocol), each once, for the
         * outcome filter. A code that two protocols word differently is offered once, with the
         * words of each protocol before its own name, so no choice is left as a bare code.
         */
        outcomeChoices(protocols) {
            const wanted = protocols && protocols.length > 0 ? protocols : Array.from(byProtocol.keys());
            const seen = new Map();
            for (const protocol of wanted) {
                for (const option of (byProtocol.get(protocol) || new Map()).values()) {
                    const earlier = seen.get(option.code) || {code: option.code, words: []};
                    earlier.words.push({protocol, label: option.label});
                    seen.set(option.code, earlier);
                }
            }
            return Array.from(seen.values()).map(entry => {
                const labels = [...new Set(entry.words.map(word => word.label).filter(Boolean))];
                // Each set of words once, after every protocol that uses it.
                const byWords = labels.map(label => entry.words.filter(word => word.label === label).map(word => word.protocol).join(' and ')
                    + ': ' + label);
                return {code: entry.code, label: labels.length === 0 ? null : labels.length === 1 ? labels[0] : byWords.join('; ')};
            });
        }
    };
}
