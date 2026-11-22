// Parts the Configuration pages of this batch share: the head of a page, how production is said,
// the busy line of a button, and a list said once. Each exists once, so two pages can never word
// the same fact two ways.

import {el, clear, button} from './dom.js';
import {app} from './app.js';
import {errorPanel} from './fault.js';

// Between a pull and the restart the page can run new scripts against old classes, and a refusal
// then names a cure the tester cannot use (I-12). The same words as fault.js, written here so the
// lines these pages draw themselves need nothing more from it.
const FILES_CHANGED = "PAVE's files changed on disk since it started, which can cause this. Stop PAVE and start it again, then try once more.";

/** The line under a refusal while /api/mode says PAVE's files changed on disk; null otherwise. */
export function filesChangedNote() {
    const mode = app.mode();
    return mode && mode.build && mode.build.changedOnDisk === true ? el('p', {class: 'files-changed-note small'}, FILES_CHANGED) : null;
}

/**
 * A file the server refused, said as one plain line like the page's own refusals of a file (plan
 * 10.11 M10): "<file>: <title> <action>". answer: what api.js resolved to. An answer that is not a
 * refusal of what was sent (an internal fault with its reference, no answer at all) keeps the
 * error panel, which carries the fault report.
 */
export function fileRefusal(fileName, answer) {
    const error = answer && answer.error ? answer.error : null;
    const refusal = error && answer.status >= 400 && answer.status < 500 && typeof error.title === 'string' && error.title !== '';
    if (!refusal) {
        return errorPanel(error);
    }
    const fields = Array.isArray(error.fieldErrors) ? error.fieldErrors.map(each => each && each.message).filter(Boolean) : [];
    const words = [error.title, error.action].concat(fields).filter(each => typeof each === 'string' && each !== '').join(' ');
    return el('div', {class: 'file-refusal'},
        el('p', {class: 'field__error', role: 'alert'}, fileName ? fileName + ': ' + words : words),
        filesChangedNote());
}

/**
 * The head of a page: its title, one quiet line, and the controls at its end. Built from the
 * prototype's section classes, so every page of Configuration sits in the same frame.
 */
export function pageHead(title, line, ...end) {
    const controls = end.flat().filter(Boolean);
    return el('div', {class: 'section__head'},
        el('h2', {class: 'section__title'}, title),
        line ? el('span', {class: 'muted'}, line) : null,
        controls.length > 0 ? el('div', {class: 'section__end'}, controls) : null);
}

/** A section of a page: a node with the prototype's frame. */
export function section(...children) {
    return el('section', {class: 'section'}, children);
}

/**
 * Whether the active environment is production, in the one set of words every page uses (the
 * guardrail state must never be said two ways). The server decides; nothing is read off a name.
 */
export function productionWords(access, environment) {
    const writes = access || {};
    const active = environment || {};
    if (writes.production === true) {
        return el('strong', null, 'Yes. The active environment is declared as production.');
    }
    return active.active ? 'No. The active environment is declared as ' + active.kind + '.' : 'No environment is active.';
}

/**
 * Runs work while control is disabled, with a line that says what it waits for and counts the
 * seconds (plan R-26): one click never sends twice, and a slow answer never looks like nothing.
 * Returns what work returns.
 */
export async function during(control, line, waitingFor, work) {
    if (control.disabled) {
        return undefined;
    }
    control.disabled = true;
    const started = Date.now();
    const show = () => {
        if (line) {
            line.textContent = waitingFor + '... ' + Math.floor((Date.now() - started) / 1000) + ' s';
        }
    };
    show();
    const timer = setInterval(show, 1000);
    try {
        return await work();
    } finally {
        clearInterval(timer);
        if (line) {
            clear(line);
        }
        control.disabled = false;
    }
}

/** Each sentence once, in the order first given: the server can repeat a note among warnings and notes. */
export function once(...lists) {
    const seen = new Set();
    const out = [];
    for (const list of lists) {
        for (const sentence of Array.isArray(list) ? list : []) {
            if (typeof sentence === 'string' && sentence !== '' && !seen.has(sentence)) {
                seen.add(sentence);
                out.push(sentence);
            }
        }
    }
    return out;
}

/** Sentences as a list, or null when there are none. */
export function sentences(list) {
    const lines = once(list);
    return lines.length > 0 ? el('ul', {class: 'notes'}, lines.map(line => el('li', null, line))) : null;
}

/** A count with its noun: "1 request", "13 requests". */
export function counted(count, one, many) {
    return count + ' ' + (count === 1 ? one : many);
}

/** The date of an ISO instant as written in it, never shifted by the browser's zone; '' when there is none. */
export function day(instant) {
    return typeof instant === 'string' && instant.length >= 10 ? instant.slice(0, 10) : '';
}

/** A quiet word for a value the server did not give. */
export function none(words) {
    return el('span', {class: 'muted'}, words);
}

/** One part of a page, set apart from the one before by a rule and space, with an optional small title. */
export function part(title, ...children) {
    return el('div', {class: 'panel__section form-section'}, title ? el('h3', {class: 'section-label'}, title) : null, children);
}

/**
 * A file input drawn as the prototype's chooser (07b): a "Choose file" button and the chosen file's
 * name, never the browser's own control, whose words and width differ by browser. The input stays
 * the source of what is read; it is kept in the box, out of sight and out of the tab order.
 * label: what the file is, for the button's accessible name. Returns the box; call shownFile(input)
 * after the page reads or lets go of the input, since setting its value fires no event.
 */
export function fileChooser(input, label) {
    const name = el('span', {class: 'file__name'});
    const pick = button('Choose file', () => input.click(), {small: true});
    pick.setAttribute('aria-label', 'Choose file: ' + label);
    input.className = (input.className ? input.className + ' ' : '') + 'visually-hidden';
    input.tabIndex = -1;
    input.showName = () => {
        const chosen = input.value && input.files && input.files.length > 0 ? input.files[0].name : '';
        name.textContent = chosen || 'No file chosen';
        name.className = chosen ? 'file__name' : 'file__name muted';
    };
    input.addEventListener('change', input.showName);
    input.showName();
    return el('div', {class: 'file config-chooser'}, pick, name, input);
}

/** Shows the file an input made by fileChooser holds now, after the page read or let go of it. */
export function shownFile(input) {
    if (input && typeof input.showName === 'function') {
        input.showName();
    }
}

/** Puts a chooser in place of the file input of a field() wrapper, keeping its label, hint and error. */
export function chooserInField(wrapper, input, label) {
    // The children are listed before the box takes the input out of the wrapper.
    const children = Array.from(wrapper.childNodes);
    const box = fileChooser(input, label);
    wrapper.replaceChildren(...children.map(child => (child === input ? box : child)));
    return wrapper;
}
