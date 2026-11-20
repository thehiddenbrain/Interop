// The marks that carry meaning by colour or by form, kept in one place so the result view, the
// suite table, the chain and the colour key all draw the same thing for the same fact.
//
// Two facts are never drawn alike:
//   the authorization outcome: what the service said. Drawn as a tone (toneMark, outcome)
//   the test result: whether that matched what the tester expected. Drawn as a stamp (stamp)
// Green and magenta belong to the stamp alone. No tone uses either.

import {el, token} from './dom.js';

/**
 * What each tone means, for the colour key. The tones themselves come from the server with every
 * outcome; this page only decides how each is drawn and says so.
 */
export const TONES = [
    ['CLEAR', 'Nothing further is required of the provider.'],
    ['ACTION', 'The provider has something to do: obtain authorization, send documentation, supply information.'],
    ['PENDING', 'A decision exists but is not final.'],
    ['STOP', 'The payer said no: not covered, or an authorization denied.'],
    ['NO_ANSWER', 'The service answered and said nothing either way.'],
    ['DATA', 'About the test data: the service does not know the member or the provider.'],
    ['FAULT', 'About the environment: the service failed, timed out or could not be reached.'],
    ['BLOCKED', 'This tool refused to send the request.'],
    ['NEUTRAL', 'Information, with no judgement attached.']
];

/** The words for a test result. The server sends pass, fail or unverified. */
const RESULT_WORDS = {pass: 'Passed', fail: 'Failed', unverified: 'Unverified'};

export const RESULTS = [
    ['pass', 'The outcome matched what the tester declared.'],
    ['fail', 'The outcome did not match what the tester declared.'],
    ['unverified', 'Nothing was declared, or nothing was sent to compare. Not a pass and not a failure.']
];

/** A test result in a word. One the page does not know is given back as sent. */
export function resultWord(result) {
    const name = String(result === undefined || result === null ? '' : result);
    return Object.prototype.hasOwnProperty.call(RESULT_WORDS, name) ? RESULT_WORDS[name] : name;
}

/** The class that draws a tone. A tone this page does not know gets no colour, which is the honest fallback. */
export function toneClass(tone) {
    return 'tone--' + token(tone || 'NEUTRAL');
}

/** A small square in the tone's colour and form. */
export function toneMark(tone) {
    return el('span', {class: 'tone-mark ' + toneClass(tone), aria: {hidden: 'true'}});
}

/** An outcome in words with its tone mark in front, for tables and lists. */
export function outcome(label, tone) {
    return el('span', {class: 'outcome'}, toneMark(tone), el('span', {class: 'outcome__label'}, label));
}

/**
 * The test result as a stamp. Its shape, its glyph and its colours are its own, so it cannot be
 * taken for the outcome beside it. A result this page does not know is shown as sent, undecorated.
 */
export function stamp(result, size) {
    const name = String(result === undefined || result === null ? '' : result);
    const known = Object.prototype.hasOwnProperty.call(RESULT_WORDS, name);
    return el('span', {class: 'stamp stamp--' + (known ? name : 'other') + (size === 'large' ? ' stamp--large' : '')},
        el('span', {class: 'stamp__glyph', aria: {hidden: 'true'}}),
        el('span', {class: 'stamp__word'}, known ? RESULT_WORDS[name] : name));
}

/** The flag for a call that completed but took longer than the threshold. */
export function slowFlag() {
    return el('span', {class: 'flag flag--slow', title: 'The call completed but took longer than the threshold.'}, 'slow');
}

/** The mark that says an answer came from a mock scenario and not from the service. */
export function mockMark() {
    return el('span', {class: 'mock-mark'}, el('span', {class: 'hatch', aria: {hidden: 'true'}}), 'mock');
}
