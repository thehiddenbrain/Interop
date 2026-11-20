// The DTR part of a result: everything the tool understood in what one DTR step returned.
//
// A package fetch fills the questionnaires, the bundles, the libraries and value sets that came
// with them and any response the package carried. A step of the adaptive loop fills the one
// questionnaire as it now stands, the response in full and where the form stands. A response put
// together inside this tool fills the same and sent nothing. All three are drawn here from the
// view records the server built; the Questionnaire JSON itself is never read by this page.
//
// The questions themselves, with the controls to answer them, are drawn by dtr-questionnaire.js
// above this part. Here they appear only for a questionnaire that cannot be answered, so that
// every item is still on screen.

import {el, append, code, facts, table, heading} from './dom.js';
import {shown, shownList, presenceMark, noneMark, hasValue} from './shown.js';
import {issuesTable} from './result.js';
import {itemList, originMark} from './dtr-items.js';

// What kind of thing came back, said before any of it. A kind this page does not know is shown under its own name.
const RESPONSE_KINDS = {
    PARAMETERS: 'The response is a FHIR Parameters resource, one of the two shapes a questionnaire package arrives in.',
    BUNDLE: 'The response is a FHIR Bundle, the other shape a questionnaire package arrives in.',
    QUESTIONNAIRE: 'The response is a Questionnaire resource on its own.',
    QUESTIONNAIRE_RESPONSE: 'The response is a QuestionnaireResponse, which is what a step of the adaptive loop returns.',
    OPERATION_OUTCOME: 'The response is a FHIR OperationOutcome.',
    OTHER_JSON: 'The response is JSON, but none of the shapes above. All of it is listed under the returned fields below.',
    NOT_JSON: 'The response body could not be read as JSON. It is shown whole under the returned fields below, and in the wire view.',
    NO_BODY: 'The response had no body.',
    NO_RESPONSE: 'No response arrived.',
    NOT_SENT: 'The response was put together inside this tool.'
};

/** The DTR detail of one result. */
export function dtrDetail(envelope) {
    const detail = envelope.detail;
    const node = el('div', {class: 'detail dtr-detail'});
    const kind = Object.prototype.hasOwnProperty.call(RESPONSE_KINDS, detail.responseKind)
        ? RESPONSE_KINDS[detail.responseKind] : ['Response kind ', code(String(detail.responseKind)), '.'];
    append(node, el('p', null, kind, detail.responseNote ? [' ', detail.responseNote] : null));

    const issues = detail.issues || [];
    if (issues.length > 0) {
        const alsoInPanel = envelope.error && envelope.error.issues && envelope.error.issues.length > 0;
        append(node, part(heading(4, 'Issues in the OperationOutcome', issues.length),
            alsoInPanel ? el('p', {class: 'quiet'}, 'They are listed issue by issue under "What went wrong", at the end of this result.') : issuesTable(issues)));
    }

    if (detail.form) {
        append(node, formPart(detail.form));
    }
    append(node, requestedPart(detail));

    const questionnaires = detail.questionnaires || [];
    if (questionnaires.length > 0 || envelope.operation === 'DTR_QUESTIONNAIRE_PACKAGE') {
        append(node, part(heading(4, envelope.operation === 'DTR_QUESTIONNAIRE_PACKAGE' ? 'Questionnaires in the package' : 'The questionnaire as it now stands',
            questionnaires.length),
        detail.kindRule ? el('p', {class: 'small quiet'}, 'How the kind is told: ', detail.kindRule) : null,
        questionnaires.length === 0 ? el('p', {class: 'quiet'}, 'The response held no questionnaire.') : questionnaires.map(questionnaireBlock)));
    }

    const resources = detail.resources || [];
    if (resources.length > 0 || envelope.operation === 'DTR_QUESTIONNAIRE_PACKAGE') {
        append(node, part(heading(4, 'Libraries, value sets and other resources that came with them', resources.length),
            resources.length === 0 ? el('p', {class: 'quiet'}, 'None came with the package.') : resources.map(resourceBlock)));
    }

    const bundles = detail.bundles || [];
    if (bundles.length > 0) {
        append(node, part(heading(4, 'Package bundles', bundles.length), table(
            [{text: 'Found at', class: 'col-25'}, {text: 'Type', class: 'col-12'}, {text: 'Id', class: 'col-13'}, {text: 'Entries', class: 'col-8'}, 'Full urls of the entries'],
            bundles.map(bundle => [code(bundle.foundAt), shown(bundle.type, {as: 'literal'}), shown(bundle.id, {as: 'literal'}),
                {class: 'num', node: String(bundle.entryCount)}, shownList(bundle.fullUrls, {as: 'literal'})]))));
    }

    const responses = detail.questionnaireResponses || [];
    if (responses.length > 0 || envelope.operation !== 'DTR_QUESTIONNAIRE_PACKAGE') {
        append(node, part(heading(4, envelope.operation === 'DTR_QUESTIONNAIRE_PACKAGE' ? 'Questionnaire responses the package carried' : 'The QuestionnaireResponse in full',
            responses.length),
        responses.length === 0 ? el('p', {class: 'quiet'}, 'This step returned no QuestionnaireResponse.') : responses.map(responseBlock)));
    }

    append(node, requestNotes(detail));
    return node;
}

function part(...children) {
    return append(el('section', {class: 'detail__part'}), children);
}

/* ---------- where the form stands ---------- */

function questionRefs(list, empty) {
    if (!list || list.length === 0) {
        return el('span', {class: 'quiet'}, empty);
    }
    return el('ul', {class: 'plain-list'}, list.map(ref => el('li', null, code(ref.linkId), ref.text ? [' ', ref.text] : null,
        ref.note ? el('div', {class: 'small quiet'}, ref.note) : null)));
}

function formPart(form) {
    const status = form.status || {};
    const changes = form.changes;
    return part(heading(4, 'Where the questionnaire stands'),
        facts([
            ['Questions so far', String(form.questionCount)],
            ['Enabled by the answers', String(form.enabledQuestionCount)],
            ['Answered', String(form.answeredCount)],
            ['Completion status', status.presence === 'VALUE'
                ? [code(status.code), status.display ? ' ' + status.display : null, status.recognised === false ? [' ', el('span', {class: 'flag'}, 'not a status this tool knows')] : null]
                : presenceMark(status.presence || 'ABSENT')],
            ['Complete', form.complete ? el('strong', null, 'Yes, as the server judged it') : 'No, as the server judged it'],
            ['Asked now', questionRefs(form.asked, 'Nothing is asked now.')],
            ['Required and unanswered', questionRefs(form.requiredUnanswered, 'None.')],
            ['Not judged', questionRefs(form.undetermined, 'None.')],
            ['The answer set', (form.answers || []).length === 0 ? el('span', {class: 'quiet'}, 'No answers yet.')
                : el('ul', {class: 'plain-list'}, form.answers.map(answer => el('li', null, code(answer.linkId), answer.text ? [' ', answer.text] : null, ': ',
                    el('strong', null, answer.values.join('; ')))))]
        ], 'facts--ruled'),
        changes ? changesBlock(changes) : null,
        (form.notes || []).length > 0 ? el('ul', {class: 'note-list'}, form.notes.map(note => el('li', null, note))) : null);
}

/** What changed since the previous step, as the server compared the two. Exported for the step list. */
export function changesBlock(changes) {
    return el('div', {class: 'dtr-changes'},
        el('p', null, changes.summary),
        changes.compared && changes.appeared.length + changes.disappeared.length + changes.answersDropped.length > 0 ? facts([
            ['Appeared', questionRefs(changes.appeared, 'None.')],
            ['Disappeared', questionRefs(changes.disappeared, 'None.')],
            ['Answers the service dropped', questionRefs(changes.answersDropped, 'None.')]
        ], 'facts--ruled') : null);
}

/* ---------- what was asked for ---------- */

function requestedPart(detail) {
    const requested = detail.requested || [];
    const missing = detail.notReturned || [];
    if (requested.length === 0 && missing.length === 0) {
        return null;
    }
    return part(heading(4, 'Questionnaires asked for by url', requested.length),
        el('ul', {class: 'plain-list'}, requested.map(url => el('li', null, code(url),
            missing.includes(url) ? [' ', el('strong', {class: 'dtr-missing'}, 'not returned')] : null))),
        missing.filter(url => !requested.includes(url)).length > 0
            ? el('p', null, 'Not returned: ', missing.filter(url => !requested.includes(url)).map((url, index) => [index > 0 ? ', ' : null, code(url)])) : null);
}

/* ---------- one questionnaire ---------- */

function questionnaireBlock(q) {
    const status = q.status || {};
    const usable = (q.problems || []).length === 0;
    return el('div', {class: 'detail__item'},
        el('p', {class: 'detail__item-head'},
            el('span', {class: 'sub-heading'}, q.position + '. ', q.subject),
            el('span', {class: 'dtr-kind dtr-kind--' + String(q.kind).toLowerCase()}, kindWord(q.kind))),
        el('p', {class: 'small quiet'}, 'Found at ', code(q.foundAt), '.'),
        facts([
            ['Title', shown(q.title)],
            ['Url', shown(q.url, {as: 'literal'})],
            ['Version', shown(q.version, {as: 'literal'})],
            ['Canonical', q.canonical ? code(q.canonical) : el('span', {class: 'presence presence--none'}, 'none: it has no url')],
            ['Name', shown(q.name, {as: 'literal'})],
            ['Id', shown(q.id, {as: 'literal'})],
            ['Status', status.presence === 'VALUE'
                ? [code(status.code), status.display ? ' ' + status.display : null, status.recognised === false ? [' ', el('span', {class: 'flag'}, 'not a status this tool knows')] : null]
                : presenceMark(status.presence || 'ABSENT')],
            ['Kind', [kindWord(q.kind), q.kindBasis ? el('div', {class: 'small'}, q.kindBasis) : null]],
            ['Questions', q.questionCount + (q.questionCount === 1 ? ' question, ' : ' questions, ') + q.requiredCount + ' marked required'],
            ['Arrived with a response', q.arrivedWith ? ['Yes, at ', code(q.arrivedWith), '. Answering starts from it.'] : 'No'],
            ['Derived from', shownList(q.derivedFrom, {as: 'literal'})],
            ['Profiles', shownList(q.profiles, {as: 'literal'})],
            ['Subject type', shownList(q.subjectType, {as: 'literal'})],
            ['Date', shown(q.date, {as: 'literal'})],
            ['Publisher', shown(q.publisher)],
            ['Description', shown(q.description, {as: 'block'})],
            ['Narrative', [shown(q.narrative, {as: 'literal'}), hasValue(q.narrativeStatus) ? el('div', {class: 'small'}, 'status ', code(q.narrativeStatus.value)) : null]],
            ['Which answers the conditions saw', q.answersBasis || null]
        ], 'facts--ruled'),
        usable ? null : el('div', {class: 'dtr-problems'},
            el('p', null, el('strong', null, 'This questionnaire cannot be answered.'), ' What makes it unusable, as the server found it:'),
            el('ul', {class: 'note-list'}, q.problems.map(problem => el('li', null, problem)))),
        notApplied(q),
        (q.extensions || []).length > 0 ? [el('p', {class: 'sub-heading'}, 'Extensions on the questionnaire (' + q.extensions.length + ')'),
            table(['Url', 'Value'], q.extensions.map(extension => [shown(extension.url, {as: 'literal'}), shown(extension.value, {as: 'literal'})]), {class: 'pairs'})] : null,
        (q.contained || []).length > 0 ? [el('p', {class: 'sub-heading'}, 'Resources it carries inside itself (' + q.contained.length + ')'),
            q.contained.map(resourceBlock)] : null,
        // A questionnaire that cannot be answered gets no controls above, so its items are listed here.
        usable ? null : [el('p', {class: 'sub-heading'}, 'Its items as returned'), itemList(q.items, {interactive: false, answers: new Map()})]);
}

/** The rules a questionnaire carries that this tool does not apply, which the tester must not read as "no value". Exported for the questionnaire view. */
export function notApplied(q) {
    const list = q.notApplied || [];
    if (list.length === 0) {
        return null;
    }
    return el('div', {class: 'dtr-notapplied'},
        el('p', null, el('strong', null, list.length === 1 ? 'A rule this tool does not apply. ' : list.length + ' rules this tool does not apply. '),
            'A blank where one of them would put a value is not an answer of "no value".'),
        el('ul', {class: 'note-list'}, list.map(line => el('li', null, line))));
}

export function kindWord(kind) {
    if (kind === 'ADAPTIVE') {
        return 'adaptive';
    }
    if (kind === 'STATIC') {
        return 'static';
    }
    if (kind === 'UNDETERMINED') {
        return 'kind undetermined';
    }
    return String(kind);
}

/* ---------- a library, a value set or another resource ---------- */

function resourceBlock(resource) {
    const content = resource.content || [];
    const concepts = resource.concepts || [];
    return el('div', {class: 'detail__nested'},
        el('p', null, el('strong', null, shown(resource.resourceType)), ' ', hasValue(resource.title) ? shown(resource.title) : shown(resource.name),
            el('span', {class: 'small quiet'}, ' at ', code(resource.foundAt))),
        facts([
            ['Url', shown(resource.url, {as: 'literal'})],
            ['Version', shown(resource.version, {as: 'literal'})],
            ['Name', shown(resource.name, {as: 'literal'})],
            ['Id', shown(resource.id, {as: 'literal'})],
            ['Status', shown(resource.status, {as: 'literal'})],
            ['Concepts', concepts.length > 0 ? el('ul', {class: 'plain-list'}, concepts.map(concept => el('li', null, shown(concept)))) : null],
            ['Content', content.length > 0 ? el('ul', {class: 'plain-list'}, content.map(piece => el('li', null,
                shown(piece.contentType, {as: 'literal'}),
                hasValue(piece.url) ? [' at ', shown(piece.url, {as: 'literal'})] : null, ', ',
                piece.dataPresence === 'VALUE'
                    ? [String(piece.dataCharacters), ' characters of encoded data, shown in full in the wire view']
                    : ['inline data ', presenceMark(piece.dataPresence || 'ABSENT')]))) : null]
        ], 'facts--ruled'));
}

/* ---------- a QuestionnaireResponse in full ---------- */

function responseBlock(response) {
    const status = response.status || {};
    return el('div', {class: 'detail__item'},
        el('p', {class: 'detail__item-head'},
            el('span', {class: 'sub-heading'}, 'QuestionnaireResponse ', shown(response.id, {as: 'literal'})),
            el('span', {class: 'small'}, response.answeredCount + (response.answeredCount === 1 ? ' item answered' : ' items answered'))),
        el('p', {class: 'small quiet'}, 'Found at ', code(response.foundAt), '.'),
        facts([
            ['Answers questionnaire', shown(response.questionnaire, {as: 'literal'})],
            ['Status', status.presence === 'VALUE'
                ? [code(status.code), status.display ? ' ' + status.display : null, status.recognised === false ? [' ', el('span', {class: 'flag'}, 'not a status this tool knows')] : null]
                : presenceMark(status.presence || 'ABSENT')],
            ['Authored', shown(response.authored, {as: 'literal'})],
            ['Subject', shown(response.subject, {as: 'literal'})],
            ['Author', shown(response.author, {as: 'literal'})],
            ['Profiles', shownList(response.profiles, {as: 'literal'})]
        ], 'facts--ruled'),
        (response.extensions || []).length > 0
            ? table(['Extension', 'Value'], response.extensions.map(extension => [shown(extension.url, {as: 'literal'}), shown(extension.value, {as: 'literal'})]), {class: 'pairs'})
            : el('p', {class: 'small'}, 'Extensions on the response: ', noneMark()),
        responseItems(response.items || []));
}

function responseItems(items) {
    if (items.length === 0) {
        return el('p', {class: 'small'}, 'Items: ', noneMark(), ' A response that holds no item answers nothing.');
    }
    const rows = [];
    const walk = (list, depth) => {
        for (const item of list) {
            const answers = item.answers || [];
            const origins = item.origins || [];
            rows.push({class: depth > 0 ? 'dtr-row--nested' : null, cells: [
                [shown(item.linkId, {as: 'literal'}),
                    el('div', {class: 'small quiet'}, code(item.path))],
                shown(item.text),
                answers.length === 0 ? el('span', {class: 'presence presence--none'}, 'no answer')
                    : el('ul', {class: 'plain-list'}, answers.map(answer => el('li', null, shown(answer)))),
                answers.length === 0 ? null : el('ul', {class: 'plain-list'}, answers.map((answer, index) => el('li', null, originMark(origins[index])))),
                (item.answerExtensions || []).length === 0 ? null
                    : el('ul', {class: 'plain-list'}, item.answerExtensions.map(extension => el('li', null, shown(extension.url, {as: 'literal'}), ' = ', shown(extension.value, {as: 'literal'}))))
            ]});
            walk(item.items || [], depth + 1);
        }
    };
    walk(items, 0);
    return table([{text: 'linkId', class: 'col-18'}, {text: 'Question', class: 'col-25'}, {text: 'Answers', class: 'col-17'},
        {text: 'Whose answer', class: 'col-25'}, {text: 'Other extensions', class: 'col-14'}], rows, {class: 'dtr-response-items'});
}

/* ---------- how the request was built ---------- */

function requestNotes(detail) {
    const notes = detail.requestNotes || [];
    const block = el('section', {class: 'detail__part'});
    if (detail.bodyBuiltByTool === false) {
        block.append(el('p', null, 'The body the tester supplied was sent in place of one this tool would have built.'));
    }
    if (notes.length > 0) {
        block.append(el('details', {class: 'fold', open: true},
            el('summary', null, 'How this tool built the request', el('span', {class: 'quiet'}, notes.length + (notes.length === 1 ? ' note' : ' notes'))),
            el('ul', {class: 'note-list'}, notes.map(note => el('li', null, note)))));
    }
    return block.childNodes.length > 0 ? block : null;
}
