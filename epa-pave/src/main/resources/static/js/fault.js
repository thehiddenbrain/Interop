// How a failure is shown, the same on every view.
//
// The words of an error are the server's (ErrorView: heading, title, action), so the heading can
// say what happened on either profile. This page words only what the server cannot: that it did
// not answer at all, or did not answer in JSON. A failure of this tool shows its reference and its
// cause together, with one button that copies both, because the server log that holds the rest
// can be on a machine the tester never looks at (FaultReportFrontendTest).

import {el, chip, fold, copyButton, firstSentence} from './dom.js';

/** Said when PAVE gave no answer at all: under the top bar, and in place of a result. */
export const UNREACHABLE_LINE = 'PAVE is not answering. Is it still running?';

/** Said when an answer could not be read as JSON: what PAVE gives when its classes were rebuilt under it while it ran. */
export function notJsonLine(status) {
    return 'PAVE did not answer as expected (HTTP ' + status + '). If PAVE runs from STS, stop it and start it again.';
}

/** Said under every refusal and failure while PAVE's files changed on disk since it started (I-12). */
export const FILES_CHANGED_LINE = "PAVE's files changed on disk since it started, which can cause this. Stop PAVE and start it again, then try once more.";

// Set by the frame from each /api/mode answer. Between a pull and a restart the page can run new
// scripts against old classes, and a refusal then names a cure that cannot help.
let filesChanged = false;

/** The frame tells this module whether /api/mode says build.changedOnDisk. */
export function setFilesChanged(on) {
    filesChanged = on === true;
}

/** The line under a refusal or failure while the files changed on disk, else null. */
export function filesChangedNote() {
    return filesChanged ? el('p', {class: 'files-changed-note small'}, FILES_CHANGED_LINE) : null;
}

/** Said when this page could not do what it was asked: a defect of the page, not of the server. */
export const PAGE_FAULT_LINE = 'This page met a fault of its own. Reload the page.';

// The frame of each kind, a style and never words: a fault to report (the environment, or this
// tool), a refusal by the guardrail, an answer that said no, or a plain frame.
const FRAMES = {
    INTERNAL: 'fault', VENDOR_ERROR: 'fault', TIMEOUT: 'fault', UNREACHABLE: 'fault', MALFORMED_RESPONSE: 'fault',
    NOT_JSON: 'fault', PAGE_FAULT: 'fault', BLOCKED: 'blocked', VENDOR_REJECTED: 'stop'
};

const PRESENCE_WORDS = {ABSENT: 'not returned', NULL: 'returned as null', EMPTY: 'returned empty'};

/**
 * The error panel, open, for any error this page was given: an ErrorView, or what api.js says when
 * there was no answer it could read. In order: the heading, the title, the first sentence of the
 * action (the rest in a closed "Why"), the field errors, the issues of an OperationOutcome, and the
 * fault report when there is a reference. options.fieldsPlaced: names of fields whose errors the
 * caller shows at the field itself, left out of the list here. Null when there is no error.
 */
export function errorPanel(error, options) {
    if (!error || typeof error !== 'object') {
        return null;
    }
    // The server's own errors of kind UNREACHABLE (the service could not be reached) carry a title;
    // the page's own (PAVE itself gave no answer) carry none.
    if (error.kind === 'UNREACHABLE' && !error.title) {
        return panel('fault', null, UNREACHABLE_LINE,
            'If a run was on its way, it may or may not have been made: look for it in Audit before sending it again.');
    }
    if (error.kind === 'NOT_JSON') {
        return panel('fault', null, notJsonLine(error.status), null);
    }
    if (error.kind === 'PAGE_FAULT') {
        return panel('fault', null, PAGE_FAULT_LINE, null);
    }
    const placed = new Set(options && options.fieldsPlaced ? options.fieldsPlaced : []);
    const labelOf = options && typeof options.labelOf === 'function' ? options.labelOf : null;
    const box = panel(FRAMES[error.kind] || 'neutral', error.heading, error.title, error.action,
        typeof error.httpStatus === 'number' ? 'HTTP ' + error.httpStatus : null, labelOf ? namedFields(error.fieldErrors, labelOf) : null);
    const fieldErrors = labelOf ? [] : Array.isArray(error.fieldErrors) ? error.fieldErrors.filter(each => each && !placed.has(each.field)) : [];
    if (fieldErrors.length > 0) {
        box.append(el('ul', {class: 'notes error-fields'}, fieldErrors.map(each =>
            el('li', null, el('span', {class: 'mono'}, each.field), ' ', each.message))));
    }
    if (Array.isArray(error.issues) && error.issues.length > 0) {
        box.append(issuesTable(error.issues));
    }
    const report = faultReport(error.reference, error.cause);
    if (report) {
        box.append(report);
    }
    return box;
}

/** True for an error that is a service's own answer (4xx or 5xx), which a result card says in itself. */
export function serviceFailure(error) {
    return Boolean(error) && (error.kind === 'VENDOR_REJECTED' || error.kind === 'VENDOR_ERROR');
}

/**
 * A service's failure inside its result card, so it is said once (plan 3.2 Failures): the card
 * already has the headline, what the service said and the next step. Here: one chip per issue,
 * the issues in full in a fold, the fault report and the line about changed files.
 */
export function failureInCard(error) {
    if (!serviceFailure(error)) {
        return [];
    }
    const issues = Array.isArray(error.issues) ? error.issues : [];
    const chips = issues.map(issue => {
        const severity = issue.severity && issue.severity.presence === 'VALUE' ? String(issue.severity.value) : null;
        const code = issue.code && issue.code.presence === 'VALUE' ? String(issue.code.value) : null;
        const words = [severity, code].filter(Boolean).join(' ');
        return words ? chip(words, {tone: 'fail', mono: true}) : null;
    }).filter(Boolean);
    return [
        chips.length > 0 ? el('div', {class: 'result-line issue-chips'}, chips) : null,
        issues.length > 0 ? fold({summary: 'Issues the service sent (' + issues.length + ')', inline: true, body: () => issuesTable(issues)}) : null,
        faultReport(error.reference, error.cause),
        filesChangedNote()
    ].filter(Boolean);
}

// A refused form names at most this many fields in its panel; the rest are counted.
const MOST_FIELDS_NAMED = 3;

/**
 * The fields of a refused form by the labels the tester sees (I-3), each with its sentence: never a
 * property path. A field the page has no label for is said by its sentence alone.
 */
function namedFields(fieldErrors, labelOf) {
    const all = Array.isArray(fieldErrors) ? fieldErrors.filter(each => each && typeof each.message === 'string') : [];
    if (all.length === 0) {
        return null;
    }
    const items = all.slice(0, MOST_FIELDS_NAMED).map(each => {
        const label = labelOf(each.field);
        return el('li', null, label ? [el('strong', null, label), ' '] : null, each.message);
    });
    if (all.length > MOST_FIELDS_NAMED) {
        items.push(el('li', null, 'and ' + (all.length - MOST_FIELDS_NAMED) + ' more'));
    }
    return el('ul', {class: 'notes error-fields'}, items);
}

/**
 * What the action says after its first sentence: one sentence more is a second, quieter line, read
 * without a click (I-34: "If the url is right, the sign-in service may be down: try again later.");
 * more than that goes in a closed Why.
 */
function restOfAction(rest) {
    if (!rest) {
        return null;
    }
    return firstSentence(rest)[1] === '' ? el('p', {class: 'small muted action-rest'}, rest)
        : fold({summary: 'Why', inline: true, body: el('p', null, rest)});
}

function panel(frame, heading, title, action, status, named) {
    const [first, rest] = firstSentence(action);
    return el('div', {class: 'verdict verdict--' + frame + ' error-panel'},
        heading || status ? el('p', {class: 'verdict__label'}, heading || null, heading && status ? ', ' : null, status || null) : null,
        el('p', {class: 'verdict__headline'}, title || null),
        named || null,
        first ? el('p', {class: 'muted'}, first) : null,
        restOfAction(rest),
        filesChangedNote());
}

/**
 * The issues of an OperationOutcome, issue by issue, as the service sent them: never reduced to a
 * status and a sentence. Absent, null and empty are three different findings, each in its words.
 */
export function issuesTable(issues) {
    const head = el('tr', null, ['Severity', 'Code', 'Diagnostics', 'Details', 'Expression', 'Location'].map(name =>
        el('th', {scope: 'col'}, name)));
    const rows = issues.map(issue => el('tr', null,
        el('td', null, shown(issue.severity, true)),
        el('td', null, shown(issue.code, true)),
        el('td', null, shown(issue.diagnostics, false)),
        el('td', null, details(issue)),
        el('td', null, listed(issue.expression, issue.expressionPresence)),
        el('td', null, listed(issue.location, issue.locationPresence))));
    return el('div', {class: 'table-wrap issues'}, el('table', {class: 'table'}, el('thead', null, head), el('tbody', null, rows)));
}

/** One value the server sent as {presence, value}: the value, or the words for what was there instead. */
function shown(value, literal) {
    if (!value || typeof value !== 'object' || value.presence === undefined) {
        return chip(PRESENCE_WORDS.ABSENT, {dashed: true});
    }
    if (value.presence !== 'VALUE') {
        return chip(PRESENCE_WORDS[value.presence] || String(value.presence), {dashed: true});
    }
    return literal ? el('span', {class: 'mono'}, String(value.value)) : String(value.value);
}

function details(issue) {
    if (issue.detailsPresence && issue.detailsPresence !== 'VALUE') {
        return chip(PRESENCE_WORDS[issue.detailsPresence] || String(issue.detailsPresence), {dashed: true});
    }
    const codes = Array.isArray(issue.detailsCodes) ? issue.detailsCodes : [];
    return [shown(issue.detailsText, false), codes.length > 0 ? el('div', {class: 'small'}, codes.map(code => [shown(code, true), ' '])) : null];
}

function listed(values, presence) {
    if (Array.isArray(values) && values.length > 0) {
        return values.map((value, index) => [index > 0 ? ', ' : null, shown(value, true)]);
    }
    if (presence && presence !== 'VALUE') {
        return chip(PRESENCE_WORDS[presence] || String(presence), {dashed: true});
    }
    if (presence === 'VALUE') {
        // Held, but not as a list of values this page can draw. It stays among the fields returned but not interpreted.
        return el('span', {class: 'small'}, 'not a list');
    }
    return chip(PRESENCE_WORDS.ABSENT, {dashed: true});
}

/**
 * The reference of a failure of this tool with its cause, as the server log names them, and one
 * button that copies both, so reporting it is one paste. Null when there is no reference.
 */
export function faultReport(reference, cause) {
    if (!reference) {
        return null;
    }
    return el('div', {class: 'fault-report'},
        el('div', {class: 'fault-report__row'}, el('span', null, 'Reference'), el('span', {class: 'mono'}, String(reference))),
        cause ? el('div', {class: 'fault-report__row'}, el('span', null, 'Cause, as the server log names it:'),
            el('span', {class: 'mono'}, String(cause))) : null,
        el('div', {class: 'fault-report__copy'},
            copyButton(cause ? 'Copy reference and cause' : 'Copy reference', () => faultText(reference, cause), {small: true})));
}

/** The text the fault report copies: one line per row, label and value together. */
export function faultText(reference, cause) {
    return 'Reference ' + reference + (cause ? '\nCause, as the server log names it: ' + cause : '');
}

/**
 * A server sentence about a failure of this tool, for a screen that shows faultReport under it.
 * The server writes the reference into the sentence so that it stands alone in a CSV, which has no
 * column for the cause. On the screen the reference stands in the report, beside its cause, so it
 * is not given a second time, and a sentence that sends the reader to the reason for it is sent to
 * the report instead. A sentence with no reference to strip is left exactly as the server wrote it.
 */
export function withoutReference(sentence, fault) {
    if (!sentence || !fault || !fault.reference) {
        return sentence;
    }
    return sentence.split(' Reference ' + fault.reference + '.').join('')
        .split('Report the reference ' + fault.reference + '.')
        .join(fault.cause ? 'Report the reference and the cause below.' : 'Report the reference below.')
        .split('The reason gives the reference.')
        .join(fault.cause ? 'The reference and the cause are below the reason.' : 'The reference is below the reason.');
}
