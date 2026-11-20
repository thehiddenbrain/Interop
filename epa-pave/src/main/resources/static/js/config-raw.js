// "Send a request as written": any request of the vendor's collection, sent exactly as the vendor
// wrote it, with its placeholders filled in, before any of the tool's own mapping is involved.
//
// It is built on the workbench every protocol tab uses, so sending, the request before it is
// sent, a refused form, a blocked write, a changed configuration and the result itself are dealt
// with in the one shared way. POST /api/raw answers the shared run envelope, so the result is the
// shared result view; the guardrail judges the request by its method, name and url, and the
// ledger records the run like any other.
//
// The placeholders the request needs are listed from /api/config/resolve, which says where each
// value would come from. A value typed for a secret is sent and then cleared from its field.

import * as calls from './config-calls.js';
import {el, fill, clear, button, code, facts} from './dom.js';
import {textField} from './forms.js';
import {createWorkbench, requestPreviewPanel} from './workbench.js';
import {issuesTable} from './result.js';
import {none, flag, sentences, placeholder, sourceWords, requestMarks} from './config-parts.js';

export function createRawView(context) {
    const app = context.app;
    let chosen = '';            // collectionId + ' ' + requestId
    let loadedFor = '';         // the request whose sample body is in the field
    let reading = 0;

    const host = el('div', {class: 'config-raw'});
    const bench = createWorkbench(app, host, {
        formLabel: 'The collection request to send as written',
        readoutLabel: 'The readout: the request before it is sent, and the result',
        onSubmit: send
    });

    const requestSelect = el('select', {id: 'config-raw-request'});
    const about = el('div', {class: 'config-raw__about'});
    const values = el('div', {class: 'config-raw__values'});
    const body = el('textarea', {id: 'config-raw-body', class: 'config-raw__body', rows: 14, spellcheck: false,
        aria: {describedby: 'config-raw-body-hint'}});
    const reload = button('Load the sample body again', () => loadBody(true), {class: 'button--text'});
    const testerRef = textField({label: 'Tester reference', field: 'testerRef', placeholder: 'ticket or initials'});
    const note = textField({label: 'Note', field: 'note'});
    const sendButton = el('button', {type: 'submit', class: 'button button--primary'}, 'Send this request as written');
    const previewButton = button('Show the request before sending', preview);
    const previewSlot = bench.slot('preview');
    bench.track(sendButton, previewButton, requestSelect);

    // The summary of a refused form sits in the scrolling form, above the fields it marks, not in
    // the strip of buttons below it: a laptop screen leaves that strip too little room to show both.
    bench.own.append(
        bench.problem,
        el('h2', {class: 'case-legend'}, 'A request of the collection'),
        el('p', {class: 'small'}, 'Sent with its own method, url and headers, with the placeholders filled in. '
            + 'The tool does not interpret the answer: it shows everything that came back and records the run.'),
        el('div', {class: 'field', dataset: {field: 'request'}}, el('label', {htmlFor: 'config-raw-request'}, 'Request'), requestSelect),
        about,
        values,
        el('div', {class: 'field', dataset: {field: 'rawBody'}},
            el('label', {htmlFor: 'config-raw-body'}, 'Body to send'),
            body,
            el('span', {class: 'field__hint', id: 'config-raw-body-hint'}, 'The sample body of the collection, as the vendor wrote it. Edit it freely. '
                + 'Placeholders in it are filled in when it is sent. Left empty, the body saved with the collection request goes, if it has one: '
                + 'the request before sending says which.'),
            el('p', null, reload)),
        el('div', {class: 'field-grid'}, testerRef.node, note.node));
    bench.actions.append(el('div', {class: 'button-row'}, sendButton, previewButton));

    requestSelect.addEventListener('change', () => choose(requestSelect.value));

    function entries() {
        const collections = (context.stored().collections || {}).collections || [];
        const all = [];
        for (const collection of collections) {
            for (const request of collection.requests || []) {
                all.push({collection, request, key: collection.id + ' ' + request.id});
            }
        }
        return all;
    }

    function current() {
        return entries().find(entry => entry.key === chosen) || null;
    }

    function draw() {
        const collections = (context.stored().collections || {}).collections || [];
        requestSelect.replaceChildren(el('option', {value: ''}, collections.length === 0 ? 'No collection is uploaded' : 'Choose a request'));
        for (const collection of collections) {
            const group = el('optgroup', {label: collection.name});
            for (const request of collection.requests || []) {
                group.append(el('option', {value: collection.id + ' ' + request.id},
                    request.name + ' (' + request.method + (request.effectiveOperation === 'UNMAPPED' ? ', not mapped' : ', ' + request.effectiveLabel) + ')'));
            }
            requestSelect.append(group);
        }
        if (chosen && !current()) {
            chosen = '';
            loadedFor = '';
            body.value = '';
            clear(values);
        }
        requestSelect.value = chosen;
        drawAbout();
        drawEmpty();
    }

    /**
     * What to do next, while the readout holds no result. Before a choice it asks for one; after it,
     * it says what the two buttons do. While the request is shown before sending it says nothing,
     * since the readout already holds what the tester asked for.
     */
    function drawEmpty() {
        if (bench.hasResult()) {
            return;
        }
        if (previewSlot.childElementCount > 0) {
            bench.showEmpty(null);
            return;
        }
        const collections = (context.stored().collections || {}).collections || [];
        bench.showEmpty(chosen
            ? el('div', {class: 'empty-state'},
                el('p', null, 'Send it with "Send this request as written", or look at it first with "Show the request before sending". '
                    + 'What comes back appears here.'))
            : emptyState(collections.length));
    }

    function emptyState(collectionCount) {
        if (collectionCount === 0) {
            return el('div', {class: 'empty-state'},
                el('p', {class: 'empty-state__lead'}, 'Upload the vendor\'s collection first.'),
                el('p', null, 'Every request of the collection can be sent from here exactly as it was written.'),
                el('p', null, button('Upload a collection', () => context.go('collections'), {class: 'button--primary'})));
        }
        return el('div', {class: 'empty-state'},
            el('p', {class: 'empty-state__lead'}, 'Choose a request of the collection.'),
            el('p', null, 'Its sample body is loaded into the form. Look at the request before sending with "Show the request before sending", '
                + 'then send it. The result appears here, in the same form as every other run.'));
    }

    function drawAbout() {
        const entry = current();
        if (!entry) {
            clear(about);
            return;
        }
        const request = entry.request;
        fill(about, facts([
            ['Method and url', [code(request.method), ' ', code(request.urlTemplate)]],
            ['Mapped to', request.effectiveOperation === 'UNMAPPED' ? 'no operation' : request.effectiveLabel],
            ['Marks', requestMarks(request)]
        ], 'facts--ruled config-raw__facts'));
    }

    async function choose(key) {
        chosen = key;
        clear(previewSlot);
        drawAbout();
        drawEmpty();
        clear(values);
        if (!key) {
            body.value = '';
            loadedFor = '';
            return;
        }
        await Promise.all([loadBody(false), listValues()]);
    }

    /** Puts the sample body of the chosen request in the field, unless the tester is already editing this request's body. */
    async function loadBody(again) {
        const entry = current();
        if (!entry || (!again && loadedFor === chosen)) {
            return;
        }
        const turn = ++reading;
        const answer = await calls.requestInFull(entry.collection.id, entry.request.id);
        if (turn !== reading) {
            return;
        }
        if (answer.kind !== 'ok') {
            bench.refuse(answer.body);
            return;
        }
        body.value = answer.body.body !== undefined ? answer.body.body : '';
        loadedFor = chosen;
    }

    /** One field for every placeholder the request needs, with where its value comes from now. */
    async function listValues() {
        const entry = current();
        if (!entry) {
            return;
        }
        const request = entry.request;
        const answer = await calls.resolve({operation: request.effectiveOperation,
            request: {collectionId: entry.collection.id, requestId: request.id}});
        if (entry.key !== chosen) {
            return;
        }
        if (answer.kind !== 'ok') {
            fill(values, el('p', {class: 'small'}, el('strong', null, 'The placeholders of this request could not be listed. '), answer.body.title,
                ' Placeholders it needs are still named if sending is refused.'));
            return;
        }
        const variables = answer.body.variables || [];
        // A {id} or :id part of the url, filled for each call. The server takes a value for each
        // under urlValues, and refuses to send or show the request while one has none.
        const tokens = (answer.body.urlTokens || []).filter(token => !token.supplied);
        const parts = tokens.length > 0
            ? [el('p', {class: 'case-legend'}, 'Parts of the url'),
                el('p', {class: 'small'}, 'The url has a part filled for each call. Type the value it is waiting for.'),
                sentences(answer.body.notes, 'small'),
                tokens.map(token => tokenField(token))]
            : null;
        if (variables.length === 0) {
            fill(values, parts, el('p', {class: 'small quiet'}, 'The url and headers of this request have no placeholders.'));
            return;
        }
        fill(values,
            parts,
            el('p', {class: 'case-legend'}, 'Placeholders'),
            el('p', {class: 'small'}, 'Each takes the value the configuration gives it. Type a value to use another one for this send only.'),
            variables.map(variable => valueField(variable)));
    }

    function tokenField(token) {
        const id = 'config-raw-t-' + token.name.replace(/[^A-Za-z0-9_-]/g, '_');
        const input = el('input', {type: 'text', id, autocomplete: 'off', spellcheck: false, class: 'literal-input', dataset: {token: token.name}});
        return el('div', {class: 'field config-raw__missing', dataset: {field: 'urlValues.' + token.name}},
            el('label', {htmlFor: id}, code('{' + token.name + '}')), input);
    }

    function valueField(variable) {
        const id = 'config-raw-v-' + variable.name.replace(/[^A-Za-z0-9_-]/g, '_');
        const input = el('input', {type: variable.secret ? 'password' : 'text', id, autocomplete: 'off', spellcheck: false,
            class: 'literal-input', dataset: {variable: variable.name}});
        return el('div', {class: 'field' + (variable.resolved ? '' : ' config-raw__missing'), dataset: {field: 'variables.' + variable.name}},
            el('label', {htmlFor: id}, placeholder(variable.name), variable.secret ? [' ', flag('secret')] : null),
            input,
            el('span', {class: 'field__hint'}, variable.resolved
                ? ['Now: ', code(variable.display), ', from ', sourceWords(variable.source), '.']
                : [el('strong', null, 'No value. '), variable.reason || '']));
    }

    function payload() {
        const entry = current();
        const variables = {};
        for (const input of values.querySelectorAll('input[data-variable]')) {
            if (input.value.length > 0) {
                variables[input.dataset.variable] = input.value;
            }
        }
        const urlValues = {};
        for (const input of values.querySelectorAll('input[data-token]')) {
            if (input.value.length > 0) {
                urlValues[input.dataset.token] = input.value;
            }
        }
        const sent = {
            request: entry ? {collectionId: entry.collection.id, requestId: entry.request.id} : null,
            rawBody: body.value,
            variables,
            configEpoch: app.epoch()
        };
        if (Object.keys(urlValues).length > 0) {
            sent.urlValues = urlValues;
        }
        if (testerRef.value()) {
            sent.testerRef = testerRef.value();
        }
        if (note.value()) {
            sent.note = note.value();
        }
        return sent;
    }

    /** A secret typed for one send has gone. It is not kept in its field. */
    function forgetSecrets() {
        for (const input of values.querySelectorAll('input[type="password"]')) {
            input.value = '';
        }
    }

    async function preview() {
        const sent = payload();
        const answer = await bench.call(() => calls.previewAsWritten(sent), {saying: 'Reading the request before it is sent.', control: previewButton});
        forgetSecrets();
        if (!answer) {
            focusRefusedField();
            return;
        }
        fill(previewSlot, requestPreviewPanel(answer.body, () => {
            clear(previewSlot);
            drawEmpty();
            previewButton.focus();
        }));
        drawEmpty();
        bench.say('The request as it would be sent is shown. Nothing was sent.');
        focusHeading(previewSlot, 'h2');
    }

    async function send() {
        const sent = payload();
        const answer = await bench.call(() => calls.sendAsWritten(sent), {saying: 'Sending the request as written.', control: sendButton});
        forgetSecrets();
        if (!answer) {
            focusRefusedField();
            return;
        }
        clear(previewSlot);
        bench.showResult(answer.body, {detail: rawDetail, detailTitle: 'What came back',
            // On this tab the shared "Open the Config tab" would lead nowhere: the place that answers a
            // refusal about the configuration or about locked writes is the view "In force now".
            nextStepControl: step => (step.action === 'OPEN_CONFIG' || step.action === 'UNLOCK_OR_SWITCH'
                ? {label: 'Go to "In force now"', run: () => context.go('inforce')} : null)});
        // The keyboard goes where the answer is, as it would on any page that changed under it.
        focusHeading(host, '.readout__result .verdict__headline');
    }

    /** A refused form: the first field it marks is brought into view and given the focus. */
    function focusRefusedField() {
        const marked = host.querySelector('.case-pane .field--invalid');
        if (!marked) {
            return;
        }
        const control = marked.querySelector('input, select, textarea');
        marked.scrollIntoView({block: 'center'});
        if (control) {
            control.focus({preventScroll: true});
        }
    }

    function focusHeading(root, selector) {
        const target = root.querySelector(selector);
        if (target) {
            target.tabIndex = -1;
            target.focus();
        }
    }

    /**
     * The configuration changed: another environment is active, or something was uploaded. What
     * this view drew from the earlier one is read again, so no field says where a value comes
     * from under a configuration that is no longer in force.
     */
    function configChanged() {
        clear(previewSlot);
        drawEmpty();
        if (chosen) {
            listValues();
        }
        bench.markStale();
        if (chosen) {
            bench.say('The configuration changed. The placeholders of the chosen request were read again.');
        }
    }

    function show(argument) {
        if (argument) {
            const [collectionId, requestId] = argument.split('/');
            const key = collectionId + ' ' + requestId;
            if (entries().some(entry => entry.key === key) && key !== chosen) {
                requestSelect.value = key;
                choose(key);
            }
        }
    }

    function address() {
        return null;
    }

    return {node: host, show, draw, configChanged, address, bench: true};
}

/** The raw detail: which request went, what kind of answer came back, and the issues of an OperationOutcome. */
function rawDetail(envelope) {
    const detail = envelope.detail || {};
    const issues = detail.issues || [];
    return el('div', {class: 'detail'},
        facts([
            ['Request sent', detail.requestName || none('not stated')],
            ['What came back', detail.responseKind ? code(detail.responseKind) : none('not stated')]
        ], 'facts--ruled'),
        issues.length > 0 ? el('div', null, el('p', {class: 'sub-heading'}, 'Issues the service reported (' + issues.length + ')'), issuesTable(issues)) : null,
        (detail.requestNotes || []).length > 0 ? el('ul', {class: 'note-list'}, detail.requestNotes.map(text => el('li', null, text))) : null);
}
