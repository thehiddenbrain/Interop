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
            return outcomes ? outcomes.get(code) || null : null;
        },

        /** The operation as the server words it, or null. */
        operation(name) {
            return operations.get(name) || null;
        },

        /** Every operation the server lists, in its order. */
        operations: () => Array.from(operations.values()),

        /**
         * Every outcome code of the protocols given (or of every protocol), each once, for the
         * outcome filter. A code that two protocols word differently is offered under its code.
         */
        outcomeChoices(protocols) {
            const wanted = protocols && protocols.length > 0 ? protocols : Array.from(byProtocol.keys());
            const seen = new Map();
            for (const protocol of wanted) {
                for (const option of (byProtocol.get(protocol) || new Map()).values()) {
                    const earlier = seen.get(option.code);
                    if (!earlier) {
                        seen.set(option.code, {code: option.code, label: option.label});
                    } else if (earlier.label !== option.label) {
                        earlier.label = null;
                    }
                }
            }
            return Array.from(seen.values());
        }
    };
}
