// Answering the questionnaires of one package: the list of them with where each stands, the
// questions of the one open, the answers typed for the next step, and the steps sent so far.
//
// The server keeps nothing between steps, so this holds what the next step must post, in memory
// only and only until the next package is fetched or the page is reloaded: each questionnaire as
// the package returned it, the response the package carried for it, the response the latest step
// of the adaptive loop returned, and the hand-over (nextStep.carry) of the latest DTR result,
// which every step posts back unchanged. What to post at each step follows the DTR service's own
// notes (REPORT_B2, integrator notes 2 and 3):
//
//   first loop step     the response the package carried for it when there is one, else the questionnaire
//   later loop steps    the response the previous loop step returned, with the answers given since
//   response step       the questionnaire, the response the package carried for it, and every answer
//                       the tester has given
//
// Whether a response is complete, which questions are enabled, what is required and what changed
// are the server's answers to those posts. Nothing here works any of them out.
//
// What this does keep is which answers the server has seen: for each questionnaire, the answers as
// they were posted with the last step that worked (its baseline). Typed answers that differ from
// it are answers the server has not evaluated. While there are any, every enabled state on screen
// says it is as of that step, and a hand-over to PAS is withdrawn, because PAS would receive the
// response as last completed, without them.

import {el, fill, clear, button, code} from './dom.js';
import {toneMark} from './marks.js';
import {itemList, responseByLinkId} from './dtr-items.js';
import {changesBlock, notApplied, kindWord} from './dtr-detail.js';

/**
 * A session for one package result.
 *
 * hooks.loop(entry)        sends the next step of the adaptive loop for the questionnaire
 * hooks.complete(entry)    sends the response step for it
 * hooks.previewLoop(entry) shows the next-question request without sending it
 * hooks.nextControl(step, envelope)  the button a result offers for its next step, or null
 * hooks.track(...controls) registers controls that are disabled while a request is on its way
 * hooks.expected()         the expectation that goes with the next step sent, as it is sent
 * hooks.editExpectation()  takes the tester to the expectation field
 * hooks.actionName(code)   the server's label of a next action, which names the button that takes it
 */
export function createSession(packageEnvelope, hooks) {
    // The two buttons are named by the labels the server gives their actions, which are the words
    // its next step quotes, so the next step and the button are one name.
    const buttonName = {loop: () => hooks.actionName('ANSWER_NEXT_QUESTION'), complete: () => hooks.actionName('SUBMIT_RESPONSE')};
    const detail = packageEnvelope.detail || {};
    const responses = detail.questionnaireResponses || [];
    const entries = (detail.questionnaires || []).map((view, index) => {
        const start = view.arrivedWith ? responses.find(response => response.foundAt === view.arrivedWith) || null : null;
        return {
            index,
            view,
            current: view,
            startResponse: start,
            response: start,
            loopJson: null,
            answers: new Map(),
            form: null,
            steps: [],
            lastFailed: null,
            // The answers as posted with the last step that worked, by linkId, each as JSON text.
            // Before any step it is empty: the package was evaluated with no answer of the tester's.
            baseline: new Map(),
            inFlight: new Map(),
            touched: false
        };
    });
    const session = {
        packageRunId: packageEnvelope.runId,
        entries,
        active: 0,
        carry: (packageEnvelope.nextStep && packageEnvelope.nextStep.carry) || {},
        latest: {nextStep: packageEnvelope.nextStep, envelope: packageEnvelope},
        // The questionnaire the latest result is about, or null when it is the package fetch.
        latestEntry: null,
        node: el('section', {class: 'dtr-work', aria: {label: 'Answering the questionnaires'}}),
        wrap: '',
        render,
        applyResult,
        loopBody,
        responseBody,
        sync,
        resultControl,
        refreshExpectation,
        problemSlot: () => session.node.querySelector('.dtr-step-problem'),
        entry: () => entries[session.active]
    };

    function snapshot(answers) {
        const out = new Map();
        for (const [linkId, values] of answers) {
            if (values !== null) {
                out.set(linkId, JSON.stringify(values));
            }
        }
        return out;
    }

    /** The linkIds whose typed answer differs from what was posted with the last step that worked. */
    function unseen(entry) {
        const now = snapshot(entry.answers);
        const ids = new Set([...now.keys(), ...entry.baseline.keys()]);
        return [...ids].filter(linkId => now.get(linkId) !== entry.baseline.get(linkId));
    }

    function lastWorked(entry) {
        return [...entry.steps].reverse().find(step => step.worked) || null;
    }

    /** The question text the server gave for a linkId, for naming it in a sentence. */
    function textOf(entry, linkId) {
        let found = null;
        const walk = list => {
            for (const item of list || []) {
                if (!found && item.linkId && item.linkId.value === linkId && item.text && item.text.presence === 'VALUE') {
                    found = String(item.text.value);
                }
                walk(item.items);
            }
        };
        walk((entry.current || entry.view).items);
        return found;
    }

    function named(entry, ids) {
        return ids.map((linkId, index) => {
            const text = textOf(entry, linkId);
            return [index > 0 ? '; ' : null, code(linkId), text ? ' "' + text + '"' : null];
        });
    }

    /** When the enabled states on screen were evaluated. */
    function basis(entry) {
        const worked = lastWorked(entry);
        if (!worked) {
            return 'the package';
        }
        return worked.kind === 'loop' ? 'step ' + worked.number : 'the last response step';
    }

    /** What the next loop step posts for this questionnaire. */
    function loopBody(entry) {
        const body = {};
        if (entry.loopJson) {
            body.questionnaireResponse = entry.loopJson;
        } else if (entry.startResponse) {
            body.questionnaireResponse = entry.startResponse.json;
        } else {
            body.questionnaire = entry.view.json;
        }
        body.answers = postedAnswers(entry);
        entry.inFlight = snapshot(entry.answers);
        if (session.wrap !== '') {
            body.wrapInParameter = session.wrap;
        }
        return body;
    }

    /** What the response step posts for this questionnaire. */
    function responseBody(entry) {
        if (entry.view.kind === 'ADAPTIVE' && entry.loopJson) {
            // An adaptive response is completed by the service: the last one it returned goes as it is.
            entry.inFlight = snapshot(entry.answers);
            return {questionnaireResponse: entry.loopJson, answers: postedAnswers(entry)};
        }
        const body = {questionnaire: entry.view.json};
        if (entry.startResponse) {
            body.questionnaireResponse = entry.startResponse.json;
        }
        body.answers = postedAnswers(entry);
        entry.inFlight = snapshot(entry.answers);
        return body;
    }

    function postedAnswers(entry) {
        const out = [];
        for (const [linkId, values] of entry.answers) {
            if (values !== null) {
                out.push({linkId, values});
            }
        }
        return out;
    }

    /**
     * Takes in the result of a step sent for a questionnaire. A result that holds where the form
     * stands is a step that worked; anything else failed, and everything answered so far stays.
     */
    function applyResult(entry, kind, envelope) {
        const result = envelope.detail || {};
        const worked = Boolean(result.form);
        entry.steps.push({
            number: entry.steps.length + 1,
            kind,
            runId: envelope.runId,
            outcome: envelope.verdict.outcomeLabel || envelope.verdict.outcomeCode,
            tone: envelope.verdict.tone,
            headline: envelope.verdict.headline,
            worked,
            recorded: envelope.ledger ? envelope.ledger.recorded === true : false,
            form: result.form || null
        });
        if (worked) {
            entry.form = result.form;
            entry.current = (result.questionnaires || [])[0] || entry.current;
            entry.response = (result.questionnaireResponses || [])[0] || entry.response;
            entry.baseline = entry.inFlight;
            if (kind === 'loop' && entry.response && entry.response.json) {
                entry.loopJson = entry.response.json;
                // The answers went out and are in the response now, where they are shown with their origin.
                entry.answers.clear();
                entry.baseline = new Map();
            }
            entry.lastFailed = null;
        } else {
            entry.lastFailed = {headline: envelope.verdict.headline, runId: envelope.runId, kind};
        }
        const carried = envelope.nextStep ? envelope.nextStep.carry : null;
        // A result that hands nothing on does not wipe what the package and the steps before it
        // handed on: posting an empty carry would lose the package run and the completed responses.
        if (carried && Object.keys(carried).length > 0) {
            session.carry = carried;
        }
        session.latest = {nextStep: envelope.nextStep, envelope};
        session.latestEntry = entry.index;
        render();
    }

    function stateOf(entry) {
        const last = entry.steps.length > 0 ? entry.steps[entry.steps.length - 1] : null;
        const worked = lastWorked(entry);
        if (worked && worked.form && worked.form.complete) {
            return unseen(entry).length > 0 ? {code: 'changed', words: 'complete as last sent, answers changed since'} : {code: 'complete', words: 'complete'};
        }
        if (last || entry.touched) {
            return {code: 'progress', words: 'in progress'};
        }
        return {code: 'none', words: 'not started'};
    }

    function render() {
        const entry = entries[session.active];
        fill(session.node,
            el('h4', {class: 'section-heading'}, 'Answering the questionnaire' + (entries.length === 1 ? '' : 's')),
            // Said once, here, where the questions start.
            el('p', {class: 'dtr-honest'}, 'There is no SMART launch here and no patient record: you type each answer yourself. '
                + 'Answers marked as pre-populated came from the payer\'s service.'),
            entries.length > 1 ? el('div', {class: 'dtr-package-slot'}, packageList()) : null,
            entry ? questionnaireView(entry) : null);
        sync();
    }

    /**
     * Brings what depends on the answers typed so far up to date, without drawing the questions
     * again (which would take the focus out of the field being typed in): the note on answers the
     * server has not seen, the mark at each enabled state, where each questionnaire stands, and
     * whether "Continue in PAS" is offered. Called after every change and after a result is drawn.
     */
    function sync() {
        const entry = entries[session.active];
        if (!entry) {
            return;
        }
        const ids = unseen(entry);
        const adaptive = entry.view.kind === 'ADAPTIVE';
        const note = session.node.querySelector('.dtr-changed');
        const marks = session.node.querySelectorAll('.dtr-asof');
        const atEach = marks.length > 0 ? ', and each question whose state depends on answers says so at the question' : '';
        if (note) {
            fill(note, ids.length === 0 ? null : adaptive
                ? ['You have typed answers the service has not seen yet. Which questions are asked and which are enabled is as of ', basis(entry),
                    atEach, '. "', buttonName.loop(), '" sends your answers.']
                : ['You have changed answers since the server last evaluated them. Which questions are enabled and which are required is as of ',
                    basis(entry), ', before your changes', atEach, '. Choose "', buttonName.complete(), '" to have them evaluated.']);
        }
        for (const mark of marks) {
            mark.textContent = ids.length === 0 ? '' : '(As of ' + basis(entry) + ', before your answers.)';
        }
        const packageSlot = session.node.querySelector('.dtr-package-slot');
        if (packageSlot) {
            fill(packageSlot, packageList());
        }
        const nextSlot = session.node.querySelector('.dtr-next-slot');
        if (nextSlot) {
            fill(nextSlot, nextStepLine());
        }
        withdrawFromResult();
    }

    /** The questionnaires with answers the server has not seen, while the latest result offers PAS. */
    function withheld() {
        const latest = session.latest;
        if (!latest || !latest.nextStep || latest.nextStep.action !== 'START_PAS') {
            return [];
        }
        return entries.map(entry => ({entry, ids: unseen(entry)})).filter(pending => pending.ids.length > 0);
    }

    function withheldWords(pending) {
        return [el('strong', null, '"Continue in PAS" is withdrawn until the response is completed again. '),
            'Answers were changed after the response was completed, and PAS would receive the response as last completed, without them. Changed: ',
            pending.map((one, index) => [index > 0 ? ' ' : null, entries.length > 1 ? ['In ', one.entry.view.subject, ': '] : null, named(one.entry, one.ids), '.']),
            ' Choose ', pending.some(one => one.entry.view.kind === 'ADAPTIVE' && one.entry.loopJson)
                ? '"' + buttonName.loop() + '"' : '"' + buttonName.complete() + '"',
            ' first, or put the answers back as they were.'];
    }

    // The control in the result's own next-step row, which this session keeps current: the
    // hand-over to PAS, or, while it would carry answers older than the ones on screen, the reason
    // it is withdrawn. The result view places it through the workbench's nextStepControl hook.
    const resultSlot = el('div', {class: 'dtr-result-control'});

    /**
     * The tab's own control for the next step of a DTR result: the hand-over to PAS, kept current
     * as answers change. Null for any other step, which the shared control covers.
     */
    function resultControl(step) {
        if (!step || step.action !== 'START_PAS') {
            return null;
        }
        withdrawFromResult();
        return {node: resultSlot};
    }

    function withdrawFromResult() {
        const latest = session.latest;
        if (!latest || !latest.nextStep || latest.nextStep.action !== 'START_PAS') {
            clear(resultSlot);
            return;
        }
        const pending = withheld();
        const control = pending.length === 0 && latest.envelope && hooks.nextControl ? hooks.nextControl(latest.nextStep, latest.envelope) : null;
        fill(resultSlot,
            control ? button(control.label, control.run, {class: 'button--primary'}) : null,
            pending.length > 0 ? el('p', {class: 'dtr-withdrawn'}, withheldWords(pending), ' The questions are below.') : null);
    }

    function packageList() {
        return el('div', {class: 'dtr-package'},
            el('p', {class: 'sub-heading'}, 'This package holds ' + entries.length + ' questionnaires. PAS is offered when every one has a completed response.'),
            el('ol', {class: 'dtr-package__list'}, entries.map(entry => {
                const state = stateOf(entry);
                const current = entry.index === session.active;
                return el('li', {class: 'dtr-package__row' + (current ? ' dtr-package__row--open' : '')},
                    current
                        ? el('span', {class: 'dtr-package__name', aria: {current: 'true'}}, entry.view.subject)
                        : button(entry.view.subject, () => open(entry),
                            {class: 'button--text', aria: {label: 'Open ' + entry.view.subject}}),
                    ' ', el('span', {class: 'dtr-kind dtr-kind--' + String(entry.view.kind).toLowerCase()}, kindWord(entry.view)),
                    ' ', el('span', {class: 'dtr-progress dtr-progress--' + state.code}, state.words),
                    current ? el('span', {class: 'quiet small'}, ' open below') : null);
            })));
    }

    /**
     * What is still to do, in the words of the latest DTR result, with the button that takes the
     * step when it is one this tool takes from here (PAS, once every response is complete). It is
     * the same button the result offers, so the action keeps one name.
     */
    function nextStepLine() {
        const latest = session.latest;
        if (!latest || !latest.nextStep) {
            return null;
        }
        const step = latest.nextStep;
        const pending = withheld();
        const control = pending.length === 0 && latest.envelope && hooks.nextControl ? hooks.nextControl(step, latest.envelope) : null;
        // The server's next step names the button by the label of its action, which is the button's
        // own name on this tab, so only where the button is is said here.
        const here = step.action === 'SUBMIT_RESPONSE' || step.action === 'ANSWER_NEXT_QUESTION' ? 'The button is below the questions.' : null;
        return el('div', {class: 'dtr-next'},
            el('p', null, el('span', {class: 'quiet'}, 'Still to do, as the latest result says: '), el('strong', null, step.headline),
                step.reason ? el('span', {class: 'quiet'}, ' ' + step.reason) : null, here ? [' ', here] : null),
            pending.length > 0 ? el('p', {class: 'dtr-withdrawn'}, withheldWords(pending)) : null,
            control ? el('div', {class: 'button-row'}, button(control.label, control.run, {class: 'button--primary'})) : null);
    }

    /** Opens another questionnaire of the package. What was typed into the one left stays with it. */
    function open(entry) {
        session.active = entry.index;
        render();
        const title = session.node.querySelector('.dtr-q__title');
        if (title) {
            title.scrollIntoView({block: 'start'});
            // The button that was pressed is gone with the list redrawn, so the focus goes to the
            // questionnaire it opened, from where its questions are the next stops of the Tab key.
            title.focus({preventScroll: true});
        }
    }

    /** Which questionnaire the result at the top is about, when it is not the one open. */
    function aboutLine(entry) {
        if (entries.length < 2 || session.latestEntry === null || session.latestEntry === entry.index) {
            return null;
        }
        const other = entries[session.latestEntry];
        return el('p', {class: 'dtr-about'}, el('strong', null, 'The result at the top is about ', other.view.subject, ', '),
            'the questionnaire the last step was sent for, not about this one. ', entry.view.subject, ' is ', stateOf(entry).words, '.');
    }

    function questionnaireView(entry) {
        const view = entry.current;
        const usable = (entry.view.problems || []).length === 0;
        const kind = entry.view.kind;
        const adaptive = kind === 'ADAPTIVE';
        const parts = [];
        parts.push(aboutLine(entry));
        parts.push(el('div', {class: 'dtr-q__head', dataset: {field: adaptive ? 'questionnaireResponse' : 'questionnaire'}},
            el('p', {class: 'dtr-q__title', tabIndex: -1}, entry.view.subject, ' ',
                el('span', {class: 'dtr-kind dtr-kind--' + String(kind).toLowerCase()}, kindWord(entry.view))),
            el('p', {class: 'small quiet'}, entry.view.canonical ? code(entry.view.canonical) : 'It has no url.', ' ', entry.view.kindBasis || null)));
        parts.push(notApplied(entry.view));
        if (!usable) {
            parts.push(el('p', null, el('strong', null, 'This questionnaire cannot be answered.'),
                ' The problems the server found, and its items as returned, are in the detail below.'));
            return el('div', {class: 'dtr-q'}, parts);
        }
        parts.push(stepState(entry), el('div', {class: 'dtr-next-slot'}));
        const required = new Set(entry.form ? entry.form.requiredUnanswered.map(ref => ref.linkId) : []);
        const undetermined = new Map(entry.form ? entry.form.undetermined.map(ref => [ref.linkId, ref]) : []);
        const changes = entry.form ? entry.form.changes : null;
        const items = view.items || [];
        if (items.length === 0) {
            parts.push(el('p', {class: 'dtr-q__empty'}, !adaptive ? 'This questionnaire holds no items to answer.'
                : entry.form ? 'The service asked no question at the last step that worked.'
                    : 'The service has asked nothing yet. The first step goes with no answers, and the service answers with its first question.'));
        } else {
            parts.push(el('div', {class: 'dtr-q__items', dataset: {field: 'answers'}}, itemList(items, {
                interactive: true,
                answers: entry.answers,
                inResponse: responseByLinkId(entry.response),
                required,
                undetermined,
                asked: entry.form ? new Set(entry.form.asked.map(ref => ref.linkId)) : null,
                appeared: changes && changes.compared ? new Set(changes.appeared.map(ref => ref.linkId)) : null,
                onAnswer(linkId, values) {
                    if (values === null) {
                        entry.answers.delete(linkId);
                    } else {
                        entry.answers.set(linkId, values);
                    }
                    entry.touched = true;
                    sync();
                    drawCount();
                }
            })));
        }
        const changedNote = el('p', {class: 'dtr-changed', role: 'status'});
        const countNote = el('p', {class: 'small'});
        const drawCount = () => {
            const given = [...entry.answers.values()].filter(values => values !== null).length;
            countNote.textContent = given === 0 ? 'No answer is typed for the next step yet.'
                : given + (given === 1 ? ' question has' : ' questions have') + ' an answer typed for the next step.';
        };
        drawCount();
        // Above the questions, where it is read before the states it is about.
        parts.splice(parts.length - 1, 0, changedNote);
        if (adaptive) {
            parts.push(stepList(entry));
        }
        parts.push(actions(entry, countNote));
        return el('div', {class: 'dtr-q'}, parts);
    }

    /** Where the questionnaire stands after the last step sent for it, and what changed, as the server said. */
    function stepState(entry) {
        const adaptive = entry.view.kind === 'ADAPTIVE';
        const parts = [];
        const sent = entry.steps.length;
        const last = sent > 0 ? entry.steps[sent - 1] : null;
        if (last) {
            // The verdict of the step is at the top of the result. It is repeated here in one quiet
            // line, because this is where the tester is working when the answer arrives.
            parts.push(el('p', {class: 'dtr-laststep', tabIndex: -1}, toneMark(last.tone), ' ',
                el('span', {class: 'quiet'}, (last.kind === 'loop' ? 'Step ' + last.number : 'The response step') + (last.worked ? ' answered: ' : ' did not work: ')),
                last.headline, ' ', el('span', {class: 'quiet small'}, 'The whole result is above.')));
        }
        if (adaptive) {
            const loops = entry.steps.filter(step => step.kind === 'loop').length;
            const responses = sent - loops;
            const missing = entry.steps.filter(step => !step.recorded).length;
            parts.push(el('p', {class: 'dtr-steps__count'},
                el('strong', null, sent === 0 ? 'No step sent yet.'
                    : [count(loops, 'step') + ' sent to the service',
                        responses > 0 ? ', ' + count(responses, 'response step') + ' put together in this tool' : null,
                        missing > 0 ? [', ', el('span', {class: 'dtr-notrecorded'}, missing === sent && sent > 1 ? 'none of them recorded' : missing + ' of them NOT recorded'),
                            ' in the ledger'] : null, '.']),
                ' Each step to the service is a call to the payer, and the ledger keeps a line for each step it recorded, as the step list says. '
                    + 'The service, not this tool, decides when the response is complete.'));
        }
        if (entry.lastFailed) {
            parts.push(el('div', {class: 'dtr-failed', role: 'alert'},
                el('p', null, el('strong', null, 'The last step failed: '), entry.lastFailed.headline, ' Run ', code(entry.lastFailed.runId), '.'),
                el('p', null, 'Everything answered so far is kept below. ', entry.lastFailed.kind === 'loop'
                    ? '"' + buttonName.loop() + '" sends the same step again.'
                    : '"' + buttonName.complete() + '" puts it together again.')));
        }
        const form = entry.form;
        if (form) {
            const status = form.status || {};
            parts.push(el('p', {class: 'dtr-standing'},
                entry.lastFailed ? 'After the last step that worked: ' : 'After the last step: ', el('strong', null, String(form.questionCount)), form.questionCount === 1 ? ' question, ' : ' questions, ',
                el('strong', null, String(form.answeredCount)), ' answered, completion status ',
                status.presence === 'VALUE' ? code(status.code) : el('span', {class: 'presence presence--absent'}, 'not returned'), '. ',
                form.complete ? el('strong', null, 'The server judged the response complete.') : 'The server did not judge the response complete.'));
            if ((form.requiredUnanswered || []).length > 0) {
                parts.push(el('p', {class: 'dtr-mark dtr-mark--required'}, el('strong', null, 'Required and unanswered, each marked at its question: '),
                    form.requiredUnanswered.map((ref, index) => [index > 0 ? '; ' : null, code(ref.linkId), ref.text ? ' ' + ref.text : null])));
            }
            if ((form.undetermined || []).length > 0) {
                parts.push(el('p', {class: 'dtr-mark dtr-mark--undetermined'}, el('strong', null, 'Not judged, because a rule this tool does not apply decides whether they still need an answer: '),
                    form.undetermined.map((ref, index) => [index > 0 ? '; ' : null, code(ref.linkId), ref.text ? ' ' + ref.text : null])));
            }
            if (form.changes) {
                // After a failed step this is still about the last step that worked, and says so.
                parts.push(entry.lastFailed ? el('p', {class: 'sub-heading'}, 'At the last step that worked') : null, changesBlock(form.changes));
            }
        }
        return parts.length > 0 ? el('div', {class: 'dtr-state-block'}, parts) : null;
    }

    function stepList(entry) {
        if (entry.steps.length === 0) {
            return null;
        }
        return el('details', {class: 'fold dtr-steps'},
            el('summary', null, 'The steps sent for this questionnaire', el('span', {class: 'quiet'}, String(entry.steps.length))),
            el('ol', {class: 'dtr-steps__list'}, entry.steps.map(step => el('li', null,
                toneMark(step.tone), ' ', el('strong', null, step.outcome), ' ', code(step.runId), ' ',
                step.recorded ? el('span', {class: 'quiet'}, 'recorded') : el('strong', {class: 'dtr-notrecorded'}, 'NOT recorded'),
                step.kind === 'response' ? el('span', {class: 'quiet'}, ', response step, nothing sent') : null,
                step.form ? el('div', {class: 'small'}, count(step.form.questionCount, 'question') + ', ' + step.form.answeredCount + ' answered. ',
                    step.form.changes ? step.form.changes.summary : null) : el('div', {class: 'small'}, step.headline)))));
    }

    function count(number, noun) {
        return number + ' ' + noun + (number === 1 ? '' : 's');
    }

    function actions(entry, countNote) {
        const kind = entry.view.kind;
        const loop = kind === 'ADAPTIVE' || kind === 'UNDETERMINED';
        const respond = kind !== 'ADAPTIVE';
        const parts = [countNote];
        if (kind === 'UNDETERMINED') {
            parts.push(el('p', null, 'This tool cannot tell how this questionnaire is meant to be answered, so both ways are offered. Choose the one its '
                + 'description above supports.'));
        }
        parts.push(el('p', {class: 'dtr-expect small'}));
        const row = el('div', {class: 'button-row'});
        if (loop) {
            const send = el('button', {type: 'button', class: 'button button--primary', on: {click: () => hooks.loop(entry)}}, buttonName.loop());
            const preview = button('Show the next-question request', () => hooks.previewLoop(entry));
            hooks.track(send, preview);
            row.append(send, preview);
        }
        if (respond) {
            const complete = el('button', {type: 'button', class: 'button ' + (loop ? 'button--quiet' : 'button--primary'), on: {click: () => hooks.complete(entry)}},
                buttonName.complete());
            hooks.track(complete);
            row.append(complete);
        }
        parts.push(row);
        // A refusal of the step is summed up here, beside the button that sent it.
        parts.push(el('div', {class: 'dtr-step-problem'}));
        if (respond) {
            parts.push(el('p', {class: 'small'}, '"' + buttonName.complete() + '" sends nothing to the payer. The response is put together inside this tool from the '
                + 'answers, and the result says whether it is complete or which required questions are unanswered.'));
        }
        if (loop) {
            parts.push(wrapChoice());
        }
        const node = el('div', {class: 'dtr-actions'}, parts);
        node.querySelectorAll('.dtr-expect').forEach(fillExpect);
        return node;
    }

    /** Which expectation goes with a click on the buttons below it, with the way to change it. */
    function fillExpect(node) {
        const text = hooks.expected ? hooks.expected() : '';
        fill(node, 'Expected outcome sent with the next click: ',
            text ? code(text) : el('span', {class: 'quiet'}, 'none, so that step will be unverified'), ' ',
            hooks.editExpectation ? button('Change it', () => hooks.editExpectation(), {class: 'button--text button--small',
                aria: {label: 'Change the expected outcome of the next step'}}) : null);
    }

    function refreshExpectation() {
        session.node.querySelectorAll('.dtr-expect').forEach(fillExpect);
    }

    /**
     * How the next-question body is shaped. DTR 2.0.1, the version this tool is held to, sends the
     * response by itself, and that stays the default. The wrapped forms are offered for a service
     * that follows a later version, one step at a time.
     */
    function wrapChoice() {
        const name = 'dtr-wrap-' + session.packageRunId;
        const choices = [
            ['', 'The QuestionnaireResponse by itself, as DTR 2.0.1 sends it (the default)'],
            ['questionnaire-response-in', 'Inside a Parameters resource, under questionnaire-response-in, as the DTR 2.2.0 ballot sends it'],
            ['questionnaire-response', 'Inside a Parameters resource, under questionnaire-response, as SDC defines the parameter']
        ];
        return el('details', {class: 'fold dtr-wrap', open: session.wrap !== '', dataset: {field: 'wrapInParameter'}},
            el('summary', null, 'Send the next step in another shape', el('span', {class: 'quiet'}, session.wrap === '' ? 'by itself' : 'wrapped, for the next step only')),
            el('div', {role: 'radiogroup', aria: {label: 'The shape of the next next-question body'}}, choices.map(([value, words], index) => {
                const input = el('input', {type: 'radio', name, id: name + '-' + index, value, checked: session.wrap === value});
                input.addEventListener('change', () => { session.wrap = value; });
                return el('label', {class: 'choice', htmlFor: name + '-' + index}, input, words);
            })),
            el('p', {class: 'small quiet'}, 'A wrapped step is the exception. After one step is sent the choice goes back to the default.'));
    }

    render();
    return session;
}

/** True when a package result holds a questionnaire to answer. */
export function hasQuestionnaires(envelope) {
    return Boolean(envelope && envelope.detail && (envelope.detail.questionnaires || []).length > 0);
}
