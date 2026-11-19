// The hand-over between steps of a case, and the tabs that receive one before they are built.
//
// When a result says the next step is DTR or PAS, the button on it goes to that tab carrying two
// things: the result's nextStep.carry, which the next run posts back unchanged as "carry", and
// the case on screen. The DTR and PAS tabs arrive in a later pass. Until then each says so in one
// sentence and shows, in words, exactly what it was handed, so the hand-over can be seen working
// and the next pass has it to build on. There is no form here: a form that cannot send anything
// would be worse than none.
//
// Seam for the next pass: app.handover(tabId) returns {carry, fromRunId, fromProtocol, chainId}
// or null. A real tab reads it in onShow, sends carry with its first run, and places the shared
// case with caseSlot.append(app.caseContext.node, app.chain.control) as tab-crd.js does.

import {el, fill, code, facts, table, heading} from './dom.js';

/**
 * A protocol tab that is not built yet.
 *
 * spec: id, label, sentence (one plain statement that the tab arrives later).
 */
export function protocolPlaceholder(spec) {
    let app = null;
    let panel = null;

    function none(text) {
        return el('span', {class: 'presence presence--none'}, text);
    }

    function draw() {
        const handed = app.handover(spec.id);
        const summary = app.caseContext.summary();
        const parts = [el('p', {class: 'plain-statement'}, spec.sentence)];
        const theCase = [
            ['Member ID', summary.memberId ? code(summary.memberId) : none('none typed')],
            ['NPI', summary.npi ? code(summary.npi) : none('none typed')],
            ['Procedure codes', summary.codes.length > 0 ? summary.codes.map((value, index) => [index > 0 ? ', ' : null, code(value)]) : none('none typed')]
        ];
        if (!handed) {
            parts.push(el('section', {class: 'panel'},
                heading(2, 'Nothing has been carried here yet'),
                el('p', null, 'When a result names ' + spec.label + ' as the next step, its button brings the case and what the step needs to this tab.'),
                el('p', {class: 'quiet'}, 'The case typed on the CRD tab is the case this tab will use:'),
                facts(theCase, 'facts--ruled')));
        } else {
            parts.push(el('section', {class: 'panel'},
                heading(2, 'Carried here from the ' + handed.fromProtocol + ' result'),
                facts([
                    ['From run', code(handed.fromRunId)],
                    ['Chain', handed.chainId ? code(handed.chainId) : none('not part of a chain')]
                ].concat(theCase), 'facts--ruled'),
                el('p', null, 'The previous step handed on these values. The first run from this tab will send them back to the server unchanged:'),
                carryTable(handed.carry)));
        }
        fill(panel, el('div', {class: 'placeholder'}, parts));
    }

    return {
        id: spec.id,
        label: spec.label,
        mount(target, application) {
            app = application;
            panel = target;
        },
        onShow() {
            draw();
        }
    };
}

function carryTable(carry) {
    const entries = Object.entries(carry || {});
    if (entries.length === 0) {
        return el('p', {class: 'quiet'}, 'Nothing: the previous step carried no values.');
    }
    return table([{text: 'Carried as', class: 'col-30'}, 'Value'], entries.map(([key, values]) => [
        code(key),
        el('ul', {class: 'plain-list'}, (Array.isArray(values) ? values : [values]).map(value => el('li', null, code(String(value)))))
    ]));
}
