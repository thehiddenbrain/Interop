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
import {el, button, firstSentence, fill, clear} from './dom.js';
import {vocabulary} from './words.js';
import {remember} from './session-runs.js';
import {resultCard} from './result.js';
import {fillFromAnswer, maskedLine} from './masks.js';
import {caseField, caseKey, onCaseChange} from './wb-case.js';
import {createMemberCard} from './wb-member.js';
import {createProviderCard} from './wb-provider.js';
import {createServiceCard} from './wb-service.js';
import {createStepper} from './wb-stepper.js';
import {createJourney, stepOfAction, stepOfOperation, STEPS} from './wb-journey.js';
import {optionsToggle} from './wb-run.js';
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
    // A chain named in the address is redrawn from the trail in a later slice (A2c). Until then an
    // address that names a chain this page does not hold is put back to the plain Workbench, so it
    // never claims a journey that is not on screen.
    if (route && route.length > 0 && route[0] !== built.journey.knownChain()) {
        window.history.replaceState(null, '', '#workbench');
    }
    const offs = [
        app.on('mode', () => built.refresh()),
        app.on('config', () => built.reload())
    ];
    built.refresh();
    built.reload();
    return {
        update() {},
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
        options: () => loaded.pasOptions
    });

    const caseColumn = el('aside', {class: 'case', 'aria-label': 'Case'},
        el('div', {class: 'case__head'}, el('h2', {class: 'section-label'}, 'Case'),
            button('Manage lists', () => app.go('#configuration/members'), {small: true})),
        member.node, provider.node, service.node);

    const bannerText = el('p', {class: 'banner__case'});
    const banner = el('div', {class: 'banner'}, bannerText, button('Start a new case', () => newCase()));
    const stepper = createStepper(n => openStep(n, true));

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
    for (const n of [2, 3, 4]) {
        scenarioFields[n] = caseField({name: 'scenarioId', label: 'Mock scenario', kind: 'select', options: []});
        optionRows[n] = el('div', {class: 'panel__options'}, Object.values(options[n]).map(control => control.node));
    }

    const panels = {};
    const modules = {};
    for (const step of STEPS) {
        panels[step.n] = el('section', {class: 'panel', id: 'step-panel-' + step.n, role: 'tabpanel', 'aria-labelledby': 'step-tab-' + step.n,
            hidden: step.n !== 1, tabindex: -1});
    }
    const work = el('div', {class: 'work'}, banner, stepper.node, Object.values(panels));
    const node = el('div', {class: 'workbench'}, caseColumn, work);

    function ctxFor(n) {
        return {
            request,
            carry(step) {
                const handed = journey.carry(step, caseKey(request()));
                return handed ? handed.carry : null;
            },
            handOn(step, carry) {
                journey.handOn(step, carry, caseKey(request()));
                if (step === 4 && carry && carry.claimId) {
                    const claim = options[4].claimId;
                    if (fillFromAnswer(claim, Array.isArray(carry.claimId) ? carry.claimId[0] : carry.claimId)) {
                        claim.set(claim.input.value, 'from step 3');
                    }
                }
            },
            option(name) {
                if (name === 'scenarioId') {
                    return app.isMock() && scenarioFields[n] ? scenarioFields[n].value() : '';
                }
                const control = options[n] ? options[n][name] : null;
                return control ? control.String(input.value || '').trim() : '';
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
            placeErrors,
            go(step, how) {
                openStep(step, true, how);
            },
            isMock: () => app.isMock(),
            visible: () => open === n && Boolean(node.isConnected),
            typedCode: () => service.typedCode(),
            blocked,
            readinessLine,
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
            crdScenarios: () => (app.isMock() && loaded.mockLists ? loaded.mockLists.crd : null)
        };
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

    /** A result arrived for step n. It updates that step's chip and never switches the view. */
    function resulted(n, envelope) {
        if (!envelope || typeof envelope !== 'object') {
            return;
        }
        const step = stepOfOperation(envelope.operation) || n;
        journey.resulted(step, envelope, caseKey(request()), app.epoch());
        remember(envelope);
        member.sentAs(envelope.wire && envelope.wire.memberId);
        if (step === 1 && envelope.nextStep && envelope.nextStep.carry) {
            fillFromStep1(envelope.nextStep.carry);
        }
        const chainId = journey.knownChain();
        if (chainId && window.location.hash.startsWith('#workbench')) {
            window.history.replaceState(null, '', '#workbench/' + encodeURIComponent(chainId));
        }
        drawStepper();
        readChain();
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
        if (focus) {
            panels[n].focus({preventScroll: false});
        }
    }

    /** The card of a result, with its next step wired to the step it leads to. */
    function card(envelope) {
        const kept = journey.result(stepOfOperation(envelope.operation) || 1);
        return resultCard(envelope, {
            context: 'workbench',
            stale: Boolean(kept && kept.epoch !== null && app.epoch() !== null && kept.epoch !== app.epoch()),
            onNext: action => next(action),
            nextBlocked: action => {
                const step = stepOfAction(action);
                return step === null ? null : blocked(OPERATION_OF_STEP[step]) || (journey.offered(step) ? null : withdrawnWords(step));
            }
        });
    }

    /** A next-step button was pressed: Continue opens its step (step 2 fetches at once; Submit sends nothing). */
    function next(action) {
        if (action === 'OPEN_CONFIG') {
            app.go('#configuration/environments');
            return;
        }
        if (action === 'UNLOCK_OR_SWITCH') {
            app.go('#configuration/production-writes');
            return;
        }
        const step = stepOfAction(action);
        if (step !== null) {
            openStep(step, true, {start: action === 'START_DTR' || action === 'CHECK_STATUS'});
        }
    }

    function handedLine(step) {
        const line = journey.handedLine(step, caseKey(request()));
        if (line.kind !== 'handed') {
            return el('p', {class: 'small muted handed'}, line.words);
        }
        return line.masked.length > 0 ? el('div', null, line.masked.map(words => maskedLine(words))) : null;
    }

    /** Each field error at its field, its fold opened; returns the names placed. */
    function placeErrors(fieldErrors) {
        const placed = [];
        for (const each of Array.isArray(fieldErrors) ? fieldErrors : []) {
            if (each && typeof each.field === 'string' && showField(each.field, each.message)) {
                placed.push(each.field);
            }
        }
        return placed;
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
            const said = el('p', {class: 'field__error'}, message);
            service.codeInput().closest('.field').append(said);
            service.codeInput().addEventListener('input', () => said.remove(), {once: true});
        }
        return service.show(name, message) || member.show(name, message) || provider.show(name, message);
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
        return el('p', {class: 'small readiness__line'}, firstSentence(sentence)[0], ' ',
            el('a', {href: '#configuration/environments'}, 'Open Configuration'));
    }

    function withdrawnWords(step) {
        return 'Step ' + step + ' was refused for a masked value; type the value, then run step ' + (step - 1) + ' again.';
    }

    function drawBanner() {
        const who = member.label();
        const by = provider.label();
        const codes = service.codesLabel();
        if (!who && !by && !codes) {
            fill(bannerText, 'Pick a member to begin.');
            return;
        }
        const dot = () => el('span', {class: 'banner__sep', 'aria-hidden': 'true'}, ' \u00b7 ');
        fill(bannerText, el('strong', null, who || 'no member'), dot(), by || 'no provider', dot(),
            el('span', {class: 'mono'}, codes || 'no code'));
    }

    /** "Start a new case": clears the case, every step's result, the journey and the chain id in the address. */
    function newCase() {
        member.clear();
        provider.clear();
        service.clear();
        for (const n of [2, 3, 4]) {
            for (const control of Object.values(options[n])) {
                control.clear();
            }
        }
        journey.reset();
        if (window.location.hash.startsWith('#workbench')) {
            window.history.replaceState(null, '', '#workbench');
        }
        for (const step of STEPS) {
            const module = modules[step.n];
            if (module && typeof module.reset === 'function') {
                guarded(() => module.reset());
            }
        }
        drawBanner();
        drawStepper();
        openStep(1, true);
        for (const module of Object.values(modules)) {
            if (module && typeof module.caseChanged === 'function') {
                guarded(() => module.caseChanged());
            }
        }
    }

    onCaseChange(reason => {
        drawBanner();
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
            const [words, crd, dtr, pas, members, providers, pasOptions, effective] = await Promise.all([
                vocabulary(),
                mock ? call('GET', '/api/crd/scenarios') : null,
                mock ? call('GET', '/api/dtr/scenarios') : null,
                mock ? call('GET', '/api/pas/scenarios') : null,
                call('GET', '/api/lists/members'),
                call('GET', '/api/lists/providers'),
                call('GET', '/api/pas/options'),
                call('GET', '/api/config/effective')
            ]);
            loaded.words = words;
            loaded.mockLists = mock && crd && crd.ok ? {crd: crd.data, dtr: dtr && dtr.ok ? dtr.data : null, pas: pas && pas.ok ? pas.data : null}
                : null;
            loaded.savedMembers = members.ok && Array.isArray(members.data.members) ? members.data.members : [];
            loaded.savedProviders = providers.ok && Array.isArray(providers.data.providers) ? providers.data.providers : [];
            loaded.pasOptions = pasOptions.ok ? pasOptions.data : null;
            loaded.effective = effective.ok ? effective.data : null;
            member.drawPicker();
            provider.drawPicker();
            service.drawOptions();
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
