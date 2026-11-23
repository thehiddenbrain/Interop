// The Request line of a Workbench step (D-313): which request of the stored collections the step
// sends, where that comes from, and a list to choose another for this page.
//
// A vendor's collection can hold many requests for one step, each for a service of its own. PAVE
// used the first in the file that fits, and a tester whose service was another one typed its
// address, after which the request went in the wrong arrangement. The line says which request is in
// force, so that is never a guess, and the list lets the tester send another without touching the
// settings.
//
// What is chosen here lives in this module's memory: until another is chosen or the page is left.
// Nothing of it goes to browser storage, and no body is fetched into the page: the server takes the
// request, and its body, from the stored collection.

import {call} from './api.js';
import {el, button, chip, fill, clear, named} from './dom.js';
import {errorPanel} from './fault.js';
import {operationOf} from './wb-values.js';

/** The operations a Workbench step sends a request of the collection for. */
export const STEP_OPERATIONS = ['CRD_ORDER_SIGN', 'CRD_ORDER_SELECT', 'DTR_QUESTIONNAIRE_PACKAGE', 'DTR_NEXT_QUESTION', 'PAS_SUBMIT', 'PAS_INQUIRE'];

// operation -> {collectionId, requestId}: a request chosen on this page.
const chosen = new Map();
// operation -> true: "As the collection writes it" chosen for the step's runs.
const asWritten = new Map();
// operation -> sentence: what "Use for every run" did, kept across the redraw the change of configuration brings.
const said = new Map();
let effective = null;
const lines = new Set();

/** The answer of GET /api/config/effective the lines are drawn from; a choice no longer listed is forgotten. */
export function useEffective(data) {
    effective = data && typeof data === 'object' ? data : null;
    for (const [operation, ref] of Array.from(chosen)) {
        if (!findRequest(requestsOf(operation), ref)) {
            chosen.delete(operation);
        }
    }
    for (const line of lines) {
        line.redraw();
    }
}

/** The request chosen on this page for an operation, as a run names it, or null. */
export function chosenFor(operation) {
    const ref = chosen.get(operation);
    return ref ? {collectionId: ref.collectionId, requestId: ref.requestId} : null;
}

/** Chooses a request for an operation on this page; null takes the choice back. */
export function choose(operation, ref) {
    if (ref && ref.collectionId && ref.requestId) {
        chosen.set(operation, {collectionId: ref.collectionId, requestId: ref.requestId});
    } else {
        chosen.delete(operation);
    }
    said.delete(operation);
    for (const line of lines) {
        line.redraw();
    }
}

/** True when the step's runs send the collection's body as written. */
export function sendsAsWritten(operation) {
    return asWritten.get(operation) === true;
}

/** Chooses how the step's body is made: true for "As the collection writes it", false for "Built from the case". */
export function writeAsWritten(operation, on) {
    if (on) {
        asWritten.set(operation, true);
    } else {
        asWritten.delete(operation);
    }
    for (const line of lines) {
        line.redraw();
    }
}

// What a run built from the case carries and a body as written does not: the body names its own member, provider and
// codes, and sending the Case column's beside it would put on the ledger line a member the request never named.
const CASE_FIELDS = ['member', 'memberOverrides', 'provider', 'providerOverrides', 'servicingProvider', 'procedureCodes', 'diagnosisCodes',
    'dateOfService', 'placeOfService', 'levelOfService', 'certificationType', 'requestType', 'serviceType', 'questionnaires',
    'coverageAssertionId', 'questionnaire', 'questionnaireResponse', 'answers', 'wrapInParameter', 'attachments', 'claimId', 'rawBody'];

/**
 * A run's body with the request chosen for its step, when one is: the server then sends that
 * request's address and headers and follows its sample. With "As the collection writes it" the
 * run asks the server for the request's own body: the page holds none and sends nothing of the case
 * for it. A body for any other call goes as it was.
 */
export function withChosenRequest(path, body) {
    if (!body || typeof body !== 'object' || Array.isArray(body)) {
        return body;
    }
    const operation = operationOf(path, body);
    if (!STEP_OPERATIONS.includes(operation)) {
        return body;
    }
    const ref = chosenFor(operation);
    const out = ref ? {...body, request: ref} : {...body};
    if (sendsAsWritten(operation)) {
        for (const name of CASE_FIELDS) {
            delete out[name];
        }
        out.asWritten = true;
    }
    return ref || sendsAsWritten(operation) ? out : body;
}

function requestsOf(operation) {
    const row = rowOf(operation);
    return row && Array.isArray(row.requests) ? row.requests : [];
}

function rowOf(operation) {
    const rows = effective && Array.isArray(effective.operations) ? effective.operations : [];
    return rows.find(each => each && each.operation === operation) || null;
}

function findRequest(requests, ref) {
    if (!ref) {
        return null;
    }
    return requests.find(each => each.collectionId === ref.collectionId && each.request && each.request.id === ref.requestId) || null;
}

/** "Folder / Name": how a request is named on the line and in the list. */
export function requestName(available) {
    const request = available && available.request ? available.request : {};
    const folders = Array.isArray(request.folderPath) ? request.folderPath : [];
    return folders.concat([request.name || '']).map(part => named(part)).join(' / ');
}

/** The end of a request's address, which tells two requests for one step apart: "POST .../epa/cds-services/order-sign". */
export function addressEnd(url) {
    const text = String(url || '').split('?')[0].split('#')[0];
    const path = text.replace(/^[A-Za-z][A-Za-z0-9+.-]*:\/\/[^/]*/, '');
    const parts = path.split('/').filter(part => part !== '');
    if (parts.length > 0 && parts[0].startsWith('{{')) {
        parts.shift();
    }
    if (parts.length === 0) {
        return '';
    }
    const kept = parts.slice(-3);
    // The start of an address (its host, or the placeholder that stands for it) is the same for every request of a service.
    return (path !== text || parts.length > kept.length || text.startsWith('{{') ? '.../' : '') + kept.join('/');
}

/**
 * Where the request in force comes from, worked out from the server's facts and never from its
 * words: {kind, available, words}. kind: 'chosen' (on this page), 'typed' (the address typed in the
 * settings), 'configuration' (mapped by hand in Configuration), 'first' (the first in the file
 * that fits), 'none'.
 */
export function inForce(operation) {
    const requests = requestsOf(operation);
    const row = rowOf(operation);
    const target = row && row.target ? row.target : null;
    const picked = findRequest(requests, chosen.get(operation));
    if (picked) {
        return {kind: 'chosen', available: picked, words: 'chosen here, for this page'};
    }
    if (target && target.typed === true) {
        return {kind: 'typed', available: null, words: 'the address typed in the settings'};
    }
    const used = requests.find(each => each.usedByDefault === true) || null;
    if (used) {
        const byHand = used.request && used.request.correctedOperation === operation;
        return {kind: byHand ? 'configuration' : 'first', available: used,
            words: byHand ? 'the one chosen in Configuration' : 'the first in the file that fits'};
    }
    return {kind: 'none', available: null, words: ''};
}

/**
 * The line, built once per step. spec: {operation() (the operation the step sends now), label (the
 * step in words), environment() (the active environment's name, for the link to its addresses),
 * changed() (a request chosen or taken back), laterStep (a step after the coverage check, which
 * says that what was handed on is not put into a body as written)}. Returns {node, summary, redraw()}: summary is the
 * short line for under the step's head.
 */
export function createRequestLine(spec) {
    const node = el('div', {class: 'request-line', 'data-request-line': ''});
    // Shown under the step's head while several requests fit, so the one in force is seen without opening the Options.
    const summary = el('p', {class: 'small request-line__summary', hidden: true});
    let open = false;
    let filter = '';
    let status = null;

    const line = {node, summary, redraw};
    lines.add(line);

    function operation() {
        return typeof spec.operation === 'function' ? spec.operation() : spec.operation;
    }

    function redraw() {
        const op = operation();
        const requests = requestsOf(op);
        const row = rowOf(op);
        const force = inForce(op);
        const target = row && row.target ? row.target : null;
        const parts = [el('span', {class: 'request-line__label'}, 'Request'), ' '];
        if (force.available) {
            parts.push(el('strong', {class: 'request-line__name'}, requestName(force.available)), ' ',
                el('span', {class: 'mono small'}, ((force.available.request && force.available.request.method) || '') + ' '
                    + addressEnd(force.available.request && force.available.request.urlTemplate)),
                ' ', el('span', {class: 'muted small'}, force.words));
        } else if (force.kind === 'typed') {
            parts.push(el('strong', null, 'The address typed in the settings'), ' ',
                el('span', {class: 'mono small'}, (target.display || target.template || '')));
        } else if (target && target.source) {
            parts.push(el('span', null, target.source));
        } else {
            parts.push(el('span', {class: 'muted'}, 'No request of the stored collections fits this step.'));
        }
        const shown = [el('p', {class: 'request-line__head'}, parts, ' ', changeButton(op, requests, force))];
        shown.push(...explain(op, requests, force, target));
        const stored = said.get(op);
        if (stored) {
            shown.push(stored);
        }
        if (status) {
            shown.push(status);
        }
        if (open && requests.length > 1) {
            shown.push(list(op, requests, force));
        }
        shown.push(bodyChoice(op, force));
        fill(node, shown);
        drawSummary(requests, force);
    }

    function drawSummary(requests, force) {
        summary.hidden = requests.length < 2;
        if (summary.hidden) {
            clear(summary);
            return;
        }
        fill(summary, 'Request: ', force.available ? ['"' + requestName(force.available) + '", ', force.words]
            : force.kind === 'typed' ? 'the address typed in the settings' : 'none of them', '. ', String(requests.length),
            ' requests of the stored collections fit this step; choose another under Options.');
    }

    /**
     * "Body": built from the case, or as the collection writes it. Offered for a request of the
     * collection that has a body of its own; an address typed in the settings has none.
     */
    function bodyChoice(op, force) {
        if (!force.available && force.kind !== 'typed') {
            asWritten.delete(op);
            return null;
        }
        const request = force.available && force.available.request ? force.available.request : null;
        const possible = Boolean(request && request.hasBody === true);
        if (!possible && sendsAsWritten(op)) {
            asWritten.delete(op);
        }
        const group = 'request-body-' + op;
        const option = (value, words, checked) => {
            const input = el('input', {type: 'radio', name: group, value, checked, disabled: !possible && value === 'written'});
            input.addEventListener('change', () => {
                if (input.checked) {
                    writeAsWritten(op, value === 'written');
                    if (typeof spec.changed === 'function') {
                        spec.changed();
                    }
                }
            });
            return el('label', {class: 'request-body__option'}, input, ' ', words);
        };
        const written = sendsAsWritten(op);
        return el('div', {class: 'request-body', role: 'radiogroup', 'aria-label': 'Body'},
            el('span', {class: 'request-line__label'}, 'Body'), ' ',
            option('case', 'Built from the case', !written), ' ',
            option('written', 'As the collection writes it', written),
            written ? el('p', {class: 'small request-line__note'}, 'The body is the collection\'s own. The member, provider and codes in it are '
                + 'sent, not those of the Case column.', spec.laterStep ? ' What the step before handed on is not put into it.' : null,
                ' A placeholder with no value is asked for before anything is sent; Postman\'s {{$guid}}, {{$randomUUID}}, {{$timestamp}}, '
                + '{{$isoTimestamp}} and {{$randomInt}} are made afresh at each send.') : null,
            !possible ? el('p', {class: 'small muted'}, force.kind === 'typed'
                ? 'The address typed in the settings has no body of a collection request: choose a request to send its body as written.'
                : 'The request in use has no body of its own to send as written.') : null);
    }

    function changeButton(op, requests, force) {
        if (requests.length > 1) {
            const toggle = button(open ? 'Close the list' : 'Change', () => {
                open = !open;
                redraw();
                if (open) {
                    const box = node.querySelector('input');
                    const first = box || node.querySelector('.request-list button');
                    if (first) {
                        first.focus();
                    }
                }
            }, {small: true, quiet: true});
            toggle.setAttribute('aria-expanded', open ? 'true' : 'false');
            return toggle;
        }
        // One request and a typed address in force: the one request can still be sent in its place.
        if (requests.length === 1 && force.kind === 'typed') {
            return button('Use this request instead', () => pick(op, requests[0]), {small: true, quiet: true});
        }
        return null;
    }

    function explain(op, requests, force, target) {
        const out = [];
        if (requests.length === 0) {
            if (force.kind === 'typed' || (target && target.source)) {
                out.push(el('p', {class: 'small muted'}, 'No request of the stored collections fits this step, so there is none to choose.'));
            }
        } else if (requests.length === 1) {
            out.push(el('p', {class: 'small muted'}, force.kind === 'typed'
                ? 'One request of the stored collections fits this step.'
                : 'It is the only request of the stored collections that fits this step.'));
        }
        if (force.kind === 'typed') {
            out.push(el('p', {class: 'small'}, 'The typed address carries no header and no body of a collection request. Choosing a request '
                + 'sends it to the request\'s own address instead.'));
        }
        if (force.kind === 'chosen') {
            if (target && target.typed === true) {
                out.push(el('p', {class: 'small request-line__note'}, 'This run goes to the request\'s own address, not the one typed in the settings.'));
            }
            const row = el('p', {class: 'small'});
            const usable = force.available.errorSample !== true;
            const already = force.available.usedByDefault === true && !(target && target.typed === true);
            if (usable && !already) {
                row.append(button('Use for every run', () => useForEveryRun(op, requests, force.available), {small: true}), ' ');
            }
            if (!usable) {
                row.append(el('span', {class: 'muted'}, 'A sample of an error is sent only when it is chosen, so it cannot be made the one for every run. '));
            }
            row.append(button('Use the request in force', () => pick(op, null), {small: true, quiet: true}));
            out.push(row);
        }
        return out;
    }

    function list(op, requests, force) {
        const items = el('ul', {class: 'request-list', role: 'list'});
        const box = requests.length > 6 ? el('input', {class: 'input', type: 'search', value: filter, autocomplete: 'off', spellcheck: false,
            'aria-label': 'Filter the requests by name, folder or address', placeholder: 'Filter by name, folder or address'}) : null;
        const drawItems = () => {
            const words = filter.trim().toLowerCase();
            const shown = requests.filter(each => words === '' || (requestName(each) + ' ' + ((each.request && each.request.urlTemplate) || '')
                + ' ' + (each.collectionName || '')).toLowerCase().includes(words));
            fill(items, shown.length === 0 ? el('li', {class: 'small muted'}, 'No request fits what is typed.')
                : shown.map(each => item(op, each, force)));
        };
        if (box) {
            box.addEventListener('input', () => {
                filter = box.value;
                drawItems();
            });
        }
        drawItems();
        return el('div', {class: 'request-list__wrap'},
            el('p', {class: 'small muted'}, requests.length + ' requests of the stored collections fit this step. The one chosen is used for '
                + 'this step\'s runs until another is chosen or the page is left.'),
            box, items);
    }

    function item(op, available, force) {
        const request = available.request || {};
        const current = force.available === available;
        const choice = el('button', {type: 'button', class: ['request-list__item', current ? 'request-list__item--current' : null],
            'aria-pressed': current ? 'true' : 'false', on: {click: () => pick(op, available)}},
            el('span', {class: 'request-list__name'}, requestName(available)), ' ',
            el('span', {class: 'mono small'}, (request.method || '') + ' ' + addressEnd(request.urlTemplate)), ' ',
            el('span', {class: 'request-list__marks'},
                available.collectionName ? el('span', {class: 'small muted'}, named(available.collectionName)) : null,
                available.usedByDefault === true ? [' ', chip('in force', {tone: 'neutral'})] : null,
                available.errorSample === true ? [' ', chip('sample of an error', {tone: 'action'})] : null));
        return el('li', null, choice);
    }

    function pick(op, available) {
        open = false;
        status = null;
        choose(op, available ? {collectionId: available.collectionId, requestId: available.request.id} : null);
        if (typeof spec.changed === 'function') {
            spec.changed();
        }
        const head = node.querySelector('.request-line__head button');
        if (head) {
            head.focus();
        }
    }

    /**
     * Makes the chosen request the one every run of the operation uses, through the mapping the
     * form's pick uses: each request that fits before it in the file stops standing for the
     * operation, then the chosen one is mapped to it. A sample of an error never stands for one,
     * so it is left as it is.
     */
    async function useForEveryRun(op, requests, available) {
        status = el('p', {class: 'small muted', role: 'status'}, 'Saving the mapping...');
        redraw();
        const at = requests.indexOf(available);
        const before = requests.slice(0, at).filter(each => each.errorSample !== true);
        let failed = null;
        for (const each of before) {
            const answer = await call('PUT', '/api/config/collections/' + encodeURIComponent(each.collectionId) + '/mapping',
                {body: {requestId: each.request.id, operation: 'UNMAPPED'}});
            failed = failed || (answer.ok ? null : answer);
        }
        if (!failed) {
            const answer = await call('PUT', '/api/config/collections/' + encodeURIComponent(available.collectionId) + '/mapping',
                {body: {requestId: available.request.id, operation: op}});
            failed = answer.ok ? null : answer;
        }
        if (failed) {
            status = el('div', null, errorPanel(failed.error));
            redraw();
            return;
        }
        status = null;
        const row = rowOf(op);
        const typed = Boolean(row && row.target && row.target.typed === true);
        const label = row && row.label ? row.label : spec.label;
        const environment = typeof spec.environment === 'function' ? spec.environment() : null;
        const edit = environment ? '#configuration/environments/' + encodeURIComponent(environment) + '/addresses' : '#configuration/environments';
        said.set(op, el('p', {class: 'small request-line__said', role: 'status'},
            'Every run of ' + label + ' that names no request now uses "' + requestName(available) + '"'
            + (before.length > 0 ? ', and the ' + (before.length === 1 ? 'request' : before.length + ' requests')
                + ' before it in the file no longer stand' + (before.length === 1 ? 's' : '') + ' for it.' : '.'),
            typed ? [' The address typed in the settings for ' + label + ' still wins until it is taken away: ',
                el('a', {href: edit}, 'Edit'), '.'] : null));
        // With an address typed, this page keeps sending the request itself; without one, the line now reads as in force.
        if (!typed) {
            chosen.delete(op);
        }
        redraw();
    }

    redraw();
    return line;
}

/** Forgets every line drawn: the Workbench is built once, so this is for a test. */
export function forgetLines() {
    lines.clear();
    chosen.clear();
    asWritten.clear();
    said.clear();
    effective = null;
}
