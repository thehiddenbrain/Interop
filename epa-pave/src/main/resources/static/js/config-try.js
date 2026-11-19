// "Try a value": how one operation's request would resolve, with values typed for this try.
//
// POST /api/config/resolve answers what a request would be without sending it: the url template
// beside what it resolves to, every variable it needs with the layer its value came from, the
// headers, the body, and whether the guardrail would let it through. A tester types a value for
// any of those variables, or for a {id} part of the url, and sees the effect at once. Nothing is
// sent and nothing is recorded.
//
// A value typed into a field for a secret is sent once and never drawn back: the field is a
// password field, it is emptied as soon as the answer arrives, and the answer itself carries the
// server's mask, not the value.

import * as calls from './config-calls.js';
import {el, fill, clear, button, code, facts, table, heading, copyButton} from './dom.js';
import {clearFieldErrors} from './forms.js';
import {none, flag, sentences, placeholder, sourceWords, refusal, during, requestMarks} from './config-parts.js';

export function createTryPanel(context) {
    let operation = null;      // the operation chosen, by name
    let requestKey = '';       // collectionId + ' ' + requestId, or '' for the one used by default
    let last = null;           // the latest answer, for the fields it names
    const typed = new Map();   // variable name -> value typed, for variables that are not secret
    const typedTokens = new Map();

    const operationSelect = el('select', {id: 'config-try-operation'});
    const requestSelect = el('select', {id: 'config-try-request'});
    const valueFields = el('div', {class: 'config-try__values'});
    const problem = el('div', {class: 'form-problem-slot'});
    const go = el('button', {type: 'submit', class: 'button button--quiet'}, 'Show how it resolves');
    const answerNode = el('div', {class: 'config-try__answer', aria: {live: 'polite'}});
    const form = el('form', {class: 'config-try__form', noValidate: true, attrs: {'aria-label': 'Try values for one operation'}},
        el('div', {class: 'config-try__choose'},
            el('div', {class: 'field', dataset: {field: 'operation'}}, el('label', {htmlFor: 'config-try-operation'}, 'Operation'), operationSelect),
            el('div', {class: 'field', dataset: {field: 'request'}}, el('label', {htmlFor: 'config-try-request'}, 'Request of the collection'), requestSelect)),
        valueFields,
        problem,
        el('div', {class: 'button-row'}, go));

    const node = el('section', {class: 'result__section config-try', id: 'config-try'},
        heading(2, 'Try a value'),
        el('p', {class: 'quiet'}, 'Choose an operation and see how its request resolves: every placeholder, where its value comes from, '
            + 'and what is still missing. Type a value for any of them to see what it would change. Nothing is sent and nothing is recorded.'),
        form,
        answerNode);

    form.addEventListener('submit', event => {
        event.preventDefault();
        during(go, resolveNow);
    });
    operationSelect.addEventListener('change', () => {
        operation = operationSelect.value;
        requestKey = '';
        last = null;
        typed.clear();
        typedTokens.clear();
        drawRequests();
        clear(valueFields);
        clear(answerNode);
        clearFieldErrors(form);
        clear(problem);
    });
    requestSelect.addEventListener('change', () => {
        requestKey = requestSelect.value;
    });

    /** The operations a request is resolved for: those that leave the tool, in the server's order and words. */
    function outboundRows() {
        const effective = context.stored().effective;
        return effective ? (effective.operations || []).filter(row => row.outbound && row.operation !== 'UNMAPPED') : [];
    }

    function draw() {
        const rows = outboundRows();
        const kept = operation;
        operationSelect.replaceChildren(...rows.map(row => el('option', {value: row.operation}, row.label)));
        if (rows.some(row => row.operation === kept)) {
            operationSelect.value = kept;
        } else if (rows.length > 0) {
            operation = rows[0].operation;
            operationSelect.value = operation;
        }
        drawRequests();
    }

    function drawRequests() {
        const row = outboundRows().find(candidate => candidate.operation === operation);
        const requests = row ? row.requests || [] : [];
        const options = [el('option', {value: ''}, requests.length > 0 ? 'The one the operation uses by default' : 'None is mapped: the address comes from application.yml')];
        for (const available of requests) {
            options.push(el('option', {value: available.collectionId + ' ' + available.request.id},
                available.request.name + ' (' + available.collectionName + ')' + (available.usedByDefault ? ', used by default' : '')));
        }
        requestSelect.replaceChildren(...options);
        requestSelect.value = options.some(option => option.value === requestKey) ? requestKey : '';
        requestKey = requestSelect.value;
    }

    /** Opens the panel on one operation and resolves it at once. */
    async function tryOperation(name) {
        if (operation !== name) {
            operationSelect.value = name;
            operationSelect.dispatchEvent(new Event('change'));
        }
        node.scrollIntoView({block: 'start'});
        await during(go, resolveNow);
        operationSelect.focus({preventScroll: true});
    }

    function collectValues() {
        const variables = {};
        const urlValues = {};
        for (const input of valueFields.querySelectorAll('input[data-variable]')) {
            const value = input.value;
            if (value.length > 0) {
                variables[input.dataset.variable] = value;
            }
            if (input.type !== 'password') {
                if (value.length > 0) {
                    typed.set(input.dataset.variable, value);
                } else {
                    typed.delete(input.dataset.variable);
                }
            }
        }
        for (const input of valueFields.querySelectorAll('input[data-token]')) {
            if (input.value.length > 0) {
                urlValues[input.dataset.token] = input.value;
                typedTokens.set(input.dataset.token, input.value);
            } else {
                typedTokens.delete(input.dataset.token);
            }
        }
        return {variables, urlValues};
    }

    async function resolveNow() {
        clearFieldErrors(form);
        clear(problem);
        if (!operation) {
            return;
        }
        const {variables, urlValues} = collectValues();
        const body = {operation};
        if (requestKey) {
            const [collectionId, requestId] = requestKey.split(' ');
            body.request = {collectionId, requestId};
        }
        if (Object.keys(variables).length > 0) {
            body.variables = variables;
        }
        if (Object.keys(urlValues).length > 0) {
            body.urlValues = urlValues;
        }
        const answer = await calls.resolve(body);
        // A secret typed for this try has been sent. It is not kept, here or in the field.
        for (const input of valueFields.querySelectorAll('input[type="password"]')) {
            input.value = '';
        }
        if (answer.kind !== 'ok') {
            fill(problem, refusal(answer, [form]));
            return;
        }
        last = answer.body;
        drawValueFields();
        fill(answerNode, resolvedView(last));
    }

    /** One field for every variable and url token the latest answer named, so a value can be typed for any of them. */
    function drawValueFields() {
        const variables = last.variables || [];
        const tokens = last.urlTokens || [];
        if (variables.length === 0 && tokens.length === 0) {
            fill(valueFields, el('p', {class: 'small quiet'}, 'This request has no placeholders to type a value for.'));
            return;
        }
        fill(valueFields,
            el('p', {class: 'small'}, 'Values for this try. A field left empty takes the value the configuration gives it. '
                + 'A value typed for a secret is sent once and then cleared from its field.'),
            el('div', {class: 'config-try__grid'},
                variables.map(variable => valueField('config-try-v-' + variable.name, variable.name, 'variables.' + variable.name,
                    placeholder(variable.name), variable.secret, typed.get(variable.name), {variable: variable.name})),
                tokens.map(token => valueField('config-try-t-' + token.name, token.name, 'urlValues.' + token.name,
                    [code('{' + token.name + '}'), ' in the url'], false, typedTokens.get(token.name), {token: token.name},
                    token.savedValue ? 'The collection saved ' + token.savedValue + ' here. It is never sent unless it is typed.' : null))));
    }

    function valueField(id, name, field, labelNode, secret, value, dataset, hint) {
        const input = el('input', {type: secret ? 'password' : 'text', id, autocomplete: 'off', spellcheck: false,
            class: 'literal-input', dataset, value: secret ? '' : (value || '')});
        return el('div', {class: 'field', dataset: {field}},
            el('label', {htmlFor: id}, labelNode, secret ? [' ', flag('secret')] : null),
            input,
            hint ? el('span', {class: 'field__hint'}, hint) : null);
    }

    return {node, draw, tryOperation};
}

/** What one operation's request resolves to, as the server answered it. */
function resolvedView(resolved) {
    const url = resolved.url || {};
    const environment = resolved.environment || {};
    const headers = resolved.headers || [];
    const variables = resolved.variables || [];
    const scripts = resolved.scripts || [];
    const bodyText = resolved.body !== undefined ? resolved.body : resolved.bodyTemplate;
    const bodyNode = bodyText !== undefined ? el('pre', {class: 'body-text', tabIndex: 0, aria: {label: 'The request body'}}, bodyText) : null;
    return el('div', {class: 'config-try__resolved'},
        resolved.blocked ? el('div', {class: 'error-panel error-panel--blocked'},
            el('p', {class: 'error-panel__kind'}, 'The production guardrail would refuse this request now'),
            el('p', {class: 'error-panel__title'}, resolved.blocked)) : null,
        resolved.unavailable ? el('div', {class: 'form-problem'},
            el('p', {class: 'form-problem__title'}, 'This cannot be sent as things stand'),
            el('p', null, resolved.unavailable)) : null,
        facts([
            ['Operation', [resolved.label, ' ', resolved.method ? code(resolved.method) : null, resolved.write ? [' ', flag('write')] : null]],
            ['Environment', environment.active ? environment.name + ' (' + environment.kind + ')' : none('none active')],
            ['Url as configured', url.template ? code(url.template) : none('none')],
            ['Url it resolves to', url.display ? code(url.display, 'literal--address')
                : [none('not complete'), resolved.urlShown ? [' So far: ', code(resolved.urlShown, 'literal--address')] : null]],
            ['Where the url comes from', url.source || none('not stated')],
            ['Collection request', resolved.requestName ? [resolved.requestName, requestMarks({scripted: resolved.scripted})] : none('none: the address is not from a collection')]
        ], 'facts--ruled'),
        sentences(resolved.notes),
        variables.length > 0
            ? [el('h3', {class: 'sub-heading'}, 'Every placeholder the request needs'),
                table([{text: 'Placeholder', class: 'col-18'}, {text: 'Value', class: 'col-25'}, {text: 'Comes from', class: 'col-17'}, 'Used in'],
                    variables.map(variable => ({class: variable.resolved ? null : 'config-row--missing', cells: [
                        placeholder(variable.name),
                        variable.resolved
                            ? [code(variable.display), variable.secret ? [' ', flag('secret', 'Masked. The value of a secret is never shown.')] : null]
                            : [el('strong', null, 'No value. '), variable.reason || ''],
                        sourceWords(variable.source),
                        el('span', {class: 'small'}, (variable.usedIn || []).join(', '))]})), {class: 'config-try__variables'})]
            : el('p', null, 'The request has no placeholders.'),
        headers.length > 0
            ? [el('h3', {class: 'sub-heading'}, 'Headers'),
                table([{text: 'Header', class: 'col-18'}, {text: 'As written', class: 'col-30'}, 'As it would be sent'],
                    headers.map(header => [code(header.name), code(header.template),
                        header.resolved ? code(header.display) : [el('strong', null, 'Not complete: '),
                            (header.unresolved || []).map((name, index) => [index > 0 ? ', ' : null, placeholder(name)])]]))]
            : null,
        bodyNode ? el('details', {class: 'fold'},
            el('summary', null, resolved.body !== undefined ? 'The body with its placeholders filled in' : 'The body as the collection holds it',
                (resolved.bodyUnresolved || []).length > 0 ? el('span', {class: 'quiet'}, ' (still missing: ' + resolved.bodyUnresolved.join(', ') + ')') : null),
            el('div', {class: 'raw-body'}, el('div', {class: 'panel__head'}, el('span', {class: 'sub-heading'}, 'Request body'),
                copyButton('Copy request body', () => bodyNode.textContent, bodyNode)), bodyNode)) : null,
        scripts.length > 0 ? el('details', {class: 'fold'},
            el('summary', null, 'Scripts Postman would run, which this tool never runs ', el('span', {class: 'quiet'}, '(' + scripts.length + ')')),
            scripts.map(script => el('div', {class: 'config-script'},
                el('p', {class: 'small'}, code(script.listen), ' from ', script.from),
                el('pre', {class: 'body-text', tabIndex: 0}, script.source)))) : null);
}
