// The response detail of a run: what the tool understood in the answer, drawn from the view the
// server built, inside the result's closed fold "Response detail". One renderer per protocol: the
// coverage information, cards and actions of a coverage check; the questionnaires, resources and
// responses of a DTR step; the decision for each item of a claim; the subscriptions of a
// subscription call.
//
// The two answers testers most often need stay first and in view inside the fold: the delegated
// vendor a coverage answer names, and the decision for every item of a claim, so a partial
// approval never reads as one line. Every decision, label and tone is the server's; the page
// words none of them.
//
// A field the response did not carry is said to be not returned, never left out, and absent, null
// and empty are three different words. What a service wrote is shown as the characters it is,
// never read as markup. Nested lists are walked with a stack of their own, never by recursion, so
// an answer nested as deep as a service likes cannot overflow the page.

import {el, append, chip, kv, fold, token} from './dom.js';
import {issuesTable} from './fault.js';
import {vocabulary} from './words.js';
// Not drawn from here. Imported so that the step modules load with the page and are held by
// StaticJoinTest until the Workbench (A2a) mounts them; the builder of H1 removes these two lines
// once workbench.js imports both.
import {mount as questionnaireStep} from './wb-dtr.js';
import {mount as submitStep} from './wb-submit.js';

const PRESENCE_WORDS = {ABSENT: 'not returned', NULL: 'returned as null', EMPTY: 'returned empty'};

/**
 * The detail of a run envelope as an element, or null when there is none to draw: no detail, or a
 * protocol that has no detail of its own (a lookup, a raw request).
 */
export function responseDetail(envelope) {
    if (!envelope || !envelope.detail || typeof envelope.detail !== 'object') {
        return null;
    }
    if (envelope.protocol === 'CRD') {
        return crdDetail(envelope);
    }
    if (envelope.protocol === 'DTR') {
        return dtrDetail(envelope);
    }
    if (envelope.protocol === 'PAS') {
        return pasDetail(envelope);
    }
    if (envelope.protocol === 'SUBSCRIPTION') {
        return envelope.operation === 'SUBSCRIPTION_NOTIFICATION' ? notificationDetail(envelope) : subscriptionDetail(envelope);
    }
    return null;
}

/**
 * The hint of the fold that holds the detail, in counts ("2 cards, 1 coverage information"), or
 * '' when there is nothing to count. Offered beside the contract so result.js need not count.
 */
export function detailHint(envelope) {
    const detail = envelope && envelope.detail;
    if (!detail || typeof detail !== 'object') {
        return '';
    }
    const parts = [];
    const add = (list, one, many) => {
        const n = Array.isArray(list) ? list.length : 0;
        if (n > 0) {
            parts.push(n + ' ' + (n === 1 ? one : many));
        }
    };
    if (envelope.protocol === 'CRD') {
        add(detail.cards, 'card', 'cards');
        add(detail.coverageInformation, 'coverage information', 'coverage information');
        add(detail.systemActions, 'system action', 'system actions');
    } else if (envelope.protocol === 'DTR') {
        add(detail.questionnaires, 'questionnaire', 'questionnaires');
        add(detail.questionnaireResponses, 'response', 'responses');
        add(detail.resources, 'resource', 'resources');
    } else if (envelope.protocol === 'PAS') {
        const decisions = (detail.claimResponses || []).reduce((sum, response) => sum + (response.decisions || []).length, 0);
        if (decisions > 0) {
            parts.push(decisions + (decisions === 1 ? ' item decision' : ' item decisions'));
        }
        add(detail.claimResponses, 'ClaimResponse', 'ClaimResponses');
    } else if (envelope.protocol === 'SUBSCRIPTION') {
        add(detail.subscriptions, 'subscription', 'subscriptions');
        add(detail.events, 'event', 'events');
    }
    add(detail.issues, 'issue', 'issues');
    return parts.join(', ');
}

/* ---------- small parts ---------- */

function code(text, extra) {
    return el('span', {class: ['mono', extra || null]}, String(text));
}

/** The words for a presence that is not a value, as a dashed chip. One this page does not know is shown under its own name. */
function presenceMark(presence) {
    const name = String(presence || 'ABSENT');
    return chip(Object.prototype.hasOwnProperty.call(PRESENCE_WORDS, name) ? PRESENCE_WORDS[name] : name, {dashed: true});
}

/** A list that can repeat and came back with nothing: the server sends [] for both no list and an empty one. */
function noneMark(text) {
    return chip(text || 'none returned', {dashed: true});
}

/** A sub-extension sent with no value[x]: there, and saying nothing, which is neither empty nor not returned. */
function noValueMark() {
    return chip('sent with no value', {dashed: true});
}

function hasValue(value) {
    return Boolean(value) && value.presence === 'VALUE';
}

/** One value the server sent as {presence, value}. options.as: 'text', 'literal' (codes and ids), 'block' (line breaks kept), 'link'. */
function shown(value, options) {
    const as = options && options.as ? options.as : 'text';
    if (!value || typeof value !== 'object' || value.presence === undefined) {
        return presenceMark('ABSENT');
    }
    if (value.presence !== 'VALUE') {
        return presenceMark(value.presence);
    }
    const text = String(value.value);
    if (as === 'literal') {
        return code(text);
    }
    if (as === 'block') {
        return el('div', {class: 'detail__text'}, text);
    }
    if (as === 'link') {
        return outLink(text, options.label);
    }
    return el('span', null, text);
}

function shownList(values, options) {
    if (!Array.isArray(values) || values.length === 0) {
        return noneMark();
    }
    if (values.length === 1) {
        return shown(values[0], options);
    }
    return el('ul', {class: 'notes'}, values.map(value => el('li', null, shown(value, options))));
}

/**
 * An address a response gave. Only an http or https address is a link, opened in a new tab with
 * no opener and no referrer; anything else stays text, so a script address is never followed.
 */
function outLink(address, label) {
    const text = String(address);
    const lower = text.toLowerCase();
    if (!lower.startsWith('https://') && !lower.startsWith('http://')) {
        return code(text);
    }
    return el('span', null, code(text), ' ', el('a', {href: text, target: '_blank', rel: 'noopener noreferrer'}, label || 'Open'));
}

/** A coded value: the code, what the tool knows it means, and a flag when it is not a code the tool knows. */
function coded(value) {
    if (!value || value.presence === undefined) {
        return presenceMark('ABSENT');
    }
    if (value.presence !== 'VALUE') {
        return presenceMark(value.presence);
    }
    return el('span', null, code(String(value.code)),
        value.display ? [' ', value.display] : null,
        value.version ? el('span', {class: 'small muted'}, ' (' + value.version + ')') : null,
        value.recognised === false ? [' ', chip('not a code this tool knows', {tone: 'neutral'})] : null);
}

function codeList(values, emptyText) {
    const list = Array.isArray(values) ? values.filter(value => value !== null && value !== undefined) : [];
    if (list.length === 0) {
        return noneMark(emptyText);
    }
    return list.map((value, index) => [index > 0 ? ', ' : null, code(String(value))]);
}

/** One part of the detail: a small heading with its count, and what it holds. */
function part(title, count, ...children) {
    return append(el('section', {class: 'detail__part'},
        el('h4', {class: 'section-label'}, title, typeof count === 'number' ? ' (' + count + ')' : null)), children);
}

function sub(text) {
    return el('p', {class: 'detail__sub'}, text);
}

function quiet(text) {
    return el('p', {class: 'muted small'}, text);
}

/** Facts as the key and value table every view uses. A row whose value is null is left out: the caller has nothing to say for it. */
function facts(rows) {
    return kv(rows.filter(row => row && row[1] !== null && row[1] !== undefined).map(([key, value]) => ({key, value})));
}

/** A table: headers, and rows of cells (nodes, strings, lists). A row given as {cells, class} carries a class. */
function table(headers, rows, extra) {
    return el('div', {class: 'table-wrap'}, el('table', {class: ['table', extra || null]},
        el('thead', null, el('tr', null, headers.map(name => el('th', {scope: 'col'}, name)))),
        el('tbody', null, rows.map(row => {
            const cells = Array.isArray(row) ? row : row.cells;
            return el('tr', {class: Array.isArray(row) ? null : row.class || null}, cells.map(cell => el('td', null, cell)));
        }))));
}

function noteList(notes) {
    return el('ul', {class: 'notes'}, notes.map(note => el('li', null, note)));
}

/**
 * What a repeating element was, when the view has no entry of it to show: each view sends the
 * presence of its lists beside them (lists, keyed by the element's name as the response spells
 * it). Null when there are entries, or when the answer carries no presence for the element.
 */
function listState(lists, name, count) {
    if (count > 0 || !lists || !lists[name]) {
        return null;
    }
    return el('p', null, listWords(lists[name], name));
}

/** The same words as the value of a row of facts. */
function listValue(lists, name, values, options) {
    if (Array.isArray(values) && values.length > 0) {
        return shownList(values, options);
    }
    if (!lists || !lists[name]) {
        return shownList(values, options);
    }
    return listWords(lists[name], name);
}

function listWords(presence, name) {
    if (presence === 'VALUE') {
        // Something that is not a list, or none of whose entries is the shape the view reads. It
        // stays among the fields returned but not interpreted.
        return [code(name), ' was returned, and none of its entries could be read as one.'];
    }
    return [code(name), ' ', presenceMark(presence)];
}

/** A list the view leaves out when it holds nothing, said only when it was sent as null or empty. */
function sentEmpty(lists, name) {
    return lists && (lists[name] === 'NULL' || lists[name] === 'EMPTY') ? listState(lists, name, 0) : null;
}

/**
 * Fields returned with a resource and drawn by no view: where each sat, whether it held anything,
 * and its value as JSON text. When the wire view masked a secret the service sent back, these
 * values are withheld here rather than matched to the masked body: a value is never shown in a
 * form the wire view hides, and the wire view holds the body as masked.
 */
function fieldsTable(fields, wire) {
    const masked = Boolean(wire && wire.response && wire.response.maskedPlaces > 0);
    return table(['Path', 'Presence', 'Value as returned'], fields.map(field => [
        code(field.path),
        field.presence === 'VALUE' ? el('span', {class: 'muted small'}, 'value') : presenceMark(field.presence),
        masked && field.kind !== 'NOTE' && field.value !== undefined && field.value !== null
            ? el('span', {class: 'muted small'}, 'Withheld here: secrets were masked in this response. The wire view shows the body as masked.')
            : field.value !== undefined && field.value !== null ? longText(String(field.value))
                : field.presence === 'NULL' ? code('null') : el('span', {class: 'muted small'}, 'no text given')
    ]));
}

function fieldsFold(fields, wire) {
    return fold({summary: 'Show the ' + fields.length + (fields.length === 1 ? ' field' : ' fields'), inline: true,
        body: () => fieldsTable(fields, wire)});
}

// A value longer than this shows its start, with the whole of it one press away.
const LONG_TEXT = 600;

function longText(value) {
    if (value.length <= LONG_TEXT) {
        return code(value);
    }
    return el('div', null, code(value.slice(0, LONG_TEXT / 2) + ' ...'),
        fold({summary: 'Show all ' + value.length + ' characters', inline: true, body: () => code(value)}));
}

/**
 * Every extension as returned, with the extensions nested inside it, so a vendor extension nobody
 * anticipated is still shown where it sat. Walked with a stack: the depth is the service's.
 */
function extensionList(extensions) {
    const list = Array.isArray(extensions) ? extensions : [];
    if (list.length === 0) {
        return null;
    }
    const root = el('ul', {class: 'notes detail__extensions'});
    const stack = [{into: root, items: list, at: 0}];
    while (stack.length > 0) {
        const top = stack[stack.length - 1];
        if (top.at >= top.items.length) {
            stack.pop();
            continue;
        }
        const extension = top.items[top.at] || {};
        top.at += 1;
        const line = el('li', null, code(extension.url || '(no url)'), extension.value ? [' ', shown(extension.value, {as: 'literal'})] : null);
        top.into.append(line);
        if (Array.isArray(extension.parts) && extension.parts.length > 0) {
            const nested = el('ul', {class: 'notes detail__extensions'});
            line.append(nested);
            stack.push({into: nested, items: extension.parts, at: 0});
        }
    }
    return root;
}

/**
 * An outcome code the server set, worded from the vocabulary: the code at once, and the server's
 * label in its tone once the vocabulary has arrived. A code the vocabulary does not hold stays the
 * code; the page never words one itself.
 */
function outcomeChip(protocol, outcomeCode) {
    if (!outcomeCode) {
        return noneMark('not stated');
    }
    const holder = el('span', {class: 'detail__outcome'}, chip(outcomeCode, {mono: true}));
    Promise.resolve(vocabulary()).then(words => {
        const list = words && words.outcomes && Array.isArray(words.outcomes[protocol]) ? words.outcomes[protocol] : [];
        const known = list.find(entry => entry && entry.code === outcomeCode);
        if (known && known.label) {
            holder.replaceChildren(chip(known.label, {tone: known.tone}), ' ', code(outcomeCode, 'small'));
        }
    }, () => null);
    return holder;
}

/** The issues of an OperationOutcome, unless the error panel of the same result lists them already. */
function issuesPart(issues, envelope) {
    if (!Array.isArray(issues) || issues.length === 0) {
        return null;
    }
    const alsoInPanel = envelope && envelope.error && Array.isArray(envelope.error.issues) && envelope.error.issues.length > 0;
    return part('Issues in the OperationOutcome', issues.length,
        alsoInPanel ? quiet('Listed issue by issue in the error panel of this result.') : issuesTable(issues));
}

/** How the request was built: the server's notes, the first in view and the rest folded. */
function requestNotesPart(detail, extraRows) {
    const notes = Array.isArray(detail.requestNotes) ? detail.requestNotes : [];
    const rows = (extraRows || []).filter(row => row && row[1] !== null && row[1] !== undefined);
    if (notes.length === 0 && rows.length === 0 && detail.bodyBuiltByTool !== false) {
        return null;
    }
    const [first, ...rest] = notes;
    return part('How this tool built the request', null,
        rows.length > 0 ? facts(rows) : null,
        detail.bodyBuiltByTool === false ? el('p', null, 'The body the tester supplied was sent in place of one this tool would have built.') : null,
        first ? noteList([first]) : null,
        rest.length > 0 ? fold({summary: 'Every note', hint: notes.length + ' notes', inline: true, body: () => noteList(rest)}) : null);
}

/** The kind of answer, in the server's words where it gave some, else in this page's for the kinds it knows. */
function kindLine(kinds, kind, note) {
    const known = Object.prototype.hasOwnProperty.call(kinds, kind) ? kinds[kind] : null;
    // A note that says the kind's own sentence, or starts with it, is said in its place, once.
    if (note && known && note.startsWith(known)) {
        return el('p', {class: 'detail__kind'}, note);
    }
    return el('p', {class: 'detail__kind'}, known || ['Response kind ', code(String(kind)), '.'], note && note !== known ? [' ', note] : null);
}

const COMMON_KINDS = {
    OPERATION_OUTCOME: 'The response is a FHIR OperationOutcome.',
    NOT_JSON: 'The response body could not be read as JSON. It is shown whole under the returned fields, and in the wire traffic.',
    NO_BODY: 'The response has no body.',
    NO_RESPONSE: 'No response arrived.'
};

/* ---------- coverage check (CRD) ---------- */

const CRD_KINDS = Object.assign({
    CDS_HOOKS: 'The response is a JSON object and was read as a CDS Hooks response.',
    OTHER_JSON: 'The response is JSON, but neither a CDS Hooks response nor an OperationOutcome. All of it is listed under the returned fields.'
}, COMMON_KINDS);

function crdDetail(envelope) {
    const detail = envelope.detail;
    const node = el('div', {class: 'detail detail--crd'});
    node.append(kindLine(CRD_KINDS, detail.responseKind, detail.responseNote));
    append(node, issuesPart(detail.issues, envelope));

    const coverage = Array.isArray(detail.coverageInformation) ? detail.coverageInformation : [];
    node.append(part('Coverage information', coverage.length,
        coverage.length === 0 ? quiet('The response carried no coverage information extension: not in a system action, not on a card, not on a suggestion.')
            : coverage.map(coverageBlock)));

    const cards = Array.isArray(detail.cards) ? detail.cards : [];
    node.append(part('Cards', cards.length, crdListState(detail.cardsPresence, cards.length, 'cards'),
        cards.map(card => cardBlock(card, cards.length, envelope.wire))));

    const actions = Array.isArray(detail.systemActions) ? detail.systemActions : [];
    node.append(part('System actions', actions.length, crdListState(detail.systemActionsPresence, actions.length, 'systemActions'),
        actions.map(action => actionBlock(action, envelope.wire))));

    append(node, requestNotesPart(detail), conformanceScope(detail.conformance));
    return node;
}

function crdListState(presence, length, name) {
    if (length > 0) {
        return null;
    }
    if (presence === 'VALUE') {
        return quiet(name + ' was returned, and none of its entries could be read as one.');
    }
    return el('p', null, code(name), ' ', presenceMark(presence || 'ABSENT'));
}

function coverageBlock(item) {
    // The sub-extensions sent with no value[x] at all, named by the server so they read apart from a value of "".
    const noValue = new Set(item.sentWithNoValue || []);
    const one = (name, value) => (noValue.has(name) ? noValueMark() : shown(value, {as: 'literal'}));
    const rows = [
        codedRow('covered', [item.covered], false, noValue),
        codedRow('pa-needed', [item.paNeeded], false, noValue),
        codedRow('doc-needed', [item.docNeeded], false, noValue),
        codedRow('doc-purpose', item.docPurpose, true, noValue),
        codedRow('info-needed', item.infoNeeded, true, noValue),
        valueRow('satisfied-pa-id', one('satisfied-pa-id', item.satisfiedPaId)),
        valueRow('questionnaire', repeated('questionnaire', item.questionnaires, {as: 'literal'}, noValue)),
        valueRow('response', repeated('response', item.responses, {as: 'literal'}, noValue)),
        valueRow('reason', repeated('reason', item.reasons, null, noValue)),
        valueRow('coverage', one('coverage', item.coverage)),
        valueRow('coverage-assertion-id', one('coverage-assertion-id', item.coverageAssertionId)),
        valueRow('date', one('date', item.date))
    ];
    if (Array.isArray(item.laterReasonCodes) && item.laterReasonCodes.length > 0) {
        // Read and named, never called wrong: a later version of the guide defines these codes.
        rows.push(valueRow('reason codes of a later version', codeList(item.laterReasonCodes)));
    }
    if (item.authOutOfNetworkOnly === true || item.authOutOfNetworkOnly === false) {
        rows.push(valueRow('auth-out-network-only', [code(String(item.authOutOfNetworkOnly)), ' ', item.authOutOfNetworkOnly
            ? 'Authorization is needed only out of network.' : 'Authorization is not limited to out of network.']));
    }
    const block = el('div', {class: 'detail__item'},
        el('p', {class: 'detail__head'}, el('strong', null, 'Answer for ', code(item.subject || 'the request')), ' ',
            item.outcome ? outcomeChip('CRD', item.outcome) : null),
        el('p', {class: 'small muted'}, 'Found at ', code(item.foundAt), '. ',
            hasValue(item.appliesTo) ? ['Applies to ', shown(item.appliesTo, {as: 'literal'}), '. ']
                : ['What it applies to: ', shown(item.appliesTo), '. '],
            Array.isArray(item.procedureCodes) && item.procedureCodes.length > 0
                ? ['For the requested ', item.procedureCodes.length === 1 ? 'code ' : 'codes ',
                    item.procedureCodes.map((value, index) => [index > 0 ? ', ' : null, code(value)]), '.']
                : 'Which requested code it is for cannot be told.'));
    if (item.delegatedVendor) {
        // Where the request routes is the answer testers most often need, so it is never left in a table row.
        block.append(el('p', {class: 'detail__vendor'}, 'Delegated vendor: ', el('strong', null, item.delegatedVendor),
            item.delegatedVendorBasis ? el('span', {class: 'muted'}, ' Recognised from ', item.delegatedVendorBasis, '.') : null));
    }
    // What was returned reads first; the elements the response did not carry at all are one press
    // away. Null, empty and sent with no value are answers and stay in view.
    const open = rows.filter(row => !row.notReturned);
    const absent = rows.filter(row => row.notReturned);
    block.append(open.length > 0 ? table(['Element', 'Code returned', 'Meaning'], open.map(row => row.cells))
        : quiet('The response returned none of these elements.'));
    if (absent.length > 0) {
        block.append(fold({summary: 'Not returned', hint: '(' + absent.length + ')', inline: true,
            body: () => table(['Element', 'Code returned', 'Meaning'], absent.map(row => row.cells))}));
    }
    const details = Array.isArray(item.details) ? item.details : [];
    block.append(sub('Detail list (' + details.length + ')'), details.length === 0
        ? el('p', null, code('detail'), ' ', presenceMark('ABSENT'))
        : table(['Code', 'Value', 'Qualification', 'Category'], details.map(entry => [shown(entry.code), shown(entry.value),
            shown(entry.qualification), shown(entry.category)])));
    const others = Array.isArray(item.otherSubExtensions) ? item.otherSubExtensions : [];
    if (others.length > 0) {
        block.append(sub('Other sub-extensions (' + others.length + ')'),
            table(['Url', 'Value', 'Defined in'], others.map(entry => [code(entry.url), shown(entry.value, {as: 'literal'}),
                entry.definedIn || el('span', {class: 'muted small'}, 'not stated')])));
    }
    return block;
}

/**
 * One coded element: its name as the response spells it, the code or codes returned, and what the
 * tool says each means. A repeating sub-extension with no entry was not returned; a null or empty
 * entry is an entry, and drawn as one.
 */
function codedRow(name, values, repeats, noValue) {
    const list = Array.isArray(values) ? values.filter(Boolean) : [];
    if (list.length === 0) {
        return {notReturned: true, cells: [code(name), presenceMark('ABSENT'), null]};
    }
    const lines = cells => (cells.length === 1 ? cells[0] : el('ul', {class: 'notes'}, cells.map(cell => el('li', null, cell))));
    const sentBare = index => noValue.has(repeats ? name + '[' + index + ']' : name);
    // An element whose every entry says it was not returned is folded with the others the response did not carry.
    const absent = list.every((value, index) => (value.presence || 'ABSENT') === 'ABSENT' && !sentBare(index));
    return {notReturned: absent, cells: [code(name),
        lines(list.map((value, index) => (value.presence === 'VALUE' ? code(String(value.code))
            : sentBare(index) ? noValueMark() : presenceMark(value.presence || 'ABSENT')))),
        lines(list.map(value => (value.presence === 'VALUE'
            ? [value.display || null, value.version ? el('span', {class: 'small muted'}, ' ' + value.version) : null,
                value.recognised === false ? [' ', chip('not a code this tool knows', {tone: 'neutral'})] : null]
            : null)))]};
}

function repeated(name, values, options, noValue) {
    if (!Array.isArray(values) || values.length === 0) {
        return {absent: true, node: presenceMark('ABSENT')};
    }
    const cell = (value, index) => (noValue.has(name + '[' + index + ']') ? noValueMark() : shown(value, options));
    return {absent: false, node: values.length === 1 ? cell(values[0], 0)
        : el('ul', {class: 'notes'}, values.map((value, index) => el('li', null, cell(value, index))))};
}

/** An element that is a plain value: folded under "Not returned" exactly when the response did not carry it. */
function valueRow(name, value) {
    const isRepeated = value && typeof value === 'object' && !(value instanceof Node) && !Array.isArray(value) && 'node' in value;
    const node = isRepeated ? value.node : value;
    const notReturned = isRepeated ? value.absent : node instanceof Element && node.classList.contains('chip--none')
        && node.textContent === PRESENCE_WORDS.ABSENT;
    return {notReturned, cells: [code(name), node, null]};
}

function cardBlock(card, total, wire) {
    const links = Array.isArray(card.links) ? card.links : [];
    const suggestions = Array.isArray(card.suggestions) ? card.suggestions : [];
    const block = el('div', {class: 'detail__item'},
        el('p', {class: 'detail__head'}, el('strong', null, 'Card ' + (card.index + 1) + ' of ' + total), ' ',
            el('span', {class: 'small'}, 'indicator ', shown(card.indicator, {as: 'literal'}))),
        el('div', {class: 'detail__summary'}, shown(card.summary)),
        facts([['Detail', shown(card.detail, {as: 'block'})]].concat(sourceRows(card), [
            ['Selection behaviour', shown(card.selectionBehavior, {as: 'literal'})],
            ['Uuid', shown(card.uuid, {as: 'literal'})],
            ['Override reasons', listValue(card.lists, 'overrideReasons', card.overrideReasons, {as: 'literal'})]
        ])));
    block.append(sub('Links (' + links.length + ')'), links.length === 0
        ? listState(card.lists, 'links', 0) || quiet('The card carried no links.')
        : table(['Label', 'Type', 'Address', 'App context'], links.map(linkRow)));
    block.append(sub('Suggestions (' + suggestions.length + ')'));
    if (suggestions.length === 0) {
        block.append(listState(card.lists, 'suggestions', 0) || quiet('The card carried no suggestions.'));
    }
    suggestions.forEach((suggestion, index) => {
        const actions = Array.isArray(suggestion.actions) ? suggestion.actions : [];
        block.append(el('div', {class: 'detail__nested'},
            facts([
                ['Suggestion ' + (index + 1), shown(suggestion.label)],
                ['Uuid', shown(suggestion.uuid, {as: 'literal'})],
                ['Recommended', shown(suggestion.isRecommended, {as: 'literal'})]
            ]),
            actions.length === 0 ? listState(suggestion.lists, 'actions', 0) || quiet('This suggestion carried no actions.')
                : actions.map(action => actionBlock(action, wire))));
    });
    return block;
}

/** The source of a card: when the source itself was not returned, null or empty, that is its one row. */
function sourceRows(card) {
    if (card.source && card.source !== 'VALUE') {
        return [['Source', [code('source'), ' ', presenceMark(card.source)]]];
    }
    return [
        ['Source label', shown(card.sourceLabel)],
        ['Source url', hasValue(card.sourceUrl) ? shown(card.sourceUrl, {as: 'link', label: 'Open the source'}) : shown(card.sourceUrl)],
        ['Source icon', shown(card.sourceIcon, {as: 'literal'})],
        ['Source topic', shown(card.sourceTopic, {as: 'literal'})]
    ];
}

function linkRow(link) {
    const isSmart = hasValue(link.type) && String(link.type.value).toLowerCase() === 'smart';
    let address;
    if (!hasValue(link.url)) {
        address = shown(link.url);
    } else if (isSmart) {
        // A SMART link is what the DTR step starts from, not somewhere to go, so it stays text.
        address = [code(link.url.value), el('div', {class: 'small muted'}, 'A SMART link: DTR starts from it.')];
    } else {
        address = outLink(link.url.value, 'Open the link');
    }
    return [shown(link.label), shown(link.type, {as: 'literal'}), address, shown(link.appContext, {as: 'literal'})];
}

function actionBlock(action, wire) {
    const rest = Array.isArray(action.resource) ? action.resource : [];
    return el('div', {class: 'detail__nested'},
        el('p', {class: 'detail__head'}, el('strong', null, 'Action at ', code(action.foundAt))),
        facts([
            ['Type', shown(action.type, {as: 'literal'})],
            ['Description', shown(action.description)],
            ['Resource type', shown(action.resourceType, {as: 'literal'})],
            ['Resource id in the action', shown(action.resourceId, {as: 'literal'})],
            ['Id of the resource carried', shown(action.resourceLogicalId, {as: 'literal'})]
        ]),
        sub('The rest of the resource (' + rest.length + ')'),
        rest.length === 0 ? quiet('Nothing further was returned with this action.') : fieldsFold(rest, wire));
}

/** Which rules of the guide the findings were checked against, and which not, with the version named. */
function conformanceScope(scope) {
    if (!scope) {
        return null;
    }
    const applied = Array.isArray(scope.applied) ? scope.applied : [];
    const notApplied = Array.isArray(scope.notApplied) ? scope.notApplied : [];
    return part('What the conformance check judged by', null,
        fold({summary: scope.version || 'No version named', hint: applied.length + ' rules applied, ' + notApplied.length + ' not applied',
            inline: true, body: () => [
                sub('Applied (' + applied.length + ')'), applied.length === 0 ? quiet('None.') : noteList(applied),
                sub('Not applied (' + notApplied.length + ')'), notApplied.length === 0 ? quiet('None.') : noteList(notApplied)]}));
}

/* ---------- questionnaire (DTR) ---------- */

// The kinds for which no body was read, so an empty list below is empty because nothing was read.
const NOTHING_READ = new Set(['NO_RESPONSE', 'NO_BODY', 'NOT_SENT']);

// Whose answer it is, in the words of the information-origin extension's three codes.
const ORIGIN_WORDS = {
    auto: 'pre-populated by the payer\'s service',
    override: 'pre-populated, then changed by a person',
    manual: 'typed by a person'
};

function dtrDetail(envelope) {
    const detail = envelope.detail;
    const isPackage = envelope.operation === 'DTR_QUESTIONNAIRE_PACKAGE';
    const nothingRead = NOTHING_READ.has(detail.responseKind);
    const node = el('div', {class: 'detail detail--dtr'});
    // The words for every kind are the server's (responseNote); the page keeps none of its own.
    node.append(el('p', {class: 'detail__kind'}, detail.responseNote || null,
        el('span', {class: 'small muted'}, detail.responseNote ? ' Response kind ' : 'Response kind ', code(String(detail.responseKind)), '.')));
    append(node, issuesPart(detail.issues, envelope));
    if (detail.form) {
        node.append(formPart(detail.form));
    }
    append(node, requestedPart(detail));

    const questionnaires = Array.isArray(detail.questionnaires) ? detail.questionnaires : [];
    if (questionnaires.length > 0 || isPackage) {
        node.append(part(isPackage ? 'Questionnaires in the package' : 'The questionnaire as it now stands', questionnaires.length,
            detail.kindRule ? quiet('How the kind is told: ' + detail.kindRule) : null,
            questionnaires.length === 0
                ? quiet(nothingRead ? 'None is listed, because no response body was read.' : 'The response held no questionnaire.')
                : questionnaires.map(questionnaire => questionnaireBlock(questionnaire))));
    }
    const resources = Array.isArray(detail.resources) ? detail.resources : [];
    if (resources.length > 0 || isPackage) {
        node.append(part('Libraries, value sets and other resources', resources.length,
            resources.length === 0 ? quiet(nothingRead ? 'None is listed, because no response body was read.' : 'None came with the package.')
                : resources.map(resourceBlock)));
    }
    const bundles = Array.isArray(detail.bundles) ? detail.bundles : [];
    if (bundles.length > 0) {
        node.append(part('Package bundles', bundles.length, table(['Found at', 'Type', 'Id', 'Entries', 'Full urls of the entries'],
            bundles.map(bundle => [code(bundle.foundAt), shown(bundle.type, {as: 'literal'}), shown(bundle.id, {as: 'literal'}),
                String(bundle.entryCount), shownList(bundle.fullUrls, {as: 'literal'})]))));
    }
    const responses = Array.isArray(detail.questionnaireResponses) ? detail.questionnaireResponses : [];
    if (responses.length > 0 || !isPackage) {
        node.append(part(isPackage ? 'Questionnaire responses the package carried' : 'The QuestionnaireResponse in full', responses.length,
            responses.length === 0
                ? quiet(nothingRead && detail.responseKind !== 'NOT_SENT' ? 'None is listed, because no response body was read.'
                    : 'This step returned no QuestionnaireResponse.')
                : responses.map(responseBlock)));
    }
    append(node, requestNotesPart(detail));
    return node;
}

function questionRefs(list, empty, extra) {
    if (!Array.isArray(list) || list.length === 0) {
        return el('span', {class: 'muted'}, empty);
    }
    return el('ul', {class: 'notes'}, list.map(ref => el('li', null, code(ref.linkId), ref.text ? [' ', ref.text] : null,
        ref.note ? el('div', {class: 'small muted'}, ref.note) : null, extra ? extra(ref) : null)));
}

function statusValue(status) {
    const value = status || {};
    return value.presence === 'VALUE'
        ? [code(value.code), value.display ? ' ' + value.display : null,
            value.recognised === false ? [' ', chip('not a status this tool knows', {tone: 'neutral'})] : null]
        : presenceMark(value.presence || 'ABSENT');
}

function formPart(form) {
    const changes = form.changes;
    const answers = Array.isArray(form.answers) ? form.answers : [];
    return part('Where the questionnaire stands', null,
        facts([
            ['Questions so far', String(form.questionCount)],
            ['Enabled by the answers', String(form.enabledQuestionCount)],
            ['Answered', String(form.answeredCount)],
            ['Completion status', statusValue(form.status)],
            ['Complete', form.complete ? el('strong', null, 'Yes, as the server judged it') : 'No, as the server judged it'],
            ['Asked now', questionRefs(form.asked, 'Nothing is asked now.')],
            ['Required and unanswered', questionRefs(form.requiredUnanswered, 'None.')],
            ['Not judged', questionRefs(form.undetermined, 'None.')],
            ['The answer set', answers.length === 0 ? el('span', {class: 'muted'}, 'No answers yet.')
                : el('ul', {class: 'notes'}, answers.map(answer => el('li', null, code(answer.linkId), answer.text ? [' ', answer.text] : null, ': ',
                    el('strong', null, (answer.values || []).join('; ')))))]
        ]),
        changes ? changesBlock(changes) : null,
        Array.isArray(form.notes) && form.notes.length > 0 ? noteList(form.notes) : null);
}

/** What changed since the previous step, as the server compared the two, with the values sent for a dropped answer. */
function changesBlock(changes) {
    const appeared = changes.appeared || [];
    const disappeared = changes.disappeared || [];
    const dropped = changes.answersDropped || [];
    const before = ref => Array.isArray(ref.values) && ref.values.length > 0
        ? el('div', {class: 'small'}, 'The answer sent: ', ref.values.map((value, index) => [index > 0 ? '; ' : null, el('strong', null, value)]))
        : null;
    return el('div', {class: 'detail__changes'},
        changes.summary ? el('p', null, changes.summary) : null,
        changes.compared && appeared.length + disappeared.length + dropped.length > 0 ? facts([
            ['Appeared', questionRefs(appeared, 'None.')],
            ['Disappeared', questionRefs(disappeared, 'None.')],
            ['Answers the service dropped', questionRefs(dropped, 'None.', before)]
        ]) : null);
}

function requestedPart(detail) {
    const requested = Array.isArray(detail.requested) ? detail.requested : [];
    const missing = Array.isArray(detail.notReturned) ? detail.notReturned : [];
    if (requested.length === 0 && missing.length === 0) {
        return null;
    }
    const others = missing.filter(url => !requested.includes(url));
    return part('Questionnaires asked for by url', requested.length,
        el('ul', {class: 'notes'}, requested.map(url => el('li', null, code(url),
            missing.includes(url) ? [' ', chip('not returned', {tone: 'stop'})] : null))),
        others.length > 0 ? el('p', null, 'Not returned: ', others.map((url, index) => [index > 0 ? ', ' : null, code(url)])) : null);
}

/** The kind of a questionnaire in the server's words, or the code it sent when it sent no words. */
function kindWord(view) {
    return view && view.kindLabel ? view.kindLabel : String(view ? view.kind : '');
}

function questionnaireBlock(q) {
    const problems = Array.isArray(q.problems) ? q.problems : [];
    const usable = problems.length === 0;
    const notApplied = Array.isArray(q.notApplied) ? q.notApplied : [];
    return el('div', {class: 'detail__item'},
        el('p', {class: 'detail__head'}, el('strong', null, q.position + '. ', q.subject), ' ', chip(kindWord(q), {tone: 'neutral'})),
        el('p', {class: 'small muted'}, 'Where it was found: ', code(q.foundAt)),
        facts([
            ['Title', shown(q.title)],
            ['Url', shown(q.url, {as: 'literal'})],
            ['Version', shown(q.version, {as: 'literal'})],
            ['Canonical', q.canonical ? code(q.canonical) : noneMark('none: it has no url')],
            ['Name', shown(q.name, {as: 'literal'})],
            ['Id', shown(q.id, {as: 'literal'})],
            ['Status', statusValue(q.status)],
            ['Kind', [kindWord(q), q.kindBasis ? el('div', {class: 'small'}, q.kindBasis) : null]],
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
        ]),
        usable ? null : el('div', {class: 'detail__problems'},
            el('p', null, el('strong', null, 'This questionnaire cannot be answered.'), ' What makes it unusable, as the server found it:'),
            noteList(problems)),
        notApplied.length === 0 ? null : el('div', {class: 'detail__notapplied'},
            el('p', null, el('strong', null, notApplied.length === 1 ? 'A rule this tool does not apply.' : notApplied.length + ' rules this tool does not apply.'),
                ' A blank where one of them would put a value is not an answer of "no value".'),
            noteList(notApplied)),
        Array.isArray(q.extensions) && q.extensions.length > 0 ? [sub('Extensions on the questionnaire (' + q.extensions.length + ')'),
            table(['Url', 'Value'], q.extensions.map(extension => [shown(extension.url, {as: 'literal'}), shown(extension.value, {as: 'literal'})]))] : null,
        Array.isArray(q.contained) && q.contained.length > 0 ? [sub('Resources it carries inside itself (' + q.contained.length + ')'),
            q.contained.map(resourceBlock)] : null,
        // A questionnaire that cannot be answered gets no form in step 2, so its items are listed here.
        usable ? null : [sub('Its items as returned'), itemsTable(q.items || [])]);
}

/**
 * The items of a questionnaire as the server read them, one row each, nested rows indented by
 * depth. Walked with a stack: the depth is the service's.
 */
function itemsTable(items) {
    const rows = [];
    const stack = items.slice().reverse().map(item => ({item, depth: 0}));
    while (stack.length > 0) {
        const {item, depth} = stack.pop();
        if (!item || typeof item !== 'object') {
            continue;
        }
        const children = Array.isArray(item.items) ? item.items : [];
        rows.push({class: 'detail__depth-' + Math.min(depth, 6), cells: [
            [shown(item.linkId, {as: 'literal'}), item.path ? el('div', {class: 'small muted'}, code(item.path)) : null],
            [shown(item.text), hasValue(item.prefix) ? el('div', {class: 'small muted'}, 'prefix ', shown(item.prefix)) : null],
            item.type && item.type.presence === 'VALUE' ? code(String(item.type.code)) : presenceMark(item.type ? item.type.presence : 'ABSENT'),
            shown(item.required, {as: 'literal'}),
            [item.enabledReason || null, children.length === 0 ? listState(item.lists, 'item', 0) : null]
        ]});
        for (let i = children.length - 1; i >= 0; i--) {
            stack.push({item: children[i], depth: depth + 1});
        }
    }
    return rows.length === 0 ? noneMark('no items') : table(['linkId', 'Question', 'Type', 'Required', 'Enabled'], rows);
}

function resourceBlock(resource) {
    const content = Array.isArray(resource.content) ? resource.content : [];
    const concepts = Array.isArray(resource.concepts) ? resource.concepts : [];
    return el('div', {class: 'detail__nested'},
        el('p', null, el('strong', null, shown(resource.resourceType)), ' ', hasValue(resource.title) ? shown(resource.title) : shown(resource.name),
            el('span', {class: 'small muted'}, ' at ', code(resource.foundAt))),
        facts([
            ['Url', shown(resource.url, {as: 'literal'})],
            ['Version', shown(resource.version, {as: 'literal'})],
            ['Name', shown(resource.name, {as: 'literal'})],
            ['Id', shown(resource.id, {as: 'literal'})],
            ['Status', shown(resource.status, {as: 'literal'})],
            ['Concepts', concepts.length > 0 ? el('ul', {class: 'notes'}, concepts.map(concept => el('li', null, shown(concept)))) : null],
            ['Content', content.length > 0 ? el('ul', {class: 'notes'}, content.map(piece => el('li', null,
                shown(piece.contentType, {as: 'literal'}),
                hasValue(piece.url) ? [' at ', shown(piece.url, {as: 'literal'})] : null, ', ',
                piece.dataPresence === 'VALUE' ? [String(piece.dataCharacters), ' characters of encoded data, whole in the wire traffic']
                    : ['inline data ', presenceMark(piece.dataPresence || 'ABSENT')]))) : null]
        ]));
}

function responseBlock(response) {
    const extensions = Array.isArray(response.extensions) ? response.extensions : [];
    return el('div', {class: 'detail__item'},
        el('p', {class: 'detail__head'}, el('strong', null, 'QuestionnaireResponse ', shown(response.id, {as: 'literal'})), ' ',
            el('span', {class: 'small'}, response.answeredCount + (response.answeredCount === 1 ? ' item answered' : ' items answered'))),
        el('p', {class: 'small muted'}, 'Where it was found: ', code(response.foundAt)),
        facts([
            ['Answers questionnaire', shown(response.questionnaire, {as: 'literal'})],
            ['Status', statusValue(response.status)],
            ['Authored', shown(response.authored, {as: 'literal'})],
            ['Subject', shown(response.subject, {as: 'literal'})],
            ['Author', shown(response.author, {as: 'literal'})],
            ['Profiles', shownList(response.profiles, {as: 'literal'})],
            ['Extensions on the response', extensions.length === 0 ? noneMark() : null]
        ]),
        extensions.length > 0
            ? table(['Extension', 'Value'], extensions.map(extension => [shown(extension.url, {as: 'literal'}), shown(extension.value, {as: 'literal'})]))
            : null,
        responseItems(Array.isArray(response.items) ? response.items : []));
}

/** Whose answer it is, as the information-origin extension states, with the code itself. */
function originMark(origin) {
    if (!origin || !hasValue(origin.source)) {
        return el('span', {class: 'muted'}, 'where it came from is not stated',
            origin && origin.source && origin.source.presence !== 'ABSENT' ? [' (source ', presenceMark(origin.source.presence), ')'] : null);
    }
    const source = String(origin.source.value);
    const known = Object.prototype.hasOwnProperty.call(ORIGIN_WORDS, source);
    return el('span', {class: 'detail__origin detail__origin--' + (known ? token(source) : 'other')},
        known ? ORIGIN_WORDS[source] : 'a source this tool does not know', ' (', code(source), ')',
        hasValue(origin.author) ? [', author ', code(String(origin.author.value))] : null,
        Array.isArray(origin.roles) && origin.roles.length > 0 ? [', role ', shownList(origin.roles, {as: 'literal'})] : null);
}

/** The items of a QuestionnaireResponse, nested rows indented by depth. Walked with a stack: the depth is the service's. */
function responseItems(items) {
    if (items.length === 0) {
        return el('p', {class: 'small'}, 'Items: ', noneMark(), ' A response that holds no item answers nothing.');
    }
    const rows = [];
    const stack = items.slice().reverse().map(item => ({item, depth: 0}));
    while (stack.length > 0) {
        const {item, depth} = stack.pop();
        if (!item || typeof item !== 'object') {
            continue;
        }
        const answers = Array.isArray(item.answers) ? item.answers : [];
        const origins = Array.isArray(item.origins) ? item.origins : [];
        const answerExtensions = Array.isArray(item.answerExtensions) ? item.answerExtensions : [];
        rows.push({class: 'detail__depth-' + Math.min(depth, 6), cells: [
            [shown(item.linkId, {as: 'literal'}), item.path ? el('div', {class: 'small muted'}, code(item.path)) : null],
            shown(item.text),
            answers.length === 0 ? noneMark('no answer') : el('ul', {class: 'notes'}, answers.map(answer => el('li', null, shown(answer)))),
            answers.length === 0 ? null : el('ul', {class: 'notes'}, answers.map((answer, index) => el('li', null, originMark(origins[index])))),
            answerExtensions.length === 0 ? null : el('ul', {class: 'notes'}, answerExtensions.map(extension => el('li', null,
                shown(extension.url, {as: 'literal'}), ' = ', shown(extension.value, {as: 'literal'}))))
        ]});
        const children = Array.isArray(item.items) ? item.items : [];
        for (let i = children.length - 1; i >= 0; i--) {
            stack.push({item: children[i], depth: depth + 1});
        }
    }
    return table(['linkId', 'Question', 'Answers', 'Whose answer', 'Other extensions'], rows);
}

/* ---------- claim (PAS) ---------- */

const PAS_KINDS = Object.assign({
    BUNDLE: 'The response is a FHIR Bundle and was read entry by entry.',
    CLAIM_RESPONSE: 'The response is a ClaimResponse on its own, not wrapped in a bundle.',
    OTHER_JSON: 'The response is JSON, but none of the resources this tool reads. All of it is listed under the returned fields.'
}, COMMON_KINDS);

function pasDetail(envelope) {
    const detail = envelope.detail;
    const node = el('div', {class: 'detail detail--pas'});
    node.append(kindLine(PAS_KINDS, detail.responseKind, detail.responseNote));
    const responses = Array.isArray(detail.claimResponses) ? detail.claimResponses : [];
    if (responses.length === 0) {
        node.append(part('Decision for each item', 0, quiet('The response carried no ClaimResponse, so no item has a decision.')));
    }
    responses.forEach((response, index) => node.append(claimResponseBlock(response, responses.length > 1 ? index + 1 : 0, envelope.wire)));
    append(node, issuesPart(detail.issues, envelope), otherResourcesPart(detail.otherResources, envelope.wire), bundlePart(detail),
        requestNotesPart(detail, [
            ['Claim identifier sent', detail.claimId ? code(detail.claimId) : noneMark('none: the request carried no claim identifier')],
            ['Attachments', envelope.operation === 'PAS_INQUIRE' || (!detail.attachmentNote && !detail.attachmentMode) ? null
                : [detail.attachmentNote || null, detail.attachmentMode ? el('div', {class: 'small muted'}, 'Attachment mode ', code(detail.attachmentMode)) : null]]
        ]));
    return node;
}

/**
 * One ClaimResponse: the decisions item by item first, then the response as a whole, what is still
 * awaited, the errors, the process notes and the items in full. number: its position when the
 * answer holds several, else 0.
 */
function claimResponseBlock(response, number, wire) {
    const block = el('div', {class: 'detail__claim'});
    if (number > 0) {
        const name = hasValue(response.id) ? 'ClaimResponse/' + response.id.value : response.foundAt;
        block.append(el('p', {class: 'detail__head'}, el('strong', null, 'ClaimResponse ' + number + ': ', code(name))));
    }
    block.append(decisionsPart(response), wholePart(response), awaitedPart(response.attachments || [], response.lists, wire),
        errorsPart(response.errors || [], response.lists), processNotesPart(response.processNotes || [], response.lists),
        itemsPart(response.items || [], response.lists));
    return block;
}

function decisionsPart(response) {
    const decisions = Array.isArray(response.decisions) ? response.decisions : [];
    if (decisions.length === 0) {
        return part('Decision for each item', 0, quiet('This ClaimResponse states no decision for any item.'));
    }
    const rows = decisions.map(decision => ({class: 'detail__decision detail__decision--' + token(String(decision.source || 'NONE').toLowerCase()), cells: [
        // The sequence is drawn only when the subject does not already say it, as "item 1" does.
        decision.subject ? [code(decision.subject), decision.sequence !== undefined && decision.sequence !== null && decision.subject !== 'item ' + decision.sequence
            ? el('div', {class: 'small muted'}, 'item ' + decision.sequence) : null]
            : 'The request as a whole',
        // A decision stated on its own item is the usual case and is not marked, so an inherited one stands out.
        [outcomeChip('PAS', decision.outcome), decision.source !== 'ITEM' && decision.sourceLabel
            ? el('div', {class: 'small'}, decision.sourceLabel) : null],
        Array.isArray(decision.reviewActions) && decision.reviewActions.length > 0
            ? el('ul', {class: 'notes'}, decision.reviewActions.map(action => el('li', null, coded(action)))) : noneMark(),
        authorization(decision),
        [Array.isArray(decision.reasonCodes) && decision.reasonCodes.length > 0 ? el('div', null, 'Reasons ', codeList(decision.reasonCodes)) : null,
            Array.isArray(decision.errorCodes) && decision.errorCodes.length > 0 ? el('div', null, 'Errors ', codeList(decision.errorCodes)) : null,
            !(decision.reasonCodes || []).length && !(decision.errorCodes || []).length ? noneMark() : null,
            decision.foundAt ? el('div', {class: 'small muted'}, 'at ', code(decision.foundAt))
                : el('div', {class: 'small muted'}, 'The response does not list this item.')]
    ]}));
    return part('Decision for each item', decisions.length,
        quiet('One row for every item asked about, listed or not. The decision is read from the review action code, never from the payer\'s wording.'),
        table(['Item', 'Decision', 'Review action as returned', 'Authorization', 'Reasons and errors'], rows, 'detail__decisions'));
}

/** The authorization number and period stated for one item, where PAS puts them. */
function authorization(decision) {
    const numbers = Array.isArray(decision.authorizationNumbers) ? decision.authorizationNumbers : [];
    const from = decision.authorizedFrom;
    const to = decision.authorizedTo;
    // Null when no item carried the itemPreAuthPeriod extension; otherwise what its period was.
    const period = decision.authorizedPeriod || null;
    if (numbers.length === 0 && !from && !to && !period) {
        return noneMark();
    }
    return el('div', null,
        numbers.length > 0 ? el('div', null, numbers.map((value, index) => [index > 0 ? ', ' : null, code(value)])) : el('div', null, 'Number ', noneMark()),
        el('div', {class: 'small'}, period && period !== 'VALUE' ? periodWords(period)
            : ['Valid from ', from ? code(from) : presenceMark('ABSENT'), ' to ', to ? code(to) : presenceMark('ABSENT')]));
}

/** An itemPreAuthPeriod with no period in it: sent with none, with a null one or an empty one are three answers. */
function periodWords(period) {
    if (period === 'ABSENT') {
        return [code('itemPreAuthPeriod'), ' was sent with no ', code('valuePeriod'), ' in it'];
    }
    return [code('itemPreAuthPeriod'), ' ', code('valuePeriod'), ' ', presenceMark(period)];
}

function wholePart(response) {
    const actions = Array.isArray(response.reviewActions) ? response.reviewActions : [];
    const added = Array.isArray(response.addItemReviewActions) ? response.addItemReviewActions : [];
    const extensions = Array.isArray(response.extensions) ? response.extensions : [];
    return part('The response as a whole', null,
        facts([
            ['What it establishes', outcomeChip('PAS', response.decision)],
            ['Authorization number', [shown(response.preAuthRef, {as: 'literal'}), el('div', {class: 'small muted'}, 'ClaimResponse.preAuthRef. An item\'s own number is in the table above.')]],
            ['Authorized period', response.preAuthPeriod && response.preAuthPeriod !== 'VALUE'
                ? [code('preAuthPeriod'), ' ', presenceMark(response.preAuthPeriod)]
                : ['from ', shown(response.preAuthPeriodStart, {as: 'literal'}), ' to ', shown(response.preAuthPeriodEnd, {as: 'literal'})]],
            ['Outcome code', [coded(response.outcome), el('div', {class: 'small muted'}, 'How far processing got, not what was decided.')]],
            ['Disposition', [shown(response.disposition, {as: 'block'}), el('div', {class: 'small muted'}, 'The payer\'s wording, never read as a decision.')]],
            ['Answers to', [shown(response.request, {as: 'literal'}), hasValue(response.requestIdentifier)
                ? el('div', {class: 'small'}, 'Claim identifier ', shown(response.requestIdentifier, {as: 'literal'})) : null]],
            ['Claim identifier echoed', hasValue(response.requestIdentifier) ? null : shown(response.requestIdentifier)],
            ['Identifiers', listValue(response.lists, 'identifier', response.identifiers, {as: 'literal'})],
            ['Id', shown(response.id, {as: 'literal'})],
            ['Full url', shown(response.fullUrl, {as: 'literal'})],
            ['Status', shown(response.status, {as: 'literal'})],
            ['Type', shown(response.type, {as: 'literal'})],
            ['Use', shown(response.use, {as: 'literal'})],
            ['Created', shown(response.created, {as: 'literal'})],
            ['Patient', shown(response.patient, {as: 'literal'})],
            ['Insurer', shown(response.insurer, {as: 'literal'})],
            ['Requestor', shown(response.requestor, {as: 'literal'})],
            ['Found at', code(response.foundAt)]
        ]),
        response.subsetted ? el('p', null, chip('SUBSETTED', {tone: 'neutral'}), ' The ClaimResponse declares that it answers only some of the items of the request.') : null,
        actions.length > 0 ? [sub('Review actions on the ClaimResponse itself (' + actions.length + ')'), actions.map(reviewActionBlock)] : null,
        added.length > 0 ? [sub('Review actions on items the payer added (' + added.length + ')'), el('ul', {class: 'notes'}, added.map(text => el('li', null, code(text)))),
            quiet('They decide nothing for the items asked about, and are listed among the fields returned but not interpreted.')] : null,
        extensions.length > 0 ? [sub('Other extensions (' + extensions.length + ')'), extensionList(extensions)] : null);
}

/** One review action with every part of it. The code decides; everything else is shown beside it. */
function reviewActionBlock(action) {
    return el('div', {class: 'detail__nested'},
        action.placement ? el('p', null, chip('outside the guide\'s place', {tone: 'neutral'}), ' ', action.placement) : null,
        facts([
            ['Code', coded(action.code)],
            ['As the payer wrote it', shown(action.returned, {as: 'literal'})],
            ['Authorization number', shown(action.number, {as: 'literal'})],
            ['Reason', [shown(action.reason), Array.isArray(action.reasonCodes) && action.reasonCodes.length > 0 ? el('div', {class: 'small'}, codeList(action.reasonCodes)) : null]],
            ['Second surgical opinion', shown(action.secondSurgicalOpinionNeeded, {as: 'literal'})],
            ['Other parts', Array.isArray(action.otherParts) && action.otherParts.length > 0 ? extensionList(action.otherParts) : noneMark()],
            ['Found at', code(action.foundAt)]
        ]));
}

function awaitedPart(attachments, lists, wire) {
    if (attachments.length === 0) {
        return part('Documentation the payer asks for', 0, quiet('The response refers to no request for supporting documentation.'),
            sentEmpty(lists, 'communicationRequest'));
    }
    return part('Documentation the payer asks for', attachments.length, attachments.map(item => el('div', {class: 'detail__item'},
        el('p', {class: 'detail__head'}, el('strong', null, 'Request for documentation ', shown(item.reference, {as: 'literal'})), ' ',
            item.open ? chip('open', {tone: 'action'}) : chip('closed', {tone: 'neutral'})),
        facts([
            ['What is asked for', shownList(item.wanted)],
            ['Category', shown(item.category, {as: 'literal'})],
            ['Status', item.carried ? shown(item.status, {as: 'literal'})
                : [noneMark('not known'), el('div', {class: 'small muted'}, 'The response refers to the request but does not carry it, so it counts as open.')]],
            ['Found at', code(item.foundAt)]
        ]),
        Array.isArray(item.rest) && item.rest.length > 0 ? [sub('The rest of that resource (' + item.rest.length + ')'), fieldsFold(item.rest, wire)] : null)));
}

function errorsPart(errors, lists) {
    if (errors.length === 0) {
        return part('Errors the payer returned', 0, listState(lists, 'error', 0) || quiet('The ClaimResponse lists no errors.'));
    }
    return part('Errors the payer returned', errors.length, table(['Code', 'Where in the request', 'Item, detail, sub-detail', 'Follow-up'],
        errors.map(error => [
            [shown(error.code, {as: 'literal'}), extensionList(error.extensions)],
            [shown(error.element, {as: 'literal'}), hasValue(error.path) ? el('div', {class: 'small'}, shown(error.path, {as: 'literal'})) : null],
            [shown(error.itemSequence, {as: 'literal'}), ' / ', shown(error.detailSequence, {as: 'literal'}), ' / ', shown(error.subDetailSequence, {as: 'literal'})],
            [shown(error.followupAction, {as: 'literal'}), el('div', {class: 'small muted'}, 'at ', code(error.foundAt))]
        ])));
}

function processNotesPart(notes, lists) {
    if (notes.length === 0) {
        return part('Process notes', 0, listState(lists, 'processNote', 0) || quiet('The ClaimResponse carries no process notes.'));
    }
    return part('Process notes', notes.length, table(['Number', 'Type', 'Text, as the payer wrote it'], notes.map(note => [
        shown(note.number, {as: 'literal'}), shown(note.type, {as: 'literal'}), shown(note.text, {as: 'block'})])));
}

function itemsPart(items, lists) {
    if (items.length === 0) {
        return part('Items as returned', 0, listState(lists, 'item', 0) || quiet('The ClaimResponse lists no items.'));
    }
    return part('Items as returned', items.length, fold({summary: 'Every adjudication and extension', hint: items.length + (items.length === 1 ? ' item' : ' items'),
        inline: true, body: () => items.map(item => el('div', {class: 'detail__item'},
            el('p', {class: 'detail__head'}, el('strong', null, 'Item ', shown(item.itemSequence, {as: 'literal'}),
                // An item tied to no code is the subject "item n" of itself, which would read "Item 1 for item 1".
                item.subject && item.procedureCode ? [' for ', code(item.subject)] : null), ' ',
                el('span', {class: 'small muted'}, 'at ', code(item.foundAt))),
            item.procedureCode ? null : quiet('This item could not be tied to a requested procedure code.'),
            Array.isArray(item.reviewActions) && item.reviewActions.length > 0 ? [sub('Review actions on the item itself'), item.reviewActions.map(reviewActionBlock)] : null,
            sub('Adjudications (' + (item.adjudications || []).length + ')'),
            (item.adjudications || []).length === 0 ? listState(item.lists, 'adjudication', 0) || quiet('None returned.')
                : item.adjudications.map(adjudication => el('div', {class: 'detail__nested'},
                    facts([
                        ['Category', shown(adjudication.category, {as: 'literal'})],
                        ['Reason', shown(adjudication.reason, {as: 'literal'})],
                        ['Amount', shown(adjudication.amount, {as: 'literal'})],
                        ['Value', shown(adjudication.value, {as: 'literal'})],
                        ['Found at', code(adjudication.foundAt)]
                    ]),
                    (adjudication.reviewActions || []).map(reviewActionBlock),
                    (adjudication.extensions || []).length > 0 ? [sub('Other extensions'), extensionList(adjudication.extensions)]
                        : sentEmpty(adjudication.lists, 'extension'))),
            (item.extensions || []).length > 0 ? [sub('Extensions on the item'), extensionList(item.extensions)] : sentEmpty(item.lists, 'extension'),
            (item.noteNumbers || []).length > 0 ? el('p', null, 'Process notes ', shownList(item.noteNumbers, {as: 'literal'}))
                : sentEmpty(item.lists, 'noteNumber')))}));
}

function otherResourcesPart(resources, wire) {
    if (!Array.isArray(resources) || resources.length === 0) {
        return null;
    }
    return part('Other resources the bundle carried', resources.length, resources.map(resource => el('div', {class: 'detail__item'},
        el('p', {class: 'detail__head'}, el('strong', null, shown(resource.resourceType, {as: 'literal'}), ' ', shown(resource.id, {as: 'literal'})), ' ',
            el('span', {class: 'small muted'}, 'at ', code(resource.foundAt))),
        facts([['Full url', shown(resource.fullUrl, {as: 'literal'})]]),
        Array.isArray(resource.fields) && resource.fields.length > 0 ? fieldsFold(resource.fields, wire) : quiet('Nothing else was returned with it.'))));
}

function bundlePart(detail) {
    if (detail.responseKind !== 'BUNDLE') {
        return null;
    }
    return part('The bundle', null, facts([
        ['Type', shown(detail.bundleType, {as: 'literal'})],
        ['Id', shown(detail.bundleId, {as: 'literal'})],
        ['Timestamp', shown(detail.bundleTimestamp, {as: 'literal'})]
    ]));
}

/* ---------- subscriptions ---------- */

const SUBSCRIPTION_KINDS = Object.assign({
    SUBSCRIPTION: 'The response is one Subscription resource.',
    BUNDLE: 'The response is a bundle, read as a list of subscriptions.',
    OTHER_JSON: 'The response is JSON, but not a subscription. All of it is listed under the returned fields.'
}, COMMON_KINDS);

function subscriptionDetail(envelope) {
    const detail = envelope.detail;
    const operation = envelope.operation;
    const node = el('div', {class: 'detail detail--subscription'});
    node.append(kindLine(SUBSCRIPTION_KINDS, detail.responseKind, detail.responseNote));
    node.append(facts([
        ['Subscription this run is about', detail.subscriptionId
            ? [code(detail.subscriptionId), detail.idSource ? el('div', {class: 'small muted'}, 'Read from ' + detail.idSource + '.') : null]
            : noneMark(operation === 'SUBSCRIPTION_GET' ? 'none: a list' : 'none stated')],
        ['Url this request named for call back', operation === 'SUBSCRIPTION_CREATE' || operation === 'SUBSCRIPTION_UPDATE'
            ? (detail.callbackUrl ? code(detail.callbackUrl) : el('strong', null, 'none: the request named no url to call back')) : null],
        ['Bundle', detail.responseKind === 'BUNDLE' ? ['type ', shown(detail.bundleType, {as: 'literal'}), ', id ', shown(detail.bundleId, {as: 'literal'}),
            ', total stated ', shown(detail.total, {as: 'literal'})] : null]
    ]));
    const subscriptions = Array.isArray(detail.subscriptions) ? detail.subscriptions : [];
    node.append(part('Subscriptions in the answer', subscriptions.length,
        subscriptions.length === 0 ? quiet('The answer carried no Subscription resource.') : subscriptions.map(subscriptionBlock)));
    append(node, issuesPart(detail.issues, envelope), otherResourcesPart(detail.otherResources, envelope.wire));
    if (operation !== 'SUBSCRIPTION_GET' && operation !== 'SUBSCRIPTION_DELETE') {
        append(node, requestNotesPart(detail));
    }
    return node;
}

function subscriptionBlock(subscription) {
    const headers = Array.isArray(subscription.headers) ? subscription.headers : [];
    return el('div', {class: 'detail__item'},
        el('p', {class: 'detail__head'}, el('strong', null, 'Subscription ', shown(subscription.id, {as: 'literal'})), ' ',
            el('span', {class: 'small muted'}, 'at ', code(subscription.foundAt))),
        facts([
            ['Status', coded(subscription.status)],
            ['Subscribed to', shown(subscription.criteria, {as: 'literal'})],
            ['Filters', shownList(subscription.filters, {as: 'literal'})],
            // What the service answered, which can differ from what the request named: two statements, labelled as two.
            ['The service says it will call back', shown(subscription.endpoint, {as: 'literal'})],
            ['Channel', shown(subscription.channelType, {as: 'literal'})],
            ['Heartbeat, seconds', shown(subscription.heartbeat, {as: 'literal'})],
            ['Last error', shown(subscription.error)],
            ['Payload', shown(subscription.payload, {as: 'literal'})],
            ['Payload content', coded(subscription.payloadContent)],
            // A header's value is a credential for the receiver: only the names are sent and shown.
            ['Headers sent with a notification', headers.length === 0 ? noneMark()
                : [shownList(headers, {as: 'literal'}), el('div', {class: 'small muted'}, 'Names only. A header\'s value is never shown.')]],
            ['Timeout', shown(subscription.timeout, {as: 'literal'})],
            ['Most per notification', shown(subscription.maxCount, {as: 'literal'})],
            ['Reason', shown(subscription.reason)],
            ['Ends', shown(subscription.end, {as: 'literal'})],
            ['Profiles', shownList(subscription.profiles, {as: 'literal'})],
            ['Full url', shown(subscription.fullUrl, {as: 'literal'})],
            ['Why the list returned it', shown(subscription.searchMode, {as: 'literal'})]
        ]),
        Array.isArray(subscription.extensions) && subscription.extensions.length > 0
            ? [sub('Other extensions (' + subscription.extensions.length + ')'), extensionList(subscription.extensions)] : null);
}

/**
 * A notification held as a run: its events and the claim responses it carried, drawn as a claim's
 * are, so a decision that arrived by notification reads as one that arrived in answer.
 */
function notificationDetail(envelope) {
    const detail = envelope.detail;
    const node = el('div', {class: 'detail detail--notification'});
    const events = Array.isArray(detail.events) ? detail.events : [];
    node.append(facts([
        ['Notification type', coded(detail.type)],
        ['Subscription status', coded(detail.subscriptionStatus)],
        ['Subscription', shown(detail.subscription, {as: 'literal'})],
        ['Topic', shown(detail.topic, {as: 'literal'})],
        ['Events since the subscription started', shown(detail.eventsSinceSubscriptionStart, {as: 'literal'})],
        ['Errors', Array.isArray(detail.errors) && detail.errors.length > 0 ? shownList(detail.errors) : detail.errorsPresence && detail.errorsPresence !== 'VALUE'
            ? [code('error'), ' ', presenceMark(detail.errorsPresence)] : noneMark()]
    ]));
    if (events.length > 0) {
        node.append(part('Events', events.length, table(['Number', 'Timestamp', 'Focus', 'Further resources'], events.map(event => [
            shown(event.eventNumber, {as: 'literal'}), shown(event.timestamp, {as: 'literal'}), shown(event.focus, {as: 'literal'}),
            Array.isArray(event.additionalContext) && event.additionalContext.length > 0 ? shownList(event.additionalContext, {as: 'literal'})
                : event.additionalContextPresence && event.additionalContextPresence !== 'VALUE'
                    ? presenceMark(event.additionalContextPresence) : noneMark()]))));
    }
    const responses = Array.isArray(detail.claimResponses) ? detail.claimResponses : [];
    responses.forEach((response, index) => node.append(claimResponseBlock(response, responses.length > 1 ? index + 1 : 0, envelope.wire)));
    append(node, issuesPart(detail.issues, envelope), otherResourcesPart(detail.otherResources, envelope.wire));
    return node;
}
