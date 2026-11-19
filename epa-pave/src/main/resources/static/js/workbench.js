// The workbench a protocol tab is built on: the case and what to send on the left, the readout
// on the right, and one way of sending a request and dealing with whatever comes back.
//
// CRD uses it in this pass. DTR and PAS are written by calling it, not by copying the CRD tab:
// a tab adds its own controls, says what to post, and passes in the part of the result that is
// its own. Everything else is the same on every tab and lives here:
//
//   a 2xx, and a 403 or 500 that carries a run   the result view, the ledger indicator, the chain
//   a 422                                        the fields are marked, the earlier result stays
//   a 409                                        the server's sentence, and /api/mode is read again
//   anything else                                the server's sentence in the error panel
//
// The request before it is sent has one shape for every protocol, so its panel is here too.

import {el, fill, clear, button, code, facts, table, heading, copyButton} from './dom.js';
import {clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {renderResult, errorPanel} from './result.js';
import {presenceMark} from './shown.js';
import {resultWord} from './marks.js';

/**
 * Builds the two panes into a tab panel.
 *
 * spec: formLabel and readoutLabel (for assistive technology), onSubmit (called when the form is
 * submitted, by its button or by Enter in a field).
 */
export function createWorkbench(app, panel, spec) {
    let busy = false;
    // The case as it stood when the latest request left. A result on screen was produced for this
    // case, whatever the tester has typed into the form since.
    let sentCase = null;
    const tracked = [];

    const caseSlot = el('div', {class: 'case-slot'});
    const own = el('div', {class: 'case-group case-pane__own'});
    const actions = el('div', {class: 'case-pane__actions'});
    const problem = el('div', {class: 'form-problem-slot'});
    const form = el('form', {class: 'case-pane', noValidate: true, attrs: {'aria-label': spec.formLabel}},
        el('div', {class: 'case-pane__scroll'}, caseSlot, own), actions);

    const status = el('p', {class: 'readout__status', role: 'status', aria: {live: 'polite'}});
    const refusal = el('div', {class: 'readout__refusal', aria: {live: 'polite'}});
    const result = el('div', {class: 'readout__result', aria: {busy: 'false'}});
    // The long parts of the readout are not live regions: a live region is read out whole. The
    // status line above them says what arrived, and that is what is announced.
    const readout = el('div', {class: 'readout', role: 'region', tabIndex: 0, aria: {label: spec.readoutLabel}}, status, refusal, result);

    form.addEventListener('submit', event => {
        event.preventDefault();
        spec.onSubmit();
    });
    form.addEventListener('input', event => {
        // A field that was refused is unmarked as soon as the tester changes it.
        const field = event.target.closest('.field--invalid');
        if (field) {
            field.classList.remove('field--invalid');
            field.querySelectorAll('.field__error').forEach(message => message.remove());
            event.target.removeAttribute('aria-invalid');
        }
    });

    panel.classList.add('tabpanel--workbench');
    fill(panel, el('div', {class: 'workbench'}, form, readout));

    function setBusy(state, control) {
        busy = state;
        for (const each of tracked) {
            each.disabled = state;
        }
        if (control) {
            control.setAttribute('aria-busy', state ? 'true' : 'false');
        }
        result.setAttribute('aria-busy', state ? 'true' : 'false');
    }

    const bench = {
        /** The form, for listening to changes in it. */
        form,
        /** Where the tab puts its own controls, under the shared case. */
        own,
        /** Where the tab puts what belongs with its send button. It stays in view while the case scrolls. */
        actions,
        /** Where the summary of a refused form appears. The tab places it among its actions. */
        problem,

        /** Places the shared case in this tab. Called whenever the tab is shown: there is one case node in the page. */
        showCase() {
            caseSlot.append(app.caseContext.node, app.chain.control);
        },

        /** Controls that cannot be used while a request is on its way. */
        track(...controls) {
            tracked.push(...controls);
        },

        /** A named place in the readout, above the result, for something the tab shows on request. */
        slot(name) {
            const node = el('div', {class: 'readout__' + name});
            result.before(node);
            return node;
        },

        /** Says in one line what the readout now holds. This is the line a screen reader announces. */
        say(text) {
            status.textContent = text;
        },

        /** Shows an error the tab met outside a call made through this workbench. */
        refuse(error) {
            fill(refusal, errorPanel(error));
            status.textContent = error.title;
        },

        /**
         * Makes one call and deals with every answer that is not what was asked for.
         *
         * send: a function that returns the promise of an api call. options.saying: the status line
         * while waiting. options.control: the control that was used, marked busy meanwhile.
         * Returns the answer when it is a completed call or a result, otherwise null: by then the
         * form has been marked or the refusal shown, and there is nothing left for the tab to do.
         */
        async call(send, options) {
            if (busy) {
                return null;
            }
            clearFieldErrors(panel);
            clear(problem);
            clear(refusal);
            setBusy(true, options.control);
            status.textContent = options.saying;
            let answer;
            try {
                // The request is built first: building it moves a code still being typed into the
                // list. The case is read after that, so it is the case that went out.
                const sending = send();
                sentCase = app.caseContext.summary();
                answer = await sending;
            } finally {
                // Whatever happened, the controls come back: a tester is never left with a dead form.
                setBusy(false, options.control);
            }
            if (answer.kind === 'ok' || answer.kind === 'result') {
                return answer;
            }
            status.textContent = answer.body.title;
            if (answer.kind === 'form') {
                // A form problem is marked at the fields. The result of the earlier run stays where it is.
                fill(problem, formProblem(answer.body, markFieldErrors([panel], answer.body.fieldErrors)));
                return null;
            }
            fill(refusal, errorPanel(answer.body),
                // The answer to a problem with the configuration is on the Config tab, so the way there is offered with it.
                answer.body.kind === 'CONFIGURATION'
                    ? el('p', {class: 'readout__way'}, button('Open the Config tab', () => app.navigate('config'))) : null);
            if (answer.kind === 'conflict') {
                // The usual cause is that the configuration changed under this screen. Reading the mode
                // again redraws every tab if it did, and then the tester can send again.
                app.refreshMode();
            }
            return null;
        },

        /**
         * Shows the result of a run and tells the rest of the page about it.
         *
         * options.detail(envelope) returns the protocol's own part; options.detailTitle is its heading.
         */
        showResult(envelope, options) {
            fill(result, renderResult(envelope, {...options, nextStepControl: nextStepControl(app, sentCase)}));
            readout.scrollTop = 0;
            status.textContent = 'Result received. ' + envelope.verdict.headline + ' Test result: ' + resultWord(envelope.expectation.result) + '.';
            // A result has arrived under the configuration now in force, so a notice about the change has done its work.
            app.clearNotices();
            app.ledgerReceipt(envelope.ledger);
            app.chain.onResult(envelope);
        },

        /** True when the result of a run is on screen. */
        hasResult: () => result.querySelector('.result') !== null,

        /** What the readout shows before the first run. */
        showEmpty(node) {
            fill(result, node);
        },

        /**
         * Marks the result on screen as produced before the configuration changed. It stays,
         * because the tester may still be reading it, and it is marked so that it is not taken
         * for an answer from the configuration now in force.
         */
        markStale() {
            const shown = result.querySelector('.result');
            if (shown && !shown.querySelector('.result__stale')) {
                shown.prepend(el('p', {class: 'result__stale'},
                    'This result was produced before the configuration changed. It does not describe the environment now active.'));
            }
        }
    };
    return bench;
}

/**
 * The button a result offers when its next step is something this tool can do from here.
 *
 * forCase is the case the result was produced for. It goes with the hand-over, so the tab that
 * receives it can tell when the case on screen is no longer the case the values belong to.
 */
export function nextStepControl(app, forCase) {
    return (step, envelope) => {
        const goTo = tabId => () => {
            app.carryTo(tabId, {carry: step.carry || {}, fromRunId: envelope.runId, fromProtocol: envelope.protocol, chainId: envelope.chainId, forCase});
            app.navigate(tabId);
        };
        if (step.action === 'START_DTR') {
            return {label: 'Continue in DTR', run: goTo('dtr')};
        }
        if (step.action === 'START_PAS') {
            return {label: 'Continue in PAS', run: goTo('pas')};
        }
        if (step.action === 'OPEN_CONFIG' || step.action === 'UNLOCK_OR_SWITCH') {
            return {label: 'Open the Config tab', run: () => app.navigate('config')};
        }
        // Seam: ANSWER_NEXT_QUESTION, SUBMIT_RESPONSE and CHECK_STATUS belong to the DTR and PAS tabs
        // and get their buttons when those tabs are built. Every other action is something a person
        // does away from this tool, so it has no button.
        return null;
    };
}

/**
 * The request as it would be sent: where it would go, beside the url as configured, the body the
 * tool would build, and what was left out of it. Every protocol answers its preview in this shape.
 */
export function requestPreviewPanel(preview, close) {
    const url = preview.url || {};
    const body = preview.body === undefined || preview.body === null ? null
        : el('pre', {class: 'body-text', tabIndex: 0, aria: {label: 'The request body as it would be sent'}}, preview.body);
    return el('section', {class: 'panel'},
        el('div', {class: 'panel__head'},
            heading(2, 'The request as it would be sent'),
            button('Close', close, {class: 'button--quiet button--small'})),
        el('p', {class: 'quiet'}, 'Nothing was sent and nothing was recorded.'),
        preview.blocked ? el('div', {class: 'error-panel error-panel--blocked'}, el('p', {class: 'error-panel__title'}, preview.blocked)) : null,
        facts([
            ['Operation', [code(preview.operation), preview.method ? [' with ', code(preview.method)] : null]],
            ['Url as configured', url.template ? code(url.template) : presenceMark('EMPTY')],
            ['Url it resolves to', url.display ? code(url.display) : el('span', {class: 'presence presence--none'}, 'none')],
            ['Where the url comes from', url.source || null],
            ['Placeholders with no value', url.unresolved && url.unresolved.length > 0
                ? el('strong', null, url.unresolved.map((name, index) => [index > 0 ? ', ' : null, code('{{' + name + '}}')])) : null],
            ['Environment', preview.environment && preview.environment.active
                ? preview.environment.name + ' (' + preview.environment.kind + ')' : 'none active'],
            ['Collection request', preview.requestName ? [preview.requestName, preview.scripted
                ? el('div', null, el('span', {class: 'flag'}, 'pre-request script'), ' This request carries a script that this tool does not run.') : null] : null],
            ['Member ID', preview.memberId ? (preview.memberId.changed
                ? ['Typed as ', code(preview.memberId.typed), ' and sent as ', code(preview.memberId.sent), '.']
                : ['Sent as typed: ', code(preview.memberId.sent), '.']) : null]
        ], 'facts--ruled'),
        url.variables && url.variables.length > 0
            ? table(['Placeholder', 'Value shown', 'Comes from'], url.variables.map(variable => [
                code('{{' + variable.name + '}}'),
                variable.display === undefined ? presenceMark('ABSENT') : [code(variable.display), variable.secret ? ' (masked: a secret)' : null],
                code(variable.source)]), {class: 'thirds'})
            : null,
        preview.notes && preview.notes.length > 0 ? el('ul', {class: 'note-list'}, preview.notes.map(note => el('li', null, note))) : null,
        body ? el('div', {class: 'raw-body'},
            el('div', {class: 'panel__head'}, el('span', {class: 'sub-heading'}, 'Request body'),
                copyButton('Copy request body', () => body.textContent, body)),
            body) : null);
}
