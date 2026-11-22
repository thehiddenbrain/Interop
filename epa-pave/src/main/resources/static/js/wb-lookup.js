// What the Member and Provider cards share: a card's head with its icon and the chip that names
// what is chosen, the lookup of an identifier, and the fold that shows what the lookup returned.
//
// A lookup is asked on leaving the field, on Enter and on "Look up", never per key. The latest
// question wins over a slower earlier answer. A lookup that does not find one entry (several, not
// found, not answering, failed, none configured) says so in one line and clears the fields the
// previous lookup filled, so a value from another member never stays on the card; its fields stay
// open for typing. Nothing a lookup returns is kept beyond the card on screen.

import {call} from './api.js';
import {el, chip, fold, kv, firstSentence, fill, clear} from './dom.js';
import {errorPanel} from './fault.js';
import {maskedReading, returnedTable, wireFold} from './result.js';
import {shown} from './shown.js';
import {MARKS} from './wb-case.js';

const NOT_CONFIGURED_LINE = 'Type the details.';

/** The head of a case card: icon, title, and a slot for the chip that names what is chosen. */
export function cardHead(title, icon) {
    const picked = el('span', {class: 'card__picked'});
    const extra = el('span', {class: 'card__extra'});
    const node = el('div', {class: 'card__head'},
        el('span', {class: 'card__icon'}, el('img', {class: 'icon', attrs: {src: 'img/' + icon + '.svg', alt: '', width: '16', height: '16'}})),
        el('h3', {class: 'card__title'}, title), extra, picked);
    return {
        node,
        /** The chip at the right: a tick and the words, or the dashed words when nothing is chosen. */
        picked(words, chosen) {
            fill(picked, chosen
                ? el('span', {class: 'chip', title: words}, el('span', {class: 'card__tick', 'aria-hidden': 'true'}),
                    el('span', {class: 'chip__text'}, words))
                : chip(words, {dashed: true}));
        },
        /** A "to check" chip after the title, when the lookup has something to read before sending. */
        attention(on) {
            fill(extra, on ? chip('to check', {tone: 'action'}) : null);
        }
    };
}

/**
 * A lookup that only its latest question may answer. ask(): a promise of the answer of api.js.
 * Returns run(ask, take), where take(answer) is called only when no newer question was asked.
 */
export function latestOnly() {
    let asked = 0;
    return async function run(ask, take) {
        asked += 1;
        const mine = asked;
        const answer = await ask();
        if (mine === asked) {
            take(answer);
        }
    };
}

/** Asks a lookup: path '/api/members' or '/api/providers', with its query. */
export function lookUp(path, query) {
    return call('GET', path, {query});
}

/**
 * Fills the fields a found lookup returned, by name, through the check for a mask: a field whose
 * value the server masked is left empty with its mark. A field the tester typed or changed keeps
 * the tester's value. Returns the names filled.
 */
export function fillFromLookup(controls, values, fields) {
    const masked = new Set((fields || []).filter(field => field.masked === true).map(field => field.name));
    const filled = [];
    for (const control of controls) {
        const mark = control.mark();
        if (mark === MARKS.HAND || mark === MARKS.CHANGED) {
            continue;
        }
        if (masked.has(control.name)) {
            control.masked();
            continue;
        }
        const value = values ? values[control.name] : undefined;
        if (value !== undefined && value !== null && String(value) !== '') {
            control.set(value, MARKS.LOOKUP);
            filled.push(control.name);
        } else if (mark === MARKS.LOOKUP) {
            control.clear();
        }
    }
    return filled;
}

/** Clears every field the previous lookup filled: a lookup that found nobody leaves nothing of another member. */
export function clearLookedUp(controls) {
    for (const control of controls) {
        if (control.mark() === MARKS.LOOKUP || control.mark() === MARKS.MASKED) {
            control.clear();
        }
    }
}

/** The status chip of a lookup that did not find exactly one entry, in the server's words. Null when found or unsaid. */
export function statusChip(answer) {
    if (!answer || answer.status === 'FOUND') {
        return null;
    }
    const words = typeof answer.statusLabel === 'string' && answer.statusLabel !== '' ? answer.statusLabel : null;
    if (!words) {
        return null;
    }
    const tone = answer.status === 'FAULT' || answer.status === 'NOT_ANSWERING' ? 'fault' : answer.status === 'DISABLED' ? null : 'data';
    return chip(words, {tone, dashed: answer.status === 'DISABLED'});
}

/**
 * The lookup's own line, its first sentence, when it did not find exactly one entry. A found entry
 * is said by the tick in the card's head; its sentence stays in "What the lookup returned".
 */
export function lookupLine(answer) {
    if (answer && answer.status === 'DISABLED') {
        // A lookup not set up is said by its chip; the property that sets it up belongs to
        // Configuration > Advanced, not to a tester in the middle of a case (I-1).
        return el('p', {class: 'small muted lookup-line'}, NOT_CONFIGURED_LINE);
    }
    if (!answer || answer.status === 'FOUND' || typeof answer.message !== 'string' || answer.message === '') {
        return null;
    }
    const [first, rest] = firstSentence(answer.message);
    return el('p', {class: 'small muted lookup-line', title: rest ? answer.message : null}, first);
}

/** The fold "What the lookup returned": every field with where it was read from, what was not interpreted, and the wire traffic. */
export function returnedFold(answer) {
    if (!answer) {
        return null;
    }
    return fold({
        summary: 'What the lookup returned', inline: true, body: () => el('div', {class: 'lookup-returned'},
            maskedReading(answer.maskedReading),
            answer.status === 'FOUND' && answer.message ? el('p', {class: 'small'}, answer.message) : null,
            Array.isArray(answer.attention) && answer.attention.length > 0
                ? el('ul', {class: 'notes'}, answer.attention.map(line => el('li', null, line))) : null,
            answer.error ? errorPanel(answer.error) : null,
            Array.isArray(answer.fields) && answer.fields.length > 0 ? kv(answer.fields.map(field => ({
                key: field.label || field.name,
                value: el('span', null, shown(field.value, {mono: true}),
                    field.sourceField ? el('span', {class: 'small muted'}, ' ' + field.sourceField) : null,
                    field.note ? el('div', {class: 'small'}, field.note) : null)
            }))) : null,
            Array.isArray(answer.additionalFields) && answer.additionalFields.length > 0
                ? [el('p', {class: 'notes__title'}, 'Returned but not interpreted'), returnedTable(answer.additionalFields, answer.wire)] : null,
            answer.wire ? wireFold(answer.wire, {error: answer.error}) : null)
    });
}

/** The value a lookup returned for a field, from its fields list, or null when it did not return one. */
export function returnedValue(answer, name) {
    const field = answer && Array.isArray(answer.fields) ? answer.fields.find(each => each.name === name) : null;
    return field && field.value && field.value.presence === 'VALUE' ? String(field.value.value) : null;
}
