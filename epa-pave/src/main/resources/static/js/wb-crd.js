// Step 1, the coverage check (plan 3.2): is prior authorization required, and what is needed?
// It posts the case to POST /api/crd with the hook, the prefetch choice, what the tester expects,
// the chain id ("new" on the first run of a case) and the configuration epoch the screen was drawn
// from; on mock only, a chosen scenario. "Preview" asks POST /api/crd/preview with the same body.

import {el, fill, clear} from './dom.js';
import {vocabulary} from './words.js';
import {showPreview} from './wb-preview.js';
import {panelHead, busyLine, sendRun, failurePanel, optionsToggle} from './wb-run.js';
import {createRequestLine} from './wb-request.js';

/** Builds the CRD body from the case and the step's own controls. Only what CRD reads is sent. */
export function crdBody(request, own) {
    const body = {
        hook: own.hook,
        member: request.member,
        memberOverrides: request.memberOverrides,
        provider: request.provider,
        providerOverrides: request.providerOverrides,
        procedureCodes: request.procedureCodes,
        includePrefetch: own.includePrefetch,
        chainId: request.chainId,
        configEpoch: request.configEpoch
    };
    if (request.dateOfService) {
        body.dateOfService = request.dateOfService;
    }
    // The server puts them on the order only where the collection's sample does (D-310); absent, nothing changes.
    if (Array.isArray(request.diagnosisCodes) && request.diagnosisCodes.length > 0) {
        body.diagnosisCodes = request.diagnosisCodes.map(each => (typeof each === 'string' ? {code: each} : each));
    }
    if (own.expected) {
        body.expected = own.expected;
    }
    if (request.testerRef) {
        body.testerRef = request.testerRef;
    }
    if (request.note) {
        body.note = request.note;
    }
    // A scenario is chosen on mock only; the server refuses one on live.
    if (own.mock && own.scenarioId) {
        body.scenarioId = own.scenarioId;
    }
    return body;
}

/** mount(root, ctx), as every step module: {show(), hide(), caseChanged(), unmount()}. ctx also gives crdScenarios(). */
export function mount(root, ctx) {
    const expect = el('select', {class: 'select select--expect', id: 'crd-expect'}, el('option', {value: ''}, 'Not set'));
    const hook = el('select', {class: 'select', id: 'crd-hook', 'aria-label': 'Hook'},
        el('option', {value: 'order-sign'}, 'order-sign'), el('option', {value: 'order-select'}, 'order-select'));
    const prefetch = el('input', {type: 'checkbox', id: 'crd-prefetch', checked: true});
    const scenario = el('select', {class: 'select', id: 'crd-scenario'});
    const scenarioField = el('div', {class: 'field'}, el('label', {class: 'field__label', for: 'crd-scenario'}, 'Mock scenario'), scenario);
    // Which request of the collection the check sends, and a list to choose another (D-313).
    const requestLine = createRequestLine({
        operation: () => operationOfHook(),
        label: 'the coverage check',
        environment: () => (typeof ctx.environmentName === 'function' ? ctx.environmentName() : null)
    });
    const optionsRow = el('div', {class: 'panel__options'}, requestLine.node,
        el('label', {class: 'small', for: 'crd-prefetch'}, prefetch, ' Send prefetch'));
    const toggle = optionsToggle(optionsRow);
    const previewButton = el('button', {type: 'button', class: 'btn', on: {click: () => preview()}}, 'Preview');
    const runButton = el('button', {type: 'button', class: 'btn btn--primary', on: {click: () => run()}}, 'Run check');
    const busy = busyLine();
    const readiness = el('div', {class: 'readiness'});
    // A refusal stands above the result, which stays: a refused send changes nothing of the journey.
    const refusal = el('div', {class: 'step-refusal'});
    const result = el('div', {class: 'step-result'});
    root.append(
        panelHead({title: 'Coverage check', tag: 'CRD', lede: 'Is prior authorization required, and what is needed?', id: 'step-title-1',
            controls: [el('label', {class: 'control-label', for: 'crd-expect'}, 'Expect'), expect, hook, toggle, previewButton, runButton]}),
        requestLine.summary, optionsRow, readiness, busy.node, refusal, result);

    function drawWords(words) {
        const before = expect.value;
        const outcomes = words && words.outcomes && Array.isArray(words.outcomes.CRD) ? words.outcomes.CRD : [];
        fill(expect, el('option', {value: ''}, 'Not set'),
            outcomes.filter(each => each.short).map(each => el('option', {value: each.code}, each.short)));
        expect.value = before;
    }

    /** "Mock scenario" exists on mock only, and only when the server listed scenarios. */
    function drawScenarios() {
        const list = ctx.isMock() ? ctx.crdScenarios() : null;
        const scenarios = list && Array.isArray(list.scenarios) ? list.scenarios : [];
        if (!ctx.isMock() || scenarios.length === 0) {
            scenarioField.remove();
            clear(scenario);
            return;
        }
        const before = scenario.value;
        fill(scenario, el('option', {value: ''}, 'The member ID decides'),
            scenarios.map(each => el('option', {value: each.id}, each.number + '. ' + (each.short || each.label))));
        scenario.value = before;
        if (!scenarioField.isConnected) {
            optionsRow.append(scenarioField);
        }
    }

    function own() {
        return {hook: hook.value, includePrefetch: prefetch.checked, expected: expect.value, mock: ctx.isMock(),
            scenarioId: ctx.isMock() ? scenario.value : ''};
    }

    function operationOfHook() {
        return hook.value === 'order-select' ? 'CRD_ORDER_SELECT' : 'CRD_ORDER_SIGN';
    }

    function drawReadiness() {
        const operation = operationOfHook();
        const blocked = ctx.blocked(operation);
        runButton.disabled = Boolean(blocked);
        runButton.title = blocked || '';
        fill(readiness, blocked ? ctx.readinessLine(blocked) : '');
    }
    hook.addEventListener('change', () => {
        drawReadiness();
        requestLine.redraw();
    });

    async function run() {
        const request = ctx.request();
        await sendRun({
            path: '/api/crd', body: crdBody(request, own()), busy, busyWords: 'Running the check...',
            buttons: [runButton, previewButton], typed: ctx.typedCode(), ctx,
            onResult: envelope => {
                clear(refusal);
                ctx.resulted(envelope);
                draw();
            },
            onRefused: (error, placed) => {
                ctx.refused(1, error);
                fill(refusal, refusedPanel(error, placed));
            },
            onFailed: error => {
                fill(refusal, failurePanel(error));
            }
        });
    }

    /** The panel of a refusal, unless every reason of it is already shown at its field. */
    function refusedPanel(error, placed) {
        const all = (error.fieldErrors || []).every(each => placed.includes(each.field));
        return all && !error.title ? null : failurePanel(error, placed, ctx.labelOf);
    }

    async function preview() {
        await showPreview('/api/crd/preview', crdBody(ctx.request(), own()), error => ctx.placeErrors(error.fieldErrors || []), ctx.labelOf);
    }

    function draw() {
        const latest = ctx.latest(1);
        fill(result, latest ? ctx.card(latest) : '');
    }

    vocabulary().then(drawWords, () => null);
    drawScenarios();
    drawReadiness();
    draw();

    return {
        show() {
            drawScenarios();
            drawReadiness();
            draw();
        },
        hide() {},
        caseChanged() {},
        /** A result read back after a reload is the step's latest: drawn as one that just arrived. */
        restored() {
            clear(refusal);
            draw();
        },
        /** Start a new case: the refusal and the result of the case before are taken away. */
        reset() {
            clear(refusal);
            clear(result);
        },
        unmount() {
            busy.stop();
        },
        /** For the frame: the words or the configuration changed. */
        redraw(words) {
            drawWords(words);
            drawScenarios();
            drawReadiness();
            draw();
        }
    };
}
