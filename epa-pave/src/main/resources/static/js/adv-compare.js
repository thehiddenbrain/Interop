// Configuration > Advanced > Compare with a request that works: a CRD request the tester pastes
// or reads from a file, beside the one PAVE builds for the same case, every difference listed as
// the server words it, and both requests side by side with each difference marked in both.
//
// What the comparison says is the server's (POST /api/crd/compare): the headline, the groups and
// their order, what each element is, what each difference means, what was read out of the pasted
// request and what was not found, and the notes. This page decides only how it is laid out. It
// judges nothing: which of the two requests a service expects is not something a comparison can
// show, and the server's sentence saying so stays in view. Nothing is sent to the service and
// nothing is recorded; the pasted text stays in the page and nowhere else.

import {call} from './api.js';
import {app} from './app.js';
import {el, fill, clear, chip, button, fold, kv, field, setFieldError} from './dom.js';
import {errorPanel} from './fault.js';
import {readText} from './files.js';
import {lineMap} from './adv-json-lines.js';
import {bodyBox, sampleButton} from './adv-raw.js';
import {pageHead, section, part, during, none} from './cfg-parts.js';

// The server compares at most 2 MB (CrdRequestComparer), so a larger file is refused in the page.
const LARGEST = 2 * 1024 * 1024;
const EMPTY_WORDS = {string: 'empty text', array: 'empty list', object: 'empty object'};
const mono = text => el('span', {class: 'mono'}, text);

/**
 * One value of a difference: what one request holds at the element. Absent, an explicit null and
 * an empty text, list or object are each said in their own words, so none of them is a blank and
 * none looks like a value.
 */
export function requestValue(value, type) {
    if (!value || typeof value !== 'object' || value.presence === undefined || value.presence === 'ABSENT') {
        return chip('not in this request', {dashed: true});
    }
    if (value.presence === 'NULL') {
        return chip('null', {dashed: true});
    }
    if (value.presence === 'EMPTY') {
        return chip(type && EMPTY_WORDS[type] ? EMPTY_WORDS[type] : 'empty', {dashed: true});
    }
    if (value.presence !== 'VALUE') {
        return chip(String(value.presence), {dashed: true});
    }
    return mono(String(value.value));
}

/** Draws the page into root. route: the parts of the address after #configuration. */
export function mount(root, route) {
    let gone = false;
    let compared = false;
    const fields = new Map();

    const hook = el('select', {class: 'select', name: 'hook'},
        el('option', {value: 'order-sign'}, 'order-sign'), el('option', {value: 'order-select'}, 'order-select'));
    const memberId = el('input', {class: 'input mono', value: '', name: 'memberId', autocomplete: 'off', spellcheck: false, placeholder: 'S123456789'});
    const npi = el('input', {class: 'input mono', value: '', name: 'npi', autocomplete: 'off', spellcheck: false, inputMode: 'numeric'});
    const codes = el('input', {class: 'input mono', value: '', name: 'procedureCodes', autocomplete: 'off', spellcheck: false});
    const dateOfService = el('input', {class: 'input mono', value: '', name: 'dateOfService', autocomplete: 'off', placeholder: 'yyyy-MM-dd'});
    const place = (name, wrapper) => {
        fields.set(name, wrapper);
        return wrapper;
    };
    const caseFields = el('div', null,
        el('div', {class: 'field-row'},
            place('hook', field({label: 'Hook', input: hook})),
            place('member.memberId', field({label: 'Member ID', input: memberId}))),
        el('div', {class: 'field-row'},
            place('provider.npi', field({label: 'NPI', input: npi})),
            place('dateOfService', field({label: 'Date of service', input: dateOfService}))),
        place('procedureCodes', field({label: 'Procedure codes', input: codes, hint: 'Separated by commas, for example E0601, E0470.'})));

    const file = el('input', {type: 'file', name: 'file', accept: '.json,application/json,text/plain'});
    const fileSaid = el('p', {class: 'small', role: 'status'});
    const fileBusy = el('span', {class: 'muted small', role: 'status'});
    const readButton = button('Read the file', () => during(readButton, fileBusy, 'Reading the file', readFile), {small: true});
    const body = bodyBox('Your request', 'The CRD request that works, as JSON. Placeholders in it are filled in from the configuration.',
        () => markStale());
    fields.set('rawBody', body.wrapper);
    const sampleSaid = el('p', {class: 'small', role: 'status'});
    const sample = sampleButton(() => ({operation: hook.value === 'order-select' ? 'CRD_ORDER_SELECT' : 'CRD_ORDER_SIGN'}),
        body.box, sampleSaid, body.changed);
    const busy = el('span', {class: 'muted small', role: 'status'});
    const compareButton = button('Compare with PAVE\'s request', () => during(compareButton, busy,
        'Building PAVE\'s request and comparing', compare), {primary: true});
    const problem = el('div');
    const stale = el('p', {role: 'status'});
    const answer = el('div');
    const page = section(
        pageHead('Compare with a request that works', 'PAVE\'s CRD request beside yours, every difference as the server words it.'),
        el('p', {class: 'small muted'}, 'Nothing is sent to the service and nothing is recorded.'),
        part('The case PAVE builds its request for', el('p', {class: 'small muted'},
            'Left empty, a value is read out of your request where it can be, and the comparison says from where.'), caseFields),
        part('Your request',
            el('div', {class: 'inline-controls'}, file, readButton, fileBusy), fileSaid,
            body.wrapper, el('div', {class: 'inline-controls'}, sample), sampleSaid, body.tools),
        el('div', {class: 'form-foot'}, compareButton, busy),
        problem, stale, answer);
    root.append(page);
    for (const control of [hook, memberId, npi, codes, dateOfService]) {
        control.addEventListener('input', markStale);
        control.addEventListener('change', markStale);
    }
    const off = app.on('config', () => {
        if (compared) {
            fill(stale, el('strong', null, 'The configuration changed since this comparison. '), 'Compare again to see it under the one in force now.');
        }
    });

    function markStale() {
        if (compared && stale.childNodes.length === 0) {
            fill(stale, el('strong', null, 'The form changed since this comparison. '), 'Compare again to update it.');
        }
    }

    /** The chosen file is read now, through files.js, and put in the box; a refusal lets the input go. */
    async function readFile() {
        const chosen = file.files && file.files[0];
        if (!chosen) {
            fill(fileSaid, 'Choose a file first.');
            return;
        }
        const got = await readText(chosen, {maxBytes: LARGEST});
        if (gone) {
            return;
        }
        if (!got.ok) {
            file.value = '';
            fill(fileSaid, got.line);
            return;
        }
        body.box.value = got.text;
        body.changed();
        markStale();
        fill(fileSaid, 'Read ' + got.fileName + ' into the box.');
    }

    function payload() {
        const sent = {hook: hook.value, rawBody: String(body.box.value || ''), configEpoch: app.epoch()};
        if (memberId.value.trim() !== '') {
            sent.member = {memberId: memberId.value.trim()};
        }
        if (npi.value.trim() !== '') {
            sent.provider = {npi: npi.value.trim()};
        }
        const list = codes.value.split(',').map(each => each.trim()).filter(each => each !== '');
        if (list.length > 0) {
            sent.procedureCodes = list.map(code => ({code}));
        }
        if (dateOfService.value.trim() !== '') {
            sent.dateOfService = dateOfService.value.trim();
        }
        return sent;
    }

    async function compare() {
        for (const wrapper of fields.values()) {
            setFieldError(wrapper, '');
        }
        clear(problem);
        if (String(body.box.value || '').trim() === '') {
            // There is nothing to compare, and nothing is asked of the server.
            setFieldError(body.wrapper, 'Paste the request, or read it from a file, first.');
            body.box.focus();
            return;
        }
        const got = await call('POST', '/api/crd/compare', {body: payload()});
        if (gone) {
            return;
        }
        if (!got.ok) {
            const placed = [];
            for (const each of Array.isArray(got.error.fieldErrors) ? got.error.fieldErrors : []) {
                const key = fields.has(each.field) ? each.field : String(each.field).startsWith('procedureCodes') ? 'procedureCodes' : null;
                if (key) {
                    setFieldError(fields.get(key), each.message);
                    placed.push(each.field);
                }
            }
            fill(problem, errorPanel(got.error, {fieldsPlaced: placed}));
            if (compared) {
                fill(stale, el('strong', null, 'The latest Compare was refused, '), 'so the comparison below answers an earlier one.');
            }
            const first = placed.length > 0 ? page.querySelector('.field--error input, .field--error select, .field--error textarea') : null;
            if (first) {
                first.focus();
            }
            return;
        }
        compared = true;
        clear(stale);
        fill(answer, drawComparison(got.data || {}, value => {
            memberId.value = value;
            markStale();
            memberId.focus();
        }));
    }

    return {
        update() {},
        unmount() {
            gone = true;
            off();
            page.remove();
        }
    };
}

/** One comparison, as the server answered it. useMemberId(value) puts a candidate in the Member ID field. */
export function drawComparison(answer, useMemberId) {
    const differences = answer.comparable === true && Array.isArray(answer.differences) ? answer.differences : [];
    const notes = Array.isArray(answer.notes) ? answer.notes : [];
    const hookDifference = differences.find(each => each.toolPointer === '/hook' && each.yourPointer === '/hook');
    const docs = documents(answer, differences);
    return part('The comparison',
        el('p', {class: 'verdict__headline'}, answer.headline || ''),
        el('p', {class: 'small'}, 'Compared as it stood when Compare was pressed. ', el('strong', null, 'Nothing was sent and nothing was recorded.')),
        answer.caveat ? el('p', {class: 'masked-reading'}, answer.caveat) : null,
        answer.comparable === true ? null : el('p', {role: 'alert'}, el('strong', null, answer.whyNot || '')),
        answer.hook && hookDifference
            ? el('p', null, 'Your request is for the hook ', requestValue(hookDifference.yourValue), ', and PAVE built its request for the hook ',
                mono(answer.hook), '. These are requests for two different hooks.')
            : answer.hook ? el('p', {class: 'small'}, 'PAVE built its request for the hook ', mono(answer.hook), '.') : null,
        notes.length > 0 ? el('ul', {class: 'notes'}, notes.map(note => el('li', null, note))) : null,
        casePart(answer, useMemberId),
        docs.node,
        answer.comparable === true ? differenceList(answer, differences, docs) : null);
}

/* ---------- what was read out of the pasted request ---------- */

function casePart(answer, useMemberId) {
    const sources = Array.isArray(answer.caseSource) ? answer.caseSource : [];
    const notFound = Array.isArray(answer.notFound) ? answer.notFound : [];
    const fromYours = sources.filter(source => source.source === 'YOUR_REQUEST').length;
    return el('div', null,
        sources.length > 0 ? fold({summary: typeof answer.toolBody === 'string' ? 'The case PAVE built its request for'
            : 'The case as far as it could be put together', hint: (sources.length === 1 ? '1 value, ' : sources.length + ' values, ') + fromYours + ' read from your request',
        body: () => kv(sources.map(source => ({key: source.label || source.field, value: [requestValue(source.value),
            el('span', {class: 'kv__display'}, (source.sourceLabel || source.source || '') + (source.path ? ', at ' + source.path : '')),
            source.note ? el('div', {class: 'small muted'}, source.note) : null]})))})
            : el('p', {class: 'small muted'}, 'The comparison names no value of the case.'),
        notFound.length > 0 ? fold({summary: 'Looked for in your request and not found', hint: String(notFound.length), open: notFound.length <= 4,
            body: () => el('ul', {class: 'notes'}, notFound.map(entry => el('li', null,
                el('strong', null, entry.label || entry.field || ''),
                entry.lookedIn ? el('div', {class: 'small'}, 'Looked for: ', entry.lookedIn) : null,
                entry.note ? el('div', {class: 'small muted'}, entry.note) : null,
                Array.isArray(entry.candidates) && entry.candidates.length > 0 ? candidates(entry, useMemberId) : null)))}) : null);
}

/** Values that disagree with each other, each with its place; none was used, so the tester chooses. */
function candidates(entry, useMemberId) {
    const isMemberId = entry.field === 'member.memberId';
    return el('div', null,
        el('p', {class: 'small'}, 'These disagree, so none was used:'),
        kv(entry.candidates.map(candidate => ({key: mono(candidate.path || ''), value: [requestValue(candidate.value),
            isMemberId && candidate.value && candidate.value.presence === 'VALUE'
                ? [' ', el('button', {type: 'button', class: 'btn btn--small btn--quiet', 'aria-label': 'Use the member ID read from ' + candidate.path,
                    on: {click: () => useMemberId(String(candidate.value.value))}}, 'Use this member ID')] : null]}))));
}

/* ---------- the differences ---------- */

function differenceList(answer, differences, docs) {
    const counts = Array.isArray(answer.counts) ? answer.counts : [];
    const known = new Set(counts.map(group => group.group));
    let number = 0;
    const row = (difference, index) => {
        number += 1;
        const shownNumber = number;
        return el('div', {class: 'panel__section', dataset: {diff: String(index)}},
            el('p', null, el('strong', null, shownNumber + '. '), mono(difference.path || ''), ' ',
                chip(difference.kindLabel || difference.kind || '', {tone: 'neutral'}), ' ',
                button('Show in both requests', () => docs.choose(index, shownNumber), {small: true, quiet: true})),
            el('p', null, difference.what || chip('no description', {dashed: true})),
            kv([
                {key: 'PAVE\'s request', value: requestValue(difference.toolValue, difference.toolType)},
                {key: 'Your request', value: requestValue(difference.yourValue, difference.yourType)}
            ]),
            difference.explanation ? el('p', {class: 'small'}, difference.explanation) : null);
    };
    const groups = counts.map(group => {
        const members = differences.map((difference, index) => ({difference, index})).filter(entry => entry.difference.group === group.group);
        return fold({summary: group.label || group.group, hint: members.length === 1 ? '1 difference' : members.length + ' differences',
            open: group.startsOpen !== false && members.length > 0,
            body: el('div', null, group.description ? el('p', {class: 'small muted'}, group.description) : null,
                members.length === 0 ? el('p', {class: 'small'}, 'None.') : el('div', null, members.map(entry => row(entry.difference, entry.index))))});
    });
    // A difference in a group the server did not count is still listed: nothing is dropped.
    const uncounted = differences.map((difference, index) => ({difference, index})).filter(entry => !known.has(entry.difference.group));
    return el('div', null,
        differences.length === 0 ? el('p', null, 'There is nothing to list.') : null,
        groups,
        uncounted.length > 0 ? fold({summary: 'In a group this page does not know', hint: String(uncounted.length), open: true,
            body: el('div', null, uncounted.map(entry => row(entry.difference, entry.index)))}) : null);
}

/* ---------- the two requests ---------- */

/**
 * The two requests side by side as numbered lines, every difference marked on the lines it covers
 * in each. Choosing a difference marks it in bold and brings its lines into view in both panes.
 */
function documents(answer, differences) {
    const tool = pane('PAVE\'s request', answer.toolBody, answer.comparable === true ? null
        : 'PAVE did not build its request, so there is nothing to show on this side.');
    const yours = pane('Your request', answer.yourBody, null);
    differences.forEach((difference, index) => {
        tool.mark(difference.toolPointer, index);
        yours.mark(difference.yourPointer, index);
    });
    const where = el('p', {class: 'small', role: 'status'});
    const node = el('div', null,
        el('p', {class: 'notes__title'}, 'The two requests'),
        answer.maskedNote ? el('p', {class: 'small masked-reading'}, answer.maskedNote) : null,
        where,
        el('div', {class: 'wire'}, tool.node, yours.node),
        differences.length > 0 ? el('p', {class: 'small muted'}, 'A highlighted line differs; the difference chosen is also in bold.') : null);
    return {
        node,
        choose(index, number) {
            const difference = differences[index];
            const inTool = tool.choose(index, difference.toolPointer);
            const inYours = yours.choose(index, difference.yourPointer);
            fill(where, el('strong', null, 'Difference ' + number + ' of ' + differences.length + ': '), mono(difference.path || ''),
                inTool ? null : ' Not in PAVE\'s request.', inYours ? null : ' Not in your request.');
            if (typeof node.scrollIntoView === 'function') {
                node.scrollIntoView({block: 'nearest'});
            }
        }
    };
}

/** One request as numbered lines in a pane of its own. */
function pane(title, text, missing) {
    const has = typeof text === 'string';
    const pointers = has ? lineMap(text) : new Map();
    const lines = [];
    const block = el('pre', {class: 'json', tabindex: 0, 'aria-label': title + ', line by line'});
    if (has) {
        const all = text.split('\n');
        const width = String(all.length).length;
        all.forEach((content, index) => {
            const line = el('span', {dataset: {line: String(index)}}, String(index + 1).padStart(width, ' ') + '  ' + content);
            lines.push(line);
            block.append(line, '\n');
        });
    }
    const marks = new Map();    // line -> difference indexes
    let chosen = [];
    return {
        node: el('div', null, el('p', {class: 'notes__title'}, title),
            has ? block : el('p', {class: 'small muted'}, missing || 'Nothing to show.')),
        mark(pointer, index) {
            const range = typeof pointer === 'string' ? pointers.get(pointer) : null;
            if (!range) {
                return;
            }
            for (let at = range.start; at <= range.end && at < lines.length; at += 1) {
                if (!marks.has(at)) {
                    marks.set(at, []);
                    const line = lines[at];
                    const text = line.textContent;
                    line.textContent = '';
                    line.append(el('mark', null, text));
                }
                marks.get(at).push(index);
            }
        },
        /** Marks the difference's lines in bold and scrolls the pane to them. Returns false when this request does not have it. */
        choose(index, pointer) {
            for (const line of chosen) {
                const strong = line.querySelector('strong');
                if (strong) {
                    const text = strong.textContent;
                    line.querySelector('mark').textContent = text;
                }
            }
            chosen = lines.filter((line, at) => (marks.get(at) || []).includes(index));
            for (const line of chosen) {
                const mark = line.querySelector('mark');
                const text = mark.textContent;
                mark.textContent = '';
                mark.append(el('strong', null, text));
            }
            const range = typeof pointer === 'string' ? pointers.get(pointer) : null;
            if (range && lines[range.start] && typeof lines[range.start].offsetTop === 'number') {
                block.scrollTop = Math.max(0, lines[range.start].offsetTop - block.offsetTop - block.clientHeight / 4);
            }
            return Boolean(range);
        }
    };
}
