// The colour key. No colour appears in this tool without a meaning a tester can name, and this
// is where each one is named. It is drawn with the same marks the pages use, so a change to how a
// fact is drawn changes the key with it.

import {el, fill, button, code} from './dom.js';
import {TONES, RESULTS, FINDING_GROUPS, toneMark, stamp, slowFlag, mockMark} from './marks.js';
import {presenceMark, noneMark} from './shown.js';

export function openLegend(dialog) {
    fill(dialog,
        el('div', {class: 'legend__head'},
            el('h2', {id: 'legend-title', class: 'section-heading'}, 'Colour key'),
            button('Close', () => dialog.close(), {class: 'button--quiet button--small'})),
        el('p', {class: 'legend__lead'},
            'The page itself has no colour. Where a colour or a mark appears, it states one of the facts below and nothing else.'),

        el('div', {class: 'legend__columns'},
            el('div', null,
                group('What the service said',
                    'The verdict strip at the top of a result, and the square in front of an outcome in a table. '
                    + 'A solid colour is an answer from the payer. A hollow frame is no answer from the payer, and the frame says why.',
                    TONES.map(([tone, meaning]) => row(toneMark(tone), meaning))),

                group('The test result',
                    'Whether the outcome matched what the tester declared. It is a different fact from what the service said, '
                    + 'and green and magenta are used for nothing else.',
                    RESULTS.map(([result, meaning]) => row(stamp(result), meaning)))),

            el('div', null,
                group('Where the tool is pointed', null, [
                    row(el('span', {class: 'kind kind--prod'}, 'prod'),
                        'Production. A solid purple banner stays at the top of every tab and says whether writes are locked.'),
                    row(el('span', {class: 'banner__writes banner__writes--unlocked legend__unlocked'}, 'UNLOCKED'),
                        'Production with writes unlocked for this browser session only. The banner says so in a white frame, and on the Config tab '
                        + 'the panel about writes wears a purple band and frame. Any change to the configuration locks them again.'),
                    row(el('span', {class: 'kind kind--uat'}, 'uat'), 'UAT. A banner in purple outline stays at the top of every tab.'),
                    row(el('span', {class: 'kind kind--sandbox'}, 'sandbox'), 'A sandbox. It has no banner: nothing about it needs a warning.'),
                    row(mockMark(), 'The mock profile. A scenario file answered in place of the service, and nothing left this machine. '
                        + 'A request this tool refused carries no hatch: nothing answered it.')
                ]),

                group('Marks', null, [
                    row(el('span', {class: 'swatch swatch--cream'}), 'Cream: test data typed by hand, which a lookup would not give back.'),
                    row(el('span', {class: 'swatch swatch--nothing'}),
                        'A dashed outline: nothing is there. No answer, nothing declared, nothing returned. The words inside say which.'),
                    row(el('span', {class: 'swatch swatch--fault'}),
                        'A heavy black frame: a fault to report. The environment failed, the ledger is not recording, or a run was not recorded. '
                        + 'A field the server refused wears it too, with the server\'s sentence beneath it.'),
                    row(el('span', {class: 'flag'}, 'write'),
                        'A thin black frame around a word: a mark to read, not a fault. A write, a secret, a pre-request script, a code this tool does not know.'),
                    row(el('span', {class: 'swatch swatch--blocked'}),
                        'A purple frame: the production guardrail. A request it refused to send, or, on the Config tab, writes it lets through while unlocked.'),
                    row(slowFlag(), 'The call completed but took longer than the threshold.')
                ]),

                group('A value that is not there', 'Each is a different finding, and none is ever shown as a blank cell.', [
                    row(presenceMark('ABSENT'), 'The response did not carry the field.'),
                    row(presenceMark('NULL'), 'The response carried the field with an explicit null.'),
                    row(presenceMark('EMPTY'), 'The response carried the field and it was empty.'),
                    row(noneMark(), 'A field that can repeat, and the response carried none of them.')
                ])),

            el('div', null,
                group('Findings',
                    'A finding is something to look at, never a failure of the call, and none has a colour. The line above its title says what it is about. '
                    + 'Its kind is the name the server sends; a kind not listed here is shown under its own name.',
                    // The line a finding carries is words, not a mark, so here each one stands above what it means.
                    FINDING_GROUPS.map(each => row(el('span', {class: 'legend__finding'}, each.heading),
                        [each.meaning, el('span', {class: 'legend__kinds'}, each.kinds.map((kind, index) => [index > 0 ? ', ' : null, code(kind)]))])),
                    'legend__rows--stacked'))));
    if (!dialog.open) {
        dialog.showModal();
    }
}

function group(title, lead, rows, extraClass) {
    return el('section', {class: 'legend__group'},
        el('h3', {class: 'sub-heading'}, title),
        lead ? el('p', {class: 'quiet small'}, lead) : null,
        el('dl', {class: 'legend__rows' + (extraClass ? ' ' + extraClass : '')}, rows));
}

function row(mark, meaning) {
    return [el('dt', null, mark), el('dd', null, meaning)];
}
