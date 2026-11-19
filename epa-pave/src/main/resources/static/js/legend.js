// The colour key. No colour appears in this tool without a meaning a tester can name, and this
// is where each one is named. It is drawn with the same marks the pages use, so a change to how a
// fact is drawn changes the key with it.

import {el, fill, button} from './dom.js';
import {TONES, RESULTS, toneMark, stamp, slowFlag, mockMark} from './marks.js';
import {presenceMark} from './shown.js';

export function openLegend(dialog) {
    fill(dialog,
        el('div', {class: 'legend__head'},
            el('h2', {id: 'legend-title', class: 'section-heading'}, 'Colour key'),
            button('Close', () => dialog.close(), {class: 'button--quiet button--small'})),
        el('p', {class: 'legend__lead'},
            'The page itself has no colour. Where a colour or a mark appears, it states one of the facts below and nothing else.'),

        group('What the service said',
            'The verdict strip at the top of a result, and the square in front of an outcome in a table. '
            + 'A solid colour is an answer from the payer. A hollow frame is no answer from the payer, and the frame says why.',
            TONES.map(([tone, meaning]) => row(toneMark(tone), meaning))),

        group('The test result',
            'Whether the outcome matched what the tester declared. It is a different fact from what the service said, '
            + 'and green and magenta are used for nothing else.',
            RESULTS.map(([result, meaning]) => row(stamp(result), meaning))),

        group('Where the tool is pointed', null, [
            row(el('span', {class: 'kind kind--prod'}, 'prod'), 'Production. A solid purple banner stays at the top of every tab and says whether writes are locked.'),
            row(el('span', {class: 'kind kind--uat'}, 'uat'), 'UAT. A banner in purple outline stays at the top of every tab.'),
            row(mockMark(), 'The mock profile. A scenario file answered in place of the service, and nothing left this machine.')
        ]),

        group('Marks', null, [
            row(el('span', {class: 'swatch swatch--cream'}), 'Cream: test data typed by hand, which a lookup would not give back.'),
            row(el('span', {class: 'swatch swatch--fault'}), 'A heavy black frame: a fault to report. The environment failed, or a run was not recorded.'),
            row(el('span', {class: 'swatch swatch--blocked'}), 'A purple frame: the production guardrail refused to send.'),
            row(slowFlag(), 'The call completed but took longer than the threshold.')
        ]),

        group('A value that is not there', 'Three different findings, never shown as a blank cell.', [
            row(presenceMark('ABSENT'), 'The response did not carry the field.'),
            row(presenceMark('NULL'), 'The response carried the field with an explicit null.'),
            row(presenceMark('EMPTY'), 'The response carried the field and it was empty.')
        ]));
    if (!dialog.open) {
        dialog.showModal();
    }
}

function group(title, lead, rows) {
    return el('section', {class: 'legend__group'},
        el('h3', {class: 'sub-heading'}, title),
        lead ? el('p', {class: 'quiet small'}, lead) : null,
        el('dl', {class: 'legend__rows'}, rows));
}

function row(mark, meaning) {
    return [el('dt', null, mark), el('dd', null, meaning)];
}
