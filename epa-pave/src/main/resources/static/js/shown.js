// Rendering a value together with whether it was returned at all.
//
// The server sends every view value as {presence, value}. Absent, null and empty are three
// different findings when chasing a contract mismatch, so each has its own mark and its own
// words, and none of them is ever a blank cell:
//
//   ABSENT  the field was not in the response     "not returned"   dashed outline
//   NULL    the field was present and null        "null"           filled, literal
//   EMPTY   the field was present and empty       "empty"          solid outline
//   VALUE   the field held a value                the value itself

import {el, code, externalLink} from './dom.js';

const WORDS = {ABSENT: 'not returned', NULL: 'null', EMPTY: 'empty'};
const MEANINGS = {
    ABSENT: 'The response did not carry this field.',
    NULL: 'The response carried this field with an explicit null.',
    EMPTY: 'The response carried this field and it was empty.'
};

/** The mark for a presence that is not a value. A presence this page does not know is shown as its own name. */
export function presenceMark(presence) {
    const name = String(presence);
    const known = Object.prototype.hasOwnProperty.call(WORDS, name);
    return el('span', {
        class: 'presence presence--' + (known ? name.toLowerCase() : 'other'),
        title: known ? MEANINGS[name] : 'A presence this page does not know: ' + name
    }, known ? WORDS[name] : name);
}

/**
 * One Shown value as a node.
 *
 * options.as: 'text' (default), 'literal' for codes, identifiers and JSON text, 'block' for long
 * text whose line breaks matter, 'link' for an address taken from a response.
 * A missing Shown (the server left the whole property out) is treated as not returned.
 */
export function shown(value, options) {
    const as = options && options.as ? options.as : 'text';
    if (!value || typeof value !== 'object' || value.presence === undefined) {
        return presenceMark('ABSENT');
    }
    if (value.presence !== 'VALUE') {
        return presenceMark(value.presence);
    }
    const text = String(value.value);
    if (as === 'literal') {
        return code(text);
    }
    if (as === 'block') {
        return el('div', {class: 'vendor-text'}, text);
    }
    if (as === 'link') {
        return externalLink(text, options.label);
    }
    return el('span', {class: 'vendor-inline'}, text);
}

/**
 * A sub-extension sent with no value[x] at all: there, and saying nothing. It is not "empty",
 * which is a value of "", and not "not returned", which it was. It wears the outline of empty,
 * the nearest of the three marks, with words of its own. The server says which element it is.
 */
export function noValueMark() {
    return el('span', {class: 'presence presence--empty', title: 'The sub-extension was sent with no value[x] in it.'}, 'sent with no value');
}

/** True when a Shown holds a value. */
export function hasValue(value) {
    return Boolean(value) && value.presence === 'VALUE';
}

/**
 * The mark for a field that can repeat and came back with nothing. It has words of its own
 * because the server sends [] both when the response had no such field and when it had an empty
 * one: "not returned" would claim more than this page knows.
 */
export function noneMark() {
    return el('span', {class: 'presence presence--none', title: 'The response carried none of these.'}, 'none returned');
}

/**
 * A list of Shown values. An empty list is said in words, and where the difference between a
 * missing list and an empty one matters the view carries a separate presence for the list itself.
 */
export function shownList(values, options) {
    if (!Array.isArray(values) || values.length === 0) {
        return noneMark();
    }
    if (values.length === 1) {
        return shown(values[0], options);
    }
    return el('ul', {class: 'plain-list'}, values.map(value => el('li', null, shown(value, options))));
}
