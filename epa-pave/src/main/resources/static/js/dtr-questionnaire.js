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

import {el, fill, button, code} from './dom.js';
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
 */
export function createSession(packageEnvelope, hooks) {
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
            changedSince: false,
            touched: false
        };
    });
    const session = {
        packageRunId: packageEnvelope.runId,
        entries,
        active: 0,
        carry: (packageEnvelope.nextStep && packageEnvelope.nextStep.carry) || {},
        latest: {nextStep: packageEnvelope.nextStep, envelope: packageEnvelope},
        node: el('section', {class: 'dtr-work', aria: {label: 'Answering the questionnaires'}}),
        wrap: '',
        render,
        applyResult,
        loopBody,
        responseBody,
        entry: () => entries[session.active]
    };

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
        if (session.wrap !== '') {
            body.wrapInParameter = session.wrap;
        }
        return body;
    }

    /** What the response step posts for this questionnaire. */
    function responseBody(entry) {
        if (entry.view.kind === 'ADAPTIVE' && entry.loopJson) {
            // An adaptive response is completed by the service: the last one it returned goes as it is.
            return {questionnaireResponse: entry.loopJson, answers: postedAnswers(entry)};
        }
        const body = {questionnaire: entry.view.json};
        if (entry.startResponse) {
            body.questionnaireResponse = entry.startResponse.json;
        }
        body.answers = postedAnswers(entry);
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
            form: result.form || null
        });
        if (worked) {
            entry.form = result.form;
            entry.current = (result.questionnaires || [])[0] || entry.current;
            entry.response = (result.questionnaireResponses || [])[0] || entry.response;
            if (kind === 'loop' && entry.response && entry.response.json) {
                entry.loopJson = entry.response.json;
                // The answers went out and are in the response now, where they are shown with their origin.
                entry.answers.clear();
            }
            entry.lastFailed = null;
            entry.changedSince = false;
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
        render();
    }

    function stateOf(entry) {
        const last = entry.steps.length > 0 ? entry.steps[entry.steps.length - 1] : null;
        const lastWorked = [...entry.steps].reverse().find(step => step.worked);
        if (lastWorked && lastWorked.form && lastWorked.form.complete) {
            return entry.changedSince ? {code: 'changed', words: 'complete as last sent, answers changed since'} : {code: 'complete', words: 'complete'};
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
            el('p', {class: 'dtr-honest'}, 'You type each answer; nothing is read from a patient record, and there is no SMART launch here. '
                + 'Answers marked as pre-populated came from the payer\'s service.'),
            entries.length > 1 ? packageList() : null,
            entry ? questionnaireView(entry) : null);
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
                    ' ', el('span', {class: 'dtr-kind dtr-kind--' + String(entry.view.kind).toLowerCase()}, kindWord(entry.view.kind)),
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
        const control = latest.envelope && hooks.nextControl ? hooks.nextControl(step, latest.envelope) : null;
        return el('div', {class: 'dtr-next'},
            el('p', null, el('span', {class: 'quiet'}, 'Still to do, as the latest result says: '), el('strong', null, step.headline),
                step.reason ? el('span', {class: 'quiet'}, ' ' + step.reason) : null),
            control ? el('div', {class: 'button-row'}, button(control.label, control.run, {class: 'button--primary'})) : null);
    }

    /** Opens another questionnaire of the package. What was typed into the one left stays with it. */
    function open(entry) {
        session.active = entry.index;
        render();
        const title = session.node.querySelector('.dtr-q__title');
        if (title) {
            title.scrollIntoView({block: 'start'});
        }
    }

    function questionnaireView(entry) {
        const view = entry.current;
        const usable = (entry.view.problems || []).length === 0;
        const kind = entry.view.kind;
        const adaptive = kind === 'ADAPTIVE';
        const parts = [];
        parts.push(el('div', {class: 'dtr-q__head', dataset: {field: adaptive ? 'questionnaireResponse' : 'questionnaire'}},
            el('p', {class: 'dtr-q__title'}, entry.view.subject, ' ',
                el('span', {class: 'dtr-kind dtr-kind--' + String(kind).toLowerCase()}, kindWord(kind))),
            el('p', {class: 'small quiet'}, entry.view.canonical ? code(entry.view.canonical) : 'It has no url.', ' ', entry.view.kindBasis || null)));
        parts.push(notApplied(entry.view));
        if (!usable) {
            parts.push(el('p', null, el('strong', null, 'This questionnaire cannot be answered.'),
                ' The problems the server found, and its items as returned, are in the detail below.'));
            return el('div', {class: 'dtr-q'}, parts);
        }
        parts.push(stepState(entry), nextStepLine());
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
                    if (entry.form && !entry.changedSince) {
                        entry.changedSince = true;
                        drawChanged();
                    }
                    drawCount();
                }
            })));
        }
        const changedNote = el('p', {class: 'dtr-changed', role: 'status'});
        const countNote = el('p', {class: 'small', role: 'status', aria: {live: 'polite'}});
        const drawChanged = () => {
            changedNote.textContent = entry.changedSince && !adaptive
                ? 'You have changed answers since the server last saw them. Which questions are enabled and which are required is as of then: '
                    + 'complete the response again to have them evaluated.'
                : '';
        };
        const drawCount = () => {
            const given = [...entry.answers.values()].filter(values => values !== null).length;
            countNote.textContent = given === 0 ? 'No answer is typed for the next step yet.'
                : given + (given === 1 ? ' question has' : ' questions have') + ' an answer typed for the next step.';
        };
        drawChanged();
        drawCount();
        parts.push(changedNote);
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
                el('span', {class: 'quiet'}, (last.kind === 'loop' ? 'Step ' + last.number + ' answered: ' : 'The response step answered: ')),
                last.headline, ' ', el('span', {class: 'quiet small'}, 'The whole result is above.')));
        }
        if (adaptive) {
            parts.push(el('p', {class: 'dtr-steps__count'},
                el('strong', null, sent === 0 ? 'No step sent yet.' : sent + (sent === 1 ? ' step sent.' : ' steps sent.')),
                ' Each step is a call to the payer and a line in the ledger. The service, not this tool, decides when the response is complete.'));
        }
        if (entry.lastFailed) {
            parts.push(el('div', {class: 'dtr-failed', role: 'alert'},
                el('p', null, el('strong', null, 'The last step failed: '), entry.lastFailed.headline, ' Run ', code(entry.lastFailed.runId), '.'),
                el('p', null, 'Everything answered so far is kept below. ', entry.lastFailed.kind === 'loop'
                    ? '"Send answers and get the next question" sends the same step again.'
                    : '"Complete the response" puts it together again.')));
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
                parts.push(changesBlock(form.changes));
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
                toneMark(step.tone), ' ', el('strong', null, step.outcome), ' ', code(step.runId),
                step.kind === 'response' ? el('span', {class: 'quiet'}, ' response step, nothing sent') : null,
                step.form ? el('div', {class: 'small'}, step.form.questionCount + ' questions, ' + step.form.answeredCount + ' answered. ',
                    step.form.changes ? step.form.changes.summary : null) : el('div', {class: 'small'}, step.headline)))));
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
        const row = el('div', {class: 'button-row'});
        if (loop) {
            const send = el('button', {type: 'button', class: 'button button--primary', on: {click: () => hooks.loop(entry)}}, 'Send answers and get the next question');
            const preview = button('Show the next-question request', () => hooks.previewLoop(entry));
            hooks.track(send, preview);
            row.append(send, preview);
        }
        if (respond) {
            const complete = el('button', {type: 'button', class: 'button ' + (loop ? 'button--quiet' : 'button--primary'), on: {click: () => hooks.complete(entry)}},
                'Complete the response');
            hooks.track(complete);
            row.append(complete);
        }
        parts.push(row);
        if (respond) {
            parts.push(el('p', {class: 'small'}, '"Complete the response" sends nothing to the payer. The response is put together inside this tool from the '
                + 'answers, and the result says whether it is complete or which required questions are unanswered.'));
        }
        if (loop) {
            parts.push(wrapChoice());
        }
        return el('div', {class: 'dtr-actions'}, parts);
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
