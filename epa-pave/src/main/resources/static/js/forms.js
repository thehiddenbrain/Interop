// Form fields, and marking the ones the server refused.
//
// The server checks a form and answers 422 with one entry per problem, each naming its field the
// way the request names it (member.memberId, provider.npi, procedureCodes[0].code). A field here
// carries that same name in data-field, so a problem lands on the field it is about with the
// server's own sentence beneath it. This page checks nothing itself: a second set of rules in the
// browser would drift from the first.

import {el, append} from './dom.js';

let sequence = 0;

function nextId(prefix) {
    sequence += 1;
    return prefix + '-' + sequence;
}

/**
 * A labelled text input.
 *
 * spec: label, field (the name the server uses for it), placeholder, hint, literal (true for an
 * identifier or a code, which is set in the monospace face), suggestions (plain values offered
 * while typing; anything else can still be typed), wide (true to span the grid).
 */
export function textField(spec) {
    const id = nextId('field');
    const input = el('input', {
        type: 'text', id, name: spec.field, placeholder: spec.placeholder, autocomplete: 'off', spellcheck: false,
        class: spec.literal ? 'literal-input' : null, inputMode: spec.inputMode
    });
    const parts = [el('label', {htmlFor: id}, spec.label), input];
    if (spec.suggestions && spec.suggestions.length > 0) {
        const listId = nextId('suggestions');
        parts.push(el('datalist', {id: listId}, spec.suggestions.map(value => el('option', {value}))));
        input.setAttribute('list', listId);
    }
    if (spec.hint) {
        const hintId = nextId('hint');
        parts.push(el('span', {class: 'field__hint', id: hintId}, spec.hint));
        input.setAttribute('aria-describedby', hintId);
    }
    const node = el('div', {class: 'field' + (spec.wide ? ' field--wide' : ''), dataset: {field: spec.field}}, parts);
    return {node, input, value: () => input.value.trim(), set: value => { input.value = value === undefined || value === null ? '' : String(value); }};
}

/** A labelled select. options: [{value, label, title}]. */
export function selectField(spec) {
    const id = nextId('field');
    const select = el('select', {id, name: spec.field});
    setOptions(select, spec.options || []);
    const parts = [el('label', {htmlFor: id}, spec.label), select];
    if (spec.hint) {
        const hintId = nextId('hint');
        parts.push(el('span', {class: 'field__hint', id: hintId}, spec.hint));
        select.setAttribute('aria-describedby', hintId);
    }
    const node = el('div', {class: 'field' + (spec.wide ? ' field--wide' : ''), dataset: {field: spec.field}}, parts);
    return {node, select, value: () => select.value, set: value => { select.value = value; }};
}

/** Replaces the options of a select, keeping the choice when it is still offered. */
export function setOptions(select, options) {
    const kept = select.value;
    select.replaceChildren(...options.map(option => el('option', {value: option.value, title: option.title}, option.label)));
    if (options.some(option => option.value === kept)) {
        select.value = kept;
    }
}

/** Removes every mark left by an earlier refusal, inside one part of the page. */
export function clearFieldErrors(root) {
    for (const marked of root.querySelectorAll('.field--invalid')) {
        marked.classList.remove('field--invalid');
    }
    for (const message of root.querySelectorAll('.field__error')) {
        message.remove();
    }
    for (const control of root.querySelectorAll('[aria-invalid="true"]')) {
        control.removeAttribute('aria-invalid');
    }
}

/**
 * Marks each field a 422 names, with the server's sentence beneath it.
 *
 * Returns the problems that name no field on screen, so the caller can list them in words: a
 * problem is never dropped because the page has nowhere to put it.
 */
export function markFieldErrors(roots, fieldErrors) {
    const unplaced = [];
    let first = null;
    for (const problem of fieldErrors || []) {
        const field = findField(roots, String(problem.field));
        if (!field) {
            unplaced.push(problem);
            continue;
        }
        field.classList.add('field--invalid');
        const messageId = nextId('problem');
        field.append(el('p', {class: 'field__error', id: messageId}, problem.message));
        const control = field.querySelector('input, select, textarea');
        if (control) {
            control.setAttribute('aria-invalid', 'true');
            const described = control.getAttribute('aria-describedby');
            control.setAttribute('aria-describedby', described ? described + ' ' + messageId : messageId);
            first = first || control;
        }
    }
    if (first) {
        first.focus();
    }
    return unplaced;
}

function findField(roots, name) {
    // procedureCodes[0].code is about the procedure codes field: the page has one control for the list.
    const candidates = [name, name.replace(/\[\d+\].*$/, '')];
    for (const candidate of candidates) {
        for (const root of roots) {
            for (const field of root.querySelectorAll('[data-field]')) {
                if (field.dataset.field === candidate) {
                    return field;
                }
            }
        }
    }
    return null;
}

/**
 * The summary of a refused form: the server's title, what to do, and any problem that names no
 * field on screen. Shown beside the button that was pressed.
 */
export function formProblem(error, unplaced) {
    const node = el('div', {class: 'form-problem', role: 'alert'},
        el('p', {class: 'form-problem__title'}, error.title),
        error.action ? el('p', null, error.action) : null);
    if (unplaced && unplaced.length > 0) {
        append(node, el('ul', {class: 'note-list'}, unplaced.map(problem =>
            el('li', null, el('code', {class: 'literal'}, problem.field), ': ', problem.message))));
    }
    return node;
}
