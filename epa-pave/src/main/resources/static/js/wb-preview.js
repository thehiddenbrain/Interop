// The request preview of every "Preview" on the Workbench: what a run would send, built by the
// server exactly as the run would build it, with nothing sent and nothing recorded.

import {call} from './api.js';
import {el, chip, kv, button, modal} from './dom.js';
import {errorPanel} from './fault.js';
import {jsonView} from './json-view.js';
import {withRunValues} from './wb-values.js';

/**
 * Asks the server for the preview of a request and shows it in a dialog. path: the preview
 * endpoint ('/api/crd/preview'); body: what the run would post. onRefused(error): a refusal of the
 * form, which the caller places at its fields. labelOf(field): the label a tester sees for a field
 * of a refusal, so the panel never names a property path. Returns the answer of api.js.
 */
export async function showPreview(path, body, onRefused, labelOf) {
    // The preview builds what the run would send, so it takes the values typed for this run too.
    const answer = await call('POST', path, {body: withRunValues(body)});
    if (!answer.ok) {
        if (answer.status === 422 && typeof onRefused === 'function') {
            onRefused(answer.error);
        }
        modal({
            title: 'Request preview',
            body: errorPanel(answer.error, {labelOf: labelOf || null}),
            footer: dialog => [button('Close preview', () => dialog.close())]
        });
        return answer;
    }
    modal({
        title: 'Request preview',
        body: previewBody(answer.data),
        footer: dialog => [el('p', {class: 'small muted'}, 'Nothing is sent and nothing is recorded.'), button('Close preview', () => dialog.close())]
    });
    return answer;
}

/** The parts of a preview: method and url, where it comes from, values with none, the body with Copy, notes, gaps. */
export function previewBody(preview) {
    const url = preview.url || {};
    const unresolved = Array.isArray(url.unresolved) ? url.unresolved : [];
    const variables = Array.isArray(url.variables) ? url.variables : [];
    const notes = Array.isArray(preview.notes) ? preview.notes : [];
    const gaps = Array.isArray(preview.toComplete) ? preview.toComplete : [];
    return el('div', {class: 'preview'},
        preview.blocked ? el('p', {class: 'masked-reading'}, preview.blocked) : null,
        kv([
            {key: 'Request', value: el('span', {class: 'mono'}, (preview.method || '') + ' ' + (url.display || url.template || ''))},
            {key: 'As configured', value: url.template ? el('span', {class: 'mono'}, url.template) : chip('none', {dashed: true})},
            url.source ? {key: 'From', value: url.source} : null,
            preview.memberId && preview.memberId.sent ? {key: 'Member ID sent', value: el('span', {class: 'mono'}, preview.memberId.sent)} : null
        ]),
        variables.length > 0 ? kv(variables.map(variable => ({
            key: el('span', {class: 'mono'}, variable.name),
            value: variable.display ? el('span', {class: 'mono'}, variable.display) : chip('no value', {dashed: true})
        }))) : null,
        unresolved.length > 0 ? el('p', {class: 'small'}, 'No value: ', unresolved.map((name, index) => [index > 0 ? ', ' : null,
            el('span', {class: 'mono'}, name)])) : null,
        gaps.length > 0 ? el('div', null, el('p', {class: 'notes__title'}, 'To complete before sending (' + gaps.length + ')'),
            el('ul', {class: 'notes'}, gaps.map(gap => el('li', null, gap.line)))) : null,
        preview.body !== undefined && preview.body !== null ? jsonView(preview.body, {label: 'Request body', copyLabel: 'Copy request'}) : null,
        notes.length > 0 ? el('ul', {class: 'notes small'}, notes.map(line => el('li', null, line))) : null);
}
