// The colour key. No colour appears in this tool without a meaning a tester can name, and this
// is where each one is named. It is drawn with the same marks the pages use, so a change to how a
// fact is drawn changes the key with it.

import {el, fill, button, code} from './dom.js';
import {TONES, RESULTS, findingGroupsOf, toneMark, stamp, slowFlag, mockMark, localMark} from './marks.js';
import * as api from './api.js';
import {presenceMark, noneMark} from './shown.js';

export async function openLegend(dialog) {
    // The kinds of finding and what each group means are the server's, read each time the key is
    // opened, so the key cannot fall behind the findings it explains.
    const answer = await api.get('/api/crd/scenarios');
    fill(dialog,
        el('div', {class: 'legend__head'},
            el('h2', {id: 'legend-title', class: 'section-heading'}, 'Colour key'),
            button('Close the colour key', () => dialog.close(), {class: 'button--quiet button--small'})),
        el('p', {class: 'legend__lead'},
            'The page itself has no colour. Where a colour or a mark appears, it states one of the facts below and nothing else.'),

        el('div', {class: 'legend__columns'},
            el('div', null,
                group('What the service said',
                    'The verdict strip at the top of a result, and the square in front of an outcome in a table. '
                    + 'A solid colour is an answer from the payer. A hollow frame is no answer from the payer, and the frame says why.',
                    [TONES.map(([tone, meaning]) => row(toneMark(tone), meaning)),
                        row(toneMark('ACTION', 'NOBODY'),
                            [localMark(), ' A step done inside this tool, such as completing a questionnaire response: nothing was sent and '
                            + 'nobody answered. The dotted frame is in the colour of what the step means, and is never filled, because no payer answered.'])]),

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
                        + 'A request this tool refused, and a step done inside this tool, carry no hatch: nothing answered them.')
                ]),

                group('Marks', null, [
                    row(el('span', {class: 'swatch swatch--cream'}), [
                        'Cream has three meanings, each about the test data and none about the service doing wrong:',
                        el('ul', {class: 'legend__meanings'},
                            el('li', null, 'a field of the case typed by hand, or taken from a pasted request, which a lookup would not give back;'),
                            el('li', null, 'a DTR answer the payer pre-populated and a person then changed (an information-origin override);'),
                            el('li', null, 'as the square of an outcome or a verdict strip, the service does not know the member or the provider.'))]),
                    row(el('span', {class: 'swatch swatch--nothing'}),
                        'A dashed outline: nothing is there. No answer, nothing returned. The words inside say which.'),
                    row(el('span', {class: 'note--none'}, 'not recorded'),
                        'Grey italic words with no frame: something this tool did not record or was not given, such as an expectation nobody '
                        + 'declared or a time nobody measured.'),
                    row(el('span', {class: 'swatch swatch--fault'}),
                        'A heavy black frame: stop and read this before the next run. A fault to report: the environment failed, the ledger is not '
                        + 'recording, or a run was not recorded. On the mock profile no environment fails: a fault of the service there is one a '
                        + 'scenario played. A field or a form the server refused, with its sentence beneath it. A result made '
                        + 'before the configuration changed. News about the tool itself, under the tabs: the configuration changed, or the server '
                        + 'stopped answering.'),
                    row(el('span', {class: 'swatch swatch--plain'}),
                        'A thin black frame around a sentence: something this tool says that is neither a fault nor a result. A thing not found in this tool, '
                        + 'a problem with its configuration, or an action on the Config tab it did not make. Its own words say what to do.'),
                    row(el('span', {class: 'flag'}, 'write'),
                        'A thin black frame around a word: a mark to read, not a fault. A write, a secret, a pre-request script, a code this tool does not know.'),
                    row(el('span', {class: 'swatch swatch--edge'}),
                        'A heavy rule down the edge of a row on the Config tab: an operation that cannot be sent to a service as things are configured. '
                        + 'In purple: one the production guardrail would refuse. The row says why. In black beside sentences of the tool: '
                        + 'a warning about an uploaded file or the address of an environment, to read before using it.'),
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
                    answer.kind === 'ok'
                        ? findingGroupsOf(answer.body.findingKinds).map(each => row(el('span', {class: 'legend__finding'}, each.groupLabel),
                            [each.groupMeaning, el('span', {class: 'legend__kinds'}, each.kinds.map((kind, index) => [index > 0 ? ', ' : null, code(kind)]))]))
                        : [row(el('span', {class: 'legend__finding'}, 'Not read'),
                            ['The kinds of finding could not be read from the tool: ', answer.body && answer.body.title ? answer.body.title : 'it did not answer', ' Close the key and open it again.'])],
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
