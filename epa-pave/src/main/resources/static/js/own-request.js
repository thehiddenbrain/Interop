// A request of the tester's own: a body pasted into a box and sent in place of the one this tool
// would build from the form.
//
// The text is the tester's. It is sent exactly as written: this page never trims it, never
// re-orders it and never repairs it. Whether it is valid JSON is said, not enforced, because a
// malformed body is a negative case a tester may mean to send; the send button then says so in
// its own words, so the choice is made at the button and not in a dialog. "Format" changes only
// the white space between the parts of valid JSON, so every value stays exactly as typed.
//
// A workbench switches this on with bench.useOwnRequest(...). Nothing typed here is kept beyond
// the page: no browser storage, nothing after a reload.

import {el, fill, clear, button, code} from './dom.js';
import {textArea} from './forms.js';
import {sampleBlock} from './vendor-sample.js';

let sequence = 0;

/**
 * spec.protocol     the protocol's name as the screen says it: "CRD"
 * spec.sendButton   the tab's send button, whose words change while the text is not valid JSON
 * spec.sendLabel    its words otherwise: "Send CRD request"
 * spec.isMock()     true on the mock profile
 * spec.changed()    called when the choice or the text changes
 * spec.modeChanged() called when the choice changes, and not for each key typed in the box
 * spec.say(text)     optional: the readout's status line, for the vendor's sample
 * spec.track(...)    optional: controls disabled while a request is on its way
 */
export function createOwnRequest(spec) {
    sequence += 1;
    const name = 'own-request-' + sequence;
    const builtChoice = el('input', {type: 'radio', name, id: name + '-built', value: 'built', checked: true});
    const ownChoice = el('input', {type: 'radio', name, id: name + '-own', value: 'own'});
    const choice = el('div', {class: 'field field--inline own__choice', role: 'radiogroup', aria: {labelledby: name + '-label'}},
        el('span', {class: 'field__label', id: name + '-label'}, 'What to send'),
        el('div', null,
            el('label', {class: 'choice', htmlFor: name + '-built'}, builtChoice, 'Built by this tool from the form'),
            el('label', {class: 'choice', htmlFor: name + '-own'}, ownChoice, 'A request of my own')));

    const box = textArea({
        label: 'The request body to send, exactly as written', field: 'rawBody', rows: 14,
        placeholder: 'Paste the JSON body here.'
    });
    const validity = el('p', {class: 'own__validity', role: 'status', aria: {live: 'polite'}});
    const formatSaid = el('span', {class: 'quiet small', role: 'status'});
    const format = button('Format', reformat, {class: 'button--quiet button--small'});
    const rules = el('ul', {class: 'note-list own__rules'});
    const panel = el('div', {class: 'own__panel', hidden: true},
        rules,
        box.node,
        el('div', {class: 'own__tools'}, validity, el('span', {class: 'own__format'}, format, formatSaid)));
    // The vendor's sample of the call is offered beside the choice on DTR and PAS. CRD offers it
    // with its comparison, in crd-compare.js, so nothing is added here for CRD.
    const sampleOperation = spec.protocol === 'DTR' ? 'DTR_QUESTIONNAIRE_PACKAGE' : spec.protocol === 'PAS' ? 'PAS_SUBMIT' : null;
    let sample = null;
    if (spec.protocol === 'DTR' || spec.protocol === 'PAS') {
        sample = sampleBlock({
            operation: sampleOperation,
            ownRequest: {
                active: () => ownChoice.checked,
                choose: () => ownChoice.click(),
                box: () => box.input,
                focus: () => box.input.focus()
            },
            say: text => { if (spec.say) { spec.say(text); } },
            track: spec.track
        });
    }
    const node = el('div', {class: 'case-group own'}, choice, sample ? sample.node : null, panel);

    builtChoice.addEventListener('change', drawMode);
    ownChoice.addEventListener('change', drawMode);
    box.input.addEventListener('input', () => {
        formatSaid.textContent = '';
        drawValidity();
        spec.changed();
    });

    function own() {
        return ownChoice.checked;
    }

    function drawRules() {
        fill(rules,
            el('li', null, el('strong', null, 'Sent exactly as written. '),
                'Nothing is added, removed or put in another order, and the text is not checked before it goes.'),
            el('li', null, 'Placeholders in double braces, such as ', code('{{baseUrl}}'),
                ', are filled in from the active environment, or from values typed for this run, before it is sent.'),
            el('li', null, 'The member, the provider and the codes on the form are not sent with it. They may be left empty, '
                + 'and what they hold is kept for when you go back to the body built by this tool.'),
            spec.isMock()
                ? el('li', null, 'On the mock profile the scenario is chosen by the Mock scenario field when one is chosen there, '
                    + 'otherwise by a test member ID found inside the text, such as ', code('P32H-NO-AUTH'),
                    '. With neither, the first scenario answers, and the result says so.')
                : null,
            el('li', null, 'The ledger records that a body of your own was sent, with the codes it finds in the body. It records no '
                + 'member ID, provider or line of business for the run, because those on the form were not sent. It does not keep '
                + 'the body, so the run cannot be replayed from the ledger alone.'));
    }

    function drawMode() {
        panel.hidden = !own();
        if (own()) {
            drawRules();
        }
        drawValidity();
        spec.changed();
        spec.modeChanged();
    }

    /** Says whether the text is valid JSON, and gives the send button the words that fit. */
    function drawValidity() {
        const text = box.value();
        const send = spec.sendButton;
        if (!own()) {
            send.textContent = spec.sendLabel;
            clear(validity);
            return;
        }
        if (text.trim() === '') {
            send.textContent = spec.sendLabel;
            fill(validity, 'Nothing pasted yet.');
            return;
        }
        const problem = jsonProblem(text);
        if (problem === null) {
            send.textContent = spec.sendLabel;
            fill(validity, el('strong', null, 'Valid JSON.'));
        } else {
            // The second confirmation is the button's own wording: pressing it is choosing to send invalid JSON.
            send.textContent = spec.sendLabel + ' anyway, as it is not valid JSON';
            fill(validity, el('strong', null, 'Not valid JSON as written. '), problem,
                /\{\{/.test(text) ? ' Placeholders are filled in before sending, which may change that.' : null);
        }
    }

    function reformat() {
        const text = box.value();
        if (jsonProblem(text) !== null) {
            formatSaid.textContent = 'Not formatted: the text is not valid JSON, and invalid text is never changed.';
            return;
        }
        box.set(indent(text));
        formatSaid.textContent = 'Formatted. Only the white space between the parts changed.';
        drawValidity();
        spec.changed();
    }

    return {
        node,
        /** True while "A request of my own" is chosen. */
        active: own,
        /** The text as it stands. */
        text: () => box.value(),
        /** What the run request carries for it: rawBody while chosen, nothing otherwise. */
        requestFields() {
            return own() ? {rawBody: box.value()} : {};
        },
        /** The field a problem about the body is marked on. */
        field: box.node,
        focus() {
            box.input.focus();
        },
        /** Redraws what depends on the profile, after the configuration changed. */
        refresh() {
            if (own()) {
                drawRules();
            }
            if (sample) {
                // A change of configuration can map, unmap or replace the collection request.
                sample.refresh();
            }
        }
    };
}

/** Null when the text is valid JSON, otherwise the browser's own sentence about where it is not. */
function jsonProblem(text) {
    try {
        JSON.parse(text);
        return null;
    } catch (failure) {
        return failure.message + '.';
    }
}

/**
 * Re-indents valid JSON by walking its characters. Parsing and writing it out again would not do:
 * that rewrites numbers such as 1.0 and 12345678901234567890, and keeps only the last of a
 * repeated key. Here only the white space between tokens changes; strings, numbers and every key
 * stay exactly as typed.
 */
export function indent(text) {
    const out = [];
    let depth = 0;
    let inString = false;
    let escaped = false;
    const newline = () => '\n' + '  '.repeat(depth);
    for (let index = 0; index < text.length; index += 1) {
        const ch = text[index];
        if (inString) {
            out.push(ch);
            if (escaped) {
                escaped = false;
            } else if (ch === '\\') {
                escaped = true;
            } else if (ch === '"') {
                inString = false;
            }
            continue;
        }
        if (ch === '"') {
            inString = true;
            out.push(ch);
        } else if (ch === '{' || ch === '[') {
            // An empty object or array stays on one line.
            const next = nextSignificant(text, index + 1);
            if (text[next] === (ch === '{' ? '}' : ']')) {
                out.push(ch, text[next]);
                index = next;
            } else {
                depth += 1;
                out.push(ch, newline());
            }
        } else if (ch === '}' || ch === ']') {
            depth -= 1;
            out.push(newline(), ch);
        } else if (ch === ',') {
            out.push(ch, newline());
        } else if (ch === ':') {
            out.push(': ');
        } else if (!/\s/.test(ch)) {
            out.push(ch);
        }
    }
    return out.join('');
}

function nextSignificant(text, from) {
    let index = from;
    while (index < text.length && /\s/.test(text[index])) {
        index += 1;
    }
    return index;
}
