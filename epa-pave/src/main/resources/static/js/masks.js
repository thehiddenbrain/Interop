// A value an answer gave that repeats a secret this tool holds reaches the screen as its mask, such
// as [secret clientSecret], because the server reads every answer with its masks in place. A mask
// is to be looked at, never used: put into a field it would pass for a value the service gave, and
// sent on it would ask the service about something nobody named (the server refuses such a step).
// So no field is filled with one and no hand-over posts one, and a line beside says why and what
// the tester can do. The clear value is not recovered from anywhere: whoever knows it types it.

import {el, clear} from './dom.js';

const OPEN = '[secret ';

/** The masks a text holds, by themselves and joined by " and ", as the server names them; null when it holds none. */
export function masksIn(text) {
    if (typeof text !== 'string') {
        return null;
    }
    const masks = [];
    let at = text.indexOf(OPEN);
    while (at >= 0) {
        const end = text.indexOf(']', at + OPEN.length);
        if (end < 0) {
            break;
        }
        const mask = text.slice(at, end + 1);
        if (!masks.includes(mask)) {
            masks.push(mask);
        }
        at = text.indexOf(OPEN, end + 1);
    }
    return masks.length > 0 ? masks.join(' and ') : null;
}

/**
 * A hand-over without the values that hold a mask, and the values it left out: {carry, masked},
 * where masked lists {key, value, masks}. A key left with no value is left out as the server
 * leaves out an empty one.
 */
export function withoutMasks(carry) {
    const clean = {};
    const masked = [];
    for (const [key, values] of Object.entries(carry || {})) {
        const list = Array.isArray(values) ? values : [values];
        const kept = [];
        for (const value of list) {
            const masks = masksIn(value);
            if (masks) {
                masked.push({key, value, masks});
            } else {
                kept.push(value);
            }
        }
        if (kept.length > 0) {
            clean[key] = kept;
        }
    }
    return {carry: clean, masked};
}

/** The words beside a field an answer's value would have filled. */
export function notFilledWords(masks) {
    return 'Not filled in: the answer gave this value masked, as ' + masks + ', because it repeats a secret this tool holds. '
        + 'Type the value, or settle the secret in Configuration.';
}

/** The words for a value handed on that is not passed on (plan section 4: "<label>: masked, not handed on"). */
export function notHandedOnWords(label) {
    return label + ': masked, not handed on';
}

/** The line itself, drawn as the screens draw a caution: a rule on its left. */
export function maskedLine(words) {
    return el('p', {class: 'masked-carry'}, words);
}

/**
 * Fills a field with a value an answer gave, or, when the value holds a mask, leaves it empty and
 * puts the line in the slot beside it. control: {input, slot}. Returns true when the field was
 * filled.
 */
export function fillFromAnswer(control, value) {
    const masks = masksIn(value);
    clear(control.slot);
    if (masks) {
        control.slot.append(maskedLine(notFilledWords(masks)));
    }
    control.input.value = masks ? '' : (value === undefined || value === null ? '' : String(value));
    return !masks;
}
