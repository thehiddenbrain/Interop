// The case on the Workbench: member, provider, service and the run options, kept in this module so
// a tester who goes to Audit and back finds the case as they left it. Nothing of it goes to browser
// storage: it holds member data, which lives in the page's memory only and is gone on a reload.
//
// Every field remembers where its value came from, and the field says so in a small mark: "from
// lookup", "from saved list", "by hand", "changed by hand", "cleared, not sent", "masked, not
// taken". A value a lookup or a list gave and the tester changed is sent as typed and named among
// the overrides, so the ledger knows the run went with details it cannot rebuild.

import {el, chip, fill, clear} from './dom.js';

/** The words of the marks, the same on every card. */
export const MARKS = {
    LOOKUP: 'from lookup',
    SAVED: 'from saved list',
    HAND: 'by hand',
    CHANGED: 'changed by hand',
    CLEARED: 'cleared, not sent',
    MASKED: 'masked, not taken',
    // Filled by the mock member picker, which itself names what was chosen; no mark is drawn for it.
    SCENARIO: 'mock member',
    // Drawn back after a reload, from what was posted (the trail) or what the ledger line kept.
    TRAIL: 'from the trail',
    LEDGER: 'from the ledger'
};

/**
 * Sets a card's fields from a case read back after a reload: each value that was posted, marked
 * where it came from; a field the tester had typed or changed (named among the posted overrides)
 * is the tester's own again, so the next run names it as before. Fields with no value are emptied.
 * Returns what was posted that the card has no field for.
 */
export function restoreFields(controls, values, overrides, mark) {
    const given = values && typeof values === 'object' ? values : {};
    const theirs = new Set(Array.isArray(overrides) ? overrides : []);
    const used = new Set();
    for (const control of controls) {
        const value = given[control.name];
        used.add(control.name);
        if (value === undefined || value === null || value === '' || typeof value === 'object') {
            control.clear();
        } else {
            control.set(value, theirs.has(control.name) ? null : mark);
        }
    }
    const rest = {};
    for (const [key, value] of Object.entries(given)) {
        if (!used.has(key) && value !== undefined && value !== null && value !== '') {
            rest[key] = value;
        }
    }
    return rest;
}

/** The hint under every field whose text the ledger keeps as typed. */
export const LEDGER_TEXT_HINT = 'Kept in the ledger as typed. Never type member data here.';

const listeners = new Set();

/** Hears every change of the case: fn(reason). Returns a function that stops it. */
export function onCaseChange(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
}

/** Says the case changed, to the banner, the step modules and the journey. */
export function caseChanged(reason) {
    for (const fn of Array.from(listeners)) {
        try {
            fn(reason || 'typed');
        } catch (defect) {
            // One listener's fault must not stop the others hearing the change.
        }
    }
}

let fieldCount = 0;

/**
 * A case field: a label, its mark, the input and an error line. spec: {name, label, mono, kind
 * ('text', 'date', 'number', 'select', 'list'), options ([[value, words]]), list (an id of a
 * datalist), hint}. Returns {node, input, name, value(), set(value, mark), clear(), mark(),
 * origin, setError(message)}.
 */
export function caseField(spec) {
    fieldCount += 1;
    const id = 'case-' + spec.name.split('.').join('-') + '-' + fieldCount;
    let input;
    if (spec.kind === 'select') {
        input = el('select', {class: 'select', id, name: spec.name},
            (spec.options || []).map(([value, words]) => el('option', {value}, words)));
    } else {
        input = el('input', {class: ['input', spec.mono ? 'mono' : null], id, name: spec.name,
            type: spec.kind === 'date' ? 'date' : spec.kind === 'number' ? 'number' : 'text', autocomplete: 'off', spellcheck: false,
            attrs: spec.list ? {list: spec.list} : null});
        if (spec.kind === 'number') {
            input.min = '0';
            input.step = 'any';
        }
    }
    const markSlot = el('span', {class: 'field__mark'});
    const error = el('p', {class: 'field__error', id: id + '-error', hidden: true});
    const slot = el('div', {class: 'masked-carry-slot', 'aria-live': 'polite'});
    const node = el('div', {class: 'field', 'data-case-field': spec.name},
        el('label', {class: 'field__label', for: id}, spec.label, markSlot),
        input,
        spec.hint ? el('p', {class: 'field__hint'}, spec.hint) : null,
        slot, error);
    const control = {
        node, input, slot, name: spec.name,
        origin: null,      // {value, mark} that a lookup, a list or a mock member gave, or null
        current: null,     // the mark on the field now, or null
        value() {
            return String(input.value || '').trim();
        },
        /** Sets the value and its mark; with no mark, it is the tester's own. */
        set(value, mark) {
            // A value put in by a pick, a lookup or a new case is not the one the server refused.
            control.setError(null);
            input.value = value === undefined || value === null ? '' : String(value);
            control.origin = mark ? {value: input.value, mark} : null;
            showMark(mark || (input.value !== '' ? MARKS.HAND : null));
        },
        /** Empties the field and forgets where it came from. */
        clear() {
            control.setError(null);
            input.value = '';
            control.origin = null;
            showMark(null);
            clear(slot);
        },
        /** A masked value: left empty, with its mark. */
        masked() {
            control.setError(null);
            input.value = '';
            control.origin = null;
            showMark(MARKS.MASKED);
        },
        mark() {
            return control.current;
        },
        setError(message) {
            const has = typeof message === 'string' && message !== '';
            error.textContent = has ? message : '';
            error.hidden = !has;
            node.classList.toggle('field--error', has);
            if (has) {
                input.setAttribute('aria-invalid', 'true');
                input.setAttribute('aria-describedby', error.id);
            } else {
                input.removeAttribute('aria-invalid');
                input.removeAttribute('aria-describedby');
            }
        }
    };
    function showMark(mark) {
        control.current = mark;
        fill(markSlot, mark && mark !== MARKS.SCENARIO ? chip(mark, {dashed: mark === MARKS.CLEARED || mark === MARKS.MASKED, title: mark}) : null);
    }
    const typed = () => {
        control.setError(null);
        clear(slot);
        const now = input.value;
        if (control.origin) {
            showMark(now === control.origin.value ? control.origin.mark : now.trim() === '' ? MARKS.CLEARED : MARKS.CHANGED);
        } else {
            showMark(now.trim() === '' ? null : MARKS.HAND);
        }
        caseChanged('typed');
    };
    input.addEventListener('input', typed);
    if (spec.kind === 'select') {
        input.addEventListener('change', typed);
    }
    return control;
}

/** The value of one of a step's options by name (ctx.option), '' when the step has no such option. */
export function optionValue(controls, name) {
    const control = controls && Object.prototype.hasOwnProperty.call(controls, name) ? controls[name] : null;
    return control ? control.value() : '';
}

/** True when a mark says the tester gave the value, or took it away: what the ledger cannot rebuild. */
export function byTheTester(mark) {
    return mark === MARKS.HAND || mark === MARKS.CHANGED || mark === MARKS.CLEARED;
}

/** The fields of a card as an object, a field with no value left out; and the names the tester set. */
export function fieldsOf(controls) {
    const values = {};
    const overrides = [];
    for (const control of controls) {
        const value = control.value();
        if (value !== '') {
            values[control.name] = value;
        }
        if (byTheTester(control.mark())) {
            overrides.push(control.name);
        }
    }
    return {values, overrides};
}

/**
 * The key a hand-over is made for: the member ID as typed, the provider's NPI or vendor
 * practitioner id, and the codes. A carry goes only while the case on screen has the same key. The
 * same case typed another way (spaces in the member ID) keeps its key, because the ID is compared
 * as the server compares it, without spaces.
 */
export function caseKey(request) {
    if (!request) {
        return '';
    }
    const member = request.member && request.member.memberId ? packed(request.member.memberId) : '';
    const provider = request.provider ? (request.provider.npi || '') + '|' + (request.provider.vendorPractitionerId || '') : '|';
    const codes = (request.procedureCodes || []).map(code => code.code).join(',');
    return member + '#' + provider + '#' + codes;
}

function packed(text) {
    let out = '';
    for (const character of String(text)) {
        if (character !== ' ') {
            out += character;
        }
    }
    return out;
}
