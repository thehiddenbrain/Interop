// The Workbench (plan 3.2): the case on the left, the four steps on the right, one screen for the
// work. The case, the journey and every step's last result are kept in this module, so a tester who
// goes to Audit and back finds them as they left them; nothing of it goes to browser storage.
//
// Each step panel is a module mounted with one ctx (plan 9.1): step 1 here (wb-crd.js), steps 2 and
// 3 by batch H (wb-dtr.js, wb-submit.js), step 4 in wb-status.js. Steps 2 and 3 load when the
// Workbench opens, each on its own, so a defect in one leaves the others working.
//
// Rules the operator's earlier tool taught (history C10, C11, C27, G14, G15, G33): a call that ends
// while another step is open updates that step's chip and never switches the view; focus moves to
// the step opened; "Start a new case" is always in the banner and clears every step; a Continue left
// by a failed answer is removed (the card drawn for a failure has none); each step keeps its own
// last result; every failure appears in one visible place, the step's own.

import {app} from './app.js';
import {call} from './api.js';
import {el, button, fill, clear} from './dom.js';
import {vocabulary} from './words.js';
import {remember} from './session-runs.js';
import {resultCard, ledgerCard} from './result.js';
import {errorPanel} from './fault.js';
import {fillFromAnswer, maskedLine} from './masks.js';
import {caseField, caseKey, onCaseChange, optionValue, MARKS} from './wb-case.js';
import {createMemberCard} from './wb-member.js';
import {createProviderCard} from './wb-provider.js';
import {createServiceCard} from './wb-service.js';
import {createStepper} from './wb-stepper.js';
import {createJourney, stepOfAction, stepOfOperation, statusCarry, readBack, caseFromLedger, NOT_KEPT_LINE, STEPS} from './wb-journey.js';
import {optionsToggle} from './wb-run.js';
import {createRunValues} from './wb-values.js';
import {useEffective, createRequestLine} from './wb-request.js';
import * as crdStep from './wb-crd.js';
import * as statusStep from './wb-status.js';
// Not used by the Workbench itself. The step modules that read a chosen file (attachments in
// Submit, batch H) and the Configuration pages (batch I) import it; until one of them does, this
// import keeps it loaded with the page and held by StaticJoinTest.
import {readBase64} from './files.js';

// What the Workbench has read from the server. Mock lists exist on mock only: on live they are
// never asked for, and nothing is built from them.
const loaded = {
    words: null,
    mockLists: null,
    savedMembers: [],
    savedProviders: [],
    savedCodes: [],
    pasOptions: null,
    effective: null,
    epoch: null
};

let built = null;

/** Draws the view into root. route: the parts of the address after #workbench. */
export function mount(root, route) {
    if (!built) {
        built = build();
    }
    root.append(built.node);
    // A chain named in the address is read back from the chain and the trail and redrawn (A2c); where
    // it cannot be, the read-back line above the steps says why in A2c's own words and the address
    // goes back to the plain Workbench, so it never claims a journey that is not on screen (I-8).
    built.follow(route);
    const offs = [
        app.on('mode', () => built.refresh()),
        app.on('config', () => built.reload())
    ];
    built.refresh();
    built.reload();
    return {
        update(next) {
            built.follow(next);
        },
        unmount() {
            for (const off of offs) {
                off();
            }
            built.node.remove();
        }
    };
}

function build() {
    const journey = createJourney();
    let open = 1;

    const member = createMemberCard({
        isMock: () => app.isMock(),
        mockLists: () => (app.isMock() ? loaded.mockLists : null),
        savedMembers: () => loaded.savedMembers,
        dateOfService: () => service.dateOfService(),
        useSample: sample => {
            if (sample.procedureCodes) {
                service.useCodes(sample.procedureCodes);
            }
            if (sample.npi) {
                provider.useNpi(sample.npi);
            }
        }
    });
    const provider = createProviderCard({
        savedProviders: () => loaded.savedProviders,
        placeOfService: (value, mark) => service.placeOfService(value, mark)
    });
    const service = createServiceCard({
        lineOfBusiness: () => member.lineOfBusiness(),
        options: () => loaded.pasOptions,
        savedCodes: () => loaded.savedCodes
    });

    const caseColumn = el('aside', {class: 'case', 'aria-label': 'Case'},
        el('div', {class: 'case__head'}, el('h2', {class: 'section-label'}, 'Case'),
            button('Manage lists', () => app.go('#configuration/members'), {small: true})),
        member.node, provider.node, service.node);

    const bannerText = el('p', {class: 'banner__case'});
    const banner = el('div', {class: 'banner'}, bannerText, button('Start a new case', () => newCase()));
    // The one place a reload says how it went: reading back, a run the trail no longer keeps, or why it could not.
    const readBackLine = el('div', {class: 'readback', 'aria-live': 'polite'});
    const stepper = createStepper((n, how) => openStep(n, !(how && how.keyboard)));

    // The options of steps 2 to 4 belong to the case's journey, so they are built here and handed
    // to the step modules through ctx.optionsRow() and ctx.option(name).
    const options = {
        2: {
            questionnaireUrl: caseField({name: 'questionnaireUrl', label: 'Questionnaire url', mono: true}),
            coverageAssertionId: caseField({name: 'coverageAssertionId', label: 'Coverage assertion id', mono: true}),
            nextQuestionShape: caseField({name: 'nextQuestionShape', label: 'Shape of the next step', kind: 'select',
                options: [['', 'DTR 2.0.1'], ['questionnaire-response-in', 'DTR 2.2.0 ballot'], ['questionnaire-response', 'SDC']]})
        },
        3: {claimId: caseField({name: 'claimId', label: 'Claim id', mono: true, hint: 'Blank: PAVE makes one.'})},
        4: {claimId: caseField({name: 'claimId', label: 'Claim id', mono: true})}
    };
    const scenarioFields = {};
    const optionRows = {};
    // Which request of the collection each later step sends, and how its body is made (D-313). A
    // body as written there carries nothing handed on from the step before, and the line says so.
    const environmentName = () => {
        const mode = app.mode();
        return mode && mode.environment && typeof mode.environment.name === 'string' && mode.environment.name !== ''
            ? mode.environment.name : null;
    };
    const requestLines = {
        2: [createRequestLine({operation: 'DTR_QUESTIONNAIRE_PACKAGE', label: 'the questionnaire package', laterStep: true, environment: environmentName}),
            createRequestLine({operation: 'DTR_NEXT_QUESTION', label: 'the next question', laterStep: true, environment: environmentName})],
        3: [createRequestLine({operation: 'PAS_SUBMIT', label: 'Submit', laterStep: true, environment: environmentName})],
        4: [createRequestLine({operation: 'PAS_INQUIRE', label: 'the inquiry', laterStep: true, environment: environmentName})]
    };
    for (const n of [2, 3, 4]) {
        scenarioFields[n] = caseField({name: 'scenarioId', label: 'Mock scenario', kind: 'select', options: []});
        optionRows[n] = el('div', {class: 'panel__options'}, requestLines[n].map(line => line.node),
            Object.values(options[n]).map(control => control.node));
    }

    const panels = {};
    const modules = {};
    for (const step of STEPS) {
        panels[step.n] = el('section', {class: 'panel', id: 'step-panel-' + step.n, role: 'tabpanel', 'aria-labelledby': 'step-tab-' + step.n,
            hidden: step.n !== 1, tabindex: -1});
    }
    // A result read back after a reload that the step's own module does not draw: drawn by the frame
    // under the module's panel until the step has a result of its own, or the module draws it.
    const restoredSlots = {};
    for (const step of STEPS) {
        restoredSlots[step.n] = el('div', {class: 'step-restored'});
    }
    const runValues = createRunValues({changed: () => null});
    const work = el('div', {class: 'work'}, banner, readBackLine, stepper.node, runValues.node, Object.values(panels));
    const node = el('div', {class: 'workbench'}, caseColumn, work);

    function ctxFor(n) {
        return {
            request,
            carry(step) {
                const handed = journey.carry(step, caseKey(request()));
                return handed ? handed.carry : null;
            },
            handOn(step, carry) {
                handOn(step, carry);
            },
            option(name) {
                if (name === 'scenarioId') {
                    return app.isMock() && scenarioFields[n] ? scenarioFields[n].value() : '';
                }
                return optionValue(options[n], name);
            },
            optionsRow() {
                return optionRows[n] || null;
            },
            optionsToggle() {
                return optionRows[n] ? optionsToggle(optionRows[n]) : null;
            },
            resulted(envelope) {
                resulted(n, envelope);
            },
            showField(name) {
                return showField(name, null);
            },
            placeErrors(fieldErrors) {
                return placeErrors(fieldErrors, n);
            },
            needValues(fieldErrors, operation) {
                return runValues.need(fieldErrors, operation);
            },
            go(step, how) {
                openStep(step, true, how);
            },
            isMock: () => app.isMock(),
            mode: () => app.mode(),
            visible: () => open === n && Boolean(node.isConnected),
            typedCode: () => service.typedCode(),
            blocked,
            readinessLine,
            labelOf: field => labelOf(field),
            latest(step) {
                const kept = journey.result(step);
                return kept ? kept.envelope : null;
            },
            card(envelope) {
                return card(envelope);
            },
            refused(step, error) {
                journey.refused(step, error);
            },
            handedLine(step) {
                return handedLine(step);
            },
            crdScenarios: () => (app.isMock() && loaded.mockLists ? loaded.mockLists.crd : null),
            environmentName
        };
    }

    /** A carry handed to a step; step 4's Claim id is filled from it only through the check for a mask. */
    function handOn(step, carry) {
        journey.handOn(step, carry, caseKey(request()));
        if (step === 4) {
            statusStep.fillClaimFrom(options[4].claimId, carry);
        }
    }

    /** What every run sends (ctx.request of plan 9.1). */
    function request() {
        const m = member.request();
        const p = provider.request();
        const s = service.request();
        return {
            member: m.member, memberOverrides: m.memberOverrides,
            provider: p.provider, providerOverrides: p.providerOverrides, servicingProvider: p.servicingProvider,
            procedureCodes: s.procedureCodes, dateOfService: s.dateOfService, placeOfService: s.placeOfService,
            diagnosisCodes: s.diagnosisCodes, levelOfService: s.levelOfService, certificationType: s.certificationType,
            requestType: s.requestType, serviceType: s.serviceType, testerRef: s.testerRef, note: s.note,
            chainId: journey.chainId(), configEpoch: app.epoch()
        };
    }

    /**
     * A result arrived for step n. It updates that step's chip and never switches the view. how.quiet:
     * a result read back after a reload, which keeps the frame's drawing of it and reads the chain once at the end.
     */
    function resulted(n, envelope, how) {
        if (!envelope || typeof envelope !== 'object') {
            return;
        }
        const step = stepOfOperation(envelope.operation) || n;
        const quiet = Boolean(how && how.quiet);
        if (!quiet) {
            clear(restoredSlots[step]);
        }
        journey.resulted(step, envelope, caseKey(request()), app.epoch());
        remember(envelope);
        member.sentAs(envelope.wire && envelope.wire.memberId);
        if (step === 1 && envelope.nextStep && envelope.nextStep.carry) {
            fillFromStep1(envelope.nextStep.carry);
        }
        // A submission hands the Status step its claim from the server's own carry, so step 4 never
        // depends on how step 3 is drawn; one that offers no inquiry takes back an earlier hand-over.
        if (step === 3) {
            handOn(4, statusCarry(envelope));
        }
        const chainId = journey.knownChain();
        if (chainId && window.location.hash.startsWith('#workbench')) {
            window.history.replaceState(null, '', '#workbench/' + encodeURIComponent(chainId));
        }
        drawStepper();
        if (!quiet) {
            readChain();
        }
    }

    /** Fills step 2's options from step 1's hand-over, through the check for a mask, marked "from step 1". */
    function fillFromStep1(carry) {
        const pairs = [['questionnaireUrl', 'questionnaire'], ['coverageAssertionId', 'coverageAssertionId']];
        for (const [name, key] of pairs) {
            const control = options[2][name];
            const values = carry[key];
            const value = Array.isArray(values) ? values[0] : values;
            if (value === undefined || value === null) {
                continue;
            }
            if (fillFromAnswer(control, value)) {
                control.set(control.input.value, 'from step 1');
            }
        }
    }

    async function readChain() {
        const chainId = journey.knownChain();
        if (!chainId) {
            return;
        }
        const answer = await call('GET', '/api/chains/' + encodeURIComponent(chainId));
        if (answer.ok && journey.knownChain() === chainId) {
            journey.setChain(answer.data);
            drawStepper();
        }
    }

    function drawStepper() {
        stepper.draw(loaded.words, journey.chain(), n => {
            const kept = journey.result(n);
            return kept ? kept.envelope : null;
        });
        stepper.select(open);
    }

    /** Opens a step. Focus moves to it. how.start asks the step to send at once (a Continue that fetches). */
    function openStep(n, focus, how) {
        open = n;
        for (const step of STEPS) {
            panels[step.n].hidden = step.n !== n;
            const module = modules[step.n];
            if (module && step.n !== n && typeof module.hide === 'function') {
                guarded(() => module.hide());
            }
        }
        stepper.select(n);
        const module = modules[n];
        if (module && typeof module.show === 'function') {
            guarded(() => module.show(how || {}));
        }
        dropRestoredWhenDrawn(n);
        if (focus) {
            panels[n].focus({preventScroll: false});
        }
    }

    /**
     * The card of a result, with its next step wired to the step it leads to. how.readBack: a card the
     * frame draws after a reload for a step whose module does not draw it; a next step inside that
     * same step (answer the questions again) has nothing on screen to act on, so it is said in words.
     */
    function card(envelope, how) {
        const own = stepOfOperation(envelope.operation) || 1;
        const kept = journey.result(own);
        const readBack = Boolean(how && how.readBack);
        const sameStep = action => stepOfAction(action) === own;
        const leadsTo = stepOfAction(envelope.nextStep && envelope.nextStep.action);
        plainWhenWayOn(own, leadsTo !== null && leadsTo !== own);
        return resultCard(envelope, {
            // A step already offers its own send (step 2's form, step 4's inquiry row): its card says
            // that next step in words and does not repeat the button (I-6 widened, look-10).
            wordsOnly: action => stepOfAction(action) === own,
            context: 'workbench',
            stale: Boolean(kept && kept.epoch !== null && app.epoch() !== null && kept.epoch !== app.epoch()),
            onNext: readBack && envelope.nextStep && sameStep(envelope.nextStep.action) ? undefined : action => next(action, envelope),
            nextBlocked: action => {
                const step = stepOfAction(action);
                return step === null ? null : blocked(OPERATION_OF_STEP[step]) || (journey.offered(step) ? null : withdrawnWords(step));
            }
        });
    }

    /**
     * One filled button at a time (I-6): once a result of step n offers a way on, the step's own run
     * button in its head is drawn plain; a new case or a result with no way on fills it again.
     */
    function plainWhenWayOn(n, wayOn) {
        const panel = panels[n];
        if (!panel) {
            return;
        }
        for (const control of panel.querySelectorAll('.panel__head .btn--primary, .panel__head [data-run-button]')) {
            control.setAttribute('data-run-button', 'true');
            control.classList.toggle('btn--primary', !wayOn);
        }
    }

    /** A next-step button was pressed: Continue opens its step (step 2 fetches at once; Submit sends nothing). */
    function next(action, envelope) {
        if (action === 'OPEN_CONFIG') {
            app.go(configurationPlace(envelope, app.mode()));
            return;
        }
        if (action === 'UNLOCK_OR_SWITCH') {
            app.go('#configuration/production-writes');
            return;
        }
        const step = stepOfAction(action);
        if (step !== null) {
            // "Deliver a sample" (mock only) asks step 4 to deliver it: a sample sends nothing to a service.
            openStep(step, true, {start: action === 'START_DTR' || action === 'CHECK_STATUS', sample: action === 'DELIVER_SAMPLE_NOTIFICATION'});
        }
    }

    function handedLine(step) {
        const line = journey.handedLine(step, caseKey(request()));
        if (line.kind !== 'handed') {
            return el('p', {class: 'small muted handed'}, line.words);
        }
        return line.masked.length > 0 ? el('div', null, line.masked.map(words => maskedLine(words))) : null;
    }

    /**
     * Each field error at its field, its fold opened; returns the names placed. A field of step n's
     * own options (its Claim id, for one) is shown there, the options row opened.
     */
    function placeErrors(fieldErrors, n) {
        const placed = [];
        for (const each of Array.isArray(fieldErrors) ? fieldErrors : []) {
            if (each && typeof each.field === 'string' && (runValues.show(each.field, each.message) || showOption(n, each.field, each.message)
                || showField(each.field, each.message))) {
                placed.push(each.field);
            }
        }
        return placed;
    }

    function showOption(n, name, message) {
        const control = n && options[n] ? options[n][name] : null;
        if (!control) {
            return false;
        }
        const row = optionRows[n];
        if (row && row.hidden) {
            row.hidden = false;
            const toggle = panels[n].querySelector('[aria-expanded]');
            if (toggle) {
                toggle.setAttribute('aria-expanded', 'true');
            }
        }
        control.setError(message || null);
        control.input.focus();
        return true;
    }

    // The procedure codes have no case field of their own, so their refusal line is kept here, to
    // go when the codes change or a new case starts and never stay under codes it was not about.
    let codesSaid = null;
    function forgetCodesSaid() {
        if (codesSaid) {
            codesSaid.remove();
            codesSaid = null;
        }
    }

    /** Opens the card fold that holds a case field and focuses it; a message is shown at it. */
    function showField(name, message) {
        const [head, ...rest] = String(name).split('.');
        const tail = rest.join('.');
        if (head === 'member' && tail) {
            return member.show(tail, message);
        }
        if (head === 'provider' && tail) {
            return provider.show(tail, message);
        }
        if (head === 'servicingProvider') {
            return provider.show(name, message);
        }
        if (head === 'procedureCodes' && message) {
            forgetCodesSaid();
            codesSaid = el('p', {class: 'field__error'}, message);
            service.codeInput().closest('.field').append(codesSaid);
            service.codeInput().addEventListener('input', forgetCodesSaid, {once: true});
        }
        return service.show(name, message) || member.show(name, message) || provider.show(name, message);
    }

    /**
     * The label a tester sees for a field a refusal names ('member.memberId', 'procedureCodes[0].quantity'),
     * or null; a refusal panel never shows the property path itself (I-1, I-3).
     */
    function labelOf(field) {
        const path = String(field || '');
        const last = path.split('.').pop().replace(/\[\d{1,3}\]$/, '');
        const fields = Array.from(node.querySelectorAll('[data-case-field]'));
        const found = fields.find(each => each.getAttribute('data-case-field') === path)
            || fields.find(each => each.getAttribute('data-case-field') === last);
        const label = found ? found.querySelector('.field__label') : null;
        const words = label && label.firstChild ? String(label.firstChild.textContent || '').trim() : '';
        return words !== '' ? words : null;
    }

    function blocked(operation) {
        if (app.isMock() || !loaded.effective || !operation) {
            return null;
        }
        const found = (loaded.effective.operations || []).find(each => each.operation === operation);
        if (!found) {
            return null;
        }
        const reason = found.unavailable || found.blocked;
        return typeof reason === 'string' && reason !== '' ? reason : null;
    }

    function readinessLine(sentence) {
        return readinessNode(sentence);
    }

    function withdrawnWords(step) {
        return 'Step ' + step + ' was refused for a masked value; type the value, then run step ' + (step - 1) + ' again.';
    }

    function drawBanner() {
        const who = member.label();
        const by = provider.label();
        const codes = service.codesLabel();
        if (!who && !by && !codes) {
            // Live has no member picker: a tester there types the ID.
            fill(bannerText, app.isMock() ? 'Pick a member to begin.' : 'Type a member ID to begin.');
            return;
        }
        const dot = () => el('span', {class: 'banner__sep', 'aria-hidden': 'true'}, ' \u00b7 ');
        fill(bannerText, el('strong', null, who || 'no member'), dot(), by || 'no provider', dot(),
            el('span', {class: 'mono'}, codes || 'no code'));
    }

    /** "Start a new case": clears the case, every step's result, the journey and the chain id in the address. */
    function newCase() {
        reading += 1;
        clearCase();
        if (window.location.hash.startsWith('#workbench')) {
            window.history.replaceState(null, '', '#workbench');
        }
        openStep(1, true);
    }

    /** Every field of the case, every step's result and the journey forgotten; the address is the caller's. */
    function clearCase() {
        clear(readBackLine);
        runValues.clear();
        forgetCodesSaid();
        member.clear();
        provider.clear();
        service.clear();
        for (const n of [2, 3, 4]) {
            for (const control of Object.values(options[n])) {
                control.clear();
            }
        }
        journey.reset();
        for (const step of STEPS) {
            clear(restoredSlots[step.n]);
            const module = modules[step.n];
            if (module && typeof module.reset === 'function') {
                guarded(() => module.reset());
            }
            plainWhenWayOn(step.n, false);
        }
        drawBanner();
        drawStepper();
        for (const module of Object.values(modules)) {
            if (module && typeof module.caseChanged === 'function') {
                guarded(() => module.caseChanged());
            }
        }
    }

    /* ---------- the reload and "Load into the Workbench" (R-38) ---------- */

    // The latest address wins: a read back still on its way when another is asked for, or when
    // "Start a new case" is pressed, is dropped when it arrives.
    let reading = 0;

    /**
     * The address after #workbench: [chainId] after a reload, ['load', runId] from Audit's "Load
     * into the Workbench". Nothing to do for the plain Workbench or the chain already on screen.
     */
    function follow(route) {
        const parts = Array.isArray(route) ? route : [];
        if (parts[0] === 'load' && parts[1]) {
            restore({runId: parts[1]});
        } else if (parts[0] && parts[0] !== 'load' && parts[0] !== journey.knownChain()) {
            restore({chainId: parts[0]});
        }
    }

    /** Reads the case and every step's result back from the chain, the trail and the ledger, and draws them. */
    async function restore(target) {
        reading += 1;
        const mine = reading;
        fill(readBackLine, el('p', {class: 'small muted', role: 'status'}, 'Reading the journey back from the ledger and the trail.'));
        const back = await readBack(target, call);
        const words = await vocabulary();
        if (mine !== reading) {
            return;
        }
        if (!back.ok) {
            fill(readBackLine, errorPanel(back.error || {kind: 'NOT_FOUND', title: 'The journey could not be read back.'}));
            if (window.location.hash.startsWith('#workbench')) {
                window.history.replaceState(null, '', '#workbench');
            }
            return;
        }
        clearCase();
        restoreCase(back);
        let furthest = 1;
        const order = [1, 2, 3].map(String).concat(['inquiry', 'subscription']
            .filter(slot => back.runs[slot]).sort((a, b) => String(back.runs[a].at || '').localeCompare(String(back.runs[b].at || ''))));
        for (const slot of order) {
            const run = back.runs[slot];
            if (!run) {
                continue;
            }
            furthest = Math.max(furthest, run.step);
            if (run.envelope) {
                resulted(run.step, run.envelope, {quiet: true});
                // A completed questionnaire handed Submit its response; read back, the frame hands it on again, as step 2 did.
                const next = run.envelope.nextStep;
                if (run.step === 2 && next && next.action === 'START_PAS' && next.carry && Object.keys(next.carry).length > 0) {
                    handOn(3, next.carry);
                }
                const module = modules[run.step];
                if (module && typeof module.restored === 'function') {
                    guarded(() => module.restored(run.envelope, {at: run.at}));
                } else {
                    restoredSlots[run.step].append(card(run.envelope, {readBack: true}));
                }
            } else {
                // The trail's own sentence says why this step shows its ledger line, not its result.
                restoredSlots[run.step].append(el('p', {class: 'small readback__line'}, run.line || NOT_KEPT_LINE),
                    ledgerCard(run.record || {runId: run.runId}, words));
            }
        }
        for (const step of STEPS) {
            if (restoredSlots[step.n].firstChild && !restoredSlots[step.n].isConnected) {
                panels[step.n].append(restoredSlots[step.n]);
            }
        }
        journey.setChainId(back.chainId);
        journey.setChain(back.chain);
        if (window.location.hash.startsWith('#workbench')) {
            window.history.replaceState(null, '', '#workbench/' + encodeURIComponent(back.chainId));
        }
        // The line speaks of the case only when the case itself came from a ledger line; a run not
        // kept beside a case the trail kept says so at its own step.
        fill(readBackLine, back.theCase && back.theCase.from === 'ledger' ? el('p', {class: 'small readback__line'}, NOT_KEPT_LINE) : null);
        drawBanner();
        drawStepper();
        openStep(furthest, true);
        for (const module of Object.values(modules)) {
            if (module && typeof module.caseChanged === 'function') {
                guarded(() => module.caseChanged());
            }
        }
    }

    /** The case of the newest run the trail keeps, as it was posted; else the fields the ledger line kept. */
    function restoreCase(back) {
        const found = back.theCase;
        if (!found) {
            return;
        }
        const posted = found.from === 'trail' ? found.entry.case || {} : caseFromLedger(found.record);
        const mark = found.from === 'trail' ? MARKS.TRAIL : MARKS.LEDGER;
        member.restore(posted.member, posted.memberOverrides, mark);
        provider.restore(posted.provider, posted.providerOverrides, posted.servicingProvider, mark);
        service.restore(posted, mark);
        // A step's own options as that step's run posted them; the scenario only on mock, where it exists.
        const postedBy = n => {
            const run = n === 4 ? back.runs.inquiry : back.runs[n];
            return run && run.entry && run.entry.case ? run.entry.case : null;
        };
        for (const n of [2, 3, 4]) {
            const own = postedBy(n);
            if (!own) {
                continue;
            }
            for (const [name, control] of Object.entries(options[n])) {
                if (typeof own[name] === 'string' && own[name] !== '') {
                    control.set(own[name], mark);
                }
            }
            if (app.isMock() && typeof own.scenarioId === 'string' && own.scenarioId !== '') {
                scenarioFields[n].set(own.scenarioId, mark);
            }
        }
    }

    /** A result the frame drew after a reload goes once the step's module draws a result of its own. */
    function dropRestoredWhenDrawn(n) {
        const slot = restoredSlots[n];
        if (!slot.firstChild) {
            return;
        }
        const own = Array.from(panels[n].querySelectorAll('.result')).some(card => !slot.contains(card));
        if (own) {
            clear(slot);
        }
    }

    onCaseChange(reason => {
        drawBanner();
        if (reason === 'codes' || reason === 'lookup' || reason === 'picked') {
            forgetCodesSaid();
        }
        if (reason === 'lookup' || reason === 'picked' || reason === 'typed') {
            service.redrawCodes();
        }
        for (const module of Object.values(modules)) {
            if (module && typeof module.caseChanged === 'function') {
                guarded(() => module.caseChanged());
            }
        }
    });

    function drawScenarioOptions() {
        const lists = app.isMock() ? loaded.mockLists : null;
        const keys = {2: 'dtr', 3: 'pas', 4: 'pas'};
        for (const n of [2, 3, 4]) {
            const field = scenarioFields[n];
            const list = lists ? lists[keys[n]] : null;
            const scenarios = list && Array.isArray(list.scenarios) ? list.scenarios : [];
            if (scenarios.length === 0) {
                field.node.remove();
                continue;
            }
            const before = field.input.value;
            fill(field.input, el('option', {value: ''}, 'The member ID decides'),
                scenarios.map(each => el('option', {value: each.id}, each.number + '. ' + (each.short || each.label))));
            field.input.value = before;
            if (!field.node.isConnected) {
                optionRows[n].append(field.node);
            }
        }
    }

    // Steps 1 and 4 are this batch's own; steps 2 and 3 load on their own, so a fault in one leaves the rest.
    modules[1] = mountStep(1, crdStep);
    modules[4] = mountStep(4, statusStep);
    loadStep(2, () => import('./wb-dtr.js'));
    loadStep(3, () => import('./wb-submit.js'));

    function mountStep(n, module) {
        let handle = null;
        guarded(() => {
            handle = module.mount(panels[n], ctxFor(n));
        });
        // The short line that names the request in force while several fit, above the step's options where they are drawn.
        const row = optionRows[n];
        if (row && row.parentNode && requestLines[n]) {
            for (const line of requestLines[n]) {
                row.before(line.summary);
            }
        }
        return handle;
    }

    async function loadStep(n, load) {
        let module;
        try {
            module = await load();
        } catch (missing) {
            panels[n].append(el('p', {class: 'stub-line'}, 'This step could not be loaded. Reload the page.'));
            return;
        }
        modules[n] = mountStep(n, module);
        if (open === n) {
            openStep(n, false);
        }
    }

    function guarded(work) {
        try {
            work();
        } catch (defect) {
            app.notice('This page met a fault of its own. Reload the page.', 'fault');
        }
    }

    drawBanner();
    drawStepper();
    stepper.select(1);

    return {
        node,
        journey,
        follow,
        /** The profile or the words may have changed: draw what depends on them. */
        refresh() {
            member.drawPicker();
            drawScenarioOptions();
            drawStepper();
        },
        /** Reads again what the Workbench draws from, once per configuration epoch. */
        async reload() {
            const epoch = app.epoch();
            if (loaded.epoch === epoch && epoch !== null) {
                return;
            }
            loaded.epoch = epoch;
            const mock = app.isMock();
            const [words, crd, dtr, pas, members, providers, pasOptions, effective, codes] = await Promise.all([
                vocabulary(),
                mock ? call('GET', '/api/crd/scenarios') : null,
                mock ? call('GET', '/api/dtr/scenarios') : null,
                mock ? call('GET', '/api/pas/scenarios') : null,
                call('GET', '/api/lists/members'),
                call('GET', '/api/lists/providers'),
                call('GET', '/api/pas/options'),
                call('GET', '/api/config/effective'),
                call('GET', '/api/lists/codes')
            ]);
            loaded.words = words;
            loaded.mockLists = mock && crd && crd.ok ? {crd: crd.data, dtr: dtr && dtr.ok ? dtr.data : null, pas: pas && pas.ok ? pas.data : null}
                : null;
            loaded.savedMembers = members.ok && Array.isArray(members.data.members) ? members.data.members : [];
            loaded.savedProviders = providers.ok && Array.isArray(providers.data.providers) ? providers.data.providers : [];
            loaded.savedCodes = codes.ok && codes.data && Array.isArray(codes.data.codes) ? codes.data.codes : [];
            loaded.pasOptions = pasOptions.ok ? pasOptions.data : null;
            loaded.effective = effective.ok ? effective.data : null;
            useEffective(loaded.effective);
            member.drawPicker();
            provider.drawPicker();
            service.drawOptions();
            service.drawSaved();
            drawScenarioOptions();
            drawStepper();
            for (const module of Object.values(modules)) {
                if (module && typeof module.redraw === 'function') {
                    guarded(() => module.redraw(words));
                }
            }
        }
    };
}

const OPERATION_OF_STEP = {1: 'CRD_ORDER_SIGN', 2: 'DTR_QUESTIONNAIRE_PACKAGE', 3: 'PAS_SUBMIT', 4: 'PAS_INQUIRE'};

// An answer that never reached a service (refused connection, unknown host) points at the address.
const ADDRESS_FAULTS = ['UNREACHABLE'];
const NO_ENVIRONMENT = 'No environment is active.';
const OPEN_CONFIGURATION = 'Open Configuration';

/**
 * The readiness line of a step: the server's sentence as it is, so its place to type a value is
 * never cut off. In "No environment is active. Open Configuration and make one active." the words
 * Open Configuration are the link; any other sentence gets the link after it.
 */
export function readinessNode(sentence) {
    const text = String(sentence || '');
    const at = text.indexOf(OPEN_CONFIGURATION);
    if (text.startsWith(NO_ENVIRONMENT) && at >= 0) {
        return el('p', {class: 'small readiness__line'}, text.slice(0, at),
            el('a', {href: '#configuration/environments'}, OPEN_CONFIGURATION), text.slice(at + OPEN_CONFIGURATION.length));
    }
    return el('p', {class: 'small readiness__line'}, text, ' ',
        el('a', {href: '#configuration/environments'}, OPEN_CONFIGURATION));
}

/**
 * Where "Open Configuration" leads after a result (I-32): the active environment's Sign-in after a
 * sign-in that failed, its Addresses after an answer that points at the address, else the list.
 */
export function configurationPlace(envelope, mode) {
    const name = mode && mode.environment && typeof mode.environment.name === 'string' && mode.environment.name !== ''
        ? mode.environment.name : null;
    if (!name) {
        return '#configuration/environments';
    }
    const base = '#configuration/environments/' + encodeURIComponent(name);
    const verdict = envelope && envelope.verdict ? envelope.verdict : {};
    const error = envelope && envelope.error ? envelope.error : {};
    const status = typeof error.httpStatus === 'number' ? error.httpStatus : null;
    if (verdict.outcomeCode === 'NOT_SIGNED_IN' || status === 401 || status === 403) {
        return base + '/sign-in';
    }
    if (status === 404 || ADDRESS_FAULTS.includes(error.kind) || ADDRESS_FAULTS.includes(verdict.outcomeCode)) {
        return base + '/addresses';
    }
    return '#configuration/environments';
}
