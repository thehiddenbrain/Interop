// The items of one questionnaire, drawn from the view the server built (ItemView), never from the
// Questionnaire JSON itself: what an item is, whether it is required, whether it repeats and
// whether the answers so far enable it are all the server's reading, shown as it sent them.
//
// Each question shows three things apart, because a tester has to see whose answer is whose:
//   the control       what the tester types for the next step. It starts empty: a value put there
//                     by this page would be indistinguishable from one the tester typed
//   in the response   what the QuestionnaireResponse now holds for it, with where each answer came
//                     from (typed by a person, pre-populated by the payer's service, or
//                     pre-populated and then changed by a person), as its information-origin
//                     extension states
//   the state         required and unanswered, not judged, asked now, new at this step, as the
//                     server reported them for the last step sent
//
// Whether an item is enabled is never worked out here. It is what the server said for the answers
// it last saw. Each item whose enablement depends on answers carries an empty .dtr-asof mark beside
// that state, which the questionnaire fills once the tester has typed answers the server has not
// seen, so the state reads at the item as what it is: the server's reading of earlier answers.

import {el, append, button, code} from './dom.js';
import {shown, shownList, presenceMark, hasValue} from './shown.js';

let sequence = 0;

// The question types the server turns plain text into an answer for. Any other type is answered
// with a FHIR answer object, which the server sends as written.
const TEXT_TYPES = new Set(['boolean', 'integer', 'decimal', 'date', 'dateTime', 'time', 'string', 'text', 'url', 'choice', 'open-choice']);

// Where an answer came from, in the words of the information-origin extension's three codes.
const ORIGIN_WORDS = {
    auto: 'pre-populated by the payer\'s service',
    override: 'pre-populated, then changed by a person',
    manual: 'typed by a person'
};

// Above this many options a list of radio buttons is longer than the question deserves, and a
// select is used instead. Both are fully usable from the keyboard.
const RADIO_LIMIT = 6;

/**
 * Draws a list of items.
 *
 * ctx.interactive   false for a questionnaire that cannot be answered: no controls are drawn
 * ctx.answers       Map linkId -> values the tester has given for the next step
 * ctx.onAnswer(linkId, values)   values null when the tester has given nothing for it
 * ctx.inResponse    Map linkId -> ResponseItemView of the response as it stands
 * ctx.required      Set of linkIds the last step reported as required and unanswered
 * ctx.undetermined  Map linkId -> QuestionRef the last step could not judge
 * ctx.asked         Set of linkIds the last step reported as asked now, or null when not known
 * ctx.appeared      Set of linkIds that appeared at the last step
 */
export function itemList(items, ctx) {
    if (!items || items.length === 0) {
        return el('p', {class: 'quiet'}, 'This questionnaire holds no items.');
    }
    return el('ol', {class: 'dtr-items'}, items.map(item => el('li', null, itemNode(item, ctx))));
}

function itemNode(item, ctx) {
    const type = item.type || {};
    if (type.code === 'display') {
        return displayItem(item);
    }
    if (!item.question) {
        return groupItem(item, ctx);
    }
    return questionItem(item, ctx);
}

/** The text of an item, with its prefix, as the payer worded it. */
function itemText(item) {
    const text = hasValue(item.text) ? String(item.text.value) : null;
    const prefix = hasValue(item.prefix) ? String(item.prefix.value) : null;
    if (!text) {
        return [prefix ? prefix + ' ' : null, el('span', {class: 'quiet'}, 'The item carries no text: '), presenceMark(item.text ? item.text.presence : 'ABSENT')];
    }
    return [prefix ? el('span', {class: 'dtr-item__prefix'}, prefix) : null, prefix ? ' ' : null, text];
}

function linkIdOf(item) {
    return hasValue(item.linkId) ? String(item.linkId.value) : null;
}

function displayItem(item) {
    return el('div', {class: 'dtr-item dtr-item--display' + enabledClass(item)},
        el('p', {class: 'dtr-item__display'}, itemText(item)),
        el('p', {class: 'dtr-item__meta'}, 'Display text, which asks nothing. ', idMeta(item), enabledLine(item)),
        conditions(item), extensions(item));
}

function groupItem(item, ctx) {
    const type = item.type || {};
    return el('div', {class: 'dtr-item dtr-item--group' + enabledClass(item)},
        el('p', {class: 'dtr-item__group-title'}, itemText(item)),
        el('p', {class: 'dtr-item__meta'},
            type.code === 'group' ? 'Group. ' : ['Holds other items, and its type is ', typeWords(type), '. '],
            idMeta(item), repeatsWords(item), enabledLine(item)),
        undeterminedLine(item, ctx),
        conditions(item), extensions(item),
        itemList(item.items, ctx));
}

function enabledClass(item) {
    return item.enabled === 'DISABLED' ? ' dtr-item--disabled' : item.enabled === 'UNDETERMINED' ? ' dtr-item--undetermined' : '';
}

function idMeta(item) {
    const linkId = linkIdOf(item);
    return [linkId ? code(linkId) : ['linkId ', presenceMark(item.linkId ? item.linkId.presence : 'ABSENT')], ' at ', code(item.path), '. '];
}

function typeWords(type) {
    if (!type || type.presence !== 'VALUE') {
        return [presenceMark(type ? type.presence || 'ABSENT' : 'ABSENT')];
    }
    if (type.recognised === false) {
        return [code(type.code), ' ', el('span', {class: 'flag'}, 'not a type this tool knows')];
    }
    return [type.display || type.code, ' (', code(type.code), ')'];
}

function repeatsWords(item) {
    return hasValue(item.repeats) && String(item.repeats.value) === 'true' ? 'Repeats. ' : null;
}

/** Whether the answers the server last saw enable the item, in its own words, with the place for saying when that was. */
function enabledLine(item) {
    const depends = item.enabled === 'DISABLED' || item.enabled === 'UNDETERMINED' || Boolean(item.enabledReason) || (item.enableWhen || []).length > 0;
    const asOf = depends ? [' ', el('span', {class: 'dtr-asof'})] : null;
    if (item.enabled === 'DISABLED') {
        return [el('span', {class: 'dtr-state dtr-state--disabled'}, item.enabledReason || 'Not enabled by the answers the server last saw.'), asOf];
    }
    if (item.enabled === 'UNDETERMINED') {
        return [el('span', {class: 'dtr-state dtr-state--undetermined'}, item.enabledReason || 'Whether it is enabled could not be judged.'), asOf];
    }
    return [item.enabledReason ? el('span', {class: 'quiet'}, item.enabledReason) : null, asOf];
}

function undeterminedLine(item, ctx) {
    const linkId = linkIdOf(item);
    const said = linkId && ctx.undetermined ? ctx.undetermined.get(linkId) : null;
    if (!said) {
        return null;
    }
    return el('p', {class: 'dtr-mark dtr-mark--undetermined'}, el('strong', null, 'Not judged. '), said.note || 'A rule this tool does not apply decides whether it still needs an answer.');
}

/** Every enableWhen condition with what became of it, because that is the behaviour under test. */
function conditions(item) {
    const list = item.enableWhen || [];
    if (list.length === 0) {
        return null;
    }
    const behaviour = item.enableBehavior && item.enableBehavior.presence === 'VALUE'
        ? ['enableBehavior ', code(item.enableBehavior.code), item.enableBehavior.display ? ' (' + item.enableBehavior.display + ')' : null, '. ']
        : list.length > 1 ? ['enableBehavior ', presenceMark(item.enableBehavior ? item.enableBehavior.presence : 'ABSENT'), '. '] : null;
    return el('details', {class: 'fold dtr-conditions'},
        el('summary', null, 'Conditions', el('span', {class: 'quiet'}, list.length + (list.length === 1 ? ' enableWhen' : ' enableWhen conditions'))),
        behaviour ? el('p', {class: 'small'}, behaviour) : null,
        el('table', {class: 'grid dtr-conditions__table'},
            el('thead', null, el('tr', null, ['Question', 'Operator', 'Answer', 'Evaluated', 'Why'].map(text => el('th', {scope: 'col'}, text)))),
            el('tbody', null, list.map(condition => el('tr', null,
                el('td', null, shown(condition.question, {as: 'literal'})),
                el('td', null, condition.operator && condition.operator.presence === 'VALUE'
                    ? [code(condition.operator.code), condition.operator.recognised === false ? [' ', el('span', {class: 'flag'}, 'not an operator this tool knows')] : null]
                    : presenceMark(condition.operator ? condition.operator.presence : 'ABSENT')),
                el('td', null, shown(condition.answer, {as: 'literal'})),
                // The state in the server's words, with the code it sent beside them.
                el('td', null, el('span', {class: 'dtr-condition dtr-condition--' + String(condition.state).toLowerCase()},
                    condition.stateLabel ? [condition.stateLabel, ' '] : null, code(String(condition.state)))),
                el('td', null, condition.explanation || null))))));
}

function extensions(item) {
    const list = item.extensions || [];
    if (list.length === 0) {
        return null;
    }
    return el('details', {class: 'fold'},
        el('summary', null, 'Extensions on this item', el('span', {class: 'quiet'}, String(list.length))),
        el('table', {class: 'grid pairs'},
            el('thead', null, el('tr', null, el('th', {scope: 'col'}, 'Url'), el('th', {scope: 'col'}, 'Value'))),
            el('tbody', null, list.map(extension => el('tr', null,
                el('td', null, shown(extension.url, {as: 'literal'})),
                el('td', null, shown(extension.value, {as: 'literal'})))))));
}

/* ---------- a question ---------- */

function questionItem(item, ctx) {
    const linkId = linkIdOf(item);
    const type = item.type || {};
    const required = hasValue(item.required) && String(item.required.value) === 'true';
    const marks = [];
    if (linkId && ctx.required && ctx.required.has(linkId)) {
        marks.push(el('p', {class: 'dtr-mark dtr-mark--required'}, el('strong', null, 'Required, and it has no answer. '),
            'The last step sent named it among the required questions that are unanswered.'));
    }
    const node = el('div', {
        class: 'dtr-item dtr-question' + enabledClass(item) + (marks.length > 0 ? ' dtr-question--unanswered' : ''),
        dataset: {field: linkId ? 'dtr-answer:' + linkId : null, linkId}
    });
    const flags = [];
    if (linkId && ctx.asked && ctx.asked.has(linkId)) {
        flags.push(el('span', {class: 'dtr-flag dtr-flag--asked'}, 'asked now'));
    }
    if (linkId && ctx.appeared && ctx.appeared.has(linkId)) {
        flags.push(el('span', {class: 'dtr-flag dtr-flag--new'}, 'new at the last step'));
    }
    const labelId = 'dtr-q-' + (++sequence);
    // The control is built first, so that a question answered in one input has its text as that
    // input's real label. A question answered by a group of radio buttons or checkboxes names the
    // group instead, and each option has a label of its own.
    const answer = ctx.interactive && linkId ? control(item, linkId, labelId, ctx) : null;
    const textAttrs = {class: 'dtr-question__text', id: labelId};
    if (answer && answer.inputId) {
        textAttrs.htmlFor = answer.inputId;
    }
    append(node,
        el(answer && answer.inputId ? 'label' : 'p', textAttrs, itemText(item), flags.length > 0 ? [' ', flags] : null),
        el('p', {class: 'dtr-item__meta'}, idMeta(item), typeWords(type), '. ',
            required ? el('strong', null, 'Required. ')
                : hasValue(item.required) ? 'Optional. '
                    : ['Required ', presenceMark(item.required ? item.required.presence : 'ABSENT'), '. '],
            repeatsWords(item),
            hasValue(item.readOnly) && String(item.readOnly.value) === 'true' ? 'Read only. ' : null,
            enabledLine(item)),
        marks,
        undeterminedLine(item, ctx));
    append(node, responseLine(linkId, item, ctx));
    if (answer) {
        append(node, answer.node);
    } else if (ctx.interactive) {
        append(node, el('p', {class: 'quiet small'}, 'It has no linkId, so an answer to it cannot be sent.'));
    }
    append(node, optionsList(item), stated(item), conditions(item), extensions(item));
    return node;
}

/** The value set and the initial values the questionnaire states for the question, when it states any. */
function stated(item) {
    const parts = [];
    if (item.answerValueSet && item.answerValueSet.presence !== 'ABSENT') {
        parts.push(el('p', {class: 'small'}, 'Answers from the value set ', shown(item.answerValueSet, {as: 'literal'}),
            (item.answerOptions || []).length === 0 ? '. This tool does not expand a value set, so it lists no options for it.' : '.'));
    }
    if ((item.initial || []).length > 0) {
        parts.push(el('p', {class: 'small'}, 'Initial value stated by the questionnaire: ', shownList(item.initial, {as: 'literal'}),
            '. It is not an answer until it is given as one.'));
    }
    return parts;
}

/** The options a choice question offers, each with the value exactly as returned, for a question that is not answered here. */
function optionsList(item) {
    const options = item.answerOptions || [];
    if (options.length === 0) {
        return null;
    }
    return el('details', {class: 'fold'},
        el('summary', null, 'Options as returned', el('span', {class: 'quiet'}, String(options.length))),
        el('table', {class: 'grid'},
            el('thead', null, el('tr', null, ['Shown as', 'Sent as', 'Value as returned', 'Selected to begin with'].map(text => el('th', {scope: 'col'}, text)))),
            el('tbody', null, options.map(option => el('tr', null,
                el('td', null, option.label || el('span', {class: 'quiet small'}, 'no value')),
                el('td', null, option.answerAs ? code(option.answerAs) : el('span', {class: 'quiet small'}, 'cannot be chosen')),
                el('td', null, shown(option.value, {as: 'literal'})),
                el('td', null, shown(option.initialSelected, {as: 'literal'})))))));
}

/** What the response holds for the question now, answer by answer, with whose answer each is. */
function responseLine(linkId, item, ctx) {
    const held = linkId && ctx.inResponse ? ctx.inResponse.get(linkId) : null;
    const answers = held ? held.answers || [] : [];
    if (answers.length === 0) {
        const prepopulated = item.prepopulated || [];
        if (prepopulated.length > 0 && !held) {
            // The package said it arrived with these, and no response with origins is on screen to say whose they are.
            return el('div', {class: 'dtr-held'}, el('span', {class: 'dtr-held__label'}, 'Arrived with the package: '),
                shownList(prepopulated), ' ', el('span', {class: 'quiet small'}, 'where they came from is not stated here'));
        }
        return el('p', {class: 'dtr-held dtr-held--none'}, el('span', {class: 'dtr-held__label'}, 'In the response: '),
            el('span', {class: 'presence presence--none'}, 'no answer'));
    }
    const origins = held.origins || [];
    return el('div', {class: 'dtr-held'},
        el('span', {class: 'dtr-held__label'}, answers.length === 1 ? 'In the response: ' : 'In the response (' + answers.length + ' answers):'),
        el('ul', {class: 'plain-list dtr-held__list'}, answers.map((answer, index) => el('li', null,
            shown(answer), ' ', originMark(origins[index])))),
        (held.answerExtensions || []).length > 0
            ? el('p', {class: 'small quiet'}, 'Other extensions on these answers: ',
                held.answerExtensions.map((extension, index) => [index > 0 ? '; ' : null, shown(extension.url, {as: 'literal'}), ' = ', shown(extension.value, {as: 'literal'})]))
            : null);
}

/** Whose answer it is, as the information-origin extension states, with the code itself. */
export function originMark(origin) {
    if (!origin || !hasValue(origin.source)) {
        return el('span', {class: 'dtr-origin dtr-origin--none'}, 'where it came from is not stated',
            origin && origin.source && origin.source.presence !== 'ABSENT' ? [' (source ', presenceMark(origin.source.presence), ')'] : null);
    }
    const source = String(origin.source.value);
    const words = Object.prototype.hasOwnProperty.call(ORIGIN_WORDS, source) ? ORIGIN_WORDS[source] : 'a source this tool does not know';
    return el('span', {class: 'dtr-origin dtr-origin--' + (Object.prototype.hasOwnProperty.call(ORIGIN_WORDS, source) ? source : 'other')},
        words, ' (', code(source), ')',
        hasValue(origin.author) ? [', author ', code(String(origin.author.value))] : null,
        (origin.roles || []).length > 0 ? [', role ', shownList(origin.roles, {as: 'literal'})] : null);
}

/* ---------- the control for the next step ---------- */

function control(item, linkId, labelId, ctx) {
    const type = item.type || {};
    const repeats = hasValue(item.repeats) && String(item.repeats.value) === 'true';
    const given = ctx.answers.get(linkId) || null;
    const held = ctx.inResponse ? ctx.inResponse.get(linkId) : null;
    const hasHeld = Boolean(held && (held.answers || []).length > 0);
    const say = el('p', {class: 'dtr-given', role: 'status'});
    const box = el('div', {class: 'dtr-control'});
    let clearTyped = null;
    const report = values => {
        ctx.onAnswer(linkId, values);
        drawGiven(values);
    };
    const drawGiven = values => {
        if (clearTyped) {
            clearTyped.hidden = values === null;
        }
        if (values === null) {
            say.textContent = '';
        } else if (values.length === 0) {
            // A removal stays with the questionnaire: a response step starts again from the package,
            // whose pre-populated answer would otherwise come back, so it is sent at every step.
            say.textContent = hasHeld ? 'You are removing this answer from the response. The next step sends this question with no answer.'
                : 'Removed: each step sends this question with no answer, so the response holds none. Type an answer to give one again.';
        } else {
            say.textContent = hasHeld ? 'Your answer replaces what the response holds when the next step is sent.' : 'Your answer goes with the next step.';
        }
    };

    let built;
    const options = (item.answerOptions || []).filter(option => option.answerAs);
    if (!TEXT_TYPES.has(type.code) || type.recognised === false) {
        built = type.code === 'quantity' ? quantityControl(labelId, given, report) : objectControl(type, labelId, given, report);
    } else if (type.code === 'boolean') {
        built = booleanControl(labelId, given, report);
    } else if ((type.code === 'choice' || type.code === 'open-choice') && options.length === 0) {
        // Plain text is refused for a choice whose options come only from a value set; an open
        // choice still takes text. Either way the reason is said at the control.
        built = type.code === 'open-choice'
            ? textControl(item, labelId, given, report, repeats, 'An answer of your own. The options come from a value set this tool does not expand.')
            : objectControl(type, labelId, given, report, 'Its options come from a value set, which this tool does not expand, so the answer is given as a FHIR answer object, such as {"valueCoding": {"system": "...", "code": "..."}}. It is sent as written.');
    } else if (type.code === 'choice' || type.code === 'open-choice') {
        built = choiceControl(options, labelId, given, report, repeats, type.code === 'open-choice');
    } else {
        built = textControl(item, labelId, given, report, repeats, null);
    }
    clearTyped = button('Clear what I typed', () => { built.reset(); report(null); },
        {class: 'button--text button--small', aria: {label: 'Clear what I typed for ' + linkId}});
    box.append(el('div', {class: 'dtr-control__head'},
        built.inputId ? el('label', {class: 'dtr-control__label', htmlFor: built.inputId}, 'Your answer for the next step')
            : el('span', {class: 'dtr-control__label'}, 'Your answer for the next step'),
        hasHeld ? button('Remove this answer from the response', () => { built.reset(); report([]); },
            {class: 'button--text button--small', aria: {label: 'Remove this answer from the response, ' + linkId}}) : null,
        clearTyped),
    built.node, say);
    drawGiven(given);
    return {node: box, inputId: built.inputId || null};
}

function booleanControl(labelId, given, report) {
    const name = 'dtr-bool-' + (++sequence);
    const choices = [['true', 'Yes', 'true'], ['false', 'No', 'false']].map(([value, words, literal]) => {
        const input = el('input', {type: 'radio', name, value, id: name + '-' + value, checked: Boolean(given && given[0] === value)});
        input.addEventListener('change', () => report([value]));
        return el('label', {class: 'choice', htmlFor: name + '-' + value}, input, words, ' ', el('span', {class: 'quiet small'}, '(sent as ', code(literal), ')'));
    });
    return {
        node: el('div', {class: 'dtr-choices', role: 'radiogroup', aria: {labelledby: labelId}}, choices),
        reset: () => choices.forEach(label => { label.querySelector('input').checked = false; })
    };
}

function choiceControl(options, labelId, given, report, repeats, open) {
    const chosen = new Set((given || []).filter(value => typeof value === 'string'));
    const ownText = (given || []).find(value => typeof value === 'string' && !options.some(option => option.answerAs === value)) || '';
    const other = open ? el('input', {type: 'text', class: 'dtr-other', autocomplete: 'off', spellcheck: false, value: ownText,
        aria: {label: 'An answer of your own, not one of the options'}}) : null;
    const collect = picked => {
        const values = picked.slice();
        if (other && other.value.trim() !== '') {
            values.push(other.value);
        }
        report(values.length > 0 ? values : null);
    };
    let node;
    let reset;
    let picked;
    if (!repeats && options.length > RADIO_LIMIT) {
        const selectId = 'dtr-in-' + (++sequence);
        const select = el('select', {id: selectId},
            el('option', {value: ''}, 'No option chosen'),
            options.map(option => el('option', {value: option.answerAs, selected: chosen.has(option.answerAs)}, option.label + '  [' + option.answerAs + ']')));
        picked = () => (select.value === '' ? [] : [select.value]);
        select.addEventListener('change', () => collect(picked()));
        node = el('div', {class: 'dtr-choices'}, select);
        node.inputId = selectId;
        reset = () => { select.value = ''; };
    } else {
        const name = 'dtr-choice-' + (++sequence);
        const inputs = options.map((option, index) => el('input', {
            type: repeats ? 'checkbox' : 'radio', name, value: option.answerAs, id: name + '-' + index, checked: chosen.has(option.answerAs)
        }));
        picked = () => inputs.filter(input => input.checked).map(input => input.value);
        inputs.forEach(input => input.addEventListener('change', () => collect(picked())));
        node = el('div', {class: 'dtr-choices' + (options.length > RADIO_LIMIT ? ' dtr-choices--many' : ''), role: repeats ? 'group' : 'radiogroup', aria: {labelledby: labelId}},
            options.map((option, index) => el('label', {class: 'choice', htmlFor: name + '-' + index}, inputs[index], option.label,
                ' ', el('span', {class: 'quiet small'}, '(', code(option.answerAs), ')'))));
        reset = () => inputs.forEach(input => { input.checked = false; });
    }
    if (other) {
        other.addEventListener('input', () => collect(picked()));
        node.append(el('label', {class: 'dtr-other__label'}, 'Or an answer of your own ', other));
    }
    return {node, inputId: node.inputId || null, reset: () => { reset(); if (other) { other.value = ''; } }};
}

// What the server accepts as plain text for each type, said where it is typed.
const TEXT_HINTS = {
    integer: 'A whole number.',
    decimal: 'A number.',
    date: 'A date as YYYY-MM-DD.',
    dateTime: 'A date and time, for example 2026-01-31T09:30:00-05:00.',
    time: 'A time of day, for example 09:30.',
    url: 'A url.'
};

function textControl(item, labelId, given, report, repeats, hint) {
    const type = (item.type || {}).code;
    const long = type === 'text' || repeats;
    const start = (given || []).filter(value => typeof value === 'string');
    const inputId = 'dtr-in-' + (++sequence);
    const input = long
        ? el('textarea', {id: inputId, rows: repeats ? 3 : 4, spellcheck: false, autocomplete: 'off'})
        : el('input', {id: inputId, type: 'text', autocomplete: 'off', spellcheck: false,
            inputMode: type === 'integer' ? 'numeric' : type === 'decimal' ? 'decimal' : null,
            placeholder: type === 'date' ? 'YYYY-MM-DD' : type === 'time' ? 'HH:MM' : null});
    input.value = repeats ? start.join('\n') : start[0] || '';
    input.addEventListener('input', () => {
        if (repeats) {
            const lines = input.value.split('\n').filter(line => line.trim() !== '');
            report(lines.length > 0 ? lines : null);
        } else {
            report(input.value.trim() === '' ? null : [input.value]);
        }
    });
    const words = [hint || TEXT_HINTS[type] || null, repeats ? 'It repeats: one answer on each line.' : null].filter(Boolean).join(' ');
    const hintId = words ? 'dtr-hint-' + (++sequence) : null;
    if (hintId) {
        input.setAttribute('aria-describedby', hintId);
    }
    return {
        node: el('div', {class: 'dtr-text'}, input, words ? el('span', {class: 'field__hint', id: hintId}, words) : null),
        inputId,
        reset: () => { input.value = ''; }
    };
}

/**
 * A quantity: a number with its unit. The server takes a quantity only as a FHIR answer object,
 * so the two are put into one, exactly as typed, and the object is shown before it is sent.
 */
function quantityControl(labelId, given, report) {
    const first = given && given[0] && typeof given[0] === 'object' && given[0].valueQuantity ? given[0].valueQuantity : {};
    const value = el('input', {type: 'text', inputMode: 'decimal', autocomplete: 'off', spellcheck: false, aria: {label: 'The number'}});
    const unit = el('input', {type: 'text', autocomplete: 'off', spellcheck: false, aria: {label: 'The unit'}});
    value.value = first.value === undefined ? '' : String(first.value);
    unit.value = first.unit === undefined ? '' : String(first.unit);
    const sent = el('p', {class: 'small'});
    const draw = () => {
        const number = value.value.trim();
        if (number === '' && unit.value.trim() === '') {
            sent.textContent = '';
            report(null);
            return;
        }
        // A number that reads as one goes as a JSON number, anything else as the text typed, for
        // the server to accept or refuse in its own words.
        const quantity = {value: /^-?\d+(\.\d+)?$/.test(number) ? Number(number) : number};
        if (unit.value.trim() !== '') {
            quantity.unit = unit.value.trim();
        }
        const answer = {valueQuantity: quantity};
        sent.replaceChildren('Sent as ', code(JSON.stringify(answer)));
        report([answer]);
    };
    value.addEventListener('input', draw);
    unit.addEventListener('input', draw);
    return {
        node: el('div', {class: 'dtr-quantity', role: 'group', aria: {labelledby: labelId}},
            el('label', null, 'Number ', value), el('label', null, 'Unit ', unit),
            el('span', {class: 'field__hint'}, 'A quantity is sent as a FHIR answer object, with the number and the unit as typed.'), sent),
        reset: () => { value.value = ''; unit.value = ''; sent.textContent = ''; }
    };
}

/**
 * An answer of a type this tool cannot turn plain text into: the tester writes the FHIR answer
 * object, and it is sent as written. The text is read as JSON here only to post it as an object;
 * nothing in it is changed.
 */
function objectControl(type, labelId, given, report, why) {
    const start = given && given[0] && typeof given[0] === 'object' ? JSON.stringify(given[0]) : '';
    const inputId = 'dtr-in-' + (++sequence);
    const input = el('textarea', {id: inputId, rows: 3, class: 'literal-area', wrap: 'off', spellcheck: false, autocomplete: 'off',
        placeholder: '{"valueString": "..."}'});
    input.value = start;
    const state = el('p', {class: 'small', role: 'status'});
    input.addEventListener('input', () => {
        const text = input.value.trim();
        if (text === '') {
            state.textContent = '';
            report(null);
            return;
        }
        let parsed;
        try {
            parsed = JSON.parse(text);
        } catch (notJson) {
            state.textContent = 'Not valid JSON yet, so nothing of it goes with the next step.';
            report(null);
            return;
        }
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
            state.textContent = 'An answer object is a JSON object, such as {"valueString": "..."}. Nothing of this goes with the next step.';
            report(null);
            return;
        }
        state.textContent = 'Valid JSON. It is sent as written.';
        report([parsed]);
    });
    const reason = why || [type && type.presence === 'VALUE'
        ? (type.recognised === false ? 'This tool does not know the type ' + type.code + '.' : 'This tool cannot turn plain text into an answer of the type ' + type.code + '.')
        : 'The question states no type.', ' The server takes it as a FHIR answer object, which is sent as written.'].join('');
    return {
        node: el('div', {class: 'dtr-object'}, el('span', {class: 'field__hint'}, reason), input, state),
        inputId,
        reset: () => { input.value = ''; state.textContent = ''; }
    };
}

/** The answers a response holds, by linkId, from its items. */
export function responseByLinkId(response) {
    const map = new Map();
    const walk = list => {
        for (const item of list || []) {
            if (hasValue(item.linkId)) {
                map.set(String(item.linkId.value), item);
            }
            walk(item.items);
        }
    };
    if (response) {
        walk(response.items);
    }
    return map;
}

