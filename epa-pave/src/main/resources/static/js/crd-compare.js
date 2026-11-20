// A pasted CRD request beside the one this tool builds for the same case, every difference
// listed and explained, and both documents side by side with each difference marked in both.
//
// What the comparison says is the server's: the headline, the groups and their order, what each
// element is, what each difference means, what was read out of the pasted request and what was
// not found, and the notes. This file decides only how it is laid out and how a tester moves
// through it. It judges nothing: which of the two requests a service expects is not something
// the comparison can show, and the server's sentence saying so stays in view.
//
// Nothing is sent to the service and nothing is recorded. The pasted text and everything read
// out of it stay on the screen: nothing here is kept in browser storage or outlives the page.

import * as api from './api.js';
import {el, fill, clear, button, code, heading, table} from './dom.js';
import {clearFieldErrors, markFieldErrors, formProblem} from './forms.js';
import {errorPanel} from './result.js';
import {lineMap, ancestors} from './crd-json-lines.js';

// The two places a value of the case can come from. The comparison sends the source by name
// only; these are the words for it. A source this page does not know is shown by its name.
const SOURCES = {FORM: 'The form', YOUR_REQUEST: 'Your request'};

// The groups that are open when a comparison is drawn. The first group the server lists is the
// answer to the tester's question and is always open. Differences that come with every call are
// not findings, so their group starts closed.
const STARTS_CLOSED = new Set(['EVERY_CALL']);

/**
 * spec.app            the application object
 * spec.bench          the workbench of the CRD tab
 * spec.ownRequest     what bench.useOwnRequest returned: the box the request is pasted into
 * spec.buildRequest() the run request the tab would post now, with rawBody when the box is chosen
 * spec.fill(answer)   fills the form from what the comparison read out of the pasted request,
 *                     and returns {filled: [...], leftAlone: [...]} in words
 * spec.useBuilt()     switches the tab to the request this tool builds from the form
 * spec.slot           the place in the readout where the comparison is drawn
 * spec.track(...)     marks controls that cannot be used while a request is on its way
 */
export function createComparison(spec) {
    const {app, bench, ownRequest, slot} = spec;
    let busy = false;
    let drawn = null;
    // Counts the questions about the vendor's sample, so only the answer to the latest is drawn.
    let sampleAsked = 0;

    const compareButton = button('Compare with this tool\'s request', () => compare('box'), {class: 'button--quiet'});
    const sampleButton = button('Compare the sample with this tool\'s request', () => compare('sample'), {class: 'button--quiet'});
    const loadSample = button('Put the vendor\'s sample in the box', putSampleInBox, {class: 'button--text'});
    const said = el('p', {class: 'small crd-cmp-bar__said', role: 'status', aria: {live: 'polite'}});
    const bar = el('div', {class: 'crd-cmp-bar', hidden: true},
        el('div', {class: 'button-row'}, compareButton),
        el('p', {class: 'small quiet'}, 'Builds this tool\'s request for the same case and lists where the two differ, with what each '
            + 'difference means. Nothing is sent to the service and nothing is recorded.'));
    // The vendor's sample is offered whichever request is chosen: it is compared as the collection
    // holds it, without being put in the box. Whether there is one is said before anything is pressed.
    const sampleState = el('p', {class: 'small'});
    const sampleControls = el('div', {class: 'crd-cmp-bar__sample', hidden: true}, sampleButton, loadSample);
    const samplePart = el('div', {class: 'crd-cmp-sample'},
        el('p', {class: 'crd-cmp-sample__title'}, 'The vendor\'s sample'), sampleState, sampleControls, said);
    spec.track(compareButton, sampleButton, loadSample);

    // The comparison belongs with the box, so it is placed straight after it.
    ownRequest.node.after(bar, samplePart);
    const showBar = () => {
        bar.hidden = !ownRequest.active();
    };
    ownRequest.node.addEventListener('change', showBar);
    showBar();
    // Each hook has its own collection request, so the sample on offer follows the hook on the form.
    bench.form.addEventListener('change', event => {
        if (event.target.name === 'crd-hook') {
            refreshSample();
        }
    });
    refreshSample();

    /** Asks the server for the comparison: of the text in the box, or of the vendor's sample. */
    async function compare(what) {
        if (busy) {
            return;
        }
        clear(said);
        clear(bench.problem);
        clearFieldErrors(bench.form);
        let request = spec.buildRequest();
        let about;
        if (what === 'box') {
            if (ownRequest.text().trim() === '') {
                // There is nothing to compare, and nothing is asked of the server.
                const nothing = {title: 'There is nothing in the box to compare.', action: 'Paste a request into the box first.'};
                fill(bench.problem, formProblem(nothing, markFieldErrors([ownRequest.field],
                    [{field: 'rawBody', message: 'Paste the request body here.'}])));
                bench.say(nothing.title);
                return;
            }
            about = {kind: 'box', words: 'the request in the box'};
        } else {
            const sample = await vendorSample();
            if (!sample) {
                return;
            }
            request = {...request, rawBody: sample.body};
            about = {kind: 'sample', words: 'the vendor\'s sample, from the collection request ' + sample.name, name: sample.name};
        }
        setBusy(true);
        bench.say('Building this tool\'s request and comparing it with ' + about.words + '. Nothing is sent.');
        let answer;
        try {
            answer = await api.postJson('/api/crd/compare', request);
        } finally {
            setBusy(false);
        }
        if (answer.kind === 'ok') {
            draw(answer.body, about);
            return;
        }
        bench.say(answer.body.title);
        if (answer.kind === 'form') {
            fill(bench.problem, formProblem(answer.body, markFieldErrors([bench.form], answer.body.fieldErrors)));
            return;
        }
        fill(slot, el('section', {class: 'panel'}, heading(2, 'The comparison could not be made'), errorPanel(answer.body)));
        if (answer.kind === 'conflict') {
            app.refreshMode();
        }
    }

    function setBusy(state) {
        busy = state;
        for (const control of [compareButton, sampleButton, loadSample]) {
            control.disabled = state;
        }
    }

    /**
     * The sample body of the request the active collection maps to the hook on the form, as the
     * collection holds it: {sample: {name, body, scripted}}, or {reason} in words when there is none.
     */
    async function readSample() {
        const operation = spec.operation();
        const answer = await api.postJson('/api/config/resolve', {operation});
        if (answer.kind !== 'ok') {
            return {reason: [el('strong', null, 'The vendor\'s sample could not be read. '), answer.body.title, ' ', answer.body.action || '']};
        }
        const resolved = answer.body;
        const named = resolved.label || operation;
        if (!resolved.requestName) {
            return {reason: ['No collection request is mapped to ', named, ', so there is no vendor\'s sample to compare. ',
                'A collection is uploaded and mapped on the Config tab. ', button('Open the Config tab', () => app.navigate('config'),
                    {class: 'button--text'})]};
        }
        if (typeof resolved.bodyTemplate !== 'string' || resolved.bodyTemplate.trim() === '') {
            return {reason: ['The collection request ', el('strong', null, resolved.requestName), ', mapped to ', named,
                ', has no sample body to compare.']};
        }
        return {sample: {name: resolved.requestName, body: resolved.bodyTemplate, scripted: resolved.scripted, operation: named}};
    }

    /** Says whether there is a sample for the hook on the form, and offers it only when there is. */
    async function refreshSample() {
        sampleAsked += 1;
        const asked = sampleAsked;
        const found = await readSample();
        if (asked !== sampleAsked) {
            // The hook or the configuration changed while this was on its way: a later answer is drawn.
            return;
        }
        sampleControls.hidden = !found.sample;
        clear(said);
        fill(sampleState, found.sample
            ? ['The collection request ', el('strong', null, found.sample.name), ' is mapped to ', found.sample.operation,
                ' and has a sample body.', found.sample.scripted ? ' It carries a pre-request script, which this tool does not run.' : null]
            : found.reason);
    }

    /** The sample as it stands now, or null with the reason said. */
    async function vendorSample() {
        bench.say('Reading the vendor\'s sample from the collection.');
        const found = await readSample();
        if (!found.sample) {
            fill(said, found.reason);
            bench.say('There is no vendor\'s sample to use.');
            return null;
        }
        return found.sample;
    }

    /** Puts the sample, exactly as the collection holds it, into the box, where it can be edited and sent. */
    async function putSampleInBox() {
        const sample = await vendorSample();
        if (!sample) {
            return;
        }
        if (!ownRequest.active()) {
            // The box is where a request of the tester's own is written, so that is chosen, as the tester would choose it.
            ownRequest.node.querySelector('input[type="radio"][value="own"]').click();
        }
        const box = ownRequest.field.querySelector('textarea');
        box.value = sample.body;
        // The box hears of it as it hears of typing, so it says again whether the text is valid JSON.
        box.dispatchEvent(new Event('input', {bubbles: true}));
        fill(said, 'The sample of the collection request ', el('strong', null, sample.name),
            ' is in the box, exactly as the collection holds it. Its placeholders are filled in when it is compared or sent.');
        bench.say('The vendor\'s sample is in the box.');
        ownRequest.focus();
    }

    /* ---------- the comparison ---------- */

    function draw(answer, about) {
        drawn = drawComparison(answer, about, {
            close: () => {
                clear(slot);
                drawn = null;
                bench.say('The comparison is closed.');
            },
            fill: () => spec.fill(answer),
            useBuilt: spec.useBuilt,
            useMemberId: value => spec.useMemberId(value)
        });
        fill(slot, drawn.node);
        drawn.node.scrollIntoView({block: 'start'});
        bench.say('Comparison ready. ' + answer.headline);
    }

    return {
        bar,
        /** Folds the comparison away, so a run's result that arrives below it is in view. */
        fold() {
            if (drawn) {
                drawn.fold();
            }
        },
        /** Marks a comparison on screen as made under a configuration that is gone. */
        markStale() {
            if (drawn) {
                drawn.markStale();
            }
            showBar();
            refreshSample();
        }
    };
}

/**
 * Draws one comparison. Returns {node, fold, markStale}.
 *
 * actions.close(), actions.fill() (returns {filled, leftAlone}), actions.useBuilt(),
 * actions.useMemberId(value).
 */
export function drawComparison(answer, about, actions) {
    const notes = answer.notes || [];
    // On a comparable answer the server puts its sentence about what the comparison cannot show
    // first among the notes, once. It is set apart so that it is always in view.
    const caveat = answer.comparable && notes.length > 0 ? notes[0] : null;
    const otherNotes = caveat ? notes.slice(1) : notes;

    const head = el('summary', {class: 'crd-cmp__summary'},
        el('span', {class: 'crd-cmp__title'}, 'Your request beside the one this tool builds'),
        el('span', {class: 'quiet'}, answer.headline));
    const body = el('div', {class: 'crd-cmp__body'});
    const root = el('details', {class: 'crd-cmp', open: true, aria: {label: 'Comparison of two CRD requests'}}, head, body);

    body.append(
        el('div', {class: 'crd-cmp__head'},
            el('p', {class: 'small'}, 'Compared: ', about.words, '. ', el('strong', null, 'Nothing was sent to the service and nothing was recorded.')),
            button('Close the comparison', actions.close, {class: 'button--quiet button--small'})),
        el('p', {class: 'crd-cmp__headline'}, answer.headline));
    if (caveat) {
        body.append(el('p', {class: 'crd-cmp__caveat'}, caveat));
    }
    if (!answer.comparable) {
        body.append(el('p', {class: 'crd-cmp__whynot', role: 'alert'}, answer.whyNot));
    }
    // A pasted request for another hook is compared all the same, and every difference below is
    // then between requests for two different hooks. The two hooks are the server's own values of
    // its difference at /hook, set out here so the tester reads that first.
    const hookDifference = (answer.differences || []).find(difference => difference.toolPointer === '/hook' && difference.yourPointer === '/hook');
    if (answer.hook && hookDifference) {
        body.append(el('p', {class: 'crd-cmp__hooks'}, 'Your request is for the hook ', requestValue(hookDifference.yourValue),
            ', and this tool built its request for the hook ', code(answer.hook), '. These are requests for two different hooks.'));
    } else if (answer.hook) {
        body.append(el('p', {class: 'small'}, 'This tool built its request for the hook ', code(answer.hook), '.'));
    }
    if (otherNotes.length > 0) {
        body.append(el('section', {class: 'crd-cmp__part'},
            el('h3', {class: 'sub-heading'}, 'About this comparison (' + otherNotes.length + ')'),
            el('ul', {class: 'note-list'}, otherNotes.map(note => el('li', null, note)))));
    }

    const caseRead = casePart(answer, actions);
    body.append(caseRead);

    const docs = documents(answer);
    if (answer.comparable) {
        const list = differenceList(answer, docs);
        docs.onChoose((index, from) => list.reveal(index, from));
        body.append(el('section', {class: 'crd-cmp__part crd-cmp__workspace'}, docs.node, list.node));
    } else {
        body.append(el('section', {class: 'crd-cmp__part'}, docs.node));
    }

    return {
        node: root,
        fold() {
            root.open = false;
        },
        markStale() {
            if (!root.querySelector('.result__stale')) {
                body.prepend(el('p', {class: 'result__stale'},
                    'This comparison was made before the configuration changed. Placeholders were filled in from the configuration '
                    + 'that is gone: compare again to see it under the one now active.'));
            }
        }
    };
}

/* ---------- what was read out of the pasted request ---------- */

function casePart(answer, actions) {
    const sources = answer.caseSource || [];
    const notFound = answer.notFound || [];
    const fromYours = sources.filter(source => source.source === 'YOUR_REQUEST');
    const part = el('section', {class: 'crd-cmp__part'});
    const outcome = el('div', {class: 'crd-cmp__filled', role: 'status', aria: {live: 'polite'}});

    part.append(el('div', {class: 'crd-cmp__part-head'},
        el('h3', {class: 'sub-heading'}, typeof answer.toolBody === 'string'
            ? 'The case this tool built its request for' : 'The case as far as it could be put together: this tool built no request from it'),
        fromYours.length > 0 ? button('Fill the form from this request', () => {
            const report = actions.fill();
            fill(outcome,
                el('p', null, el('strong', null, report.filled.length > 0 ? 'Filled: ' : 'Nothing was filled.'),
                    report.filled.length > 0 ? report.filled.join('; ') + '.' : null),
                report.leftAlone.length > 0 ? el('p', null, el('strong', null, 'Left alone: '), report.leftAlone.join('; ') + '.') : null,
                el('p', null, 'The form now describes the case of your request. To send the request this tool builds for it, choose ',
                    '"Built by this tool from the form", then "Send CRD request".', ' ',
                    button('Switch to the request built by this tool', actions.useBuilt, {class: 'button--text'})));
        }) : null));
    part.append(el('p', {class: 'small quiet'}, sources.length === 0
        ? 'The comparison names no value of the case.'
        : 'Where the form had a value it was used. Where it had none, the value was read out of your request, from the place shown.'));
    part.append(outcome);

    if (sources.length > 0) {
        const read = fromYours.length;
        part.append(el('details', {class: 'fold crd-cmp__values'},
            el('summary', null, 'Each value and where it came from',
                el('span', {class: 'quiet'}, sources.length + (sources.length === 1 ? ' value, ' : ' values, ') + read + ' read from your request')),
            table([{text: 'Value', class: 'col-22'}, {text: 'Taken from', class: 'col-13'}, {text: 'What was used', class: 'col-30'},
            'Where it was read'], sources.map(source => [
            source.label || code(source.field),
            Object.prototype.hasOwnProperty.call(SOURCES, source.source) ? SOURCES[source.source] : code(String(source.source)),
            requestValue(source.value),
            source.path ? [code(source.path), source.note ? el('div', {class: 'small quiet'}, source.note) : null]
                : source.note ? el('span', {class: 'small quiet'}, source.note) : el('span', {class: 'quiet small'}, 'the form')
        ]), {class: 'crd-cmp__case'})));
    }

    if (notFound.length > 0) {
        // Where a value was looked for is said in words that can hold a path, so it is set as text.
        part.append(el('details', {class: 'fold crd-cmp__values', open: notFound.length <= 4},
            el('summary', null, 'Looked for in your request and not found', el('span', {class: 'quiet'}, String(notFound.length))),
            el('ul', {class: 'crd-cmp__notfound'}, notFound.map(entry => el('li', null,
                el('strong', null, entry.label || entry.field),
                entry.lookedIn ? el('div', {class: 'small'}, 'Looked for: ', entry.lookedIn) : null,
                entry.note ? el('div', {class: 'small quiet'}, entry.note) : null,
                (entry.candidates || []).length > 0 ? candidates(entry, actions) : null)))));
    }
    return part;
}

/** Values that disagree with each other, each with its place, for the tester to choose one. */
function candidates(entry, actions) {
    const isMemberId = entry.field === 'member.memberId';
    return el('div', {class: 'crd-cmp__candidates'},
        el('p', {class: 'small'}, 'These disagree, so none was used. Choose one and put it on the form:'),
        table(['Value', 'Where it is', ''], entry.candidates.map(candidate => [
            requestValue(candidate.value),
            code(candidate.path),
            isMemberId && candidate.value && candidate.value.presence === 'VALUE'
                ? button('Use this member ID', () => actions.useMemberId(candidate.value.value),
                    {class: 'button--text', aria: {label: 'Use the member ID read from ' + candidate.path}})
                : null
        ])));
}

/* ---------- a value in a request ---------- */

// The words for a value that is not there, said of a request and not of a response.
const EMPTY_WORDS = {string: 'empty text', array: 'empty list', object: 'empty object'};

/**
 * One value of a difference: what one request holds at the element. Absent, an explicit null and
 * an empty text, list or object are each said in their own words with the marks the page uses
 * for them, so none of them is a blank and none looks like a value.
 */
export function requestValue(value, type) {
    if (!value || typeof value !== 'object' || value.presence === undefined || value.presence === 'ABSENT') {
        return el('span', {class: 'presence presence--absent', title: 'This request does not have this element.'}, 'not in this request');
    }
    if (value.presence === 'NULL') {
        return el('span', {class: 'presence presence--null', title: 'This request has the element with an explicit null.'}, 'null');
    }
    if (value.presence === 'EMPTY') {
        return el('span', {class: 'presence presence--empty', title: 'This request has the element and it is empty.'},
            type && EMPTY_WORDS[type] ? EMPTY_WORDS[type] : 'empty');
    }
    if (value.presence !== 'VALUE') {
        return el('span', {class: 'presence presence--other'}, String(value.presence));
    }
    const text = String(value.value);
    return el('code', {class: 'literal crd-value' + (text.length > 160 ? ' crd-value--long' : '')}, text);
}

/* ---------- the differences ---------- */

function differenceList(answer, docs) {
    const differences = answer.differences || [];
    const counts = answer.counts || [];
    const rows = new Map();
    const groups = new Map();
    const node = el('div', {class: 'crd-diffs'});

    if (differences.length === 0) {
        node.append(el('p', null, 'There is nothing to list.'));
    }
    for (const group of counts) {
        const members = differences.map((difference, index) => ({difference, index})).filter(entry => entry.difference.group === group.group);
        const details = el('details', {class: 'crd-group fold', open: !STARTS_CLOSED.has(group.group) && (members.length > 0 || group === counts[0])},
            el('summary', null, group.label, el('span', {class: 'quiet'}, members.length === 1 ? '1 difference' : members.length + ' differences')),
            group.description ? el('p', {class: 'small quiet crd-group__about'}, group.description) : null,
            members.length === 0 ? el('p', {class: 'small'}, 'None.')
                : el('ol', {class: 'crd-group__list'}, members.map(entry => {
                    const row = differenceRow(entry.difference, entry.index, docs);
                    rows.set(entry.index, row);
                    return row;
                })));
        groups.set(group.group, details);
        node.append(details);
    }
    // A difference in a group the server did not count is still listed: nothing is dropped.
    const uncounted = differences.map((difference, index) => ({difference, index})).filter(entry => !groups.has(entry.difference.group));
    if (uncounted.length > 0) {
        node.append(el('details', {class: 'crd-group fold', open: true},
            el('summary', null, 'In a group this page does not know', el('span', {class: 'quiet'}, String(uncounted.length))),
            el('ol', {class: 'crd-group__list'}, uncounted.map(entry => {
                const row = differenceRow(entry.difference, entry.index, docs);
                rows.set(entry.index, row);
                return row;
            }))));
    }

    return {
        node,
        /**
         * Opens the group of a difference and marks its row as chosen. Chosen in a document, the
         * row is brought into view below the documents; chosen in the list, it stays where the
         * tester chose it, unless the documents had to be brought into view instead.
         */
        reveal(index, from) {
            const row = rows.get(index);
            if (!row) {
                return;
            }
            const group = row.closest('details');
            if (group) {
                group.open = true;
            }
            for (const other of rows.values()) {
                other.classList.toggle('crd-diff--chosen', other === row);
                other.querySelector('.crd-diff__go').setAttribute('aria-current', other === row ? 'true' : 'false');
            }
            if (from === 'list' && !docs.pinned()) {
                return;
            }
            keepInView(row, docs.pinned() ? docs.node : null);
        }
    };
}

/** The nearest ancestor that scrolls, or the page. */
function scrollerOf(node) {
    for (let at = node.parentElement; at; at = at.parentElement) {
        const style = getComputedStyle(at);
        if (/(auto|scroll)/.test(style.overflowY) && at.scrollHeight > at.clientHeight) {
            return at;
        }
    }
    return document.scrollingElement;
}

/**
 * Scrolls as little as it can so that a row is in view and not under what is pinned above it.
 * scrollIntoView knows nothing of a pinned element, and leaves a row it brings down from above
 * hidden behind the documents.
 */
function keepInView(row, pinnedAbove) {
    const scroller = scrollerOf(row);
    const page = scroller === document.scrollingElement;
    const box = page ? {top: 0, bottom: window.innerHeight} : scroller.getBoundingClientRect();
    const margin = 8;
    const top = Math.max(box.top, pinnedAbove ? pinnedAbove.getBoundingClientRect().bottom : box.top) + margin;
    const bottom = box.bottom - margin;
    const at = row.getBoundingClientRect();
    let delta = 0;
    if (at.top < top) {
        delta = at.top - top;
    } else if (at.bottom > bottom) {
        // A row taller than the room left is shown from its top.
        delta = Math.min(at.bottom - bottom, at.top - top);
    }
    if (delta !== 0) {
        if (page) {
            window.scrollBy(0, delta);
        } else {
            scroller.scrollTop += delta;
        }
    }
}

/** Says which resource a path is in, from the resource type the path itself names in brackets. */
function resourceOf(path) {
    const found = /\b(?:entry|prefetch)\[([A-Z][A-Za-z]+)(?: #\d+)?\]/.exec(path);
    return found ? found[1] : null;
}

function differenceRow(difference, index, docs) {
    const resource = resourceOf(difference.path);
    const go = el('button', {type: 'button', class: 'crd-diff__go', aria: {current: 'false'}, dataset: {diff: String(index)},
        on: {click: () => docs.chooseFromList(index)}},
        el('span', {class: 'crd-diff__number'}, String(index + 1)),
        el('span', {class: 'crd-diff__where'}, code(difference.path),
            resource ? el('span', {class: 'small quiet crd-diff__in'}, 'in the ' + resource) : null),
        el('span', {class: 'crd-diff__kind'}, difference.kindLabel || difference.kind));
    return el('li', {class: 'crd-diff', dataset: {diff: String(index)}},
        go,
        el('div', {class: 'crd-diff__body'},
            difference.what
                ? el('p', {class: 'crd-diff__what'}, difference.what)
                : el('p', {class: 'crd-diff__what'}, el('span', {class: 'presence presence--none',
                    title: 'The comparison has no description of this element, and this page gives none of its own.'}, 'no description')),
            el('div', {class: 'crd-diff__values'},
                el('div', {class: 'crd-diff__side'}, el('p', {class: 'crd-diff__who'}, 'This tool\'s request'),
                    requestValue(difference.toolValue, difference.toolType)),
                el('div', {class: 'crd-diff__side'}, el('p', {class: 'crd-diff__who'}, 'Your request'),
                    requestValue(difference.yourValue, difference.yourType))),
            el('p', {class: 'crd-diff__explanation'}, difference.explanation)));
}

/* ---------- the two documents ---------- */

/**
 * The two requests side by side, each as numbered lines. Every difference is marked on the lines
 * it covers in each. Choosing a difference marks it as chosen and brings it into view in both.
 * The two panes scroll together where their lines correspond, and each scrolls on its own when
 * the tester switches that off. A pane never holds on to the page: when it has scrolled to its
 * end, the page scrolls on.
 */
function documents(answer) {
    const differences = answer.comparable ? answer.differences || [] : [];
    const tool = pane('This tool\'s request', answer.toolBody, answer.comparable
        ? null : 'This tool did not build its request, so there is nothing to show on this side.');
    const yours = pane('Your request', answer.yourBody, null);
    let chosen = null;
    let chooseListener = () => {};
    const where = el('p', {class: 'small crd-docs__where', role: 'status', aria: {live: 'polite'}});

    // Marks every difference on the lines it covers. Clicking a marked line chooses its difference.
    differences.forEach((difference, index) => {
        tool.mark(difference.toolPointer, index);
        yours.mark(difference.yourPointer, index);
    });

    const together = el('input', {type: 'checkbox', id: 'crd-docs-together', checked: true});
    const anchors = answer.comparable ? correspondingLines(tool, yours, differences) : [];
    let syncing = false;
    const follow = (from, to, forward) => () => {
        if (syncing || !together.checked || anchors.length === 0) {
            return;
        }
        syncing = true;
        to.scrollToLine(mapLine(anchors, from.topLine(), forward), 0);
        // The second pane's own scroll event arrives after this one: it is not passed back.
        window.requestAnimationFrame(() => { syncing = false; });
    };
    tool.scroller.addEventListener('scroll', follow(tool, yours, true));
    yours.scroller.addEventListener('scroll', follow(yours, tool, false));

    const step = delta => {
        if (differences.length === 0) {
            return;
        }
        const next = chosen === null ? (delta > 0 ? 0 : differences.length - 1) : (chosen + delta + differences.length) % differences.length;
        choose(next, 'documents');
        chooseListener(next, 'documents');
    };

    function choose(index, from) {
        chosen = index;
        const difference = differences[index];
        tool.choose(index);
        yours.choose(index);
        syncing = true;
        const toolAt = tool.place(difference.toolPointer, yours.pointerLines, difference.yourPointer, differences, 'tool');
        const yoursAt = yours.place(difference.yourPointer, tool.pointerLines, difference.toolPointer, differences, 'yours');
        window.requestAnimationFrame(() => { syncing = false; });
        fill(where, el('strong', null, 'Difference ' + (index + 1) + ' of ' + differences.length + ': '), code(difference.path),
            toolAt.exact ? null : el('span', null, ' Not in this tool\'s request: its pane shows the nearest element both requests have.'),
            yoursAt.exact ? null : el('span', null, ' Not in your request: its pane shows the nearest element both requests have.'));
        // Pinned, the documents are in view whenever the list is. Where they are not pinned, a
        // difference chosen in the list brings them into view, which is what choosing it is for.
        if (from === 'list' && !pinned()) {
            node.scrollIntoView({block: 'nearest'});
        }
    }

    function pinned() {
        return getComputedStyle(node).position === 'sticky';
    }

    const node = el('div', {class: 'crd-docs'},
        el('div', {class: 'crd-docs__bar'},
            answer.comparable ? el('label', {class: 'choice', htmlFor: 'crd-docs-together'}, together,
                'Scroll the two together where their lines correspond') : el('span', {class: 'small quiet'}, 'Both texts, as they stand.'),
            answer.comparable && differences.length > 0 ? el('span', {class: 'crd-docs__step'},
                button('Previous difference', () => step(-1), {class: 'button--quiet button--small'}),
                button('Next difference', () => step(1), {class: 'button--quiet button--small'})) : null),
        where,
        el('div', {class: 'crd-docs__panes'}, tool.node, yours.node),
        answer.comparable && differences.length > 0 ? el('p', {class: 'small quiet crd-docs__key'},
            el('span', {class: 'crd-docs__keymark', aria: {hidden: 'true'}}), ' A bar at the left marks a line that differs. ',
            el('span', {class: 'crd-docs__keymark crd-docs__keymark--chosen', aria: {hidden: 'true'}}),
            ' A heavy bar and bold text mark the difference chosen. Choose a marked line to find its difference in the list.') : null);

    for (const side of [tool, yours]) {
        side.scroller.addEventListener('click', event => {
            const line = event.target.closest('.crd-line[data-diffs]');
            if (line) {
                const index = Number(line.dataset.diffs.split(' ')[0]);
                choose(index, 'documents');
                chooseListener(index, 'documents');
            }
        });
    }

    return {
        node,
        pinned,
        /** Chooses a difference from its row in the list. */
        chooseFromList(index) {
            choose(index, 'list');
            chooseListener(index, 'list');
        },
        onChoose(listener) {
            chooseListener = listener;
        }
    };
}

/** One request as numbered lines in a pane of its own. */
function pane(title, text, missing) {
    const scroller = el('div', {class: 'crd-doc__lines', tabIndex: 0, role: 'region', aria: {label: title + ', line by line'}});
    const node = el('div', {class: 'crd-doc'}, el('p', {class: 'crd-doc__title'}, title), scroller);
    const lines = [];
    const has = typeof text === 'string';
    const pointerLines = has ? lineMap(text) : new Map();
    if (!has) {
        scroller.append(el('p', {class: 'crd-doc__missing'}, missing || 'Nothing to show.'));
    } else {
        const all = text.split('\n');
        const width = String(all.length).length;
        const frag = document.createDocumentFragment();
        all.forEach((content, index) => {
            const line = el('div', {class: 'crd-line'},
                el('span', {class: 'crd-line__number', aria: {hidden: 'true'}}, String(index + 1).padStart(width, ' ')),
                el('span', {class: 'crd-line__text'}, content === '' ? ' ' : content));
            lines.push(line);
            frag.append(line);
        });
        scroller.append(frag);
    }
    let chosenLines = [];

    function linesOf(pointer) {
        if (pointer === null || pointer === undefined) {
            return null;
        }
        return pointerLines.get(pointer) || null;
    }

    return {
        node,
        scroller,
        pointerLines,
        mark(pointer, index) {
            const range = linesOf(pointer);
            if (!range) {
                return;
            }
            for (let at = range.start; at <= range.end && at < lines.length; at += 1) {
                const line = lines[at];
                line.classList.add('crd-line--differs');
                line.dataset.diffs = line.dataset.diffs ? line.dataset.diffs + ' ' + index : String(index);
            }
        },
        choose(index) {
            for (const line of chosenLines) {
                line.classList.remove('crd-line--chosen');
            }
            chosenLines = lines.filter(line => line.dataset.diffs && line.dataset.diffs.split(' ').includes(String(index)));
            for (const line of chosenLines) {
                line.classList.add('crd-line--chosen');
            }
        },
        /**
         * Brings the element into view. When this request does not have it, the pane shows the
         * nearest element that holds it in the other request and exists in this one, found through
         * the differences whose two places are known, else by the same pointer. Returns {exact}.
         */
        place(pointer, otherLines, otherPointer, differences, side) {
            const range = linesOf(pointer);
            if (range) {
                this.scrollToLine(range.start, 0.25);
                return {exact: true};
            }
            const near = nearestHere(otherPointer, differences, side, pointerLines);
            if (near !== null) {
                this.scrollToLine(pointerLines.get(near).start, 0.25);
            }
            return {exact: false};
        },
        scrollToLine(index, fraction) {
            if (lines.length === 0) {
                return;
            }
            const line = lines[Math.max(0, Math.min(lines.length - 1, Math.round(index)))];
            scroller.scrollTop = Math.max(0, line.offsetTop - scroller.clientHeight * fraction);
        },
        topLine() {
            if (lines.length === 0) {
                return 0;
            }
            const height = lines[0].offsetHeight || 1;
            return (scroller.scrollTop) / height;
        }
    };
}

/**
 * The nearest element of this request that holds what only the other request has. A difference
 * whose two places are both known says which element of this request matches an element of the
 * other; the deepest such pair above the element is used, and failing that, the deepest pointer
 * above it that this request also has.
 */
function nearestHere(otherPointer, differences, side, here) {
    if (!otherPointer) {
        return here.has('') ? '' : null;
    }
    const mine = side === 'tool' ? 'toolPointer' : 'yourPointer';
    const theirs = side === 'tool' ? 'yourPointer' : 'toolPointer';
    for (const above of ancestors(otherPointer)) {
        const pair = differences.find(difference => difference[theirs] === above && difference[mine]);
        if (pair && here.has(pair[mine])) {
            return pair[mine];
        }
        if (here.has(above)) {
            return above;
        }
    }
    return null;
}

/**
 * Pairs of lines, one in each request, that hold the same element: the two places of every
 * difference that has both, and every pointer the two requests share. Only pairs that run in the
 * same order in both are kept, so scrolling one pane never sends the other backwards.
 */
function correspondingLines(tool, yours, differences) {
    const pairs = [];
    for (const [pointer, range] of tool.pointerLines) {
        const other = yours.pointerLines.get(pointer);
        if (other) {
            pairs.push([range.start, other.start]);
        }
    }
    for (const difference of differences) {
        const a = difference.toolPointer ? tool.pointerLines.get(difference.toolPointer) : null;
        const b = difference.yourPointer ? yours.pointerLines.get(difference.yourPointer) : null;
        if (a && b) {
            pairs.push([a.start, b.start]);
        }
    }
    pairs.sort((one, other) => one[0] - other[0] || one[1] - other[1]);
    return increasing(pairs);
}

/** The longest run of pairs whose second lines rise with their first. */
function increasing(pairs) {
    const tails = [];
    const tailIndex = [];
    const previous = new Array(pairs.length).fill(-1);
    pairs.forEach((pair, index) => {
        let low = 0;
        let high = tails.length;
        while (low < high) {
            const middle = (low + high) >> 1;
            if (tails[middle] < pair[1]) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        tails[low] = pair[1];
        tailIndex[low] = index;
        previous[index] = low > 0 ? tailIndex[low - 1] : -1;
    });
    const out = [];
    let at = tailIndex[tails.length - 1];
    while (at !== undefined && at >= 0) {
        out.push(pairs[at]);
        at = previous[at];
    }
    return out.reverse();
}

/** The line of the other pane that corresponds to a line of this one, between the nearest pairs. */
function mapLine(anchors, line, forward) {
    const from = forward ? 0 : 1;
    const to = forward ? 1 : 0;
    let below = null;
    let above = null;
    for (const pair of anchors) {
        if (pair[from] <= line) {
            below = pair;
        } else {
            above = pair;
            break;
        }
    }
    if (!below) {
        return above ? above[to] - (above[from] - line) : line;
    }
    if (!above || above[from] === below[from]) {
        return below[to] + (line - below[from]);
    }
    const share = (line - below[from]) / (above[from] - below[from]);
    return below[to] + share * (above[to] - below[to]);
}
