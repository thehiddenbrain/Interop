// One run, from GET /api/runs/{runId}: everything its ledger line holds, the four questions first
// (what was expected, what happened, did it match, what next), then the codes, where it ran, how
// it ties to other runs, the note, and replay.
//
// The line is not the result. It keeps codes and identifiers, never the request, the response or
// the sentences the result was worded in. Where a tester would look for those, the page says that
// the ledger does not keep them and how to get them again.

import * as api from './api.js';
import {el, fill, clear, button, code, copyButton, facts, millis} from './dom.js';
import {textField, clearFieldErrors, markFieldErrors, formProblem, LEDGER_TEXT_HINT} from './forms.js';
import {stamp, slowFlag} from './marks.js';
import {errorPanel} from './result.js';
import {runLink, chainLink, suiteRunsLink, none, memberAsRecorded, outcomeOf, expectedOf, whatRan, whereRan, codes, whenFiled} from './history-common.js';
import {replayReport, summaryText} from './history-replay.js';

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

// The replay statuses after which asking again gives the same answer: the line itself, or the
// profile it was made on, is what stops it. Blocked and not configured can change with the
// configuration, so the button stays after those.
const FINAL = new Set(['not-replayable', 'invalid']);

/** How many of the runs that follow a run its view lists. */
const FOLLOWERS_SHOWN = 20;

export function createRunView(app, context) {
    let openId = null;
    const node = el('div', {class: 'history-run'});

    async function open(runId, focusAfter) {
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
        if (focusAfter) {
            // The link that opened the run went with the list, so the focus starts at the run's heading.
            node.querySelector('.history-run__title').focus({preventScroll: true});
        }
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
        // Neither is a live region: a report is long. What happened is announced in a sentence.
        const replaySlot = el('div');
        const loadSlot = el('div');
        const heldMember = Boolean(run.memberId);

        fill(node, backLink(), context.health.node, status,
            el('header', {class: 'history-run__head'},
                el('h2', {class: 'history-run__title', tabIndex: -1}, whatRan(vocabulary, run), el('span', {class: 'history-run__when'}, ' ', whenFiled(run.timestamp))),
                el('p', {class: 'small'}, 'Run ', code(run.runId, 'literal--id'), ' ', copyButton('Copy run id', () => run.runId),
                    run.replayOfRunId ? [' ', el('span', {class: 'flag'}, 'replay'), ' of ', runLink(run.replayOfRunId)] : null)),

            el('section', {class: 'history-run__answers', aria: {label: 'The four questions'}},
                answer('What was expected', run.expected
                    ? [expectedOf(vocabulary, run), el('p', {class: 'small quiet'}, 'As the tester declared it.')]
                    : [none('nothing declared'), el('p', {class: 'small quiet'}, 'No expectation was sent with this run, so it is unverified.')]),
                answer('What happened', [outcomeOf(vocabulary, run), el('p', {class: 'small'}, 'Outcome code ', code(run.outcome),
                    typeof run.httpStatus === 'number' ? [', HTTP ', String(run.httpStatus)] : ', no HTTP status',
                    run.errorType && run.errorType !== 'NONE' ? [', error ', code(run.errorType)] : null, '.')]),
                answer('Did it match', [stamp(run.result, 'large')]),
                answer('What next', run.nextAction
                    ? [vocabulary.nextAction(run.nextAction) ? [el('strong', null, vocabulary.nextAction(run.nextAction)), ' '] : null,
                        el('span', {class: 'small'}, '(', code(run.nextAction), ')'),
                        el('p', {class: 'small quiet'}, 'The step this run named, in the server\'s words for it. The ledger keeps the name only, '
                        + 'so the reason the result gave beside it is not kept.'),
                        run.chainId ? el('p', {class: 'small'}, chainLink(run.chainId, 'Open its chain'), ' to see where the journey stands now.')
                            : el('p', {class: 'small'}, 'To read the reason, load this case into the form below and send it: the result states it.')]
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
                        ['Elapsed', [millis(run.elapsedMillis) || none('not measured'), run.slow ? [' ', slowFlag()] : null]],
                        [member.label, member.node],
                        ['NPI', run.npi ? code(run.npi) : none('not recorded')],
                        ['Date of service', run.dateOfService ? code(run.dateOfService) : none('not recorded')],
                        ['Tester reference', run.testerRef ? run.testerRef : none('none')]
                    ], 'facts--ruled'))),

            el('section', {class: 'history-run__section'},
                el('h3', {class: 'sub-heading'}, 'How it ties to other runs'),
                ties(run), followers(run)),

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
                replayRefused(run),
                el('p', {class: 'small quiet'}, run.unrecordedInputs && run.unrecordedInputs.length > 0 ? null
                    : 'Replay sends the same request again against the configuration in force now and says whether the answer is the same. '
                    + 'The server says if this line cannot be sent again, and why. ',
                    TAB_OF[run.protocol] ? (heldMember
                        ? 'Loading the case puts the member ID, the NPI and the codes of this line on the ' + run.protocol + ' tab for you to send yourself.'
                        : 'This line holds no member ID, so loading the case empties the member on the ' + run.protocol
                            + ' tab and puts the NPI and the codes there: type the member before sending.') : null),
                loadSlot, replaySlot),

            el('section', {class: 'history-run__section'},
                el('h3', {class: 'sub-heading'}, 'Note'),
                noteSlot));
        drawNote(run, noteSlot);
    }

    function answer(question, body) {
        return el('div', {class: 'history-run__answer'}, el('h3', {class: 'history-run__question'}, question), el('div', null, body));
    }

    /**
     * The runs that name this one as the run they follow, read from the ledger, so a QA analyst
     * goes from a step to the steps after it as easily as back: from a package fetch to the
     * response completed from it, and from there to the submission that carried its answers.
     */
    function followers(run) {
        const slot = el('div', {class: 'history-run__followers', aria: {live: 'polite'}},
            el('p', {class: 'small quiet'}, 'Reading the runs that follow this one.'));
        api.get('/api/runs?follows=' + encodeURIComponent(run.runId) + '&limit=' + FOLLOWERS_SHOWN).then(answer => {
            if (openId !== run.runId) {
                return;
            }
            if (answer.kind !== 'ok') {
                fill(slot, el('p', {class: 'small'}, 'The runs that follow this one could not be read: ', answer.body.title));
                return;
            }
            const runs = answer.body.runs || [];
            fill(slot, facts([['Followed by', runs.length === 0 ? none('no run names this one as the run it follows')
                : [el('ul', {class: 'plain-list'}, runs.map(each => el('li', null, runLink(each.runId), ' ',
                    whatRan(context.vocabulary(), each), ', ', outcomeOf(context.vocabulary(), each)))),
                answer.body.truncated ? el('p', {class: 'small'}, 'Only the newest ' + FOLLOWERS_SHOWN + ' are listed.') : null]]],
            'facts--ruled'));
        });
        return slot;
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
            el('span', {class: 'field__hint', id: noteId + '-hint'}, LEDGER_TEXT_HINT));
        const ref = textField({label: 'Tester reference', field: 'testerRef', placeholder: 'ticket or initials', hint: LEDGER_TEXT_HINT});
        ref.set(run.testerRef || '');
        const problem = el('div');
        const receipt = el('div');
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
            // Disabling the button while the note was sent dropped the focus; it goes back where the tester left it.
            save.focus();
            if (answer.kind === 'form') {
                fill(problem, formProblem(answer.body, markFieldErrors([form], answer.body.fieldErrors)));
                context.announce('The note was not saved: ' + answer.body.title);
                return;
            }
            if (answer.kind !== 'ok') {
                fill(problem, errorPanel(answer.body));
                context.announce('The note was not saved: ' + answer.body.title);
                return;
            }
            const kept = answer.body.receipt;
            if (kept.recorded) {
                fill(receipt, el('p', {class: 'ledger'}, el('strong', null, 'Saved in the ledger.'), ' ',
                    answer.body.run && answer.body.run.note ? 'The note now reads as above.' : 'This run now has no note.'));
                context.announce(answer.body.run && answer.body.run.note ? 'Note saved in the ledger.' : 'Saved: this run now has no note.');
                if (answer.body.run) {
                    text.value = answer.body.run.note || '';
                    ref.set(answer.body.run.testerRef || '');
                }
            } else {
                fill(receipt, el('div', {class: 'ledger ledger--missing', role: 'alert'},
                    el('p', {class: 'ledger__state'}, 'NOT saved'),
                    el('p', null, 'The ledger did not keep this note. It said:'),
                    el('blockquote', null, kept.message)));
                context.announce('The note was NOT saved. The ledger did not keep it.');
            }
            context.health.refresh();
        });
        fill(slot, form);
    }

    /* ---------- replay and load ---------- */

    function replayButton(run, slot) {
        if (run.unrecordedInputs && run.unrecordedInputs.length > 0) {
            // The record states that replay refuses a line that lists any input it does not hold,
            // so a button here could only ask to be told so. replayRefused says it instead.
            return null;
        }
        const control = button('Replay this run', () => replay(run, slot, control), {class: 'button--primary'});
        return control;
    }

    /** For a line replay refuses as it stands, the reason, in place of the button. */
    function replayRefused(run) {
        if (!run.unrecordedInputs || run.unrecordedInputs.length === 0) {
            return null;
        }
        return el('p', {class: 'history-run__refused'}, el('strong', null, 'This run cannot be replayed from its line. '),
            'It also sent ', codes(run.unrecordedInputs), ', which the line does not hold, and replay refuses such a line rather than send something else. ',
            run.chainId ? ['Its chain shows the step it followed: ', chainLink(run.chainId, 'open its chain'), '.'] : null);
    }

    async function replay(run, slot, control) {
        control.disabled = true;
        const status = el('p', {class: 'readout__status'}, 'Sending the run again.');
        fill(slot, status);
        context.announce('Sending the run again.');
        const answer = await api.postJson('/api/runs/replay', {runIds: [run.runId]});
        control.disabled = false;
        if (answer.kind === 'ok') {
            const report = replayReport(answer.body, context.vocabulary(), {single: true, title: 'What the replay found'});
            fill(slot, report);
            const entry = (answer.body.results || [])[0];
            if (entry && FINAL.has(entry.status)) {
                // The server has said why this line cannot be sent here. A button that would only ask again is taken away,
                // and the focus it had goes to the answer.
                control.hidden = true;
                report.querySelector('h3').focus();
            } else {
                // Disabling the button while the run was sent dropped the focus; it goes back to the button.
                control.focus();
            }
            context.announce('Replay done: ' + (entry ? entry.status.replace(/-/g, ' ') + '. ' : '') + (entry && entry.reason ? entry.reason : summaryText(answer.body)));
            context.health.refresh();
            return;
        }
        fill(slot, errorPanel(answer.body));
        control.focus();
        context.announce('Nothing was replayed: ' + answer.body.title);
        if (answer.kind === 'conflict') {
            app.refreshMode();
        }
    }

    /**
     * Puts this line's case into the shared case. What the line does not hold is emptied, never
     * left as it was: a member, a provider or codes typed for another case must not go out beside
     * this run's details as if they were its own.
     */
    function loadIntoCase(run, slot) {
        const loaded = [];
        const emptied = [];
        const before = app.caseContext.summary();
        if (run.memberId) {
            // The member details of the case belong to whoever was there before, so they go: only the ID comes from the line.
            app.caseContext.fillMember({memberId: run.memberId});
            loaded.push(['the member ID ', code(run.memberId)]);
        } else {
            app.caseContext.fillMember({});
            emptied.push(before.memberId ? 'the member who was in the case before, because this line holds no member ID'
                : 'the member, because this line holds no member ID');
        }
        if (run.npi) {
            app.caseContext.fillProvider({npi: run.npi});
            loaded.push(['the NPI ', code(run.npi)]);
        } else {
            app.caseContext.fillProvider({});
            emptied.push('the provider, because this line holds no NPI');
        }
        if (run.procedureCodes && run.procedureCodes.length > 0) {
            app.caseContext.setCodes(run.procedureCodes);
            loaded.push(['the codes ', codes(run.procedureCodes)]);
        } else {
            app.caseContext.setCodes([]);
            emptied.push('the procedure codes, because this line holds none');
        }
        const tab = TAB_OF[run.protocol];
        const message = el('div', {class: 'history-loaded'},
            el('p', null, loaded.length > 0 ? ['Loaded into the case: ', loaded.map((part, index) => [index > 0 ? ', ' : null, part]), '. '] : 'Nothing could be loaded. ',
                emptied.length > 0 ? [el('strong', null, 'Emptied: ' + emptied.join('; ') + '.'), ' Type what is missing on the tab before sending. '] : null,
                'The hook, the date of service, the scenario and the expectation are not loaded: set them on the tab before sending.'),
            button('Open the ' + run.protocol + ' tab', () => app.navigate(tab), {class: 'button--primary'}));
        fill(slot, message);
        context.announce(message.querySelector('p').textContent);
    }

    return {node, open, openId: () => openId};
}
