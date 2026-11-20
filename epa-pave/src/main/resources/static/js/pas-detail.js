// The PAS part of a result: everything the tool understood in a ClaimResponse, a subscription
// answer or a notification, in the order a tester needs it.
//
// The decision for each item comes first, because a partial approval is the case that goes wrong
// when items are flattened into one line. Every decision, label and tone is the server's: the
// outcome of an item is a code the server set, worded from the vocabulary the scenarios endpoint
// serves. Nothing here reads a review action code and decides what it means.
//
// A field the response did not carry is shown as not returned, never left out, and what the
// payer wrote (a disposition, a process note) is shown as the characters it is.

import {el, append, code, facts, table, heading} from './dom.js';
import {shown, shownList, presenceMark, noneMark, hasValue} from './shown.js';
import {outcome} from './marks.js';
import {additionalFieldsTable, issuesTable} from './result.js';

const PAS_KINDS = {
    BUNDLE: 'The response is a FHIR Bundle and was read entry by entry.',
    CLAIM_RESPONSE: 'The response is a ClaimResponse on its own, not wrapped in a bundle.',
    OPERATION_OUTCOME: 'The response is a FHIR OperationOutcome.',
    OTHER_JSON: 'The response is JSON, but none of the resources this tool reads. All of it is listed under the returned fields below.',
    NOT_JSON: 'The response body could not be read as JSON. It is shown whole under the returned fields below, and in the wire view.',
    NO_BODY: 'The response had no body.',
    NO_RESPONSE: 'No response arrived.'
};

const SUBSCRIPTION_KINDS = {
    SUBSCRIPTION: 'The response is one Subscription resource.',
    BUNDLE: 'The response is a bundle, read as a list of subscriptions.',
    OPERATION_OUTCOME: 'The response is a FHIR OperationOutcome.',
    OTHER_JSON: 'The response is JSON, but not a subscription. All of it is listed under the returned fields below.',
    NOT_JSON: 'The response body could not be read as JSON. It is shown whole under the returned fields below, and in the wire view.',
    NO_BODY: 'The response had no body.',
    NO_RESPONSE: 'No response arrived.'
};

// Where a decision was stated. The server sets it; this only words it, so an inherited decision is never read as the item's own.
const SOURCES = {
    ITEM: null,
    RESPONSE: 'stated once for the response as a whole, not on this item',
    NONE: 'no review action applies to this item'
};

function none(text) {
    return el('span', {class: 'presence presence--none'}, text);
}

function kindLine(kinds, kind, note) {
    const known = Object.prototype.hasOwnProperty.call(kinds, kind) ? kinds[kind] : ['Response kind ', code(String(kind)), '.'];
    // The server's note can say the same as the kind's own sentence, as it does when no response arrived. It is said once.
    return el('p', null, known, note && note !== known ? [' ', note] : null);
}

/** A coded value as returned: the code, what the tool knows it means, and a flag when it is not a code the tool knows. */
export function coded(value) {
    if (!value || value.presence === undefined) {
        return presenceMark('ABSENT');
    }
    if (value.presence !== 'VALUE') {
        return presenceMark(value.presence);
    }
    return el('span', {class: 'pas-coded'}, code(String(value.code)),
        value.display ? [' ', el('span', null, value.display)] : null,
        value.version ? el('span', {class: 'quiet small'}, ' (' + value.version + ')') : null,
        value.recognised === false ? [' ', el('span', {class: 'flag'}, 'not a code this tool knows')] : null);
}

/** Codes that are plain strings, such as reason codes and error codes. */
function codeList(values, emptyText) {
    const list = Array.isArray(values) ? values.filter(value => value !== null && value !== undefined) : [];
    if (list.length === 0) {
        return none(emptyText);
    }
    return list.map((value, index) => [index > 0 ? ', ' : null, code(String(value))]);
}

/** Every extension as returned, with the extensions nested inside it, so a vendor extension nobody anticipated is still shown where it sat. */
export function extensionList(extensions) {
    const list = extensions || [];
    if (list.length === 0) {
        return null;
    }
    return el('ul', {class: 'pas-extensions'}, list.map(extension => el('li', null,
        code(extension.url || '(no url)'),
        extension.value ? [' ', shown(extension.value, {as: 'literal'})] : null,
        extensionList(extension.parts))));
}

/**
 * The PAS detail of a submission or an inquiry.
 *
 * vocabulary.outcomes: a map from outcome code to {label, tone}, as the scenarios endpoint served it.
 */
export function pasDetail(envelope, vocabulary) {
    const detail = envelope.detail;
    const node = el('div', {class: 'detail pas-detail'});
    node.append(kindLine(PAS_KINDS, detail.responseKind, detail.responseNote));

    const responses = detail.claimResponses || [];
    if (responses.length === 0) {
        node.append(el('section', {class: 'detail__part'}, heading(4, 'Decision for each item'),
            el('p', {class: 'quiet'}, 'The response carried no ClaimResponse, so no item has a decision.')));
    }
    responses.forEach((response, index) => node.append(claimResponseBlock(response, vocabulary, responses.length > 1 ? index + 1 : 0)));

    // A part with nothing to show is null. append() leaves it out, where the node's own append would write the word null.
    append(node, issuesPart(detail.issues || [], envelope), otherResourcesPart(detail.otherResources || []), bundlePart(detail),
        requestPart(detail, envelope.operation));
    return node;
}

/**
 * One ClaimResponse: the decisions item by item, then what the payer said of the request as a
 * whole, what is still awaited, the errors, the process notes and the items in full.
 *
 * number: its position when the answer holds several, else 0.
 */
export function claimResponseBlock(response, vocabulary, number) {
    const block = el('div', {class: 'pas-claim'});
    const name = response.id && hasValue(response.id) ? 'ClaimResponse/' + response.id.value : response.foundAt;
    if (number > 0) {
        block.append(el('p', {class: 'pas-claim__name'}, 'ClaimResponse ' + number + ': ', code(name)));
    }
    block.append(decisionsPart(response, vocabulary));
    block.append(wholePart(response, vocabulary));
    block.append(awaitedPart(response.attachments || []));
    block.append(errorsPart(response.errors || []));
    block.append(processNotesPart(response.processNotes || []));
    block.append(itemsPart(response.items || []));
    return block;
}

function outcomeOf(vocabulary, codeName) {
    if (!codeName) {
        return none('not stated');
    }
    const known = vocabulary && vocabulary.outcomes ? vocabulary.outcomes.get(codeName) : null;
    return el('span', null, outcome(known ? known.label : codeName, known ? known.tone : null),
        el('div', {class: 'small quiet'}, code(codeName)));
}

/* ---------- the decision for each item ---------- */

function decisionsPart(response, vocabulary) {
    const decisions = response.decisions || [];
    const part = el('section', {class: 'detail__part'}, heading(4, 'Decision for each item', decisions.length));
    if (decisions.length === 0) {
        part.append(el('p', {class: 'quiet'}, 'This ClaimResponse states no decision for any item.'));
        return part;
    }
    part.append(el('p', {class: 'quiet small'}, 'One row for every item that was asked about, whether or not the response lists it. '
        + 'The decision is read from the review action code only, never from the payer\'s wording.'));
    const rows = decisions.map(decision => ({cells: [
        decision.subject ? [code(decision.subject), decision.sequence !== undefined && decision.sequence !== null
            ? el('div', {class: 'small quiet'}, 'item ' + decision.sequence) : null]
            : el('span', null, 'The request as a whole'),
        [outcomeOf(vocabulary, decision.outcome), SOURCES[decision.source]
            ? el('div', {class: 'small pas-source'}, SOURCES[decision.source]) : null],
        decision.reviewActions && decision.reviewActions.length > 0
            ? el('ul', {class: 'plain-list'}, decision.reviewActions.map(action => el('li', null, coded(action))))
            : none('none returned'),
        authorization(decision),
        el('div', null,
            decision.reasonCodes && decision.reasonCodes.length > 0 ? el('div', null, 'Reasons ', codeList(decision.reasonCodes)) : null,
            decision.errorCodes && decision.errorCodes.length > 0 ? el('div', null, 'Errors ', codeList(decision.errorCodes)) : null,
            (!decision.reasonCodes || decision.reasonCodes.length === 0) && (!decision.errorCodes || decision.errorCodes.length === 0)
                ? none('none returned') : null,
            decision.foundAt ? el('div', {class: 'small quiet'}, 'at ', code(decision.foundAt))
                : el('div', {class: 'small quiet'}, 'The response does not list this item.'))
    ], class: 'pas-decision pas-decision--' + String(decision.source || 'NONE').toLowerCase()}));
    part.append(table([{text: 'Item', class: 'col-14'}, {text: 'Decision', class: 'col-25'}, {text: 'Review action as returned', class: 'col-22'},
        {text: 'Authorization', class: 'col-22'}, 'Reasons and errors'], rows, {class: 'pas-decisions'}));
    return part;
}

/** The authorization number and period stated for one item, where PAS 2.0.1 puts them. */
function authorization(decision) {
    const numbers = decision.authorizationNumbers || [];
    const from = decision.authorizedFrom;
    const to = decision.authorizedTo;
    return el('div', null,
        numbers.length > 0 ? el('div', null, numbers.map((value, index) => [index > 0 ? ', ' : null, code(value, 'pas-authno')]))
            : el('div', null, none('no number stated')),
        from || to ? el('div', {class: 'small'}, 'Valid from ', from ? code(from) : none('not stated'), ' to ', to ? code(to) : none('not stated'))
            : el('div', {class: 'small'}, none('no period stated')));
}

/* ---------- the response as a whole ---------- */

function wholePart(response, vocabulary) {
    const actions = response.reviewActions || [];
    return el('section', {class: 'detail__part'}, heading(4, 'The response as a whole'),
        el('div', {class: 'facts-pair'},
            facts([
                ['What it establishes', outcomeOf(vocabulary, response.decision)],
                ['Authorization number', [shown(response.preAuthRef, {as: 'literal'}),
                    el('div', {class: 'small quiet'}, 'ClaimResponse.preAuthRef. PAS 2.0.1 puts an item\'s number on its review action, in the table above.')]],
                ['Authorized period', ['from ', shown(response.preAuthPeriodStart, {as: 'literal'}), ' to ', shown(response.preAuthPeriodEnd, {as: 'literal'})]],
                ['Outcome code', [coded(response.outcome), el('div', {class: 'small quiet'}, 'How far processing got, not what was decided.')]],
                ['Disposition', [shown(response.disposition, {as: 'block'}),
                    el('div', {class: 'small quiet'}, 'The payer\'s wording. It is shown and never read as a decision.')]],
                ['Answers to', [shown(response.request, {as: 'literal'}), hasValue(response.requestIdentifier)
                    ? el('div', {class: 'small'}, 'Claim identifier ', shown(response.requestIdentifier, {as: 'literal'})) : null]],
                ['Claim identifier echoed', hasValue(response.requestIdentifier) ? null : shown(response.requestIdentifier)],
                ['Identifiers', shownList(response.identifiers, {as: 'literal'})]
            ], 'facts--ruled'),
            facts([
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
            ], 'facts--ruled')),
        response.subsetted ? el('p', null, el('span', {class: 'flag'}, 'SUBSETTED'),
            ' The ClaimResponse declares that it answers only some of the items of the request.') : null,
        actions.length > 0 ? [el('p', {class: 'sub-heading'}, 'Review actions on the ClaimResponse itself (' + actions.length + ')'),
            actions.map(action => reviewActionBlock(action))] : null,
        response.addItemReviewActions && response.addItemReviewActions.length > 0
            ? el('div', null, el('p', {class: 'sub-heading'}, 'Review actions on items the payer added (' + response.addItemReviewActions.length + ')'),
                el('ul', {class: 'note-list'}, response.addItemReviewActions.map(text => el('li', null, code(text)))),
                el('p', {class: 'quiet small'}, 'They decide nothing for the items that were asked about, and are listed among the fields returned but not interpreted.'))
            : null,
        response.extensions && response.extensions.length > 0
            ? el('div', null, el('p', {class: 'sub-heading'}, 'Other extensions (' + response.extensions.length + ')'), extensionList(response.extensions))
            : null);
}

/** One review action with every part of it. The code decides; everything else is shown beside it. */
function reviewActionBlock(action) {
    return el('div', {class: 'detail__nested'},
        action.placement ? el('p', {class: 'pas-placement'}, el('span', {class: 'flag'}, 'outside the guide\'s place'), ' ', action.placement) : null,
        facts([
            ['Code', coded(action.code)],
            ['As the payer wrote it', shown(action.returned, {as: 'literal'})],
            ['Authorization number', shown(action.number, {as: 'literal'})],
            ['Reason', [shown(action.reason), action.reasonCodes && action.reasonCodes.length > 0
                ? el('div', {class: 'small'}, codeList(action.reasonCodes)) : null]],
            ['Second surgical opinion', shown(action.secondSurgicalOpinionNeeded, {as: 'literal'})],
            ['Other parts', action.otherParts && action.otherParts.length > 0 ? extensionList(action.otherParts) : none('none returned')],
            ['Found at', code(action.foundAt)]
        ], 'facts--ruled'));
}

/* ---------- what is still awaited ---------- */

function awaitedPart(attachments) {
    if (attachments.length === 0) {
        return el('section', {class: 'detail__part'}, heading(4, 'Documentation the payer asks for', 0),
            el('p', {class: 'quiet'}, 'The response refers to no request for supporting documentation.'));
    }
    return el('section', {class: 'detail__part'}, heading(4, 'Documentation the payer asks for', attachments.length),
        attachments.map(item => el('div', {class: 'detail__item'},
            el('p', {class: 'detail__item-head'},
                el('span', {class: 'sub-heading'}, 'Request for documentation ', shown(item.reference, {as: 'literal'})),
                item.open ? el('span', {class: 'flag'}, 'open') : el('span', {class: 'quiet'}, 'closed')),
            facts([
                ['What is asked for', shownList(item.wanted)],
                ['Category', shown(item.category, {as: 'literal'})],
                ['Status', item.carried ? shown(item.status, {as: 'literal'})
                    : [none('not known'), el('div', {class: 'small quiet'}, 'The response refers to the request but does not carry it, so its status cannot be read. It counts as open.')]],
                ['Found at', code(item.foundAt)]
            ], 'facts--ruled'),
            item.rest && item.rest.length > 0
                ? [el('p', {class: 'sub-heading'}, 'The rest of that resource (' + item.rest.length + ')'), additionalFieldsTable(item.rest)] : null)));
}

/* ---------- errors and process notes ---------- */

function errorsPart(errors) {
    if (errors.length === 0) {
        return el('section', {class: 'detail__part'}, heading(4, 'Errors the payer returned', 0),
            el('p', {class: 'quiet'}, 'The ClaimResponse lists no errors.'));
    }
    return el('section', {class: 'detail__part'}, heading(4, 'Errors the payer returned', errors.length),
        table([{text: 'Code', class: 'col-30'}, {text: 'Where in the request', class: 'col-30'}, 'Item, detail, sub-detail', 'Follow-up'],
            errors.map(error => [
                [shown(error.code, {as: 'literal'}), error.extensions && error.extensions.length > 0 ? extensionList(error.extensions) : null],
                [shown(error.element, {as: 'literal'}), hasValue(error.path) ? el('div', {class: 'small'}, shown(error.path, {as: 'literal'})) : null],
                [shown(error.itemSequence, {as: 'literal'}), ' / ', shown(error.detailSequence, {as: 'literal'}), ' / ',
                    shown(error.subDetailSequence, {as: 'literal'})],
                [shown(error.followupAction, {as: 'literal'}), el('div', {class: 'small quiet'}, 'at ', code(error.foundAt))]
            ]), {class: 'pas-errors'}));
}

function processNotesPart(notes) {
    if (notes.length === 0) {
        return el('section', {class: 'detail__part'}, heading(4, 'Process notes', 0),
            el('p', {class: 'quiet'}, 'The ClaimResponse carries no process notes.'));
    }
    return el('section', {class: 'detail__part'}, heading(4, 'Process notes', notes.length),
        table([{text: 'Number', class: 'col-12'}, {text: 'Type', class: 'col-18'}, 'Text, as the payer wrote it'], notes.map(note => [
            shown(note.number, {as: 'literal'}), shown(note.type, {as: 'literal'}), shown(note.text, {as: 'block'})])));
}

/* ---------- the items in full ---------- */

function itemsPart(items) {
    const part = el('section', {class: 'detail__part'});
    if (items.length === 0) {
        part.append(heading(4, 'Items as returned', 0), el('p', {class: 'quiet'}, 'The ClaimResponse lists no items.'));
        return part;
    }
    part.append(el('details', {class: 'fold'},
        el('summary', null, 'Items as returned, with every adjudication and extension',
            el('span', {class: 'quiet'}, items.length + (items.length === 1 ? ' item' : ' items'))),
        items.map(item => el('div', {class: 'detail__item'},
            el('p', {class: 'detail__item-head'},
                el('span', {class: 'sub-heading'}, 'Item ', shown(item.itemSequence, {as: 'literal'}),
                    item.subject ? [' for ', code(item.subject)] : null),
                el('span', {class: 'small quiet'}, 'at ', code(item.foundAt))),
            item.procedureCode ? null : el('p', {class: 'small quiet'}, 'This item could not be tied to a requested procedure code.'),
            (item.reviewActions || []).length > 0
                ? [el('p', {class: 'sub-heading'}, 'Review actions on the item itself'), item.reviewActions.map(reviewActionBlock)] : null,
            el('p', {class: 'sub-heading'}, 'Adjudications (' + (item.adjudications || []).length + ')'),
            (item.adjudications || []).length === 0 ? el('p', {class: 'quiet'}, 'None returned.')
                : item.adjudications.map(adjudication => el('div', {class: 'detail__nested'},
                    facts([
                        ['Category', shown(adjudication.category, {as: 'literal'})],
                        ['Reason', shown(adjudication.reason, {as: 'literal'})],
                        ['Amount', shown(adjudication.amount, {as: 'literal'})],
                        ['Value', shown(adjudication.value, {as: 'literal'})],
                        ['Found at', code(adjudication.foundAt)]
                    ], 'facts--ruled'),
                    (adjudication.reviewActions || []).map(reviewActionBlock),
                    (adjudication.extensions || []).length > 0
                        ? [el('p', {class: 'sub-heading'}, 'Other extensions'), extensionList(adjudication.extensions)] : null)),
            (item.extensions || []).length > 0 ? [el('p', {class: 'sub-heading'}, 'Extensions on the item'), extensionList(item.extensions)] : null,
            (item.noteNumbers || []).length > 0 ? el('p', null, 'Process notes ', shownList(item.noteNumbers, {as: 'literal'})) : null))));
    return part;
}

/* ---------- the rest of the answer ---------- */

function issuesPart(issues, envelope) {
    if (issues.length === 0) {
        return null;
    }
    const alsoInPanel = envelope && envelope.error && envelope.error.issues && envelope.error.issues.length > 0;
    return el('section', {class: 'detail__part'}, heading(4, 'Issues in the OperationOutcome', issues.length),
        alsoInPanel ? el('p', {class: 'quiet'}, 'They are listed issue by issue under "What went wrong", at the end of this result.')
            : issuesTable(issues));
}

export function otherResourcesPart(resources) {
    if (resources.length === 0) {
        return null;
    }
    return el('section', {class: 'detail__part'}, heading(4, 'Other resources the bundle carried', resources.length),
        resources.map(resource => el('div', {class: 'detail__item'},
            el('p', {class: 'detail__item-head'},
                el('span', {class: 'sub-heading'}, shown(resource.resourceType, {as: 'literal'}), ' ', shown(resource.id, {as: 'literal'})),
                el('span', {class: 'small quiet'}, 'at ', code(resource.foundAt))),
            facts([['Full url', shown(resource.fullUrl, {as: 'literal'})]], 'facts--ruled'),
            (resource.fields || []).length > 0 ? additionalFieldsTable(resource.fields) : el('p', {class: 'quiet'}, 'Nothing else was returned with it.'))));
}

function bundlePart(detail) {
    if (detail.responseKind !== 'BUNDLE') {
        return null;
    }
    return el('section', {class: 'detail__part'}, heading(4, 'The bundle'),
        facts([
            ['Type', shown(detail.bundleType, {as: 'literal'})],
            ['Id', shown(detail.bundleId, {as: 'literal'})],
            ['Timestamp', shown(detail.bundleTimestamp, {as: 'literal'})]
        ], 'facts--ruled'));
}

/**
 * How the request was built: the claim identifier it carried, how documents travelled, and the
 * server's notes on what was left out. The first note counts what PAS 2.0.1 requires and nobody
 * supplied, so it is shown open; the rest are folded under it.
 */
function requestPart(detail, operation) {
    const notes = detail.requestNotes || [];
    const part = el('section', {class: 'detail__part'}, heading(4, 'How this tool built the request'));
    part.append(facts([
        ['Claim identifier sent', detail.claimId ? code(detail.claimId) : none('none: the request carried no claim identifier')],
        ['Attachment mode', operation === 'PAS_INQUIRE' ? null
            : detail.attachmentMode ? code(detail.attachmentMode) : none('no attachment was sent')]
    ], 'facts--ruled'));
    if (detail.bodyBuiltByTool === false) {
        part.append(el('p', null, 'The body the tester supplied was sent in place of one this tool would have built.'));
    }
    part.append(notesFold(notes, 'request'));
    return part;
}

/** The server's notes on a request: the first open, the rest folded. Shared by the preview and the result. */
export function notesFold(notes, what) {
    if (!notes || notes.length === 0) {
        return el('p', {class: 'quiet'}, 'The server made no notes on this ' + what + '.');
    }
    const [first, ...rest] = notes;
    return el('div', {class: 'pas-notes'},
        el('ul', {class: 'note-list'}, el('li', null, first)),
        rest.length > 0 ? el('details', {class: 'fold'},
            el('summary', null, 'Every note on how the ' + what + ' was built', el('span', {class: 'quiet'}, notes.length + ' notes')),
            el('ul', {class: 'note-list'}, rest.map(note => el('li', null, note)))) : null);
}

/* ---------- subscriptions ---------- */

/** The detail of a subscription call: create, read, list, update or delete. */
export function subscriptionDetail(envelope) {
    const detail = envelope.detail;
    const node = el('div', {class: 'detail pas-detail'});
    node.append(kindLine(SUBSCRIPTION_KINDS, detail.responseKind, detail.responseNote));
    node.append(facts([
        ['Subscription this run is about', detail.subscriptionId ? [code(detail.subscriptionId),
            detail.idSource ? el('div', {class: 'small quiet'}, 'Read from ' + detail.idSource + '.') : null]
            : none(envelope.operation === 'SUBSCRIPTION_GET' && !detail.subscriptionId ? 'none: a list' : 'none stated')],
        ['Url it named for call back', envelope.operation === 'SUBSCRIPTION_CREATE' || envelope.operation === 'SUBSCRIPTION_UPDATE'
            ? (detail.callbackUrl ? code(detail.callbackUrl) : el('strong', null, 'none: the request named no url to call back')) : null],
        ['Bundle', detail.responseKind === 'BUNDLE' ? ['type ', shown(detail.bundleType, {as: 'literal'}), ', id ', shown(detail.bundleId, {as: 'literal'}),
            ', total stated ', shown(detail.total, {as: 'literal'})] : null]
    ], 'facts--ruled'));

    const subscriptions = detail.subscriptions || [];
    node.append(el('section', {class: 'detail__part'}, heading(4, 'Subscriptions in the answer', subscriptions.length),
        subscriptions.length === 0 ? el('p', {class: 'quiet'}, 'The answer carried no Subscription resource.')
            : subscriptions.map(subscriptionBlock)));
    append(node, issuesPart(detail.issues || [], envelope), otherResourcesPart(detail.otherResources || []));
    if (envelope.operation !== 'SUBSCRIPTION_GET' && envelope.operation !== 'SUBSCRIPTION_DELETE') {
        node.append(el('section', {class: 'detail__part'}, heading(4, 'How this tool built the request'),
            detail.bodyBuiltByTool === false ? el('p', null, 'The body the tester supplied was sent in place of one this tool would have built.') : null,
            notesFold(detail.requestNotes || [], 'request')));
    }
    return node;
}

function subscriptionBlock(subscription) {
    const headers = subscription.headers || [];
    return el('div', {class: 'detail__item'},
        el('p', {class: 'detail__item-head'},
            el('span', {class: 'sub-heading'}, 'Subscription ', shown(subscription.id, {as: 'literal'})),
            el('span', {class: 'small quiet'}, 'at ', code(subscription.foundAt))),
        el('div', {class: 'facts-pair'},
            facts([
                ['Status', coded(subscription.status)],
                ['Subscribed to', shown(subscription.criteria, {as: 'literal'})],
                ['Filters', shownList(subscription.filters, {as: 'literal'})],
                ['Calls back', shown(subscription.endpoint, {as: 'literal'})],
                ['Channel', shown(subscription.channelType, {as: 'literal'})],
                ['Heartbeat, seconds', shown(subscription.heartbeat, {as: 'literal'})],
                ['Last error', shown(subscription.error)]
            ], 'facts--ruled'),
            facts([
                ['Payload', shown(subscription.payload, {as: 'literal'})],
                ['Payload content', coded(subscription.payloadContent)],
                ['Headers sent with a notification', headers.length === 0 ? noneMark()
                    : [shownList(headers, {as: 'literal'}), el('div', {class: 'small quiet'}, 'Names only. A header\'s value is a credential for the receiver and is never shown.')]],
                ['Timeout', shown(subscription.timeout, {as: 'literal'})],
                ['Most per notification', shown(subscription.maxCount, {as: 'literal'})],
                ['Reason', shown(subscription.reason)],
                ['Ends', shown(subscription.end, {as: 'literal'})],
                ['Profiles', shownList(subscription.profiles, {as: 'literal'})],
                ['Full url', shown(subscription.fullUrl, {as: 'literal'})],
                ['Why the list returned it', shown(subscription.searchMode, {as: 'literal'})]
            ], 'facts--ruled')),
        (subscription.extensions || []).length > 0
            ? [el('p', {class: 'sub-heading'}, 'Other extensions (' + subscription.extensions.length + ')'), extensionList(subscription.extensions)] : null);
}

/** For a tab that renders a result: the right detail for the protocol of the envelope. */
export function detailFor(vocabulary) {
    return envelope => (envelope.protocol === 'SUBSCRIPTION' ? subscriptionDetail(envelope) : pasDetail(envelope, vocabulary));
}
