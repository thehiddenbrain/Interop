// The vendor's sample: the body the collection request mapped to an operation carries, as the
// collection holds it, offered to be put in the box of a request of the tester's own. It is read
// from the server each time it is asked for; nothing of it is kept. A pre-request script on it is
// marked and never run.

import * as api from './api.js';
import {el, fill, clear, button} from './dom.js';

/**
 * The sample body of the request the active collection maps to an operation, as the collection
 * holds it. One of: {sample: {name, body, scripted, operation}}, {unmapped: {operation}},
 * {noBody: {name, operation}}, {refused: body} with the server's own refusal. Words are the
 * caller's: CRD compares the sample, DTR and PAS put it in the box to use.
 */
export async function readSample(operation) {
    const answer = await api.postJson('/api/config/resolve', {operation});
    if (answer.kind !== 'ok') {
        return {refused: answer.body};
    }
    const resolved = answer.body;
    const named = resolved.label || operation;
    if (!resolved.requestName) {
        return {unmapped: {operation: named}};
    }
    if (typeof resolved.bodyTemplate !== 'string' || resolved.bodyTemplate.trim() === '') {
        return {noBody: {name: resolved.requestName, operation: named}};
    }
    return {sample: {name: resolved.requestName, body: resolved.bodyTemplate, scripted: resolved.scripted, operation: named}};
}

/**
 * The block that says whether the mapped collection request has a sample body, with the button
 * that puts it in the box. ownRequest: {active(), choose(), box(), focus()}. say(text): the
 * readout's status line. track(...controls): disabled while a request is on its way.
 * Returns {node, refresh()}; refresh() reads the state again, after a change of configuration.
 */
export function sampleBlock({operation, ownRequest, say, track}) {
    let asked = 0;
    const state = el('p', {class: 'small', aria: {live: 'polite'}});
    const said = el('p', {class: 'small', role: 'status', aria: {live: 'polite'}});
    const put = button('Put the vendor\'s sample in the box', putInBox, {class: 'button--text'});
    const controls = el('div', {class: 'vendor-sample__controls', hidden: true}, put);
    const node = el('div', {class: 'vendor-sample'},
        el('p', {class: 'vendor-sample__title'}, 'The vendor\'s sample'), state, controls, said);
    if (track) {
        track(put);
    }

    function reason(found) {
        if (found.refused) {
            return [el('strong', null, 'The vendor\'s sample could not be read. '), found.refused.title, ' ', found.refused.action || ''];
        }
        if (found.unmapped) {
            // The hash route the page already follows; this module has no app object to navigate with.
            return ['No collection request is mapped to ', found.unmapped.operation, ', so there is no vendor\'s sample to use. ',
                'A collection is uploaded and mapped on the Config tab. ', el('a', {href: '#config'}, 'Open the Config tab')];
        }
        return ['The collection request ', el('strong', null, found.noBody.name), ', mapped to ', found.noBody.operation,
            ', has no sample body to use.'];
    }

    async function refresh() {
        asked += 1;
        const mine = asked;
        const found = await readSample(operation);
        if (mine !== asked) {
            // A later question is on its way; its answer is the one drawn.
            return;
        }
        controls.hidden = !found.sample;
        clear(said);
        fill(state, found.sample
            ? ['The collection request ', el('strong', null, found.sample.name), ' is mapped to ', found.sample.operation,
                ' and has a sample body.', found.sample.scripted ? ' It carries a pre-request script, which this tool does not run.' : null]
            : reason(found));
    }

    async function putInBox() {
        say('Reading the vendor\'s sample from the collection.');
        const found = await readSample(operation);
        if (!found.sample) {
            fill(said, reason(found));
            say('There is no vendor\'s sample to use.');
            return;
        }
        if (!ownRequest.active()) {
            // The box is where a request of the tester's own is written, so that is chosen, as the tester would choose it.
            ownRequest.choose();
        }
        const box = ownRequest.box();
        box.value = found.sample.body;
        // The box hears of it as it hears of typing, so it says again whether the text is valid JSON.
        box.dispatchEvent(new Event('input', {bubbles: true}));
        fill(said, 'The sample of the collection request ', el('strong', null, found.sample.name),
            ' is in the box, exactly as the collection holds it. Its placeholders are filled in when it is sent.');
        say('The vendor\'s sample is in the box.');
        ownRequest.focus();
    }

    refresh();
    return {node, refresh};
}
