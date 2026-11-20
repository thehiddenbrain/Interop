// The questionnaire answers completed in DTR, shown as answers: before sending, as they were
// handed on; on the preview, as they stand inside the request body the tool would send; and on a
// result, as they stand inside the request body that went.
//
// The answers are read out of the text that goes on the wire, not out of what DTR handed on, so
// what a tester reads here is what the service receives. Nothing is judged: each answer is shown
// with its question as the QuestionnaireResponse holds it, and a value of a kind this page does not
// name is shown as the JSON it is.
//
// The exact text of each QuestionnaireResponse is cut out of the body as it stands, character for
// character, rather than parsed and written out again, which would rewrite numbers such as 1.0.

import {el, code, table, heading, copyButton} from './dom.js';

const QR = 'QuestionnaireResponse';

/** The answers of one QuestionnaireResponse, in the order of its items: [{linkId, text, values, depth}]. */
export function answersOf(response) {
    const rows = [];
    walk(response && Array.isArray(response.item) ? response.item : [], 0, rows);
    return rows;
}

function walk(items, depth, rows) {
    for (const item of items) {
        if (!item || typeof item !== 'object') {
            continue;
        }
        const answers = Array.isArray(item.answer) ? item.answer : [];
        rows.push({linkId: item.linkId, text: item.text, values: answers.map(valueOf), group: answers.length === 0 && Array.isArray(item.item), depth});
        if (Array.isArray(item.item)) {
            walk(item.item, depth + 1, rows);
        }
        // An answer can carry items of its own: the questions it opened.
        for (const answer of answers) {
            if (answer && Array.isArray(answer.item)) {
                walk(answer.item, depth + 1, rows);
            }
        }
    }
}

/** One answer's value as text, from whichever value[x] it carries. */
function valueOf(answer) {
    const key = answer && typeof answer === 'object' ? Object.keys(answer).find(name => name.startsWith('value')) : undefined;
    if (!key) {
        return {kind: null, text: null};
    }
    const value = answer[key];
    const kind = key.slice('value'.length);
    if (value === null || typeof value !== 'object') {
        return {kind, text: String(value)};
    }
    if (kind === 'Coding') {
        return {kind, text: [value.code, value.display ? '(' + value.display + ')' : null, value.system ? value.system : null].filter(Boolean).join(' ')};
    }
    if (kind === 'Quantity') {
        return {kind, text: [value.value, value.unit || value.code].filter(part => part !== undefined && part !== null).join(' ')};
    }
    if (kind === 'Reference') {
        return {kind, text: value.reference || value.display || JSON.stringify(value)};
    }
    return {kind, text: JSON.stringify(value)};
}

/**
 * Every QuestionnaireResponse in a request body, with its exact text: [{resource, text}]. The body
 * is a bundle whose entries hold resources, or a QuestionnaireResponse on its own. A body that is
 * not JSON holds none that can be read, and the caller says so.
 */
export function responsesInBody(bodyText) {
    if (typeof bodyText !== 'string' || bodyText.trim() === '') {
        return {readable: false, found: []};
    }
    let parsed;
    try {
        parsed = JSON.parse(bodyText);
    } catch (notJson) {
        return {readable: false, found: []};
    }
    const resources = [];
    if (parsed && parsed.resourceType === QR) {
        resources.push(parsed);
    }
    for (const entry of parsed && Array.isArray(parsed.entry) ? parsed.entry : []) {
        if (entry && entry.resource && entry.resource.resourceType === QR) {
            resources.push(entry.resource);
        }
    }
    const spans = spansOf(bodyText);
    return {readable: true, found: resources.map((resource, index) => ({resource, text: spans[index] || null}))};
}

/**
 * The text of each object in a JSON text whose own resourceType is QuestionnaireResponse, in the
 * order they appear. A scan of the characters: strings are skipped whole, so a brace or the word
 * inside a string never counts.
 */
function spansOf(text) {
    const open = [];       // {start, isResponse} for each object not yet closed
    const spans = [];
    let lastKey = null;
    let expectValueOf = null;
    for (let index = 0; index < text.length; index += 1) {
        const ch = text[index];
        if (ch === '"') {
            const end = stringEnd(text, index);
            const value = JSON.parse(text.slice(index, end + 1));
            if (expectValueOf === 'resourceType' && value === QR && open.length > 0) {
                open[open.length - 1].isResponse = true;
            }
            expectValueOf = null;
            lastKey = value;
            index = end;
            continue;
        }
        if (ch === ':') {
            expectValueOf = lastKey;
            continue;
        }
        if (ch === '{') {
            open.push({start: index, isResponse: false});
        } else if (ch === '}') {
            const object = open.pop();
            if (object && object.isResponse) {
                spans.push(text.slice(object.start, index + 1));
            }
        }
        if (!/\s/.test(ch)) {
            expectValueOf = null;
            lastKey = null;
        }
    }
    return spans;
}

function stringEnd(text, start) {
    for (let index = start + 1; index < text.length; index += 1) {
        if (text[index] === '\\') {
            index += 1;
        } else if (text[index] === '"') {
            return index;
        }
    }
    return text.length - 1;
}

/**
 * The answers of one QuestionnaireResponse as a table: question, linkId, answer as it stands.
 *
 * compact: true for the narrow case pane, where the linkId goes under its question and a coding's
 * system under its code, so no column is squeezed to a few characters a line.
 */
export function answersTable(response, compact) {
    const rows = answersOf(response);
    if (rows.length === 0) {
        return el('p', {class: 'quiet'}, 'It holds no items.');
    }
    const question = row => (row.text === undefined ? el('span', {class: 'quiet small'}, 'no question text') : String(row.text));
    const linkId = row => (row.linkId === undefined ? el('span', {class: 'quiet small'}, 'none') : code(String(row.linkId)));
    const answer = row => (row.group ? el('span', {class: 'quiet small'}, 'a group: its questions follow')
        : row.values.length === 0 ? el('span', {class: 'presence presence--none'}, 'no answer')
            : el('ul', {class: 'plain-list'}, row.values.map(value => el('li', null,
                value.text === null ? el('span', {class: 'quiet small'}, 'an answer with no value') : code(value.text),
                value.kind ? el('span', {class: 'quiet small'}, ' ' + value.kind) : null))));
    if (compact) {
        return table([{text: 'Question', class: 'col-45'}, 'Answer as it stands'], rows.map(row => ({
            class: row.depth > 0 ? 'pas-answer pas-answer--nested' : 'pas-answer',
            cells: [[question(row), el('div', {class: 'small'}, linkId(row))], answer(row)]
        })), {class: 'pas-answers__table'});
    }
    return table([{text: 'Question', class: 'col-45'}, {text: 'linkId', class: 'col-18'}, 'Answer as it stands'], rows.map(row => ({
        class: row.depth > 0 ? 'pas-answer pas-answer--nested' : 'pas-answer',
        cells: [question(row), linkId(row), answer(row)]
    })), {class: 'pas-answers__table'});
}

/** A few facts of one QuestionnaireResponse: its id, status and the questionnaire it answers. */
function aboutLine(resource) {
    const part = (label, value) => (value === undefined || value === null ? null : [label, code(String(value)), ' ']);
    return el('p', {class: 'small'}, part('id ', resource.id), part('status ', resource.status), part('answers ', resource.questionnaire));
}

/**
 * The section that shows the QuestionnaireResponse inside a request body.
 *
 * spec.body      the request body text
 * spec.when      'preview' for a request not yet sent, 'sent' for the body that went, 'built'
 *                for the body of a run whose request was built and not sent
 * spec.handed    how many questionnaire responses DTR handed on (0 when none)
 * spec.withheld  true when the tester chose to send without them
 */
export function answersInRequest(spec) {
    const sent = spec.when === 'sent';
    const built = spec.when === 'built';
    const {readable, found} = responsesInBody(spec.body);
    const title = sent ? 'The questionnaire answers in the request that was sent'
        : built ? 'The questionnaire answers in the request built, which was not sent' : 'The questionnaire answers in this request';
    const node = el('section', {class: 'pas-answers', aria: {label: title}}, heading(3, title, found.length));
    if (!readable) {
        node.append(el('p', null, sent || built ? 'The request body is not JSON this page can read, so the answers in it cannot be listed. The wire view shows it whole.'
            : 'The request body is not JSON this page can read, so the answers in it cannot be listed.'));
        return node;
    }
    if (found.length === 0) {
        node.append(el('p', {class: spec.handed > 0 && !spec.withheld ? 'pas-answers__missing' : null},
            spec.handed > 0 && spec.withheld
                ? (sent ? 'None. You chose to send this request without the answers completed in DTR, and it went without them.'
                    : built ? 'None. You chose to send this request without the answers completed in DTR, and it was built without them.'
                    : 'None. You chose to send this request without the answers completed in DTR.')
                : spec.handed > 0
                    ? [el('strong', null, 'The answers handed on from DTR are not in this request body.'),
                        ' They were handed on, and the body does not hold a QuestionnaireResponse. Read the notes on the request.']
                    : 'None. Nothing was handed on from DTR, and the body holds no QuestionnaireResponse.'));
        return node;
    }
    node.append(el('p', null, sent
        ? 'Read from the request body that went, shown in full in the wire view below. Each answer is as the body holds it.'
        : built ? 'Read from the request body this tool built, shown in the wire view below. It was not sent.'
            : 'Read from the request body below, which is what would be sent. Each answer is as the body holds it.'));
    found.forEach((item, index) => {
        const exact = item.text === null ? null
            : el('pre', {class: 'body-text', tabIndex: 0, aria: {label: 'The QuestionnaireResponse exactly as it stands in the request body'}}, item.text);
        node.append(el('div', {class: 'pas-answers__one'},
            found.length > 1 ? el('p', {class: 'sub-heading'}, 'QuestionnaireResponse ' + (index + 1)) : null,
            aboutLine(item.resource),
            answersTable(item.resource),
            exact ? el('details', {class: 'fold'},
                el('summary', null, 'The QuestionnaireResponse exactly as it stands in the body', el('span', {class: 'quiet'}, item.text.length.toLocaleString() + ' characters')),
                el('div', {class: 'raw-body'}, copyButton('Copy it', () => exact.textContent, exact), exact)) : null));
    });
    return node;
}
