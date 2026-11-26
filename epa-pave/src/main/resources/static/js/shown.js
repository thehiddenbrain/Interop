// A value drawn together with whether it was returned at all.
//
// The server sends every view value as {presence, value}. Absent, null and empty are three
// different findings when chasing a contract mismatch, so each has its own words and a dashed
// chip, and none of them is ever a blank cell.

import {el, chip} from './dom.js';

const WORDS = {ABSENT: 'not returned', NULL: 'returned as null', EMPTY: 'returned empty'};
const MEANINGS = {
    ABSENT: 'The response did not carry this field.',
    NULL: 'The response carried this field with an explicit null.',
    EMPTY: 'The response carried this field and it was empty.'
};

/** The words for a presence that is not a value. A presence this page does not know is given back as its own name. */
export function presenceWords(presence) {
    const name = String(presence || 'ABSENT');
    return Object.prototype.hasOwnProperty.call(WORDS, name) ? WORDS[name] : name;
}

/** The dashed chip for a presence that is not a value. */
export function presenceMark(presence) {
    const name = String(presence || 'ABSENT');
    return chip(presenceWords(name), {dashed: true, title: MEANINGS[name] || 'A presence this page does not know: ' + name});
}

/**
 * One Shown value as a node. options.mono: literal data (codes, identifiers). A missing Shown (the
 * server left the whole property out) is drawn as not returned.
 */
export function shown(value, options) {
    if (!value || typeof value !== 'object' || value.presence === undefined) {
        return presenceMark('ABSENT');
    }
    if (value.presence !== 'VALUE') {
        return presenceMark(value.presence);
    }
    return el('span', {class: options && options.mono ? 'mono' : null}, String(value.value));
}

/** True when a Shown holds a value. */
export function hasValue(value) {
    return Boolean(value) && value.presence === 'VALUE';
}
