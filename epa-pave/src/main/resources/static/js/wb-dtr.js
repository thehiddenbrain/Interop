// Step 2 of the Workbench, the questionnaire (plan 3.2, step 2; section 4). It fetches the payer's
// questionnaire package for the case, with what step 1 handed on, draws each questionnaire as a
// form, steps through an adaptive questionnaire one turn at a time, completes a static one, and
// hands the completed response to step 3 through ctx.
//
// The server keeps nothing between the steps of a questionnaire, so this module holds what the next
// post needs, in the page's memory only, until the next package or "Start a new case": each
// questionnaire as the package returned it, the response the package carried for it, the response
// the latest turn returned, and the hand-over (nextStep.carry) of the latest result, posted back
// unchanged. What a post carries follows the DTR service:
//
//   a turn of an adaptive questionnaire   the response the last turn returned (the package's at the
//                                         first turn, else the questionnaire) and the answers given since
//   completing a static questionnaire     the questionnaire, the response the package carried, and
//                                         every answer the tester gave
//
// Which questions are enabled, which are required and unanswered, and whether a response is complete
// are the server's answers to those posts; nothing here works them out. What this module does keep
// is which answers the server has seen: while a typed answer differs from the ones posted with the
// last step that worked, "Continue to Submit" is withdrawn, because Submit would carry the response
// as last completed, without them.
//
// Answers from the case (plan R-18): after a package or a turn puts questions on screen, the page
// posts the questionnaire and the case to the server, which says which questions the case answers.
// They go only into fields that hold nothing, never over the payer's answer or the tester's, each
// marked with the server's words, and are sent as the tester's own answers unless changed or cleared.
// A question the tester took such an answer out of is never filled again.

import {el, chip, kv, fold, fill, clear, button, firstSentence} from './dom.js';
import {call} from './api.js';
import {vocabulary} from './words.js';
import {withoutMasks, maskedLine, notHandedOnWords} from './masks.js';
import {panelHead, busyLine, sendRun, failurePanel} from './wb-run.js';
import {showPreview} from './wb-preview.js';
import {wireFold} from './result.js';
import {responseDetail, detailHint} from './detail.js';
import {flatItems, heldByLinkId, questionField, itemLine, formChips, linkIdOf, isDateQuestion} from './wb-dtr-items.js';

// The form's button carries the server's words for the next action it takes (plan 7.4, the
// vocabulary's nextActions), so a next step and its button are one name. Until the vocabulary has
// answered, it says what it does in words of this page's own.
const LOOP_ACTION = 'ANSWER_NEXT_QUESTION';
const RESPOND_ACTION = 'SUBMIT_RESPONSE';
const UNTIL_WORDS = {ANSWER_NEXT_QUESTION: 'Send the answers', SUBMIT_RESPONSE: 'Put the response together'};
const WITHDRAWN = 'Complete the response again before Submit.';
const DATE_LINE = 'Date questions take the date of service from the Service card.';
const CASE_PATH = '/api/dtr/case-answers';
// The quiet time after the last change of the case before the answers from it are asked for again.
const CASE_QUIET = 500;
const SMART = 'There is no SMART launch here and no patient record: answers come from the case where a question asks for it, '
    + 'and you type the rest.';

// A questionnaire url is cut on the handed-on line; the whole url is its title.
const URL_SHOWN = 60;

/**
 * A step option through ctx.option. Should reading it fail in the Workbench itself, the value is read
 * from the step's own options row, where the tester typed it, so a defect there neither stops the
 * step nor drops what was typed.
 */
function optionOf(ctx, name) {
    try {
        return ctx.option(name);
    } catch (defect) {
        const row = typeof ctx.optionsRow === 'function' ? ctx.optionsRow() : null;
        const field = row ? row.querySelector('[data-case-field="' + name + '"] input, [data-case-field="' + name + '"] select') : null;
        if (field) {
            return String(field.value || '').trim();
        }
        throw defect;
    }
}

/** The parts of ctx.request() every DTR call takes; the DTR requests are read strictly, so nothing else goes. */
export function caseBody(request, own) {
    const body = {
        member: request.member,
        memberOverrides: request.memberOverrides,
        provider: request.provider,
        providerOverrides: request.providerOverrides,
        procedureCodes: request.procedureCodes,
        chainId: request.chainId,
        configEpoch: request.configEpoch
    };
    for (const name of ['dateOfService', 'testerRef', 'note']) {
        if (request[name]) {
            body[name] = request[name];
        }
    }
    if (own && own.expected) {
        body.expected = own.expected;
    }
    return body;
}

/** The package request: the case, the step's options and what step 1 handed on. A scenario on mock only. */
export function packageBody(request, own, carry) {
    const body = caseBody(request, own);
    if (own.questionnaireUrl) {
        body.questionnaires = [own.questionnaireUrl];
    }
    if (own.coverageAssertionId) {
        body.coverageAssertionId = own.coverageAssertionId;
    }
    if (carry && Object.keys(carry).length > 0) {
        body.carry = carry;
    }
    if (own.mock && own.scenarioId) {
        body.scenarioId = own.scenarioId;
    }
    return body;
}

/**
 * The questionnaires of a package result, each with what its next post needs. Returns null when
 * the result holds no questionnaire.
 */
export function createSession(envelope) {
    const detail = (envelope && envelope.detail) || {};
    const views = Array.isArray(detail.questionnaires) ? detail.questionnaires : [];
    if (views.length === 0) {
        return null;
    }
    const responses = Array.isArray(detail.questionnaireResponses) ? detail.questionnaireResponses : [];
    const action = envelope.nextStep ? envelope.nextStep.action : null;
    const split = withoutMasks(envelope.nextStep && envelope.nextStep.carry);
    return {
        packageEnvelope: envelope,
        carry: split.carry,
        masked: split.masked,
        labels: (envelope.nextStep && envelope.nextStep.carryLabels) || {},
        latest: null,
        active: 0,
        entries: views.map((view, index) => {
            const start = view.arrivedWith ? responses.find(response => response.foundAt === view.arrivedWith) || null : null;
            return {
                index,
                view,
                current: view,
                start,
                response: start,
                loopJson: null,
                // The kind decides the one button: an adaptive questionnaire takes turns, a static one is completed.
                // One whose kind could not be told takes the way the package's next step names.
                loop: view.kind === 'ADAPTIVE' || (view.kind !== 'STATIC' && action === 'ANSWER_NEXT_QUESTION'),
                answers: new Map(),
                baseline: new Map(),
                inFlight: new Map(),
                form: null,
                steps: [],
                // Answers from the case: what went into each field, and the questions the tester took one out of.
                fromCase: new Map(),
                left: new Set(),
                caseAsked: false
            };
        })
    };
}

function snapshot(answers) {
    const out = new Map();
    for (const [linkId, values] of answers) {
        out.set(linkId, JSON.stringify(values));
    }
    return out;
}

/** The linkIds whose typed answer differs from what went with the last step that worked. */
export function unseen(entry) {
    const now = snapshot(entry.answers);
    const ids = new Set([...now.keys(), ...entry.baseline.keys()]);
    return [...ids].filter(linkId => now.get(linkId) !== entry.baseline.get(linkId));
}

function postedAnswers(entry) {
    return [...entry.answers].map(([linkId, values]) => ({linkId, values}));
}

/** What a turn of the adaptive loop posts for the questionnaire, beside the case. */
export function loopPart(entry) {
    const part = {};
    if (entry.loopJson) {
        part.questionnaireResponse = entry.loopJson;
    } else if (entry.start && entry.start.json) {
        part.questionnaireResponse = entry.start.json;
    } else {
        part.questionnaire = entry.view.json;
    }
    part.answers = postedAnswers(entry);
    return part;
}

/** What completing the questionnaire posts, beside the case. */
export function responsePart(entry) {
    if (entry.loop && entry.loopJson) {
        // An adaptive response is completed by the service: the last one it returned goes as it is.
        return {questionnaireResponse: entry.loopJson, answers: postedAnswers(entry)};
    }
    const part = {questionnaire: entry.view.json};
    if (entry.start && entry.start.json) {
        part.questionnaireResponse = entry.start.json;
    }
    part.answers = postedAnswers(entry);
    return part;
}

/**
 * Takes in the result of a step sent for a questionnaire. A result that says where the form stands
 * is a step that worked; anything else failed, and everything answered so far stays.
 */
export function applyResult(session, entry, kind, envelope) {
    const detail = envelope.detail || {};
    const worked = Boolean(detail.form);
    entry.steps.push({kind, envelope, worked, sent: envelope.answeredBy !== 'NOT_SENT'});
    if (worked) {
        entry.form = detail.form;
        entry.current = (detail.questionnaires || [])[0] || entry.current;
        entry.baseline = entry.inFlight;
        if (kind === 'loop') {
            entry.response = (detail.questionnaireResponses || [])[0] || entry.response;
            if (entry.response && entry.response.json) {
                entry.loopJson = entry.response.json;
            }
            // The answers went out and are in the response now, where the form shows them.
            entry.answers.clear();
            entry.baseline = new Map();
            // The turn may ask new questions, which the case may answer.
            entry.caseAsked = false;
        }
    }
    const carried = envelope.nextStep ? envelope.nextStep.carry : null;
    // A result that hands nothing on does not wipe what the package handed on.
    if (carried && Object.keys(carried).length > 0) {
        const split = withoutMasks(carried);
        session.carry = split.carry;
        session.masked = split.masked;
        session.labels = envelope.nextStep.carryLabels || session.labels;
    }
    session.latest = {entry: entry.index, envelope};
}

/** The turns of an adaptive questionnaire the server sent on, counted from its answers. */
export function turns(entry) {
    return entry.steps.filter(step => step.kind === 'loop' && step.sent).length;
}

/** The one line under the form: "<answered> of <questions> answered", the turn, the completion state. */
export function countLine(entry) {
    const form = entry.form;
    const questions = form ? form.questionCount : entry.current.questionCount;
    const answered = form ? form.answeredCount : entry.response ? entry.response.answeredCount || 0 : 0;
    const parts = [answered + ' of ' + questions + ' answered'];
    if (entry.loop) {
        parts.push('turn ' + turns(entry));
    }
    const status = form ? form.status : entry.response ? entry.response.status : null;
    if (form && form.complete) {
        parts.push(unseen(entry).length > 0 ? 'changed since complete' : 'complete');
    } else if (status && status.presence === 'VALUE') {
        parts.push(String(status.display || status.code).toLowerCase());
    }
    return parts.join(' \u00b7 ');
}

/** The questions that appeared or disappeared at the last step, each as one visible line. */
export function changeLines(entry) {
    const changes = entry.form && entry.form.changes;
    if (!changes || !changes.compared) {
        return [];
    }
    // A question's own text often ends with its question mark; the line then takes no full stop.
    const named = ref => {
        const text = ref.text ? String(ref.text) : String(ref.linkId);
        const last = text.slice(-1);
        return last === '?' || last === '.' || last === '!' ? text : text + '.';
    };
    return (changes.appeared || []).map(ref => 'Now asked: ' + named(ref))
        .concat((changes.disappeared || []).map(ref => 'No longer asked: ' + named(ref)));
}

/** Where a questionnaire stands, for the list of a package that holds several. */
export function stateWords(entry) {
    if (entry.form && entry.form.complete) {
        return unseen(entry).length > 0 ? 'changed since complete' : 'complete';
    }
    return entry.steps.length > 0 || entry.answers.size > 0 ? 'in progress' : 'not started';
}

/**
 * What the page posts to ask which questions the case answers: the questionnaire on screen and the
 * case fields R-18 names; a case field left blank is not sent, so the server never reads it as a value.
 */
export function caseAnswersBody(request, entry) {
    const body = {
        questionnaire: (entry.current && entry.current.json) || entry.view.json,
        member: request.member,
        provider: request.provider,
        procedureCodes: request.procedureCodes || []
    };
    for (const name of ['dateOfService', 'placeOfService', 'levelOfService']) {
        if (request[name]) {
            body[name] = request[name];
        }
    }
    return body;
}

/** mount(root, ctx), as every step module (plan 9.1). show({start: true}) fetches at once (R-27). */
export function mount(root, ctx) {
    const expect = el('select', {class: 'select select--expect', id: 'dtr-expect'}, el('option', {value: ''}, 'Not set'));
    const previewButton = el('button', {type: 'button', class: 'btn', on: {click: () => previewPackage()}}, 'Preview');
    const fetchButton = el('button', {type: 'button', class: 'btn btn--primary', on: {click: () => fetchPackage()}}, 'Fetch questionnaire');
    const toggle = typeof ctx.optionsToggle === 'function' ? ctx.optionsToggle() : null;
    const optionsRow = typeof ctx.optionsRow === 'function' ? ctx.optionsRow() : null;
    const readiness = el('div', {class: 'readiness'});
    const handed = el('div', {class: 'handed-slot'});
    const busy = busyLine();
    const refusal = el('div', {class: 'step-refusal'});
    const packageSlot = el('div', {class: 'panel__section dtr-package'});
    const work = el('div', {class: 'dtr-work'});
    // A step mounted before the Workbench gives it an options row has none; nothing is drawn for it.
    root.append(...[
        panelHead({title: 'Questionnaire', tag: 'DTR', lede: 'Answer the payer\'s questions.', id: 'step-title-2',
            controls: [el('label', {class: 'control-label', for: 'dtr-expect'}, 'Expect'), expect, toggle, previewButton, fetchButton]
                .filter(Boolean)}),
        optionsRow, readiness, handed, busy.node, refusal, packageSlot, work].filter(Boolean));

    let session = null;
    let loaded = null;
    let fields = new Map();
    let handedOn = false;
    let stepButtons = [];
    let caseTimer = null;

    function own() {
        return {
            expected: expect.value,
            questionnaireUrl: optionOf(ctx, 'questionnaireUrl'),
            coverageAssertionId: optionOf(ctx, 'coverageAssertionId'),
            mock: ctx.isMock(),
            scenarioId: ctx.isMock() ? optionOf(ctx, 'scenarioId') : ''
        };
    }

    /** The button of a next action, in the server's words, or this page's own until they are read. */
    function actionWords(action) {
        const found = loaded && Array.isArray(loaded.nextActions) ? loaded.nextActions.find(each => each.code === action) : null;
        return found && found.button ? found.button : UNTIL_WORDS[action];
    }

    function drawWords(words) {
        loaded = words || loaded;
        for (const each of work.querySelectorAll('.dtr-send')) {
            each.textContent = actionWords(each.getAttribute('data-action'));
        }
        const before = expect.value;
        const outcomes = words && words.outcomes && Array.isArray(words.outcomes.DTR) ? words.outcomes.DTR : [];
        fill(expect, el('option', {value: ''}, 'Not set'),
            outcomes.filter(each => each.short).map(each => el('option', {value: each.code}, each.short)));
        expect.value = before;
    }

    function drawReadiness() {
        const blocked = ctx.blocked('DTR_QUESTIONNAIRE_PACKAGE');
        fetchButton.disabled = Boolean(blocked);
        fetchButton.title = blocked || '';
        fill(readiness, blocked ? ctx.readinessLine(blocked) : '');
    }

    /** "From step 1: <url> and coverage assertion id <id>.", or the line that says nothing was handed on. */
    function drawHanded() {
        const carry = ctx.carry(2);
        const parts = [];
        if (carry) {
            const url = first(carry.questionnaire);
            const id = first(carry.coverageAssertionId);
            if (url || id) {
                parts.push(el('p', {class: 'small muted handed'}, 'From step 1: ',
                    url ? el('span', {class: 'mono', title: url}, url.length > URL_SHOWN ? url.slice(0, URL_SHOWN) + '...' : url) : null,
                    url && id ? ' and ' : null,
                    id ? ['coverage assertion id ', el('span', {class: 'mono'}, id)] : null, '.'));
            }
        }
        fill(handed, parts, ctx.handedLine(2));
    }

    async function fetchPackage() {
        const body = packageBody(ctx.request(), own(), ctx.carry(2));
        await sendRun({
            path: '/api/dtr/package', body, busy, busyWords: 'Fetching the questionnaire...',
            buttons: [fetchButton, previewButton], typed: ctx.typedCode(), ctx,
            onResult: envelope => {
                clear(refusal);
                session = envelope.operation === 'DTR_QUESTIONNAIRE_PACKAGE' ? createSession(envelope) : null;
                handOn(null);
                ctx.resulted(envelope);
                drawPackage(envelope);
                drawSession();
                focusFirst();
            },
            onRefused: (error, placed) => {
                ctx.refused(2, error);
                fill(refusal, refusedPanel(error, placed));
            },
            onFailed: error => fill(refusal, failurePanel(error))
        });
    }

    async function previewPackage() {
        await showPreview('/api/dtr/package/preview', packageBody(ctx.request(), own(), ctx.carry(2)),
            error => ctx.placeErrors((error && error.fieldErrors) || []));
    }

    /** The package result: on one line when it holds a questionnaire to answer, else the whole card, which says what came back. */
    function drawPackage(envelope) {
        if (!session) {
            fill(packageSlot, ctx.card(envelope));
            return;
        }
        fill(packageSlot, packageLine(envelope));
    }

    function packageLine(envelope) {
        const status = envelope.wire && envelope.wire.response && typeof envelope.wire.response.status === 'number'
            ? envelope.wire.response.status : null;
        const timing = envelope.timing || {};
        const who = el('span');
        const ledger = envelope.ledger || {};
        const line = el('div', {class: 'result-line'},
            status !== null ? chip('HTTP ' + status) : null,
            who,
            typeof timing.elapsedMillis === 'number' ? chip(timing.elapsedMillis + ' ms') : null,
            el('span', {class: 'result-line__text'}, (envelope.verdict && envelope.verdict.headline) || ''),
            ledger.recorded === true && envelope.runId
                ? el('a', {class: 'small', href: '#audit/runs/' + encodeURIComponent(envelope.runId)}, 'Open in Audit')
                : chip('NOT recorded', {tone: 'fault'}));
        vocabulary().then(words => {
            const answered = words && Array.isArray(words.answeredBy) ? words.answeredBy.find(each => each.code === envelope.answeredBy) : null;
            if (answered && answered.chip) {
                fill(who, chip(answered.chip, {hatched: envelope.answeredBy === 'SCENARIO', dashed: envelope.answeredBy !== 'SCENARIO'}));
            }
        }, () => null);
        const detail = responseDetail(envelope);
        return el('div', null, line,
            ledger.recorded !== true && ledger.message ? el('p', {class: 'small'}, ledger.message) : null,
            detail ? fold({summary: 'Response detail', hint: detailHint(envelope), body: detail}) : null,
            wireFold(envelope.wire));
    }

    /** The questionnaires of the package: the list when there are several, the open one as a form. */
    function drawSession() {
        // With questions on screen the form's own button is the one primary button: fetching again starts over.
        fetchButton.classList.toggle('btn--primary', !session);
        fields = new Map();
        stepButtons = [];
        if (!session) {
            clear(work);
            return;
        }
        const entry = session.entries[session.active];
        fill(work,
            session.entries.length > 1 ? questionnaireList() : null,
            questionnaireForm(entry));
        syncContinue();
        drawCaseState(entry);
        if (!entry.caseAsked) {
            askCase(entry);
        }
    }

    /**
     * Asks which questions of the open questionnaire the case answers and fills the empty fields
     * that may take them. An answer that comes back after another form went on screen is dropped,
     * and asked for again when that questionnaire is open.
     */
    async function askCase(entry) {
        entry.caseAsked = true;
        const problems = Array.isArray(entry.view.problems) ? entry.view.problems : [];
        if (problems.length > 0 || fields.size === 0 || !((entry.current && entry.current.json) || entry.view.json)) {
            return;
        }
        const asked = session;
        const drawn = fields;
        const answer = await call('POST', CASE_PATH, {body: caseAnswersBody(ctx.request(), entry)});
        if (session !== asked || fields !== drawn) {
            entry.caseAsked = false;
            return;
        }
        if (!answer.ok) {
            entry.caseNote = 'No answers were taken from the case: '
                + (answer.error && typeof answer.error.message === 'string' && answer.error.message !== ''
                    ? firstSentence(answer.error.message)[0] : 'PAVE did not answer.');
            drawCaseState(entry);
            return;
        }
        entry.caseNote = null;
        const answers = answer.data && Array.isArray(answer.data.answers) ? answer.data.answers : [];
        for (const one of answers) {
            const linkId = one && typeof one.linkId === 'string' ? one.linkId : null;
            const field = linkId ? fields.get(linkId) : null;
            if (!field || entry.left.has(linkId) || entry.answers.has(linkId) || typeof one.mark !== 'string') {
                continue;
            }
            const values = Array.isArray(one.values) ? one.values.map(String) : [];
            if (field.fillFromCase(values, one.mark)) {
                entry.fromCase.set(linkId, {values, mark: one.mark});
            }
        }
        drawCaseState(entry);
    }

    /** The "from the case" chip of the form's head, and the line for date questions the case cannot fill. */
    function drawCaseState(entry) {
        const chips = work.querySelector('.dtr-case-chip');
        if (chips) {
            const count = [...entry.fromCase.keys()].filter(linkId => fields.has(linkId)).length;
            fill(chips, formChips({}, null, count));
            chips.hidden = count === 0;
        }
        const note = work.querySelector('.dtr-case-note');
        if (note) {
            note.textContent = entry.caseNote || '';
            note.hidden = !entry.caseNote;
        }
        const line = work.querySelector('.dtr-date-line');
        if (line) {
            line.hidden = !datesWaiting(entry);
        }
    }

    /** True when a date question stays empty because the Service card has no date of service. */
    function datesWaiting(entry) {
        if (ctx.request().dateOfService) {
            return false;
        }
        return flatItems(entry.current.items).some(row => {
            const field = row.kind === 'question' && isDateQuestion(row.item) ? fields.get(linkIdOf(row.item)) : null;
            return Boolean(field && field.takesCase());
        });
    }

    function questionnaireList() {
        return el('div', {class: 'panel__section dtr-list', 'aria-label': 'Questionnaires of the package'}, kv(session.entries.map(entry => {
            const open = entry.index === session.active;
            const state = stateWords(entry);
            return {
                key: entry.view.subject || 'Questionnaire ' + (entry.index + 1),
                value: el('span', null, chip(state, {dashed: state === 'not started', tone: state === 'complete' ? 'clear' : null}), ' ',
                    open ? el('span', {class: 'small muted', 'aria-current': 'true'}, 'open below')
                        : button('Open', () => {
                            session.active = entry.index;
                            drawSession();
                            focusFirst();
                        }, {small: true}))
            };
        })));
    }

    function questionnaireForm(entry) {
        const view = entry.current;
        const title = view.title && view.title.presence === 'VALUE' ? String(view.title.value) : entry.view.subject;
        const head = el('div', {class: 'form-head'}, el('h3', {class: 'form-head__title', tabindex: -1}, title),
            formChips(entry.view, entry.start), el('span', {class: 'dtr-case-chip', hidden: true}));
        const problems = Array.isArray(entry.view.problems) ? entry.view.problems : [];
        if (problems.length > 0) {
            return el('div', {class: 'panel__section'}, head,
                el('p', null, el('strong', null, 'This questionnaire cannot be answered. '), firstSentence(problems[0])[0]),
                el('p', {class: 'small muted'}, 'The response detail above lists every problem and the items as returned.'));
        }
        const held = heldByLinkId(entry.loop ? entry.response : entry.start);
        // A required question is marked unanswered after completing, not after a turn that has just asked it.
        const last = entry.steps.length > 0 ? entry.steps[entry.steps.length - 1] : null;
        const required = new Set(entry.form && last && last.kind === 'response' ? (entry.form.requiredUnanswered || []).map(ref => ref.linkId) : []);
        const questions = el('div', {class: 'questions'});
        for (const row of flatItems(view.items)) {
            if (row.kind !== 'question') {
                questions.append(itemLine(row));
                continue;
            }
            const linkId = linkIdOf(row.item);
            const field = questionField({
                item: row.item, depth: row.depth, held: linkId ? held.get(linkId) || null : null,
                given: linkId ? entry.answers.get(linkId) : undefined,
                fromCase: linkId ? entry.fromCase.get(linkId) : undefined,
                onCaseLeft(id) {
                    entry.fromCase.delete(id);
                    entry.left.add(id);
                    drawCaseState(entry);
                },
                onChange(id, values) {
                    if (values === null) {
                        entry.answers.delete(id);
                    } else {
                        entry.answers.set(id, values);
                    }
                    const said = fields.get(id);
                    if (said) {
                        said.setError('');
                    }
                    count.textContent = countLine(entry);
                    syncContinue();
                }
            });
            if (linkId && required.has(linkId)) {
                field.setError('Required, no answer.');
            }
            if (linkId) {
                fields.set(linkId, field);
            }
            questions.append(field.node);
        }
        if (view.items === undefined || view.items.length === 0) {
            questions.append(el('p', {class: 'small muted'}, entry.loop
                ? (entry.form ? 'The service asked no question at the last turn.' : 'Nothing asked yet: the first turn goes with no answers.')
                : 'This questionnaire holds no question.'));
        }
        const count = el('p', {class: 'small muted dtr-count', role: 'status'}, countLine(entry));
        const action = entry.loop ? LOOP_ACTION : RESPOND_ACTION;
        const send = el('button', {type: 'button', class: 'btn btn--primary dtr-send', 'data-action': action,
            on: {click: () => sendStep(entry, entry.loop ? 'loop' : 'respond')}}, actionWords(action));
        const preview = entry.loop ? el('button', {type: 'button', class: 'btn', on: {click: () => previewLoop(entry)}}, 'Preview') : null;
        stepButtons = [send, preview].filter(Boolean);
        const blocked = entry.loop ? ctx.blocked('DTR_NEXT_QUESTION') : null;
        if (blocked) {
            send.disabled = true;
            send.title = blocked;
        }
        const latest = session.latest && session.latest.entry === entry.index ? session.latest.envelope : null;
        const card = latest ? ctx.card(latest) : null;
        const whyLines = () => [el('p', {class: 'small'}, SMART),
            (entry.view.notApplied || []).map(line => el('p', {class: 'small muted'}, line)),
            (entry.form && entry.form.notes ? entry.form.notes : []).map(line => el('p', {class: 'small muted'}, line))];
        const whyOfCard = card ? cardWhy(card) : null;
        if (whyOfCard) {
            // One "Why" on the panel: the step's own lines go under the result's reasons.
            let added = false;
            whyOfCard.addEventListener('toggle', () => {
                const content = whyOfCard.querySelector('.fold__body');
                if (!added && whyOfCard.open && content) {
                    added = true;
                    content.append(...whyLines().flat());
                }
            });
        }
        if (card && latest.nextStep && latest.nextStep.action === action) {
            // The form's own button takes this next action; the result does not offer it a second time.
            const foot = card.querySelector('.verdict__foot');
            if (foot) {
                foot.hidden = true;
            }
        }
        const unanswered = flatItems(view.items).filter(row => row.kind === 'question' && required.has(linkIdOf(row.item)))
            .map(row => (row.item.text && row.item.text.presence === 'VALUE' ? String(row.item.text.value) : linkIdOf(row.item)));
        return el('div', {class: 'panel__section dtr-form'},
            head,
            changeLines(entry).map(words => el('p', {class: 'small dtr-change'}, words)),
            el('p', {class: 'small dtr-date-line', hidden: true}, DATE_LINE, ' ',
                button('Show the field', () => ctx.showField('dateOfService'), {small: true})),
            el('p', {class: 'small muted dtr-case-note', hidden: true}),
            questions,
            count,
            unanswered.length > 0 ? el('p', {class: 'small dtr-required'}, 'Required, no answer: ' + unanswered.join('; ')
                + (['.', '?', '!'].includes(unanswered[unanswered.length - 1].slice(-1)) ? '' : '.')) : null,
            session.masked.map(one => maskedLine(notHandedOnWords(session.labels[one.key] || one.key))),
            el('div', {class: 'form-foot'}, send, preview),
            el('p', {class: 'small dtr-withdrawn', hidden: true}, el('strong', null, WITHDRAWN)),
            el('div', {class: 'step-refusal dtr-step-refusal'}),
            entry.steps.length > 0 ? stepsFold(entry) : null,
            whyOfCard ? null : fold({summary: 'Why', body: () => el('div', null, whyLines())}),
            el('div', {class: 'step-result dtr-step-result'}, card));
    }

    /** The result card's own "Why" fold, or null. */
    function cardWhy(card) {
        for (const details of card.querySelectorAll('details.fold')) {
            const summary = details.querySelector('summary');
            const words = summary && summary.firstChild ? String(summary.firstChild.textContent || '') : '';
            if (words === 'Why') {
                return details;
            }
        }
        return null;
    }

    /** "Steps and changes": each step sent for the questionnaire, and what appeared, disappeared or was dropped. */
    function stepsFold(entry) {
        const words = entry.steps.length + (entry.steps.length === 1 ? ' step' : ' steps');
        return fold({summary: 'Steps and changes', hint: words, body: () => el('ol', {class: 'notes small'}, entry.steps.map((step, index) => {
            const verdict = step.envelope.verdict || {};
            const changes = step.envelope.detail && step.envelope.detail.form ? step.envelope.detail.form.changes : null;
            const dropped = changes && Array.isArray(changes.answersDropped) ? changes.answersDropped : [];
            return el('li', null,
                el('strong', null, step.kind === 'loop' ? 'Turn ' + (index + 1) : 'Completed in PAVE'), ' ',
                verdict.outcomeLabel || verdict.outcomeCode || '', ' ',
                el('span', {class: 'mono muted'}, step.envelope.runId || ''),
                step.sent ? null : el('span', null, ', not sent'),
                changes && changes.summary ? el('div', {class: 'muted'}, changes.summary) : null,
                dropped.length > 0 ? el('div', null, 'Answers dropped: ', dropped.map(ref => ref.text || ref.linkId).join('; ')) : null);
        }))});
    }

    async function sendStep(entry, kind) {
        const loop = kind === 'loop';
        const mine = loop ? {...own(), wrap: optionOf(ctx, 'nextQuestionShape')} : own();
        const body = {...caseBody(ctx.request(), mine), ...(loop ? loopPart(entry) : responsePart(entry))};
        if (Object.keys(session.carry).length > 0) {
            body.carry = session.carry;
        }
        if (loop && mine.wrap) {
            body.wrapInParameter = mine.wrap;
        }
        // The response step sends nothing to the payer, so it takes no scenario.
        if (loop && mine.mock && mine.scenarioId) {
            body.scenarioId = mine.scenarioId;
        }
        const posted = body.answers || [];
        const slot = work.querySelector('.dtr-step-refusal');
        entry.inFlight = snapshot(entry.answers);
        await sendRun({
            path: loop ? '/api/dtr/next-question' : '/api/dtr/response', body, busy,
            busyWords: loop ? 'Sending the answers...' : 'Completing the response...',
            buttons: stepButtons.concat([fetchButton]), typed: ctx.typedCode(), ctx,
            onResult: envelope => {
                applyResult(session, entry, loop ? 'loop' : 'response', envelope);
                ctx.resulted(envelope);
                drawSession();
                focusAfter(entry, loop);
            },
            onRefused: (error, placed) => {
                ctx.refused(2, error);
                const atQuestions = placeAnswerErrors(error, posted);
                if (slot) {
                    fill(slot, refusedPanel(error, placed.concat(atQuestions)));
                }
            },
            onFailed: error => {
                if (slot) {
                    fill(slot, failurePanel(error));
                }
            }
        });
    }

    async function previewLoop(entry) {
        const mine = {...own(), wrap: optionOf(ctx, 'nextQuestionShape')};
        const body = {...caseBody(ctx.request(), mine), ...loopPart(entry)};
        if (Object.keys(session.carry).length > 0) {
            body.carry = session.carry;
        }
        if (mine.wrap) {
            body.wrapInParameter = mine.wrap;
        }
        if (mine.mock && mine.scenarioId) {
            body.scenarioId = mine.scenarioId;
        }
        const posted = body.answers;
        await showPreview('/api/dtr/next-question/preview', body, error => placeAnswerErrors(error, posted));
    }

    /**
     * The server names a refused answer by its place in the list posted (answers[2].values[0]); the
     * question it is about is found by that place, and the sentence is shown at the question.
     * Returns the names placed there.
     */
    function placeAnswerErrors(error, posted) {
        const placed = [];
        for (const each of (error && Array.isArray(error.fieldErrors)) ? error.fieldErrors : []) {
            const name = String(each.field || '');
            if (!name.startsWith('answers[')) {
                continue;
            }
            const index = Number(name.slice('answers['.length, name.indexOf(']')));
            const sent = Number.isInteger(index) ? posted[index] : null;
            const field = sent ? fields.get(sent.linkId) : null;
            if (field) {
                field.setError(each.message);
                placed.push(each.field);
            }
        }
        return placed;
    }

    /** The panel of a refusal, unless every reason of it is already shown at its field. */
    function refusedPanel(error, placed) {
        const all = (error.fieldErrors || []).every(each => placed.includes(each.field));
        return all && (error.fieldErrors || []).length > 0 ? null : failurePanel(error, placed);
    }

    /**
     * The hand-over to step 3, kept current as answers change: the completed response goes on while
     * the answers on screen are the ones it was completed with, and is withdrawn while they differ.
     */
    function syncContinue() {
        const latest = session ? session.latest : null;
        const completed = latest && latest.envelope.nextStep && latest.envelope.nextStep.action === 'START_PAS' ? latest.envelope : null;
        const pending = session ? session.entries.some(entry => unseen(entry).length > 0) : false;
        handOn(completed && !pending ? completed.nextStep.carry : null);
        const line = work.querySelector('.dtr-withdrawn');
        if (line) {
            line.hidden = !(completed && pending);
        }
        // The card draws its Continue once the vocabulary has answered, so it is looked for again then.
        const mark = () => {
            for (const next of work.querySelectorAll('.dtr-step-result .verdict__foot .btn--primary')) {
                next.disabled = Boolean(completed && pending);
                next.title = completed && pending ? WITHDRAWN : '';
            }
        };
        mark();
        vocabulary().then(mark, () => null);
    }

    function handOn(carry) {
        if (carry) {
            ctx.handOn(3, carry);
            handedOn = true;
        } else if (handedOn) {
            ctx.handOn(3, null);
            handedOn = false;
        }
    }

    function focusFirst() {
        const target = work.querySelector('.question input, .question select, .question textarea') || work.querySelector('.form-head__title');
        if (target) {
            target.focus({preventScroll: true});
        }
    }

    /** After a turn, the tester goes on at the question just asked; after completing, at the result. */
    function focusAfter(entry, loop) {
        const appeared = loop && entry.form && entry.form.changes && entry.form.changes.appeared ? entry.form.changes.appeared : [];
        const field = appeared.length > 0 ? fields.get(appeared[appeared.length - 1].linkId) : null;
        if (field && field.input) {
            field.input.focus({preventScroll: false});
            return;
        }
        const result = work.querySelector('.dtr-step-result');
        if (result && result.firstChild) {
            result.tabIndex = -1;
            result.focus({preventScroll: false});
        }
    }

    vocabulary().then(drawWords, () => null);
    drawReadiness();
    drawHanded();

    return {
        show(how) {
            drawReadiness();
            drawHanded();
            if (how && how.start === true && !fetchButton.disabled) {
                fetchPackage();
            }
        },
        hide() {},
        caseChanged() {
            drawHanded();
            // A date of service typed after the date line showed may now answer those questions.
            const line = work.querySelector('.dtr-date-line');
            if (session && line && !line.hidden) {
                clearTimeout(caseTimer);
                caseTimer = setTimeout(() => {
                    const entry = session ? session.entries[session.active] : null;
                    if (entry && ctx.request().dateOfService) {
                        askCase(entry);
                    }
                }, CASE_QUIET);
            }
        },
        /** Start a new case: the package, the questionnaires, the answers and every result are forgotten. */
        reset() {
            clearTimeout(caseTimer);
            session = null;
            handedOn = false;
            fields = new Map();
            clear(refusal);
            clear(packageSlot);
            clear(work);
            drawHanded();
        },
        unmount() {
            clearTimeout(caseTimer);
            busy.stop();
        },
        /** The words or the configuration changed. */
        redraw(words) {
            drawWords(words);
            drawReadiness();
            drawHanded();
        }
    };
}

function first(values) {
    const list = Array.isArray(values) ? values : values === undefined || values === null ? [] : [values];
    return list.length > 0 && list[0] !== null && list[0] !== '' ? String(list[0]) : '';
}
