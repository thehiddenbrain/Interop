// The questions of one questionnaire as a form (plan 3.2, step 2), drawn from the view the server
// built (ItemView), never from the Questionnaire JSON: what an item is, whether it is required and
// whether the answers the server last saw enable it are the server's reading, shown as it sent them.
//
// A field starts with what the response holds for its question, so a payer's prefilled answer is
// seen where it will be sent from, marked "payer prefilled" on a grey ground. What the tester types
// is kept apart from that: only a value that differs from the response is an answer of the
// tester's, and clearing a held answer is sent as a removal. Nothing here is guessed: a question the
// response holds nothing for starts empty.
//
// Nested items are walked with an explicit stack, never by recursion, so a questionnaire nested as
// deep as a service likes is drawn without exhausting the stack.

import {el, chip} from './dom.js';

let sequence = 0;

// Above this many options a list of radio buttons is longer than the question deserves.
const RADIO_LIMIT = 6;

// The types a typed text is sent for as it is. Any other type is answered with a FHIR answer
// object, which the server sends as written.
const TEXT_TYPES = new Set(['boolean', 'integer', 'decimal', 'date', 'dateTime', 'time', 'string', 'text', 'url', 'choice', 'open-choice']);

// The deepest indent drawn; deeper items keep this one, so a deep questionnaire stays on the page.
const DEEPEST = 6;

function value(shown) {
    return shown && shown.presence === 'VALUE' && shown.value !== undefined && shown.value !== null ? String(shown.value) : null;
}

/** The linkId of an item or a response item, or null. */
export function linkIdOf(item) {
    return item ? value(item.linkId) : null;
}

/** The items of a questionnaire in reading order with their depth, without recursion. */
export function flatItems(items) {
    const rows = [];
    const stack = [{list: Array.isArray(items) ? items : [], at: 0, depth: 0}];
    while (stack.length > 0) {
        const top = stack[stack.length - 1];
        if (top.at >= top.list.length) {
            stack.pop();
            continue;
        }
        const item = top.list[top.at];
        top.at++;
        if (!item || typeof item !== 'object') {
            continue;
        }
        const type = item.type && item.type.code;
        rows.push({item, depth: top.depth, kind: type === 'display' ? 'display' : item.question ? 'question' : 'group'});
        if (Array.isArray(item.items) && item.items.length > 0) {
            stack.push({list: item.items, at: 0, depth: top.depth + 1});
        }
    }
    return rows;
}

/** The response items of a QuestionnaireResponseView by linkId, without recursion. */
export function heldByLinkId(response) {
    const map = new Map();
    const stack = [response && Array.isArray(response.items) ? response.items : []];
    while (stack.length > 0) {
        const list = stack.pop();
        for (const item of list) {
            const linkId = linkIdOf(item);
            if (linkId && !map.has(linkId)) {
                map.set(linkId, item);
            }
            if (item && Array.isArray(item.items) && item.items.length > 0) {
                stack.push(item.items);
            }
        }
    }
    return map;
}

/** True when the payer's service gave the answer the response holds (information-origin auto or override). */
export function payerGave(held) {
    const origins = held && Array.isArray(held.origins) ? held.origins : [];
    return origins.some(origin => {
        const source = origin ? value(origin.source) : null;
        return source === 'auto' || source === 'override';
    });
}

/** How many questions of a response hold an answer the payer gave. */
export function prefilledCount(response) {
    let count = 0;
    for (const held of heldByLinkId(response).values()) {
        if ((held.answers || []).length > 0 && payerGave(held)) {
            count++;
        }
    }
    return count;
}

/**
 * The values a question's field starts with, as the field would send them: the response's answers
 * read through the item's own options. A held answer the field cannot show (a quantity, an object,
 * an option the item does not list) leaves the field empty and is named beside it instead.
 */
export function heldValues(item, held) {
    const answers = held && Array.isArray(held.answers) ? held.answers.map(value).filter(text => text !== null) : [];
    const type = item.type && item.type.code;
    if (answers.length === 0) {
        return {values: [], unshown: []};
    }
    if (!TEXT_TYPES.has(type) || (item.type && item.type.recognised === false)) {
        return {values: [], unshown: answers};
    }
    if (type === 'choice' || type === 'open-choice') {
        const values = [];
        const unshown = [];
        for (const text of answers) {
            const option = (item.answerOptions || []).find(each => each.answerAs && (value(each.value) === text || each.label === text));
            if (option) {
                values.push(option.answerAs);
            } else if (type === 'open-choice') {
                values.push(text);
            } else {
                unshown.push(text);
            }
        }
        return {values, unshown};
    }
    return {values: answers, unshown: []};
}

/** Two lists of values, as they would be posted, compared. */
export function sameValues(a, b) {
    return JSON.stringify(a || []) === JSON.stringify(b || []);
}

/** A group's title or a display item's text, drawn as a line of the form. */
export function itemLine(row) {
    const text = value(row.item.text) || linkIdOf(row.item) || '';
    const depth = Math.min(row.depth, DEEPEST);
    if (row.kind === 'group') {
        return el('p', {class: ['section-label', 'question__group', depth > 0 ? 'detail__depth-' + depth : null]}, text);
    }
    return el('p', {class: ['small', 'question__display', depth > 0 ? 'detail__depth-' + depth : null]}, text);
}

/** The types whose field a date of service can go into (plan R-18, the date line of 3.2). */
export function isDateQuestion(item) {
    const type = item && item.type ? item.type.code : null;
    return type === 'date' || type === 'dateTime';
}

/**
 * One question as a field. spec: {item, depth, held (the ResponseItemView or null), given (what the
 * tester gave for it, or undefined), fromCase ({values, mark} when an answer from the case went into
 * it before the form was drawn again), onChange(linkId, values), onCaseLeft(linkId)}: values null
 * while the field holds what the response holds, else what the tester gave ([] removes a held
 * answer); onCaseLeft when the tester changes an answer from the case, which then is theirs.
 * Returns {node, linkId, input, setError(text), setMark(text), prefilled, empty(), takesCase(),
 * fillFromCase(values, mark)}.
 */
export function questionField(spec) {
    const item = spec.item;
    const linkId = linkIdOf(item);
    const type = (item.type && item.type.code) || '';
    // A question its conditions turn off is drawn muted and never as required, but stays open to
    // typing (I-36): the answers sent with it may meet its condition, and the server decides then.
    const off = item.enabled === 'DISABLED';
    const required = value(item.required) === 'true' && !off;
    const repeats = value(item.repeats) === 'true';
    const readOnly = value(item.readOnly) === 'true';
    const start = heldValues(item, spec.held);
    const prefilled = start.values.length > 0 && payerGave(spec.held);
    const id = 'dtr-q-' + (++sequence);
    const markSlot = el('span', {class: 'mark'}, prefilled ? 'payer prefilled' : null);
    const label = el('label', {class: 'field__label', for: id},
        value(item.prefix) ? value(item.prefix) + ' ' : null,
        value(item.text) || linkId || 'This question carries no text',
        required ? [el('span', {class: 'required', 'aria-hidden': 'true'}, '*'), el('span', {class: 'visually-hidden'}, 'required')] : null,
        markSlot);
    const error = el('p', {class: 'field__error', hidden: true});
    const node = el('div', {class: ['field', 'question', off ? 'question--off' : null,
        spec.depth > 0 ? 'detail__depth-' + Math.min(spec.depth, DEEPEST) : null], 'data-link-id': linkId});

    let control;
    // The values an answer from the case put here, while the field still holds them: its mark says
    // where they came from, and goes as soon as the tester makes the answer their own.
    let fromCase = null;
    const report = () => {
        if (!linkId) {
            return;
        }
        const given = control.get();
        if (fromCase && !sameValues(given, fromCase.values)) {
            fromCase = null;
            markSlot.textContent = prefilled ? 'payer prefilled' : '';
            if (typeof spec.onCaseLeft === 'function') {
                spec.onCaseLeft(linkId);
            }
        }
        spec.onChange(linkId, given === null || sameValues(given, start.values) ? null : given);
    };
    const options = (item.answerOptions || []).filter(option => option.answerAs);
    if (!TEXT_TYPES.has(type) || (item.type && item.type.recognised === false)) {
        control = type === 'quantity' ? quantityControl(id, report) : objectControl(id, report);
    } else if (type === 'boolean') {
        control = choiceControl(id, [{answerAs: 'true', label: 'Yes'}, {answerAs: 'false', label: 'No'}], false, false, report);
    } else if ((type === 'choice' || type === 'open-choice') && options.length > 0) {
        control = choiceControl(id, options, repeats, type === 'open-choice', report);
    } else if (type === 'choice') {
        control = objectControl(id, report);
    } else {
        control = textControl(id, type, repeats, report);
    }
    control.set(start.values);
    // What the tester gave before the form was drawn again (after a step) goes back into the field.
    if (Array.isArray(spec.given)) {
        control.set(spec.given);
    }
    if (prefilled) {
        control.prefilled(true);
    }
    if (!prefilled && spec.fromCase && sameValues(control.get(), spec.fromCase.values)) {
        fromCase = spec.fromCase;
        markSlot.textContent = fromCase.mark;
    }
    const empty = () => {
        const now = control.get();
        return Array.isArray(now) && now.length === 0;
    };
    const takesCase = () => Boolean(linkId) && !prefilled && !readOnly && !off && empty();
    if (readOnly) {
        control.disable();
    }
    node.append(label, control.node);
    if (start.unshown.length > 0) {
        node.append(el('span', {class: 'field__hint'}, 'In the response: ' + start.unshown.join(', ')));
    }
    if (typeof item.enabledReason === 'string' && item.enabledReason !== '') {
        node.append(el('span', {class: 'field__hint muted enabled-reason'}, item.enabledReason));
    }
    if (!linkId) {
        node.append(el('span', {class: 'field__hint'}, 'It has no linkId, so an answer to it cannot be sent.'));
        control.disable();
    }
    node.append(error);
    // Enter in a field of the form never sends it and never reloads the page: there is no form
    // element, and a field of its own submits nothing.
    node.addEventListener('keydown', event => {
        if (event.key === 'Enter' && event.target && event.target.tagName === 'INPUT') {
            event.preventDefault();
        }
    });
    return {
        node,
        linkId,
        input: control.focusable(),
        setError(text) {
            error.textContent = text || '';
            error.hidden = !text;
            node.classList.toggle('field--error', Boolean(text));
        },
        setMark(text) {
            markSlot.textContent = text || (prefilled ? 'payer prefilled' : '');
        },
        prefilled,
        /** True while the field holds no answer at all. */
        empty,
        /**
         * True when an answer from the case may go here: never over the payer's answer, a held or
         * typed one, nor into a question that is read only, has no linkId or whose condition does
         * not hold.
         */
        takesCase,
        /**
         * Puts an answer from the case into the field, marked, as an answer that will be sent and
         * that the tester can change or clear. Returns false, changing nothing, when the field
         * cannot show the values (an option it does not list).
         */
        fillFromCase(values, mark) {
            if (!takesCase() || !Array.isArray(values) || values.length === 0) {
                return false;
            }
            control.set(values.map(String));
            if (!sameValues(control.get(), values.map(String))) {
                control.set([]);
                return false;
            }
            fromCase = {values: values.map(String), mark};
            markSlot.textContent = mark;
            spec.onChange(linkId, sameValues(fromCase.values, start.values) ? null : fromCase.values);
            return true;
        }
    };
}

/* ---------- controls: each {node, get(), set(values), prefilled(on), disable(), focusable()} ---------- */

function choiceControl(id, options, repeats, open, report) {
    const other = open ? el('input', {class: 'input', type: 'text', autocomplete: 'off', 'aria-label': 'An answer of your own'}) : null;
    let inputs = [];
    let select = null;
    let node;
    if (!repeats && options.length > RADIO_LIMIT) {
        select = el('select', {class: 'select', id},
            el('option', {value: ''}, 'Choose an answer'),
            options.map(option => el('option', {value: option.answerAs}, option.label || option.answerAs)));
        select.addEventListener('change', report);
        node = el('div', null, select);
    } else {
        const name = 'dtr-c-' + (++sequence);
        inputs = options.map((option, index) => el('input', {type: repeats ? 'checkbox' : 'radio', name, value: option.answerAs,
            id: index === 0 ? id : name + '-' + index}));
        inputs.forEach(input => input.addEventListener('change', report));
        node = el('div', {class: 'choices', role: repeats ? 'group' : 'radiogroup'},
            options.map((option, index) => el('label', {class: 'choice small', for: inputs[index].id}, inputs[index], ' ', option.label || option.answerAs)));
    }
    if (other) {
        other.addEventListener('input', report);
        node.append(other);
    }
    const listed = new Set(options.map(option => option.answerAs));
    return {
        node,
        get() {
            const picked = select ? (select.value === '' ? [] : [select.value]) : inputs.filter(input => input.checked).map(input => input.value);
            if (other && other.value.trim() !== '') {
                picked.push(other.value.trim());
            }
            return picked;
        },
        set(values) {
            const chosen = new Set(values);
            if (select) {
                select.value = values.find(each => listed.has(each)) || '';
            }
            inputs.forEach(input => {
                input.checked = chosen.has(input.value);
            });
            if (other) {
                other.value = values.find(each => !listed.has(each)) || '';
            }
        },
        prefilled(on) {
            if (select) {
                select.classList.toggle('select--prefilled', on);
            }
            node.classList.toggle('choices--prefilled', on);
        },
        disable() {
            inputs.forEach(input => {
                input.disabled = true;
            });
            if (select) {
                select.disabled = true;
            }
        },
        focusable: () => select || inputs[0] || other
    };
}

function textControl(id, type, repeats, report) {
    const long = type === 'text' || repeats;
    const input = long
        ? el('textarea', {class: 'input', id, rows: repeats ? 3 : 2, spellcheck: 'false'})
        : el('input', {class: ['input', type === 'date' || type === 'dateTime' || type === 'time' ? 'mono' : null], id, autocomplete: 'off',
            type: type === 'integer' || type === 'decimal' ? 'number' : 'text',
            step: type === 'decimal' ? 'any' : null,
            placeholder: type === 'date' ? 'YYYY-MM-DD' : type === 'dateTime' ? '2026-01-31T09:30:00-05:00' : type === 'time' ? 'HH:MM' : null});
    input.addEventListener('input', report);
    return {
        node: el('div', null, input, repeats ? el('span', {class: 'field__hint'}, 'One answer on each line.') : null),
        get() {
            if (repeats) {
                return input.value.split('\n').map(line => line.trim()).filter(line => line !== '');
            }
            return input.value.trim() === '' ? [] : [input.value.trim()];
        },
        set(values) {
            input.value = repeats ? values.join('\n') : values[0] || '';
        },
        prefilled(on) {
            input.classList.toggle('input--prefilled', on);
        },
        disable() {
            input.readOnly = true;
        },
        focusable: () => input
    };
}

/** A quantity: a number and a unit, sent as one FHIR answer object exactly as typed. */
function quantityControl(id, report) {
    const number = el('input', {class: 'input', id, type: 'number', step: 'any', 'aria-label': 'The number'});
    const unit = el('input', {class: 'input', type: 'text', autocomplete: 'off', 'aria-label': 'The unit'});
    number.addEventListener('input', report);
    unit.addEventListener('input', report);
    return {
        node: el('div', {class: 'field-row'}, number, unit),
        get() {
            const typed = number.value.trim();
            if (typed === '' && unit.value.trim() === '') {
                return [];
            }
            const quantity = {value: typed !== '' && Number.isFinite(Number(typed)) ? Number(typed) : typed};
            if (unit.value.trim() !== '') {
                quantity.unit = unit.value.trim();
            }
            return [{valueQuantity: quantity}];
        },
        set() {
            number.value = '';
            unit.value = '';
        },
        prefilled() {},
        disable() {
            number.readOnly = true;
            unit.readOnly = true;
        },
        focusable: () => number
    };
}

/**
 * An answer of a type no plain text can be turned into: the tester writes the FHIR answer object
 * and it is sent as written. Text that is not a JSON object sends nothing and says so at the field.
 */
function objectControl(id, report) {
    const input = el('textarea', {class: 'input mono', id, rows: 2, spellcheck: 'false', placeholder: '{"valueString": "..."}'});
    const state = el('span', {class: 'field__hint'}, 'A FHIR answer object, sent as written.');
    input.addEventListener('input', report);
    return {
        node: el('div', null, input, state),
        get() {
            const text = input.value.trim();
            if (text === '') {
                state.textContent = 'A FHIR answer object, sent as written.';
                return [];
            }
            let parsed = null;
            try {
                parsed = JSON.parse(text);
            } catch (notJson) {
                parsed = null;
            }
            if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
                state.textContent = 'Not a JSON object yet, so nothing of it is sent.';
                return null;
            }
            state.textContent = 'A JSON object. It is sent as written.';
            return [parsed];
        },
        set() {
            input.value = '';
        },
        prefilled() {},
        disable() {
            input.readOnly = true;
        },
        focusable: () => input
    };
}

/** The chips of a questionnaire's head: (server) kindShort, "<n> required", "<n> prefilled", "<n> from the case". */
export function formChips(view, response, fromCase) {
    const chips = [];
    if (typeof view.kindShort === 'string' && view.kindShort !== '') {
        chips.push(chip(view.kindShort, {tone: 'neutral'}));
    }
    if (typeof view.requiredCount === 'number' && view.requiredCount > 0) {
        chips.push(chip(view.requiredCount + ' required'));
    }
    const prefilled = prefilledCount(response);
    if (prefilled > 0) {
        chips.push(chip(prefilled + ' prefilled'));
    }
    if (typeof fromCase === 'number' && fromCase > 0) {
        chips.push(chip(fromCase + ' from the case'));
    }
    return chips;
}
