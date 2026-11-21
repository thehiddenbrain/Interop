// The Collections view of the Config tab: upload the vendor's Postman collection, see what the
// tool made of it, and correct the mapping of a request to an operation.
//
// The mapping table is drawn from /api/config/effective, which lists for every operation the
// requests mapped to it and the one a run uses by default. What each request is, and why it was
// mapped where it was, comes from the collections the tool holds. Nothing is mapped or judged in
// this page: a correction is sent to the server, and the table is read again from its answer.

import * as calls from './config-calls.js';
import {el, fill, clear, button, code, facts, table, heading, when, copyButton} from './dom.js';
import {clearFieldErrors} from './forms.js';
import {errorPanel} from './result.js';
import {none, flag, sentences, refusal, during, counted, requestName, requestMarks, readAgainAfter, staleRefusal} from './config-parts.js';

export function createCollectionsView(context) {
    let choosing = null;     // the operation a request is being chosen for
    let removing = null;     // the id of the collection a removal is being confirmed for
    let inFull = null;       // {collectionId, requestId} of the request shown in full

    /* ---------- the upload ---------- */

    const file = el('input', {type: 'file', id: 'config-collection-file', accept: '.json,application/json'});
    const upload = el('button', {type: 'submit', class: 'button button--primary'}, 'Upload a collection');
    const problem = el('div', {class: 'form-problem-slot', aria: {live: 'polite'}});
    const outcome = el('div', {class: 'config-outcome', aria: {live: 'polite'}});
    const form = el('form', {class: 'config-upload', noValidate: true, attrs: {'aria-label': 'Upload a Postman collection'}},
        el('div', {class: 'field config-upload__file', dataset: {field: 'file'}},
            el('label', {htmlFor: 'config-collection-file'}, 'Postman collection file'), file,
            el('span', {class: 'field__hint'}, 'In Postman: Collections, the menu of the collection, Export, Collection v2.1.')),
        problem,
        el('div', {class: 'button-row'}, upload));
    form.addEventListener('submit', event => {
        event.preventDefault();
        during(upload, send);
    });
    form.addEventListener('change', () => {
        clearFieldErrors(form);
        clear(problem);
    });

    async function send() {
        clearFieldErrors(form);
        clear(problem);
        clear(outcome);
        if (file.files.length === 0) {
            // The one problem that never reaches the server: there is nothing to send.
            fill(problem, el('div', {class: 'form-problem', role: 'alert'},
                el('p', {class: 'form-problem__title'}, 'No file is chosen.'),
                el('p', null, 'Choose the collection file that Postman exported, then upload it.')));
            file.focus();
            return;
        }
        const answer = await calls.uploadCollection(file.files[0]);
        if (answer.kind !== 'ok') {
            fill(problem, refusal(answer, [form]));
            return;
        }
        fill(outcome, uploaded(answer.body));
        file.value = '';
        await context.changed();
    }

    /** What the server read from the file: requests, where each went, what it could not place, what it warns about. */
    function uploaded(done) {
        const collection = done.collection;
        const requests = collection.requests || [];
        const byOperation = new Map();
        for (const request of requests) {
            if (request.effectiveOperation === 'UNMAPPED') {
                continue;
            }
            const label = request.effectiveLabel || request.effectiveOperation;
            byOperation.set(label, (byOperation.get(label) || 0) + 1);
        }
        const unplaced = requests.filter(request => request.effectiveOperation === 'UNMAPPED');
        const scripted = requests.filter(request => request.scripted || request.testScripted);
        return el('div', {class: 'config-done'},
            el('p', {class: 'config-done__title'}, 'Stored the collection "', collection.name, '": ', counted(requests.length, 'request', 'requests'), '.',
                done.replaced ? ' It replaced the one stored under that name.' : null),
            byOperation.size > 0
                ? el('p', null, 'Mapped: ', [...byOperation.entries()].map(([label, number], index) =>
                    [index > 0 ? ', ' : null, el('strong', null, label), number > 1 ? ' (' + number + ')' : null]), '.')
                : el('p', null, el('strong', null, 'No request was mapped to an operation of this tool.')),
            unplaced.length > 0
                ? el('p', null, el('strong', null, unplaced.length === 1 ? 'One request could not be placed: ' : unplaced.length + ' requests could not be placed: '),
                    unplaced.map((request, index) => [index > 0 ? ', ' : null, request.name]),
                    '. That is not an error: ', unplaced.length === 1 ? 'it' : 'each',
                    calls.canChangeMapping() ? ' can be sent as written, or mapped by hand below.' : ' can be sent from "Send as written".')
                : el('p', null, 'Every request was placed.'),
            scripted.length > 0
                ? el('p', null, el('strong', null, scripted.length === 1 ? 'One request carries a Postman script' : scripted.length + ' requests carry Postman scripts'),
                    ' (', scripted.map((request, index) => [index > 0 ? ', ' : null, request.name]), '). '
                    + 'This tool never runs a script, so a value a script would have set is not set. Each such request is marked wherever it appears.')
                : null,
            (done.warnings || []).length > 0 ? el('div', {class: 'config-warnings', role: 'alert'},
                el('p', {class: 'sub-heading'}, done.warnings.length === 1 ? 'The tool has one warning about this file' : 'The tool has ' + done.warnings.length + ' warnings about this file'),
                sentences(done.warnings)) : null,
            sentences(collection.notes));
    }

    /* ---------- the view ---------- */

    const mappingNode = el('section', {class: 'result__section'});
    const unmappedNode = el('section', {class: 'result__section'});
    const heldNode = el('section', {class: 'result__section'});
    // Not a live region: a request in full is long. It takes the focus when it arrives, which is announced.
    const detailNode = el('section', {class: 'result__section config-detail', id: 'config-request-in-full', tabIndex: -1});
    detailNode.hidden = true;
    // The outcome of a change to the mapping or a removal. It takes the focus when the change is
    // done, because the control that made it is redrawn away and the focus would fall to the page.
    const status = el('div', {class: 'config-outcome', tabIndex: -1, aria: {live: 'polite'}});

    // While production writes are unlocked, an upload ends the unlock, from this browser or any other.
    const uploadLocks = el('p', {class: 'config-switch config-switch--unlocked'});
    uploadLocks.hidden = true;

    const node = el('div', {class: 'config-view'},
        el('section', {class: 'result__section'}, heading(2, 'Upload a collection'), uploadLocks, form, outcome),
        mappingNode, status, detailNode, unmappedNode, heldNode);

    /** Every request of every collection the tool holds, with the collection it belongs to. */
    function allRequests() {
        const collections = (context.stored().collections || {}).collections || [];
        const all = [];
        for (const collection of collections) {
            for (const request of collection.requests || []) {
                all.push({collection, request});
            }
        }
        return all;
    }

    function draw() {
        const stored = context.stored();
        const access = (context.app.mode() || {}).writeAccess || {};
        uploadLocks.hidden = !(access.production === true && access.writesAllowed === true);
        fill(uploadLocks, el('strong', null, 'Production writes are unlocked now, and an upload locks them again. '),
            'Any change to the configuration does, made from any browser.');
        if (!stored.effective || !stored.collections) {
            fill(mappingNode, heading(2, 'Which request each operation uses'), el('p', {class: 'quiet'}, 'Reading the configuration from the tool.'));
            clear(unmappedNode);
            clear(heldNode);
            return;
        }
        const collections = stored.collections.collections || [];
        if (removing && !collections.some(collection => collection.id === removing)) {
            removing = null;
        }
        drawMapping(stored.effective, collections);
        drawUnmapped();
        drawHeld(collections);
        if (inFull && !allRequests().some(entry => entry.collection.id === inFull.collectionId && entry.request.id === inFull.requestId)) {
            inFull = null;
            detailNode.hidden = true;
            clear(detailNode);
        }
    }

    /* ---------- the mapping, operation by operation ---------- */

    function drawMapping(effective, collections) {
        const rows = (effective.operations || []).filter(row => row.operation !== 'UNMAPPED');
        // An operation with no request of its own can still have an address the server derived for
        // it (discovery is cut from the order-sign url). Only one with neither is counted as empty.
        const empty = rows.filter(row => row.outbound && (row.requests || []).length === 0 && !row.target);
        const derived = rows.filter(row => row.outbound && (row.requests || []).length === 0 && row.target);
        fill(mappingNode,
            heading(2, 'Which request each operation uses'),
            collections.length === 0
                ? el('p', null, el('strong', null, 'No collection is uploaded, so no operation has a request of the vendor\'s. '),
                    'Upload the collection above. Until then an operation can only use an address from application.yml.')
                : el('p', {class: 'quiet'}, empty.length === 0
                    ? 'Every operation that is sent has a request of the collection or an address.'
                    : el('strong', null, empty.length === 1 ? 'One operation has no request mapped to it and no address.'
                        : empty.length + ' operations have no request mapped to them and no address.'),
                    derived.length > 0 ? (derived.length === 1 ? ' One more has no request of its own and takes its address from elsewhere, as its row says.'
                        : ' ' + derived.length + ' more have no request of their own and take their address from elsewhere, as their rows say.') : null,
                    calls.canChangeMapping() ? ' Choose another request for an operation when detection placed the wrong one.' : null),
            calls.canChangeMapping() ? null : el('p', {class: 'small'}, el('strong', null, 'Changing the mapping is not offered here. '), calls.MAPPING_NOT_OFFERED),
            el('div', {class: 'table-scroll'}, el('table', {class: 'grid config-mapping' + (calls.canChangeMapping() ? '' : ' config-mapping--fixed')},
                el('thead', null, el('tr', null,
                    el('th', {scope: 'col'}, 'Operation'),
                    el('th', {scope: 'col'}, 'Request of the collection'),
                    el('th', {scope: 'col'}, 'How it was mapped'),
                    el('th', {scope: 'col'}, calls.canChangeMapping() ? 'Change' : 'Look'))),
                el('tbody', null, rows.flatMap(row => mappingRows(row))))));
    }

    function mappingRows(row) {
        const requests = row.requests || [];
        const missing = row.outbound && requests.length === 0;
        const lines = [];
        const changeCell = !calls.canChangeMapping() ? null
            : row.outbound || requests.length > 0 ? chooseControl(row) : none('nothing to map');
        if (requests.length === 0) {
            const target = row.target || null;
            lines.push(el('tr', {class: 'config-op' + (missing && !target ? ' config-op--missing' : '') + (row.outbound ? '' : ' config-op--inside')},
                el('th', {scope: 'row'}, el('span', {class: 'config-op__label'}, row.label)),
                el('td', {colSpan: 2}, target
                    // No request of its own, and an address all the same: where it comes from is the server's sentence.
                    ? [el('div', null, 'No request of its own. It is sent to ', target.display ? code(target.display, 'literal--address') : none('an address that is not complete'), '.'),
                        target.source ? el('div', {class: 'small'}, 'Where it comes from: ', target.source, '.') : null,
                        row.unavailable || row.note ? el('div', {class: 'small'}, row.unavailable || row.note) : null]
                    : missing
                        ? el('div', {class: 'config-op__unavailable'}, el('strong', null, 'Nothing is mapped. '),
                            row.unavailable || row.note || none('not stated'))
                        : el('span', {class: 'small'}, row.unavailable || row.note || none('not stated'))),
                el('td', null, changeCell || none('nothing to look at'))));
        } else {
            requests.forEach((available, index) => {
                const request = available.request;
                lines.push(el('tr', {class: 'config-op' + (index > 0 ? ' config-op--more' : '') + (index < requests.length - 1 ? ' config-op--more-follows' : '')
                    + (row.outbound ? '' : ' config-op--inside')},
                    index === 0 ? el('th', {scope: 'row', rowSpan: requests.length},
                        el('span', {class: 'config-op__label'}, row.label),
                        row.outbound ? null : el('div', {class: 'small quiet'}, row.unavailable || ''),
                        severalNote(row)) : null,
                    el('td', null, requestName(request),
                        el('div', {class: 'small'}, code(request.method), ' ', code(request.urlTemplate)),
                        el('div', {class: 'config-marks'},
                            available.usedByDefault ? el('span', {class: 'config-default'}, 'used by default') : null,
                            requests.length > 1 && !available.usedByDefault ? el('span', {class: 'small quiet'}, 'used when a run names it') : null),
                        requestMarks(request),
                        collectionsHeld() > 1 ? el('div', {class: 'small quiet'}, 'from ', available.collectionName) : null),
                    el('td', null, mappedHow(request)),
                    el('td', null, el('div', {class: 'config-change'},
                        button('Look at it in full', () => showInFull(available.collectionId, request.id), {class: 'button--text', aria: {label: 'Look at ' + request.name + ' in full'}}),
                        calls.canChangeMapping() && request.effectiveOperation !== 'UNMAPPED' ? takeOffControl(available.collectionId, request, row) : null,
                        index === requests.length - 1 && changeCell ? changeCell : null))));
            });
        }
        if (choosing === row.operation) {
            lines.push(chooserRow(row));
        }
        return lines;
    }

    /** The server's sentence beside an operation with several requests: which one a run takes, why, and how to have another taken. */
    function severalNote(row) {
        return row.defaultRule ? el('div', {class: 'small config-op__rule'}, row.defaultRule) : null;
    }

    function collectionsHeld() {
        return ((context.stored().collections || {}).collections || []).length;
    }

    function mappedHow(request) {
        return [
            request.correctedOperation
                ? el('div', null, flag('chosen by hand', 'Someone mapped this request by hand. The tool keeps the choice until it is changed again.'),
                    request.detectedOperation === 'UNMAPPED' ? ' Detection did not map it to any operation.'
                        : [' Detection proposed ', el('strong', null, context.operationLabel(request.detectedOperation)), '.'])
                : null,
            el('div', {class: 'small'}, request.detectionBasis || none('no reason given')),
            sentences(request.notes, 'small')
        ];
    }

    function chooseControl(row) {
        const open = choosing === row.operation;
        // With nothing mapped there is nothing to choose another of.
        const words = (row.requests || []).length === 0 ? 'Choose a request' : 'Choose another request';
        return button(open ? 'Close the choice' : words, () => {
            choosing = open ? null : row.operation;
            draw();
            if (!open) {
                const select = document.getElementById('config-choose-' + row.operation);
                if (select) {
                    select.focus();
                }
            }
        }, {class: 'button--quiet button--small', aria: {expanded: String(open), label: (open ? 'Close the choice of a request for ' : words + ' for ') + row.label}});
    }

    function takeOffControl(collectionId, request, row) {
        const control = button('Take it off this operation', () => during(control, () => remap(collectionId, request.id, 'UNMAPPED', null, row.label)),
            {class: 'button--text', aria: {label: 'Take ' + request.name + ' off ' + row.label}});
        return control;
    }

    /** The choice of a request for one operation: any request of any collection, looked at in full before it is used. */
    function chooserRow(row) {
        const entries = allRequests();
        const id = 'config-choose-' + row.operation;
        const select = el('select', {id});
        select.append(el('option', {value: ''}, 'Choose a request'));
        for (const collection of (context.stored().collections || {}).collections || []) {
            const group = el('optgroup', {label: collection.name});
            for (const request of collection.requests || []) {
                // A request mapped here already is listed, so the list is complete, and cannot be
                // chosen: choosing it would change nothing.
                const here = request.effectiveOperation === row.operation;
                group.append(el('option', {value: collection.id + ' ' + request.id, disabled: here},
                    request.name + ' (' + request.method + ')' + (here ? ', mapped to it already'
                        : request.effectiveOperation === 'UNMAPPED' ? ', not mapped to any operation'
                        : ', mapped to ' + (request.effectiveLabel || request.effectiveOperation) + ' now')));
            }
            select.append(group);
        }
        const problemSlot = el('div', {class: 'form-problem-slot'});
        const look = button('Look at it in full', () => {
            if (select.value) {
                const [collectionId, requestId] = select.value.split(' ');
                showInFull(collectionId, requestId);
            }
        }, {class: 'button--quiet button--small'});
        const use = button(['Use it for ', row.label], () => during(use, async () => {
            if (!select.value) {
                fill(problemSlot, el('p', {class: 'form-problem'}, 'Choose a request first.'));
                select.focus();
                return;
            }
            const [collectionId, requestId] = select.value.split(' ');
            await remap(collectionId, requestId, row.operation, problemSlot);
        }), {class: 'button--primary button--small'});
        return el('tr', {class: 'config-chooser'}, el('td', {colSpan: 4},
            el('div', {class: 'config-chooser__box', role: 'group', aria: {label: 'Choose a request for ' + row.label}},
                el('div', {class: 'field', dataset: {field: 'requestId'}},
                    el('label', {htmlFor: id}, ['The request of the collection to use for ', el('strong', null, row.label)]), select),
                entries.length === 0 ? el('p', null, 'No collection is uploaded, so there is no request to choose.') : null,
                el('p', {class: 'small quiet'}, 'The request keeps its own url, headers and sample body. A request moved here is no longer used for the operation it had before.'),
                row.defaultRule ? el('p', {class: 'small'}, row.defaultRule) : null,
                problemSlot,
                el('div', {class: 'button-row'}, look, use))));
    }

    /** fromLabel is the operation a request is being taken off, so the outcome can name it. */
    async function remap(collectionId, requestId, operation, problemSlot, fromLabel) {
        clear(status);
        const before = defaultsNow();
        const answer = await calls.changeMapping(collectionId, requestId, operation);
        if (answer.kind !== 'ok') {
            if (readAgainAfter(answer)) {
                // What this screen offered is out of date. The view is read again and redrawn, which
                // takes the chooser away with what it offered, so the refusal is said in the line
                // that stays, and the focus goes there.
                choosing = null;
                await context.changed();
                fill(status, staleRefusal(answer, 'the request you chose'));
                status.focus();
                return;
            }
            fill(problemSlot || status, refusal(answer, problemSlot ? [problemSlot.parentNode] : []));
            if (!problemSlot) {
                status.focus();
            }
            return;
        }
        const done = answer.body;
        choosing = null;
        const title = el('p', {class: 'config-done__title'}, done.changed && operation === 'UNMAPPED' && fromLabel
            // Taken off: what changed is that the operation no longer uses it, and where it went is the list below.
            ? ['"', done.request.name, '" is no longer used for ', fromLabel, '. It is listed below, among the requests not mapped to any operation.']
            : done.changed && done.request.effectiveOperation === 'UNMAPPED'
            ? ['"', done.request.name, '" is not mapped to any operation now.']
            : done.changed
            ? ['"', done.request.name, '" is mapped to ', done.request.effectiveLabel || done.request.effectiveOperation, ' now.']
            : ['"', done.request.name, '" was mapped to ', done.request.effectiveLabel || done.request.effectiveOperation, ' already. Nothing changed.']);
        // A change that moved nothing leaves the configuration as it was, so the frame is not asked to say it changed.
        if (done.changed) {
            await context.changed();
        }
        fill(status, el('div', {class: 'config-done'}, title,
            // The server's first note restates the change the title has just said (ConfigService.correctMapping
            // always puts it first); what it adds after that, such as a request that still counts as a write, is kept.
            sentences((done.notes || []).slice(1)),
            defaultsChanged(before, [operation, done.request.effectiveOperation])));
        status.focus();
    }

    /**
     * Which request each outbound operation uses by default, from /api/config/effective as last
     * read: operation name -> {row, used}, used being the request marked usedByDefault or null.
     */
    function defaultsNow() {
        const rows = ((context.stored().effective || {}).operations || []).filter(row => row.operation !== 'UNMAPPED' && row.outbound);
        return new Map(rows.map(row => [row.operation, {row, used: (row.requests || []).find(available => available.usedByDefault) || null}]));
    }

    function usedKey(used) {
        return used ? used.collectionId + ' ' + used.request.id : null;
    }

    /**
     * After a change, the request each operation it touched now uses by default, read from the
     * effective configuration, so a tester never has to work out what will be sent. An operation
     * named in always is said even when its default did not move; the others only when it did.
     */
    function defaultsChanged(before, always) {
        const after = defaultsNow();
        const lines = [];
        for (const [operation, now] of after) {
            const was = before.get(operation);
            const moved = !was || usedKey(was.used) !== usedKey(now.used);
            if (moved || always.includes(operation)) {
                lines.push(el('li', null, defaultSentence(now, moved)));
            }
        }
        return lines.length > 0 ? el('div', {class: 'config-defaults'},
            el('p', {class: 'sub-heading'}, 'What a run uses now'),
            el('ul', {class: 'note-list'}, lines)) : null;
    }

    function defaultSentence(now, moved) {
        const row = now.row;
        const label = el('strong', null, row.label);
        if (now.used) {
            return [label, moved ? ' now uses "' : ' still uses "', now.used.request.name, '" by default',
                collectionsHeld() > 1 ? [', from the collection "', now.used.collectionName, '"'] : null, '.'];
        }
        const target = row.target;
        return [label, ' uses no request of a collection by default now.',
            target && target.display ? [' It is sent to ', code(target.display, 'literal--address'), target.source ? ', from ' + target.source : null, '.'] : null,
            row.unavailable || row.note ? [' ', row.unavailable || row.note] : null];
    }

    /* ---------- requests no operation uses ---------- */

    function drawUnmapped() {
        const unmapped = allRequests().filter(entry => entry.request.effectiveOperation === 'UNMAPPED');
        if (unmapped.length === 0) {
            fill(unmappedNode, heading(2, 'Requests not mapped to any operation'),
                el('p', {class: 'quiet'}, allRequests().length === 0 ? 'No collection is uploaded.' : 'None. Every request of the collection is mapped.'));
            return;
        }
        fill(unmappedNode,
            heading(2, 'Requests not mapped to any operation', unmapped.length),
            el('p', {class: 'quiet'}, 'An unmapped request is not an error. It can be sent exactly as written, with its placeholders filled in',
                calls.canChangeMapping() ? ', or mapped to an operation by hand from the table above.' : '.'),
            table([{text: 'Request', class: 'col-30'}, 'Method and url', {text: '', class: 'col-22'}],
                unmapped.map(({collection, request}) => [
                    [requestName(request), requestMarks(request)],
                    el('span', {class: 'small'}, code(request.method), ' ', code(request.urlTemplate), el('div', {class: 'quiet'}, request.detectionBasis || '')),
                    el('div', {class: 'config-change'},
                        button('Look at it in full', () => showInFull(collection.id, request.id), {class: 'button--text', aria: {label: 'Look at ' + request.name + ' in full'}}),
                        button('Send as written', () => context.go('raw', collection.id + '/' + request.id), {class: 'button--text', aria: {label: 'Send as written: ' + request.name}}))
                ]), {class: 'config-unmapped'}));
    }

    /* ---------- the collections the tool holds ---------- */

    function drawHeld(collections) {
        if (collections.length === 0) {
            fill(heldNode, heading(2, 'Collections the tool holds', 0),
                el('p', null, 'None yet. Choose the collection file the vendor gave you and upload it above.'));
            return;
        }
        const body = [];
        for (const collection of collections) {
            const requests = collection.requests || [];
            const scripted = requests.filter(request => request.scripted).length;
            const actions = [];
            if (calls.canRemove()) {
                actions.push(button('Remove', () => { removing = collection.id; draw(); focusKeep(); },
                    {class: 'button--text', aria: {label: 'Remove the collection ' + collection.name, expanded: String(removing === collection.id)}}));
            }
            body.push(el('tr', null,
                el('td', null, el('strong', null, collection.name),
                    el('div', {class: 'small quiet'}, 'from the file ', code(collection.originalFileName || 'with no name')),
                    sentences(collection.notes, 'small')),
                el('td', null, counted(requests.length, 'request', 'requests'),
                    scripted > 0 ? el('div', {class: 'small'}, counted(scripted, 'carries a pre-request script', 'carry a pre-request script')) : null),
                el('td', null, variablesFold(collection)),
                el('td', null, when(collection.uploadedAt)),
                el('td', {class: 'config-actions'}, actions.length > 0 ? actions : none('none here'))));
            if (removing === collection.id) {
                body.push(removalRow(collection));
            }
        }
        fill(heldNode,
            heading(2, 'Collections the tool holds', collections.length),
            el('table', {class: 'grid config-collections'},
                el('thead', null, el('tr', null, ['Collection', 'Requests', 'Variables of the collection', 'Uploaded', ''].map(text => el('th', {scope: 'col'}, text)))),
                el('tbody', null, body)),
            calls.canRemove() ? null : el('p', {class: 'quiet small'}, el('strong', null, 'Removing a collection is not offered here. '), calls.REMOVAL_NOT_OFFERED));
    }

    function variablesFold(collection) {
        const variables = collection.variables || [];
        if (variables.length === 0) {
            return none('none defined');
        }
        return el('details', {class: 'fold config-fold'},
            el('summary', null, counted(variables.length, 'variable', 'variables')),
            el('ul', {class: 'plain-list small'}, variables.map(variable => el('li', null, code(variable.name), ': ',
                variable.set ? code(variable.display) : none('blank'),
                variable.secret ? [' ', flag('secret')] : null,
                variable.inForce ? null : [' ', flag('not in force')]))));
    }

    function removalRow(collection) {
        const keep = button('Keep it', () => { removing = null; draw(); }, {class: 'button--quiet', id: 'config-collection-keep'});
        const remove = button(['Remove the collection "', collection.name, '"'], () => during(remove, () => removeNow(collection)), {class: 'button--quiet'});
        return el('tr', {class: 'config-removal'}, el('td', {colSpan: 5},
            el('div', {class: 'config-removal__box', role: 'group', aria: {label: 'Remove the collection ' + collection.name}},
                el('p', null, el('strong', null, 'Remove the collection "', collection.name, '"?'),
                    ' Its uploaded file is deleted from the tool, with every mapping chosen by hand for its requests. '
                    + 'Operations that use one of its requests fall back to another collection or to application.yml. '
                    + 'The file you uploaded it from is not touched.'),
                el('div', {class: 'button-row'}, keep, remove))));
    }

    function focusKeep() {
        const keep = document.getElementById('config-collection-keep');
        if (keep) {
            keep.focus();
        }
    }

    async function removeNow(collection) {
        clear(status);
        // What an earlier upload said is about a collection that may be the one going now.
        clear(outcome);
        const before = defaultsNow();
        const answer = await calls.removeCollection(collection.id);
        removing = null;
        await context.changed();
        if (answer.kind !== 'ok') {
            fill(status, staleRefusal(answer, 'the collection "' + collection.name + '"'));
        } else {
            fill(status, el('div', {class: 'config-done'},
                el('p', {class: 'config-done__title'}, 'Removed the collection "', answer.body.removed, '".'),
                (answer.body.warnings || []).length > 0 ? el('div', {class: 'config-warnings', role: 'alert'}, sentences(answer.body.warnings)) : null,
                defaultsChanged(before, [])));
        }
        status.focus();
    }

    /* ---------- one request in full ---------- */

    async function showInFull(collectionId, requestId) {
        inFull = {collectionId, requestId};
        detailNode.hidden = false;
        fill(detailNode, heading(2, 'One request in full'), el('p', {class: 'quiet'}, 'Reading the request from the tool.'));
        detailNode.scrollIntoView({block: 'start'});
        const answer = await calls.requestInFull(collectionId, requestId);
        if (!inFull || inFull.collectionId !== collectionId || inFull.requestId !== requestId) {
            return;
        }
        if (answer.kind !== 'ok') {
            fill(detailNode, heading(2, 'One request in full'), errorPanel(answer.body));
            return;
        }
        fill(detailNode, requestDetail(answer.body, () => {
            inFull = null;
            detailNode.hidden = true;
            clear(detailNode);
        }));
        detailNode.focus({preventScroll: true});
    }

    function show(argument) {
        if (argument) {
            const [collectionId, requestId] = argument.split('/');
            if (collectionId && requestId) {
                showInFull(collectionId, requestId);
            }
        }
    }

    return {node, show, draw};
}

/** One request of a collection as the vendor wrote it, secrets masked by the server. */
function requestDetail(detail, close) {
    const request = detail.request;
    const headers = detail.headers || [];
    const scripts = detail.scripts || [];
    const examples = detail.examples || [];
    const pathVariables = detail.pathVariables || [];
    const body = detail.body !== undefined ? el('pre', {class: 'body-text', tabIndex: 0, aria: {label: 'The sample body of ' + request.name}}, detail.body) : null;
    const auth = detail.auth;
    return [
        el('div', {class: 'panel__head'},
            heading(2, 'One request in full: ' + request.name),
            button('Close this request', close, {class: 'button--quiet button--small'})),
        facts([
            ['Collection', detail.collectionName],
            ['Folder', (request.folderPath || []).length > 0 ? request.folderPath.join(' / ') : none('at the top of the collection')],
            ['Method', code(request.method)],
            ['Url as written', code(request.urlTemplate)],
            ['Mapped to', [el('strong', null, request.effectiveLabel || request.effectiveOperation),
                request.correctedOperation ? [' ', flag('chosen by hand')] : null,
                el('div', {class: 'small'}, request.detectionBasis || '')]],
            ['Marks', requestMarks(request) || none('none')],
            ['Description', detail.description ? el('span', {class: 'vendor-text'}, detail.description) : null]
        ], 'facts--ruled'),
        sentences(request.notes),
        el('h3', {class: 'sub-heading'}, 'Headers'),
        headers.length > 0
            ? table([{text: 'Header', class: 'col-25'}, 'Value as written'],
                headers.map(header => ({class: header.disabled ? 'config-row--idle' : null, cells: [
                    [code(header.name), header.disabled ? [' ', flag('switched off', 'The collection switches this header off, so it is not sent.')] : null],
                    header.value !== undefined ? code(header.value) : none('no value')]})))
            : el('p', null, 'The request has no headers.'),
        pathVariables.length > 0 ? [el('h3', {class: 'sub-heading'}, 'Parts of the url filled for each call'),
            table(['Part', 'Value the collection saved'], pathVariables.map(variable => [code(':' + variable.name),
                variable.savedValue !== undefined ? [code(variable.savedValue), el('span', {class: 'small quiet'}, ' shown only, never sent')] : none('none saved')]))] : null,
        el('h3', {class: 'sub-heading'}, 'Sample body'),
        body ? el('div', {class: 'raw-body'},
            el('div', {class: 'panel__head'}, el('span', {class: 'small'}, detail.bodyMode ? ['Body mode ', code(detail.bodyMode)] : null,
                detail.bodyLanguage ? [', language ', code(detail.bodyLanguage)] : null),
                copyButton('Copy sample body', () => body.textContent, body)),
            body) : el('p', null, detail.bodyMode ? ['The request has a body of mode ', code(detail.bodyMode), ', which this tool does not load.'] : 'The request has no body.'),
        scripts.length > 0 ? [el('h3', {class: 'sub-heading'}, 'Scripts Postman would run'),
            el('p', null, el('strong', null, 'This tool never runs them.'), ' A value a script would have set is not set here, so read what each one does.'),
            scripts.map(script => el('div', {class: 'config-script'},
                el('p', {class: 'small'}, code(script.listen), ' from ', script.from),
                el('pre', {class: 'body-text', tabIndex: 0}, script.source)))] : null,
        auth ? [el('h3', {class: 'sub-heading'}, 'Postman authentication'),
            facts([['Type', code(auth.type)], ['Set on', auth.from || null],
                ['Placeholders', (auth.variables || []).length > 0 ? auth.variables.map((name, index) => [index > 0 ? ', ' : null, code('{{' + name + '}}')]) : null],
                ['What the tool does', auth.note || null]], 'facts--ruled')] : null,
        examples.length > 0 ? [el('h3', {class: 'sub-heading'}, 'Saved example responses', examples.length),
            examples.map(example => el('details', {class: 'fold'},
                el('summary', null, example.name || 'An example with no name', ' ',
                    el('span', {class: 'quiet'}, example.code !== undefined ? 'HTTP ' + example.code : '', example.status ? ' ' + example.status : '')),
                example.body !== undefined ? el('pre', {class: 'body-text', tabIndex: 0}, example.body) : el('p', null, 'The example has no body.')))] : null
    ];
}
