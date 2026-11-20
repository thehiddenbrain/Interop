// The procedure code picker: the codes of the case, and a type ahead over the code list.
//
// The tester sees what the code list says about a code before sending it: for the line of
// business in the case, whether the list says prior authorization is required, and the vendor it
// names. The list can disagree with what the service answers, and that disagreement is a finding,
// so the list's word is shown as the list's and never as the answer.
//
// The type ahead is a combobox in the ARIA sense: the input keeps the focus, the arrows move
// through the options, Enter adds, Escape closes, Tab leaves. A code that is not on the list is
// still added when typed and Enter is pressed, because an unknown code is a negative case, and it
// is marked as not on the list once added. Several codes can be pasted at once.
//
// Requests to the list wait for a pause in typing, and an answer that arrives after a newer
// question was asked is dropped. A list that cannot be reached is said in one line and never
// stops a code from being typed by hand. Nothing is kept: no recent codes, no cache.

import * as api from './api.js';
import {el, fill, clear, button, code} from './dom.js';

const CODE_SEPARATORS = /[\s,;|]+/;
const PAUSE_MS = 220;

let sequence = 0;

/**
 * spec.lineOfBusiness()  the line of business in the case now, or ''
 * spec.changed()         called whenever the list of codes changes
 */
export function createCodePicker(spec) {
    sequence += 1;
    const ids = {
        input: 'code-picker-' + sequence,
        list: 'code-picker-options-' + sequence,
        hint: 'code-picker-hint-' + sequence,
        status: 'code-picker-status-' + sequence
    };

    // Each entry: {code, system, description, rows (the list's rows for the code, or null when
    // not checked), check ('pending', 'listed', 'other-lines', 'not-listed', 'failed'), sentence}
    let entries = [];
    let options = [];      // what the open list offers: [{code, rows}]
    let active = -1;       // the option the arrows are on, or -1
    let asked = 0;         // counts searches, so an answer to an older one is dropped
    let pause = null;
    let checkedFor = null; // the line of business the chips were last checked for

    const chips = el('ul', {class: 'code-list', aria: {label: 'Procedure codes in this case'}});
    const input = el('input', {
        type: 'text', id: ids.input, autocomplete: 'off', spellcheck: false, class: 'literal-input',
        placeholder: 'type a code or words of its description',
        role: 'combobox',
        aria: {autocomplete: 'list', expanded: 'false', controls: ids.list, describedby: ids.hint + ' ' + ids.status}
    });
    const add = button('Add code', () => { commitTyped(); input.focus(); }, {class: 'button--quiet'});
    const head = el('div', {class: 'picker__head'});
    const listbox = el('ul', {class: 'picker__options', id: ids.list, role: 'listbox', aria: {label: 'Codes on the code list'}});
    const foot = el('div', {class: 'picker__foot'});
    const popup = el('div', {class: 'picker__popup', hidden: true}, head, listbox, foot);
    // Announces how many options there are, and nothing else: the options are read as the arrows reach them.
    const status = el('span', {class: 'visually-hidden', id: ids.status, role: 'status', aria: {live: 'polite'}});
    // Two notes of one line each: whether the list in use is the placeholder, and a list that did not answer.
    const listNote = el('p', {class: 'picker__note'});
    const failNote = el('p', {class: 'picker__note', aria: {live: 'polite'}});
    const node = el('div', {class: 'case-group field picker', dataset: {field: 'procedureCodes'}},
        el('label', {htmlFor: ids.input, class: 'case-legend'}, 'Procedure codes'),
        listNote,
        chips,
        el('div', {class: 'picker__entry'}, el('div', {class: 'code-entry'}, input, add), popup),
        el('span', {class: 'field__hint', id: ids.hint},
            'Arrows choose from the code list, Enter adds. A code not on the list is added as typed. Several can be pasted at once, '
            + 'separated by commas or spaces.'),
        status,
        failNote);

    /* ---------- typing and the keyboard ---------- */

    input.addEventListener('input', () => {
        // A paste of several codes is added at once: the list cannot answer for many codes in one question.
        if (CODE_SEPARATORS.test(input.value.trim())) {
            close();
            return;
        }
        searchSoon();
    });

    input.addEventListener('paste', () => {
        // The pasted text is in the box once the paste is done.
        setTimeout(() => {
            if (CODE_SEPARATORS.test(input.value.trim())) {
                commitTyped();
            }
        }, 0);
    });

    input.addEventListener('keydown', event => {
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            if (popup.hidden) {
                // The list opens with its first option under the arrows, so the next Enter adds that
                // option and never the words typed to find it.
                searchNow(true);
                return;
            }
            move(event.key === 'ArrowDown' ? 1 : -1);
        } else if (event.key === 'Enter') {
            // Enter adds the code. It must not send the request the tester is still composing.
            event.preventDefault();
            if (!popup.hidden && active >= 0) {
                choose(options[active]);
            } else if (!popup.hidden && options.length > 0) {
                // The list is open with matches and none is chosen. The text may be words of a
                // description, which are not a code: the same text is added only from a closed
                // list. Unless it is one of the codes offered, written the same way, the first
                // option is put under the arrows, and the foot says how to add the text as typed.
                const typed = input.value.trim();
                const same = options.findIndex(option => option.code === typed);
                if (same >= 0) {
                    choose(options[same]);
                } else {
                    active = 0;
                    markActive();
                    status.textContent = 'Enter again adds ' + options[0].code + '. To add the text as typed, press Escape, then Enter.';
                }
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
        } else if (event.key === 'Backspace' && input.value === '' && entries.length > 0) {
            entries = entries.slice(0, -1);
            draw();
        }
    });

    input.addEventListener('blur', () => {
        // A click on an option keeps the focus in the box (see the option's mousedown), so a blur is leaving.
        close();
    });

    function move(step) {
        if (options.length === 0) {
            return;
        }
        active = active < 0 ? (step > 0 ? 0 : options.length - 1) : (active + step + options.length) % options.length;
        markActive();
    }

    function markActive() {
        listbox.querySelectorAll('[role="option"]').forEach((option, index) => {
            option.setAttribute('aria-selected', index === active ? 'true' : 'false');
            if (index === active) {
                option.scrollIntoView({block: 'nearest'});
            }
        });
        if (active >= 0) {
            input.setAttribute('aria-activedescendant', ids.list + '-' + active);
        } else {
            input.removeAttribute('aria-activedescendant');
        }
    }

    /* ---------- asking the list ---------- */

    function searchSoon() {
        clearTimeout(pause);
        pause = setTimeout(searchNow, PAUSE_MS);
    }

    async function searchNow(activateFirst) {
        clearTimeout(pause);
        const query = input.value.trim();
        const line = spec.lineOfBusiness();
        asked += 1;
        const mine = asked;
        const answer = await api.get('/api/procedure-codes?q=' + encodeURIComponent(query) + '&lob=' + encodeURIComponent(line));
        if (mine !== asked || document.activeElement !== input) {
            // A newer question was asked, or the tester has left the box. This answer is about neither.
            return;
        }
        if (answer.kind !== 'ok') {
            close();
            fill(failNote, 'The code list could not be searched: ' + answer.body.title + ' A code can still be typed and added by hand.',
                reference(answer.body));
            return;
        }
        clear(failNote);
        open(answer.body, line, activateFirst === true);
    }

    function open(found, line, activateFirst) {
        const rows = found.codes || [];
        options = groupByCode(rows);
        active = activateFirst && options.length > 0 ? 0 : -1;
        const typed = input.value.trim();
        fill(head,
            // Said first, where it is seen before the options: the text typed may be words, and Enter does not add words.
            options.length > 0 && typed !== '' && !options.some(option => option.code === typed)
                ? el('p', {class: 'picker__sentence'}, 'Enter adds the code under the arrows. To add ', code(typed),
                    ' as typed instead, press Escape, then Enter.')
                : null,
            found.notice ? el('p', {class: 'picker__sentence'}, found.notice) : null,
            found.otherLines ? el('p', {class: 'picker__sentence'}, found.otherLines) : null,
            line === '' && options.length > 0
                ? el('p', {class: 'picker__sentence'}, 'The list answers by line of business, and the case has none, so every line the list holds is shown.')
                : null);
        listbox.replaceChildren(...options.map((option, index) => optionNode(option, index, line)));
        const more = typeof found.matched === 'number' && found.matched > rows.length;
        fill(foot, options.length === 0
            ? el('p', null, 'No code on the list matches. Press Enter to add ', typed === '' ? 'a code' : code(typed),
                ' as typed: it will be marked as not on the list.')
            : more ? el('p', null, 'Showing the first ' + rows.length + ' of ' + found.matched + ' matching rows. Type more to narrow them.') : null);
        popup.hidden = false;
        input.setAttribute('aria-expanded', 'true');
        markActive();
        // The pane scrolls; the options are brought into it, so they are not opened out of sight under the send button.
        popup.scrollIntoView({block: 'nearest'});
        status.textContent = options.length === 0 ? 'No code on the list matches.'
            : options.length + (options.length === 1 ? ' code on the list matches.' : ' codes on the list match.')
            + (more ? ' Not all are shown.' : '');
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

    function optionNode(option, index, line) {
        const first = option.rows[0];
        const node = el('li', {id: ids.list + '-' + index, role: 'option', class: 'picker__option', aria: {selected: 'false'}},
            el('span', {class: 'picker__line'}, code(option.code), first.description ? el('span', {class: 'picker__description'}, first.description) : null),
            listSays(option.rows, line));
        // The focus stays in the box while the mouse picks, so the combobox is never left half open.
        node.addEventListener('mousedown', event => event.preventDefault());
        node.addEventListener('click', () => { choose(option); input.focus(); });
        return node;
    }

    /* ---------- the codes of the case ---------- */

    function choose(option) {
        const first = option.rows[0];
        addEntry({code: option.code, system: first.system, description: first.description},
            {rows: option.rows, check: 'listed', line: spec.lineOfBusiness()});
        input.value = '';
        close();
        draw();
    }

    /** Moves whatever is typed in the box into the list, as typed. */
    function commitTyped() {
        const typed = input.value.split(CODE_SEPARATORS).map(part => part.trim()).filter(part => part.length > 0);
        if (typed.length === 0) {
            return;
        }
        for (const value of typed) {
            addEntry({code: value}, null);
        }
        input.value = '';
        close();
        draw();
        checkPending();
    }

    function addEntry(candidate, known) {
        if (!candidate || !candidate.code || entries.some(existing => existing.code === String(candidate.code))) {
            return;
        }
        const kept = {code: String(candidate.code)};
        if (candidate.system) {
            kept.system = candidate.system;
        }
        if (candidate.description) {
            kept.description = candidate.description;
        }
        entries = entries.concat([{
            input: kept,
            rows: known ? known.rows : null,
            check: known ? known.check : 'pending',
            line: known ? known.line : null,
            sentence: null
        }]);
    }

    /**
     * Asks the list about each code added by hand, so the chip can say what the list says, or that
     * the code is not on it. Each code is asked for exactly: the list matches the start of a code,
     * and only a row whose code is the same text is that code.
     */
    async function checkPending() {
        const line = spec.lineOfBusiness();
        for (const entry of entries.filter(each => each.check === 'pending')) {
            entry.check = 'asking';
            const answer = await api.get('/api/procedure-codes?q=' + encodeURIComponent(entry.input.code) + '&lob=' + encodeURIComponent(line));
            if (!entries.includes(entry)) {
                continue;
            }
            entry.line = line;
            if (answer.kind !== 'ok') {
                entry.check = 'failed';
                entry.sentence = answer.body.title + (answer.body.reference ? ' Reference ' + answer.body.reference + '.' : '');
            } else {
                const returned = answer.body.codes || [];
                const rows = returned.filter(row => row.code === entry.input.code);
                // The list is searched without regard to case, so e0601 finds E0601. That is the same
                // code on the list, written another way: it is not "not on the list", and the code
                // is still sent as typed, which the chip says.
                const otherCase = rows.length > 0 ? []
                    : returned.filter(row => String(row.code).toUpperCase() === entry.input.code.toUpperCase());
                entry.rows = rows.length > 0 ? rows : otherCase;
                entry.check = rows.length > 0 ? 'listed' : otherCase.length > 0 ? 'other-case'
                    : answer.body.otherLines ? 'other-lines' : 'not-listed';
                entry.sentence = rows.length > 0 || otherCase.length > 0 ? null : answer.body.otherLines || null;
            }
            draw();
        }
    }

    /** What the list says about each code was for one line of business. When the case's line changes, it is asked again. */
    function lineChanged() {
        const line = spec.lineOfBusiness();
        if (line === checkedFor) {
            return;
        }
        checkedFor = line;
        let any = false;
        for (const entry of entries) {
            if (entry.line !== null && entry.line !== line) {
                entry.check = 'pending';
                any = true;
            }
        }
        if (any) {
            draw();
            checkPending();
        }
    }

    /** Puts the list's own spelling of a code in place of the one typed, at the tester's request. */
    function respell(entry, listed) {
        if (entries.some(other => other !== entry && other.input.code === listed)) {
            entries = entries.filter(other => other !== entry);
        } else {
            entry.input = {...entry.input, code: listed};
            entry.check = 'listed';
        }
        draw();
        input.focus();
    }

    function draw() {
        chips.replaceChildren(...entries.map(entry => {
            const remove = el('button', {
                type: 'button', class: 'code-chip__remove', title: 'Remove ' + entry.input.code, aria: {label: 'Remove ' + entry.input.code},
                on: {click: () => { entries = entries.filter(other => other !== entry); draw(); input.focus(); }}
            }, 'x');
            return el('li', {class: 'code-chip picker__chip'},
                el('span', {class: 'picker__chip-text'},
                    code(entry.input.code),
                    describe(entry) ? el('span', {class: 'picker__description'}, describe(entry)) : null,
                    chipStatus(entry)),
                remove);
        }));
        spec.changed();
    }

    /** The description to show: the one chosen with the code, or for a code typed by hand, the list's. Only the first is sent. */
    function describe(entry) {
        return entry.input.description || (entry.rows && entry.rows.length > 0 ? entry.rows[0].description : null);
    }

    function chipStatus(entry) {
        if (entry.check === 'pending' || entry.check === 'asking') {
            return el('span', {class: 'quiet small'}, 'checking the code list');
        }
        if (entry.check === 'failed') {
            return el('span', {class: 'quiet small'}, 'not checked: the code list did not answer',
                entry.sentence ? el('span', {class: 'picker__chip-sentence'}, entry.sentence) : null);
        }
        if (entry.check === 'other-case') {
            const listed = entry.rows[0].code;
            return el('span', null,
                el('span', {class: 'picker__chip-sentence picker__chip-sentence--strong'}, 'On the list as ', code(listed),
                    '. It will be sent as typed, ', code(entry.input.code), '. ',
                    button('Use ' + listed, () => respell(entry, listed), {
                        class: 'button--text', aria: {label: 'Send ' + listed + ' as the list writes it, in place of ' + entry.input.code}
                    })),
                listSays(entry.rows, entry.line || ''));
        }
        if (entry.check === 'not-listed') {
            return el('span', {class: 'presence presence--none'}, 'not on the list');
        }
        if (entry.check === 'other-lines') {
            return el('span', null, el('span', {class: 'presence presence--none'}, 'not on the list for ' + entry.line),
                entry.sentence ? el('span', {class: 'picker__chip-sentence'}, entry.sentence) : null);
        }
        return listSays(entry.rows || [], entry.line || '');
    }

    return {
        node,
        values() {
            // A code still sitting in the box is part of what the tester means to send. It is
            // moved into the list first, so that the list on screen is the list that goes out.
            commitTyped();
            return entries.map(entry => ({...entry.input}));
        },
        /** The codes in the list as it stands, without touching what is being typed. */
        shown() {
            return entries.map(entry => ({...entry.input}));
        },
        add(candidate) {
            addEntry(candidate, null);
            draw();
            checkPending();
        },
        replace(candidates) {
            entries = [];
            for (const candidate of candidates || []) {
                addEntry(typeof candidate === 'string' ? {code: candidate} : candidate, null);
            }
            input.value = '';
            close();
            draw();
            checkPending();
        },
        lineChanged,
        /** Asks the server whether the list in use is the placeholder that ships with the tool. */
        async checkList() {
            const answer = await api.get('/api/procedure-codes/self-check');
            if (answer.kind !== 'ok') {
                fill(listNote, 'Whether the code list in use is a P32H export could not be checked: ' + answer.body.title);
            } else if (answer.body.placeholder === true) {
                fill(listNote, el('strong', null, 'Placeholder code list in use:'), ' its tags are synthetic, not P32H\'s.');
            } else {
                clear(listNote);
            }
        }
    };
}

/** The reference a failure of this tool carries, so it can be found in the server's log. Nothing when there is none. */
function reference(error) {
    return error && error.reference ? [' Reference ', code(error.reference), '.'] : null;
}

/** One option per code: the list has a row per code and line of business, and a code is chosen once. */
function groupByCode(rows) {
    const byCode = new Map();
    for (const row of rows) {
        if (!byCode.has(row.code)) {
            byCode.set(row.code, []);
        }
        byCode.get(row.code).push(row);
    }
    return Array.from(byCode, ([value, group]) => ({code: value, rows: group}));
}

/**
 * What the list says about a code. One row: its tags. Several rows, one per line of business:
 * each line with its tags. A row that states nothing gets no tag at all, never a "no".
 */
function listSays(rows, line) {
    if (rows.length === 0) {
        return null;
    }
    const byLine = rows.length > 1 || (line === '' && rows[0].lineOfBusiness);
    return el('span', {class: 'picker__says'}, rows.map(row => el('span', {class: 'picker__row'},
        byLine ? el('span', {class: 'picker__lob'}, row.lineOfBusiness ? row.lineOfBusiness : 'every line') : null,
        tags(row))));
}

function tags(row) {
    const out = [];
    if (row.authRequired === 'REQUIRED') {
        out.push(el('span', {class: 'code-tag', title: 'The code list says prior authorization is required' + listedAs(row)}, row.authRequiredLabel || 'PA'));
    } else if (row.authRequired === 'NOT_REQUIRED') {
        out.push(el('span', {class: 'code-tag', title: 'The code list says prior authorization is not required' + listedAs(row)}, row.authRequiredLabel || 'No PA'));
    }
    if (row.delegatedVendor) {
        out.push(el('span', {class: 'code-tag code-tag--vendor', title: 'The vendor the code list names'}, row.delegatedVendor));
    }
    // A row that says neither gets nothing at all: an empty space next to a code is the list's silence, never a no.
    return out;
}

function listedAs(row) {
    return row.authRequiredSource ? ' (written "' + row.authRequiredSource + '" in the list).' : '.';
}
