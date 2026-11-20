// The documents a tester adds to a submission: each with a title, a content type, its content
// inline or by url, and the type and category US Core asks a document to state.
//
// How a document travels is not chosen here. It follows the attachment mode the installation is
// configured with, and the mode changes what an attachment means: in one mode the document goes
// inside the bundle, in the other this tool only names it on the claim and sends nothing else.
// The mode is read from the configuration and said at the fields, before anything is sent.
//
// Each field carries the name the server gives it in a refusal (attachments[0].title), so a
// problem lands on the field of the attachment it is about.

import {el, fill, button, code} from './dom.js';
import {textField, textArea} from './forms.js';

export function createAttachments(spec) {
    const list = el('div', {class: 'pas-attachments__list', dataset: {field: 'attachments'}});
    const mode = el('div', {class: 'pas-attachments__mode', aria: {live: 'polite'}});
    const adder = button('Add an attachment', () => { add(); spec.changed(); }, {class: 'button--text'});
    const node = el('fieldset', {class: 'case-group pas-attachments'},
        el('legend', null, 'Attachments'),
        mode,
        list,
        el('p', null, adder));
    let modeValue = null;

    function add() {
        const index = list.children.length;
        const title = textField({label: 'Title', field: 'attachments[' + index + '].title', placeholder: 'what the document is'});
        const contentType = textField({label: 'Content type', field: 'attachments[' + index + '].contentType', literal: true,
            suggestions: ['text/plain', 'application/pdf', 'image/png', 'image/jpeg']});
        const name = 'pas-attachment-content-' + index + '-' + Date.now();
        const inline = el('input', {type: 'radio', name, value: 'text', checked: true, id: name + '-text'});
        const byUrl = el('input', {type: 'radio', name, value: 'url', id: name + '-url'});
        const text = textArea({label: 'The document as text', field: 'attachments[' + index + '].text', rows: 4});
        const url = textField({label: 'Where the document can be fetched', field: 'attachments[' + index + '].url', literal: true, wide: true,
            placeholder: 'https://'});
        const typeCode = textField({label: 'Document type code', field: 'attachments[' + index + '].type', literal: true, placeholder: 'such as a LOINC code'});
        const typeSystem = textField({label: 'Its system', field: 'attachments[' + index + '].type.system', literal: true, placeholder: 'http://loinc.org'});
        const categoryCode = textField({label: 'Category code', field: 'attachments[' + index + '].category', literal: true, placeholder: 'clinical-note'});
        const categorySystem = textField({label: 'Its system', field: 'attachments[' + index + '].category.system', literal: true});
        url.node.hidden = true;
        const showContent = () => {
            text.node.hidden = !inline.checked;
            url.node.hidden = inline.checked;
        };
        inline.addEventListener('change', showContent);
        byUrl.addEventListener('change', showContent);
        const remove = button('Remove this attachment', () => { item.remove(); renumber(); spec.changed(); },
            {class: 'button--text', aria: {label: 'Remove attachment ' + (index + 1)}});
        const heading = el('p', {class: 'pas-attachment__head'}, el('span', {class: 'sub-heading'}, 'Attachment ' + (index + 1)), remove);
        const item = el('div', {class: 'pas-attachment', dataset: {field: 'attachments[' + index + ']'}},
            heading,
            el('div', {class: 'field-grid'}, title.node, contentType.node),
            el('div', {class: 'field field--inline', role: 'radiogroup', aria: {label: 'How the content is given'}},
                el('label', {class: 'choice', htmlFor: inline.id}, inline, 'Inline text'),
                el('label', {class: 'choice', htmlFor: byUrl.id}, byUrl, 'By url')),
            text.node, url.node,
            el('div', {class: 'field-grid'}, typeCode.node, typeSystem.node, categoryCode.node, categorySystem.node));
        item.value = () => {
            const out = {title: title.value(), contentType: contentType.value()};
            if (inline.checked) {
                out.text = text.value();
            } else {
                out.url = url.value();
            }
            const coded = (codeControl, systemControl) => {
                if (codeControl.value() === '' && systemControl.value() === '') {
                    return undefined;
                }
                const value = {code: codeControl.value()};
                if (systemControl.value() !== '') {
                    value.system = systemControl.value();
                }
                return value;
            };
            out.type = coded(typeCode, typeSystem);
            out.category = coded(categoryCode, categorySystem);
            return out;
        };
        item.heading = heading;
        list.append(item);
        drawMode();
    }

    /** The names a refusal uses follow the order on screen, so they are given again when one is removed. */
    function renumber() {
        Array.from(list.children).forEach((item, index) => {
            item.dataset.field = 'attachments[' + index + ']';
            item.heading.querySelector('.sub-heading').textContent = 'Attachment ' + (index + 1);
            for (const field of item.querySelectorAll('[data-field]')) {
                field.dataset.field = field.dataset.field.replace(/^attachments\[\d+\]/, 'attachments[' + index + ']');
            }
        });
        drawMode();
    }

    function drawMode() {
        const count = list.children.length;
        if (modeValue === null) {
            fill(mode, el('p', {class: 'small quiet'}, 'The attachment mode could not be read from the configuration.'));
            return;
        }
        const x12 = modeValue === 'x12-275';
        fill(mode,
            el('p', {class: 'small'}, 'Attachment mode ', code(modeValue), ', from the setting ', code('onyx.pas.attachment-mode'), '.'),
            x12 ? el('p', {class: 'pas-x12'}, el('strong', null, 'In this mode no document is sent.'),
                ' The claim names each attachment and the document itself would travel in a separate X12 275 transaction, which this tool does not send. '
                + 'Show the request to read the server\'s note on it before sending.')
                : count > 0 ? el('p', {class: 'small quiet'}, 'In this mode each document travels inside the bundle, as a DocumentReference.') : null,
            count === 0 ? el('p', {class: 'small quiet'}, 'None added. A submission needs none.') : null);
    }

    drawMode();
    return {
        node,
        /** The documents as the request takes them. Blank fields are sent as typed, so the server can name what is missing. */
        values: () => Array.from(list.children).map(item => item.value()),
        count: () => list.children.length,
        /** The attachment mode from the configuration, or null when it could not be read. */
        setMode(value) {
            modeValue = value;
            drawMode();
        }
    };
}
