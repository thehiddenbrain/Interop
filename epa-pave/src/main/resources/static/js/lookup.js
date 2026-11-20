// The member lookup and the provider lookup.
//
// Both are a lookup by identifier and never a search: one identifier in, one record out. The
// server answers 200 for every lookup it made and says in the body how it ended. Each ending is
// drawn as what it is, and none of them as a crash:
//
//   FOUND                   the server's sentence. The case context fills the details from it
//   FOUND, with attention   the same, and every sentence of attention is listed
//   NOT_FOUND               a finding about the test data, said calmly. The case can still be sent
//   FAULT                   a finding about the environment: the error panel, as everywhere else
//   DISABLED                no lookup is configured. The server's sentence, and not an error
//
// A 422 is a refusal before any lookup, and its sentence goes to the field it names.
//
// Nothing of a lookup is kept. The latest answer is held only so that it can be shown, a second
// lookup replaces it on screen, and nothing is written to any browser storage.

import * as api from './api.js';
import {el, fill, clear, button, code, table, heading, facts} from './dom.js';
import {shown} from './shown.js';
import {outcome} from './marks.js';
import {errorPanel, wireView, additionalFieldsSection} from './result.js';

/**
 * A literal in which every space can be seen and counted. The text itself is unchanged, so what
 * is copied from the screen is the identifier with its spaces, and not a drawing of it.
 */
export function spaced(text) {
    const node = el('code', {class: 'literal literal--spaced'});
    for (const part of String(text).split(/(\s)/)) {
        if (part === '') {
            continue;
        }
        node.append(/^\s$/.test(part) ? el('span', {class: 'space-mark'}, part) : part);
    }
    return node;
}

function spaceCount(text) {
    const found = String(text).match(/\s/g);
    const count = found ? found.length : 0;
    return count === 0 ? 'no spaces' : count === 1 ? '1 space' : count + ' spaces';
}

/**
 * A member ID as typed and as sent, with the spaces of each made visible and counted. The service
 * takes the form without spaces, so the tool removes them on the way out, and that is never
 * done out of sight.
 */
export function idForms(forms) {
    if (!forms.changed) {
        return el('span', null, 'Sent as typed: ', spaced(forms.sent), ' (', spaceCount(forms.sent), ').');
    }
    return el('span', null, 'Typed as ', spaced(forms.typed), ' (', spaceCount(forms.typed), ') and sent as ',
        spaced(forms.sent), ' (', spaceCount(forms.sent), ').');
}

/**
 * One lookup: its button, the strip that says how the latest one ended, and the panel that shows
 * everything it returned.
 *
 * spec.subject      the thing looked up, as the screen names it: "member" or "provider"
 * spec.identifier   what it is looked up by, as the screen names it: "member ID" or "NPI"
 * spec.address      the address the identifier is appended to, percent-encoded
 * spec.record       the property of the answer that holds the record: "member" or "provider"
 * spec.read()       the identifier on screen
 * spec.starting()   called as a lookup starts, before anything is asked
 * spec.refused(fieldErrors, viaButton)   called with the problems of a 422, to mark the field
 * spec.answered(answer)   called when a lookup ends: with the answer the server gave, or with
 *                   null when it gave none (the lookup was refused, or the tool did not answer)
 * spec.panelChanged(section)   called when the panel is opened, with what it now shows, and
 *                   with null when it is put away, so the page can bring it into view or close the gap
 * spec.ended(strip) called when a lookup has ended and the strip says how, so the page can bring
 *                   the strip into view without moving the focus
 */
export function createLookup(spec) {
    let latest = 0;        // counts lookups, so an answer that arrives after a newer question is dropped
    let held = null;       // {id, answer}: the lookup on screen, or null
    let pending = null;    // the identifier being looked up now, or null
    let before = null;     // a sentence saying the lookup on screen was made under an earlier configuration, or null
    let opener = null;     // the control that opened the panel, which takes the focus back when the panel is closed
    let pageNote = null;   // a line the page asked to have said under the answer, or null

    const control = button('Look up ' + spec.subject, () => lookUp(true), {class: 'button--quiet lookup-action'});
    // The first line is the live region: it says how the lookup ended and nothing else, so a
    // screen reader hears one sentence and not the whole strip.
    const said = el('p', {class: 'lookup-strip__state', role: 'status', aria: {live: 'polite'}});
    const rest = el('div', {class: 'lookup-strip__rest'});
    // It can be given the focus, so that a tester sent to it from the send button lands on what it says.
    const strip = el('div', {class: 'lookup-strip', hidden: true, tabIndex: -1}, said, rest);
    const panel = el('div', {class: 'lookup-panel'});

    /**
     * Draws the strip. options.spoken: the first line is for a screen reader only, because what
     * follows it on screen says the same in a form of its own.
     */
    function show(state, sentence, more, options) {
        strip.hidden = false;
        said.classList.toggle('visually-hidden', Boolean(options && options.spoken));
        fill(said, state, state ? ' ' : null, sentence);
        fill(rest, more);
    }

    /** Shows the panel, or puts it away when given nothing, and tells the page that it changed. */
    function setPanel(section) {
        fill(panel, section);
        spec.panelChanged(section || null);
    }

    /**
     * Closes the panel at the tester's request. The focus goes back to the button that opened it,
     * or, when the strip has been drawn again since, to the button that looks the identifier up,
     * so a keyboard user is not sent back to the top of the page.
     */
    function closePanel() {
        const back = opener && opener.isConnected ? opener : control;
        opener = null;
        setPanel(null);
        back.focus();
    }

    function forget() {
        latest += 1;
        pending = null;
        held = null;
        before = null;
        pageNote = null;
        control.removeAttribute('aria-busy');
        strip.hidden = true;
        clear(said);
        clear(rest);
        setPanel(null);
    }

    async function lookUp(viaButton) {
        const id = spec.read();
        latest += 1;
        const mine = latest;
        held = null;
        before = null;
        pageNote = null;
        setPanel(null);
        spec.starting();
        pending = id;
        control.setAttribute('aria-busy', 'true');
        show(null, 'Looking up the ' + spec.subject + '.');
        // An empty identifier is asked for as one space, which reaches the server as a blank
        // identifier: the server then says what is wrong, in its own words, at the field.
        // The identifier goes as a query parameter, so one that holds a slash or a backslash reaches
        // the server as typed and is answered there, in its words, rather than refused on the way.
        const answer = await api.get(spec.address + encodeURIComponent(id === '' ? ' ' : id));
        if (mine !== latest) {
            // A newer lookup, or a change of the identifier, has replaced this one. Its answer is about something no longer on screen.
            return;
        }
        pending = null;
        control.removeAttribute('aria-busy');
        if (answer.kind === 'form') {
            // The identifier cannot be looked up as it is written. The sentence about it goes to the field.
            show(null, answer.body.title);
            spec.refused(answer.body.fieldErrors || [], viaButton);
            spec.answered(null);
            spec.ended(strip);
            return;
        }
        if (answer.kind !== 'ok') {
            // This tool could not answer. That says nothing about the member or the provider.
            show(null, 'The ' + spec.subject + ' lookup could not be made.', errorPanel(answer.body));
            spec.answered(null);
            spec.ended(strip);
            return;
        }
        held = {id, answer: answer.body};
        draw();
        spec.answered(answer.body);
        spec.ended(strip);
    }

    function draw() {
        const answer = held.answer;
        const attention = answer.attention || [];
        const extra = [];
        if (before) {
            extra.push(el('p', {class: 'lookup-strip__before'}, before));
        }
        if (answer.memberId && answer.memberId.changed) {
            extra.push(el('p', {class: 'lookup-strip__ids'}, idForms(answer.memberId)));
        }
        if (pageNote) {
            extra.push(el('p', {class: 'lookup-strip__note'}, pageNote));
        }
        // How the lookup ended, in the server's words for it when it sends them.
        const label = typeof answer.statusLabel === 'string' && answer.statusLabel !== '' ? answer.statusLabel : null;
        if (answer.status === 'FOUND') {
            if (attention.length > 0) {
                extra.push(attentionList(attention));
            }
            extra.push(returnedLine(answer));
            show(attention.length > 0 ? el('span', {class: 'flag'}, 'to check') : el('strong', null, (label || 'Found') + '.'), answer.message, extra);
        } else if (answer.status === 'NOT_FOUND') {
            // A finding about the test data, drawn in the mark the result view gives that meaning.
            extra.push(el('p', null, 'The case can still be sent: a ' + spec.subject + ' the lookup does not know is a test case.'));
            extra.push(returnedLine(answer));
            show(outcome((label || 'Not found') + '.', 'DATA'), answer.message, extra);
        } else if (answer.status === 'DISABLED') {
            // Nothing was asked, so there is nothing to show beyond the server's sentence.
            show(el('span', {class: 'presence presence--none'}, label || 'no lookup'), answer.message, extra);
        } else if (answer.status === 'FAULT' && answer.error) {
            // The error panel says what happened and what to do, so the first line is spoken and not drawn twice.
            extra.push(errorPanel(answer.error));
            extra.push(returnedLine(answer));
            show(null, 'The ' + spec.subject + ' lookup failed. ' + answer.message, extra, {spoken: true});
        } else {
            // A state this page does not know is shown under its own name, with the server's sentence.
            extra.push(returnedLine(answer));
            show(code(String(answer.status)), answer.message, extra);
        }
    }

    /** What the answer carried, in a line, with the button that shows all of it. */
    function returnedLine(answer) {
        const fields = (answer.fields || []).length;
        const masked = (answer.fields || []).filter(field => field.masked === true).length;
        const additional = (answer.additionalFields || []).length;
        const counts = [];
        if (fields > 0) {
            counts.push(fields + (fields === 1 ? ' field read' : ' fields read'));
        }
        if (masked > 0) {
            // A field the server marks as masked holds something this tool masked, and is never put into the case.
            counts.push(masked + ' masked and not taken into the case');
        }
        if (additional > 0) {
            counts.push(additional + ' returned but not interpreted');
        }
        return el('p', {class: 'lookup-strip__returned'},
            counts.length > 0 ? el('span', {class: 'quiet'}, counts.join(', ') + '. ') : null,
            button('Show what the lookup returned', event => {
                opener = event.currentTarget;
                openPanel();
            }, {class: 'button--text'}));
    }

    function attentionList(sentences) {
        return el('ul', {class: 'note-list attention-list'}, sentences.map(sentence => el('li', null, sentence)));
    }

    function openPanel() {
        if (!held) {
            return;
        }
        const answer = held.answer;
        const attention = answer.attention || [];
        const fields = answer.fields || [];
        const section = el('section', {class: 'panel', tabIndex: -1, aria: {label: 'What the ' + spec.subject + ' lookup returned'}},
            el('div', {class: 'panel__head'},
                heading(2, 'What the ' + spec.subject + ' lookup returned'),
                button('Close', closePanel, {class: 'button--quiet button--small'})),
            el('p', null, answer.message),
            before ? el('p', {class: 'lookup-strip__before'}, before) : null,
            facts([
                ['How it ended', code(String(answer.status))],
                ['Looked up', spaced(held.id)],
                ['Member ID', answer.memberId ? idForms(answer.memberId) : null]
            ], 'facts--ruled'),
            attention.length > 0 ? [el('p', {class: 'sub-heading'}, 'To check before sending (' + attention.length + ')'), attentionList(attention)] : null,
            answer.error ? errorPanel(answer.error) : null,
            el('section', {class: 'result__section'},
                heading(3, 'Fields', fields.length),
                fields.length === 0
                    ? el('p', {class: 'quiet'}, 'No ' + spec.subject + ' record was read from this answer, so there are no fields to list.')
                    : [el('p', {class: 'quiet'}, 'Every field this tool looks for, with what the answer held for it and where in the answer it was read from.'),
                        fieldsTable(fields)]),
            additionalFieldsSection(answer.additionalFields || [], null, answer.wire),
            el('section', {class: 'result__section'}, wireView(answer.wire)));
        setPanel(section);
    }

    return {
        /** The button that looks the identifier up. It sits beside the field. */
        control,
        /** How the latest lookup ended. It sits under the field. */
        strip,
        /** Everything the latest lookup returned, when asked for. The page places it where there is room. */
        panel,
        lookUp,
        forget,
        /** The identifier the lookup on screen was made for, or the one being looked up now, or null. */
        id: () => (held ? held.id : pending),
        /**
         * The identifier that needs no new lookup when the tester leaves the field: the one being
         * looked up now, or the one the answer on screen was given for, unless that answer was a
         * fault. A fault says nothing about the identifier, and its sentence asks the tester to try
         * again by leaving the field, so leaving it does.
         */
        settledId: () => (pending !== null ? pending : held && held.answer.status !== 'FAULT' ? held.id : null),
        /** The answer on screen, or null. */
        answer: () => (held ? held.answer : null),
        /** The sentences a found record still needs a look for. Empty when there are none or nothing was found. */
        attention: () => (held && held.answer.status === 'FOUND' ? held.answer.attention || [] : []),
        /**
         * Marks the lookup on screen as made under an earlier configuration. It stays, because the
         * details it filled are still on the form, and it says that the environment now active was not asked.
         */
        madeBefore(sentence) {
            if (held && !before) {
                before = sentence;
                setPanel(null);
                draw();
            }
        },
        outdated: () => before !== null,
        /**
         * A line the page adds under the answer on screen, such as that a pasted request names
         * another person than the one this lookup found. Null takes it away. A new lookup starts without one.
         */
        note(text) {
            pageNote = text || null;
            if (held) {
                draw();
            }
        }
    };
}

/** The fields of a record: what each is called, what the answer held for it, and where it was read from. */
function fieldsTable(fields) {
    return table([{text: 'Field', class: 'col-25'}, 'Value returned', {text: 'Read from', class: 'col-30'}], fields.map(field => [
        field.label || code(String(field.name)),
        // A masked value is shown with its mask and the server's note, never as data: the case does not take it.
        field.masked === true ? [fieldValue(field.value), ' ', el('span', {class: 'flag'}, 'masked'),
            field.note ? el('div', {class: 'small'}, field.note) : null] : fieldValue(field.value),
        field.sourceField ? code(field.sourceField) : el('span', {class: 'quiet small'}, 'no spelling of it in the answer')
    ]), {class: 'lookup-fields'});
}

/**
 * The value of one field. A value that begins or ends with a space, or holds two together, is
 * drawn with its spaces visible: for an identifier they are the difference between two formats.
 */
function fieldValue(value) {
    if (value && value.presence === 'VALUE' && /^\s|\s$|\s\s/.test(String(value.value))) {
        return spaced(value.value);
    }
    return shown(value);
}
