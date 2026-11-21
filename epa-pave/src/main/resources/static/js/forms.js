// Form fields, and marking the ones the server refused.
//
// The server checks a form and answers 422 with one entry per problem, each naming its field the
// way the request names it (member.memberId, provider.npi, procedureCodes[0].code,
// variables.baseUrl). A field here carries that same name in data-field, so a problem lands on
// the field it is about with the server's own sentence beneath it. This page checks nothing
// itself: a second set of rules in the browser would drift from the first.

import {el, append, fill, button} from './dom.js';

let sequence = 0;

/**
 * The hint on every field whose text the ledger keeps as typed: the note, the tester reference and
 * the expectation of a run, on every tab, and the note editor of a run. The same words everywhere,
 * so a tester reads one rule. The server refuses text that holds the member data of the request it
 * goes with, but it cannot know member data the request does not carry, so the rule is said here.
 */
export const LEDGER_TEXT_HINT = 'The ledger keeps this as typed. Do not type member data into it.';

function nextId(prefix) {
    sequence += 1;
    return prefix + '-' + sequence;
}

/**
 * A labelled text input.
 *
 * spec: label, field (the name the server uses for it), placeholder, hint, literal (true for an
 * identifier or a code, which is set in the monospace face), suggestions (plain values offered
 * while typing; anything else can still be typed), wide (true to span the grid), beside (a
 * control that belongs on the same line as the input, such as the button that looks the
 * identifier up), secret (true for a value that must not be readable on a shared screen),
 * origin (true to give the field a short note on its label's line, saying where its value came
 * from; the control then has an origin node to write it in).
 */
export function textField(spec) {
    const id = nextId('field');
    const input = el('input', {
        type: 'text', id, name: spec.field, placeholder: spec.placeholder, autocomplete: 'off', spellcheck: false,
        class: spec.literal ? 'literal-input' : null, inputMode: spec.inputMode
    });
    const label = el('label', {htmlFor: id}, spec.label);
    // The note shares the label's line, so a grid of details does not grow a line per field.
    const origin = spec.origin ? el('span', {class: 'field__origin', id: id + '-origin'}) : null;
    const parts = [origin ? el('div', {class: 'field__head'}, label, origin) : label,
        spec.beside ? el('div', {class: 'field__row'}, input, spec.beside) : input];
    if (spec.suggestions && spec.suggestions.length > 0) {
        const listId = nextId('suggestions');
        parts.push(el('datalist', {id: listId}, spec.suggestions.map(value => el('option', {value}))));
        input.setAttribute('list', listId);
    }
    const describedBy = [];
    if (spec.hint) {
        const hintId = nextId('hint');
        parts.push(el('span', {class: 'field__hint', id: hintId}, spec.hint));
        describedBy.push(hintId);
    }
    if (origin) {
        describedBy.push(origin.id);
    }
    if (describedBy.length > 0) {
        input.setAttribute('aria-describedby', describedBy.join(' '));
    }
    const node = el('div', {class: 'field' + (spec.wide ? ' field--wide' : ''), dataset: {field: spec.field}}, parts);
    const control = {
        node, input, origin,
        value: () => input.value.trim(),
        set: value => { input.value = value === undefined || value === null ? '' : String(value); },
        /**
         * Makes the input one whose text is not readable on screen, or an ordinary one again.
         * The value is never offered back by the browser: it belongs to one run and is not a
         * password of the person typing it.
         */
        setSecret(secret) {
            input.type = secret ? 'password' : 'text';
            input.autocomplete = secret ? 'new-password' : 'off';
        }
    };
    if (spec.secret) {
        control.setSecret(true);
    }
    return control;
}

/**
 * A labelled box for long literal text, such as a request body. It never wraps a line, because
 * where a line ends is part of what the tester pasted.
 *
 * spec: label, field, rows, hint, placeholder, labelClass.
 */
export function textArea(spec) {
    const id = nextId('field');
    const input = el('textarea', {
        id, name: spec.field, rows: spec.rows || 12, wrap: 'off', spellcheck: false, autocomplete: 'off',
        placeholder: spec.placeholder, class: 'literal-area'
    });
    const parts = [el('label', {htmlFor: id, class: spec.labelClass}, spec.label), input];
    if (spec.hint) {
        const hintId = nextId('hint');
        parts.push(el('span', {class: 'field__hint', id: hintId}, spec.hint));
        input.setAttribute('aria-describedby', hintId);
    }
    const node = el('div', {class: 'field field--wide', dataset: {field: spec.field}}, parts);
    // The text is given back exactly as it stands. A request body is sent as written, so not even
    // the white space around it is this page's to remove.
    return {node, input, value: () => input.value, set: value => { input.value = value === undefined || value === null ? '' : String(value); }};
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
        unmark(marked);
    }
}

/** The event a field sends up the page when its mark is removed, so a summary that names it can drop it. */
export const UNMARKED = 'pave-unmarked';

/** The event a field sends up the page when it is marked, so a summary on screen can name it too. */
export const MARKED = 'pave-marked';

/** Removes the mark of a refusal from one field: the frame, the server's sentence and what told assistive technology of both. */
export function unmark(field) {
    const wasMarked = field.classList.contains('field--invalid');
    field.classList.remove('field--invalid');
    const gone = [];
    for (const message of field.querySelectorAll('.field__error')) {
        gone.push(message.id);
        message.remove();
    }
    for (const control of field.querySelectorAll('[aria-invalid="true"]')) {
        control.removeAttribute('aria-invalid');
        // The sentence is gone, so the control no longer points at it. Anything else it is described by stays.
        const described = (control.getAttribute('aria-describedby') || '').split(/\s+/).filter(id => id !== '' && !gone.includes(id));
        if (described.length > 0) {
            control.setAttribute('aria-describedby', described.join(' '));
        } else {
            control.removeAttribute('aria-describedby');
        }
    }
    if (wasMarked) {
        field.dispatchEvent(new CustomEvent(UNMARKED, {bubbles: true}));
    }
}

/**
 * Marks each field a 422 names, with the server's sentence beneath it.
 *
 * Returns the problems that name no field on screen, so the caller can list them in words: a
 * problem is never dropped because the page has nowhere to put it.
 *
 * options.focus: false to leave the focus where it is. A refusal that arrives while the tester
 * is already typing in the next field must not pull them back out of it.
 * options.placed: an array; each problem marked at a field is pushed to it as {field, label,
 * message}, so the summary can name the field and take the tester to it. A caller that passes it
 * shows a summary, and the summary takes the focus (showFormProblem), so no field is scrolled to
 * or focused here: a field brought into view pushed the summary above the visible part of the form.
 *
 * A field inside a closed fold has the fold opened first, so no refusal sits where nobody sees it.
 */
export function markFieldErrors(roots, fieldErrors, options) {
    const unplaced = [];
    const placed = options && Array.isArray(options.placed) ? options.placed : null;
    let first = null;
    for (const problem of fieldErrors || []) {
        const field = findField(roots, String(problem.field));
        if (!field) {
            unplaced.push(problem);
            continue;
        }
        openFoldsAround(field);
        if (placed) {
            placed.push({field, label: fieldLabel(field, problem.field), message: problem.message});
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
        field.dispatchEvent(new CustomEvent(MARKED, {bubbles: true}));
    }
    if (first && !placed && !(options && options.focus === false)) {
        // The field is brought into view whole, label and message included, before it takes the
        // focus. Focus alone would scroll only the input to the edge of the pane.
        const field = first.closest('[data-field]');
        if (field && field.scrollIntoView) {
            field.scrollIntoView({block: 'center'});
        }
        first.focus({preventScroll: true});
    }
    return unplaced;
}

/** Opens every closed fold the node sits in, from the inside out. */
function openFoldsAround(node) {
    for (let fold = node.closest('details:not([open])'); fold; fold = fold.closest('details:not([open])')) {
        fold.open = true;
    }
}

/**
 * The words a field is known by on screen: its label or its legend, and its name only when it has
 * neither, so a summary can name it the way the tester sees it.
 */
export function fieldLabel(field, fallback) {
    const label = field ? field.querySelector('label:not(.choice), legend, .field__label') : null;
    // A label can carry marks beside its words (a DTR question's "asked now"); only its words name the field.
    const words = label ? label.querySelector('.field__label-text') || label : null;
    const text = words ? words.textContent.trim() : '';
    return text !== '' ? text : String(fallback || '');
}

/** Opens the folds around a marked field, brings it into view and puts the focus in it. */
function focusField(field) {
    openFoldsAround(field);
    field.scrollIntoView({block: 'center'});
    const control = field.querySelector('input:not([type="hidden"]), select, textarea, button');
    if (control) {
        control.focus({preventScroll: true});
    }
}

function findField(roots, name) {
    // procedureCodes[0].code is about the procedure codes field: the page has one control for the list.
    const candidates = [name, name.replace(/\[\d+\].*$/, '')];
    for (const candidate of candidates) {
        for (const root of roots) {
            // A root may be the field itself, as when one lookup marks the one field it was made from.
            for (const field of [root, ...root.querySelectorAll('[data-field]')]) {
                if (field.dataset.field === candidate) {
                    return field;
                }
            }
        }
    }
    return null;
}

/**
 * The lines a summary shows for the marked fields: one per field, in the order of the form, with
 * the sentences the field shows. A field the server named twice still gets one line.
 */
function linesFor(fields, messageOf) {
    const unique = fields.filter((field, index) => fields.indexOf(field) === index);
    unique.sort((a, b) => (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1));
    return unique.map(field => ({field, label: fieldLabel(field, field.dataset.field), message: messageOf(field)}));
}

function linesList(lines) {
    return el('ul', {class: 'form-problem__fields'}, lines.map(line =>
        el('li', null, button(line.label, () => focusField(line.field), {class: 'button--text'}), ': ', line.message)));
}

function signature(lines) {
    return lines.map(line => line.label + ': ' + line.message).join('\n');
}

// What each summary lists now, so a mark that changed nothing leaves the summary as it is.
const listed = new WeakMap();

/**
 * Makes the summaries in a slot list the fields marked now inside scope, as the fields show them:
 * a mark that goes takes its line, a mark replaced by another sentence changes it, a field marked
 * while the summary is shown gets one. The server's title counted the problems at the send, so
 * once the list differs the title says what is left instead. With no field and no other problem
 * left, the summary goes. A field inside a part of the page with a summary of its own
 * (data-summary="own") belongs to that one.
 */
export function mirrorMarks(slot, scope) {
    for (const summary of slot.querySelectorAll('.form-problem')) {
        const marked = Array.from(scope.querySelectorAll('.field--invalid')).filter(field => {
            const own = field.closest('[data-summary="own"]');
            return !own || own.contains(summary);
        });
        const lines = linesFor(marked, field =>
            Array.from(field.querySelectorAll('.field__error'), message => message.textContent.trim()).join(' '));
        if (signature(lines) === listed.get(summary)) {
            continue;
        }
        listed.set(summary, signature(lines));
        const others = summary.querySelectorAll('.note-list > li').length;
        if (lines.length === 0 && others === 0) {
            summary.remove();
            continue;
        }
        const list = summary.querySelector('.form-problem__fields');
        if (list) {
            list.remove();
        }
        if (lines.length > 0) {
            const notes = summary.querySelector('.note-list');
            summary.insertBefore(linesList(lines), notes);
        }
        const title = summary.querySelector('.form-problem__title');
        if (title) {
            title.textContent = 'Nothing was sent. Still to correct:';
        }
    }
}

/**
 * The status line after a refused form, in the past tense with the count the refusal had, so it
 * stays true while the tester corrects the fields; the summary says what is left.
 * what: 'comparison' for the CRD comparison, which sends nothing; left out, a send.
 */
export function refusedSaying(count, what) {
    const problems = count + (count === 1 ? ' problem' : ' problems');
    if (what === 'comparison') {
        return 'The comparison was refused for ' + problems + ' in the form. Nothing was compared.';
    }
    // A preview sends nothing, so it is not said to be a send that was refused.
    if (what === 'preview') {
        return 'The request cannot be shown: ' + problems + ' in the form.';
    }
    return 'The send was refused for ' + problems + ' in the form. Nothing was sent.';
}

/**
 * Puts a summary in its slot, scrolls the part of the page it sits in so the summary is at the top
 * of what is visible, and puts the keyboard focus on it. It is where a tester and a screen reader
 * land after a refusal; its links then take them to each field.
 */
export function showFormProblem(slot, node) {
    fill(slot, node);
    if (node.scrollIntoView) {
        node.scrollIntoView({block: 'start'});
    }
    node.focus({preventScroll: true});
}

/**
 * The summary of a refused form: the server's title, what to do, each field marked by its label,
 * as a button that takes the tester to it, in the order of the form, and any problem that names no
 * field on screen. Shown at the top of the form, by showFormProblem.
 *
 * placed, optional: what markFieldErrors pushed to options.placed.
 */
export function formProblem(error, unplaced, placed) {
    // Focusable from script only, so a refusal can put the keyboard here without adding a Tab stop.
    const node = el('div', {class: 'form-problem', role: 'alert', tabIndex: -1},
        el('p', {class: 'form-problem__title'}, error.title),
        error.action ? el('p', null, error.action) : null);
    const entries = placed || [];
    const lines = linesFor(entries.map(entry => entry.field), field =>
        entries.filter(entry => entry.field === field).map(entry => entry.message).join(' '));
    listed.set(node, signature(lines));
    if (lines.length > 0) {
        append(node, linesList(lines));
    }
    if (unplaced && unplaced.length > 0) {
        append(node, el('ul', {class: 'note-list'}, unplaced.map(problem =>
            el('li', null, el('code', {class: 'literal'}, problem.field), ': ', problem.message))));
    }
    return node;
}
