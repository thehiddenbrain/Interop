// Step 3 of the Workbench, Submit (plan 3.2, step 3; section 4): what goes, in at most three lines
// of chips; what the server says is still missing, as chips that open their field; the files to
// attach; and "Send PAS request". The request follows the environment's PAS guide (2.0.1 unless
// it chooses 2.2.1) and is built by the server; this page only says what it carries.
//
// Sending stays possible whatever is missing: an incomplete request is a negative test, said before
// it is sent (R-39). On an environment of kind prod the server's own words on writes stand beside
// the button, and a refusal is the production guardrail's result, drawn as every result is. Those
// words come from /api/mode as the frame reads it, heard through api.js; this module does not
// import the frame, so it runs on its own beside a stand-in for the page.

import {call, watch} from './api.js';
import {el, chip, fill, clear, button} from './dom.js';
import {vocabulary} from './words.js';
import {sizeText} from './files.js';
import {panelHead, busyLine, sendRun, failurePanel} from './wb-run.js';
import {showPreview} from './wb-preview.js';
import {createAttachments} from './wb-attachments.js';

// The page asks the server for the gaps this long after the last change of the case.
const QUIET_MS = 500;

// A gap chip shows the start of the server's line; the whole line is its title.
const GAP_WORDS = 40;

/** The number of questions a QuestionnaireResponse (JSON text) holds an answer for, walked without recursion; null when it cannot be read. */
export function answerCount(json) {
    let response;
    try {
        response = typeof json === 'string' ? JSON.parse(json) : json;
    } catch (notJson) {
        return null;
    }
    if (!response || typeof response !== 'object') {
        return null;
    }
    let count = 0;
    const stack = [Array.isArray(response.item) ? response.item : []];
    while (stack.length > 0) {
        for (const item of stack.pop()) {
            if (!item || typeof item !== 'object') {
                continue;
            }
            if (Array.isArray(item.answer) && item.answer.length > 0) {
                count++;
                for (const answer of item.answer) {
                    if (answer && Array.isArray(answer.item)) {
                        stack.push(answer.item);
                    }
                }
            }
            if (Array.isArray(item.item)) {
                stack.push(item.item);
            }
        }
    }
    return count;
}

/** What a gap chip says: the start of the server's line, up to its first colon or full stop. */
/**
 * What a case lacks before a preview of it is worth asking for: no member ID, no NPI, no procedure
 * code. Each as a gap {field, line} the step draws as it draws the server's.
 */
export function caseGaps(request) {
    const given = value => typeof value === 'string' && value.trim() !== '';
    const gaps = [];
    if (!given(request && request.member ? request.member.memberId : null)) {
        gaps.push({field: 'member.memberId', line: 'Member ID: type one in the Member card.'});
    }
    if (!given(request && request.provider ? request.provider.npi : null)) {
        gaps.push({field: 'provider.npi', line: 'NPI: type one in the Provider card.'});
    }
    const codes = request && Array.isArray(request.procedureCodes) ? request.procedureCodes : [];
    if (!codes.some(code => code && given(code.code))) {
        gaps.push({field: 'procedureCodes', line: 'Procedure code: add one in the Service card.'});
    }
    return gaps;
}

export function gapWords(line) {
    const text = String(line || '');
    let end = text.length;
    for (let i = 0; i < text.length; i++) {
        if (text[i] === ':' || text[i] === '.') {
            end = i;
            break;
        }
    }
    const shown = text.slice(0, end).trim();
    return shown.length > GAP_WORDS ? shown.slice(0, GAP_WORDS) + '...' : shown;
}

/**
 * The submit request: the case as PAS reads it, the step's options, the files, and what step 2
 * handed on. The questionnaire response goes only while "Include it" is ticked; a scenario on mock only.
 */
export function submitBody(request, own, carry, attachments) {
    const body = {
        member: request.member,
        memberOverrides: request.memberOverrides,
        provider: request.provider,
        providerOverrides: request.providerOverrides,
        procedureCodes: request.procedureCodes,
        chainId: request.chainId,
        configEpoch: request.configEpoch
    };
    for (const name of ['dateOfService', 'placeOfService', 'levelOfService', 'certificationType', 'requestType', 'serviceType',
        'servicingProvider', 'testerRef', 'note']) {
        if (request[name] !== undefined && request[name] !== null && request[name] !== '') {
            body[name] = request[name];
        }
    }
    // The Service card gives diagnosis codes as typed text; PAS reads each as a code.
    if (Array.isArray(request.diagnosisCodes) && request.diagnosisCodes.length > 0) {
        body.diagnosisCodes = request.diagnosisCodes.map(each => (typeof each === 'string' ? {code: each} : each));
    }
    if (own.claimId) {
        body.claimId = own.claimId;
    }
    if (own.expected) {
        body.expected = own.expected;
    }
    if (own.mock && own.scenarioId) {
        body.scenarioId = own.scenarioId;
    }
    if (carry && Object.keys(carry).length > 0) {
        const sent = {...carry};
        if (!own.includeResponse) {
            delete sent.questionnaireResponse;
        }
        if (Object.keys(sent).length > 0) {
            body.carry = sent;
        }
    }
    if (Array.isArray(attachments) && attachments.length > 0) {
        body.attachments = attachments;
    }
    return body;
}

/** A line of chips with a space between each, so they read apart whatever the stylesheet gives the line. */
function chipLine(nodes) {
    const parts = [];
    nodes.forEach((node, index) => parts.push(index > 0 ? ' ' : null, node));
    return el('div', {class: 'goes__line'}, parts);
}

/** mount(root, ctx), as every step module (plan 9.1). Opening the step sends nothing (R-27). */
export function mount(root, ctx) {
    const expect = el('select', {class: 'select select--expect', id: 'pas-expect'}, el('option', {value: ''}, 'Not set'));
    const previewButton = el('button', {type: 'button', class: 'btn', on: {click: () => preview()}}, 'Preview');
    const sendButton = el('button', {type: 'button', class: 'btn btn--primary', on: {click: () => send()}}, 'Send PAS request');
    const toggle = typeof ctx.optionsToggle === 'function' ? ctx.optionsToggle() : null;
    const optionsRow = typeof ctx.optionsRow === 'function' ? ctx.optionsRow() : null;
    const writes = el('div', {class: 'submit-writes', hidden: true});
    const readiness = el('div', {class: 'readiness'});
    const goes = el('div', {class: 'panel__section goes'});
    const gaps = el('div', {class: 'gaps'});
    const include = el('input', {type: 'checkbox', id: 'pas-include', checked: true});
    let limits = null;
    const files = createAttachments({limits: () => limits, changed: () => drawGoes()});
    const busy = busyLine();
    const refusal = el('div', {class: 'step-refusal'});
    const result = el('div', {class: 'step-result'});
    root.append(...[
        panelHead({title: 'Submit', tag: 'PAS', lede: 'Send the prior authorization request.', id: 'step-title-3',
            controls: [el('label', {class: 'control-label', for: 'pas-expect'}, 'Expect'), expect, toggle, previewButton, sendButton]
                .filter(Boolean)}),
        optionsRow, writes, readiness, goes, gaps, files.node, busy.node, refusal, result].filter(Boolean));

    let latestPreview = null;
    let timer = null;
    let asked = 0;

    function own() {
        return {
            expected: expect.value,
            claimId: ctx.option('claimId'),
            mock: ctx.isMock(),
            scenarioId: ctx.isMock() ? ctx.option('scenarioId') : '',
            includeResponse: include.checked
        };
    }

    function drawWords(words) {
        const before = expect.value;
        const outcomes = words && words.outcomes && Array.isArray(words.outcomes.PAS) ? words.outcomes.PAS : [];
        fill(expect, el('option', {value: ''}, 'Not set'),
            outcomes.filter(each => each.short).map(each => el('option', {value: each.code}, each.short)));
        expect.value = before;
    }

    function drawReadiness() {
        const blocked = ctx.blocked('PAS_SUBMIT');
        sendButton.disabled = Boolean(blocked);
        sendButton.title = blocked || '';
        fill(readiness, blocked ? ctx.readinessLine(blocked) : '');
    }

    /** On kind prod: the server's sentence on writes and the way to Production writes. The button stays. */
    function drawWrites() {
        const access = mode ? mode.writeAccess : null;
        // The box is framed by the stylesheet, so it is hidden, not only emptied, off kind prod.
        if (!access || access.production !== true) {
            clear(writes);
            writes.hidden = true;
            return;
        }
        writes.hidden = false;
        fill(writes, el('p', {class: 'line line--prod small'}, access.reason || '', ' ',
            el('a', {class: 'line__link', href: '#configuration/production-writes'}, 'Production writes')));
    }

    /** "What goes": at most three lines of chips, each a short label and its value (M22). */
    function drawGoes() {
        const request = ctx.request();
        const member = request.member || {};
        const provider = request.provider || {};
        const sentAs = latestPreview && latestPreview.memberId ? latestPreview.memberId : null;
        const line1 = [];
        const memberId = sentAs && sentAs.sent ? sentAs.sent : member.memberId;
        if (memberId) {
            line1.push(chip('member ' + memberId, {mono: true}));
            if (sentAs && sentAs.rule) {
                line1.push(chip('rule: ' + sentAs.rule));
            }
        } else {
            line1.push(chip('no member', {dashed: true}));
        }
        if (provider.vendorPractitionerId) {
            line1.push(chip('id ' + provider.vendorPractitionerId, {mono: true}));
        } else if (provider.npi) {
            line1.push(chip('NPI ' + provider.npi, {mono: true}));
        } else {
            line1.push(chip('no provider', {dashed: true}));
        }
        if (provider.phone) {
            line1.push(chip('phone'));
        }
        if (provider.fax) {
            line1.push(chip('fax'));
        }
        const servicing = request.servicingProvider;
        if (servicing && (servicing.npi || servicing.vendorPractitionerId)) {
            line1.push(chip('servicing ' + (servicing.npi || servicing.vendorPractitionerId), {mono: true}));
        }

        const line2 = [];
        for (const code of Array.isArray(request.procedureCodes) ? request.procedureCodes : []) {
            const amount = code.quantity !== undefined && code.quantity !== null ? ' x' + code.quantity + (code.unit ? ' ' + code.unit : '') : '';
            line2.push(chip(code.code + amount, {mono: true}));
        }
        if (line2.length === 0) {
            line2.push(chip('no code', {dashed: true}));
        }
        if (request.dateOfService) {
            line2.push(chip(request.dateOfService, {mono: true}));
        }
        const labelled = [['placeOfService', 'place'], ['levelOfService', 'level'], ['certificationType', 'certification'],
            ['requestType', 'request'], ['serviceType', 'service']];
        for (const [name, label] of labelled) {
            if (request[name]) {
                line2.push(chip(label + ' ' + request[name]));
            }
        }

        const line3 = [];
        const carry = ctx.carry(3);
        const response = carry && Array.isArray(carry.questionnaireResponse) ? carry.questionnaireResponse[0] : null;
        if (response) {
            const answers = answerCount(response);
            line3.push(chip('questionnaire response' + (answers === null ? '' : ', ' + answers + (answers === 1 ? ' answer' : ' answers'))),
                el('label', {class: 'small', for: 'pas-include'}, include, ' Include it'));
        } else {
            const handed = ctx.handedLine(3);
            if (handed) {
                line3.push(handed);
            }
        }
        const count = files.count();
        line3.push(count > 0 ? chip(count + (count === 1 ? ' file, ' : ' files, ') + sizeText(files.bytes())) : chip('no files', {dashed: true}));
        if (Array.isArray(request.diagnosisCodes) && request.diagnosisCodes.length > 0) {
            line3.push(chip('dx ' + request.diagnosisCodes.map(each => (typeof each === 'string' ? each : each.code)).join(', '), {mono: true}));
        }
        fill(goes, el('p', {class: 'section-label'}, 'What goes'), chipLine(line1), chipLine(line2), chipLine(line3));
    }

    /** "To complete before sending (<n>)": the server's gaps, each a dashed chip that opens its field. */
    function drawGaps() {
        const list = latestPreview && Array.isArray(latestPreview.toComplete) ? latestPreview.toComplete : [];
        if (list.length === 0) {
            clear(gaps);
            return;
        }
        fill(gaps, el('p', {class: 'small'}, 'To complete before sending (' + list.length + ')'),
            chipLine(list.map(gap => el('button', {type: 'button', class: 'chip chip--none', title: gap.line,
                on: {click: () => openGap(gap.field)}}, el('span', {class: 'chip__text'}, gapWords(gap.line))))));
    }

    function openGap(field) {
        const name = String(field || '');
        if (name.startsWith('values.')) {
            const environment = (mode && mode.environment) || {};
            const id = environment.id || environment.name;
            globalThis.location.hash = id ? '#configuration/environments/' + encodeURIComponent(id) + '/values' : '#configuration/environments';
            return;
        }
        ctx.showField(name);
    }

    /** Asks the server what the request lacks; nothing is sent and nothing is recorded. Files are not read for it. */
    async function askGaps() {
        const mine = ++asked;
        const missing = caseGaps(ctx.request());
        if (missing.length > 0) {
            // The server would only refuse a case with no member, provider or code: say it here, ask nothing.
            latestPreview = {toComplete: missing};
            drawGoes();
            drawGaps();
            return;
        }
        const answer = await call('POST', '/api/pas/submit/preview', {body: submitBody(ctx.request(), own(), ctx.carry(3), [])});
        if (mine !== asked) {
            return;
        }
        latestPreview = answer.ok ? answer.data : null;
        drawGoes();
        drawGaps();
    }

    function askSoon() {
        clearTimeout(timer);
        timer = setTimeout(() => askGaps(), QUIET_MS);
    }

    async function withFiles() {
        const read = await files.read();
        if (!read.ok) {
            fill(refusal, el('p', {class: 'field__error'}, read.line, ' Nothing was sent.'));
            return null;
        }
        return read.attachments;
    }

    async function send() {
        const attachments = await withFiles();
        if (attachments === null) {
            return;
        }
        await sendRun({
            path: '/api/pas/submit', body: submitBody(ctx.request(), own(), ctx.carry(3), attachments), busy,
            busyWords: 'Sending the request...', buttons: [sendButton, previewButton], typed: ctx.typedCode(), ctx,
            onResult: envelope => {
                clear(refusal);
                // The frame hands step 4 its claim from this envelope (through its check for a mask), so
                // this step hands nothing on of its own and never undoes the frame's hand-over.
                ctx.resulted(envelope);
                fill(result, ctx.card(envelope));
            },
            onRefused: (error, placed) => {
                ctx.refused(3, error);
                const all = (error.fieldErrors || []).length > 0 && (error.fieldErrors || []).every(each => placed.includes(each.field));
                fill(refusal, all ? null : failurePanel(error, placed, ctx.labelOf));
            },
            onFailed: error => fill(refusal, failurePanel(error))
        });
    }

    async function preview() {
        const attachments = await withFiles();
        if (attachments === null) {
            return;
        }
        clear(refusal);
        await showPreview('/api/pas/submit/preview', submitBody(ctx.request(), own(), ctx.carry(3), attachments),
            error => ctx.placeErrors((error && error.fieldErrors) || []), ctx.labelOf);
    }

    include.addEventListener('change', () => askSoon());
    let mode = null;
    // Every answer of /api/mode the frame reads (a write unlocked, an environment made active) redraws the box on writes.
    const offWatch = watch(heard => {
        if (heard && heard.path === '/api/mode' && heard.answer && heard.answer.ok) {
            mode = heard.answer.data;
            drawWrites();
        }
    });

    async function readOptions() {
        const answer = await call('GET', '/api/pas/options');
        limits = answer.ok && answer.data && answer.data.attachments ? answer.data.attachments : null;
        files.redraw();
    }

    vocabulary().then(drawWords, () => null);
    readOptions();
    call('GET', '/api/mode');
    drawReadiness();
    drawWrites();
    drawGoes();

    return {
        show() {
            drawReadiness();
            drawWrites();
            drawGoes();
            const latest = ctx.latest(3);
            if (latest && !result.firstChild) {
                fill(result, ctx.card(latest));
            }
            askGaps();
        },
        hide() {
            clearTimeout(timer);
        },
        caseChanged() {
            drawGoes();
            if (ctx.visible()) {
                askSoon();
            }
        },
        /** The step's latest result read back after a reload: drawn as one that just arrived; the files sent with it are not read back. */
        restored(envelope) {
            if (!envelope || typeof envelope !== 'object') {
                return;
            }
            clear(refusal);
            fill(result, ctx.card(envelope));
        },
        /** Start a new case: the files, the gaps and the result go. */
        reset() {
            files.clear();
            latestPreview = null;
            include.checked = true;
            clear(refusal);
            clear(result);
            clear(gaps);
            drawGoes();
        },
        unmount() {
            clearTimeout(timer);
            busy.stop();
            offWatch();
        },
        redraw(words) {
            drawWords(words);
            drawReadiness();
            drawWrites();
            readOptions();
        }
    };
}
