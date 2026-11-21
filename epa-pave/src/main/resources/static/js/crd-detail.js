// The CRD part of a result: everything the tool understood in a CDS Hooks response.
//
// Every coverage information extension with every sub-extension and the whole detail list, every
// card with every field, every system action with the rest of its resource, and suggestions with
// their actions. A field the response did not carry is shown as not returned, never left out:
// the space where a field should be is where a contract mismatch shows.
//
// What a card says is vendor text. It is shown as the characters it is, with its line breaks.
// It is never read as markdown or as markup.

import {el, append, code, facts, table, heading, externalLink} from './dom.js';
import {shown, presenceMark, hasValue, noValueMark} from './shown.js';
import {outcome} from './marks.js';
import {additionalFieldsTable, issuesTable, listState as presenceOfList, listValue, fieldsFold} from './result.js';

// What kind of thing came back, said before any of it is shown. A kind this page does not know
// is shown under its own name.
const RESPONSE_KINDS = {
    CDS_HOOKS: 'The response is a JSON object and was read as a CDS Hooks response.',
    OPERATION_OUTCOME: 'The response is a FHIR OperationOutcome.',
    OTHER_JSON: 'The response is JSON, but it is neither a CDS Hooks response nor an OperationOutcome. All of it is listed under the returned fields below.',
    NOT_JSON: 'The response body could not be read as JSON. It is shown whole under the returned fields below, and in the wire view.',
    NO_BODY: 'The response has no body.',
    NO_RESPONSE: 'No response arrived.'
};

/**
 * vocabulary.outcomes: a map from outcome code to {label, tone}, as the scenarios endpoint served
 * it, used to word the outcome of each coverage information extension.
 */
// The wire of the result being drawn, so the rest of a resource an action carried is drawn masked
// wherever the wire view masked a secret the service sent back.
let shownWire = null;

export function crdDetail(envelope, vocabulary) {
    shownWire = envelope.wire || null;
    const detail = envelope.detail;
    const node = el('div', {class: 'detail'});

    // The server words some kinds itself. Where its sentence is the one this page has for the
    // kind, it is said once.
    const known = Object.prototype.hasOwnProperty.call(RESPONSE_KINDS, detail.responseKind) ? RESPONSE_KINDS[detail.responseKind] : null;
    const note = detail.responseNote && detail.responseNote !== known ? detail.responseNote : null;
    node.append(el('p', null, known || ['Response kind ', code(String(detail.responseKind)), '.'], note ? [' ', note] : null));

    const issues = detail.issues || [];
    if (issues.length > 0) {
        const alsoInPanel = envelope.error && envelope.error.issues && envelope.error.issues.length > 0;
        node.append(el('section', {class: 'detail__part'}, heading(4, 'Issues in the OperationOutcome', issues.length),
            alsoInPanel
                ? el('p', {class: 'quiet'}, 'They are listed issue by issue in the error panel at the end of this result.')
                : issuesTable(issues)));
    }

    const coverage = detail.coverageInformation || [];
    node.append(el('section', {class: 'detail__part'}, heading(4, 'Coverage information', coverage.length),
        coverage.length === 0
            ? el('p', {class: 'quiet'}, 'The response carried no coverage information extension: not in a system action, not on a card, not on a suggestion.')
            : coverage.map(item => coverageBlock(item, vocabulary))));

    const cards = detail.cards || [];
    node.append(el('section', {class: 'detail__part'}, heading(4, 'Cards', cards.length),
        listState(detail.cardsPresence, cards.length, 'cards'),
        cards.map(card => cardBlock(card, cards.length))));

    const actions = detail.systemActions || [];
    node.append(el('section', {class: 'detail__part'}, heading(4, 'System actions', actions.length),
        listState(detail.systemActionsPresence, actions.length, 'systemActions'),
        actions.map(action => actionBlock(action))));

    node.append(requestNotes(detail));
    node.append(conformanceScope(detail.conformance));
    return node;
}

/** Says whether a list was in the response at all, when it holds nothing to show. */
function listState(presence, length, name) {
    if (length > 0) {
        return null;
    }
    if (presence === 'VALUE') {
        return el('p', {class: 'quiet'}, code(name), ' was returned, and none of its entries could be read as one.');
    }
    return el('p', null, code(name), ' ', presenceMark(presence || 'ABSENT'));
}

/* ---------- coverage information ---------- */

function coverageBlock(item, vocabulary) {
    const known = vocabulary && vocabulary.outcomes ? vocabulary.outcomes.get(item.outcome) : null;
    // The sub-extensions sent with no value[x] at all. The server shows each as empty, as it does a
    // value of "", and names them here so the screen can tell the two apart.
    const noValue = new Set(item.sentWithNoValue || []);
    const codedRow = (name, values, repeats) => codedRowOf(name, values, repeats, noValue);
    const one = (name, value) => (noValue.has(name) ? noValueMark() : shown(value, {as: 'literal'}));
    const repeated = (name, values, options) => repeatedOf(name, values, options, noValue);
    const rows = [
        codedRow('covered', [item.covered]),
        codedRow('pa-needed', [item.paNeeded]),
        codedRow('doc-needed', [item.docNeeded]),
        codedRow('doc-purpose', item.docPurpose, true),
        codedRow('info-needed', item.infoNeeded, true),
        valueRow('satisfied-pa-id', one('satisfied-pa-id', item.satisfiedPaId)),
        valueRow('questionnaire', repeated('questionnaire', item.questionnaires, {as: 'literal'})),
        valueRow('response', [repeated('response', item.responses, {as: 'literal'}), (item.responses || []).length > 0
            ? el('div', {class: 'small quiet'}, 'A partly completed QuestionnaireResponse that DTR can start from. The next step says whether it is handed on.')
            : null]),
        valueRow('reason', repeated('reason', item.reasons)),
        valueRow('coverage', one('coverage', item.coverage)),
        valueRow('coverage-assertion-id', one('coverage-assertion-id', item.coverageAssertionId)),
        valueRow('date', one('date', item.date))
    ];
    if ((item.laterReasonCodes || []).length > 0) {
        // Read and named, never called wrong: a later version of the guide defines these codes.
        rows.push(valueRow('reason codes of a later version', el('ul', {class: 'plain-list'}, item.laterReasonCodes.map(entry => el('li', null, code(entry))))));
    }
    if (item.authOutOfNetworkOnly === true || item.authOutOfNetworkOnly === false) {
        rows.push(valueRow('auth-out-network-only', [code(String(item.authOutOfNetworkOnly)), ' ', item.authOutOfNetworkOnly
            ? 'The detail says authorization is needed only out of network.'
            : 'The detail says authorization is not limited to out of network.']));
    }
    const block = el('div', {class: 'detail__item'},
        el('p', {class: 'detail__item-head'},
            el('span', {class: 'sub-heading'}, 'Answer for ', code(item.subject || 'the request')),
            item.outcome ? outcome(known ? known.label : item.outcome, known ? known.tone : null) : null),
        el('p', {class: 'small quiet'}, 'Found at ', code(item.foundAt), '. ',
            // A sentence about what the answer applies to is said only when the response says it.
            hasValue(item.appliesTo) ? ['Applies to ', shown(item.appliesTo, {as: 'literal'}), '. ']
                : ['The response does not say what it applies to (', shown(item.appliesTo), '). '],
            (item.procedureCodes || []).length > 0
                ? ['The answer for the requested ', item.procedureCodes.length === 1 ? 'code ' : 'codes ',
                    item.procedureCodes.map((value, index) => [index > 0 ? ', ' : null, code(value)]), '.']
                : 'It cannot be told which requested code this answer is for.'));
    if (item.delegatedVendor) {
        // Where the request routes is the answer testers most often need, so it is not left in a table row.
        block.append(el('p', {class: 'vendor-callout'}, 'Delegated vendor: ', el('strong', null, item.delegatedVendor),
            item.delegatedVendorBasis ? el('span', {class: 'quiet'}, ' Recognised from ', item.delegatedVendorBasis, '.') : null));
    }
    // Every element the extension can carry stays listed. Those the response did not carry at all
    // are one click away under the table, so what was returned reads first; null, empty and sent
    // with no value are answers and stay in the table.
    const open = rows.filter(row => !onlyNotReturned(row));
    const notReturned = rows.filter(onlyNotReturned);
    block.append(open.length > 0 ? table(['Element', 'Code returned', 'Meaning'], open, {class: 'elements'})
        : el('p', {class: 'quiet'}, 'The response returned none of these elements.'));
    if (notReturned.length > 0) {
        block.append(el('details', {class: 'fold coverage__absent'},
            el('summary', null, 'Not returned', el('span', {class: 'quiet'}, '(' + notReturned.length + ')')),
            table(['Element', 'Code returned', 'Meaning'], notReturned, {class: 'elements'})));
    }

    const details = item.details || [];
    block.append(el('p', {class: 'sub-heading'}, 'Detail list (' + details.length + ')'));
    block.append(details.length === 0
        ? el('p', null, code('detail'), ' ', presenceMark('ABSENT'))
        : table(['Code', 'Value', 'Qualification', 'Category'], details.map(entry => [shown(entry.code), shown(entry.value),
            shown(entry.qualification), shown(entry.category)]), {class: 'detail-list'}));

    const others = item.otherSubExtensions || [];
    if (others.length > 0) {
        block.append(el('p', {class: 'sub-heading'}, 'Other sub-extensions (' + others.length + ')'),
            table(['Url', 'Value', 'Defined in'], others.map(entry => [code(entry.url), shown(entry.value, {as: 'literal'}),
                entry.definedIn || el('span', {class: 'quiet small'}, 'not stated')]), {class: 'pairs'}));
    }
    return block;
}

/**
 * One coded element of the extension: its name as the response spells it, the code or codes
 * returned, and what the tool says each means. The code is what QA signs off against, so it has
 * a column to itself. A sub-extension that can repeat is a list only on this page: in the
 * response each is a sub-extension of its own, so none of them means none was returned, and that
 * is said as "not returned". A null or an empty one is an entry, and is drawn as one.
 */
function codedRowOf(name, values, repeats, noValue) {
    const list = Array.isArray(values) ? values.filter(Boolean) : [];
    if (list.length === 0) {
        return [code(name), presenceMark('ABSENT'), null];
    }
    const lines = cells => (cells.length === 1 ? cells[0] : el('ul', {class: 'plain-list'}, cells.map(cell => el('li', null, cell))));
    const sentBare = index => noValue.has(repeats ? name + '[' + index + ']' : name);
    return [code(name),
        lines(list.map((value, index) => (value.presence === 'VALUE' ? code(String(value.code))
            : sentBare(index) ? noValueMark() : presenceMark(value.presence || 'ABSENT')))),
        lines(list.map(value => (value.presence === 'VALUE'
            ? [value.display || null, value.version ? el('span', {class: 'small quiet'}, ' ' + value.version) : null,
                value.recognised === false ? [' ', el('span', {class: 'flag'}, 'not a code this tool knows')] : null]
            : null)))];
}

/** The values of a sub-extension that can repeat. None of them is a sub-extension not returned, as for a coded one. */
function repeatedOf(name, values, options, noValue) {
    if (!Array.isArray(values) || values.length === 0) {
        return presenceMark('ABSENT');
    }
    const cell = (value, index) => (noValue.has(name + '[' + index + ']') ? noValueMark() : shown(value, options));
    return values.length === 1 ? cell(values[0], 0) : el('ul', {class: 'plain-list'}, values.map((value, index) => el('li', null, cell(value, index))));
}

/**
 * True for a row of the coverage table whose value is the mark of an element the response did not
 * carry, and nothing else. Read from what the row draws, so a row is folded exactly when it says
 * "not returned" and no other answer.
 */
function onlyNotReturned(row) {
    const cell = row[1];
    const node = cell && typeof cell === 'object' && !(cell instanceof Node) && !Array.isArray(cell) ? cell.node : cell;
    const parts = [node].flat(Infinity).filter(part => part !== null && part !== undefined && part !== false);
    return parts.length === 1 && parts[0] instanceof Element && parts[0].classList.contains('presence--absent');
}

/** One element of the extension that is a plain value and not a code. */
function valueRow(name, value) {
    return [code(name), {colSpan: 2, node: value}];
}

/* ---------- cards ---------- */

function cardBlock(card, total) {
    const links = card.links || [];
    const suggestions = card.suggestions || [];
    const block = el('div', {class: 'detail__item'},
        el('p', {class: 'detail__item-head'},
            el('span', {class: 'sub-heading'}, 'Card ' + (card.index + 1) + ' of ' + total),
            el('span', {class: 'small'}, 'indicator ', shown(card.indicator, {as: 'literal'}))),
        el('div', {class: 'card-summary'}, shown(card.summary)),
        facts([
            ['Detail', shown(card.detail, {as: 'block'})]
        ].concat(sourceRows(card), [
            ['Selection behaviour', shown(card.selectionBehavior, {as: 'literal'})],
            ['Uuid', shown(card.uuid, {as: 'literal'})],
            ['Override reasons', listValue(card.lists, 'overrideReasons', card.overrideReasons, {as: 'literal'})]
        ]), 'facts--ruled'));

    block.append(el('p', {class: 'sub-heading'}, 'Links (' + links.length + ')'));
    block.append(links.length === 0
        ? presenceOfList(card.lists, 'links', 0) || el('p', {class: 'quiet'}, 'The card carried no links.')
        : table(['Label', 'Type', 'Address', 'App context'], links.map(linkRow), {class: 'card-links'}));

    block.append(el('p', {class: 'sub-heading'}, 'Suggestions (' + suggestions.length + ')'));
    if (suggestions.length === 0) {
        block.append(presenceOfList(card.lists, 'suggestions', 0) || el('p', {class: 'quiet'}, 'The card carried no suggestions.'));
    }
    suggestions.forEach((suggestion, index) => {
        const actions = suggestion.actions || [];
        block.append(el('div', {class: 'detail__nested'},
            facts([
                ['Suggestion ' + (index + 1), shown(suggestion.label)],
                ['Uuid', shown(suggestion.uuid, {as: 'literal'})],
                ['Recommended', shown(suggestion.isRecommended, {as: 'literal'})]
            ], 'facts--ruled'),
            actions.length === 0 ? presenceOfList(suggestion.lists, 'actions', 0) || el('p', {class: 'quiet'}, 'This suggestion carried no actions.')
                : actions.map(action => actionBlock(action))));
    });
    return block;
}

/**
 * The source of a card. Its label, url, icon and topic say what was inside it; whether the source
 * itself was not returned, null or empty is its own fact, and when it is one of those the four
 * rows inside it would only repeat "not returned" for a source that was there.
 */
function sourceRows(card) {
    if (card.source && card.source !== 'VALUE') {
        return [['Source', [code('source'), ' ', presenceMark(card.source)]]];
    }
    return [
        ['Source label', [shown(card.sourceLabel), hasValue(card.sourceUrl) ? [' ', shown(card.sourceUrl, {as: 'link', label: 'Open the source'})] : null]],
        ['Source url', hasValue(card.sourceUrl) ? null : shown(card.sourceUrl)],
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
        // A SMART link is not somewhere to go. It is what the DTR step starts from, so it stays text.
        address = [code(link.url.value), el('div', {class: 'small quiet'}, 'A SMART link: DTR starts from it. It is not opened from here.')];
    } else {
        address = externalLink(link.url.value, 'Open the link');
    }
    return [shown(link.label), shown(link.type, {as: 'literal'}), address, shown(link.appContext, {as: 'literal'})];
}

/* ---------- actions ---------- */

function actionBlock(action) {
    const rest = action.resource || [];
    return el('div', {class: 'detail__item detail__item--action'},
        el('p', {class: 'detail__item-head'},
            el('span', {class: 'sub-heading'}, 'Action at ', code(action.foundAt))),
        facts([
            ['Type', shown(action.type, {as: 'literal'})],
            ['Description', shown(action.description)],
            ['Resource type', shown(action.resourceType, {as: 'literal'})],
            ['Resource id in the action', shown(action.resourceId, {as: 'literal'})],
            ['Id of the resource carried', shown(action.resourceLogicalId, {as: 'literal'})]
        ], 'facts--ruled'),
        el('p', {class: 'sub-heading'}, 'The rest of the resource (' + rest.length + ')'),
        rest.length === 0 ? el('p', {class: 'quiet'}, 'Nothing further was returned with this action.')
            : fieldsFold(rest.length, additionalFieldsTable(rest, shownWire)));
}

/* ---------- how the request was built ---------- */

function requestNotes(detail) {
    const notes = detail.requestNotes || [];
    const part = el('section', {class: 'detail__part'});
    if (detail.bodyBuiltByTool === false) {
        append(part, el('p', null, 'The body the tester supplied was sent in place of one this tool would have built.'));
    }
    if (notes.length > 0) {
        part.append(el('details', {class: 'fold'},
            el('summary', null, 'How this tool built the request', el('span', {class: 'quiet'}, notes.length + (notes.length === 1 ? ' note' : ' notes'))),
            el('ul', {class: 'note-list'}, notes.map(note => el('li', null, note)))));
    }
    return part.childNodes.length > 0 ? part : null;
}

/* ---------- what the conformance check judged by ---------- */

/**
 * Which rules of the guide the findings above were checked against, and which were not, in the
 * server's words. The version is named in each, so a finding is never read as a judgement against
 * another version of the guide.
 */
function conformanceScope(scope) {
    if (!scope) {
        return null;
    }
    const applied = scope.applied || [];
    const notApplied = scope.notApplied || [];
    return el('section', {class: 'detail__part'},
        el('details', {class: 'fold'},
            el('summary', null, 'What the conformance check judged by',
                el('span', {class: 'quiet'}, (scope.version || 'no version named') + ': ' + applied.length + ' rules applied, '
                    + notApplied.length + ' not applied')),
            el('p', {class: 'small'}, 'The findings above that are about the guide were checked against ', scope.version
                ? el('strong', null, scope.version) : 'a version the result does not name', ', and these rules only.'),
            el('p', {class: 'sub-heading'}, 'Applied (' + applied.length + ')'),
            applied.length === 0 ? el('p', {class: 'quiet'}, 'None.') : el('ul', {class: 'note-list'}, applied.map(rule => el('li', null, rule))),
            el('p', {class: 'sub-heading'}, 'Not applied (' + notApplied.length + ')'),
            notApplied.length === 0 ? el('p', {class: 'quiet'}, 'None.') : el('ul', {class: 'note-list'}, notApplied.map(rule => el('li', null, rule)))));
}
