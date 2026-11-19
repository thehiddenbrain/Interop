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
import {shown, shownList, presenceMark, noneMark, hasValue} from './shown.js';
import {outcome} from './marks.js';
import {additionalFieldsTable, issuesTable} from './result.js';

// What kind of thing came back, said before any of it is shown. A kind this page does not know
// is shown under its own name.
const RESPONSE_KINDS = {
    CDS_HOOKS: 'The response is a JSON object and was read as a CDS Hooks response.',
    OPERATION_OUTCOME: 'The response is a FHIR OperationOutcome.',
    OTHER_JSON: 'The response is JSON, but it is neither a CDS Hooks response nor an OperationOutcome. All of it is listed under the returned fields below.',
    NOT_JSON: 'The response body could not be read as JSON. It is shown whole under the returned fields below, and in the wire view.',
    NO_BODY: 'The response had no body.',
    NO_RESPONSE: 'No response arrived.'
};

/**
 * vocabulary.outcomes: a map from outcome code to {label, tone}, as the scenarios endpoint served
 * it, used to word the outcome of each coverage information extension.
 */
export function crdDetail(envelope, vocabulary) {
    const detail = envelope.detail;
    const node = el('div', {class: 'detail'});

    const kind = Object.prototype.hasOwnProperty.call(RESPONSE_KINDS, detail.responseKind)
        ? RESPONSE_KINDS[detail.responseKind] : ['Response kind ', code(String(detail.responseKind)), '.'];
    node.append(el('p', null, kind, detail.responseNote ? [' ', detail.responseNote] : null));

    const issues = detail.issues || [];
    if (issues.length > 0) {
        const alsoInPanel = envelope.error && envelope.error.issues && envelope.error.issues.length > 0;
        node.append(el('section', {class: 'detail__part'}, heading(4, 'Issues in the OperationOutcome', issues.length),
            alsoInPanel
                ? el('p', {class: 'quiet'}, 'They are listed issue by issue under "What went wrong", at the end of this result.')
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
    const rows = [
        codedRow('covered', [item.covered]),
        codedRow('pa-needed', [item.paNeeded]),
        codedRow('doc-needed', [item.docNeeded]),
        codedRow('doc-purpose', item.docPurpose, true),
        codedRow('info-needed', item.infoNeeded, true),
        valueRow('satisfied-pa-id', shown(item.satisfiedPaId, {as: 'literal'})),
        valueRow('questionnaire', shownList(item.questionnaires, {as: 'literal'})),
        valueRow('reason', shownList(item.reasons)),
        valueRow('coverage', shown(item.coverage, {as: 'literal'})),
        valueRow('coverage-assertion-id', shown(item.coverageAssertionId, {as: 'literal'})),
        valueRow('date', shown(item.date, {as: 'literal'}))
    ];
    const block = el('div', {class: 'detail__item'},
        el('p', {class: 'detail__item-head'},
            el('span', {class: 'sub-heading'}, 'Answer for ', code(item.subject || 'the request')),
            item.outcome ? outcome(known ? known.label : item.outcome, known ? known.tone : null) : null),
        el('p', {class: 'small quiet'}, 'Found at ', code(item.foundAt), '. Applies to ', shown(item.appliesTo, {as: 'literal'}), '.'));
    if (item.delegatedVendor) {
        // Where the request routes is the answer testers most often need, so it is not left in a table row.
        block.append(el('p', {class: 'vendor-callout'}, 'Delegated vendor: ', el('strong', null, item.delegatedVendor),
            item.delegatedVendorBasis ? el('span', {class: 'quiet'}, ' Recognised from ', item.delegatedVendorBasis, '.') : null));
    }
    block.append(table(['Element', 'Code returned', 'Meaning'], rows, {class: 'elements'}));

    const details = item.details || [];
    block.append(el('p', {class: 'sub-heading'}, 'Detail list (' + details.length + ')'));
    block.append(details.length === 0
        ? el('p', {class: 'quiet'}, 'The extension carried no detail entries.')
        : table(['Code', 'Value', 'Qualifier'], details.map(entry => [shown(entry.code), shown(entry.value), shown(entry.qualifier)]),
            {class: 'detail-list'}));

    const others = item.otherSubExtensions || [];
    if (others.length > 0) {
        block.append(el('p', {class: 'sub-heading'}, 'Other sub-extensions (' + others.length + ')'),
            table(['Url', 'Value'], others.map(entry => [code(entry.url), shown(entry.value, {as: 'literal'})]), {class: 'pairs'}));
    }
    return block;
}

/**
 * One coded element of the extension: its name as the response spells it, the code or codes
 * returned, and what the tool says each means. The code is what QA signs off against, so it has
 * a column to itself. An element that can repeat and came back with nothing says so.
 */
function codedRow(name, values, repeats) {
    const list = Array.isArray(values) ? values.filter(Boolean) : [];
    if (list.length === 0) {
        return [code(name), repeats ? noneMark() : presenceMark('ABSENT'), null];
    }
    const lines = cells => (cells.length === 1 ? cells[0] : el('ul', {class: 'plain-list'}, cells.map(cell => el('li', null, cell))));
    return [code(name),
        lines(list.map(value => (value.presence === 'VALUE' ? code(String(value.code)) : presenceMark(value.presence || 'ABSENT')))),
        lines(list.map(value => (value.presence === 'VALUE'
            ? [value.display || null, value.recognised === false ? [' ', el('span', {class: 'flag'}, 'not a code this tool knows')] : null]
            : null)))];
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
            ['Detail', shown(card.detail, {as: 'block'})],
            ['Source', [shown(card.sourceLabel), hasValue(card.sourceUrl) ? [' ', shown(card.sourceUrl, {as: 'link', label: 'Open the source'})] : null]],
            ['Source url', hasValue(card.sourceUrl) ? null : shown(card.sourceUrl)],
            ['Source icon', shown(card.sourceIcon, {as: 'literal'})],
            ['Source topic', shown(card.sourceTopic, {as: 'literal'})],
            ['Selection behaviour', shown(card.selectionBehavior, {as: 'literal'})],
            ['Uuid', shown(card.uuid, {as: 'literal'})],
            ['Override reasons', shownList(card.overrideReasons, {as: 'literal'})]
        ], 'facts--ruled'));

    block.append(el('p', {class: 'sub-heading'}, 'Links (' + links.length + ')'));
    block.append(links.length === 0
        ? el('p', {class: 'quiet'}, 'The card carried no links.')
        : table(['Label', 'Type', 'Address', 'App context'], links.map(linkRow), {class: 'card-links'}));

    block.append(el('p', {class: 'sub-heading'}, 'Suggestions (' + suggestions.length + ')'));
    if (suggestions.length === 0) {
        block.append(el('p', {class: 'quiet'}, 'The card carried no suggestions.'));
    }
    suggestions.forEach((suggestion, index) => {
        const actions = suggestion.actions || [];
        block.append(el('div', {class: 'detail__nested'},
            facts([
                ['Suggestion ' + (index + 1), shown(suggestion.label)],
                ['Uuid', shown(suggestion.uuid, {as: 'literal'})],
                ['Recommended', shown(suggestion.isRecommended, {as: 'literal'})]
            ], 'facts--ruled'),
            actions.length === 0 ? el('p', {class: 'quiet'}, 'This suggestion carried no actions.') : actions.map(action => actionBlock(action))));
    });
    return block;
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
            : additionalFieldsTable(rest));
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
