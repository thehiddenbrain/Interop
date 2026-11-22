// Configuration > Collections: upload the vendor's Postman collection export, see what PAVE made of
// it, and correct the operation a request stands for. Nothing is mapped or judged in this page: a
// correction is sent to the server and the table is read again from its answer.
//
// The file is read in the page when "Upload collection" is pressed (files.js, plan R-33) and its
// text is posted as JSON (R-06), so a file changed after it was chosen is one plain line and never a
// request the browser refuses.

import {call} from './api.js';
import {app} from './app.js';
import {el, fill, clear, chip, button, modal, named} from './dom.js';
import {errorPanel} from './fault.js';
import {readText} from './files.js';
import {pageHead, section, part, during, once, sentences, counted, day, none, fileRefusal, fileChooser, shownFile} from './cfg-parts.js';

const UNMAPPED = 'UNMAPPED';

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let collections = null;      // the last answer of GET /api/config/collections
    let operations = [];         // [{operation, label}] from GET /api/config/effective, for the mapping choice
    let open = new Set();        // ids of the collections whose mapping is open
    let gone = false;

    const file = el('input', {type: 'file', class: 'input', accept: '.json,application/json', 'aria-label': 'Collection export'});
    const busy = el('span', {class: 'muted small', role: 'status'});
    const upload = button('Upload collection', () => during(upload, busy, 'Uploading', send), {primary: true});
    const locks = el('p', {class: 'small', hidden: true});
    const outcome = el('div', {'aria-live': 'polite'});
    const status = el('div', {'aria-live': 'polite'});
    const table = el('div');

    const page = section(
        pageHead('Collections', 'Postman collection exports: sample requests and their mapping.', fileChooser(file, 'Collection export'), upload, busy),
        locks, outcome, status, table);
    root.append(page);

    async function send() {
        clear(outcome);
        clear(status);
        const read = await readText(file, {maxBytes: 20 * 1024 * 1024});
        shownFile(file);
        if (!read.ok) {
            fill(outcome, el('p', {class: 'field__error', role: 'alert'}, read.line));
            return;
        }
        const answer = await call('POST', '/api/config/collections', {body: {fileName: read.fileName, text: read.text}});
        // Whatever the answer, the chosen file is let go: the next press reads what is chosen then.
        file.value = '';
        shownFile(file);
        if (!answer.ok) {
            fill(outcome, fileRefusal(read.fileName, answer));
            return;
        }
        fill(outcome, uploaded(answer.data || {}));
        await load();
    }

    /** What the server read from the file, each sentence once. */
    function uploaded(done) {
        const collection = done.collection || {};
        const requests = Array.isArray(collection.requests) ? collection.requests : [];
        const placed = requests.filter(request => request.effectiveOperation && request.effectiveOperation !== UNMAPPED);
        const unplaced = requests.filter(request => request.effectiveOperation === UNMAPPED);
        const scripted = requests.filter(request => request.scripted || request.testScripted);
        const byOperation = new Map();
        for (const request of placed) {
            const label = request.effectiveLabel || request.effectiveOperation;
            byOperation.set(label, (byOperation.get(label) || 0) + 1);
        }
        let placing;
        if (requests.length === 0) {
            // Nothing was read as a request, so nothing can have been placed (hunt-files 11).
            placing = 'No request was read from this file.';
        } else if (unplaced.length === 0) {
            placing = 'Every request was placed.';
        } else {
            placing = counted(unplaced.length, 'request was', 'requests were') + ' not mapped to an operation: '
                + unplaced.map(request => named(request.name)).join(', ') + '. Map ' + (unplaced.length === 1 ? 'it' : 'each') + ' below, or send it from Advanced.';
        }
        return el('div', {class: requests.length === 0 || unplaced.length > 0 ? 'verdict verdict--action' : 'verdict verdict--clear'},
            el('p', {class: 'verdict__headline'}, 'Stored the collection "' + named(collection.name) + '": ' + counted(requests.length, 'request', 'requests') + '.',
                done.replaced ? ' It replaced the one stored under that name.' : null),
            byOperation.size > 0 ? el('p', null, 'Mapped: ' + Array.from(byOperation.entries())
                .map(([label, number]) => number > 1 ? label + ' (' + number + ')' : label).join(', ') + '.') : null,
            el('p', null, placing),
            scripted.length > 0 ? el('p', null, counted(scripted.length, 'request carries', 'requests carry')
                + ' a Postman script. PAVE never runs a script, so a value a script would set is not set.') : null,
            sentences(once(done.warnings, collection.notes)));
    }

    async function load() {
        const [list, effective] = await Promise.all([call('GET', '/api/config/collections'), call('GET', '/api/config/effective')]);
        if (gone) {
            return;
        }
        if (effective.ok && effective.data && Array.isArray(effective.data.operations)) {
            operations = effective.data.operations.filter(row => row.operation && row.operation !== UNMAPPED);
        }
        if (!list.ok) {
            fill(table, errorPanel(list.error));
            return;
        }
        collections = list.data && Array.isArray(list.data.collections) ? list.data.collections : [];
        draw();
    }

    function draw() {
        const mode = app.mode() || {};
        const access = mode.writeAccess || {};
        const unlocked = access.production === true && access.writesAllowed === true;
        locks.hidden = !unlocked;
        locks.textContent = unlocked ? 'Production writes are unlocked now, and an upload locks them again.' : '';
        if (collections === null) {
            fill(table, none('Reading the collections from PAVE.'));
            return;
        }
        if (collections.length === 0) {
            fill(table, part(null, el('p', {class: 'muted'}, 'No collection is uploaded yet. Choose the collection export the vendor gave you and upload it.')));
            return;
        }
        const rows = [];
        for (const collection of collections) {
            rows.push(collectionRow(collection));
            if (open.has(collection.id)) {
                // A removal sits inside the open row, apart from the button that opens it, so it is never pressed by accident.
                rows.push(el('tr', null, el('td', {colSpan: 6}, mapping(collection),
                    el('div', {class: 'form-foot'}, button('Remove collection', () => confirmRemove(collection), {small: true, remove: true})))));
            }
        }
        fill(table, part(null, el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
            el('thead', null, el('tr', null,
                el('th', {scope: 'col'}, 'Collection'), el('th', {scope: 'col', class: 'num'}, 'Requests'),
                el('th', {scope: 'col', class: 'num'}, 'Mapped'), el('th', {scope: 'col'}, 'Scripts'),
                el('th', {scope: 'col'}, 'Uploaded'), el('th', {scope: 'col'}, el('span', {class: 'visually-hidden'}, 'Actions')))),
            el('tbody', null, rows)))));
    }

    function collectionRow(collection) {
        const requests = Array.isArray(collection.requests) ? collection.requests : [];
        const mapped = requests.filter(request => request.effectiveOperation && request.effectiveOperation !== UNMAPPED).length;
        const scripts = requests.filter(request => request.scripted || request.testScripted).length;
        const isOpen = open.has(collection.id);
        return el('tr', null,
            el('td', null, collection.name, collection.originalFileName ? el('div', {class: 'small muted'}, collection.originalFileName) : null),
            el('td', {class: 'num'}, String(requests.length)),
            el('td', {class: 'num'}, String(mapped)),
            el('td', null, scripts > 0 ? chip(counted(scripts, 'script', 'scripts') + ', not run', {tone: 'neutral', title: 'PAVE never runs a Postman script.'})
                : none('none')),
            el('td', null, el('span', {class: 'mono'}, day(collection.uploadedAt))),
            el('td', null,
                el('button', {type: 'button', class: 'btn btn--small', 'aria-expanded': String(isOpen), on: {click: () => {
                    if (isOpen) {
                        open.delete(collection.id);
                    } else {
                        open.add(collection.id);
                    }
                    draw();
                }}}, 'Mapping')));
    }

    /** One line per request: its name, method and the operation it stands for, changed at once. */
    function mapping(collection) {
        const requests = Array.isArray(collection.requests) ? collection.requests : [];
        if (requests.length === 0) {
            return el('p', {class: 'muted'}, 'This collection holds no request.');
        }
        return el('div', {class: 'table-wrap'}, el('table', {class: 'table'},
            el('thead', null, el('tr', null, el('th', {scope: 'col'}, 'Request'), el('th', {scope: 'col'}, 'Method'),
                el('th', {scope: 'col'}, 'Stands for'), el('th', {scope: 'col'}, 'How it was mapped'))),
            el('tbody', null, requests.map(request => mappingRow(collection, request)))));
    }

    function mappingRow(collection, request) {
        const select = el('select', {class: 'select', 'aria-label': 'Operation for ' + named(request.name)});
        const known = new Set();
        for (const row of operations) {
            known.add(row.operation);
            select.append(el('option', {value: row.operation, selected: row.operation === request.effectiveOperation}, row.label || row.operation));
        }
        if (request.effectiveOperation && request.effectiveOperation !== UNMAPPED && !known.has(request.effectiveOperation)) {
            select.append(el('option', {value: request.effectiveOperation, selected: true}, request.effectiveLabel || request.effectiveOperation));
        }
        select.append(el('option', {value: UNMAPPED, selected: request.effectiveOperation === UNMAPPED}, 'Unmapped'));
        const line = el('span', {class: 'muted small', role: 'status'});
        select.addEventListener('change', () => during(select, line, 'Saving', () => remap(collection, request, select.value)));
        const takeOff = request.correctedOperation && request.detectedOperation
            ? button('Take the correction off', () => during(takeOff, line, 'Saving', () => remap(collection, request, request.detectedOperation)), {small: true, quiet: true})
            : null;
        return el('tr', null,
            el('td', {title: request.detectionBasis || null}, named(request.name),
                request.scripted || request.testScripted ? [' ', chip('script, not run', {tone: 'neutral', title: 'PAVE never runs a Postman script.'})] : null),
            el('td', null, el('span', {class: 'mono'}, request.method || '')),
            el('td', null, select, ' ', line),
            el('td', null,
                request.correctedOperation ? chip('chosen by hand', {tone: 'neutral'}) : chip('detected', {title: request.detectionBasis || null}),
                takeOff ? [' ', takeOff] : null));
    }

    async function remap(collection, request, operation) {
        clear(status);
        const answer = await call('PUT', '/api/config/collections/' + encodeURIComponent(collection.id) + '/mapping',
            {body: {requestId: request.id, operation}});
        if (!answer.ok) {
            fill(status, errorPanel(answer.error));
            await load();
            return;
        }
        const done = answer.data || {};
        fill(status, el('div', {class: 'verdict verdict--neutral'},
            el('p', {class: 'verdict__headline'}, done.changed ? 'Saved the mapping of "' + named(request.name) + '".'
                : 'Nothing changed: "' + named(request.name) + '" was mapped so already.'),
            sentences(done.notes)));
        await load();
    }

    function confirmRemove(collection) {
        const line = el('span', {class: 'muted small', role: 'status'});
        modal({
            title: 'Remove the collection "' + named(collection.name) + '"?',
            body: el('p', null, 'Its uploaded file is deleted from PAVE, with every mapping chosen by hand for its requests. '
                + 'Operations that use one of its requests fall back to another collection or to application.yml. The file you uploaded it from is not touched.'),
            footer: dialog => {
                const remove = button('Remove the collection', () => during(remove, line, 'Removing', async () => {
                    await removeNow(collection);
                    dialog.close();
                }), {primary: true});
                return [line, button('Keep it', () => dialog.close()), remove];
            }
        });
    }

    async function removeNow(collection) {
        clear(status);
        clear(outcome);
        const answer = await call('DELETE', '/api/config/collections/' + encodeURIComponent(collection.id));
        if (!answer.ok) {
            fill(status, errorPanel(answer.error));
        } else {
            open.delete(collection.id);
            fill(status, el('div', {class: 'verdict verdict--neutral'},
                el('p', {class: 'verdict__headline'}, 'Removed the collection "' + named(answer.data && answer.data.removed || collection.name) + '".'),
                sentences(answer.data && answer.data.warnings)));
        }
        await load();
    }

    const offs = [app.on('config', () => load()), app.on('writes', () => draw())];
    load();

    return {
        update() {},
        unmount() {
            gone = true;
            offs.forEach(off => off());
            page.remove();
        }
    };
}
