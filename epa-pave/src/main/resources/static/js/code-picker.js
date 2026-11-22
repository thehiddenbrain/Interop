// The procedure codes of the case: a type-ahead over the code list, and the codes chosen, each a
// line with its description cut in the page and what the list says of it ("PA yes", "PA no", "PA
// not stated", the vendor it names). The list can disagree with what the service answers, and that
// disagreement is a finding, so the list's word is drawn as the list's and never as the answer.
//
// The type-ahead is a combobox in the ARIA sense: the input keeps the focus, the arrows move
// through the options, Enter adds, Escape closes, Tab leaves. Any code typed is taken (a code not
// on the list is a negative case) and several can be pasted at once. Requests to the list wait for
// a pause in typing, and an answer that arrives after a newer question was asked is dropped. A list
// that cannot be reached is said in one line and never stops a code from being typed by hand.
// Nothing is kept: no recent codes, no cache.

import {call} from './api.js';
import {el, chip, button, fill} from './dom.js';

const PAUSE_MS = 220;
const DESCRIPTION_SHOWN = 40;

let sequence = 0;

/** A code list description as the page shows it: its first 40 characters and "...", whole in its title. */
export function cutDescription(text) {
    const whole = typeof text === 'string' ? text : '';
    return whole.length <= DESCRIPTION_SHOWN ? whole : whole.slice(0, DESCRIPTION_SHOWN).trimEnd() + '...';
}

/** True for a character that separates codes pasted together. */
function separator(character) {
    return character === ' ' || character === ',' || character === ';' || character === '|' || character === '\n' || character === '\t'
        || character === '\r';
}

/** The codes in a text, split at the separators, empty parts left out. */
export function splitCodes(text) {
    const out = [];
    let current = '';
    for (const character of String(text || '')) {
        if (separator(character)) {
            if (current !== '') {
                out.push(current);
            }
            current = '';
        } else {
            current += character;
        }
    }
    if (current !== '') {
        out.push(current);
    }
    return out;
}

/**
 * spec.lineOfBusiness()  the line of business in the case now, or ''
 * spec.changed()         called whenever the codes of the case change
 */
export function createCodePicker(spec) {
    sequence += 1;
    const ids = {input: 'code-' + sequence, list: 'code-options-' + sequence, status: 'code-status-' + sequence};

    // Each entry: {input: {code, system?, description?}, rows (the list's rows for the code, or null), check}
    let entries = [];
    let options = [];
    let active = -1;
    let asked = 0;
    let pause = null;

    const input = el('input', {
        type: 'text', id: ids.input, class: 'input mono', autocomplete: 'off', spellcheck: false, role: 'combobox',
        'aria-autocomplete': 'list', 'aria-expanded': 'false', 'aria-controls': ids.list, 'aria-describedby': ids.status
    });
    const add = el('button', {type: 'button', class: 'btn btn--icon', 'aria-label': 'Add this code', title: 'Add this code',
        on: {click: () => { commitTyped(); input.focus(); }}}, '+');
    const listbox = el('ul', {class: 'picker__options', id: ids.list, role: 'listbox', 'aria-label': 'Codes on the code list'});
    const popup = el('div', {class: 'picker__popup', hidden: true}, listbox);
    const status = el('span', {class: 'visually-hidden', id: ids.status, role: 'status', 'aria-live': 'polite'});
    const note = el('p', {class: 'field__hint picker__note', 'aria-live': 'polite'});
    const lines = el('div', {class: 'code-lines', 'aria-label': 'Procedure codes in this case'});
    const node = el('div', {class: 'field picker', 'data-field': 'procedureCodes'},
        el('label', {class: 'field__label', for: ids.input}, 'Procedure code'),
        el('div', {class: 'picker__entry'}, el('div', {class: 'inline-controls'}, input, add), popup),
        status, note, lines);

    input.addEventListener('input', () => {
        note.textContent = '';
        if (splitCodes(input.value).length > 1) {
            close();
            return;
        }
        clearTimeout(pause);
        pause = setTimeout(search, PAUSE_MS);
        spec.changed();
    });
    input.addEventListener('paste', () => {
        setTimeout(() => {
            if (splitCodes(input.value).length > 1) {
                commitTyped();
            }
        }, 0);
    });
    input.addEventListener('keydown', event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            if (popup.hidden) {
                search(true);
                return;
            }
            move(event.key === 'ArrowDown' ? 1 : -1);
        } else if (event.key === 'Enter') {
            event.preventDefault();
            if (!popup.hidden && active >= 0) {
                choose(options[active]);
            } else {
                commitTyped();
            }
        } else if (event.key === 'Escape') {
            if (!popup.hidden) {
                event.preventDefault();
                close();
            }
        } else if (event.key === 'Tab') {
            close();
        }
    });
    // A click on an option keeps the focus in the box (see its mousedown), so a blur is leaving it.
    input.addEventListener('blur', () => close());

    function move(step) {
        if (options.length === 0) {
            return;
        }
        active = active < 0 ? (step > 0 ? 0 : options.length - 1) : (active + step + options.length) % options.length;
        listbox.querySelectorAll('[role="option"]').forEach((option, index) => option.setAttribute('aria-selected', index === active ? 'true' : 'false'));
        input.setAttribute('aria-activedescendant', ids.list + '-' + active);
    }

    async function search(first) {
        clearTimeout(pause);
        const query = String(input.value || '').trim();
        if (query === '' && first !== true) {
            close();
            return;
        }
        asked += 1;
        const mine = asked;
        const answer = await call('GET', '/api/procedure-codes', {query: {q: query, lob: spec.lineOfBusiness() || undefined}});
        if (mine !== asked || document.activeElement !== input) {
            // A newer question was asked, or the tester has left the box: this answer is about neither.
            return;
        }
        if (!answer.ok) {
            close();
            note.textContent = 'The code list did not answer. A code typed here is still added as typed.';
            return;
        }
        open(answer.data);
    }

    function open(found) {
        options = byCode(Array.isArray(found.codes) ? found.codes : []);
        active = -1;
        fill(listbox, ...options.map((option, index) => optionNode(option, index)));
        popup.hidden = options.length === 0;
        input.setAttribute('aria-expanded', options.length === 0 ? 'false' : 'true');
        status.textContent = options.length === 0 ? 'No code on the list matches; Enter adds it as typed.'
            : options.length + (options.length === 1 ? ' code on the list matches.' : ' codes on the list match.');
    }

    function close() {
        clearTimeout(pause);
        asked += 1;
        popup.hidden = true;
        options = [];
        active = -1;
        input.setAttribute('aria-expanded', 'false');
        input.removeAttribute('aria-activedescendant');
    }

    function optionNode(option, index) {
        const first = option.rows[0];
        const node = el('li', {id: ids.list + '-' + index, role: 'option', class: 'picker__option', 'aria-selected': 'false',
            title: first.description || null},
        el('span', {class: 'mono'}, option.code), ' ', el('span', {class: 'small'}, cutDescription(first.description)), ' ',
        tags(option.rows));
        node.addEventListener('mousedown', event => event.preventDefault());
        node.addEventListener('click', () => { choose(option); input.focus(); });
        return node;
    }

    function choose(option) {
        const first = option.rows[0];
        repeated(addEntry({code: option.code, system: first.system, description: first.description}, option.rows) ? [] : [option.code]);
        input.value = '';
        close();
        draw();
    }

    /** Moves whatever is typed in the box into the case, as typed. */
    function commitTyped() {
        const typed = splitCodes(input.value);
        if (typed.length === 0) {
            return;
        }
        repeated(typed.filter(value => !addEntry({code: value}, null)));
        input.value = '';
        close();
        draw();
        checkTyped();
    }

    /**
     * Adds a code to the case unless the case holds it already. Every way a code gets into the case
     * comes through here, so the same code is never sent twice. Returns false when nothing was added.
     */
    function addEntry(candidate, rows) {
        if (!candidate || !candidate.code || entries.some(existing => existing.input.code === String(candidate.code))) {
            return false;
        }
        const kept = {code: String(candidate.code)};
        if (candidate.system) {
            kept.system = candidate.system;
        }
        if (candidate.description) {
            kept.description = candidate.description;
        }
        entries = entries.concat([{input: kept, rows: rows || null, check: rows ? 'listed' : 'pending'}]);
        return true;
    }

    function repeated(values) {
        const distinct = [...new Set(values.filter(Boolean))];
        note.textContent = distinct.length === 0 ? ''
            : distinct.join(', ') + (distinct.length === 1 ? ' is in the case already.' : ' are in the case already.');
    }

    /** Asks the list about each code typed by hand, so its line says what the list says, or that it is not on it. */
    async function checkTyped() {
        for (const entry of entries.filter(each => each.check === 'pending')) {
            entry.check = 'asking';
            const answer = await call('GET', '/api/procedure-codes', {query: {q: entry.input.code, lob: spec.lineOfBusiness() || undefined}});
            if (!entries.includes(entry)) {
                continue;
            }
            const rows = answer.ok && Array.isArray(answer.data.codes) ? answer.data.codes.filter(row => row.code === entry.input.code) : [];
            entry.rows = rows;
            entry.check = !answer.ok ? 'failed' : rows.length > 0 ? 'listed' : 'not-listed';
            draw(true);
        }
    }

    function draw(quiet) {
        fill(lines, ...entries.map(entry => {
            const description = entry.input.description || (entry.rows && entry.rows.length > 0 ? entry.rows[0].description : '');
            return el('div', {class: 'code-line'},
                chip(entry.input.code, {mono: true}),
                description ? el('span', {class: 'code-line__text', title: description}, cutDescription(description)) : null,
                entry.check === 'not-listed' ? chip('not on the list', {dashed: true}) : null,
                entry.check === 'failed' ? chip('not checked', {dashed: true, title: 'The code list did not answer.'}) : null,
                entry.rows ? tags(entry.rows) : null,
                button('Remove', () => {
                    entries = entries.filter(other => other !== entry);
                    draw();
                    input.focus();
                }, {small: true, quiet: true, title: 'Remove ' + entry.input.code}));
        }));
        for (const remove of lines.querySelectorAll('button')) {
            remove.setAttribute('aria-label', remove.title);
        }
        if (quiet !== true) {
            spec.changed();
        }
    }

    return {
        node,
        input,
        /**
         * The codes of the case, as their lines show them. Text still in the box is not one of
         * them: nothing the tester did not see as part of the case is sent, and the Workbench
         * refuses a send while there is any, so it is not dropped in silence either.
         */
        values() {
            return entries.map(entry => ({...entry.input}));
        },
        /** Text typed in the box and not added, trimmed. */
        typed() {
            return String(input.value || '').trim();
        },
        replace(candidates) {
            entries = [];
            note.textContent = '';
            for (const candidate of candidates || []) {
                addEntry(typeof candidate === 'string' ? {code: candidate} : candidate, null);
            }
            input.value = '';
            close();
            draw();
            checkTyped();
        }
    };
}

/** One option per code: the list has a row per code and line of business, and a code is chosen once. */
function byCode(rows) {
    const found = new Map();
    for (const row of rows) {
        if (!found.has(row.code)) {
            found.set(row.code, []);
        }
        found.get(row.code).push(row);
    }
    return Array.from(found, ([code, group]) => ({code, rows: group}));
}

/** What the list says of a code: whether it needs prior authorization and the vendor it names, per row. */
function tags(rows) {
    const out = [];
    const seen = new Set();
    for (const row of rows) {
        const word = row.authRequired === 'REQUIRED' ? 'PA yes' : row.authRequired === 'NOT_REQUIRED' ? 'PA no' : 'PA not stated';
        const said = (row.lineOfBusiness ? row.lineOfBusiness + ': ' : '') + word;
        if (!seen.has(said)) {
            seen.add(said);
            out.push(chip(rows.length > 1 && row.lineOfBusiness ? said : word, {tone: 'neutral',
                title: 'What the code list says' + (row.lineOfBusiness ? ' for ' + row.lineOfBusiness : '')}));
        }
        if (row.delegatedVendor && !seen.has('vendor ' + row.delegatedVendor)) {
            seen.add('vendor ' + row.delegatedVendor);
            out.push(chip(row.delegatedVendor, {tone: 'neutral', title: 'The vendor the code list names'}));
        }
    }
    return out;
}
