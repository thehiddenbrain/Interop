// The short words of the screens, as the server serves them (GET /api/vocabulary): step names,
// outcome short labels and tones, next-step buttons, test result labels, who answered, the kinds
// and limits of attachments. The page keeps no copy of them: a view looks a code up here and draws
// nothing for a word the server has not given.

import {call} from './api.js';

let epochOf = () => null;
let cached = null;

/**
 * Tells this module where the configuration epoch is read, so the words are read again once it
 * moves. The frame calls it once at start; until then every call reads the words again.
 */
export function useEpoch(source) {
    if (typeof source === 'function') {
        epochOf = source;
    }
}

/**
 * The vocabulary, read once per configuration epoch: a promise of the server's answer, or of null
 * when the server did not give one. A failed read is not kept, so the next call asks again.
 */
export function vocabulary() {
    const epoch = epochOf();
    if (cached && epoch !== null && cached.epoch === epoch) {
        return cached.words;
    }
    const words = call('GET', '/api/vocabulary').then(answer => {
        if (answer.ok && answer.data && typeof answer.data === 'object') {
            return answer.data;
        }
        if (cached && cached.words === words) {
            cached = null;
        }
        return null;
    });
    cached = {epoch, words};
    return words;
}
