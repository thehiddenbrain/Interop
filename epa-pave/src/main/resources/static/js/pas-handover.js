// What an earlier step handed to the PAS tab, shown as what it is before anything is sent.
//
// A hand-over is the previous result's nextStep.carry with the case that result was produced for.
// It is posted back to the server unchanged, with one exception the tester makes on purpose: the
// questionnaire answers completed in DTR can be left out of this submission. They are never
// dropped silently and never sent silently: the panel says which of the two will happen.
//
// Values carried for one case must not go out with another, so when the case on screen is no
// longer the case of the hand-over, nothing of it is sent and the panel says so.

import {el, fill, clear, button, code, facts} from './dom.js';
import {runLink} from './history-common.js';
import {indent} from './own-request.js';
import {answersTable} from './pas-answers.js';

const ANSWERS = 'questionnaireResponse';

export function createHandover(spec) {
    let handed = null;
    let dismissed = false;
    let sendAnswers = true;
    const node = el('section', {class: 'case-group pas-handover', hidden: true, dataset: {field: 'carry.' + ANSWERS},
        aria: {labelledby: 'pas-handover-title'}});

    function sameCase(one, other) {
        if (!one) {
            return true;
        }
        return one.memberId === other.memberId && one.npi === other.npi && JSON.stringify(one.codes) === JSON.stringify(other.codes);
    }

    function applies() {
        return Boolean(handed) && !dismissed && sameCase(handed.forCase, spec.caseNow());
    }

    function answers() {
        const values = handed && handed.carry ? handed.carry[ANSWERS] : null;
        return Array.isArray(values) ? values : [];
    }

    function caseLine(summary) {
        const none = text => el('span', {class: 'presence presence--none'}, text);
        return [summary.memberId ? code(summary.memberId) : none('no member ID'), ', NPI ', summary.npi ? code(summary.npi) : none('none'),
            ', codes ', summary.codes.length > 0 ? summary.codes.map((value, index) => [index > 0 ? ', ' : null, code(value)]) : none('none')];
    }

    function draw() {
        if (!handed || dismissed) {
            node.hidden = true;
            clear(node);
            return;
        }
        node.hidden = false;
        const carry = handed.carry || {};
        const responses = answers();
        const others = Object.entries(carry).filter(([key]) => key !== ANSWERS);
        const changed = !sameCase(handed.forCase, spec.caseNow());
        const parts = [
            el('h2', {class: 'case-legend', id: 'pas-handover-title'}, 'Handed on from ' + (handed.fromProtocol || 'an earlier step')),
            el('p', {class: 'small'}, 'From run ', handed.fromRunId ? runLink(handed.fromRunId) : 'not stated',
                handed.forCase ? [', for the case ', caseLine(handed.forCase)] : null, '.')
        ];
        if (changed) {
            parts.push(el('p', {class: 'pas-handover__changed'}, el('strong', null, 'The case on screen has changed since that run.'),
                ' Nothing that was handed on is sent with a request for this case. Put the case back, or start from the earlier step again.'));
        }
        if (responses.length > 0) {
            const box = el('input', {type: 'checkbox', id: 'pas-send-answers', checked: sendAnswers, disabled: changed});
            box.addEventListener('change', () => { sendAnswers = box.checked; draw(); spec.changed(); });
            parts.push(el('div', {class: 'pas-handover__answers'},
                el('p', {class: sendAnswers && !changed ? 'pas-handover__going' : 'pas-handover__staying'},
                    el('strong', null, sendAnswers && !changed
                        ? (responses.length === 1 ? 'The answers completed in DTR will travel with this request.'
                            : 'The ' + responses.length + ' questionnaire responses completed in DTR will travel with this request.')
                        : 'The answers completed in DTR will not travel with this request.')),
                el('label', {class: 'choice', htmlFor: 'pas-send-answers'}, box, 'Send the completed answers with this request'),
                responses.map((text, index) => el('details', {class: 'fold'},
                    el('summary', null, responses.length === 1 ? 'Look at the answers' : 'Look at questionnaire response ' + (index + 1)),
                    readable(text),
                    el('details', {class: 'fold'}, el('summary', null, 'As DTR handed it on', el('span', {class: 'quiet'}, String(text).length + ' characters')),
                        el('pre', {class: 'body-text', tabIndex: 0, aria: {label: 'A completed questionnaire response'}}, pretty(text))))),
                el('p', {class: 'small'}, '"Show the request" lists the answers as they stand inside the request body that would be sent.')));
        }
        if (others.length > 0) {
            parts.push(el('p', {class: 'small'}, changed ? 'What was handed on:' : 'What goes with the next request, unchanged:'),
                facts(others.map(([key, values]) => [key, valuesOf(key, values)]), 'facts--ruled pas-handover__facts'));
        }
        parts.push(el('p', null, button('Set this hand-over aside', () => { dismissed = true; draw(); spec.changed(); }, {class: 'button--text'})));
        fill(node, parts);
    }

    function valuesOf(key, values) {
        const list = (Array.isArray(values) ? values : [values]).filter(value => value !== null && value !== undefined && value !== '');
        if (list.length === 0) {
            return el('span', {class: 'presence presence--none'}, 'none');
        }
        if (key === 'runId') {
            return list.map((value, index) => [index > 0 ? ', ' : null, runLink(String(value))]);
        }
        // A long value, such as a whole questionnaire, would fill the pane. It is folded, and shown whole when asked for.
        return list.map((value, index) => [index > 0 ? ', ' : null, String(value).length > 160
            ? el('details', {class: 'fold'}, el('summary', null, 'Look at it', el('span', {class: 'quiet'}, String(value).length + ' characters')),
                el('pre', {class: 'body-text', tabIndex: 0, aria: {label: 'The value handed on as ' + key}}, pretty(String(value))))
            : code(String(value))]);
    }

    return {
        node,
        /** Takes the hand-over the page holds for this tab. A new one is shown afresh, with its answers set to travel. */
        take(payload) {
            if (payload !== handed) {
                handed = payload;
                dismissed = false;
                sendAnswers = true;
            }
            draw();
        },
        redraw: draw,
        /** The hand-over in force, or null: none, set aside, or for another case. */
        current: () => (applies() ? handed : null),
        /** How many completed questionnaire responses the hand-over in force carries, and whether the tester chose to leave them out. */
        answersState: () => ({handed: applies() ? answers().length : 0, withheld: applies() && answers().length > 0 && !sendAnswers}),
        /**
         * What a submission posts as carry: the hand-over unchanged, without the answers when the
         * tester chose to leave them out. Undefined when nothing applies to the case on screen.
         */
        carry() {
            if (!applies()) {
                return undefined;
            }
            const out = {...(handed.carry || {})};
            if (!sendAnswers) {
                delete out[ANSWERS];
            }
            return out;
        }
    };
}

/** The answers of a handed-on questionnaire response as a table, or a sentence when the text is not a QuestionnaireResponse. */
function readable(text) {
    let parsed = null;
    try {
        parsed = JSON.parse(text);
    } catch (notJson) {
        return el('p', {class: 'small'}, 'It is not JSON, so its answers cannot be listed. It is shown below as it came.');
    }
    if (!parsed || parsed.resourceType !== 'QuestionnaireResponse') {
        return el('p', {class: 'small'}, 'It is not a QuestionnaireResponse, so its answers cannot be listed. It is shown below as it came.');
    }
    return answersTable(parsed, true);
}

/** A questionnaire response is JSON text. It is indented for reading; anything that is not JSON is shown as it came. */
function pretty(text) {
    try {
        JSON.parse(text);
        return indent(String(text));
    } catch (notJson) {
        return String(text);
    }
}
