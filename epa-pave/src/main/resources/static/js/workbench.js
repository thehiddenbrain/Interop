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
//
// Two parts a tab switches on with one call each, and never copies:
//
//   bench.useOwnRequest({protocol, sendButton, sendLabel, modeChanged})   a choice between the body
//       this tool builds from the form and a request of the tester's own, pasted into a box
//   bench.useRunValues({operation})   fields for placeholders no environment defines, offered
//       when the server refuses a run for want of them
//
// A tab that uses either merges bench.requestFields() into every request it posts, after the case's
// own fields, the preview included.

import {el, fill, clear, button, code, facts, table, heading, copyButton} from './dom.js';
import {clearFieldErrors, markFieldErrors, formProblem, unmark} from './forms.js';
import {renderResult, errorPanel, operationName} from './result.js';
import {presenceMark} from './shown.js';
import {resultWord} from './marks.js';
import {idForms} from './lookup.js';
import {createOwnRequest} from './own-request.js';
import {createRunValues} from './run-values.js';
import {sourceWords} from './config-parts.js';

/**
 * Builds the two panes into a tab panel.
 *
 * spec: formLabel and readoutLabel (for assistive technology), onSubmit (called when the form is
 * submitted by its send button; Enter in a field does not submit it).
 *
 * spec.operations, optional: the operations the tab sends, in the order a case meets them, named
 * as /api/config/effective names them (["CRD_ORDER_SIGN", "CRD_ORDER_SELECT"]), or a function
 * returning that list. When it is given and the server says the first one cannot be sent as things
 * stand, the form is not offered: in its place the tab says what is missing, in the server's
 * words, with a way to the Config tab, and the form comes back as soon as the configuration
 * changes so that it can be sent. Left out, the form is always offered, as before.
 *
 * spec.readinessWays, optional: a function returning nodes the statement offers beside the way to
 * the Config tab, for a part of the tab that needs no service (the PAS tab's notification
 * receiver). A tab that changes which operations it sends calls bench.checkReadiness() after.
 */
export function createWorkbench(app, panel, spec) {
    let busy = false;
    // The case as it stood when the latest request left. A result on screen was produced for this
    // case, whatever the tester has typed into the form since.
    let sentCase = null;
    // A set, so a control tracked twice is disabled once and untracked once.
    const tracked = new Set();

    const caseSlot = el('div', {class: 'case-slot'});
    const own = el('div', {class: 'case-group case-pane__own'});
    // The places the two optional parts go: the choice of body above the case, which it changes the
    // meaning of; the values for one run under the tab's own controls, nearest the send button.
    const modeSlot = el('div', {class: 'mode-slot'});
    const valuesSlot = el('div', {class: 'values-slot'});
    // Said above the case while a request of the tester's own is chosen, so nobody reads the case as what is sent.
    const caseNote = el('p', {class: 'case-note', hidden: true},
        'A request of your own is chosen: the member, provider and codes below are not sent with it, and are not what the ledger records. '
        + 'They may be left empty.');
    let ownRequest = null;
    let runValues = null;
    // That the lookups said something a tester should read before sending. It sits with the send
    // button, which stays in view however far the case above it has been scrolled. It takes one line
    // per lookup there, because the box it sits in is fixed and a laptop screen has little height to
    // spare; the sentences themselves are under the identifier, and its button goes to them.
    const beforeSending = el('div', {class: 'before-sending', hidden: true});
    const actions = el('div', {class: 'case-pane__actions'}, beforeSending);
    const problem = el('div', {class: 'form-problem-slot'});
    const form = el('form', {class: 'case-pane', noValidate: true, attrs: {'aria-label': spec.formLabel}},
        el('div', {class: 'case-pane__scroll'}, modeSlot, caseNote, caseSlot, own, valuesSlot), actions);

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
    // Enter in a field of the form never sends the run. A browser submits a form when Enter is
    // pressed in one of its inputs, and a tester who presses it after typing a member ID or a date
    // expects that field to be done with, not a request sent and recorded. The send button is the
    // one way to send. A field that gives Enter a meaning of its own (an identifier looks itself
    // up, the code picker adds a code) handles the key before it reaches the form.
    form.addEventListener('keydown', event => {
        if (event.key === 'Enter' && event.target instanceof HTMLInputElement) {
            event.preventDefault();
        }
    });
    form.addEventListener('input', event => {
        // A field that was refused is unmarked as soon as the tester changes it.
        const field = event.target.closest('.field--invalid');
        if (field) {
            unmark(field);
        }
    });

    // Said in place of the form when the tab's first operation cannot be sent. Not a live region:
    // the status line says in one sentence that the form is withheld, and that is what is announced.
    const readinessTitle = (panel.id || 'workbench') + '-readiness-title';
    const readiness = el('section', {class: 'readiness', hidden: true, aria: {labelledby: readinessTitle}});
    // The readout, and its status line with it, can be hidden while the form is withheld, so the
    // withholding and the return of the form are announced from a line that is never hidden.
    const readinessStatus = el('p', {class: 'visually-hidden', role: 'status', aria: {live: 'polite'}});
    const workbench = el('div', {class: 'workbench'}, readinessStatus, readiness, form, readout);

    panel.classList.add('tabpanel--workbench');
    fill(panel, workbench);

    function drawBeforeSending() {
        const items = app.caseContext.attention();
        beforeSending.hidden = items.length === 0;
        if (items.length === 0) {
            clear(beforeSending);
            return;
        }
        const byLookup = new Map();
        for (const item of items) {
            if (!byLookup.has(item.about)) {
                byLookup.set(item.about, []);
            }
            byLookup.get(item.about).push(item);
        }
        // Once the case on screen has been sent, the sentences are no longer a step still to take:
        // they are what the lookups said about the case the result is for.
        const sent = result.querySelector('.result') !== null && sentCase !== null && JSON.stringify(app.caseContext.summary()) === JSON.stringify(sentCase);
        fill(beforeSending,
            el('p', {class: 'before-sending__title'}, sent ? 'What the lookups said about the case sent' : 'Check before sending'),
            el('ul', {class: 'plain-list'}, Array.from(byLookup, ([about, said]) => el('li', {class: 'before-sending__line'},
                el('span', {class: 'before-sending__text', title: said.map(item => item.text).join(' ')},
                    about + ': ', said[0].text),
                button(said.length > 1 ? 'Read all ' + said.length : 'Read it', said[0].reveal, {
                    class: 'button--text', aria: {label: 'Read what the ' + about.toLowerCase() + ' said, under its identifier'}
                })))));
    }
    app.caseContext.onChange(drawBeforeSending);

    /** The operations the tab sends, as given, or an empty list when it gave none. */
    function operationsSent() {
        const given = typeof spec.operations === 'function' ? spec.operations() : spec.operations;
        return Array.isArray(given) ? given.filter(name => typeof name === 'string' && name.length > 0) : [];
    }

    /**
     * Whether the form can be offered, from what /api/config/effective says now. Only the server's
     * own sentence withholds it: an operation the server has not described yet, or does not list,
     * keeps the form, and a send then meets whatever the server answers.
     */
    function drawReadiness() {
        const names = operationsSent();
        const first = names.length > 0 ? app.operationRow(names[0]) : null;
        const withheld = Boolean(first && first.outbound !== false && first.unavailable);
        const was = !readiness.hidden;
        // With no result to read, the readout's words about the form would point at nothing, so the
        // statement takes the whole width. A result on screen stays beside it.
        const whole = withheld && !bench.hasResult();
        workbench.classList.toggle('workbench--gated', withheld);
        workbench.classList.toggle('workbench--gated-whole', whole);
        readiness.hidden = !withheld;
        form.hidden = withheld;
        readout.hidden = whole;
        if (!withheld) {
            clear(readiness);
            if (was) {
                readinessStatus.textContent = 'The configuration now lets this tab send. The form is back.';
            }
            return;
        }
        const later = names.slice(1).map(name => app.operationRow(name))
            .filter(row => row && row.outbound !== false && row.unavailable);
        const label = first.label || first.operation;
        fill(readiness,
            el('h2', {class: 'readiness__title', id: readinessTitle}, 'This tab cannot send yet'),
            el('p', {class: 'readiness__lead'}, 'The tool cannot send ', el('strong', null, label),
                ' as it is configured now, so there is no form to fill in. This is what it says is missing:'),
            el('blockquote', {class: 'readiness__missing'}, first.unavailable),
            later.length > 0 ? [
                el('p', null, 'The later steps of this tab are missing something as well:'),
                el('ul', {class: 'readiness__later'}, later.map(row => el('li', null, el('strong', null, row.label || row.operation), ': ', row.unavailable)))
            ] : null,
            el('p', {class: 'readiness__way'}, button('Open the Config tab', () => app.navigate('config'), {class: 'button--primary'})),
            typeof spec.readinessWays === 'function' ? spec.readinessWays() : null,
            el('p', {class: 'quiet'}, 'Upload or choose what is missing there. The form comes back here on its own as soon as the configuration lets this tab send, '
                + 'with no reload.'));
        if (!was) {
            readinessStatus.textContent = 'This tab cannot send yet. ' + first.unavailable;
        }
    }

    // The configuration this workbench starts under. A lookup made under it says so once it has changed.
    app.caseContext.configChanged(app.epoch());

    /** Lets go of the tracked controls inside the result on screen, which is about to be replaced. */
    function forgetResultControls() {
        for (const control of tracked) {
            if (result.contains(control)) {
                tracked.delete(control);
            }
        }
    }

    /** Returns the focus to the control that started a call, when the focus fell to the page meanwhile. */
    function giveFocusBack(control) {
        const now = document.activeElement;
        if (control && control.isConnected && !control.disabled && !control.closest('[hidden]') && (!now || now === document.body)) {
            control.focus({preventScroll: true});
        }
    }

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

        /**
         * Places the shared case in this tab. Called whenever the tab is shown: there is one case
         * node in the page. What the lookups returned goes with it, into the readout, where there
         * is room for a table and a wire view.
         */
        showCase() {
            caseSlot.append(app.caseContext.node, app.chain.control);
            refusal.after(app.caseContext.detail);
            drawBeforeSending();
            drawReadiness();
        },

        /**
         * Controls that cannot be used while a request is on its way. A control drawn inside a
         * result is let go by the workbench when that result is replaced, so a tab may track the
         * controls of each result it draws without them piling up.
         */
        track(...controls) {
            for (const control of controls) {
                if (control) {
                    tracked.add(control);
                }
            }
        },

        /** Stops tracking controls the tab has taken off the page itself. */
        untrack(...controls) {
            for (const control of controls) {
                tracked.delete(control);
            }
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

        /**
         * Takes away a refusal left on screen by an earlier call. A tab calls it before something it
         * does outside bench.call (a comparison), so an old refusal is not read as the new answer's.
         */
        clearRefusal() {
            clear(refusal);
        },

        /** Settles again whether the form is offered, after the tab changed which operations it sends. */
        checkReadiness() {
            drawReadiness();
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
            if (ownRequest && ownRequest.active() && ownRequest.text().trim() === '') {
                // An empty box would be sent as no body at all, and the run built from the form:
                // the opposite of what was chosen. Nothing is asked of the server.
                const nothing = {title: 'There is no request of your own to send. Nothing was sent.',
                    action: 'Paste the request into the box, or choose "Built by this tool from the form".', fieldErrors: []};
                const said = formProblem(nothing, markFieldErrors([ownRequest.field], [{field: 'rawBody', message: 'Paste the request body here.'}]));
                said.classList.add('form-problem--own');
                fill(problem, said);
                status.textContent = nothing.title;
                return null;
            }
            // The control that started the call. Disabling it while the call is out drops the focus
            // to the page, so it is given back when the call is done, unless the tester has moved on.
            const starter = options.control || (document.activeElement instanceof HTMLElement && document.activeElement !== document.body
                ? document.activeElement : null);
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
                // Before anything else is drawn, so a form problem can still move the focus to the
                // field it marks, which is a deliberate move and wins.
                giveFocusBack(starter);
            }
            if (answer.kind === 'ok' || answer.kind === 'result') {
                return answer;
            }
            status.textContent = answer.body.title;
            if (answer.kind === 'form') {
                if (runValues) {
                    // Placeholders with no value get their fields first, so the server's sentence lands at each.
                    await runValues.need(answer.body.fieldErrors || []);
                }
                // A form problem is marked at the fields. The result of the earlier run stays where it is.
                fill(problem, formProblem(answer.body, markFieldErrors([panel], answer.body.fieldErrors)));
                return null;
            }
            fill(refusal, errorPanel(answer.body),
                // The answer to a problem with the configuration is on the Config tab, so the way there
                // is offered with it, unless this workbench is on the Config tab already.
                answer.body.kind === 'CONFIGURATION' && !panel.closest('#panel-config')
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
         * options.nextStepControl(nextStep, envelope), optional, returns {label, run} for a next step
         * the tab itself can take, {node} for a control the tab keeps current itself (it is put in the
         * next-step row as it is), or null; the shared control is used whenever it returns null.
         *
         * The keyboard goes to the verdict of the new result, on every tab, so a keyboard or screen
         * reader user carries on from where the answer starts rather than from the top of the page.
         * A tab that puts it somewhere more useful afterwards (the next question) moves it again.
         */
        showResult(envelope, options) {
            const settings = options || {};
            // A tab's own control for the next step (answering the next question, sending the
            // response, checking a status) is asked first; the shared one covers the rest.
            const shared = nextStepControl(app, sentCase);
            const own = typeof settings.nextStepControl === 'function' ? settings.nextStepControl : null;
            forgetResultControls();
            // A body of the tester's own is said under the verdict by the result view itself, on every protocol.
            fill(result, renderResult(envelope, {...settings,
                nextStepControl: (step, shownEnvelope) => (own ? own(step, shownEnvelope) : null) || shared(step, shownEnvelope)}));
            drawBeforeSending();
            readout.scrollTop = 0;
            const verdict = result.querySelector('.verdict__headline');
            if (verdict) {
                verdict.tabIndex = -1;
                verdict.focus({preventScroll: true});
            }
            // The line announced is short: a headline worded into a paragraph is read in the strip, and
            // here the server's short label of the outcome stands for it.
            const headline = String(envelope.verdict.headline || '');
            const said = headline.length > 120 && envelope.verdict.outcomeLabel ? envelope.verdict.outcomeLabel : headline;
            status.textContent = 'Result received. ' + said + (/[.!?]$/.test(said) ? '' : '.') + ' Test result: ' + resultWord(envelope.expectation.result) + '.';
            // A result has arrived under the configuration now in force, so a notice about the change has done its work.
            app.clearNotices();
            app.ledgerReceipt(envelope.ledger);
            app.chain.onResult(envelope);
        },

        /**
         * Switches on the choice of "A request of my own". spec: protocol ("CRD"), sendButton (the
         * tab's send button, whose words change while the pasted text is not valid JSON), sendLabel
         * (its usual words, "Send CRD request"), modeChanged (optional: called when the tester
         * switches between the two, so the tab can word what it says before the first run for the
         * one chosen).
         */
        useOwnRequest(ownSpec) {
            ownRequest = createOwnRequest({
                protocol: ownSpec.protocol, sendButton: ownSpec.sendButton, sendLabel: ownSpec.sendLabel,
                isMock: () => app.isMock(),
                changed: () => {
                    caseNote.hidden = !ownRequest.active();
                    // The refusal of an empty box is out of date as soon as the box or the choice changes.
                    problem.querySelectorAll('.form-problem--own').forEach(node => node.remove());
                },
                modeChanged: () => {
                    if (ownSpec.modeChanged) {
                        ownSpec.modeChanged();
                    }
                }
            });
            modeSlot.append(ownRequest.node);
            return ownRequest;
        },

        /**
         * Switches on values for one run. spec.operation() names the operation about to be sent, as
         * /api/config/resolve takes it ("crd-order-sign"), so each field can say where it is used.
         */
        useRunValues(valuesSpec) {
            runValues = createRunValues({
                operation: valuesSpec.operation,
                ownText: () => (ownRequest && ownRequest.active() ? ownRequest.text() : null),
                navigate: id => app.navigate(id),
                changed: () => {}
            });
            valuesSlot.append(runValues.node);
            return runValues;
        },

        /**
         * What the two optional parts add to a run request: rawBody while a request of the
         * tester's own is chosen, variables while values for this run are typed. Empty otherwise.
         *
         * While a body of the tester's own is chosen, the case's member, provider and codes are
         * taken out of the request: the body is what goes, so the form's copies would only steer
         * the mock scenario and fill the ledger with a member the body may not be about. Their
         * properties are set to undefined, which a JSON body leaves out, so a tab merging this
         * after the case's own fields sends none of them.
         */
        requestFields() {
            const out = {};
            if (ownRequest && ownRequest.active()) {
                Object.assign(out, {member: undefined, memberOverrides: undefined, provider: undefined, providerOverrides: undefined,
                    procedureCodes: undefined});
            }
            if (ownRequest) {
                Object.assign(out, ownRequest.requestFields());
            }
            if (runValues && runValues.any()) {
                out.variables = runValues.values();
            }
            return out;
        },

        /** True while a request of the tester's own is chosen. */
        ownRequestActive: () => Boolean(ownRequest && ownRequest.active()),

        /** True when the result of a run is on screen. */
        hasResult: () => result.querySelector('.result') !== null,

        /** What the readout shows before the first run. */
        showEmpty(node) {
            forgetResultControls();
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
            // A lookup on screen was answered under the configuration that is gone, and says so too.
            app.caseContext.configChanged(app.epoch());
            if (ownRequest) {
                ownRequest.refresh();
            }
        }
    };
    if (spec.operations) {
        // The frame reads the operations again on every change of the configuration, then says so.
        app.whenConfigChanges(drawReadiness);
        drawReadiness();
    }
    return bench;
}

// The tab each protocol's results are drawn on.
const TAB_OF_PROTOCOL = {CRD: 'crd', DTR: 'dtr', PAS: 'pas', SUBSCRIPTION: 'pas'};

/**
 * The button a result offers when its next step is something this tool can do from here.
 *
 * forCase is the case the result was produced for. It goes with the hand-over, so the tab that
 * receives it can tell when the case on screen is no longer the case the values belong to.
 */
export function nextStepControl(app, forCase) {
    return (step, envelope) => {
        // A result never offers to carry itself into the tab it is on: that would send the same
        // request again under the tab's own name, the resubmission a next step may warn against.
        // The tab offers its own control for that step, or none.
        const here = TAB_OF_PROTOCOL[envelope.protocol] || null;
        const goTo = tabId => () => {
            app.carryTo(tabId, {carry: step.carry || {}, carryLabels: step.carryLabels || {}, fromRunId: envelope.runId, fromProtocol: envelope.protocol, chainId: envelope.chainId, forCase});
            app.navigate(tabId);
        };
        if (step.action === 'START_DTR' && here !== 'dtr') {
            return {label: 'Continue in DTR', run: goTo('dtr')};
        }
        if (step.action === 'START_PAS' && here !== 'pas') {
            return {label: 'Continue in PAS', run: goTo('pas')};
        }
        if (step.action === 'OPEN_CONFIG' || step.action === 'UNLOCK_OR_SWITCH') {
            return {label: 'Open the Config tab', run: () => app.navigate('config')};
        }
        // ANSWER_NEXT_QUESTION, SUBMIT_RESPONSE and CHECK_STATUS are taken on the DTR and PAS tabs,
        // which pass their own control to bench.showResult as options.nextStepControl. Every other
        // action is something a person does away from this tool, so it has no button.
        return null;
    };
}

/**
 * A placeholder written the way the url as configured writes it. A collection writes most of them
 * in double braces and the single-brace parts of a lookup url in single ones; the tester compares
 * this with the template above it, so it must not change the form.
 */
function placeholderAsWritten(name, template) {
    const doubled = '{{' + name + '}}';
    const single = '{' + name + '}';
    return code(typeof template === 'string' && !template.includes(doubled) && template.includes(single) ? single : doubled);
}

/**
 * The request as it would be sent: where it would go, beside the url as configured, the body the
 * tool would build, and what was left out of it. Every protocol answers its preview in this shape.
 * Every name the server sends as a code (the operation, where a value came from) is given in words.
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
            ['Operation', operationName(preview.operation)],
            ['Method', preview.method ? code(preview.method) : null],
            // The server sends an empty template when nothing is configured: that is said, not drawn as an empty value of a response.
            ['Url as configured', url.template ? code(url.template) : el('span', {class: 'presence presence--none'}, 'nothing configured')],
            ['Url it resolves to', url.display ? code(url.display) : el('span', {class: 'presence presence--none'}, 'none')],
            ['Where the url comes from', url.source || null],
            ['Placeholders with no value', url.unresolved && url.unresolved.length > 0
                ? el('strong', null, url.unresolved.map((name, index) => [index > 0 ? ', ' : null, placeholderAsWritten(name, url.template)])) : null],
            ['Environment', preview.environment && preview.environment.active
                ? preview.environment.name + ' (' + preview.environment.kind + ')' : 'none active'],
            ['Collection request', preview.requestName ? [preview.requestName, preview.scripted
                ? el('div', null, el('span', {class: 'flag'}, 'pre-request script'), ' This request carries a script that this tool does not run.') : null] : null],
            ['Member ID', preview.memberId ? idForms(preview.memberId) : null]
        ], 'facts--ruled'),
        url.variables && url.variables.length > 0
            ? table(['Placeholder', 'Value shown', 'Comes from'], url.variables.map(variable => [
                placeholderAsWritten(variable.name, url.template),
                variable.display === undefined ? presenceMark('ABSENT') : [code(variable.display), variable.secret ? ' (masked: a secret)' : null],
                sourceWords(variable)]), {class: 'thirds'})
            : null,
        preview.notes && preview.notes.length > 0 ? el('ul', {class: 'note-list'}, preview.notes.map(note => el('li', null, note))) : null,
        body ? el('div', {class: 'raw-body'},
            el('div', {class: 'panel__head'}, el('span', {class: 'sub-heading'}, 'Request body'),
                copyButton('Copy request body', () => body.textContent, body)),
            body) : null);
}

/**
 * The member ID cell of a mock scenario's row on an empty tab: the first member ID that selects
 * it, then every other identifier the server lists for it (sentinelMemberIds, sentinelNpis and,
 * on DTR, the CRD member IDs it also answers for), so a tester can reach it every way it can be
 * reached. Each list is the server's; none is worked out here.
 */
export function sentinelCell(scenario) {
    const first = scenario.sentinelMemberId || null;
    const others = (scenario.sentinelMemberIds || []).filter(id => id !== first);
    const npis = scenario.sentinelNpis || [];
    const fromCrd = scenario.crdSentinelMemberIds || [];
    const list = values => values.map((value, index) => [index > 0 ? ', ' : null, code(value)]);
    return [
        first ? code(first) : el('span', {class: 'presence presence--none'}, 'none'),
        others.length > 0 ? el('div', {class: 'small'}, 'or ', list(others)) : null,
        npis.length > 0 ? el('div', {class: 'small'}, 'or the NPI ', list(npis)) : null,
        fromCrd.length > 0 ? el('div', {class: 'small'}, 'and the CRD member ', fromCrd.length === 1 ? 'ID ' : 'IDs ', list(fromCrd), ', for a journey begun at CRD') : null
    ];
}
