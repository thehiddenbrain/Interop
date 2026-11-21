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

import * as api from './api.js';
import {el, fill, clear, button, code, facts, table, heading, copyButton, when} from './dom.js';
import {clearFieldErrors, markFieldErrors, formProblem, showFormProblem, refusedSaying, unmark, fieldLabel, mirrorMarks, UNMARKED, MARKED} from './forms.js';
import {renderResult, errorPanel, operationName} from './result.js';
import {presenceMark} from './shown.js';
import {resultWord} from './marks.js';
import {idForms} from './lookup.js';
import {createOwnRequest} from './own-request.js';
import {createRunValues} from './run-values.js';
import {sourceWords} from './config-parts.js';
import {runLink} from './history-common.js';

/**
 * The text a send is refused for, or empty when it may go: what was typed in the procedure code box
 * and not added, when the call sends the case. Nothing the tester did not see as part of the case is
 * ever sent, so such text is not sent quietly, and not dropped quietly either. A call that sends
 * nothing of the case is not held up by a box it does not read: a subscription call, a request of
 * the tester's own, any call made while the case is put out of the way.
 *
 * facts: typed (the text in the box), caseShown (the case is on this tab and not hidden),
 * ownRequest (a request of the tester's own is chosen, which carries nothing of the case), and
 * sendsCase (what the tab said of this call: false when it carries nothing of the case; left out,
 * the case is sent whenever it is shown and no request of the tester's own is chosen).
 */
export function textLeftToRefuse(facts) {
    const typed = typeof facts.typed === 'string' ? facts.typed.trim() : '';
    const sendsCase = facts.sendsCase === false ? false : facts.caseShown === true && facts.ownRequest !== true;
    return sendsCase ? typed : '';
}

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
 *
 * spec.moreOptions, optional: true to put the tab's own optional fields (bench.own) and the chain
 * control in one closed "More options" fold under the case, whose summary says what in it is not
 * at its default. The fields a call needs go in bench.beforeOptions, which is never folded. Left
 * out, bench.own is open under the case, as the Config tab's request sent as written wants it.
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
    // The fields of the call itself that a tester must see or fill, between the case and the fold.
    const beforeOptions = el('div', {class: 'before-options'});
    // What in the fold is not at its default, so a closed fold never hides that something is set.
    const optionsSaid = el('span', {class: 'quiet more-options__said'});
    const moreOptions = el('details', {class: 'fold more-options'}, el('summary', null, 'More options', optionsSaid));
    const folded = spec.moreOptions === true;
    const firstSeen = new WeakMap();
    if (folded) {
        moreOptions.append(own);
        moreOptions.addEventListener('change', drawOptionsSaid);
        moreOptions.addEventListener('input', drawOptionsSaid);
    }
    const scroll = el('div', {class: 'case-pane__scroll'}, modeSlot, caseNote, caseSlot, beforeOptions,
        folded ? [valuesSlot, moreOptions] : [own, valuesSlot]);
    const form = el('form', {class: 'case-pane', noValidate: true, attrs: {'aria-label': spec.formLabel}}, scroll, actions);

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
    // However a field loses or gains a mark (typed in, filled by a lookup or a case, checked again
    // on leaving it), the summary lists the marks as they are now, so it never names a field that
    // was corrected nor leaves out one that is still wrong.
    panel.addEventListener(UNMARKED, () => mirrorMarks(problem, panel));
    panel.addEventListener(MARKED, () => mirrorMarks(problem, panel));

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

    /**
     * Says on the closed fold's summary what in it is set: a text field that holds something, a
     * box ticked or cleared against its default, a choice other than the first. Read from the
     * fields themselves, so a field added to the fold is said without a word more here. A box
     * built ticked carries no default the page can read, so the state each box is first seen in,
     * when the tab is first shown and nothing has been touched, is taken as its default.
     */
    function drawOptionsSaid() {
        const said = [];
        for (const control of moreOptions.querySelectorAll('input, select, textarea')) {
            if (control.closest('[hidden]')) {
                continue;
            }
            const field = control.closest('[data-field], .field, .case-group');
            const named = control.type === 'checkbox' || control.type === 'radio'
                ? (control.closest('label') ? control.closest('label').textContent.trim() : control.value)
                : fieldLabel(field, control.name);
            if (!firstSeen.has(control)) {
                firstSeen.set(control, control.checked);
            }
            if (control.type === 'checkbox') {
                if (control.checked !== firstSeen.get(control)) {
                    said.push(named + (control.checked ? '' : ' off'));
                }
            } else if (control.type === 'radio') {
                if (control.checked && !firstSeen.get(control)) {
                    said.push(named);
                }
            } else if (control.tagName === 'SELECT') {
                if (control.selectedIndex > 0) {
                    const number = /^(\d+)\./.exec(control.options[control.selectedIndex].text);
                    said.push(control.name === 'scenarioId' && number ? 'mock scenario ' + number[1] + ' chosen' : named);
                }
            } else if (control.value.trim() !== '') {
                said.push(named);
            }
        }
        optionsSaid.textContent = said.length === 0 ? '' : said.join(', ');
    }

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
            // One plain sentence first, true whatever the reason; the reason itself stays the server's, below.
            el('p', {class: 'readiness__plain'}, 'This step is missing something the tool needs before it can send. '
                + 'Upload the vendor\'s Postman environment and collection on the Config tab if you have not.'),
            el('p', {class: 'readiness__way'}, button('Open the Config tab', () => app.navigate('config'), {class: 'button--primary'})),
            el('p', {class: 'sub-heading'}, 'What the tool says is missing'),
            el('p', {class: 'readiness__lead'}, 'The tool cannot send ', el('strong', null, label),
                ' as it is configured now, so there is no form to fill in. This is what it says is missing:'),
            el('blockquote', {class: 'readiness__missing'}, first.unavailable),
            later.length > 0 ? [
                el('p', null, 'The later steps of this tab are missing something as well:'),
                el('ul', {class: 'readiness__later'}, later.map(row => el('li', null, el('strong', null, row.label || row.operation), ': ', row.unavailable)))
            ] : null,
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

    /**
     * What the screen says of the procedure code box for one call: the text typed there and not
     * added, whether the case is shown on this tab, whether a request of the tester's own is chosen,
     * and what the tab said of the call. The case is one, moved to whichever protocol tab is open,
     * so on any other tab, the Config tab among them, it is not shown here.
     */
    function codeBoxFacts(options) {
        const node = app.caseContext.node;
        const shown = panel.contains(node) && node.closest('[hidden]') === null;
        const box = shown ? node.querySelector('[data-field="procedureCodes"] input[role="combobox"]') : null;
        return {typed: box ? box.value : '', caseShown: shown, ownRequest: Boolean(ownRequest && ownRequest.active()), sendsCase: options.sendsCase};
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

    // The read timeout of a call to the service, in seconds, as /api/config/effective states it, or
    // null when it is not known. Read again on every change of the configuration; never guessed.
    let readTimeout = null;
    async function readTimeoutSetting() {
        const answer = await api.get('/api/config/effective');
        const settings = answer.kind === 'ok' && answer.body && Array.isArray(answer.body.settings) ? answer.body.settings : [];
        const row = settings.find(setting => setting.property === 'onyx.read-timeout-ms');
        const millis = row && row.set ? Number(row.value) : NaN;
        readTimeout = Number.isFinite(millis) && millis > 0 ? Math.round(millis / 1000) : null;
    }
    readTimeoutSetting();
    app.whenConfigChanges(readTimeoutSetting);

    /**
     * Puts the sending panel in the readout while a call is out, keeping what it held. The counter
     * is hidden from screen readers: the status line has announced the call once already.
     */
    function showSending(saying) {
        const kept = Array.from(result.childNodes);
        const scrolled = readout.scrollTop;
        const elapsed = el('span', {class: 'sending__elapsed', aria: {hidden: 'true'}});
        const started = Date.now();
        const tick = () => {
            const seconds = Math.floor((Date.now() - started) / 1000);
            elapsed.textContent = 'Waiting ' + seconds + ' s.'
                + (readTimeout !== null ? ' The read timeout is ' + readTimeout + ' s (onyx.read-timeout-ms).' : '');
        };
        tick();
        const node = el('div', {class: 'empty-state empty-state--sending'},
            el('p', {class: 'empty-state__lead'}, saying),
            el('p', {class: 'small'}, elapsed));
        result.replaceChildren(node);
        return {node, kept, scrolled, timer: setInterval(tick, 1000)};
    }

    const bench = {
        /** The form, for listening to changes in it. */
        form,
        /** Where the tab puts its own controls, under the shared case: inside More options when the tab folds them. */
        own,
        /** Where the tab puts the fields of the call a tester must see or fill, between the case and More options. Never folded. */
        beforeOptions,
        /** The closed fold of the tab's optional fields, or a fold holding nothing when the tab does not fold them. */
        moreOptions,
        /** Says again on the fold's summary what in it is set, after the tab changed a field without an event. */
        optionsChanged: () => drawOptionsSaid(),
        /** Where the tab puts what belongs with its send button. It stays in view while the case scrolls. */
        actions,
        /** Where the summary of a refused form appears. The tab places it, at the top of the form's scrolling part unless it says otherwise. */
        problem,
        /** The part of the form that scrolls. A refusal's summary goes at its top, so it never takes height from the pinned send bar. */
        scroll,

        /**
         * Places the shared case in this tab. Called whenever the tab is shown: there is one case
         * node in the page. What the lookups returned goes with it, into the readout, where there
         * is room for a table and a wire view.
         */
        showCase() {
            caseSlot.append(app.caseContext.node);
            if (folded) {
                moreOptions.append(app.chain.control);
                drawOptionsSaid();
            } else {
                caseSlot.append(app.chain.control);
            }
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
         * options.sendsCase, optional: false for a call that carries nothing of the case, so text
         * left in the procedure code box does not hold it up; left out, the call is taken to send
         * the case whenever the case is shown on this tab.
         * options.result, optional: true for a call that goes to the service and draws a result, so
         * the readout says it is waiting, and for how long, instead of what it held before.
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
                const placed = [];
                const said = formProblem(nothing, markFieldErrors([ownRequest.field], [{field: 'rawBody', message: 'Paste the request body here.'}], {placed}), placed);
                said.classList.add('form-problem--own');
                showFormProblem(problem, said);
                // Not the title: once the box is filled, "There is no request" would no longer be true.
                status.textContent = refusedSaying(1);
                return null;
            }
            const typed = textLeftToRefuse(codeBoxFacts(options));
            if (typed !== '') {
                // Text left in the code box is not part of the case on screen, so it is never sent,
                // and the send is refused rather than made without it: the tester decides which.
                const left = {title: 'Nothing was sent: "' + typed + '" was typed in the procedure code box and not added to the case.',
                    action: 'Choose Add code to add it, or clear the box, then send again.', fieldErrors: []};
                const placed = [];
                showFormProblem(problem, formProblem(left, markFieldErrors([app.caseContext.node],
                    [{field: 'procedureCodes', message: 'Typed and not added. Choose Add code, or clear the box.'}], {placed}), placed));
                status.textContent = left.title;
                return null;
            }
            // The control that started the call. Disabling it while the call is out drops the focus
            // to the page, so it is given back when the call is done, unless the tester has moved on.
            const starter = options.control || (document.activeElement instanceof HTMLElement && document.activeElement !== document.body
                ? document.activeElement : null);
            setBusy(true, options.control);
            status.textContent = options.saying;
            // A live call can wait many seconds, and the readout would go on saying what it held
            // before (nothing sent yet, or the last result) as if it were the answer.
            const waiting = options.result === true ? showSending(options.saying) : null;
            let answer;
            try {
                // The request is built first and the case read after it, so the case kept is the one that went out.
                const sending = send();
                sentCase = app.caseContext.summary();
                answer = await sending;
            } finally {
                if (waiting) {
                    // Whatever the answer, what the readout held comes back; a result then replaces it.
                    clearInterval(waiting.timer);
                    if (result.contains(waiting.node)) {
                        result.replaceChildren(...waiting.kept);
                        readout.scrollTop = waiting.scrolled;
                    }
                }
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
                const placed = [];
                showFormProblem(problem, formProblem(answer.body, markFieldErrors([panel], answer.body.fieldErrors, {placed}), placed));
                const counted = Array.isArray(answer.body.fieldErrors) ? answer.body.fieldErrors.length : 0;
                if (counted > 0) {
                    // The server's title counts the problems in the present tense; the summary keeps it and
                    // follows the corrections, and this line says what the send met, which stays true.
                    status.textContent = refusedSaying(counted);
                }
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
            // Once a result is drawn the case table of the empty state is gone, so on mock the cases stay one click away under it.
            const another = typeof settings.anotherCase === 'function' ? settings.anotherCase() : null;
            if (another) {
                result.append(another);
            }
            drawBeforeSending();
            readout.scrollTop = 0;
            // In one column the page scrolls, not the readout, and the window still shows the bottom of the form that was
            // sent: the new result is brought to the top of the window, as a later DTR step already is. Where the readout
            // scrolls on its own it is already at its top, and the page is left where it is.
            if (getComputedStyle(readout).overflowY === 'visible') {
                const shown = result.querySelector('.result') || result;
                shown.scrollIntoView({block: 'start'});
            }
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
                say: text => { status.textContent = text; },
                track: (...controls) => bench.track(...controls),
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
            button('Close the request', close, {class: 'button--quiet button--small'})),
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
 * The label of a mock scenario's row on an empty tab, with the server's notes about its file
 * beneath it: what is wrong with the file, such as a claim that does not fit what it answers.
 * The note that a file is the default only explains, and goes in About this step.
 */
export function scenarioLabel(scenario) {
    const notes = scenario.notes || [];
    return notes.length === 0 ? scenario.label
        : [scenario.label, el('ul', {class: 'note-list small scenario-notes'}, notes.map(note => el('li', null, note)))];
}

/**
 * What the server says about the scenario files as a whole that no scenario's own notes already
 * say, such as a file that was not read, set above the table of scenarios. Null when there is none.
 */
export function scenarioListNotes(list) {
    const said = new Set((list.scenarios || []).flatMap(scenario => (scenario.notes || []).concat(scenario.defaultNote ? [scenario.defaultNote] : [])));
    const rest = (list.notes || []).filter(note => !said.has(note));
    return rest.length === 0 ? null : el('div', {class: 'scenario-list-notes'},
        el('p', {class: 'sub-heading'}, 'About the scenario files'),
        el('ul', {class: 'note-list'}, rest.map(note => el('li', null, note))));
}

/**
 * A scenario's description with its notes after it, for the hint beneath the Mock scenario field.
 */
export function scenarioAbout(scenario) {
    return [scenario.description, ...(scenario.notes || []).concat(scenario.defaultNote ? [scenario.defaultNote] : [])
        .map(note => [' ', el('strong', null, note)])];
}

/**
 * The notes of the scenario list that only explain, for About this step: which file is the
 * default, in the server's words. Null when there is none.
 */
export function scenarioDefaultNotes(list) {
    const notes = ((list && list.scenarios) || []).filter(scenario => scenario.defaultNote)
        .map(scenario => el('li', null, 'Scenario ' + scenario.number + ': ' + scenario.defaultNote));
    return notes.length === 0 ? null : el('ul', {class: 'note-list'}, notes);
}

/** The words of the code picker's hint that explain it, said in each protocol tab's About this step. */
export const CODE_PICKER_ABOUT = 'In the procedure code box, the arrows choose from the code list. A code not on the list is added as typed. '
    + 'Several can be pasted at once, separated by commas or spaces.';

/**
 * The table of mock scenarios on an empty tab, the same on CRD, DTR and PAS. Drawn only on the
 * mock profile, by a tab that has a scenario list to show; on live there is none to draw.
 *
 * options.own: true while a request of the tester's own is chosen. The form's case is not sent
 * with such a body, so the member ID is copied, to be put into the body, and nothing is filled.
 * options.onUse(scenario), optional: called before the case is filled, so the tab can let the
 * member ID decide again in place of a scenario chosen by hand.
 *
 * "Use this case" fills the member ID, the NPI and the codes the scenario file names for its case,
 * each looked up as if typed, so the first send needs no other input. A file that names no sample
 * case fills the member ID only.
 */
/**
 * The cases of the mock scenario table, folded, for a readout that holds a result. Null on live and when the list is not
 * read: the table and everything that fills in a sample exist on mock only. "Start a new case" in the chain bar opens it.
 */
export function anotherCaseFold(app, list, own, onUse) {
    if (!app.isMock() || !list || !list.available || list.scenarios.length === 0) {
        return null;
    }
    return el('details', {class: 'fold another-case'}, el('summary', null, 'Start another case on mock (' + list.scenarios.length + ')'),
        el('p', null, own ? 'On mock, a member ID from this table inside your body picks the answer. Copy one:'
            : 'On mock, each member ID picks the answer. Pick a case:'),
        scenarioListNotes(list),
        scenarioTable(app, list, {own, onUse}));
}

export function scenarioTable(app, list, options) {
    const settings = options || {};
    const own = settings.own === true;
    return table([{text: '', class: 'col-5'}, {text: 'Scenario', class: 'col-45'}, {text: 'Case', class: 'col-25'},
        {text: '', class: 'col-25'}], list.scenarios.map(scenario => {
        const sample = scenario.sampleCase && scenario.sampleCase.npi ? scenario.sampleCase : null;
        const codes = sample ? sample.procedureCodes || [] : [];
        const memberId = scenario.sentinelMemberId || null;
        const said = sample ? 'member ID ' + memberId + ', NPI ' + sample.npi + ', ' + (codes.length === 1 ? 'code ' : 'codes ') + codes.join(', ') : '';
        return [
            {class: 'num', node: String(scenario.number)},
            scenarioLabel(scenario),
            [sentinelCell(scenario), sample && !own && memberId
                ? el('div', {class: 'small scenario-sample'}, 'NPI ', code(sample.npi), ', ', codes.length === 1 ? 'code ' : 'codes ',
                    codes.map((value, index) => [index > 0 ? ', ' : null, code(value)]))
                : null],
            !memberId ? null
                : own ? copyButton('Copy this member ID', () => memberId)
                    : button('Use this case', () => {
                        if (typeof settings.onUse === 'function') {
                            settings.onUse(scenario);
                        }
                        if (sample) {
                            app.caseContext.useSample({memberId, npi: sample.npi, procedureCodes: codes});
                        } else {
                            app.caseContext.setMemberId(memberId);
                        }
                    }, {class: 'button--text', aria: {label: sample ? 'Use the case: ' + said : 'Use the case: member ID ' + memberId}})
        ];
    }), {class: 'sentinels'});
}

/**
 * The mock scenario chosen by hand in the Mock scenario field, as the server listed it, or null
 * when none is chosen or the one chosen is not in the list. It says nothing about which scenario
 * the member ID or the NPI would pick: that rule is the server's, and the result names the
 * scenario that answered.
 */
export function scenarioOverride(list, chosenId) {
    const id = typeof chosenId === 'string' ? chosenId.trim() : '';
    if (id === '' || !list || !Array.isArray(list.scenarios)) {
        return null;
    }
    return list.scenarios.find(scenario => scenario.id === id) || null;
}

/**
 * The sentence under the Member ID field while a mock scenario is chosen by hand, so the tester
 * learns it where they look before sending and not from the result. where says where the field
 * is; letDecide sets the field back to "The member ID decides".
 */
export function scenarioOverrideNote(scenario, where, letDecide) {
    return el('p', {class: 'scenario-override'},
        'Mock scenario ' + scenario.number + ' "' + scenario.label + '" is chosen ' + where + '. It answers this run in place of the '
            + 'scenario the member ID and NPI would pick. ',
        button('Let the member ID decide', letDecide, {class: 'button--text'}));
}

/**
 * The member ID cell of a mock scenario's row on an empty tab: the first member ID that selects
 * it, then every other identifier the server lists for it (sentinelMemberIds, sentinelNpis and,
 * on DTR and PAS, the CRD member IDs it also answers for), so a tester can reach it every way it
 * can be reached. Each list is the server's; none is worked out here.
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
        fromCrd.length > 0 ? el('div', {class: 'small'}, 'or the CRD member ', fromCrd.length === 1 ? 'ID ' : 'IDs ', list(fromCrd), ', for a journey begun at CRD') : null
    ];
}

/**
 * What a tab says first when work was handed on to it from another tab: where it came from, and
 * a quiet button that presses the form's own send button, so the send goes the usual way and the
 * form's button stays the one primary button with that name on the tab.
 */
export function handedOnLead(handed, heading, label, send) {
    return el('div', {class: 'empty-state__handed'},
        el('p', {class: 'empty-state__lead'}, 'Handed on from the ' + handed.fromProtocol + ' run ', code(handed.fromRunId), '.'),
        el('p', null, 'What it handed on is in the form, under "' + heading + '". Choose "' + label + '".'),
        button(label, send, {class: 'button--quiet'}));
}

/**
 * The lead of an empty readout, true after a reload too, with the newest run of the protocol in
 * the ledger read from the server each time: the page keeps nothing of a run across a reload.
 * Only the run id, its time and its outcome are drawn, never what it was about.
 */
export function sinceLoaded(protocol, words, labelOf) {
    const newest = el('p', {class: 'small', hidden: true});
    api.get('/api/runs?protocol=' + protocol.toLowerCase() + '&limit=1').then(answer => {
        const runs = answer.kind === 'ok' && answer.body && Array.isArray(answer.body.runs) ? answer.body.runs : [];
        const run = runs[0];
        if (!run || !run.runId) {
            return;
        }
        fill(newest, 'The newest ' + protocol + ' run in the ledger: ', runLink(run.runId), ', ', when(run.timestamp), ', ', labelOf(run.outcome),
            '. It may be from another tab or another tester. Open it to read its result.');
        newest.hidden = false;
    });
    return el('div', null, el('p', {class: 'empty-state__lead'}, words), newest);
}
