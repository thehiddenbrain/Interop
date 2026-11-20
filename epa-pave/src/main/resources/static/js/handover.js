// The hand-over between steps of a case, and the tabs that receive one before they are built.
//
// When a result says the next step is DTR or PAS, the button on it goes to that tab carrying two
// things: the result's nextStep.carry, which the next run posts back unchanged as "carry", and
// the case on screen. The DTR and PAS tabs arrive in a later pass. Until then each says so in one
// sentence and shows, in words, exactly what it was handed, so the hand-over can be seen working
// and the next pass has it to build on. There is no form here: a form that cannot send anything
// would be worse than none.
//
// Seam for the next pass: app.carriedTo(tabId) returns {carry, fromRunId, fromProtocol, chainId,
// forCase} or null. forCase is the member ID, NPI and codes the run was sent with. A real tab is
// built on createWorkbench as tab-crd.js is: it reads what was carried in onShow, places the
// shared case with bench.showCase(), and sends carry with its first run only while the case on
// screen is still forCase. Values carried for one member must not go out with another:
// sameCase(forCase, app.caseContext.summary()) is the test of that, for every tab.

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

    function caseRows(summary) {
        return [
            ['Member ID', summary.memberId ? code(summary.memberId) : none('none typed')],
            ['NPI', summary.npi ? code(summary.npi) : none('none typed')],
            ['Procedure codes', summary.codes.length > 0 ? summary.codes.map((value, index) => [index > 0 ? ', ' : null, code(value)]) : none('none typed')]
        ];
    }

    function draw() {
        const handed = app.carriedTo(spec.id);
        const onScreen = app.caseContext.summary();
        const parts = [el('p', {class: 'plain-statement'}, spec.sentence)];
        if (!handed) {
            parts.push(el('section', {class: 'panel'},
                heading(2, 'Nothing has been carried here yet'),
                el('p', null, 'When a result names ' + spec.label + ' as the next step, its button brings the case and what the step needs to this tab.'),
                el('p', {class: 'quiet'}, 'The case typed on the CRD tab is the case this tab will use:'),
                facts(caseRows(onScreen), 'facts--ruled')));
        } else {
            const sentWith = handed.forCase || onScreen;
            parts.push(el('section', {class: 'panel'},
                heading(2, 'Carried here from the ' + handed.fromProtocol + ' result'),
                el('p', {class: 'quiet'}, 'The run it came from, and the case that run was sent with:'),
                facts([
                    ['From run', code(handed.fromRunId)],
                    ['Chain', handed.chainId ? code(handed.chainId) : none('not part of a chain')]
                ].concat(caseRows(sentWith)), 'facts--ruled'),
                // The values below belong to the case of that run. A tester who has typed another
                // case since must see that before anything is sent with them.
                sameCase(sentWith, onScreen) ? null : [
                    el('p', null, el('strong', null, 'The case on screen has changed since that run.'),
                        ' What was carried belongs to the case above, not to the one on screen now:'),
                    facts(caseRows(onScreen), 'facts--ruled')
                ],
                el('p', null, 'The values the previous step handed on, which the next step posts back to the server unchanged:'),
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

/**
 * True when two cases are the same member, provider and codes. one and other are each
 * {memberId, npi, codes}, as app.caseContext.summary() gives them and as forCase is carried. A
 * hand-over made for one case must not go out with another, and this is the one test of that.
 */
export function sameCase(one, other) {
    if (!one || !other) {
        return false;
    }
    return one.memberId === other.memberId && one.npi === other.npi && JSON.stringify(one.codes || []) === JSON.stringify(other.codes || []);
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
