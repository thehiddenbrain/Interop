// What the trail kept of one run (R-21): its request and its answer as sent and received, the
// secrets masked as the wire view masks them. Read from GET /api/trail/{runId} each time a run is
// opened, so a run the trail no longer keeps says so in the server's own line and never shows a
// body from an earlier reading.
//
// The trail is not the ledger: it holds member data on purpose, for the vendor. Nothing of it is
// kept in the page beyond the open view.

import {el, fold} from './dom.js';
import {errorPanel} from './fault.js';
import {jsonView} from './json-view.js';

/** The address of a run's kept entry as a file. */
export function trailDownload(runId) {
    return '/api/trail/' + encodeURIComponent(runId) + '/download';
}

/**
 * The entry a trail answer holds, or null: only an answer that says kept, with an entry, is one.
 * answer: the {ok, status, data | error} of GET /api/trail/{runId}.
 */
export function keptEntry(answer) {
    const data = answer && answer.ok ? answer.data : null;
    return data && data.kept === true && data.entry && typeof data.entry === 'object' ? data.entry : null;
}

/**
 * One side of a run as the trail kept it: side 'request' or 'answer'. Returns {summary, hint,
 * body()}, the body drawn when the fold opens. Kept: the side and "Download". Not kept: the
 * server's line and nothing else. The trail not read: why, through the one error shape.
 */
export function trailPart(answer, runId, side) {
    const summary = side === 'request' ? 'Request JSON' : 'Answer JSON';
    if (!answer || !answer.ok) {
        return {summary, hint: 'the trail could not be read', body: () => errorPanel(answer ? answer.error : null)};
    }
    const data = answer.data || {};
    const entry = keptEntry(answer);
    if (!entry) {
        const line = typeof data.line === 'string' && data.line !== '' ? data.line : 'Not kept in the trail.';
        return {summary, hint: line, body: () => el('p', {class: 'muted small'}, line)};
    }
    const wire = entry.envelope && entry.envelope.wire ? entry.envelope.wire : {};
    return {summary, hint: 'from the trail', body: () => el('div', {class: 'audit-trail'},
        side === 'request' ? requestSide(wire.request) : answerSide(wire.response),
        el('p', {class: 'audit-trail__foot'},
            el('a', {class: 'btn btn--small', href: trailDownload(runId), download: ''}, 'Download'),
            el('span', {class: 'muted small'}, ' The request and the answer as one file, as the trail keeps them.')))};
}

function requestSide(request) {
    if (!request) {
        return el('p', {class: 'muted small'}, 'The trail holds no request for this run.');
    }
    // One line, as the request went: the method and the address. A request the mock answered was never sent and has no
    // address, so its own note says so instead of an empty address.
    return [
        el('p', {class: 'mono small audit-trail__url'}, request.method || '', request.url ? ' ' + request.url : null),
        !request.url && request.note ? el('p', {class: 'muted small'}, request.note) : null,
        hasBody(request.body) ? jsonView(request.body, {label: 'Request JSON'}) : el('p', {class: 'muted small'}, 'This request had no body.')
    ];
}

function answerSide(response) {
    if (!response) {
        // A timeout or a refused connection leaves a request and no answer: said, never drawn as an empty answer.
        return el('p', {class: 'muted small'}, 'No answer arrived for this request.');
    }
    return [
        el('p', {class: 'mono small'}, typeof response.status === 'number' ? 'HTTP ' + response.status : 'No status'),
        hasBody(response.body) ? jsonView(response.body, {label: 'Answer JSON'}) : el('p', {class: 'muted small'}, 'The answer had no body.')
    ];
}

function hasBody(body) {
    return body !== undefined && body !== null && !(typeof body === 'string' && body === '');
}

/** The two folds, Request JSON and Answer JSON, closed, from one trail answer. */
export function trailFolds(answer, runId) {
    return ['request', 'answer'].map(side => {
        const part = trailPart(answer, runId, side);
        return fold({summary: part.summary, hint: part.hint, body: part.body});
    });
}
