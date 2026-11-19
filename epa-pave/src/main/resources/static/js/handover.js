// The hand-over between steps of a case, and the tabs that receive one before they are built.
//
// When a result says the next step is DTR or PAS, the button on it goes to that tab carrying two
// things: the result's nextStep.carry, which the next run posts back unchanged as "carry", and
// the case on screen. The DTR and PAS tabs arrive in a later pass. Until then each shows, in
// words, exactly what it was handed, so the hand-over can be seen working and the next pass has
// it to build on.
//
// Seam for the next pass: app.handover(tabId) returns {carry, fromRunId, fromProtocol, chainId}
// or null. A real tab reads it in onShow and sends carry with its first run.

import {el, fill, code, facts, table, heading} from './dom.js';

/**
 * A protocol tab that is not built yet.
 *
 * spec: id, label, sentence (one plain statement that the tab arrives later).
 */
export function protocolPlaceholder(spec) {
    let app = null;
    let caseSlot = null;
    let readout = null;

    function draw() {
        const handed = app.handover(spec.id);
        const summary = app.caseContext.summary();
        const parts = [el('p', {class: 'plain-statement'}, spec.sentence)];
        if (!handed) {
            parts.push(el('p', {class: 'quiet'},
                'Nothing has been carried here yet. When a result names ' + spec.label + ' as the next step, its button brings the case and what the step needs to this tab.'));
        } else {
            parts.push(el('section', {class: 'panel'},
                heading(2, 'Carried here from ' + handed.fromProtocol + ' run'),
                facts([
                    ['From run', code(handed.fromRunId)],
                    ['Chain', handed.chainId ? code(handed.chainId) : el('span', {class: 'presence presence--none'}, 'not part of a chain')],
                    ['Member ID', summary.memberId ? code(summary.memberId) : el('span', {class: 'presence presence--none'}, 'none typed')],
                    ['NPI', summary.npi ? code(summary.npi) : el('span', {class: 'presence presence--none'}, 'none typed')],
                    ['Procedure codes', summary.codes.length > 0 ? summary.codes.map((value, index) => [index > 0 ? ', ' : null, code(value)])
                        : el('span', {class: 'presence presence--none'}, 'none typed')]
                ], 'facts--ruled'),
                el('p', null, 'The previous step handed on these values. The first run from this tab will send them back to the server unchanged:'),
                carryTable(handed.carry)));
        }
        fill(readout, parts);
    }

    return {
        id: spec.id,
        label: spec.label,
        mount(panel, application) {
            app = application;
            caseSlot = el('div', {class: 'case-slot'});
            readout = el('div', {class: 'readout', role: 'region', tabIndex: 0, aria: {label: 'What this tab was handed'}});
            panel.classList.add('tabpanel--workbench');
            fill(panel, el('div', {class: 'workbench'},
                el('div', {class: 'case-pane'}, el('div', {class: 'case-pane__scroll'}, caseSlot)), readout));
            app.caseContext.onChange(() => {
                if (!panel.hidden) {
                    draw();
                }
            });
        },
        onShow() {
            // The one case node moves to whichever protocol tab is on screen.
            caseSlot.append(app.caseContext.node, app.chain.control);
            draw();
        }
    };
}

function carryTable(carry) {
    const entries = Object.entries(carry || {});
    if (entries.length === 0) {
        return el('p', {class: 'quiet'}, 'Nothing: the previous step carried no values.');
    }
    return table(['Carried as', 'Value'], entries.map(([key, values]) => [
        code(key),
        el('ul', {class: 'plain-list'}, (Array.isArray(values) ? values : [values]).map(value => el('li', null, code(String(value)))))
    ]));
}
