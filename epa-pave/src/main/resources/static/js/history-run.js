// One run, from GET /api/runs/{runId}: everything its ledger line holds, the four questions first
// (what was expected, what happened, did it match, what next), then the codes, where it ran, how
// it ties to other runs, the note, and replay.
//
// The line is not the result. It keeps codes and identifiers, never the request, the response or
// the sentences the result was worded in. Where a tester would look for those, the page says that
// the ledger does not keep them and how to get them again.

import * as api from './api.js';
import {el, fill, clear, button, code, copyButton, facts, millis, when} from './dom.js';
import {textField, clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {stamp, slowFlag} from './marks.js';
import {errorPanel} from './result.js';
import {runLink, chainLink, suiteRunsLink, none, memberAsRecorded, outcomeOf, whatRan, whereRan, codes} from './history-common.js';
import {replayReport} from './history-replay.js';

// The correlation keys a line can carry, in words. A key the page does not know is shown under its own name.
const CORRELATION = {
    correlatedRunId: 'Follows the run',
    subscriptionRunId: 'Subscription created by the run',
    claimId: 'Claim identifier',
    claimResponseId: 'ClaimResponse id',
    authorizationRef: 'Authorization number, as recorded',
    subscriptionId: 'Subscription id',
    questionnaire: 'Questionnaire'
};
const RUN_KEYS = new Set(['correlatedRunId', 'subscriptionRunId']);

// The tabs a protocol's case can be loaded into.
const TAB_OF = {CRD: 'crd', DTR: 'dtr', PAS: 'pas'};

export function createRunView(app, context) {
    let openId = null;
    const node = el('div', {class: 'history-run'});

    async function open(runId) {
        openId = runId;
        const status = el('p', {class: 'readout__status', role: 'status', aria: {live: 'polite'}}, 'Reading run ' + runId + ' from the ledger.');
        fill(node, backLink(), status);
        const [answer] = await Promise.all([api.get('/api/runs/' + encodeURIComponent(runId)), context.ready()]);
        if (openId !== runId) {
            // Another run was opened while this answer was on its way. The later choice stands.
            return;
        }
        if (answer.kind !== 'ok') {
            status.textContent = answer.body.title;
            status.classList.add('visually-hidden');
            fill(node, backLink(), status, errorPanel(answer.body),
                answer.status === 404 ? el('p', null, 'A run that the ledger did not record is not here, though its result showed a run id. '
                    + 'The result said NOT recorded, with the reason, where it was made.') : null);
            return;
        }
        status.textContent = 'Run read from the ledger.';
        // Said for a screen reader. On screen the run itself says it.
        status.classList.add('visually-hidden');
        draw(answer.body, status);
    }

    function backLink() {
        const query = context.listQuery();
        return el('p', {class: 'history-run__back'},
            el('a', {href: '#history/runs' + (query && query.toString() ? '?' + query.toString() : ''), class: 'history-link'}, 'Back to the list of runs'));
    }

    function draw(run, status) {
        const vocabulary = context.vocabulary();
        const member = memberAsRecorded(run);
        const noteSlot = el('div');
        const replaySlot = el('div', {aria: {live: 'polite'}});
        const loadSlot = el('div', {aria: {live: 'polite'}});

        fill(node, backLink(), context.health.node, status,
            el('header', {class: 'history-run__head'},
                el('h2', {class: 'history-run__title'}, whatRan(vocabulary, run), el('span', {class: 'history-run__when'}, ' ', when(run.timestamp))),
                el('p', {class: 'small'}, 'Run ', code(run.runId, 'literal--id'), ' ', copyButton('Copy run id', () => run.runId),
                    run.replayOfRunId ? [' ', el('span', {class: 'flag'}, 'replay'), ' of ', runLink(run.replayOfRunId)] : null)),

            el('section', {class: 'history-run__answers', aria: {label: 'The four questions'}},
                answer('What was expected', run.expected
                    ? [code(run.expected), el('p', {class: 'small quiet'}, 'As the tester declared it.')]
                    : [none('nothing declared'), el('p', {class: 'small quiet'}, 'No expectation was sent with this run, so it is unverified.')]),
                answer('What happened', [outcomeOf(vocabulary, run), el('p', {class: 'small'}, 'Outcome code ', code(run.outcome),
                    typeof run.httpStatus === 'number' ? [', HTTP ', String(run.httpStatus)] : ', no HTTP status',
                    run.errorType && run.errorType !== 'NONE' ? [', error ', code(run.errorType)] : null, '.')]),
                answer('Did it match', [stamp(run.result, 'large')]),
                answer('What next', run.nextAction
                    ? [code(run.nextAction), el('p', {class: 'small quiet'}, 'The step this run named. The ledger keeps its name; '
                        + 'the sentence the result showed is not kept.'),
                        run.chainId ? el('p', {class: 'small'}, chainLink(run.chainId, 'Open its chain'), ' for the next step in words.') : null]
                    : [none('not recorded'), el('p', {class: 'small quiet'}, 'This line names no next step.')])),

            el('div', {class: 'facts-pair history-run__facts'},
                el('section', null,
                    el('h3', {class: 'sub-heading'}, 'What the line recorded'),
                    facts([
                        ['Procedure codes', codes(run.procedureCodes, 'none recorded')],
                        ['Codes returned', run.coverageCodes && run.coverageCodes.length > 0
                            ? el('ul', {class: 'plain-list'}, run.coverageCodes.map(entry => el('li', null, code(entry))))
                            : none('none recorded')],
                        ['Delegated vendor', run.delegatedVendor ? run.delegatedVendor : none('none recorded')],
                        ['Line of business', run.lineOfBusiness ? run.lineOfBusiness : none('none recorded')],
                        ['Findings', run.findings && run.findings.length > 0
                            ? el('ul', {class: 'plain-list'}, run.findings.map(kind => el('li', null, code(kind))))
                            : none('none raised')],
                        ['Hook', run.hook ? code(run.hook) : null],
                        ['Attachments sent as', run.attachmentMode ? code(run.attachmentMode) : null],
                        ['Mock scenario', run.scenarioId ? code(run.scenarioId) : null]
                    ], 'facts--ruled')),
                el('section', null,
                    el('h3', {class: 'sub-heading'}, 'Where and for whom'),
                    facts([
                        ['Ran against', [whereRan(run), ', ', run.profile || 'no', ' profile, target ', run.target || 'not stated']],
                        ['Url came from', run.targetSource ? ['the collection request ', el('strong', null, run.targetSource)]
                            : el('span', {class: 'quiet'}, 'the fallback settings, or nothing was configured')],
                        ['Elapsed', [millis(run.elapsedMillis), run.slow ? [' ', slowFlag()] : null]],
                        [member.label, member.node],
                        ['NPI', run.npi ? code(run.npi) : none('not recorded')],
                        ['Date of service', run.dateOfService ? code(run.dateOfService) : none('not recorded')],
                        ['Tester reference', run.testerRef ? run.testerRef : none('none')]
                    ], 'facts--ruled'))),

            el('section', {class: 'history-run__section'},
                el('h3', {class: 'sub-heading'}, 'How it ties to other runs'),
                ties(run)),

            el('section', {class: 'history-run__section'},
                el('h3', {class: 'sub-heading'}, 'Not in the ledger'),
                el('p', null, 'The ledger keeps no request and no response, and no member name, date of birth or address, under any setting. '
                    + 'That is a rule, not a gap: a file of responses would be a store of member data. To see a request and its response, '
                    + 'load this case into the form below and send it: the wire view of that result shows both. Replay sends the same case again '
                    + 'and compares the answer with this line.'),
                run.unrecordedInputs && run.unrecordedInputs.length > 0
                    ? el('p', null, 'This run also used inputs its line does not hold: ', codes(run.unrecordedInputs), '.') : null),

            el('section', {class: 'history-run__section'},
                el('h3', {class: 'sub-heading'}, 'Run it again'),
                el('div', {class: 'button-row'},
                    replayButton(run, replaySlot),
                    TAB_OF[run.protocol] ? button('Load this case into the form', () => loadIntoCase(run, loadSlot)) : null),
                el('p', {class: 'small quiet'}, 'Replay sends the same request again against the configuration in force now and says whether the answer is the same. '
                    + 'The server says if this line cannot be sent again, and why. Loading the case puts the member, the NPI and the codes on the '
                    + (TAB_OF[run.protocol] ? run.protocol : 'protocol') + ' tab for you to send yourself.'),
                loadSlot, replaySlot),

            el('section', {class: 'history-run__section'},
                el('h3', {class: 'sub-heading'}, 'Note'),
                noteSlot));
        drawNote(run, noteSlot);
    }

    function answer(question, body) {
        return el('div', {class: 'history-run__answer'}, el('h3', {class: 'history-run__question'}, question), el('div', null, body));
    }

    function ties(run) {
        const rows = [];
        rows.push(['Chain', run.chainId ? [chainLink(run.chainId), el('div', {class: 'small quiet'}, 'Opens the whole journey of this case.')]
            : none('not part of a chain')]);
        rows.push(['Suite run', run.suiteRunId
            ? [suiteRunsLink(run.suiteRunId), run.caseId ? [', case ', code(run.caseId)] : null,
                el('div', {class: 'small quiet'}, 'Lists every run of that suite run.')]
            : none('not part of a suite run')]);
        rows.push(['Replay of', run.replayOfRunId ? runLink(run.replayOfRunId) : null]);
        for (const [key, value] of Object.entries(run.correlation || {})) {
            const label = Object.prototype.hasOwnProperty.call(CORRELATION, key) ? CORRELATION[key] : key;
            rows.push([label, RUN_KEYS.has(key) && /^run-/.test(value) ? runLink(value) : code(value)]);
        }
        return facts(rows, 'facts--ruled');
    }

    /* ---------- the note ---------- */

    function drawNote(run, slot) {
        const noteId = 'history-note-' + run.runId;
        const text = el('textarea', {id: noteId, name: 'note', rows: 3, maxLength: 4000, value: run.note || '', aria: {describedby: noteId + '-hint'}});
        const noteField = el('div', {class: 'field field--wide', dataset: {field: 'note'}},
            el('label', {htmlFor: noteId}, 'Note on this run'), text,
            el('span', {class: 'field__hint', id: noteId + '-hint'}, 'Kept in the ledger exactly as written and shown to anyone who reads it. '
                + 'Do not put member data here: no name, date of birth, address or member details.'));
        const ref = textField({label: 'Tester reference', field: 'testerRef', placeholder: 'ticket or initials'});
        ref.set(run.testerRef || '');
        const problem = el('div');
        const receipt = el('div', {aria: {live: 'polite'}});
        const save = el('button', {type: 'submit', class: 'button button--quiet'}, 'Save the note');
        const form = el('form', {class: 'history-note', noValidate: true, attrs: {'aria-label': 'The note on this run'}},
            noteField, ref.node, problem, el('div', {class: 'button-row'}, save), receipt);
        form.addEventListener('submit', async event => {
            event.preventDefault();
            clearFieldErrors(form);
            clear(problem);
            clear(receipt);
            save.disabled = true;
            // Both are sent as they stand on screen. The server decides what a blank value means.
            const answer = await api.postJson('/api/runs/' + encodeURIComponent(run.runId) + '/note', {note: text.value, testerRef: ref.input.value});
            save.disabled = false;
            if (answer.kind === 'form') {
                fill(problem, formProblem(answer.body, markFieldErrors([form], answer.body.fieldErrors)));
                return;
            }
            if (answer.kind !== 'ok') {
                fill(problem, errorPanel(answer.body));
                return;
            }
            const kept = answer.body.receipt;
            if (kept.recorded) {
                fill(receipt, el('p', {class: 'ledger'}, el('strong', null, 'Saved in the ledger.'), ' ',
                    answer.body.run && answer.body.run.note ? 'The note now reads as above.' : 'This run now has no note.'));
                if (answer.body.run) {
                    text.value = answer.body.run.note || '';
                    ref.set(answer.body.run.testerRef || '');
                }
            } else {
                fill(receipt, el('div', {class: 'ledger ledger--missing', role: 'alert'},
                    el('p', {class: 'ledger__state'}, 'NOT saved'),
                    el('p', null, 'The ledger did not keep this note. It said:'),
                    el('blockquote', null, kept.message)));
            }
            context.health.refresh();
        });
        fill(slot, form);
    }

    /* ---------- replay and load ---------- */

    function replayButton(run, slot) {
        const control = button('Replay this run', () => replay(run, slot, control), {class: 'button--primary'});
        return control;
    }

    async function replay(run, slot, control) {
        control.disabled = true;
        const status = el('p', {class: 'readout__status', role: 'status'}, 'Sending the run again.');
        fill(slot, status);
        const answer = await api.postJson('/api/runs/replay', {runIds: [run.runId]});
        control.disabled = false;
        if (answer.kind === 'ok') {
            fill(slot, replayReport(answer.body, context.vocabulary(), {single: true, title: 'What the replay found'}));
            const entry = (answer.body.results || [])[0];
            if (entry && entry.status === 'not-replayable') {
                // The server has said this line cannot be sent again. A button that would only ask again is taken away.
                control.hidden = true;
            }
            context.health.refresh();
            return;
        }
        fill(slot, errorPanel(answer.body));
        if (answer.kind === 'conflict') {
            app.refreshMode();
        }
    }

    function loadIntoCase(run, slot) {
        const loaded = [];
        const missing = [];
        // The member details of the case belong to whoever was there before, so they go: only the ID comes from the line.
        if (run.memberId) {
            app.caseContext.fillMember({memberId: run.memberId});
            loaded.push(['the member ID ', code(run.memberId)]);
        } else {
            missing.push('the member ID, which this line does not hold');
        }
        if (run.npi) {
            app.caseContext.fillProvider({npi: run.npi});
            loaded.push(['the NPI ', code(run.npi)]);
        } else {
            missing.push('the NPI, which this line does not hold');
        }
        if (run.procedureCodes && run.procedureCodes.length > 0) {
            app.caseContext.setCodes(run.procedureCodes);
            loaded.push(['the codes ', codes(run.procedureCodes)]);
        }
        const tab = TAB_OF[run.protocol];
        fill(slot, el('div', {class: 'history-loaded'},
            el('p', null, loaded.length > 0 ? ['Loaded into the case: ', loaded.map((part, index) => [index > 0 ? ', ' : null, part]), '. '] : 'Nothing could be loaded. ',
                missing.length > 0 ? ['Not loaded: ', missing.join('; '), '. '] : null,
                'The hook, the date of service, the scenario and the expectation are not loaded: set them on the tab before sending.'),
            button('Open the ' + run.protocol + ' tab', () => app.navigate(tab), {class: 'button--primary'})));
    }

    return {node, open, openId: () => openId};
}
