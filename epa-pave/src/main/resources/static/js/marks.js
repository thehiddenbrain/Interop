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
    const name = String(tone || 'NEUTRAL');
    // A tone the key does not list is drawn as the key's "information, with no judgement attached",
    // and keeps its own name as a class beside it.
    return TONES.some(([known]) => known === name) ? 'tone--' + token(name) : 'tone--NEUTRAL tone--' + token(name);
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

/**
 * What a finding is about, by its kind. The server words every finding, and sends its kind as a
 * name; it sends no word for what the kind is about. That is said here, in a short line above the
 * finding's title, so that a finding about the request this tool sent is never read as a fault of
 * the service, and the colour key lists the same groups. No group is a fault and none is coloured.
 * A kind not listed here is drawn plainly with its name as sent.
 */
export const FINDING_GROUPS = [
    {id: 'request', heading: 'About the request this tool sent, not the service',
        kinds: ['REQUEST_INCOMPLETE', 'NO_CALLBACK_URL', 'PRE_REQUEST_SCRIPT_NOT_RUN'],
        meaning: 'What went out, or what this tool did not do. A refusal by the service that follows from it is not a fault of the service.'},
    {id: 'guide', heading: 'About the response, held to the guide',
        kinds: ['REQUIRED_ELEMENT_MISSING', 'CODE_OUTSIDE_VALUE_SET', 'GUIDE_RULE_BROKEN'],
        meaning: 'The response does not meet the version of the guide this tool is held to. The finding names the guide and the version.'},
    {id: 'information', heading: 'Information about the response',
        kinds: ['OTHER_VERSION_CONTENT', 'NOT_STATED'],
        meaning: 'Something to know, which the guide does not call wrong: content a later version defines, or something the response does not state.'},
    {id: 'codelist', heading: 'The code list beside the service',
        kinds: ['CODE_LIST_DISAGREES_ON_AUTH', 'CODE_LIST_DISAGREES_ON_VENDOR', 'CODE_LIST_NOT_COMPARED'],
        meaning: 'The two positions side by side, or why they could not be compared. Either side can be the wrong one.'},
    {id: 'notification', heading: 'About a notification that arrived',
        kinds: ['UNMATCHED_NOTIFICATION', 'UNCHECKED_NOTIFICATION'],
        meaning: 'A notification this tool could not tie to a run, or could not check. It is kept and shown.'},
    {id: 'timing', heading: 'About the time the call took',
        kinds: ['SLOW_RESPONSE'],
        meaning: 'The call completed and took longer than the threshold.'}
];

/** The group of a finding kind, or null for a kind this page does not list. */
export function findingGroup(kind) {
    return FINDING_GROUPS.find(group => group.kinds.includes(String(kind))) || null;
}

/** The flag for a call that completed but took longer than the threshold. */
export function slowFlag() {
    return el('span', {class: 'flag flag--slow', title: 'The call completed but took longer than the threshold.'}, 'slow');
}

/** The mark that says an answer came from a mock scenario and not from the service. */
export function mockMark() {
    return el('span', {class: 'mock-mark'}, el('span', {class: 'hatch', aria: {hidden: 'true'}}), 'mock');
}
