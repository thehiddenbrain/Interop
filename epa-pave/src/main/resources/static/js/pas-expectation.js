// What the tester expects of a PAS call: an outcome, and optionally standard codes that must
// hold, sent as one text the server reads ("PARTIALLY_APPROVED; 97112: review-action=A3").
//
// The outcomes and the element names are the server's, fetched for each kind of call: a
// submission, an inquiry and a subscription call each have their own. Nothing here judges the
// text; the server refuses an expectation it cannot read and says which part.

import {el, fill, button, code} from './dom.js';
import {selectField, setOptions} from './forms.js';

let sequence = 0;

export function createExpectation() {
    let elements = [];
    const select = selectField({label: 'Expected outcome (optional)', field: 'expected'});
    const rows = el('div', {class: 'assertions'});
    const sentAs = el('p', {class: 'small'});
    const adder = el('div', {class: 'assertions__add'}, button('Add an assertion', () => addRow(), {class: 'button--text'}));
    const hint = el('p', {class: 'field__hint'},
        'Each asserts one standard code, such as review-action = A3 for one procedure code. Every one must hold for the run to pass.');
    const fold = el('details', {class: 'fold'}, el('summary', null, 'Assert standard codes as well'), el('div', {class: 'assertions'}, hint, rows, adder));
    const node = el('div', {class: 'pas-expectation'}, select.node, fold, sentAs);

    function elementOptions() {
        return [{value: '', label: 'element'}].concat(elements.map(name => ({value: name, label: name})));
    }

    function addRow() {
        sequence += 1;
        const subjectId = 'pas-assertion-for-' + sequence;
        const element = el('select', {aria: {label: 'Element'}});
        setOptions(element, elementOptions());
        const value = el('input', {type: 'text', class: 'literal-input', placeholder: 'code', autocomplete: 'off', spellcheck: false,
            aria: {label: 'The code expected in that element'}});
        const subject = el('input', {type: 'text', id: subjectId, class: 'literal-input', placeholder: 'every item', autocomplete: 'off', spellcheck: false});
        const row = el('div', {class: 'assertion field'},
            el('div', {class: 'assertion__what'}, element, el('span', {aria: {hidden: 'true'}}, '='), value),
            el('div', {class: 'assertion__for'},
                el('label', {htmlFor: subjectId}, 'Only for procedure code'), subject,
                button('Remove', () => { row.remove(); draw(); }, {class: 'button--text', aria: {label: 'Remove this assertion'}})));
        row.term = () => {
            if (element.value === '' && value.value.trim() === '' && subject.value.trim() === '') {
                return null;
            }
            return (subject.value.trim() === '' ? '' : subject.value.trim() + ': ') + element.value + '=' + value.value.trim();
        };
        row.element = element;
        rows.append(row);
        draw();
    }

    function text() {
        const terms = [];
        if (select.value() !== '') {
            terms.push(select.value());
        }
        for (const row of rows.querySelectorAll('.assertion')) {
            const term = row.term();
            if (term) {
                terms.push(term);
            }
        }
        return terms.join('; ');
    }

    function draw() {
        const sent = text();
        fill(sentAs, sent === '' ? null : ['Sent as ', code(sent)]);
    }

    node.addEventListener('input', draw);
    node.addEventListener('change', draw);
    addRow();

    return {
        node,
        text,
        /**
         * The outcomes and element names for the kind of call on screen. The choice is kept when the
         * new list still offers it. With no elements to assert on, the fold is not offered at all.
         */
        setVocabulary(outcomes, elementNames, noneLabel) {
            elements = elementNames || [];
            setOptions(select.select, [{value: '', label: noneLabel || 'No expectation: the run will be unverified'}]
                .concat((outcomes || []).map(option => ({value: option.code, label: option.label}))));
            for (const row of rows.querySelectorAll('.assertion')) {
                setOptions(row.element, elementOptions());
            }
            fold.hidden = elements.length === 0;
            draw();
        }
    };
}
